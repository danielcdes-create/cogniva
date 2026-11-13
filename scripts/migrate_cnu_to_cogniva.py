#!/usr/bin/env python3
"""Idempotent, strictly read-only on CNU migration of questions and catalogs into Cogniva 2.0 (`cogniva`).

- Reads `todasQuestoes` from source database (`CNU` by default) with ZERO writes to source.
- Normalizes every legacy V1 or early V2 question into a strict `schema_version = 2` document
  with JCS SHA-256 `content_revision`, `LEGACY_IMPORT` provenance, preserved pedagogical caches,
  and NFKD-normalized discipline labels against `shared/knowledge-catalog.json`.
- Validates every normalized document against `scripts/question_contracts.py` BEFORE writing.
- Seeds `knowledge_catalog_versions` and `exam_templates` idempotently into target database (`cogniva`).
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import knowledge_catalog as kc
import question_contracts as qc

ROOT = kc.ROOT
MIGRATION_TIMESTAMP = "2026-11-12T00:00:00Z"


def deterministic_uuid(seed: str) -> str:
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest[:16], version=4))


def subject_lookup_table(catalog: dict) -> dict[str, dict]:
    table: dict[str, dict] = {}
    for subj in catalog["subjects"]:
        if subj["status"] != "ACTIVE":
            continue
        table[kc.normalized(subj["subject_id"])] = subj
        table[kc.normalized(subj["label"])] = subj
        for alias in subj.get("aliases", []):
            table[kc.normalized(alias)] = subj
    return table


def _oid_hex(raw_id) -> str:
    if isinstance(raw_id, dict) and "$oid" in raw_id:
        return str(raw_id["$oid"]).lower()
    text = str(raw_id).strip().lower()
    if len(text) == 24 and all(c in "0123456789abcdef" for c in text):
        return text
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:24]


def _resolve_objective_answers(raw: dict) -> tuple[str, list[str], list[int], list[str]]:
    raw_answers = raw.get("answers")
    if not isinstance(raw_answers, list) or not raw_answers:
        raise ValueError("missing answers list")
    answers = [str(a).strip() for a in raw_answers]
    if any(not a for a in answers):
        raise ValueError("blank answer option")
    if len(set(answers)) != len(answers):
        raise ValueError("duplicate answer options")

    raw_indices = raw.get("correct_answer_indices")
    raw_texts = raw.get("correct_answers")
    mapped: list[int] = []
    if isinstance(raw_texts, list) and raw_texts:
        for t in raw_texts:
            t_clean = str(t).strip()
            hits = [i for i, ans in enumerate(answers) if ans == t_clean]
            if len(hits) != 1:
                raise ValueError("ambiguous textual correct_answers")
            mapped.append(hits[0])

    indices: list[int] = []
    if isinstance(raw_indices, list) and raw_indices:
        if not all(type(idx) is int and 0 <= idx < len(answers) for idx in raw_indices):
            raise ValueError("invalid correct_answer_indices")
        indices = sorted(set(raw_indices))
        if mapped and sorted(set(mapped)) != indices:
            raise ValueError("contradictory correct_answers and correct_answer_indices")
    else:
        indices = sorted(set(mapped))

    if len(indices) != 1:
        raise ValueError("expected exactly 1 correct answer index")

    correct_idx = indices[0]

    # Detect TRUE_FALSE (2 options matching Certo/Errado or Verdadeiro/Falso)
    explicit_type = raw.get("question_type")
    norm_pair = [kc.normalized(a) for a in answers]
    tf_true = {"certo", "verdadeiro", "c", "v"}
    tf_false = {"errado", "falso", "e", "f"}
    if len(answers) == 2 and (
        explicit_type == "TRUE_FALSE"
        or (norm_pair[0] in tf_true and norm_pair[1] in tf_false)
        or (norm_pair[0] in tf_false and norm_pair[1] in tf_true)
    ):
        is_correct_true = norm_pair[correct_idx] in tf_true or (explicit_type == "TRUE_FALSE" and answers == ["Certo", "Errado"] and correct_idx == 0)
        norm_answers = ["Certo", "Errado"]
        norm_idx = 0 if is_correct_true else 1
        return "TRUE_FALSE", norm_answers, [norm_idx], [norm_answers[norm_idx]]

    if len(answers) not in (4, 5):
        raise ValueError(f"unsupported multiple-choice option count: {len(answers)}")
    return "MULTIPLE_CHOICE", answers, [correct_idx], [answers[correct_idx]]


def _clean_optional_str(val, max_len: int = 20000) -> str | None:
    if not isinstance(val, str):
        return None
    cleaned = val.strip()
    if not cleaned:
        return None
    return cleaned[:max_len]


def normalize_question_to_v2(raw_doc: dict, catalog: dict, recorded_at: str = MIGRATION_TIMESTAMP) -> dict:
    """Pure, deterministic transformation of a raw CNU V1 or V2 question into Cogniva 2.0 V2 format."""
    if not isinstance(raw_doc, dict):
        raise ValueError("document must be a dict")

    oid = _oid_hex(raw_doc.get("_id", ""))
    question_text = _clean_optional_str(raw_doc.get("question"), 20000)
    if not question_text:
        raise ValueError("empty question text")

    subj_lookup = subject_lookup_table(catalog)
    catalog_ver = int(catalog["catalog_version"])

    # If already schema_version == 2, upgrade catalog_version if valid and validate strictly
    if raw_doc.get("schema_version") == 2:
        doc = copy.deepcopy(raw_doc)
        doc["_id"] = {"$oid": oid}
        doc["question"] = question_text
        if "classification" in doc and isinstance(doc["classification"], dict):
            if doc["classification"].get("state") == "UNCLASSIFIED":
                doc["classification"]["catalog_version"] = catalog_ver
                doc["canonical_subject_id"] = None
                doc["canonical_topic_id"] = None
        raw_disc = _clean_optional_str(doc.get("disciplina"), 300)
        if raw_disc:
            matched_subj = subj_lookup.get(kc.normalized(raw_disc))
            doc["disciplina"] = matched_subj["label"] if matched_subj else raw_disc
        doc["content_revision"] = qc.content_revision(doc)
        doc["content_hash_version"] = "question-v2-jcs-sha256"
        qc.validate_question(doc, catalog)
        return doc

    q_type, answers, correct_indices, correct_answers = _resolve_objective_answers(raw_doc)
    grading = {
        "strategy": "EXACT_OPTION",
        "max_score": 100,
        "pass_score": 60,
        "expected_responses": [],
        "rubric": None,
        "engine": None,
        "confirmation_policy": "AUTOMATIC_EXACT",
        "policy_version": "objective-exact/1",
    }

    raw_disc = _clean_optional_str(raw_doc.get("disciplina"), 300) or _clean_optional_str(raw_doc.get("canonical_subject_id"), 300) or "Geral"
    matched_subj = subj_lookup.get(kc.normalized(raw_disc))
    normalized_disciplina = matched_subj["label"] if matched_subj else raw_disc

    exam_year = raw_doc.get("exam_year")
    if not (type(exam_year) is int and 1900 <= exam_year <= 2200):
        exam_year = None

    source_str = _clean_optional_str(raw_doc.get("source"), 300)
    level_str = _clean_optional_str(raw_doc.get("level"), 300)

    origins = [
        {
            "origin_id": deterministic_uuid(f"cogniva-v2-origin:{oid}"),
            "kind": "LEGACY_IMPORT",
            "verification": "PENDING",
            "source_exam_code": None,
            "institution": None,
            "organizer": source_str,
            "exam_year": exam_year,
            "question_number": None,
            "source_reference": None,
            "provenance": {
                "actor": {"type": "SYSTEM", "id": "cogniva-2.0-migrator"},
                "method": "LEGACY_IMPORT",
                "rationale": " Questão importada de modo somente-leitura do acervo legado para curadoria editorial V2.".strip(),
                "recorded_at": recorded_at,
                "evidence": [],
                "model": None,
                "prompt_id": None,
                "prompt_version": None,
                "source_suggestion_id": None,
            },
        }
    ]

    # Map legacy legal_validity safely without inventing human review decisions
    legal_val = raw_doc.get("legal_validity") if isinstance(raw_doc.get("legal_validity"), dict) else {}
    legacy_status = str(legal_val.get("status") or "ACTIVE").upper()
    if legacy_status in ("ACTIVE", "VIGENTE"):
        val_status = "UNKNOWN"
        reason_codes = []
    else:
        val_status = "UNDER_REVIEW"
        reason_codes = ["REVIEW_REQUESTED"]

    validity = {
        "revision": 0,
        "status": val_status,
        "basis": "LEGAL",
        "reference_date": None,
        "review_due_on": None,
        "reason_codes": reason_codes,
        "evaluated_content_revision": None,
        "decision": None,
        "scope": "CURRENT_KNOWLEDGE",
        "context_date": None,
    }

    classification = {
        "revision": 0,
        "catalog_version": catalog_ver,
        "state": "UNCLASSIFIED",
        "links": [],
    }

    hints_raw = raw_doc.get("contextualHint")
    hints = []
    if isinstance(hints_raw, list):
        for h in hints_raw[:3]:
            cleaned = _clean_optional_str(h, 20000)
            if cleaned is not None:
                hints.append(cleaned)

    concursos_raw = raw_doc.get("concursos")
    if isinstance(concursos_raw, str):
        concursos = concursos_raw
    elif isinstance(concursos_raw, list) and all(isinstance(x, str) for x in concursos_raw):
        concursos = concursos_raw
    else:
        concursos = None

    normalized_doc = {
        "_id": {"$oid": oid},
        "schema_version": 2,
        "revision": 1,
        "content_revision": "",
        "content_hash_version": "question-v2-jcs-sha256",
        "status": "ARCHIVED" if raw_doc.get("status") == "ARCHIVED" else "ACTIVE",
        "question": question_text,
        "question_type": q_type,
        "answers": answers,
        "correct_answer_indices": correct_indices,
        "correct_answers": correct_answers,
        "origins": origins,
        "classification": classification,
        "validity": validity,
        "grading": grading,
        "disciplina": normalized_disciplina,
        "canonical_subject_id": None,
        "canonical_topic_id": None,
        "source": source_str,
        "exam_year": exam_year,
        "level": level_str,
        "concursos": concursos,
        "explanation": _clean_optional_str(raw_doc.get("explanation"), 20000),
        "drSincero": _clean_optional_str(raw_doc.get("drSincero"), 20000),
        "ajudaInfantil": _clean_optional_str(raw_doc.get("ajudaInfantil"), 20000),
        "explicacaoInfantil": _clean_optional_str(raw_doc.get("explicacaoInfantil"), 20000),
        "explanationExtra": _clean_optional_str(raw_doc.get("explanationExtra"), 20000),
        "mnemonico": _clean_optional_str(raw_doc.get("mnemonico"), 20000),
        "plausibilityMarkdown": _clean_optional_str(raw_doc.get("plausibilityMarkdown"), 20000),
        "contextualHint": hints,
    }
    normalized_doc["content_revision"] = qc.content_revision(normalized_doc)
    qc.validate_question(normalized_doc, catalog)
    return normalized_doc


def migrate_batch(raw_docs: list[dict], catalog: dict) -> tuple[list[dict], dict]:
    """Normalize a batch of raw documents and return `(valid_v2_docs, stats)` without PII/content."""
    subj_lookup = subject_lookup_table(catalog)
    migrated: list[dict] = []
    skipped: list[dict] = []
    mapped_subjects = 0
    unmapped_subjects = 0

    for raw in raw_docs:
        oid = _oid_hex(raw.get("_id", "")) if isinstance(raw, dict) else "unknown"
        try:
            v2 = normalize_question_to_v2(raw, catalog)
            if kc.normalized(v2.get("disciplina", "")) in subj_lookup:
                mapped_subjects += 1
            else:
                unmapped_subjects += 1
            migrated.append(v2)
        except Exception as exc:
            skipped.append({"id": oid, "reason": str(exc)})

    stats = {
        "source_documents_read": len(raw_docs),
        "normalized_v2_valid": len(migrated),
        "mapped_discipline_count": mapped_subjects,
        "unmapped_discipline_count": unmapped_subjects,
        "skipped_invalid_count": len(skipped),
        "skipped_invalid": skipped[:50],
    }
    return migrated, stats


def _to_mongo_doc(v2_extjson: dict) -> dict:
    from bson import ObjectId

    doc = copy.deepcopy(v2_extjson)
    doc["_id"] = ObjectId(doc["_id"]["$oid"])
    return doc


def run_mongo_migration(
    source_uri: str,
    source_db_name: str,
    target_uri: str,
    target_db_name: str,
    dry_run: bool,
) -> dict:
    from bson import json_util
    from pymongo import MongoClient, ReplaceOne

    if source_uri == target_uri and source_db_name == target_db_name:
        raise ValueError("source and target database cannot be identical; CNU must remain untouched")

    catalog = kc.read(ROOT / "shared/knowledge-catalog.json")
    templates_bundle = kc.read(ROOT / "shared/exam-templates.json")

    src_client = MongoClient(source_uri, appname="cogniva-2.0-readonly-migrator")
    try:
        src_db = src_client.get_database(source_db_name)
        raw_cursor = src_db.get_collection("todasQuestoes").find({})
        raw_docs = [json.loads(json_util.dumps(d)) for d in raw_cursor]
    finally:
        src_client.close()

    migrated, stats = migrate_batch(raw_docs, catalog)
    stats["dry_run"] = dry_run
    stats["source_db"] = source_db_name
    stats["target_db"] = target_db_name
    stats["source_writes"] = 0

    if dry_run:
        return stats

    tgt_client = MongoClient(target_uri, appname="cogniva-2.0-target-writer")
    try:
        tgt_db = tgt_client.get_database(target_db_name)
        if migrated:
            ops = []
            for v2 in migrated:
                mdoc = _to_mongo_doc(v2)
                ops.append(ReplaceOne({"_id": mdoc["_id"]}, mdoc, upsert=True))
            res = tgt_db.get_collection("todasQuestoes").bulk_write(ops, ordered=False)
            stats["questions_upserted"] = res.upserted_count + res.modified_count + res.matched_count

        # Seed unified catalog v2 idempotently
        cat_key = f"{catalog['catalog_id']}:{catalog['catalog_version']}"
        tgt_db.get_collection("knowledge_catalog_versions").update_one(
            {"_id": cat_key},
            {
                "$setOnInsert": {
                    "catalog_id": catalog["catalog_id"],
                    "version": catalog["catalog_version"],
                    "payload": catalog,
                }
            },
            upsert=True,
        )

        # Seed global exam templates idempotently (insert only if absent so admin edits are preserved)
        tpl_coll = tgt_db.get_collection("exam_templates")
        seeded_templates = 0
        now_dt = datetime.now(timezone.utc)
        for tpl in templates_bundle["templates"]:
            tpl_doc = copy.deepcopy(tpl)
            tpl_doc["exam_id"] = tpl_doc["_id"]
            tpl_doc["updated_at"] = now_dt
            up = tpl_coll.update_one({"_id": tpl_doc["_id"]}, {"$setOnInsert": tpl_doc}, upsert=True)
            if up.upserted_id is not None:
                seeded_templates += 1
        stats["exam_templates_seeded"] = seeded_templates
    finally:
        tgt_client.close()

    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-uri", default=os.environ.get("SOURCE_MONGODB_URI", ""))
    parser.add_argument("--source-db", default="CNU")
    parser.add_argument("--target-uri", default=os.environ.get("MONGODB_URI", ""))
    parser.add_argument("--target-db", default=os.environ.get("DATABASE_NAME", "cogniva"))
    parser.add_argument("--input-json", type=Path, help="Optional offline JSON file instead of MongoDB source")
    parser.add_argument("--output-report", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if args.input_json:
        catalog = kc.read(ROOT / "shared/knowledge-catalog.json")
        raw_docs = json.loads(args.input_json.read_text(encoding="utf-8"))
        _, report = migrate_batch(raw_docs, catalog)
        report["dry_run"] = True
        report["source_writes"] = 0
    else:
        if not args.source_uri:
            raise SystemExit("Informe --source-uri (ou SOURCE_MONGODB_URI) ou --input-json.")
        target_uri = args.target_uri or args.source_uri
        report = run_mongo_migration(
            source_uri=args.source_uri,
            source_db_name=args.source_db,
            target_uri=target_uri,
            target_db_name=args.target_db,
            dry_run=args.dry_run,
        )

    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output_report:
        args.output_report.parent.mkdir(parents=True, exist_ok=True)
        args.output_report.write_text(output + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
