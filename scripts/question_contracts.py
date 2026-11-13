"""Executable Cogniva V2 question, suggestion, compatibility, and evaluation contracts."""
import argparse
from datetime import date
from decimal import Decimal
from functools import lru_cache
import hashlib
import json

from jsonschema import Draft202012Validator, FormatChecker
import rfc8785

from knowledge_catalog import ROOT, read


def require(condition, message):
    if not condition:
        raise ValueError(message)


@lru_cache(maxsize=None)
def schema_validator(kind):
    bundle = read(ROOT / "shared/schemas/question-contracts.schema.json")
    schema = {"$schema": bundle["$schema"], "$ref": f"#/$defs/{kind}", "$defs": bundle["$defs"]}
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def shape(kind, record):
    errors = sorted(schema_validator(kind).iter_errors(record), key=lambda e: str(list(e.path)))
    if errors:
        raise ValueError(f"{kind} schema {list(errors[0].path)}: {errors[0].message}")


def content_revision(question):
    fields = ["question_type", "question", "answers", "correct_answer_indices", "grading"]
    return hashlib.sha256(rfc8785.dumps({key: question[key] for key in fields})).hexdigest()


def human_review(provenance):
    return provenance is not None and provenance["actor"]["type"] == "HUMAN" and provenance["method"] == "MANUAL_REVIEW"


def taxonomy_links(links, catalog, confirmed):
    topics = {t["topic_id"]: t for t in catalog["topics"]}
    subjects = {s["subject_id"]: s for s in catalog["subjects"]}
    ids = set()
    link_ids = set()
    for link in links:
        topic = topics.get(link["topic_id"])
        require(topic is not None and topic["subject_id"] == link["subject_id"], "invalid taxonomy link")
        require(topic["status"] == "ACTIVE" and subjects[link["subject_id"]]["status"] == "ACTIVE", "inactive taxonomy link")
        require(link["topic_id"] not in ids, "duplicate classified topic")
        ids.add(link["topic_id"])
        if link["role"] == "PRIMARY":
            require(link["coverage"] == "DIRECT", "primary link must be direct")
        if confirmed:
            require(human_review(link["provenance"]), "confirmed classification requires human review")
            require(link["link_id"] not in link_ids, "duplicate classification link_id")
            link_ids.add(link["link_id"])
    return sum(link["role"] == "PRIMARY" for link in links)


def validate_question(q, catalog):
    shape("question", q)
    require(q["content_revision"] == content_revision(q), "content revision mismatch")
    require(bool(q["question"].strip()), "empty question")
    answers = q["answers"]
    require(all(a.strip() for a in answers), "blank answer option")
    require(len({a.strip() for a in answers}) == len(answers), "duplicate answer options")
    if q["question_type"] != "OPEN_ENDED":
        index = q["correct_answer_indices"][0]
        require(0 <= index < len(answers), "correct_answer_indices out of bounds")
        require(q["correct_answers"] == [answers[index]], "textual and indexed answers disagree")

    classification = q["classification"]
    require(1 <= classification["catalog_version"] <= catalog["catalog_version"], "unknown catalog version")
    primaries = taxonomy_links(classification["links"], catalog, True)
    require(primaries <= 1, "multiple primary links")
    if classification["state"] == "CONFIRMED":
        require(primaries == 1, "confirmed classification needs one primary link")
    if classification["links"]:
        require(classification["revision"] > 0, "confirmed links require a revision")
    primary = next((l for l in classification["links"] if l["role"] == "PRIMARY"), None)
    for legacy, field in (("canonical_subject_id", "subject_id"), ("canonical_topic_id", "topic_id")):
        if legacy in q and q[legacy] is not None:
            require(primary is not None and q[legacy] == primary[field], "legacy canonical mirror disagrees")

    validity = q["validity"]
    require((validity["scope"] == "CURRENT_KNOWLEDGE") == (validity["context_date"] is None), "historical validity needs explicit context date")
    if validity["status"] in ("CURRENT", "OBSOLETE"):
        require(validity["revision"] > 0 and human_review(validity["decision"]), "validity decision requires reviewed revision")
        require(validity["evaluated_content_revision"] == q["content_revision"], "stale validity decision")
        if validity["basis"] != "TIMELESS":
            require(bool(validity["decision"]["evidence"]), "time-sensitive validity requires evidence")
    if validity["status"] == "CURRENT" and validity["basis"] == "LEGAL":
        require(validity["review_due_on"] is not None, "current legal question needs review deadline")
    if validity["review_due_on"] and validity["reference_date"]:
        require(validity["review_due_on"] >= validity["reference_date"], "review deadline precedes review")
    if any(r in ("LAW_REPEALED", "LAW_AMENDED") for r in validity["reason_codes"]):
        require(validity["basis"] == "LEGAL", "legal reason on nonlegal validity")

    grading = q["grading"]
    require(grading["pass_score"] <= grading["max_score"], "passing score exceeds maximum")
    if q["question_type"] == "OPEN_ENDED":
        rubric = grading["rubric"]
        ids = [c["criterion_id"] for c in rubric["criteria"]]
        require(len(ids) == len(set(ids)), "duplicate rubric criterion")
        require(
            sum(Decimal(str(c["max_points"])) for c in rubric["criteria"]) == Decimal(str(grading["max_score"])),
            "rubric total differs from max_score",
        )
        require(all(t.strip() for t in grading["expected_responses"]), "empty reference answer")
        engine = grading["engine"]
        if grading["strategy"] == "RUBRIC":
            require(engine is None, "manual rubric cannot declare automatic engine")
        elif grading["strategy"] == "AI":
            require(
                engine is not None
                and engine["provider"] == "xai"
                and all(engine[k] for k in ("model", "prompt_id", "prompt_version")),
                "AI grading needs provider/model/prompt version",
            )
            require(
                all(engine[k] is None for k in ("semantic_model_id", "semantic_model_revision", "calibration_id")),
                "AI rubric cannot masquerade as semantic calibration",
            )
        elif grading["strategy"] == "SEMANTIC":
            require(
                engine is not None
                and all(engine[k] for k in ("semantic_model_id", "semantic_model_revision", "calibration_id")),
                "semantic grading needs versioned calibrated engine",
            )
            require(
                all(engine[k] is None for k in ("provider", "model", "prompt_id", "prompt_version")),
                "semantic engine cannot declare AI generation metadata",
            )
    return q


def practice_eligible(q, mode="GENERAL", as_of=None):
    """Normative eligibility definition for practice selection."""
    require(as_of is not None, "eligibility needs explicit reference date")
    if q.get("question_type") == "OPEN_ENDED":
        return False
    validity = q["validity"]
    if q["status"] != "ACTIVE" or validity["status"] in ("OBSOLETE", "UNDER_REVIEW"):
        return False
    historical = validity["scope"] == "EXPLICIT_HISTORICAL_DATE"
    if historical != (mode == "HISTORICAL_ONLY"):
        return False
    if validity["review_due_on"] and date.fromisoformat(validity["review_due_on"]) < as_of:
        return False
    return mode != "CURRENT_ONLY" or validity["status"] == "CURRENT"


def validate_suggestion(s, question, catalog):
    shape("suggestion", s)
    require(s["question_id"] == question["_id"]["$oid"], "suggestion references another question")
    require(1 <= s["catalog_version"] <= catalog["catalog_version"], "suggestion catalog mismatch")
    require(taxonomy_links(s["proposed_links"], catalog, False) == 1, "suggestion must propose one primary link")
    current = (
        s["question_content_revision"] == question["content_revision"]
        and s["expected_classification_revision"] == question["classification"]["revision"]
    )
    if s["status"] in ("PENDING", "ACCEPTED"):
        require(
            current
            or s["status"] == "ACCEPTED"
            and s["resolution"] is not None
            and s["resolution"]["resulting_classification_revision"] == question["classification"]["revision"]
            and s["question_content_revision"] == question["content_revision"],
            "stale suggestion must be marked STALE",
        )
    if s["status"] in ("ACCEPTED", "REJECTED"):
        require(s["resolution"] is not None and s["resolution"]["actor"]["type"] == "HUMAN", "suggestion resolution requires human")
    if s["status"] == "PENDING":
        require(s["resolution"] is None, "pending suggestion cannot have a resolution")
    if s["status"] == "REJECTED":
        require(s["resolution"]["resulting_classification_revision"] is None, "rejection cannot claim applied classification")
    if s["status"] == "ACCEPTED":
        require(
            s["resolution"]["resulting_classification_revision"] == s["expected_classification_revision"] + 1,
            "acceptance must increment classification revision",
        )
        confirmed = {(l["subject_id"], l["topic_id"], l["role"], l["coverage"]) for l in question["classification"]["links"]}
        require(
            all((l["subject_id"], l["topic_id"], l["role"], l["coverage"]) in confirmed for l in s["proposed_links"]),
            "accepted proposal absent from confirmed links",
        )
        accepted_topics = {l["topic_id"] for l in s["proposed_links"]}
        require(
            all(
                l["provenance"]["source_suggestion_id"] == s["_id"]
                for l in question["classification"]["links"]
                if l["topic_id"] in accepted_topics
            ),
            "accepted classification lost suggestion provenance",
        )
    return s


def validate_compatibility(c, question, catalog, exam):
    shape("compatibility", c)
    require(
        c["question_id"] == question["_id"]["$oid"] and c["question_content_revision"] == question["content_revision"],
        "stale compatibility question",
    )
    require(1 <= c["catalog_version"] <= catalog["catalog_version"], "compatibility catalog mismatch")
    require(
        c["owner_user_id"] == exam["owner_user_id"]
        and c["exam_id"] == exam["exam_id"]
        and c["exam_revision"] == exam["revision"],
        "compatibility owner/exam revision mismatch",
    )
    component = next((s for s in exam["components"] if s["component_id"] == c["component_id"]), None)
    require(component is not None, "compatibility component absent")
    require(len(set(c["matched_topic_ids"])) == len(c["matched_topic_ids"]), "duplicate matched topics")
    if c["relation"] == "DIRECT":
        direct = {
            l["topic_id"]
            for l in question["classification"]["links"]
            if l["coverage"] == "DIRECT" and l["role"] == "PRIMARY"
        }
        require(
            bool(c["matched_topic_ids"]) and set(c["matched_topic_ids"]) <= direct.intersection(component["topic_ids"]),
            "compatibility lacks confirmed grade intersection",
        )
    if c["status"] == "CONFIRMED":
        require(human_review(c["provenance"]), "explicit compatibility requires review")
    return c


def validate_evaluation(e, question):
    shape("evaluation", e)
    require(
        e["question_id"] == question["_id"]["$oid"] and e["question_content_revision"] == question["content_revision"],
        "evaluation references stale question",
    )
    require(e["supersedes_evaluation_id"] != e["_id"], "evaluation cannot supersede itself")
    g = question["grading"]
    require(
        e["strategy"] == g["strategy"] and e["max_score"] == g["max_score"] and e["engine"] == g["engine"],
        "evaluation grading snapshot mismatch",
    )
    objective = question["question_type"] != "OPEN_ENDED"
    require(e["submission"]["type"] == ("OPTION" if objective else "TEXT"), "submission type mismatch")
    if objective:
        require(e["submission"]["selected_index"] < len(question["answers"]), "selected option outside question")
        require(e["rubric_revision"] is None and not e["criterion_results"], "objective evaluation cannot use rubric")
    else:
        require(bool(e["submission"]["text"].strip()), "empty open response")
        require(e["rubric_revision"] == g["rubric"]["revision"], "evaluation uses stale rubric")
    if e["status"] in ("PENDING", "FAILED"):
        require(e["score"] is None and e["is_correct"] is None and not e["criterion_results"], "ungraded response has a score")
        return e
    require(e["score"] is not None and 0 <= e["score"] <= e["max_score"], "score outside bounds")
    if objective:
        correct = e["submission"]["selected_index"] == question["correct_answer_indices"][0]
        require(
            e["status"] == "CONFIRMED" and e["is_correct"] == correct and e["score"] == (g["max_score"] if correct else 0),
            "objective result disagrees with answer key",
        )
        require(
            e["decision"] is not None
            and e["decision"]["actor"]["type"] == "SYSTEM"
            and e["decision"]["method"] == "RULE_BASED",
            "objective decision needs exact engine provenance",
        )
    else:
        criteria = {c["criterion_id"]: c for c in g["rubric"]["criteria"]}
        results = e["criterion_results"]
        require(
            len(results) == len(criteria) and {r["criterion_id"] for r in results} == set(criteria),
            "missing or duplicate evaluated criterion",
        )
        require(all(0 <= r["awarded_points"] <= criteria[r["criterion_id"]]["max_points"] for r in results), "criterion score outside bounds")
        require(sum(Decimal(str(r["awarded_points"])) for r in results) == Decimal(str(e["score"])), "evaluation score does not equal criteria")
        if e["status"] == "PROVISIONAL":
            require(e["is_correct"] is None, "provisional score cannot count as confirmed correctness")
            require(e["decision"] is not None, "provisional result needs provenance")
            if e["strategy"] == "AI":
                require(
                    e["decision"]["actor"]["type"] == "AI"
                    and e["decision"]["method"] == "AI_SUGGESTION"
                    and all(e["decision"][key] == g["engine"][key] for key in ("model", "prompt_id", "prompt_version")),
                    "AI decision provenance differs from engine",
                )
            if e["strategy"] == "SEMANTIC":
                require(
                    e["decision"]["actor"]["type"] == "SYSTEM"
                    and e["decision"]["method"] == "RULE_BASED"
                    and e["decision"]["model"] == g["engine"]["semantic_model_id"],
                    "semantic decision provenance differs from engine",
                )
        else:
            require(human_review(e["decision"]), "open grading confirmation requires human review")
            passed = e["score"] >= g["pass_score"] and all(
                not criteria[r["criterion_id"]]["essential"] or r["awarded_points"] > 0 for r in results
            )
            require(e["is_correct"] == passed, "confirmed correctness disagrees with rubric")
    return e


def validate_evaluation_binding(e, attempt, previous=None):
    """Bind a result to the independently stored attempt, never model-supplied identity."""
    require(e["attempt_id"] == attempt["attempt_id"] and e["user_id"] == attempt["owner_user_id"], "evaluation attempt owner mismatch")
    require(
        e["question_id"] == attempt["question_id"]
        and e["question_content_revision"] == attempt["question_content_revision"]
        and e["submission"] == attempt["submission"],
        "evaluation changed submitted answer or snapshot",
    )
    if e["supersedes_evaluation_id"] is not None:
        require(
            previous is not None
            and previous["_id"] == e["supersedes_evaluation_id"]
            and previous["attempt_id"] == e["attempt_id"]
            and previous["user_id"] == e["user_id"]
            and previous["question_content_revision"] == e["question_content_revision"],
            "superseded evaluation belongs to another attempt",
        )
    return e


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--examples", action="store_true", required=True)
    p.parse_args()
    catalog = read(ROOT / "shared/knowledge-catalog.json")
    base = ROOT / "shared/examples/question-contracts"
    questions = {}
    counts = {}
    for path in sorted(base.glob("question-*.json")):
        q = validate_question(read(path), catalog)
        questions[q["_id"]["$oid"]] = q
    counts["questions"] = len(questions)
    exam = read(base / "exam-context.json")
    attempts = {a["attempt_id"]: a for a in read(base / "attempt-contexts.json")}
    for kind, function in (
        ("suggestion", validate_suggestion),
        ("compatibility", validate_compatibility),
        ("evaluation", validate_evaluation),
    ):
        paths = sorted(base.glob(f"{kind}-*.json"))
        for path in paths:
            record = read(path)
            q = questions[record["question_id"]]
            if kind == "suggestion":
                function(record, q, catalog)
            elif kind == "compatibility":
                function(record, q, catalog, exam)
            else:
                function(record, q)
                validate_evaluation_binding(record, attempts[record["attempt_id"]])
        counts[kind] = len(paths)
    require(bool(questions) and all(counts.values()), "missing contract examples")
    print(json.dumps({"contract": "COGNIVA_V2_ACTIVE", "examples_validated": counts}, indent=2))


if __name__ == "__main__":
    main()
