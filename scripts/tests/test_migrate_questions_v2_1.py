import copy
from pathlib import Path
import sys
import unittest

from bson import ObjectId

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from knowledge_catalog import ROOT, read
import migrate_questions_v2_1 as mig
import question_contracts as qc

BASE = ROOT / "shared/examples/question-contracts"


class MigrateQuestionsV21Test(unittest.TestCase):
    def setUp(self):
        self.catalog = read(ROOT / "shared/knowledge-catalog.json")

    def test_idempotent_non_destructive_migration_and_provenance_audit(self):
        # T22, T23: Legacy V2 without audit trail vs legacy V2 WITH server revision audit trail
        q_unverified = read(BASE / "question-multiple-choice.json")
        # Actor "synthetic-fixture-reviewer" has no server revision event -> flagged UNVERIFIED_LEGACY

        q_verified = copy.deepcopy(q_unverified)
        q_verified["_id"] = {"$oid": "000000000000000000000099"}
        for origin in q_verified["origins"]:
            origin["provenance"]["actor"]["id"] = "42"
        for link in q_verified["classification"]["links"]:
            link["provenance"]["actor"]["id"] = "42"
        q_verified["validity"]["decision"]["actor"]["id"] = "42"

        q_unclassified = read(BASE / "question-unclassified.json")

        revision_events = [
            {
                "_id": "42:11111111-1111-4111-8111-111111111111",
                "question_id": "000000000000000000000099",
                "revision": 1,
                "actor_user_id": 42,
            }
        ]

        bson_inputs = [
            qc.contract_to_bson_question(q_unverified),
            qc.contract_to_bson_question(q_verified),
            qc.contract_to_bson_question(q_unclassified),
        ]

        first = mig.migrate_documents(
            questions=bson_inputs,
            catalog=self.catalog,
            revision_events=revision_events,
            recorded_at="2026-11-15T15:00:00Z",
        )
        report = first["report"]
        self.assertEqual(report["processed"], 3)
        self.assertEqual(report["migrated"], 3)
        self.assertEqual(report["skipped"], 0)
        self.assertEqual(report["inconsistent"], 0)
        self.assertEqual(report["unverified_legacy_flagged"], 2)  # q_unverified and q_unclassified origin

        migrated_unverified = qc.bson_to_contract_question(first["questions"][0])
        migrated_verified = qc.bson_to_contract_question(first["questions"][1])
        self.assertIsInstance(first["questions"][0]["_id"], ObjectId)

        # Original content_revision and actor identities preserved untouched
        self.assertEqual(migrated_unverified["content_revision"], q_unverified["content_revision"])
        self.assertEqual(
            migrated_unverified["validity"]["decision"]["actor"],
            {"type": "HUMAN", "id": "synthetic-fixture-reviewer"},
        )
        self.assertEqual(
            migrated_unverified["validity"]["decision"]["trust_level"],
            "UNVERIFIED_LEGACY",
        )
        self.assertIsNone(migrated_unverified["validity"]["decision"]["review_event_id"])
        # Unverified legacy provenance is blocked from operational practice until reviewed
        elig_unverified = qc.evaluate_question_eligibility(
            migrated_unverified,
            self.catalog,
            evaluation_date="2026-11-15",
            trusted_review_context={"require_server_verified": True},
        )
        self.assertFalse(elig_unverified["eligible"])
        self.assertIn("HUMAN_REVIEW_REQUIRED", elig_unverified["reason_codes"])

        # Verified provenance linked to audit event is eligible even under strict require_server_verified
        self.assertEqual(migrated_verified["validity"]["decision"]["trust_level"], "SERVER_VERIFIED")
        self.assertIsNotNone(migrated_verified["validity"]["decision"]["review_event_id"])
        self.assertEqual(migrated_verified["validity"]["evaluation_mode"], "SEMANTIC")
        self.assertEqual(
            migrated_verified["validity"]["evaluated_semantic_revision"],
            migrated_verified["semantic_revision"],
        )
        verified_event_ids = {ev["review_event_id"] for ev in first["review_events"]}
        elig_verified = qc.evaluate_question_eligibility(
            migrated_verified,
            self.catalog,
            evaluation_date="2026-11-15",
            trusted_review_context={
                "require_server_verified": True,
                "verified_review_event_ids": verified_event_ids,
            },
        )
        self.assertTrue(elig_verified["eligible"])

        # Migration audit events use SYSTEM actor, never faking HUMAN decisions
        self.assertEqual(len(first["migration_events"]), 3)
        for mev in first["migration_events"]:
            self.assertEqual(mev["actor"]["type"], "SYSTEM")

        # T22: Second run is 100% idempotent (migrated = 0, skipped = 3)
        second = mig.migrate_documents(
            questions=first["questions"],
            catalog=self.catalog,
            revision_events=revision_events,
            existing_review_events=first["review_events"],
            recorded_at="2026-11-15T16:00:00Z",
        )
        self.assertEqual(second["report"]["processed"], 3)
        self.assertEqual(second["report"]["migrated"], 0)
        self.assertEqual(second["report"]["skipped"], 3)
        self.assertEqual(second["report"]["inconsistent"], 0)
        self.assertEqual(second["migration_events"], [])
        self.assertEqual(second["review_events"], [])
        self.assertEqual(second["questions"], first["questions"])

    def test_corrupted_or_stale_records_are_flagged_inconsistent_and_never_silently_repaired(self):
        corrupted = read(BASE / "question-multiple-choice.json")
        corrupted["content_revision"] = "f" * 64

        stale_val = read(BASE / "question-true-false.json")
        stale_val["validity"]["evaluated_content_revision"] = "a" * 64

        bad_oid = read(BASE / "question-unclassified.json")
        bad_oid["_id"] = "not-an-extjson-oid"

        result = mig.migrate_documents(
            questions=[corrupted, stale_val, bad_oid],
            catalog=self.catalog,
            recorded_at="2026-11-15T15:00:00Z",
        )
        self.assertEqual(result["report"]["processed"], 3)
        self.assertEqual(result["report"]["migrated"], 0)
        self.assertEqual(result["report"]["inconsistent"], 3)
        codes = [item["code"] for item in result["report"]["inconsistencies"]]
        self.assertEqual(
            codes,
            ["CONTENT_REVISION_MISMATCH", "CONTENT_REVISION_MISMATCH", "INVALID_OBJECT_ID"],
        )
        # Documents remain completely untouched
        self.assertEqual(result["questions"][0], corrupted)
        self.assertEqual(result["questions"][1], stale_val)
        self.assertEqual(result["questions"][2], bad_oid)


if __name__ == "__main__":
    unittest.main()
