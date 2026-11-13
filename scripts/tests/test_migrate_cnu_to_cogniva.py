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

    def test_selective_banco_central_contest_and_questions_migration(self):
        bcb_exam = {
            "exam_id": "BCB_2027_EF",
            "owner_user_id": "12345",
            "revision": 3,
            "title": "Banco Central do Brasil — Analista (Economia e Finanças)",
            "short_name": "BCB 2027 - EF",
            "institution": "Banco Central do Brasil (BCB)",
            "role": "Analista",
            "trunk_slug": "bancaria",
            "status": "RUMOR_PREVISTO",
            "exam_boards": ["Cebraspe"],
            "target_date": {"type": "UNDEFINED"},
            "historical_cutoff_score": 72.5,
            "is_active_in_ladder": True,
            "ladder_priority": 1,
            "subjects": [
                {
                    "local_subject_id": "econ-0",
                    "canonical_subject_id": "ECON",
                    "display_name": "Economia",
                    "category": "CONHECIMENTOS_ESPECIFICOS",
                    "weight": 3.0,
                    "question_count": 20,
                    "relevance_tier": "CORE",
                    "topics": [
                        {
                            "local_topic_id": "econ-t1",
                            "canonical_topic_id": "ECON_01",
                            "canonical_subject_id": "ECON",
                            "display_title": "Política monetária e fiscal",
                            "diff_status": "PRE_EDITAL_BASE",
                        },
                        {
                            "local_topic_id": "econ-t2",
                            "canonical_topic_id": "TOPICO_LEGADO_SFN",
                            "canonical_subject_id": "ECON",
                            "display_title": "Regulação prudencial bancária",
                            "diff_status": "PRE_EDITAL_BASE",
                        },
                    ],
                },
                {
                    "local_subject_id": "sfn-custom",
                    "canonical_subject_id": "DISC_LEGADA_BCB",
                    "display_name": "Sistema Financeiro e Supervisão",
                    "category": "CONHECIMENTOS_ESPECIFICOS",
                    "weight": 2.0,
                    "question_count": 15,
                    "relevance_tier": "SPECIFIC",
                    "topics": [
                        {
                            "local_topic_id": "sfn-t1",
                            "canonical_topic_id": "TOP_BCB_PIX",
                            "canonical_subject_id": "DISC_LEGADA_BCB",
                            "display_title": "Arranjos de pagamento e Pix",
                            "diff_status": "PRE_EDITAL_BASE",
                        }
                    ],
                },
            ],
        }
        other_exam = {
            "exam_id": "AFT_2026",
            "title": "Auditor-Fiscal do Trabalho",
            "short_name": "AFT",
            "institution": "MTE",
            "subjects": [],
        }
        q_bcb_v1 = {
            "_id": {"$oid": "66a1b2c3d4e5f60123456790"},
            "question": "Julgue o item acerca do regime de metas para a inflação conduzido pelo Banco Central.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [0],
            "disciplina": "Economia",
            "concursos": "BACEN",
            "source": "Cebraspe",
            "exam_year": 2024,
        }
        q_bcb_exclusive_topic = {
            "_id": {"$oid": "66a1b2c3d4e5f60123456791"},
            "question": "Acerca do ecossistema do Pix regulado pelo BCB, assinale a opção correta.",
            "answers": ["A", "B", "C", "D", "E"],
            "correct_answer_indices": [1],
            "canonical_subject_id": "DISC_LEGADA_BCB",
            "canonical_topic_id": "TOP_BCB_PIX",
        }
        q_unrelated = {
            "_id": {"$oid": "66a1b2c3d4e5f60123456792"},
            "question": "Questão exclusiva de NR-15 para AFT.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [1],
            "disciplina": "Segurança e Saúde do Trabalhador",
            "concursos": "AFT",
        }

        templates, selected_qs, diag = migrator.filter_contests_and_questions(
            raw_contests_by_coll={"user_exams": [bcb_exam, other_exam]},
            raw_questions=[q_bcb_v1, q_bcb_exclusive_topic, q_unrelated],
            catalog=self.catalog,
            contest_filter=r"banco central|bacen|\bbcb\b",
            source_catalog_labels={"DISC_LEGADA_BCB": "Sistema Financeiro e Supervisão"},
        )
        self.assertEqual(len(templates), 1)
        tpl = templates[0]
        self.assertEqual(tpl["_id"], "BCB_2027_EF")
        self.assertIsNone(tpl["owner_user_id"])
        self.assertEqual(tpl["trunk_slug"], "bancaria")
        self.assertEqual(tpl["subjects"][0]["canonical_subject_id"], "ECON")
        self.assertEqual(tpl["subjects"][0]["topics"][0]["canonical_topic_id"], "ECON_01")
        self.assertTrue(tpl["subjects"][0]["topics"][1]["canonical_topic_id"].startswith("custom-"))
        self.assertTrue(tpl["subjects"][1]["canonical_subject_id"].startswith("custom-"))

        self.assertEqual(len(selected_qs), 2)
        self.assertEqual(diag["matched_questions_direct"], 1)
        self.assertEqual(diag["matched_questions_exclusive_topics"], 1)

        v2_docs, stats = migrator.migrate_batch(
            selected_qs,
            self.catalog,
            source_catalog_labels={"DISC_LEGADA_BCB": "Sistema Financeiro e Supervisão"},
            default_contest_tag=tpl["_id"],
        )
        self.assertEqual(len(v2_docs), 2)
        self.assertEqual(stats["skipped_invalid_count"], 0)
        for doc in v2_docs:
            qc.validate_question(doc, self.catalog)
        self.assertEqual(v2_docs[1]["concursos"], "BCB_2027_EF")

    def test_merge_source_catalog_extensions_preserves_confirmed_v2_links_and_is_idempotent(self):
        registry = kc.read(kc.ROOT / "shared/knowledge-id-registry.json")
        schema = kc.read(kc.ROOT / "shared/knowledge-catalog.schema.json")

        legacy_cnu_activation = {
            "catalog_version": 2,
            "sources": [
                {
                    "source_id": "BCB_EDITAL_2024",
                    "kind": "EDITORIAL_PROPOSAL",
                    "reference": "Edital BCB 2024",
                    "note": "Extensão editorial do Banco Central no acervo legado.",
                }
            ],
            "subjects": [
                {
                    "subject_id": "COSIF_BCB",
                    "label": "Contabilidade de Instituições Financeiras (COSIF)",
                    "aliases": ["COSIF"],
                    "scope_note": "Padrão contábil COSIF aplicável ao SFN.",
                    "status": "ACTIVE",
                    "revision": 1,
                    "source_ids": ["BCB_EDITAL_2024"],
                    # Legacy v1 trunk_links without default_weight
                    "trunk_links": [
                        {
                            "trunk_id": "bancaria",
                            "role": "FOUNDATIONAL",
                            "basis": "EDITORIAL_SUGGESTION",
                            "note": "Disciplina específica do BCB.",
                        }
                    ],
                },
                {
                    # Collides by label with existing MFIN ("Mercado Financeiro")
                    "subject_id": "FIN_BCB_DUP",
                    "label": "Mercado Financeiro",
                    "aliases": ["Finanças BCB"],
                    "scope_note": "Colisão intencional para testar remapeamento.",
                    "status": "ACTIVE",
                    "revision": 1,
                    "source_ids": ["BCB_EDITAL_2024"],
                    "trunk_links": [],
                },
            ],
            "topics": [
                {
                    "topic_id": "COSIF_TOP_01",
                    "subject_id": "COSIF_BCB",
                    "parent_topic_id": None,
                    "kind": "TOPIC",
                    "label": "Plano Contábil das Instituições do SFN",
                    "aliases": [],
                    "status": "ACTIVE",
                    "revision": 1,
                    "source_ids": ["BCB_EDITAL_2024"],
                },
                {
                    "topic_id": "BCB_PIX_ARRANJOS",
                    "subject_id": "FIN_BCB_DUP",
                    "parent_topic_id": None,
                    "kind": "TOPIC",
                    "label": "Arranjos de pagamento instantâneo e ecossistema Pix",
                    "aliases": [],
                    "status": "ACTIVE",
                    "revision": 1,
                    "source_ids": ["BCB_EDITAL_2024"],
                },
            ],
        }

        v3_cat, v2_reg, sid_map, tpair_map, tflat_map, extended = (
            migrator.merge_source_catalog_extensions(
                self.catalog,
                registry,
                [legacy_cnu_activation],
            )
        )
        self.assertTrue(extended)
        self.assertEqual(v3_cat["catalog_version"], self.catalog["catalog_version"] + 1)
        self.assertEqual(v2_reg["registry_version"], registry["registry_version"] + 1)
        kc.validate(v3_cat, v2_reg, schema)

        self.assertEqual(sid_map["COSIF_BCB"], "COSIF_BCB")
        self.assertEqual(sid_map["FIN_BCB_DUP"], "MFIN")
        self.assertEqual(tpair_map[("FIN_BCB_DUP", "BCB_PIX_ARRANJOS")], ("MFIN", "BCB_PIX_ARRANJOS"))

        # Re-merging onto v3 must be a strict no-op (idempotent)
        v3_again, reg_again, _, _, _, extended_again = migrator.merge_source_catalog_extensions(
            v3_cat,
            v2_reg,
            [legacy_cnu_activation],
        )
        self.assertFalse(extended_again)
        self.assertEqual(v3_again, v3_cat)
        self.assertEqual(reg_again, v2_reg)

        # Confirmed V2 question referencing FIN_BCB_DUP / BCB_PIX_ARRANJOS keeps CONFIRMED link under MFIN
        confirmed_q = copy.deepcopy(
            kc.read(kc.ROOT / "shared/examples/question-contracts/question-multiple-choice.json")
        )
        confirmed_q["disciplina"] = "Finanças BCB"
        confirmed_q["classification"] = {
            "revision": 2,
            "catalog_version": 2,
            "state": "CONFIRMED",
            "links": [
                {
                    "link_id": "11111111-2222-4333-8444-555555555555",
                    "subject_id": "FIN_BCB_DUP",
                    "topic_id": "BCB_PIX_ARRANJOS",
                    "role": "PRIMARY",
                    "coverage": "DIRECT",
                    "provenance": {
                        "actor": {"type": "HUMAN", "id": "3762914356612732"},
                        "method": "MANUAL_REVIEW",
                        "rationale": "Classificação editorial confirmada para o BCB.",
                        "recorded_at": "2026-11-12T10:00:00Z",
                        "evidence": [],
                        "model": None,
                        "prompt_id": None,
                        "prompt_version": None,
                        "source_suggestion_id": None,
                    },
                }
            ],
        }

        v2_docs, stats = migrator.migrate_batch(
            [confirmed_q],
            v3_cat,
            default_contest_tag="BCB_2027_EF",
            subject_id_map=sid_map,
            topic_pair_map=tpair_map,
            topic_flat_map=tflat_map,
        )
        self.assertEqual(stats["skipped_invalid_count"], 0)
        self.assertEqual(stats["confirmed_classified_count"], 1)
        self.assertEqual(stats["mapped_discipline_count"], 1)
        out = v2_docs[0]
        self.assertEqual(out["classification"]["state"], "CONFIRMED")
        self.assertEqual(out["classification"]["catalog_version"], v3_cat["catalog_version"])
        self.assertEqual(out["canonical_subject_id"], "MFIN")
        self.assertEqual(out["canonical_topic_id"], "BCB_PIX_ARRANJOS")
        self.assertEqual(out["disciplina"], "Mercado Financeiro")

    def test_cast_catalog_doc_int64_fields_wraps_metadata_integers(self):
        class FakeInt64(int):
            pass

        head = {
            "_id": "cogniva-knowledge",
            "activation_id": "0:abc",
            "version": 3,
            "reviewed": True,
        }
        patched_head = migrator._cast_catalog_doc_int64_fields(head, ("version", "reviewed"), FakeInt64)
        self.assertIsInstance(patched_head["version"], FakeInt64)
        self.assertEqual(patched_head["version"], 3)
        self.assertIs(patched_head["reviewed"], True)
        self.assertNotIsInstance(patched_head["reviewed"], FakeInt64)

        act = {
            "_id": "0:abc",
            "version": 3,
            "actor_user_id": 0,
            "draft_revision": 1,
        }
        patched_act = migrator._cast_catalog_doc_int64_fields(
            act, ("version", "actor_user_id", "draft_revision"), FakeInt64
        )
        self.assertIsInstance(patched_act["version"], FakeInt64)
        self.assertIsInstance(patched_act["actor_user_id"], FakeInt64)
        self.assertIsInstance(patched_act["draft_revision"], FakeInt64)


if __name__ == "__main__":
    unittest.main()


