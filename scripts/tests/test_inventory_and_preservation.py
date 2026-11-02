import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import classification_preservation as preservation
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
        opened=read(ROOT / "shared/examples/question-contracts/question-open-manual.json")
        self.assertEqual(inventory.classify_document(opened,self.catalog),"v2_confirmed")
        broken=read(ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        broken["answers"].pop()
        self.assertEqual(inventory.classify_document(broken,self.catalog),"invalid_ambiguous")

    def test_each_state_is_distinct_and_a_label_is_not_a_canonical_reference(self):
        confirmed = read(ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        partial = json.loads(json.dumps(confirmed))
        partial["classification"]["state"] = "PARTIAL"
        samples = {
            "legacy_unclassified": document(disciplina="Matemática"),
            "existing_singular_canonical": document(canonical_subject_id="MAT", canonical_topic_id="MAT_01_001"),
            "v2_confirmed": confirmed,
            "v2_partial": partial,
            "invalid_ambiguous": document(canonical_subject_id="MAT", canonical_topic_id="PORT_01_001"),
            "obsolete_under_review": document(validity={"status": "OBSOLETE"}),
            "insufficient_metadata": {"answers": []},
        }
        counts = {state: 0 for state in inventory.STATES}
        for state, sample in samples.items():
            self.assertEqual(inventory.classify_document(sample, self.catalog), state)
            counts[state] += 1
        self.assertEqual(set(counts), set(inventory.STATES))
        textual = document(canonical_subject_id="Matemática", canonical_topic_id="MAT_01_001")
        self.assertEqual(inventory.classify_document(textual, self.catalog), "legacy_unclassified")
        singular = samples["existing_singular_canonical"]
        self.assertNotIn("classification", singular)

    def test_dry_run_keeps_fixture_bytes_and_omits_private_content(self):
        confirmed = read(ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        documents = [
            document(disciplina="Matemática", note="SECRET_KEY"),
            document(canonical_subject_id="MAT", canonical_topic_id="MAT_01_001"),
            confirmed,
            document(canonical_subject_id="NOPE", canonical_topic_id="NOPE"),
            document(validity={"status": "UNDER_REVIEW"}),
            {"question": ""},
        ]
        partial = json.loads(json.dumps(confirmed))
        partial["classification"]["state"] = "PARTIAL"
        documents.append(partial)
        learning = [{"attempt_id": "private-user-event", "text": "SECRET_KEY"}]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "questions.json"
            events = root / "learning.json"
            report_path = root / "report.json"
            source.write_text(json.dumps(documents), encoding="utf-8")
            events.write_text(json.dumps(learning), encoding="utf-8")
            before = (source.read_bytes(), events.read_bytes())
            for _ in range(2):
                report = inventory.inventory(json.loads(source.read_text(encoding="utf-8")), self.catalog)
                report["learning_event_count"] = len(json.loads(events.read_text(encoding="utf-8")))
                report_path.write_text(json.dumps(report), encoding="utf-8")
            self.assertEqual(before, (source.read_bytes(), events.read_bytes()))
            written = report_path.read_text(encoding="utf-8")
            self.assertNotIn("SECRET_KEY", written)
            self.assertNotIn("private-user-event", written)
            self.assertNotIn("bson", written.casefold())
            parsed = json.loads(written)
            self.assertEqual(set(parsed["counts"]), set(inventory.STATES))
            self.assertGreater(sum(parsed["counts"].values()), 0)
            self.assertFalse(parsed["legacy_singular_assumed_human_review"])
            self.assertEqual(parsed["documents_modified"], 0)


class PreservationTest(unittest.TestCase):
    def test_authorized_classification_fields_may_change_and_history_may_not(self):
        before = [document(_id="q1", mnemonico="cache", extra={"keep": True}, answers=["A", "B"])]
        after = [dict(before[0], classification={"state": "CONFIRMED"}, canonical_subject_id="MAT", canonical_topic_id="MAT_01_001", revision=1, content_revision="abc")]
        history = [{"attempt_id": "a"}]
        neighbor = [{"service": "fin"}]
        result = preservation.compare_snapshots(before, after, history, history, neighbor, neighbor)
        self.assertEqual(result["authorized_changes"][0]["fields"], ["canonical_subject_id", "canonical_topic_id", "classification", "content_revision", "revision"])
        with self.assertRaisesRegex(ValueError, "Content field"):
            preservation.compare_snapshots(before, [dict(after[0], question="alterado")], history, history, neighbor, neighbor)
        with self.assertRaisesRegex(ValueError, "Unauthorized"):
            preservation.compare_snapshots(before, [dict(after[0], mnemonico="novo")], history, history, neighbor, neighbor)
        with self.assertRaisesRegex(ValueError, "Unauthorized"):
            preservation.compare_snapshots(before, [dict(after[0], extra={"keep": False})], history, history, neighbor, neighbor)
        with self.assertRaisesRegex(ValueError, "Learning history"):
            preservation.compare_snapshots(before, after, history, [{"attempt_id": "b"}], neighbor, neighbor)
        with self.assertRaisesRegex(ValueError, "Neighboring"):
            preservation.compare_snapshots(before, after, history, history, neighbor, [{"service": "other"}])


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
