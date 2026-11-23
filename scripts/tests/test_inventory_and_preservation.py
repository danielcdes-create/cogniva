import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import inventory_questions as inventory
import question_contracts as qc
from knowledge_catalog import ROOT, read


def document(**extra):
    base = {
        "question": "Enunciado sintético sem conteúdo privado de usuário.",
        "answers": ["A", "B", "C", "D", "E"],
        "correct_answers": ["B"],
    }
    base.update(extra)
    return base


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.catalog = inventory.load_catalog()

    def test_open_questions_are_inventoryable_and_invalid_v2_is_not_confirmed(self):
        opened = read(ROOT / "shared/examples/question-contracts/question-open-manual.json")
        self.assertEqual(inventory.classify_document(opened, self.catalog), "v2_confirmed")
        broken = read(ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        broken["answers"] = ["A", "B", "C", "D"]
        self.assertEqual(inventory.classify_document(broken, self.catalog), "invalid_ambiguous")
        self.assertEqual(
            inventory.classify_document(document(answers=["A", "B", "C", "D"]), self.catalog),
            "invalid_ambiguous",
        )
        self.assertEqual(
            inventory.classify_document(
                document(answers=["Certo", "Errado"], correct_answers=["Certo"]), self.catalog
            ),
            "legacy_needs_migration",
        )

    def test_each_state_is_distinct(self):
        confirmed = read(ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        unclassified = read(ROOT / "shared/examples/question-contracts/question-unclassified.json")
        partial = json.loads(json.dumps(confirmed))
        partial["classification"]["state"] = "PARTIAL"
        samples = {
            "legacy_needs_migration": document(disciplina="Matemática"),
            "v2_confirmed": confirmed,
            "v2_partial": partial,
            "v2_unclassified": unclassified,
            "invalid_ambiguous": document(canonical_subject_id="MAT", canonical_topic_id="PORT_01_001"),
            "obsolete_under_review": document(validity={"status": "OBSOLETE"}),
            "insufficient_metadata": {"answers": []},
        }
        for state, sample in samples.items():
            self.assertEqual(inventory.classify_document(sample, self.catalog), state)

    def test_same_topic_with_distinct_concepts_is_confirmed_and_duplicate_pair_fails(self):
        catalog_with_concepts = copy.deepcopy(self.catalog)
        confirmed = read(ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        primary_link = confirmed["classification"]["links"][0]
        sid = primary_link["subject_id"]
        tid = primary_link["topic_id"]
        catalog_with_concepts["concepts"] = [
            {
                "concept_id": "CONCEPT_TEST_A",
                "subject_id": sid,
                "topic_id": tid,
                "label": "Conceito A",
                "aliases": [],
                "definition": "Definição sintética A.",
                "prerequisite_concept_ids": [],
                "normative_source_ids": [],
                "status": "ACTIVE",
                "revision": 1,
                "source_ids": ["EDITORIAL_V1"],
            },
            {
                "concept_id": "CONCEPT_TEST_B",
                "subject_id": sid,
                "topic_id": tid,
                "label": "Conceito B",
                "aliases": [],
                "definition": "Definição sintética B.",
                "prerequisite_concept_ids": [],
                "normative_source_ids": [],
                "status": "ACTIVE",
                "revision": 1,
                "source_ids": ["EDITORIAL_V1"],
            },
        ]

        q_two_concepts = copy.deepcopy(confirmed)
        q_two_concepts["classification"]["links"][0]["concept_id"] = "CONCEPT_TEST_A"
        second_link = copy.deepcopy(q_two_concepts["classification"]["links"][0])
        second_link["link_id"] = "00000000-0000-4000-8000-000000000777"
        second_link["role"] = "SECONDARY"
        second_link["concept_id"] = "CONCEPT_TEST_B"
        q_two_concepts["classification"]["links"].append(second_link)

        self.assertEqual(inventory.classify_document(q_two_concepts, catalog_with_concepts), "v2_confirmed")

        q_dup_concept = copy.deepcopy(q_two_concepts)
        q_dup_concept["classification"]["links"][1]["concept_id"] = "CONCEPT_TEST_A"
        self.assertEqual(inventory.classify_document(q_dup_concept, catalog_with_concepts), "invalid_ambiguous")

    def test_dry_run_keeps_fixture_bytes_omits_private_content_and_enforces_cli_flag(self):
        confirmed = read(ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        documents = [
            document(disciplina="Matemática", note="SECRET_KEY"),
            confirmed,
            document(canonical_subject_id="NOPE", canonical_topic_id="NOPE"),
            document(validity={"status": "UNDER_REVIEW"}),
            {"question": ""},
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "questions.json"
            report_path = root / "report.json"
            source.write_text(json.dumps(documents), encoding="utf-8")
            before = source.read_bytes()

            with self.assertRaises(SystemExit):
                inventory.main(["--input", str(source), "--output", str(report_path)])

            buf = io.StringIO()
            with mock.patch("sys.stdout", buf):
                rc = inventory.main(["--input", str(source), "--output", str(report_path), "--dry-run"])
            self.assertEqual(rc, 0)
            self.assertEqual(before, source.read_bytes())
            written = report_path.read_text(encoding="utf-8")
            self.assertNotIn("SECRET_KEY", written)
            parsed = json.loads(written)
            self.assertEqual(set(parsed["counts"]), set(inventory.STATES))
            self.assertEqual(parsed["documents_modified"], 0)


class SuggestionBoundaryTest(unittest.TestCase):
    def test_invented_catalog_id_and_lost_provenance_are_rejected(self):
        catalog = read(ROOT / "shared/knowledge-catalog.json")
        question = read(ROOT / "shared/examples/question-contracts/question-unclassified.json")
        suggestion = read(ROOT / "shared/examples/question-contracts/suggestion-pending.json")
        suggestion["catalog_version"] = 99
        with self.assertRaisesRegex(ValueError, "catalog"):
            qc.validate_suggestion(suggestion, question, catalog)
        suggestion = read(ROOT / "shared/examples/question-contracts/suggestion-pending.json")
        suggestion["proposed_links"][0]["topic_id"] = "NOT_A_REAL_TOPIC"
        with self.assertRaisesRegex(ValueError, "taxonomy"):
            qc.validate_suggestion(suggestion, question, catalog)


if __name__ == "__main__":
    unittest.main()
