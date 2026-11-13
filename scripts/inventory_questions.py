"""Dry-run inventory of question documents against Cogniva V2 contracts. Never writes to MongoDB."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATES = (
    "legacy_needs_migration",
    "v2_confirmed",
    "v2_partial",
    "v2_unclassified",
    "invalid_ambiguous",
    "obsolete_under_review",
    "insufficient_metadata",
)


def load_catalog():
    return json.loads((ROOT / "shared" / "knowledge-catalog.json").read_text(encoding="utf-8"))


def _topics(catalog):
    return {topic["topic_id"]: topic for topic in catalog["topics"] if topic["status"] == "ACTIVE"}


def _subjects(catalog):
    return {subject["subject_id"]: subject for subject in catalog["subjects"] if subject["status"] == "ACTIVE"}


def _text_list(value):
    return isinstance(value, list) and value and all(isinstance(item, str) and item.strip() for item in value)


def _answer_key_ambiguous(document):
    answers = document.get("answers")
    if not _text_list(answers):
        return True
    texts = document.get("correct_answers")
    indices = document.get("correct_answer_indices")
    if texts in (None, []) and indices in (None, []):
        return True
    mapped = []
    if isinstance(texts, list) and texts:
        for text in texts:
            if not isinstance(text, str):
                return True
            hits = [index for index, answer in enumerate(answers) if answer.strip() == text.strip()]
            if len(hits) != 1:
                return True
            mapped.append(hits[0])
    if isinstance(indices, list) and indices:
        if not all(type(index) is int and 0 <= index < len(answers) for index in indices):
            return True
        if mapped and sorted(set(mapped)) != sorted(set(indices)):
            return True
    return len(indices if isinstance(indices, list) and indices else mapped) != 1


def _human(provenance):
    return (
        isinstance(provenance, dict)
        and provenance.get("actor", {}).get("type") == "HUMAN"
        and provenance.get("method") == "MANUAL_REVIEW"
    )


def _valid_links(links, topics):
    seen = set()
    primaries = []
    for link in links:
        if not isinstance(link, dict):
            return None
        topic = topics.get(link.get("topic_id"))
        if topic is None or topic["subject_id"] != link.get("subject_id"):
            return None
        if link.get("topic_id") in seen:
            return None
        seen.add(link["topic_id"])
        if link.get("role") == "PRIMARY":
            if link.get("coverage") != "DIRECT" or not _human(link.get("provenance")):
                return None
            primaries.append(link)
        elif link.get("role") != "SECONDARY" or not _human(link.get("provenance")):
            return None
    return primaries


def classify_document(document, catalog):
    """Classify a raw or V2 document into an inventory bucket without mutating it."""
    if not isinstance(document, dict):
        return "insufficient_metadata"
    question = document.get("question")
    if not isinstance(question, str) or not question.strip():
        return "insufficient_metadata"

    if document.get("schema_version") == 2:
        from question_contracts import validate_question

        try:
            validate_question(document, catalog)
        except (ValueError, KeyError, TypeError):
            return "invalid_ambiguous"
        validity = document.get("validity") if isinstance(document.get("validity"), dict) else {}
        if document.get("status") == "ARCHIVED" or validity.get("status") in ("OBSOLETE", "UNDER_REVIEW"):
            return "obsolete_under_review"
        classification = document.get("classification", {})
        state = classification.get("state")
        links = classification.get("links", [])
        if state == "UNCLASSIFIED" and links == []:
            return "v2_unclassified"
        if state in ("CONFIRMED", "PARTIAL") and isinstance(links, list) and links:
            primaries = _valid_links(links, _topics(catalog))
            if primaries is None or len(primaries) > 1 or (state == "CONFIRMED" and len(primaries) != 1):
                return "invalid_ambiguous"
            return "v2_confirmed" if state == "CONFIRMED" else "v2_partial"
        return "invalid_ambiguous"

    if not _text_list(document.get("answers")):
        return "insufficient_metadata"
    validity = document.get("validity") if isinstance(document.get("validity"), dict) else {}
    legal_validity = document.get("legal_validity") if isinstance(document.get("legal_validity"), dict) else {}
    if (
        document.get("status") == "ARCHIVED"
        or validity.get("status") in ("OBSOLETE", "UNDER_REVIEW")
        or legal_validity.get("status") in ("OUTDATED_LAW", "UNDER_REVIEW")
    ):
        return "obsolete_under_review"
    if _answer_key_ambiguous(document):
        return "invalid_ambiguous"
    subject_id = document.get("canonical_subject_id")
    topic_id = document.get("canonical_topic_id")
    if subject_id not in (None, "") or topic_id not in (None, ""):
        topics = _topics(catalog)
        subjects = _subjects(catalog)
        if subject_id not in subjects or (topic_id not in (None, "") and (topic_id not in topics or topics[topic_id]["subject_id"] != subject_id)):
            return "invalid_ambiguous"
    return "legacy_needs_migration"


def inventory(documents, catalog):
    counts = {state: 0 for state in STATES}
    for document in documents:
        counts[classify_document(document, catalog)] += 1
    return {
        "dry_run": True,
        "documents_read": len(documents),
        "documents_modified": 0,
        "counts": counts,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not args.dry_run:
        raise SystemExit("inventory_questions.py só executa com --dry-run e nunca altera documentos.")
    documents = json.loads(args.input.read_text(encoding="utf-8"))
    if not isinstance(documents, list):
        raise SystemExit("A entrada deve ser uma lista de documentos JSON.")
    report = inventory(documents, load_catalog())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
