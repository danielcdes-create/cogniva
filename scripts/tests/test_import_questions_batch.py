import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import import_questions_batch as iqb
import knowledge_catalog as kc
import question_contracts as qc


class ImportQuestionsBatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = kc.read(kc.ROOT / "shared/knowledge-catalog.json")

    @staticmethod
    def _sample_source_reference(slug: str) -> dict:
        return {
            "uri": f"urn:cogniva:author-batch:{slug}",
            "title": f"Caderno autoral revisado ({slug})",
            "locator": "item 1",
            "retrieved_at": "2026-11-17T10:30:00Z",
            "sha256": None,
        }

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
            "explanation": "Explicação técnica completa com mais de vinte caracteres.",
            "level": "Superior",
            "origin_kind": "AUTHOR_CREATED",
            "confirm_origin": True,
            "source_exam_code": None,
            "institution": None,
            "organizer": None,
            "exam_year": None,
            "question_number": None,
            "source_reference": self._sample_source_reference(f"mc-{suffix}"),
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
            "source_reference": self._sample_source_reference(f"tf-{suffix}"),
            "subject_id": None,
            "topic_id": None,
            "confirm_classification": False,
            "grading": None,
        }

    def test_build_v2_question_from_simplified_validates_and_populates_v2_1_hashes(self):
        mc_doc = iqb.build_v2_question_from_simplified(self._sample_mc_item(), self.catalog)
        qc.validate_question(mc_doc, self.catalog)
        self.assertEqual(mc_doc["schema_version"], 2)
        self.assertEqual(mc_doc["classification"]["state"], "UNCLASSIFIED")
        self.assertEqual(mc_doc["source"], "Autoral")
        self.assertEqual(mc_doc["_id"]["$oid"], mc_doc["content_revision"][:24])
        self.assertEqual(mc_doc["semantic_revision"], qc.semantic_revision(mc_doc))
        self.assertEqual(mc_doc["semantic_hash_version"], qc.SEMANTIC_HASH_VERSION)
        self.assertEqual(mc_doc["grading_revision"], qc.grading_revision(mc_doc))
        self.assertEqual(mc_doc["grading_hash_version"], qc.GRADING_HASH_VERSION)

        tf_doc = iqb.build_v2_question_from_simplified(self._sample_tf_item(), self.catalog)
        qc.validate_question(tf_doc, self.catalog)
        self.assertEqual(tf_doc["question_type"], "TRUE_FALSE")
        self.assertEqual(tf_doc["correct_answers"], ["Certo"])
        self.assertEqual(tf_doc["semantic_revision"], qc.semantic_revision(tf_doc))

        # Already-V2 input without V2.1 hashes also receives canonical V2.1 hashes
        raw_v2 = kc.read(kc.ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        raw_v2.pop("semantic_revision", None)
        raw_v2.pop("grading_revision", None)
        v2_upgraded = iqb.build_v2_question_from_simplified(raw_v2, self.catalog)
        self.assertEqual(v2_upgraded["semantic_revision"], qc.semantic_revision(v2_upgraded))
        self.assertEqual(v2_upgraded["grading_revision"], qc.grading_revision(v2_upgraded))

    def test_confirm_origin_requires_non_null_source_reference(self):
        missing_ref = self._sample_mc_item("missing-ref")
        missing_ref["source_reference"] = None
        with self.assertRaisesRegex(ValueError, "source_reference"):
            iqb.build_v2_question_from_simplified(missing_ref, self.catalog)

        unconfirmed_author = self._sample_mc_item("author-no-confirm")
        unconfirmed_author["confirm_origin"] = False
        unconfirmed_author["source_reference"] = None
        doc = iqb.build_v2_question_from_simplified(unconfirmed_author, self.catalog)
        self.assertEqual(doc["origins"][0]["verification"], "CONFIRMED")

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

    def test_missing_or_empty_batch_dir_fails_explicitly_in_cli_and_resolver(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            missing_dir = Path(tmpdir) / "does-not-exist"
            with self.assertRaises(FileNotFoundError):
                iqb.resolve_batch_files(missing_dir)
            with mock.patch.dict("os.environ", {"MONGODB_URI": ""}, clear=False):
                with self.assertRaises(FileNotFoundError):
                    iqb.main(["--batch-dir", str(missing_dir)])

            empty_dir = Path(tmpdir) / "empty"
            empty_dir.mkdir()
            (empty_dir / "notes.txt").write_text("not json", encoding="utf-8")
            with self.assertRaises(ValueError):
                iqb.resolve_batch_files(empty_dir)
            with mock.patch.dict("os.environ", {"MONGODB_URI": ""}, clear=False):
                with self.assertRaises(ValueError):
                    iqb.main(["--batch-dir", str(empty_dir)])

            valid_dir = Path(tmpdir) / "valid"
            valid_dir.mkdir()
            (valid_dir / "batch1.json").write_text(
                json.dumps([self._sample_mc_item("cli-ok")]), encoding="utf-8"
            )
            buf = io.StringIO()
            with mock.patch.dict("os.environ", {"MONGODB_URI": ""}, clear=False), mock.patch(
                "sys.stdout", buf
            ):
                rc = iqb.main(["--batch-dir", str(valid_dir)])
            self.assertEqual(rc, 0)
            out = json.loads(buf.getvalue())
            self.assertTrue(out["dry_run"])
            self.assertEqual(out["new_questions_to_insert"], 1)

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
