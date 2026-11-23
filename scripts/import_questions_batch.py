#!/usr/bin/env python3
"""Idempotent, deduplicated batch question importer and inspector for Cogniva 2.0 (`cogniva.todasQuestoes`).

Guarantees:
- Zero duplicate or repeated questions: deduplicates both within the input files and against
  existing documents in `todasQuestoes` by:
  1. `content_revision` (RFC 8785 JCS SHA-256 of `{question_type, question, answers, correct_answer_indices, grading}`),
  2. Recomputed `qc.content_revision(existing_doc)` across all existing documents,
  3. NFKD-normalized `question` text (`kc.normalized(question)`),
  4. Deterministic `_id` (`ObjectId` from `content_revision[:24]`).
- Strict `schema_version = 2` validation via `scripts/question_contracts.py` BEFORE any database write.
- Preserves all existing questions in `todasQuestoes` untouched (insert-only via `$setOnInsert`).
"""
from __future__ import annotations

from collections import Counter
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import uuid

import rfc8785

sys.path.insert(0, str(Path(__file__).resolve().parent))
import knowledge_catalog as kc
import question_contracts as qc

ROOT = kc.ROOT
DEFAULT_RECORDED_AT = "2026-11-17T10:30:00Z"


def deterministic_uuid(seed: str) -> str:
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest[:16], version=4))


def rfc8785_sha256(value: dict) -> str:
    return hashlib.sha256(rfc8785.dumps(value)).hexdigest()


def build_v2_question_from_simplified(
    item: dict,
    catalog: dict,
    actor_user_id: int | str = 1,
    recorded_at: str = DEFAULT_RECORDED_AT,
    disciplina_override: str | None = None,
    concursos_override: list[str] | str | None = None,
    subject_id_override: str | None = None,
    topic_id_override: str | None = None,
    confirm_classification_override: bool | None = None,
) -> dict:
    """Convert a simplified Cogniva import object into a strict Schema v2 question document."""
    if not isinstance(item, dict):
        raise ValueError("each question item must be a JSON object")

    if item.get("schema_version") == 2:
        doc = copy.deepcopy(item)
        qc.populate_v2_1_hashes(doc)
        qc.validate_question(doc, catalog)
        return doc

    kind = str(item.get("question_type") or "").strip()
    if kind not in ("MULTIPLE_CHOICE", "TRUE_FALSE", "OPEN_ENDED"):
        raise ValueError(f"unsupported question_type: {kind!r}")

    question_text = str(item.get("question") or "").strip()
    if not question_text:
        raise ValueError("empty question text")

    origin_kind = str(item.get("origin_kind") or "AUTHOR_CREATED").strip()
    if origin_kind not in ("AUTHOR_CREATED", "LEGACY_IMPORT", "OFFICIAL_EXAM"):
        raise ValueError(f"invalid origin_kind: {origin_kind!r}")

    raw_answers = item.get("answers")
    if not isinstance(raw_answers, list):
        raise ValueError("answers must be a list")
    answers = [str(a).strip() for a in raw_answers]

    raw_indices = item.get("correct_answer_indices")
    if not isinstance(raw_indices, list):
        raise ValueError("correct_answer_indices must be a list")
    indices = [int(i) for i in raw_indices]
    correct_answers = [answers[i] for i in indices]

    if kind == "OPEN_ENDED":
        grading = copy.deepcopy(item.get("grading"))
    else:
        if item.get("grading") is not None:
            raise ValueError("objective simplified item must have grading=null")
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

    # Compute canonical content_revision first so identity is 100% deterministic by content
    temp_for_hash = {
        "question_type": kind,
        "question": question_text,
        "answers": answers,
        "correct_answer_indices": indices,
        "grading": grading,
    }
    content_rev = qc.content_revision(temp_for_hash)
    oid_hex = content_rev[:24]
    seed = f"cogniva-v2-import:{content_rev}"

    actor_id_str = str(actor_user_id)
    provenance = {
        "actor": {"type": "HUMAN", "id": actor_id_str},
        "method": "MANUAL_REVIEW",
        "rationale": "Cadastro conferido pelo responsável autenticado.",
        "recorded_at": recorded_at,
        "evidence": [],
        "model": None,
        "prompt_id": None,
        "prompt_version": None,
        "source_suggestion_id": None,
    }

    eff_subject_id = subject_id_override if subject_id_override is not None else item.get("subject_id")
    eff_topic_id = topic_id_override if topic_id_override is not None else item.get("topic_id")
    eff_confirm_cls = (
        confirm_classification_override
        if confirm_classification_override is not None
        else bool(item.get("confirm_classification"))
    )

    catalog_ver = int(catalog["catalog_version"])
    if eff_confirm_cls and eff_subject_id and eff_topic_id:
        classification = {
            "revision": 1,
            "catalog_version": catalog_ver,
            "state": "CONFIRMED",
            "links": [
                {
                    "link_id": deterministic_uuid(f"{seed}:link:{eff_subject_id}:{eff_topic_id}"),
                    "subject_id": eff_subject_id,
                    "topic_id": eff_topic_id,
                    "role": "PRIMARY",
                    "coverage": "DIRECT",
                    "provenance": copy.deepcopy(provenance),
                }
            ],
        }
        canonical_subject_id = eff_subject_id
        canonical_topic_id = eff_topic_id
    else:
        classification = {
            "revision": 0,
            "catalog_version": catalog_ver,
            "state": "UNCLASSIFIED",
            "links": [],
        }
        canonical_subject_id = None
        canonical_topic_id = None

    confirm_origin = bool(item.get("confirm_origin"))
    source_reference = item.get("source_reference")
    if confirm_origin and not source_reference:
        raise ValueError("confirm_origin=True requires a non-null source_reference")
    verification = "CONFIRMED" if (origin_kind == "AUTHOR_CREATED" or confirm_origin) else "PENDING"

    origins = [
        {
            "origin_id": deterministic_uuid(f"{seed}:origin"),
            "kind": origin_kind,
            "verification": verification,
            "source_exam_code": item.get("source_exam_code"),
            "institution": item.get("institution"),
            "organizer": item.get("organizer"),
            "exam_year": item.get("exam_year"),
            "question_number": item.get("question_number"),
            "source_reference": source_reference,
            "provenance": copy.deepcopy(provenance),
        }
    ]

    validity = {
        "revision": 0,
        "status": "UNKNOWN",
        "basis": "FACTUAL",
        "reference_date": None,
        "review_due_on": None,
        "reason_codes": [],
        "evaluated_content_revision": None,
        "decision": None,
        "scope": "CURRENT_KNOWLEDGE",
        "context_date": None,
    }

    explanation = item.get("explanation")
    if isinstance(explanation, str):
        explanation = explanation.strip() or None

    level = item.get("level")
    if isinstance(level, str):
        level = level.strip() or None

    source_label = "Autoral" if origin_kind == "AUTHOR_CREATED" else (item.get("organizer") or "Origem não informada")

    doc = {
        "_id": {"$oid": oid_hex},
        "schema_version": 2,
        "revision": 1,
        "content_revision": content_rev,
        "content_hash_version": "question-v2-jcs-sha256",
        "status": "ACTIVE",
        "question_type": kind,
        "question": question_text,
        "answers": answers,
        "correct_answer_indices": indices,
        "correct_answers": correct_answers,
        "explanation": explanation,
        "level": level,
        "exam_year": item.get("exam_year"),
        "source": source_label,
        "canonical_subject_id": canonical_subject_id,
        "canonical_topic_id": canonical_topic_id,
        "origins": origins,
        "classification": classification,
        "validity": validity,
        "grading": grading,
    }

    if disciplina_override:
        doc["disciplina"] = disciplina_override
    if concursos_override is not None:
        doc["concursos"] = concursos_override

    qc.populate_v2_1_hashes(doc)
    qc.validate_question(doc, catalog)
    return doc


def resolve_batch_files(batch_dir: Path) -> list[Path]:
    """Validate that `batch_dir` exists and contains at least one `.json` batch file."""
    if not batch_dir.is_dir():
        raise FileNotFoundError(f"Diretório de lotes não encontrado: {batch_dir}")
    batch_files = sorted(batch_dir.glob("*.json"))
    if not batch_files:
        raise ValueError(f"Nenhum arquivo JSON de lote encontrado em: {batch_dir}")
    return batch_files


def load_batch_files(batch_paths: list[Path]) -> list[tuple[str, int, dict]]:
    """Read and parse all JSON files (handling UTF-8 BOM transparently)."""
    records: list[tuple[str, int, dict]] = []
    for path in sorted(batch_paths):
        raw_text = path.read_text(encoding="utf-8-sig")
        data = json.loads(raw_text)
        if not isinstance(data, list):
            raise ValueError(f"{path.name}: top-level JSON must be a list")
        for idx, item in enumerate(data):
            records.append((path.name, idx, item))
    return records


def deduplicate_and_prepare(
    records: list[tuple[str, int, dict]],
    catalog: dict,
    existing_docs: list[dict],
    actor_user_id: int | str = 1,
    recorded_at: str = DEFAULT_RECORDED_AT,
    file_classification_map: dict[str, dict] | None = None,
) -> tuple[list[tuple[str, int, dict]], dict]:
    """Deduplicate input records against `existing_docs` and within the batch, returning `(to_insert, report)`."""
    existing_oids: set[str] = set()
    existing_content_revs: set[str] = set()
    existing_norm_questions: set[str] = set()

    for edoc in existing_docs:
        if not isinstance(edoc, dict):
            continue
        raw_id = edoc.get("_id")
        if isinstance(raw_id, dict) and "$oid" in raw_id:
            existing_oids.add(str(raw_id["$oid"]).lower())
        elif raw_id is not None:
            existing_oids.add(str(raw_id).lower())

        crev = edoc.get("content_revision")
        if isinstance(crev, str) and crev:
            existing_content_revs.add(crev)
        try:
            recomputed = qc.content_revision(edoc)
            existing_content_revs.add(recomputed)
        except Exception:
            pass

        q_txt = edoc.get("question")
        if isinstance(q_txt, str) and q_txt.strip():
            existing_norm_questions.add(kc.normalized(q_txt))

    batch_seen_oids: set[str] = set()
    batch_seen_revs: set[str] = set()
    batch_seen_norms: set[str] = set()

    to_insert: list[tuple[str, int, dict]] = []
    per_file_stats: dict[str, dict] = {}
    skipped_existing: list[dict] = []
    skipped_intra_batch: list[dict] = []

    f_map = file_classification_map or {}

    for fname, idx, item in records:
        fstat = per_file_stats.setdefault(
            fname,
            {"total_in_file": 0, "new_to_insert": 0, "skipped_already_in_db": 0, "skipped_duplicate_in_batch": 0},
        )
        fstat["total_in_file"] += 1

        overrides = f_map.get(fname) or {}
        v2_doc = build_v2_question_from_simplified(
            item,
            catalog,
            actor_user_id=actor_user_id,
            recorded_at=recorded_at,
            disciplina_override=overrides.get("disciplina"),
            concursos_override=overrides.get("concursos"),
            subject_id_override=overrides.get("subject_id"),
            topic_id_override=overrides.get("topic_id"),
            confirm_classification_override=overrides.get("confirm_classification"),
        )

        oid = v2_doc["_id"]["$oid"].lower()
        crev = v2_doc["content_revision"]
        norm_q = kc.normalized(v2_doc["question"])

        if (
            oid in existing_oids
            or crev in existing_content_revs
            or norm_q in existing_norm_questions
        ):
            fstat["skipped_already_in_db"] += 1
            skipped_existing.append(
                {
                    "file": fname,
                    "index": idx,
                    "oid": oid,
                    "content_revision": crev,
                    "matched_by_oid": oid in existing_oids,
                    "matched_by_content_revision": crev in existing_content_revs,
                    "matched_by_normalized_question": norm_q in existing_norm_questions,
                }
            )
            continue

        if (
            oid in batch_seen_oids
            or crev in batch_seen_revs
            or norm_q in batch_seen_norms
        ):
            fstat["skipped_duplicate_in_batch"] += 1
            skipped_intra_batch.append(
                {
                    "file": fname,
                    "index": idx,
                    "oid": oid,
                    "content_revision": crev,
                }
            )
            continue

        batch_seen_oids.add(oid)
        batch_seen_revs.add(crev)
        batch_seen_norms.add(norm_q)
        fstat["new_to_insert"] += 1
        to_insert.append((fname, idx, v2_doc))

    report = {
        "input_files_count": len(per_file_stats),
        "input_questions_total": len(records),
        "existing_db_questions_total": len(existing_docs),
        "new_questions_to_insert": len(to_insert),
        "skipped_already_in_db_count": len(skipped_existing),
        "skipped_duplicate_in_batch_count": len(skipped_intra_batch),
        "per_file": per_file_stats,
        "skipped_already_in_db_sample": skipped_existing[:20],
        "skipped_duplicate_in_batch_sample": skipped_intra_batch[:20],
    }
    return to_insert, report


def inspect_and_import_mongo(
    mongo_uri: str,
    target_db_name: str,
    batch_dir: Path,
    dry_run: bool = True,
    file_classification_map: dict[str, dict] | None = None,
) -> dict:
    batch_files = resolve_batch_files(batch_dir)

    from bson import Int64, ObjectId, json_util
    from pymongo import MongoClient

    bundled_catalog = kc.read(ROOT / "shared/knowledge-catalog.json")
    bundled_registry = kc.read(ROOT / "shared/knowledge-id-registry.json")

    import migrate_cnu_to_cogniva as migrator

    client = MongoClient(mongo_uri, appname="cogniva-2.0-batch-question-importer")
    try:
        db = client.get_database(target_db_name)
        active_catalog, active_registry, head_act_id = migrator._read_target_active_catalog(
            db, bundled_catalog, bundled_registry
        )

        # Inspect admin user in `usuarios`
        admin_user = db.get_collection("usuarios").find_one({"role": "admin"})
        actor_user_id = int(admin_user["user_id"]) if admin_user and "user_id" in admin_user else 1

        # Read all existing questions in `cogniva.todasQuestoes`
        raw_existing = [
            json.loads(json_util.dumps(d, json_options=json_util.RELAXED_JSON_OPTIONS))
            for d in db.get_collection("todasQuestoes").find({})
        ]

        # Summarize existing questions in `cogniva.todasQuestoes`
        disc_counter = Counter()
        subj_counter = Counter()
        topic_counter = Counter()
        source_counter = Counter()
        cls_state_counter = Counter()
        concursos_counter = Counter()
        financas_samples = []

        for q in raw_existing:
            disc = str(q.get("disciplina") or "<none>")
            disc_counter[disc] += 1
            subj_counter[str(q.get("canonical_subject_id") or "<none>")] += 1
            topic_counter[str(q.get("canonical_topic_id") or "<none>")] += 1
            source_counter[str(q.get("source") or "<none>")] += 1
            cls_state = str((q.get("classification") or {}).get("state") or "<none>")
            cls_state_counter[cls_state] += 1
            conc = q.get("concursos")
            if isinstance(conc, list):
                for c in conc:
                    concursos_counter[str(c)] += 1
            else:
                concursos_counter[str(conc or "<none>")] += 1

            if (
                "finan" in kc.normalized(disc)
                or q.get("canonical_subject_id") == "MFIN"
                or q.get("source") in ("Gerada por IA", "Autoral")
            ):
                if len(financas_samples) < 5:
                    financas_samples.append(
                        {
                            "_id": q.get("_id"),
                            "question_prefix": str(q.get("question") or "")[:90],
                            "question_type": q.get("question_type"),
                            "disciplina": q.get("disciplina"),
                            "canonical_subject_id": q.get("canonical_subject_id"),
                            "canonical_topic_id": q.get("canonical_topic_id"),
                            "source": q.get("source"),
                            "level": q.get("level"),
                            "concursos": q.get("concursos"),
                            "origins": q.get("origins"),
                            "classification": q.get("classification"),
                            "validity": q.get("validity"),
                        }
                    )

        # Inspect MFIN topics in active_catalog
        mfin_catalog_topics = [
            {
                "topic_id": t["topic_id"],
                "subject_id": t["subject_id"],
                "parent_topic_id": t.get("parent_topic_id"),
                "kind": t.get("kind"),
                "label": t["label"],
                "aliases": t.get("aliases", []),
                "status": t["status"],
            }
            for t in active_catalog.get("topics", [])
            if t.get("subject_id") == "MFIN" or "BCB" in str(t.get("topic_id", ""))
        ]

        # Inspect BCB 2027 - EF in exam_templates and user_exams
        bcb_templates = []
        for coll_name in ("exam_templates", "user_exams"):
            if coll_name in set(db.list_collection_names()):
                for doc in db.get_collection(coll_name).find({}):
                    ed = json.loads(json_util.dumps(doc, json_options=json_util.RELAXED_JSON_OPTIONS))
                    if "bcb" in kc.normalized(str(ed.get("short_name") or "")) or "banco central" in kc.normalized(str(ed.get("institution") or "")):
                        fin_subjects = [
                            s
                            for s in (ed.get("subjects") or [])
                            if s.get("canonical_subject_id") == "MFIN"
                            or "finan" in kc.normalized(str(s.get("display_name") or ""))
                        ]
                        bcb_templates.append(
                            {
                                "collection": coll_name,
                                "_id": ed.get("_id"),
                                "exam_id": ed.get("exam_id"),
                                "short_name": ed.get("short_name"),
                                "revision": ed.get("revision"),
                                "financas_subjects": fin_subjects,
                            }
                        )

        records = load_batch_files(batch_files)
        now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

        to_insert, dedup_report = deduplicate_and_prepare(
            records=records,
            catalog=active_catalog,
            existing_docs=raw_existing,
            actor_user_id=actor_user_id,
            recorded_at=now_iso,
            file_classification_map=file_classification_map,
        )

        result = {
            "dry_run": dry_run,
            "target_db": target_db_name,
            "active_catalog_version": active_catalog.get("catalog_version"),
            "head_activation_id": head_act_id,
            "actor_user_id": actor_user_id,
            "existing_summary": {
                "total": len(raw_existing),
                "by_disciplina": dict(disc_counter.most_common(30)),
                "by_canonical_subject_id": dict(subj_counter.most_common(30)),
                "by_canonical_topic_id": dict(topic_counter.most_common(30)),
                "by_source": dict(source_counter.most_common(20)),
                "by_classification_state": dict(cls_state_counter),
                "by_concursos": dict(concursos_counter.most_common(20)),
                "financas_samples": financas_samples,
            },
            "mfin_catalog_topics": mfin_catalog_topics,
            "bcb_templates_financas": bcb_templates,
            "deduplication": dedup_report,
        }

        if dry_run or not to_insert:
            result["inserted_count"] = 0
            return result

        # Perform idempotent, create-only insertion with audit events
        q_coll = db.get_collection("todasQuestoes")
        cmd_coll = db.get_collection("question_commands")
        rev_coll = db.get_collection("question_revision_events")
        now_dt = datetime.now(timezone.utc)

        inserted_count = 0
        already_present_on_write = 0

        for fname, idx, v2_doc in to_insert:
            oid_str = v2_doc["_id"]["$oid"]
            oid = ObjectId(oid_str)
            op_uuid = deterministic_uuid(f"cogniva-v2-batch-op:{oid_str}")
            event_id = f"{actor_user_id}:{op_uuid}"
            payload_hash = rfc8785_sha256(
                {"user": actor_user_id, "expected": 0, "document": v2_doc}
            )

            mongo_doc = copy.deepcopy(v2_doc)
            mongo_doc["_id"] = oid
            mongo_doc["schema_version"] = Int64(2)
            mongo_doc["revision"] = Int64(1)
            mongo_doc["classification"]["revision"] = Int64(int(mongo_doc["classification"]["revision"]))
            mongo_doc["classification"]["catalog_version"] = Int64(int(mongo_doc["classification"]["catalog_version"]))
            mongo_doc["validity"]["revision"] = Int64(int(mongo_doc["validity"]["revision"]))
            mongo_doc["grading"]["max_score"] = Int64(int(mongo_doc["grading"]["max_score"]))
            mongo_doc["grading"]["pass_score"] = Int64(int(mongo_doc["grading"]["pass_score"]))
            mongo_doc["correct_answer_indices"] = [Int64(int(i)) for i in mongo_doc["correct_answer_indices"]]

            up_res = q_coll.update_one(
                {"_id": oid},
                {"$setOnInsert": mongo_doc},
                upsert=True,
            )
            if up_res.upserted_id is not None:
                inserted_count += 1
                cmd_coll.update_one(
                    {"_id": event_id},
                    {
                        "$setOnInsert": {
                            "_id": event_id,
                            "payload_sha256": payload_hash,
                            "question_id": oid_str,
                            "actor_user_id": Int64(actor_user_id),
                        }
                    },
                    upsert=True,
                )
                rev_coll.update_one(
                    {"_id": event_id},
                    {
                        "$setOnInsert": {
                            "_id": event_id,
                            "question_id": oid_str,
                            "revision": Int64(1),
                            "payload_sha256": payload_hash,
                            "actor_user_id": Int64(actor_user_id),
                            "recorded_at": now_dt,
                            "previous": {},
                        }
                    },
                    upsert=True,
                )
            else:
                already_present_on_write += 1

        result["inserted_count"] = inserted_count
        result["already_present_on_write"] = already_present_on_write
        post_docs = list(q_coll.find({}, {"content_revision": 1, "question": 1}))
        post_rev_counts = Counter(str(d.get("content_revision") or "") for d in post_docs)
        post_dup_revs = {k: v for k, v in post_rev_counts.items() if k and v > 1}
        result["post_insert_total_in_db"] = len(post_docs)
        result["post_insert_unique_content_revisions"] = len(post_rev_counts)
        result["post_insert_duplicate_content_revisions_count"] = len(post_dup_revs)
        return result
    finally:
        client.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mongo-uri", default=os.environ.get("MONGODB_URI", ""))
    parser.add_argument("--target-db", default=os.environ.get("MONGODB_DB_NAME") or "cogniva")
    parser.add_argument("--batch-dir", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)

    if not args.mongo_uri:
        # Offline validation + deduplication mode
        catalog = kc.read(ROOT / "shared/knowledge-catalog.json")
        batch_files = resolve_batch_files(args.batch_dir)
        records = load_batch_files(batch_files)
        _, report = deduplicate_and_prepare(records, catalog, existing_docs=[])
        report["dry_run"] = True
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    report = inspect_and_import_mongo(
        mongo_uri=args.mongo_uri,
        target_db_name=args.target_db,
        batch_dir=args.batch_dir,
        dry_run=not args.apply,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
