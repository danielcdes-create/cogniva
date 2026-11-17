import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import import_questions_batch as iqb
import knowledge_catalog as kc
import question_contracts as qc


class ImportQuestionsBatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = kc.read(kc.ROOT / "shared/knowledge-catalog.json")

    def _sample_mc_item(self, suffix: str = "1") -> dict:
        return {
            "question_type": "MULTIPLE_CHOICE",
            "question": f"Em uma operação compromissada de teste ({suffix}), qual é o tratamento do lastro?",
            "answers": [
                "O lastro permanece bloqueado na conta vinculada.",
                "O lastro é livremente negociado na conta de clientes.",
                "O lastro é cancelado no Selic.",
                "O lastro deve ser substituído por ouro físico.",
                "O lastro não pode ser título público federal.",
            ],
            "correct_answer_indices": [0],
            "explanation": "Explicação técnica completa com mais de dez caracteres.",
            "level": "Superior",
            "origin_kind": "AUTHOR_CREATED",
            "confirm_origin": True,
            "source_exam_code": None,
            "institution": None,
            "organizer": None,
            "exam_year": None,
            "question_number": None,
            "source_reference": None,
            "subject_id": None,
            "topic_id": None,
            "confirm_classification": False,
            "grading": None,
        }

    def _sample_tf_item(self, suffix: str = "1") -> dict:
        return {
            "question_type": "TRUE_FALSE",
            "question": f"Julgue o item sobre depósitos interfinanceiros ({suffix}): a liquidação ocorre na B3.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [0],
            "explanation": "Depósitos interfinanceiros são registrados e liquidados na B3.",
            "level": "Superior",
            "origin_kind": "AUTHOR_CREATED",
            "confirm_origin": True,
            "source_exam_code": None,
            "institution": None,
            "organizer": None,
            "exam_year": None,
            "question_number": None,
            "source_reference": None,
            "subject_id": None,
            "topic_id": None,
            "confirm_classification": False,
            "grading": None,
        }

    def test_build_v2_question_from_simplified_validates_against_schema_v2(self):
        mc_doc = iqb.build_v2_question_from_simplified(self._sample_mc_item(), self.catalog)
        qc.validate_question(mc_doc, self.catalog)
        self.assertEqual(mc_doc["schema_version"], 2)
        self.assertEqual(mc_doc["classification"]["state"], "UNCLASSIFIED")
        self.assertEqual(mc_doc["source"], "Autoral")
        self.assertEqual(mc_doc["_id"]["$oid"], mc_doc["content_revision"][:24])

        tf_doc = iqb.build_v2_question_from_simplified(self._sample_tf_item(), self.catalog)
        qc.validate_question(tf_doc, self.catalog)
        self.assertEqual(tf_doc["question_type"], "TRUE_FALSE")
        self.assertEqual(tf_doc["correct_answers"], ["Certo"])

    def test_load_batch_files_handles_utf8_bom_and_plain_utf8(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            p1 = Path(tmpdir) / "a_plain.json"
            p2 = Path(tmpdir) / "b_bom.json"
            p1.write_text(json.dumps([self._sample_mc_item("a")]), encoding="utf-8")
            p2.write_bytes(b"\xef\xbb\xbf" + json.dumps([self._sample_tf_item("b")]).encode("utf-8"))

            records = iqb.load_batch_files([p2, p1])
            self.assertEqual(len(records), 2)
            self.assertEqual(records[0][0], "a_plain.json")
            self.assertEqual(records[1][0], "b_bom.json")

    def test_deduplicate_catches_existing_db_with_different_oid_and_intra_batch_duplicates(self):
        item1 = self._sample_mc_item("dup-db")
        item2 = self._sample_tf_item("new-1")
        item2_whitespace_variant = copy.deepcopy(item2)
        item2_whitespace_variant["question"] = "  " + item2["question"].upper() + "  "

        existing_doc = iqb.build_v2_question_from_simplified(item1, self.catalog)
        # Simulate Web Admin import which assigns an operation_id-derived ObjectId
        existing_doc["_id"] = {"$oid": "aaaaaaaaaaaaaaaaaaaaaaaa"}

        records = [
            ("lote1.json", 0, item1),
            ("lote2.json", 0, item2),
            ("lote2.json", 1, copy.deepcopy(item2)),
            ("lote3.json", 0, item2_whitespace_variant),
        ]

        to_insert, report = iqb.deduplicate_and_prepare(
            records=records,
            catalog=self.catalog,
            existing_docs=[existing_doc],
        )

        self.assertEqual(len(to_insert), 1)
        self.assertEqual(report["input_questions_total"], 4)
        self.assertEqual(report["new_questions_to_insert"], 1)
        self.assertEqual(report["skipped_already_in_db_count"], 1)
        self.assertEqual(report["skipped_duplicate_in_batch_count"], 2)

        skipped_db = report["skipped_already_in_db_sample"][0]
        self.assertFalse(skipped_db["matched_by_oid"])
        self.assertTrue(skipped_db["matched_by_content_revision"])
        self.assertTrue(skipped_db["matched_by_normalized_question"])


if __name__ == "__main__":
    unittest.main()
