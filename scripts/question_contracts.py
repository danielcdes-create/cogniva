"""Executable Cogniva V2/V2.1 question, suggestion, compatibility, evaluation, and eligibility contracts."""
import argparse
import copy
from datetime import date
from decimal import Decimal
from functools import lru_cache
import hashlib
import json
import re

from bson import ObjectId
from jsonschema import Draft202012Validator, FormatChecker, validators
import rfc8785

from knowledge_catalog import ROOT, read

CONTENT_HASH_VERSION = "question-v2-jcs-sha256"
SEMANTIC_HASH_VERSION = "question-v2-semantic-jcs-sha256-v1"
GRADING_HASH_VERSION = "question-v2-grading-jcs-sha256-v1"

HEX24_RE = re.compile(r"^[a-f0-9]{24}$")
SHA256_RE = re.compile(r"^[a-f0-9]{64}$")
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


class ContractValidationError(ValueError):
    """Structured contract validation error shared with Rust parity tests."""

    def __init__(self, code, field, message):
        self.code = code
        self.field = field
        self.detail = message
        super().__init__(message)


def fail(code, field, message):
    raise ContractValidationError(code, field, message)


def require(condition, message, code="CONTRACT_INVARIANT_FAILED", field=None):
    if not condition:
        fail(code, field, message)


def is_strict_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def is_valid_oid_hex(value):
    return isinstance(value, str) and bool(HEX24_RE.fullmatch(value))


def validate_api_question_id(value, field="question_id"):
    if not is_valid_oid_hex(value):
        fail("INVALID_OBJECT_ID", field, f"schema [{field}]: expected 24-char lowercase hex ObjectId string")
    return value


def validate_contract_oid(value, field="_id"):
    if not isinstance(value, dict) or set(value.keys()) != {"$oid"} or not is_valid_oid_hex(value.get("$oid")):
        fail("INVALID_OBJECT_ID", field, f"schema [{field}]: expected canonical Extended JSON ObjectId")
    return value["$oid"]


STRICT_TYPE_CHECKER = Draft202012Validator.TYPE_CHECKER.redefine(
    "integer",
    lambda _checker, instance: is_strict_int(instance),
)


@lru_cache(maxsize=None)
def _schema_bundle():
    return read(ROOT / "shared/schemas/question-contracts.schema.json")


@lru_cache(maxsize=None)
def schema_validator(kind):
    bundle = _schema_bundle()
    schema = {"$schema": bundle["$schema"], "$ref": f"#/$defs/{kind}", "$defs": bundle["$defs"]}
    Draft202012Validator.check_schema(schema)
    StrictValidator = validators.extend(Draft202012Validator, type_checker=STRICT_TYPE_CHECKER)
    return StrictValidator(schema, format_checker=FormatChecker())


def _is_nullable_integer_schema(subschema):
    if not isinstance(subschema, dict):
        return False
    if subschema.get("type") == "integer":
        return True
    branches = subschema.get("anyOf") or subschema.get("oneOf") or []
    return any(isinstance(b, dict) and b.get("type") == "integer" for b in branches)


def _classify_schema_error(kind, error):
    path_parts = [str(p) for p in error.absolute_path]
    field = ".".join(path_parts) if path_parts else kind
    if field in ("_id", "_id.$oid") or field.startswith("_id."):
        return "INVALID_OBJECT_ID", "_id"
    if field == "question_id":
        return "INVALID_OBJECT_ID", "question_id"
    if (error.validator == "type" and error.validator_value == "integer") or (
        error.validator in ("anyOf", "oneOf")
        and _is_nullable_integer_schema(error.schema)
        and error.instance is not None
        and not is_strict_int(error.instance)
    ):
        return "INVALID_INTEGER", field
    return "SCHEMA_VALIDATION_FAILED", field


def shape(kind, record):
    if not isinstance(record, dict):
        fail("SCHEMA_VALIDATION_FAILED", kind, f"{kind} schema []: expected JSON object")
    if kind == "question" and "_id" in record:
        validate_contract_oid(record["_id"], "_id")
    elif kind in ("suggestion", "compatibility", "evaluation") and "question_id" in record:
        validate_api_question_id(record["question_id"], "question_id")

    errors = sorted(
        schema_validator(kind).iter_errors(record),
        key=lambda e: (0 if _classify_schema_error(kind, e)[0] == "INVALID_INTEGER" else 1, [str(p) for p in e.absolute_path]),
    )
    if errors:
        code, field = _classify_schema_error(kind, errors[0])
        fail(code, field, f"{kind} schema {list(errors[0].absolute_path)}: {errors[0].message}")


def content_revision(question):
    fields = ["question_type", "question", "answers", "correct_answer_indices", "grading"]
    return hashlib.sha256(rfc8785.dumps({key: question[key] for key in fields})).hexdigest()


def semantic_payload(question):
    is_open = question["question_type"] == "OPEN_ENDED"
    grading = question.get("grading") or {}
    expected_responses = list(grading.get("expected_responses") or []) if is_open else []
    rubric = grading.get("rubric") if is_open else None
    substantive_criteria = (
        [
            {
                "criterion_id": c["criterion_id"],
                "description": c["description"],
                "essential": c["essential"],
            }
            for c in (rubric.get("criteria") or [])
        ]
        if isinstance(rubric, dict)
        else []
    )
    return {
        "question_type": question["question_type"],
        "question": question["question"],
        "answers": question["answers"],
        "correct_answer_indices": question["correct_answer_indices"],
        "expected_responses": expected_responses,
        "substantive_criteria": substantive_criteria,
    }


def semantic_revision(question):
    return hashlib.sha256(rfc8785.dumps(semantic_payload(question))).hexdigest()


def grading_revision(question):
    return hashlib.sha256(rfc8785.dumps(question["grading"])).hexdigest()


def populate_v2_1_hashes(question):
    question["content_revision"] = content_revision(question)
    question["content_hash_version"] = CONTENT_HASH_VERSION
    question["semantic_revision"] = semantic_revision(question)
    question["semantic_hash_version"] = SEMANTIC_HASH_VERSION
    question["grading_revision"] = grading_revision(question)
    question["grading_hash_version"] = GRADING_HASH_VERSION
    return question


def human_review(provenance):
    return (
        isinstance(provenance, dict)
        and isinstance(provenance.get("actor"), dict)
        and provenance["actor"].get("type") == "HUMAN"
        and provenance.get("method") == "MANUAL_REVIEW"
    )


def trusted_human_review(provenance, trusted_review_context=None):
    if not human_review(provenance):
        return False
    trust_level = provenance.get("trust_level")
    if trust_level == "UNVERIFIED_LEGACY":
        return False
    review_event_id = provenance.get("review_event_id")
    ctx = trusted_review_context or {}
    require_verified = bool(ctx.get("require_server_verified", False))
    verified_ids = ctx.get("verified_review_event_ids")
    if trust_level == "SERVER_VERIFIED":
        if not isinstance(review_event_id, str) or not UUID_RE.fullmatch(review_event_id):
            return False
        if verified_ids is not None and review_event_id not in verified_ids:
            return False
        return True
    if require_verified:
        return False
    if verified_ids is not None and review_event_id is not None:
        return review_event_id in verified_ids
    return True


def _reject_client_server_provenance(provenance, field_prefix):
    if not isinstance(provenance, dict):
        return
    if provenance.get("review_event_id") is not None:
        fail(
            "REVIEW_ACTOR_UNTRUSTED",
            f"{field_prefix}.review_event_id",
            "client payload cannot self-assign review_event_id",
        )
    if provenance.get("trust_level") == "SERVER_VERIFIED":
        fail(
            "REVIEW_ACTOR_UNTRUSTED",
            f"{field_prefix}.trust_level",
            "client payload cannot self-assign SERVER_VERIFIED trust_level",
        )


def check_safe_evidence_list(items, field="evidence"):
    for ev in items or []:
        uri = str(ev.get("uri") or "").strip()
        is_http = uri.lower().startswith(("https://", "http://")) and len(uri) <= 2048
        is_cogniva_urn = bool(re.fullmatch(r"urn:cogniva:[A-Za-z0-9._:-]+", uri)) and len(uri) <= 2048
        is_archival_pdf = (
            bool(re.fullmatch(r"file:///[A-Za-z0-9._:/-]+\.[Pp][Dd][Ff]", uri))
            and ".." not in uri
            and len(uri) <= 2048
        )
        require(is_http or is_cogniva_urn or is_archival_pdf, "unsafe evidence uri scheme", "UNSAFE_EVIDENCE_URI", field)


def check_provenance_uris(provenance, field="provenance"):
    if provenance is not None:
        check_safe_evidence_list(provenance.get("evidence"), f"{field}.evidence")
        if provenance.get("trust_level") == "SERVER_VERIFIED":
            rev_id = provenance.get("review_event_id")
            require(
                isinstance(rev_id, str) and bool(UUID_RE.fullmatch(rev_id)),
                "SERVER_VERIFIED provenance requires review_event_id",
                "REVIEW_ACTOR_UNTRUSTED",
                f"{field}.review_event_id",
            )


def taxonomy_links(links, catalog, confirmed):
    topics = {t["topic_id"]: t for t in catalog["topics"]}
    subjects = {s["subject_id"]: s for s in catalog["subjects"]}
    ids = set()
    link_ids = set()
    for idx, link in enumerate(links):
        topic = topics.get(link["topic_id"])
        require(
            topic is not None and topic["subject_id"] == link["subject_id"],
            "invalid taxonomy link",
            "INVALID_TAXONOMY_LINK",
            f"classification.links.{idx}",
        )
        require(
            topic["status"] == "ACTIVE" and subjects[link["subject_id"]]["status"] == "ACTIVE",
            "inactive taxonomy link",
            "INACTIVE_TAXONOMY_LINK",
            f"classification.links.{idx}",
        )
        require(
            link["topic_id"] not in ids,
            "duplicate classified topic",
            "DUPLICATE_TAXONOMY_LINK",
            f"classification.links.{idx}.topic_id",
        )
        ids.add(link["topic_id"])
        if link["role"] == "PRIMARY":
            require(
                link["coverage"] == "DIRECT",
                "primary link must be direct",
                "INVALID_PRIMARY_LINK",
                f"classification.links.{idx}.coverage",
            )
        if confirmed:
            require(
                human_review(link["provenance"]),
                "confirmed classification requires human review",
                "HUMAN_REVIEW_REQUIRED",
                f"classification.links.{idx}.provenance",
            )
            require(
                link["link_id"] not in link_ids,
                "duplicate classification link_id",
                "DUPLICATE_TAXONOMY_LINK",
                f"classification.links.{idx}.link_id",
            )
            link_ids.add(link["link_id"])
    return sum(link["role"] == "PRIMARY" for link in links)


def validate_question(q, catalog, allow_server_provenance=True):
    if not allow_server_provenance and isinstance(q, dict):
        for idx, origin in enumerate(q.get("origins") or []):
            if isinstance(origin, dict):
                _reject_client_server_provenance(origin.get("provenance"), f"origins.{idx}.provenance")
        classification_raw = q.get("classification")
        if isinstance(classification_raw, dict):
            for idx, link in enumerate(classification_raw.get("links") or []):
                if isinstance(link, dict):
                    _reject_client_server_provenance(link.get("provenance"), f"classification.links.{idx}.provenance")
        validity_raw = q.get("validity")
        if isinstance(validity_raw, dict):
            _reject_client_server_provenance(validity_raw.get("decision"), "validity.decision")

    shape("question", q)

    computed_content = content_revision(q)
    require(
        q["content_revision"] == computed_content,
        "content revision mismatch",
        "CONTENT_REVISION_MISMATCH",
        "content_revision",
    )

    computed_semantic = semantic_revision(q)
    sem_rev = q.get("semantic_revision")
    sem_ver = q.get("semantic_hash_version")
    if sem_rev is not None or sem_ver is not None:
        require(
            sem_rev is not None and sem_ver == SEMANTIC_HASH_VERSION and sem_rev == computed_semantic,
            "semantic revision mismatch",
            "SEMANTIC_REVISION_MISMATCH",
            "semantic_revision",
        )

    computed_grading = grading_revision(q)
    grad_rev = q.get("grading_revision")
    grad_ver = q.get("grading_hash_version")
    if grad_rev is not None or grad_ver is not None:
        require(
            grad_rev is not None and grad_ver == GRADING_HASH_VERSION and grad_rev == computed_grading,
            "grading revision mismatch",
            "GRADING_REVISION_MISMATCH",
            "grading_revision",
        )

    if q.get("exam_year") is not None:
        require(is_strict_int(q["exam_year"]), "exam_year must be integer", "INVALID_INTEGER", "exam_year")
        require(1900 <= q["exam_year"] <= 2200, "exam_year out of bounds", "SCHEMA_VALIDATION_FAILED", "exam_year")
    if isinstance(q.get("concursos"), list):
        require(len(q["concursos"]) <= 50, "concursos exceeds limit", "SCHEMA_VALIDATION_FAILED", "concursos")

    for idx, origin in enumerate(q["origins"]):
        if origin.get("source_reference") is not None:
            check_safe_evidence_list([origin["source_reference"]], f"origins.{idx}.source_reference")
        check_provenance_uris(origin.get("provenance"), f"origins.{idx}.provenance")
        if origin["kind"] == "OFFICIAL_EXAM" and origin["verification"] == "CONFIRMED":
            require(
                human_review(origin["provenance"]),
                "confirmed official exam origin requires human review",
                "HUMAN_REVIEW_REQUIRED",
                f"origins.{idx}.provenance",
            )

    for idx, link in enumerate(q["classification"]["links"]):
        check_provenance_uris(link.get("provenance"), f"classification.links.{idx}.provenance")
    check_provenance_uris(q["validity"].get("decision"), "validity.decision")

    require(bool(q["question"].strip()), "empty question", "EMPTY_QUESTION", "question")
    answers = q["answers"]
    require(all(a.strip() for a in answers), "blank answer option", "INVALID_ANSWERS", "answers")
    require(len({a.strip() for a in answers}) == len(answers), "duplicate answer options", "INVALID_ANSWERS", "answers")
    if q["question_type"] == "MULTIPLE_CHOICE":
        require(len(answers) == 5, "multiple choice requires exactly 5 answer options", "INVALID_ANSWERS", "answers")
    if q["question_type"] != "OPEN_ENDED":
        index = q["correct_answer_indices"][0]
        require(0 <= index < len(answers), "correct_answer_indices out of bounds", "INVALID_ANSWER_KEY", "correct_answer_indices")
        require(q["correct_answers"] == [answers[index]], "textual and indexed answers disagree", "INVALID_ANSWER_KEY", "correct_answers")

    classification = q["classification"]
    require(
        1 <= classification["catalog_version"] <= catalog["catalog_version"],
        "unknown catalog version",
        "UNKNOWN_CATALOG_VERSION",
        "classification.catalog_version",
    )
    primaries = taxonomy_links(classification["links"], catalog, True)
    require(primaries <= 1, "multiple primary links", "MULTIPLE_PRIMARY_LINKS", "classification.links")
    if classification["state"] == "CONFIRMED":
        require(
            primaries == 1,
            "confirmed classification needs one primary link",
            "CLASSIFICATION_NOT_CONFIRMED",
            "classification.links",
        )
    if classification["links"]:
        require(
            classification["revision"] > 0,
            "confirmed links require a revision",
            "SCHEMA_VALIDATION_FAILED",
            "classification.revision",
        )
    primary = next((l for l in classification["links"] if l["role"] == "PRIMARY"), None)
    for legacy, field in (("canonical_subject_id", "subject_id"), ("canonical_topic_id", "topic_id")):
        if legacy in q and q[legacy] is not None:
            require(
                primary is not None and q[legacy] == primary[field],
                "legacy canonical mirror disagrees",
                "CANONICAL_MIRROR_MISMATCH",
                legacy,
            )

    validity = q["validity"]
    require(
        (validity["scope"] == "CURRENT_KNOWLEDGE") == (validity["context_date"] is None),
        "historical validity needs explicit context date",
        "INVALID_VALIDITY_SCOPE",
        "validity.context_date",
    )
    eval_mode = validity.get("evaluation_mode")
    eval_sem = validity.get("evaluated_semantic_revision")
    if validity["status"] in ("CURRENT", "OBSOLETE"):
        require(
            validity["revision"] > 0 and human_review(validity["decision"]),
            "validity decision requires reviewed revision",
            "HUMAN_REVIEW_REQUIRED",
            "validity.decision",
        )
        if eval_mode == "SEMANTIC" or eval_sem is not None:
            require(
                eval_mode in (None, "SEMANTIC") and eval_sem == computed_semantic,
                "stale validity decision (semantic revision mismatch)",
                "SEMANTIC_REVISION_MISMATCH",
                "validity.evaluated_semantic_revision",
            )
        else:
            require(
                validity["evaluated_content_revision"] == q["content_revision"],
                "stale validity decision",
                "CONTENT_REVISION_MISMATCH",
                "validity.evaluated_content_revision",
            )
        if validity["basis"] != "TIMELESS":
            require(
                bool(validity["decision"]["evidence"]),
                "time-sensitive validity requires evidence",
                "VALIDITY_EVIDENCE_REQUIRED",
                "validity.decision.evidence",
            )
    if validity["status"] == "CURRENT" and validity["basis"] == "LEGAL":
        require(
            validity["review_due_on"] is not None,
            "current legal question needs review deadline",
            "VALIDITY_DEADLINE_REQUIRED",
            "validity.review_due_on",
        )
    if validity["review_due_on"] and validity["reference_date"]:
        require(
            validity["review_due_on"] >= validity["reference_date"],
            "review deadline precedes review",
            "INVALID_VALIDITY_DEADLINE",
            "validity.review_due_on",
        )
    if any(r in ("LAW_REPEALED", "LAW_AMENDED") for r in validity["reason_codes"]):
        require(
            validity["basis"] == "LEGAL",
            "legal reason on nonlegal validity",
            "INVALID_VALIDITY_REASON",
            "validity.reason_codes",
        )

    grading = q["grading"]
    require(
        grading["pass_score"] <= grading["max_score"],
        "passing score exceeds maximum",
        "INVALID_GRADING",
        "grading.pass_score",
    )
    if q["question_type"] == "OPEN_ENDED":
        rubric = grading["rubric"]
        ids = [c["criterion_id"] for c in rubric["criteria"]]
        require(len(ids) == len(set(ids)), "duplicate rubric criterion", "INVALID_GRADING", "grading.rubric.criteria")
        require(
            sum(Decimal(str(c["max_points"])) for c in rubric["criteria"]) == Decimal(str(grading["max_score"])),
            "rubric total differs from max_score",
            "INVALID_GRADING",
            "grading.rubric.criteria",
        )
        require(
            all(t.strip() for t in grading["expected_responses"]),
            "empty reference answer",
            "INVALID_GRADING",
            "grading.expected_responses",
        )
        engine = grading["engine"]
        if grading["strategy"] == "RUBRIC":
            require(engine is None, "manual rubric cannot declare automatic engine", "INVALID_GRADING", "grading.engine")
        elif grading["strategy"] == "AI":
            require(
                engine is not None
                and engine["provider"] == "xai"
                and all(engine[k] for k in ("model", "prompt_id", "prompt_version")),
                "AI grading needs provider/model/prompt version",
                "INVALID_GRADING",
                "grading.engine",
            )
            require(
                all(engine[k] is None for k in ("semantic_model_id", "semantic_model_revision", "calibration_id")),
                "AI rubric cannot masquerade as semantic calibration",
                "INVALID_GRADING",
                "grading.engine",
            )
        elif grading["strategy"] == "SEMANTIC":
            require(
                engine is not None
                and all(engine[k] for k in ("semantic_model_id", "semantic_model_revision", "calibration_id")),
                "semantic grading needs versioned calibrated engine",
                "INVALID_GRADING",
                "grading.engine",
            )
            require(
                all(engine[k] is None for k in ("provider", "model", "prompt_id", "prompt_version")),
                "semantic engine cannot declare AI generation metadata",
                "INVALID_GRADING",
                "grading.engine",
            )
    return q


def validate_untrusted_client_question(q, catalog):
    return validate_question(q, catalog, allow_server_provenance=False)


def contract_to_bson_question(question):
    """Convert canonical Extended JSON question to BSON-ready dict with native ObjectId."""
    oid_hex = validate_contract_oid(question.get("_id"), "_id")
    doc = copy.deepcopy(question)
    doc["_id"] = ObjectId(oid_hex)
    return doc


def bson_to_contract_question(bson_doc):
    """Validate native BSON types (ObjectId and strict BSON integers) and project to Extended JSON."""
    if not isinstance(bson_doc, dict):
        fail("SCHEMA_VALIDATION_FAILED", "question", "BSON question must be a document")
    raw_id = bson_doc.get("_id")
    if not isinstance(raw_id, ObjectId):
        fail("INVALID_OBJECT_ID", "_id", "MongoDB question _id must be native BSON ObjectId, never Extended JSON or string")

    for top_int in ("schema_version", "revision"):
        if top_int in bson_doc and not is_strict_int(bson_doc[top_int]):
            fail("INVALID_INTEGER", top_int, f"BSON field {top_int} must be int32/int64, never double/bool/string")
    if bson_doc.get("exam_year") is not None and not is_strict_int(bson_doc["exam_year"]):
        fail("INVALID_INTEGER", "exam_year", "BSON exam_year must be int32/int64")
    for idx, item in enumerate(bson_doc.get("correct_answer_indices") or []):
        if not is_strict_int(item):
            fail("INVALID_INTEGER", f"correct_answer_indices.{idx}", "BSON answer index must be int32/int64")
    cls_doc = bson_doc.get("classification")
    if isinstance(cls_doc, dict):
        for sub in ("revision", "catalog_version"):
            if sub in cls_doc and not is_strict_int(cls_doc[sub]):
                fail("INVALID_INTEGER", f"classification.{sub}", f"BSON classification.{sub} must be int32/int64")
    val_doc = bson_doc.get("validity")
    if isinstance(val_doc, dict) and "revision" in val_doc and not is_strict_int(val_doc["revision"]):
        fail("INVALID_INTEGER", "validity.revision", "BSON validity.revision must be int32/int64")
    for idx, origin in enumerate(bson_doc.get("origins") or []):
        if isinstance(origin, dict) and origin.get("exam_year") is not None and not is_strict_int(origin["exam_year"]):
            fail("INVALID_INTEGER", f"origins.{idx}.exam_year", "BSON origins.exam_year must be int32/int64")
    grad_doc = bson_doc.get("grading")
    if isinstance(grad_doc, dict) and isinstance(grad_doc.get("rubric"), dict):
        rub_rev = grad_doc["rubric"].get("revision")
        if rub_rev is not None and not is_strict_int(rub_rev):
            fail("INVALID_INTEGER", "grading.rubric.revision", "BSON grading.rubric.revision must be int32/int64")

    projected = copy.deepcopy(bson_doc)
    projected["_id"] = {"$oid": str(raw_id)}
    projected.pop("schema_outbox", None)
    return projected


def validate_bson_question(bson_doc, catalog):
    projected = bson_to_contract_question(bson_doc)
    validate_question(projected, catalog)
    return projected


def _parse_iso_date(raw, field="evaluation_date"):
    if isinstance(raw, date):
        return raw
    if isinstance(raw, str):
        try:
            return date.fromisoformat(raw)
        except ValueError:
            pass
    fail("INVALID_DATE", field, f"expected ISO date YYYY-MM-DD in {field}")


def _compatibility_matches_question(rec, question, sem_rev):
    if rec.get("status") != "CONFIRMED":
        return False
    if rec.get("question_id") != question["_id"]["$oid"]:
        return False
    rec_sem = rec.get("question_semantic_revision")
    if rec_sem is not None:
        return rec_sem == sem_rev
    return rec.get("question_content_revision") == question["content_revision"]


def evaluate_question_eligibility(
    question,
    catalog,
    exam_context=None,
    evaluation_date=None,
    trusted_review_context=None,
    mode="STANDARD_OBJECTIVE",
):
    """Centralized normative practice eligibility evaluator (NT-COGNIVA-QUESTIONS-002 Sec. 7)."""
    require(evaluation_date is not None, "eligibility needs explicit reference date", "MISSING_EVALUATION_DATE", "evaluation_date")
    as_of = _parse_iso_date(evaluation_date, "evaluation_date")
    reasons = []

    def add_reason(code):
        if code not in reasons:
            reasons.append(code)

    if not isinstance(question, dict):
        return {
            "eligible": False,
            "reason_codes": ["SCHEMA_VALIDATION_FAILED", "QUESTION_NOT_ELIGIBLE"],
            "coverage_mode": None,
            "is_borrowed_analogous": False,
            "primary_subject_id": None,
            "primary_topic_id": None,
        }

    # 1. Active status
    if question.get("status") != "ACTIVE":
        add_reason("QUESTION_ARCHIVED")

    # 2. Objective question type in standard practice
    qtype = question.get("question_type")
    if mode != "DISCURSIVE_EXPLICIT" and qtype == "OPEN_ENDED":
        add_reason("OPEN_ENDED_EXCLUDED_FROM_OBJECTIVE_PRACTICE")
    elif qtype not in ("MULTIPLE_CHOICE", "TRUE_FALSE", "OPEN_ENDED"):
        add_reason("SCHEMA_VALIDATION_FAILED")

    # 3. Structural, grading, and hash integrity
    try:
        validate_question(question, catalog)
    except ContractValidationError as exc:
        add_reason(exc.code)

    # 4 & 5. Confirmed classification with active PRIMARY DIRECT link & trusted human review
    classification = question.get("classification") if isinstance(question.get("classification"), dict) else {}
    links = classification.get("links") if isinstance(classification.get("links"), list) else []
    primary_link = next((l for l in links if isinstance(l, dict) and l.get("role") == "PRIMARY" and l.get("coverage") == "DIRECT"), None)
    active_topics = {
        (t["subject_id"], t["topic_id"])
        for t in catalog.get("topics", [])
        if t.get("status") == "ACTIVE"
    }
    active_subjects = {s["subject_id"] for s in catalog.get("subjects", []) if s.get("status") == "ACTIVE"}

    primary_subject_id = primary_link.get("subject_id") if primary_link else None
    primary_topic_id = primary_link.get("topic_id") if primary_link else None

    if (
        classification.get("state") != "CONFIRMED"
        or primary_link is None
        or primary_subject_id not in active_subjects
        or (primary_subject_id, primary_topic_id) not in active_topics
    ):
        add_reason("CLASSIFICATION_NOT_CONFIRMED")
    elif not trusted_human_review(primary_link.get("provenance"), trusted_review_context):
        add_reason("HUMAN_REVIEW_REQUIRED")

    for origin in question.get("origins") or []:
        if isinstance(origin, dict) and origin.get("kind") == "OFFICIAL_EXAM" and origin.get("verification") == "CONFIRMED":
            if not trusted_human_review(origin.get("provenance"), trusted_review_context):
                add_reason("HUMAN_REVIEW_REQUIRED")

    # 6, 7 & 8. Confirmed validity, semantic/content binding, trusted human review, and review_due_on
    validity = question.get("validity") if isinstance(question.get("validity"), dict) else {}
    vstatus = validity.get("status")
    historical = validity.get("scope") == "EXPLICIT_HISTORICAL_DATE"
    expected_historical = mode == "HISTORICAL_ONLY"
    if vstatus != "CURRENT" or historical != expected_historical:
        add_reason("VALIDITY_NOT_CONFIRMED")
    else:
        if not trusted_human_review(validity.get("decision"), trusted_review_context):
            add_reason("HUMAN_REVIEW_REQUIRED")
        due_raw = validity.get("review_due_on")
        if due_raw is not None:
            due_date = _parse_iso_date(due_raw, "validity.review_due_on")
            if due_date < as_of:
                add_reason("VALIDITY_REVIEW_EXPIRED")

    # 9. Exam compatibility context
    coverage_mode = None
    is_borrowed_analogous = False
    if exam_context is not None:
        sem_rev = semantic_revision(question) if all(k in question for k in ("question_type", "question", "answers", "correct_answer_indices", "grading")) else None
        owner_id = exam_context.get("owner_user_id")
        exam_id = exam_context.get("exam_id")
        exam_rev = exam_context.get("exam_revision")
        comp_id = exam_context.get("component_id")
        allowed_topics = set(exam_context.get("allowed_topic_ids") or [])
        if "components" in exam_context and not allowed_topics:
            for comp in exam_context.get("components") or []:
                if comp_id is None or comp.get("component_id") == comp_id:
                    allowed_topics.update(comp.get("topic_ids") or [])
        require_explicit = bool(exam_context.get("require_explicit_compatibility", False))
        records = exam_context.get("compatibility_records") or []

        matching = []
        for rec in records:
            if not isinstance(rec, dict):
                continue
            if owner_id is not None and rec.get("owner_user_id") != owner_id:
                continue
            if exam_id is not None and rec.get("exam_id") != exam_id:
                continue
            if exam_rev is not None and rec.get("exam_revision") != exam_rev:
                continue
            if comp_id is not None and rec.get("component_id") != comp_id:
                continue
            if sem_rev is not None and _compatibility_matches_question(rec, question, sem_rev):
                if trusted_human_review(rec.get("provenance"), trusted_review_context):
                    matching.append(rec)

        relations = {r.get("relation") for r in matching}
        if "EXCLUDED" in relations:
            add_reason("EXAM_COMPATIBILITY_EXCLUDED")
        elif "DIRECT" in relations and primary_topic_id in allowed_topics:
            coverage_mode = "DIRECT"
            is_borrowed_analogous = False
        elif "ANALOGOUS" in relations:
            coverage_mode = "ANALOGOUS"
            is_borrowed_analogous = True
        elif not require_explicit and primary_topic_id is not None and primary_topic_id in allowed_topics:
            coverage_mode = "DIRECT"
            is_borrowed_analogous = False
        else:
            add_reason("EXAM_COMPATIBILITY_MISSING")

    if reasons:
        add_reason("QUESTION_NOT_ELIGIBLE")
        return {
            "eligible": False,
            "reason_codes": reasons,
            "coverage_mode": None,
            "is_borrowed_analogous": False,
            "primary_subject_id": primary_subject_id,
            "primary_topic_id": primary_topic_id,
        }

    return {
        "eligible": True,
        "reason_codes": [],
        "coverage_mode": coverage_mode or "DIRECT",
        "is_borrowed_analogous": is_borrowed_analogous,
        "primary_subject_id": primary_subject_id,
        "primary_topic_id": primary_topic_id,
    }


@lru_cache(maxsize=1)
def _default_catalog():
    return read(ROOT / "shared/knowledge-catalog.json")


def practice_eligible(
    q,
    mode="GENERAL",
    as_of=None,
    catalog=None,
    exam_context=None,
    trusted_review_context=None,
):
    """Normative eligibility predicate delegating to centralized evaluate_question_eligibility."""
    require(as_of is not None, "eligibility needs explicit reference date", "MISSING_EVALUATION_DATE", "as_of")
    eval_mode = "HISTORICAL_ONLY" if mode == "HISTORICAL_ONLY" else "STANDARD_OBJECTIVE"
    decision = evaluate_question_eligibility(
        question=q,
        catalog=catalog or _default_catalog(),
        exam_context=exam_context,
        evaluation_date=as_of,
        trusted_review_context=trusted_review_context,
        mode=eval_mode,
    )
    return decision["eligible"]


def validate_suggestion(s, question, catalog):
    shape("suggestion", s)
    require(
        s["question_id"] == question["_id"]["$oid"],
        "suggestion references another question",
        "SUGGESTION_QUESTION_MISMATCH",
        "question_id",
    )
    require(
        1 <= s["catalog_version"] <= catalog["catalog_version"],
        "suggestion catalog mismatch",
        "UNKNOWN_CATALOG_VERSION",
        "catalog_version",
    )
    require(
        taxonomy_links(s["proposed_links"], catalog, False) == 1,
        "suggestion must propose one primary link",
        "INVALID_PRIMARY_LINK",
        "proposed_links",
    )
    if s.get("question_semantic_revision") is not None:
        require(
            s["question_semantic_revision"] == semantic_revision(question),
            "stale suggestion semantic revision",
            "SEMANTIC_REVISION_MISMATCH",
            "question_semantic_revision",
        )
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
            "STALE_SUGGESTION",
            "status",
        )
    if s["status"] in ("ACCEPTED", "REJECTED"):
        require(
            s["resolution"] is not None and s["resolution"]["actor"]["type"] == "HUMAN",
            "suggestion resolution requires human",
            "HUMAN_REVIEW_REQUIRED",
            "resolution.actor",
        )
    if s["status"] == "PENDING":
        require(s["resolution"] is None, "pending suggestion cannot have a resolution", "SCHEMA_VALIDATION_FAILED", "resolution")
    if s["status"] == "REJECTED":
        require(
            s["resolution"]["resulting_classification_revision"] is None,
            "rejection cannot claim applied classification",
            "SCHEMA_VALIDATION_FAILED",
            "resolution.resulting_classification_revision",
        )
    if s["status"] == "ACCEPTED":
        require(
            s["resolution"]["resulting_classification_revision"] == s["expected_classification_revision"] + 1,
            "acceptance must increment classification revision",
            "SCHEMA_VALIDATION_FAILED",
            "resolution.resulting_classification_revision",
        )
        confirmed = {(l["subject_id"], l["topic_id"], l["role"], l["coverage"]) for l in question["classification"]["links"]}
        require(
            all((l["subject_id"], l["topic_id"], l["role"], l["coverage"]) in confirmed for l in s["proposed_links"]),
            "accepted proposal absent from confirmed links",
            "CLASSIFICATION_NOT_CONFIRMED",
            "proposed_links",
        )
        accepted_topics = {l["topic_id"] for l in s["proposed_links"]}
        require(
            all(
                l["provenance"]["source_suggestion_id"] == s["_id"]
                for l in question["classification"]["links"]
                if l["topic_id"] in accepted_topics
            ),
            "accepted classification lost suggestion provenance",
            "PROVENANCE_MISMATCH",
            "classification.links",
        )
    return s


def validate_compatibility(c, question, catalog, exam):
    shape("compatibility", c)
    sem_match = (
        c.get("question_semantic_revision") is not None
        and c["question_semantic_revision"] == semantic_revision(question)
    )
    require(
        c["question_id"] == question["_id"]["$oid"]
        and (c["question_content_revision"] == question["content_revision"] or sem_match),
        "stale compatibility question",
        "CONTENT_REVISION_MISMATCH",
        "question_content_revision",
    )
    require(
        1 <= c["catalog_version"] <= catalog["catalog_version"],
        "compatibility catalog mismatch",
        "UNKNOWN_CATALOG_VERSION",
        "catalog_version",
    )
    require(
        c["owner_user_id"] == exam["owner_user_id"]
        and c["exam_id"] == exam["exam_id"]
        and c["exam_revision"] == exam["revision"],
        "compatibility owner/exam revision mismatch",
        "COMPATIBILITY_OWNER_MISMATCH",
        "owner_user_id",
    )
    component = next((s for s in exam["components"] if s["component_id"] == c["component_id"]), None)
    require(component is not None, "compatibility component absent", "COMPATIBILITY_COMPONENT_ABSENT", "component_id")
    require(len(set(c["matched_topic_ids"])) == len(c["matched_topic_ids"]), "duplicate matched topics", "SCHEMA_VALIDATION_FAILED", "matched_topic_ids")
    if c["relation"] == "DIRECT":
        direct = {
            l["topic_id"]
            for l in question["classification"]["links"]
            if l["coverage"] == "DIRECT" and l["role"] == "PRIMARY"
        }
        require(
            bool(c["matched_topic_ids"]) and set(c["matched_topic_ids"]) <= direct.intersection(component["topic_ids"]),
            "compatibility lacks confirmed grade intersection",
            "COMPATIBILITY_INTERSECTION_MISSING",
            "matched_topic_ids",
        )
    if c["status"] == "CONFIRMED":
        require(
            human_review(c["provenance"]),
            "explicit compatibility requires review",
            "HUMAN_REVIEW_REQUIRED",
            "provenance",
        )
    return c


def validate_evaluation(e, question):
    shape("evaluation", e)
    require(
        e["question_id"] == question["_id"]["$oid"] and e["question_content_revision"] == question["content_revision"],
        "evaluation references stale question",
        "CONTENT_REVISION_MISMATCH",
        "question_content_revision",
    )
    if e.get("grading_revision") is not None:
        require(
            e["grading_revision"] == grading_revision(question),
            "evaluation grading revision mismatch",
            "GRADING_REVISION_MISMATCH",
            "grading_revision",
        )
    require(e["supersedes_evaluation_id"] != e["_id"], "evaluation cannot supersede itself", "SCHEMA_VALIDATION_FAILED", "supersedes_evaluation_id")
    g = question["grading"]
    require(
        e["strategy"] == g["strategy"] and e["max_score"] == g["max_score"] and e["engine"] == g["engine"],
        "evaluation grading snapshot mismatch",
        "GRADING_REVISION_MISMATCH",
        "strategy",
    )
    objective = question["question_type"] != "OPEN_ENDED"
    require(e["submission"]["type"] == ("OPTION" if objective else "TEXT"), "submission type mismatch", "SCHEMA_VALIDATION_FAILED", "submission.type")
    if objective:
        require(e["submission"]["selected_index"] < len(question["answers"]), "selected option outside question", "SCHEMA_VALIDATION_FAILED", "submission.selected_index")
        require(e["rubric_revision"] is None and not e["criterion_results"], "objective evaluation cannot use rubric", "SCHEMA_VALIDATION_FAILED", "rubric_revision")
    else:
        require(bool(e["submission"]["text"].strip()), "empty open response", "SCHEMA_VALIDATION_FAILED", "submission.text")
        require(e["rubric_revision"] == g["rubric"]["revision"], "evaluation uses stale rubric", "GRADING_REVISION_MISMATCH", "rubric_revision")
    if e["status"] in ("PENDING", "FAILED"):
        require(e["score"] is None and e["is_correct"] is None and not e["criterion_results"], "ungraded response has a score", "SCHEMA_VALIDATION_FAILED", "score")
        return e
    require(e["score"] is not None and 0 <= e["score"] <= e["max_score"], "score outside bounds", "SCHEMA_VALIDATION_FAILED", "score")
    if objective:
        correct = e["submission"]["selected_index"] == question["correct_answer_indices"][0]
        require(
            e["status"] == "CONFIRMED" and e["is_correct"] == correct and e["score"] == (g["max_score"] if correct else 0),
            "objective result disagrees with answer key",
            "EVALUATION_RESULT_MISMATCH",
            "is_correct",
        )
        require(
            e["decision"] is not None
            and e["decision"]["actor"]["type"] == "SYSTEM"
            and e["decision"]["method"] == "RULE_BASED",
            "objective decision needs exact engine provenance",
            "PROVENANCE_MISMATCH",
            "decision",
        )
    else:
        criteria = {c["criterion_id"]: c for c in g["rubric"]["criteria"]}
        results = e["criterion_results"]
        require(
            len(results) == len(criteria) and {r["criterion_id"] for r in results} == set(criteria),
            "missing or duplicate evaluated criterion",
            "EVALUATION_RESULT_MISMATCH",
            "criterion_results",
        )
        require(
            all(0 <= r["awarded_points"] <= criteria[r["criterion_id"]]["max_points"] for r in results),
            "criterion score outside bounds",
            "EVALUATION_RESULT_MISMATCH",
            "criterion_results",
        )
        require(
            sum(Decimal(str(r["awarded_points"])) for r in results) == Decimal(str(e["score"])),
            "evaluation score does not equal criteria",
            "EVALUATION_RESULT_MISMATCH",
            "score",
        )
        if e["status"] == "PROVISIONAL":
            require(e["is_correct"] is None, "provisional score cannot count as confirmed correctness", "EVALUATION_RESULT_MISMATCH", "is_correct")
            require(e["decision"] is not None, "provisional result needs provenance", "PROVENANCE_MISMATCH", "decision")
            if e["strategy"] == "AI":
                require(
                    e["decision"]["actor"]["type"] == "AI"
                    and e["decision"]["method"] == "AI_SUGGESTION"
                    and all(e["decision"][key] == g["engine"][key] for key in ("model", "prompt_id", "prompt_version")),
                    "AI decision provenance differs from engine",
                    "PROVENANCE_MISMATCH",
                    "decision",
                )
            if e["strategy"] == "SEMANTIC":
                require(
                    e["decision"]["actor"]["type"] == "SYSTEM"
                    and e["decision"]["method"] == "RULE_BASED"
                    and e["decision"]["model"] == g["engine"]["semantic_model_id"],
                    "semantic decision provenance differs from engine",
                    "PROVENANCE_MISMATCH",
                    "decision",
                )
        else:
            require(
                human_review(e["decision"]),
                "open grading confirmation requires human review",
                "HUMAN_REVIEW_REQUIRED",
                "decision",
            )
            passed = e["score"] >= g["pass_score"] and all(
                not criteria[r["criterion_id"]]["essential"] or r["awarded_points"] > 0 for r in results
            )
            require(e["is_correct"] == passed, "confirmed correctness disagrees with rubric", "EVALUATION_RESULT_MISMATCH", "is_correct")
    return e


def validate_evaluation_binding(e, attempt, previous=None):
    """Bind a result to the independently stored attempt, never model-supplied identity."""
    require(
        e["attempt_id"] == attempt["attempt_id"] and e["user_id"] == attempt["owner_user_id"],
        "evaluation attempt owner mismatch",
        "EVALUATION_OWNER_MISMATCH",
        "user_id",
    )
    require(
        e["question_id"] == attempt["question_id"]
        and e["question_content_revision"] == attempt["question_content_revision"]
        and e["submission"] == attempt["submission"],
        "evaluation changed submitted answer or snapshot",
        "EVALUATION_BINDING_MISMATCH",
        "submission",
    )
    if e["supersedes_evaluation_id"] is not None:
        require(
            previous is not None
            and previous["_id"] == e["supersedes_evaluation_id"]
            and previous["attempt_id"] == e["attempt_id"]
            and previous["user_id"] == e["user_id"]
            and previous["question_content_revision"] == e["question_content_revision"],
            "superseded evaluation belongs to another attempt",
            "EVALUATION_BINDING_MISMATCH",
            "supersedes_evaluation_id",
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
    print(json.dumps({"contract": "COGNIVA_V2_1_ACTIVE", "examples_validated": counts}, indent=2))


if __name__ == "__main__":
    main()
