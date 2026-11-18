"""Idempotent, non-destructive Cogniva V2.0 -> V2.1 question contract migration (NT-COGNIVA-QUESTIONS-002 Sec. 10)."""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import uuid

from bson import ObjectId

from knowledge_catalog import ROOT, read
import question_contracts as qc

MIGRATION_ID = "cogniva-questions-v2.1.0"
SYSTEM_ACTOR = {"type": "SYSTEM", "id": "migrate-questions-v2.1.0"}


def _deterministic_uuid(seed: str) -> str:
    digest = hashlib.sha256(seed.encode("utf-8")).digest()[:16]
    return str(uuid.UUID(bytes=digest))


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _extract_oid_hex(doc: dict) -> str | None:
    raw_id = doc.get("_id")
    if isinstance(raw_id, ObjectId):
        return str(raw_id)
    if isinstance(raw_id, dict) and set(raw_id.keys()) == {"$oid"} and qc.is_valid_oid_hex(raw_id.get("$oid")):
        return raw_id["$oid"]
    return None


def _find_verifiable_revision_event(question_oid: str, provenance: dict, revision_events_by_qid: dict) -> dict | None:
    actor = provenance.get("actor") if isinstance(provenance, dict) else None
    if not isinstance(actor, dict) or actor.get("type") != "HUMAN":
        return None
    actor_id_str = str(actor.get("id") or "").strip()
    if not actor_id_str.lstrip("-").isdigit():
        return None
    actor_uid = int(actor_id_str)
    if actor_uid == 0:
        return None
    for ev in revision_events_by_qid.get(question_oid, []):
        if ev.get("actor_user_id") == actor_uid and qc.is_strict_int(ev.get("actor_user_id")):
            return ev
    return None


def _audit_provenance_block(
    provenance: dict,
    question_oid: str,
    block_key: str,
    action: str,
    resource_revision: int,
    content_rev: str,
    semantic_rev: str,
    revision_events_by_qid: dict,
    existing_review_events_by_id: dict,
    emitted_review_events: list,
) -> tuple[bool, bool]:
    """Audit a single human provenance block without inventing identities.

    Returns `(mutated, flagged_unverified)`.
    """
    if not qc.human_review(provenance):
        return False, False

    existing_trust = provenance.get("trust_level")
    existing_rev_id = provenance.get("review_event_id")
    if existing_trust == "SERVER_VERIFIED" and isinstance(existing_rev_id, str) and qc.UUID_RE.fullmatch(existing_rev_id):
        return False, False
    if existing_trust == "UNVERIFIED_LEGACY" and existing_rev_id is None:
        return False, True

    matched_ev = _find_verifiable_revision_event(question_oid, provenance, revision_events_by_qid)
    if matched_ev is not None:
        rev_event_id = _deterministic_uuid(f"{MIGRATION_ID}:{question_oid}:{block_key}:{matched_ev.get('_id')}")
        provenance["review_event_id"] = rev_event_id
        provenance["trust_level"] = "SERVER_VERIFIED"
        if rev_event_id not in existing_review_events_by_id:
            review_event = {
                "_id": rev_event_id,
                "schema_version": 1,
                "review_event_id": rev_event_id,
                "principal_id": str(provenance["actor"]["id"]),
                "actor_user_id": int(provenance["actor"]["id"]),
                "action": action,
                "resource_id": question_oid,
                "resource_revision": max(1, int(resource_revision)),
                "content_revision": content_rev,
                "semantic_revision": semantic_rev,
                "reviewed_at": provenance.get("recorded_at") or _now_iso(),
                "decision": "APPROVED",
            }
            qc.shape("review_event", review_event)
            emitted_review_events.append(review_event)
            existing_review_events_by_id[rev_event_id] = review_event
        return True, False

    # No verifiable server session/revision trail: preserve original actor & timestamp, classify as UNVERIFIED_LEGACY.
    provenance["review_event_id"] = None
    provenance["trust_level"] = "UNVERIFIED_LEGACY"
    return True, True


def migrate_documents(
    questions: list[dict],
    catalog: dict,
    revision_events: list[dict] | None = None,
    existing_review_events: list[dict] | None = None,
    recorded_at: str | None = None,
) -> dict:
    """Run non-destructive, idempotent V2.0 -> V2.1 migration over ExtJSON or BSON question dicts."""
    ts = recorded_at or _now_iso()
    revision_events_by_qid: dict[str, list[dict]] = {}
    for ev in revision_events or []:
        qid = ev.get("question_id")
        if isinstance(qid, str):
            revision_events_by_qid.setdefault(qid, []).append(ev)

    review_events_by_id: dict[str, dict] = {}
    for rev in existing_review_events or []:
        rid = rev.get("review_event_id") or rev.get("_id")
        if isinstance(rid, str):
            review_events_by_id[rid] = copy.deepcopy(rev)

    migrated_questions: list[dict] = []
    migration_events: list[dict] = []
    emitted_review_events: list[dict] = []
    inconsistencies: list[dict] = []

    processed = 0
    migrated_count = 0
    skipped_count = 0
    inconsistent_count = 0
    unverified_flagged_count = 0

    for raw in questions:
        processed += 1
        was_bson_oid = isinstance(raw.get("_id"), ObjectId)
        oid_hex = _extract_oid_hex(raw)
        if oid_hex is None:
            inconsistent_count += 1
            inconsistencies.append(
                {
                    "question_id": str(raw.get("_id")),
                    "code": "INVALID_OBJECT_ID",
                    "reason": "Question _id is neither canonical Extended JSON nor BSON ObjectId",
                }
            )
            migrated_questions.append(copy.deepcopy(raw))
            continue

        try:
            working = qc.bson_to_contract_question(raw) if was_bson_oid else copy.deepcopy(raw)
        except qc.ContractValidationError as exc:
            inconsistent_count += 1
            inconsistencies.append(
                {
                    "question_id": oid_hex,
                    "code": exc.code,
                    "field": exc.field,
                    "reason": str(exc),
                }
            )
            migrated_questions.append(copy.deepcopy(raw))
            continue

        # 1. Verify original V2 content_revision integrity before touching anything
        try:
            computed_content = qc.content_revision(working)
        except Exception as exc:  # noqa: BLE001
            inconsistent_count += 1
            inconsistencies.append(
                {
                    "question_id": oid_hex,
                    "code": "SCHEMA_VALIDATION_FAILED",
                    "reason": f"Cannot compute content_revision: {exc}",
                }
            )
            migrated_questions.append(copy.deepcopy(raw))
            continue

        if working.get("content_revision") != computed_content or working.get("content_hash_version") != qc.CONTENT_HASH_VERSION:
            inconsistent_count += 1
            inconsistencies.append(
                {
                    "question_id": oid_hex,
                    "code": "CONTENT_REVISION_MISMATCH",
                    "field": "content_revision",
                    "reason": "Stored content_revision does not match canonical JCS SHA-256",
                }
            )
            migrated_questions.append(copy.deepcopy(raw))
            continue

        computed_semantic = qc.semantic_revision(working)
        computed_grading = qc.grading_revision(working)

        if working.get("semantic_revision") not in (None, computed_semantic):
            inconsistent_count += 1
            inconsistencies.append(
                {
                    "question_id": oid_hex,
                    "code": "SEMANTIC_REVISION_MISMATCH",
                    "field": "semantic_revision",
                    "reason": "Existing semantic_revision diverges from canonical semantic payload",
                }
            )
            migrated_questions.append(copy.deepcopy(raw))
            continue

        if working.get("grading_revision") not in (None, computed_grading):
            inconsistent_count += 1
            inconsistencies.append(
                {
                    "question_id": oid_hex,
                    "code": "GRADING_REVISION_MISMATCH",
                    "field": "grading_revision",
                    "reason": "Existing grading_revision diverges from canonical grading payload",
                }
            )
            migrated_questions.append(copy.deepcopy(raw))
            continue

        before_snapshot = copy.deepcopy(working)
        doc_flagged_unverified = False

        # 2. Populate V2.1 hashes without altering content_revision
        working["semantic_revision"] = computed_semantic
        working["semantic_hash_version"] = qc.SEMANTIC_HASH_VERSION
        working["grading_revision"] = computed_grading
        working["grading_hash_version"] = qc.GRADING_HASH_VERSION

        # 3. Deterministically upgrade validity binding when non-ambiguous
        validity = working.get("validity")
        if not isinstance(validity, dict):
            inconsistent_count += 1
            inconsistencies.append(
                {
                    "question_id": oid_hex,
                    "code": "SCHEMA_VALIDATION_FAILED",
                    "field": "validity",
                    "reason": "Missing validity object",
                }
            )
            migrated_questions.append(copy.deepcopy(raw))
            continue

        vstatus = validity.get("status")
        if vstatus in ("CURRENT", "OBSOLETE"):
            existing_eval_sem = validity.get("evaluated_semantic_revision")
            if existing_eval_sem is not None and existing_eval_sem != computed_semantic:
                inconsistent_count += 1
                inconsistencies.append(
                    {
                        "question_id": oid_hex,
                        "code": "SEMANTIC_REVISION_MISMATCH",
                        "field": "validity.evaluated_semantic_revision",
                        "reason": "Existing evaluated_semantic_revision does not match semantic_revision",
                    }
                )
                migrated_questions.append(copy.deepcopy(raw))
                continue
            if existing_eval_sem is None:
                if validity.get("evaluated_content_revision") != computed_content:
                    inconsistent_count += 1
                    inconsistencies.append(
                        {
                            "question_id": oid_hex,
                            "code": "CONTENT_REVISION_MISMATCH",
                            "field": "validity.evaluated_content_revision",
                            "reason": "Legacy validity evaluated_content_revision is stale; manual review required",
                        }
                    )
                    migrated_questions.append(copy.deepcopy(raw))
                    continue
                validity["evaluated_semantic_revision"] = computed_semantic
                validity["evaluation_mode"] = "SEMANTIC"
            elif validity.get("evaluation_mode") is None:
                validity["evaluation_mode"] = "SEMANTIC"
        else:
            if "evaluated_semantic_revision" not in validity or validity.get("evaluated_semantic_revision") is not None:
                validity["evaluated_semantic_revision"] = None
            if "evaluation_mode" not in validity or validity.get("evaluation_mode") is not None:
                validity["evaluation_mode"] = None

        # 4. Audit human provenance blocks (never invent human identities)
        q_rev = int(working.get("revision") or 1)
        for idx, origin in enumerate(working.get("origins") or []):
            if origin.get("kind") == "OFFICIAL_EXAM" and origin.get("verification") == "CONFIRMED":
                _, flagged = _audit_provenance_block(
                    origin["provenance"],
                    oid_hex,
                    f"origin:{origin.get('origin_id', idx)}",
                    "ORIGIN_CONFIRM",
                    q_rev,
                    computed_content,
                    computed_semantic,
                    revision_events_by_qid,
                    review_events_by_id,
                    emitted_review_events,
                )
                doc_flagged_unverified = doc_flagged_unverified or flagged
            elif qc.human_review(origin.get("provenance")) and "trust_level" not in origin["provenance"]:
                _, flagged = _audit_provenance_block(
                    origin["provenance"],
                    oid_hex,
                    f"origin:{origin.get('origin_id', idx)}",
                    "ORIGIN_CONFIRM",
                    q_rev,
                    computed_content,
                    computed_semantic,
                    revision_events_by_qid,
                    review_events_by_id,
                    emitted_review_events,
                )
                doc_flagged_unverified = doc_flagged_unverified or flagged

        for idx, link in enumerate(working.get("classification", {}).get("links") or []):
            _, flagged = _audit_provenance_block(
                link["provenance"],
                oid_hex,
                f"classification_link:{link.get('link_id', idx)}",
                "CLASSIFICATION_CONFIRM",
                q_rev,
                computed_content,
                computed_semantic,
                revision_events_by_qid,
                review_events_by_id,
                emitted_review_events,
            )
            doc_flagged_unverified = doc_flagged_unverified or flagged

        if vstatus in ("CURRENT", "OBSOLETE") and isinstance(validity.get("decision"), dict):
            val_action = "VALIDITY_CONFIRM_CURRENT" if vstatus == "CURRENT" else "VALIDITY_CONFIRM_OBSOLETE"
            _, flagged = _audit_provenance_block(
                validity["decision"],
                oid_hex,
                f"validity:{validity.get('revision', 1)}",
                val_action,
                q_rev,
                computed_content,
                computed_semantic,
                revision_events_by_qid,
                review_events_by_id,
                emitted_review_events,
            )
            doc_flagged_unverified = doc_flagged_unverified or flagged

        # 5. Validate resulting V2.1 document against full schema & domain invariants
        try:
            qc.validate_question(working, catalog)
        except qc.ContractValidationError as exc:
            inconsistent_count += 1
            inconsistencies.append(
                {
                    "question_id": oid_hex,
                    "code": exc.code,
                    "field": exc.field,
                    "reason": str(exc),
                }
            )
            migrated_questions.append(copy.deepcopy(raw))
            continue

        if doc_flagged_unverified:
            unverified_flagged_count += 1

        if working == before_snapshot:
            skipped_count += 1
            migrated_questions.append(qc.contract_to_bson_question(working) if was_bson_oid else working)
            continue

        migrated_count += 1
        migration_events.append(
            {
                "_id": f"{MIGRATION_ID}:{oid_hex}",
                "migration_id": MIGRATION_ID,
                "question_id": oid_hex,
                "actor": SYSTEM_ACTOR,
                "recorded_at": ts,
                "content_revision": computed_content,
                "semantic_revision": computed_semantic,
                "grading_revision": computed_grading,
                "unverified_legacy_provenance": doc_flagged_unverified,
            }
        )
        migrated_questions.append(qc.contract_to_bson_question(working) if was_bson_oid else working)

    return {
        "report": {
            "migration_id": MIGRATION_ID,
            "processed": processed,
            "migrated": migrated_count,
            "skipped": skipped_count,
            "inconsistent": inconsistent_count,
            "unverified_legacy_flagged": unverified_flagged_count,
            "inconsistencies": inconsistencies,
        },
        "questions": migrated_questions,
        "migration_events": migration_events,
        "review_events": emitted_review_events,
    }


def run_mongo_migration(mongo_uri: str, db_name: str, catalog: dict, apply: bool = False) -> dict:
    from pymongo import MongoClient

    client = MongoClient(mongo_uri)
    db = client[db_name]
    raw_questions = list(db["todasQuestoes"].find({}))
    rev_events = list(db["question_revision_events"].find({}))
    existing_reviews = list(db["question_review_events"].find({}))

    result = migrate_documents(
        questions=raw_questions,
        catalog=catalog,
        revision_events=rev_events,
        existing_review_events=existing_reviews,
    )
    report = dict(result["report"])
    report["dry_run"] = not apply

    if apply and result["migration_events"]:
        by_oid = {q["_id"]: q for q in result["questions"] if isinstance(q.get("_id"), ObjectId)}
        for ev in result["review_events"]:
            rid = ev["_id"]
            payload = {k: v for k, v in ev.items() if k != "_id"}
            db["question_review_events"].update_one({"_id": rid}, {"$setOnInsert": payload}, upsert=True)
        for mev in result["migration_events"]:
            oid = ObjectId(mev["question_id"])
            updated_doc = by_oid[oid]
            set_fields = {
                "semantic_revision": updated_doc["semantic_revision"],
                "semantic_hash_version": updated_doc["semantic_hash_version"],
                "grading_revision": updated_doc["grading_revision"],
                "grading_hash_version": updated_doc["grading_hash_version"],
                "origins": updated_doc["origins"],
                "classification": updated_doc["classification"],
                "validity": updated_doc["validity"],
            }
            db["todasQuestoes"].update_one(
                {"_id": oid, "content_revision": mev["content_revision"]},
                {"$set": set_fields},
            )
            mev_id = mev["_id"]
            mev_payload = {k: v for k, v in mev.items() if k != "_id"}
            db["question_migration_events"].update_one({"_id": mev_id}, {"$setOnInsert": mev_payload}, upsert=True)

    client.close()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-json", type=Path, help="Offline JSON array of V2 questions to migrate")
    parser.add_argument("--output-json", type=Path, help="Optional output path for migrated JSON array")
    parser.add_argument("--mongo-uri", type=str, help="MongoDB URI for live migration")
    parser.add_argument("--db-name", type=str, default="cogniva", help="MongoDB database name")
    parser.add_argument("--apply", action="store_true", help="Persist changes (default is dry-run)")
    args = parser.parse_args()

    catalog = read(ROOT / "shared/knowledge-catalog.json")
    if args.mongo_uri:
        report = run_mongo_migration(args.mongo_uri, args.db_name, catalog, apply=args.apply)
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return

    if args.input_json:
        docs = json.loads(args.input_json.read_text(encoding="utf-8"))
        result = migrate_documents(docs, catalog)
        report = dict(result["report"])
        report["dry_run"] = not args.apply
        if args.apply and args.output_json:
            args.output_json.write_text(
                json.dumps(result["questions"], indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return

    parser.error("Provide either --input-json or --mongo-uri")


if __name__ == "__main__":
    main()
