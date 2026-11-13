import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import knowledge_catalog as kc
import migrate_cnu_to_cogniva as migrator
import question_contracts as qc


class MigrateCnuToCognivaTest(unittest.TestCase):
    def setUp(self):
        self.catalog = kc.read(kc.ROOT / "shared/knowledge-catalog.json")

    def test_v1_multiple_choice_with_four_and_five_options_normalize_to_strict_v2(self):
        for options in (["A1", "B2", "C3", "D4"], ["A1", "B2", "C3", "D4", "E5"]):
            raw = {
                "_id": {"$oid": "66a1b2c3d4e5f60123456789"},
                "question": "De acordo com a CLT, assinale a alternativa correta.",
                "answers": options,
                "correct_answers": [options[2]],
                "disciplina": "direito do trabalho",
                "source": "CESGRANRIO",
                "exam_year": 2024,
                "level": "Superior",
                "explanation": "Explicação preservada integralmente.",
                "mnemonico": "Mnemônico preservado.",
                "contextualHint": ["Dica 1", "Dica 2"],
            }
            v2 = migrator.normalize_question_to_v2(raw, self.catalog)
            qc.validate_question(v2, self.catalog)
            self.assertEqual(v2["schema_version"], 2)
            self.assertEqual(v2["question_type"], "MULTIPLE_CHOICE")
            self.assertEqual(v2["correct_answer_indices"], [2])
            self.assertEqual(v2["disciplina"], "Direito do Trabalho")
            self.assertEqual(v2["origins"][0]["kind"], "LEGACY_IMPORT")
            self.assertEqual(v2["origins"][0]["verification"], "PENDING")
            self.assertEqual(v2["explanation"], "Explicação preservada integralmente.")
            self.assertEqual(v2["exam_year"], 2024)
            self.assertEqual(v2["level"], "Superior")
            # Idempotency: re-normalizing V2 produces identical document
            v2_again = migrator.normalize_question_to_v2(v2, self.catalog)
            self.assertEqual(v2, v2_again)

    def test_v1_true_false_inverted_order_remaps_correct_index_accurately(self):
        raw = {
            "_id": {"$oid": "66a1b2c3d4e5f6012345678a"},
            "question": "Julgue o item a seguir acerca da NR-01.",
            "answers": ["Errado", "Certo"],
            "correct_answers": ["Certo"],
            "disciplina": "Segurança e Saúde do Trabalhador",
        }
        v2 = migrator.normalize_question_to_v2(raw, self.catalog)
        qc.validate_question(v2, self.catalog)
        self.assertEqual(v2["question_type"], "TRUE_FALSE")
        self.assertEqual(v2["answers"], ["Certo", "Errado"])
        self.assertEqual(v2["correct_answer_indices"], [0])
        self.assertEqual(v2["correct_answers"], ["Certo"])

    def test_outdated_legacy_law_maps_to_under_review_and_excludes_practice(self):
        raw = {
            "_id": {"$oid": "66a1b2c3d4e5f6012345678b"},
            "question": "Questão com norma revogada.",
            "answers": ["Op 1", "Op 2", "Op 3", "Op 4", "Op 5"],
            "correct_answer_indices": [0],
            "legal_validity": {"status": "OUTDATED_LAW"},
        }
        v2 = migrator.normalize_question_to_v2(raw, self.catalog)
        self.assertEqual(v2["validity"]["status"], "UNDER_REVIEW")
        self.assertEqual(v2["validity"]["reason_codes"], ["REVIEW_REQUESTED"])

    def test_open_ended_v2_is_preserved_and_batch_skips_corrupt_records_safely(self):
        open_q = kc.read(kc.ROOT / "shared/examples/question-contracts/question-open-manual.json")
        corrupt = {"_id": {"$oid": "66a1b2c3d4e5f6012345678c"}, "question": "Sem gabarito", "answers": ["A", "B", "C", "D"]}
        migrated, stats = migrator.migrate_batch([copy.deepcopy(open_q), corrupt], self.catalog)
        self.assertEqual(len(migrated), 1)
        self.assertEqual(migrated[0]["question_type"], "OPEN_ENDED")
        self.assertEqual(stats["skipped_invalid_count"], 1)


if __name__ == "__main__":
    unittest.main()
