import copy
from datetime import date
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import question_contracts as qc
from knowledge_catalog import ROOT, read

BASE = ROOT / "shared/examples/question-contracts"


def fixture(name):
    return read(BASE / (name + ".json"))


def rehash(q):
    q["content_revision"] = qc.content_revision(q)
    if q["validity"]["evaluated_content_revision"] is not None:
        q["validity"]["evaluated_content_revision"] = q["content_revision"]


class QuestionContractsTest(unittest.TestCase):
    def setUp(self):
        self.catalog = read(ROOT / "shared/knowledge-catalog.json")

    def test_all_question_formats_and_obsolete_unknown_examples(self):
        for path in BASE.glob("question-*.json"):
            with self.subTest(path=path.name):
                qc.validate_question(read(path), self.catalog)

    def test_five_options_and_one_correct_option_are_required_for_multiple_choice(self):
        q = fixture("question-multiple-choice")
        self.assertEqual(len(q["answers"]), 5)
        qc.validate_question(q, self.catalog)
        q4 = copy.deepcopy(q)
        q4["answers"].pop()
        rehash(q4)
        with self.assertRaisesRegex(ValueError, "schema"):
            qc.validate_question(q4, self.catalog)
        q6 = copy.deepcopy(q)
        q6["answers"].append("8")
        rehash(q6)
        with self.assertRaisesRegex(ValueError, "schema"):
            qc.validate_question(q6, self.catalog)
        q = fixture("question-multiple-choice")
        q["correct_answer_indices"] = [0, 1]
        with self.assertRaisesRegex(ValueError, "schema"):
            qc.validate_question(q, self.catalog)

    def test_true_false_uses_explicit_order_and_open_has_no_fake_options(self):
        q = fixture("question-true-false")
        q["answers"] = ["Errado", "Certo"]
        with self.assertRaisesRegex(ValueError, "schema"):
            qc.validate_question(q, self.catalog)
        q = fixture("question-open-ai")
        q["answers"] = ["placeholder"]
        with self.assertRaisesRegex(ValueError, "schema"):
            qc.validate_question(q, self.catalog)

    def test_ambiguous_options_and_contradictory_answer_keys_fail(self):
        q = fixture("question-multiple-choice")
        q["answers"][0] = "4 "
        rehash(q)
        with self.assertRaisesRegex(ValueError, "duplicate answer"):
            qc.validate_question(q, self.catalog)
        q = fixture("question-multiple-choice")
        q["correct_answers"] = ["5"]
        with self.assertRaisesRegex(ValueError, "answers disagree"):
            qc.validate_question(q, self.catalog)

    def test_content_hash_ignores_classification_and_ai_but_includes_rubric(self):
        q = fixture("question-open-ai")
        original = q["content_revision"]
        q["mnemonico"] = "Cache sintético"
        q["classification"]["revision"] += 1
        self.assertEqual(qc.content_revision(q), original)
        q["grading"]["rubric"]["criteria"][0]["description"] += " Ajuste de critério."
        self.assertNotEqual(qc.content_revision(q), original)
        with self.assertRaisesRegex(ValueError, "content revision mismatch"):
            qc.validate_question(q, self.catalog)

    def test_primary_secondary_links_need_review_and_consistent_taxonomy(self):
        q = fixture("question-multiple-choice")
        link = q["classification"]["links"][0]
        link["provenance"]["actor"]["type"] = "AI"
        with self.assertRaisesRegex(ValueError, "human review"):
            qc.validate_question(q, self.catalog)
        q = fixture("question-multiple-choice")
        second = copy.deepcopy(q["classification"]["links"][0])
        second.update(link_id="00000000-0000-0000-0000-000000000999", topic_id="MAT_01_002")
        q["classification"]["links"].append(second)
        with self.assertRaisesRegex(ValueError, "multiple primary"):
            qc.validate_question(q, self.catalog)
        second["role"] = "SECONDARY"
        qc.validate_question(q, self.catalog)
        second["subject_id"] = "PORT"
        with self.assertRaisesRegex(ValueError, "taxonomy link"):
            qc.validate_question(q, self.catalog)

    def test_obsolete_and_review_pending_stay_out_of_current_practice(self):
        for name in ["question-obsolete", "question-unclassified"]:
            q = fixture(name)
            self.assertFalse(qc.practice_eligible(q, "CURRENT_ONLY", date(2026, 11, 2)))
            self.assertFalse(qc.practice_eligible(q, "GENERAL", date(2026, 11, 2)))
        q_valid = fixture("question-multiple-choice")
        self.assertTrue(qc.practice_eligible(q_valid, "GENERAL", date(2026, 11, 2)))
        q = fixture("question-unclassified")
        q["validity"]["status"] = "UNDER_REVIEW"
        self.assertFalse(qc.practice_eligible(q, "GENERAL", date(2026, 11, 2)))

    def test_normative_review_needs_evidence_and_expires_without_erasing_history(self):
        q = fixture("question-obsolete")
        q["validity"]["status"] = "CURRENT"
        q["validity"]["reason_codes"] = []
        q["validity"]["review_due_on"] = "2026-11-03"
        qc.validate_question(q, self.catalog)
        self.assertTrue(qc.practice_eligible(q, "CURRENT_ONLY", date(2026, 11, 2)))
        self.assertFalse(qc.practice_eligible(q, "CURRENT_ONLY", date(2026, 11, 4)))
        q["validity"]["decision"]["evidence"] = []
        with self.assertRaisesRegex(ValueError, "requires evidence"):
            qc.validate_question(q, self.catalog)

    def test_historical_context_is_distinct_from_current_legislation(self):
        q = fixture("question-obsolete")
        q["validity"].update(status="CURRENT", reason_codes=[], scope="EXPLICIT_HISTORICAL_DATE", context_date="2020-01-01", review_due_on="2027-01-01")
        qc.validate_question(q, self.catalog)
        self.assertFalse(qc.practice_eligible(q, "CURRENT_ONLY", date(2026, 11, 2)))
        self.assertFalse(qc.practice_eligible(q, "GENERAL", date(2026, 11, 2)))
        self.assertTrue(qc.practice_eligible(q, "HISTORICAL_ONLY", date(2026, 11, 2)))

    def test_stale_validity_and_unknown_without_review_cannot_claim_current(self):
        q = fixture("question-multiple-choice")
        q["validity"]["evaluated_content_revision"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "stale validity"):
            qc.validate_question(q, self.catalog)
        q = fixture("question-unclassified")
        q["validity"]["status"] = "CURRENT"
        with self.assertRaisesRegex(ValueError, "schema"):
            qc.validate_question(q, self.catalog)

    def test_open_rubric_totals_and_engine_contracts_are_enforced(self):
        q = fixture("question-open-ai")
        q["grading"]["rubric"]["criteria"][0]["max_points"] = 40
        rehash(q)
        with self.assertRaisesRegex(ValueError, "rubric total"):
            qc.validate_question(q, self.catalog)
        q = fixture("question-open-ai")
        q["grading"]["engine"]["prompt_version"] = None
        rehash(q)
        with self.assertRaisesRegex(ValueError, "prompt version"):
            qc.validate_question(q, self.catalog)
        q = fixture("question-open-semantic")
        q["grading"]["engine"]["calibration_id"] = None
        rehash(q)
        with self.assertRaisesRegex(ValueError, "calibrated engine"):
            qc.validate_question(q, self.catalog)

    def test_suggestion_is_pending_not_confirmed_and_cannot_target_stale_revision(self):
        q, s = fixture("question-unclassified"), fixture("suggestion-pending")
        qc.validate_suggestion(s, q, self.catalog)
        s["expected_classification_revision"] = 99
        with self.assertRaisesRegex(ValueError, "stale suggestion"):
            qc.validate_suggestion(s, q, self.catalog)
        s["status"] = "STALE"
        qc.validate_suggestion(s, q, self.catalog)

    def test_acceptance_requires_human_revision_increment_and_proposal_trace(self):
        q, s = fixture("question-unclassified"), fixture("suggestion-pending")
        s["status"] = "ACCEPTED"
        s["resolution"] = {"decided_at": "2026-11-02T16:00:00Z", "actor": {"type": "AI", "id": "fixture"}, "reason": "Synthetic acceptance", "resulting_classification_revision": 1}
        with self.assertRaisesRegex(ValueError, "requires human"):
            qc.validate_suggestion(s, q, self.catalog)
        s["resolution"]["actor"]["type"] = "HUMAN"
        q["classification"] = copy.deepcopy(fixture("question-multiple-choice")["classification"])
        with self.assertRaisesRegex(ValueError, "lost suggestion provenance"):
            qc.validate_suggestion(s, q, self.catalog)
        q["classification"]["links"][0]["provenance"]["source_suggestion_id"] = s["_id"]
        qc.validate_suggestion(s, q, self.catalog)

    def test_origin_does_not_establish_exam_compatibility_or_bypass_owner(self):
        q, c, exam = fixture("question-multiple-choice"), fixture("compatibility-direct"), fixture("exam-context")
        qc.validate_compatibility(c, q, self.catalog, exam)
        c["owner_user_id"] = 456
        with self.assertRaisesRegex(ValueError, "owner/exam"):
            qc.validate_compatibility(c, q, self.catalog, exam)
        c["owner_user_id"] = 123
        c["matched_topic_ids"] = ["PORT_01_001"]
        with self.assertRaisesRegex(ValueError, "grade intersection"):
            qc.validate_compatibility(c, q, self.catalog, exam)

    def test_evaluation_examples_cover_exact_manual_semantic_and_ai(self):
        for name, question in [("objective", "multiple-choice"), ("open-manual", "open-manual"), ("open-semantic", "open-semantic"), ("open-ai", "open-ai")]:
            with self.subTest(name=name):
                qc.validate_evaluation(fixture("evaluation-" + name), fixture("question-" + question))

    def test_ai_provisional_result_cannot_be_a_confirmed_hit(self):
        e, q = fixture("evaluation-open-ai"), fixture("question-open-ai")
        e["is_correct"] = True
        with self.assertRaisesRegex(ValueError, "provisional score"):
            qc.validate_evaluation(e, q)
        e["is_correct"] = None
        e["status"] = "CONFIRMED"
        with self.assertRaisesRegex(ValueError, "human review"):
            qc.validate_evaluation(e, q)

    def test_scores_cannot_exceed_rubric_or_omit_a_criterion(self):
        e, q = fixture("evaluation-open-ai"), fixture("question-open-ai")
        e["criterion_results"][0]["awarded_points"] = 60
        with self.assertRaisesRegex(ValueError, "criterion score"):
            qc.validate_evaluation(e, q)
        e = fixture("evaluation-open-ai")
        e["criterion_results"].pop()
        with self.assertRaisesRegex(ValueError, "missing or duplicate"):
            qc.validate_evaluation(e, q)

    def test_stale_rubric_model_provenance_and_objective_spoofing_fail(self):
        e, q = fixture("evaluation-open-ai"), fixture("question-open-ai")
        e["rubric_revision"] = 2
        with self.assertRaisesRegex(ValueError, "stale rubric"):
            qc.validate_evaluation(e, q)
        e = fixture("evaluation-open-ai")
        e["decision"]["model"] = "different-model"
        with self.assertRaisesRegex(ValueError, "provenance differs"):
            qc.validate_evaluation(e, q)
        e, q = fixture("evaluation-objective"), fixture("question-multiple-choice")
        e["submission"]["selected_index"] = 0
        with self.assertRaisesRegex(ValueError, "disagrees with answer key"):
            qc.validate_evaluation(e, q)

    def test_secondary_content_cannot_be_promoted_to_direct_compatibility(self):
        q, c, exam = fixture("question-multiple-choice"), fixture("compatibility-direct"), fixture("exam-context")
        q["classification"]["links"][0]["role"] = "SECONDARY"
        q["classification"]["state"] = "PARTIAL"
        with self.assertRaisesRegex(ValueError, "grade intersection"):
            qc.validate_compatibility(c, q, self.catalog, exam)

    def test_essential_criterion_cannot_be_bypassed_by_total_score(self):
        q, e = fixture("question-open-manual"), fixture("evaluation-open-manual")
        q["grading"]["pass_score"] = 40
        rehash(q)
        e["question_content_revision"] = q["content_revision"]
        e["criterion_results"][0]["awarded_points"] = 0
        e["score"] = 50
        with self.assertRaisesRegex(ValueError, "correctness disagrees"):
            qc.validate_evaluation(e, q)
        e["is_correct"] = False
        qc.validate_evaluation(e, q)

    def test_incomplete_evaluation_is_not_an_incorrect_answer_and_counters_cannot_overflow(self):
        q, e = fixture("question-open-ai"), fixture("evaluation-open-ai")
        e.update(status="FAILED", score=None, is_correct=None, criterion_results=[])
        qc.validate_evaluation(e, q)
        e["score"] = 0
        with self.assertRaisesRegex(ValueError, "ungraded response has a score"):
            qc.validate_evaluation(e, q)
        q["revision"] = 9223372036854775807
        with self.assertRaisesRegex(ValueError, "schema"):
            qc.validate_question(q, self.catalog)

    def test_evaluation_cannot_change_the_owner_or_submitted_text(self):
        e = fixture("evaluation-open-ai")
        attempt = next(a for a in fixture("attempt-contexts") if a["attempt_id"] == e["attempt_id"])
        qc.validate_evaluation_binding(e, attempt)
        e["user_id"] = 456
        with self.assertRaisesRegex(ValueError, "owner mismatch"):
            qc.validate_evaluation_binding(e, attempt)
        e["user_id"] = 123
        e["submission"]["text"] = "Different submitted answer"
        with self.assertRaisesRegex(ValueError, "changed submitted answer"):
            qc.validate_evaluation_binding(e, attempt)

    def test_regrading_appends_a_result_for_the_same_attempt(self):
        previous = fixture("evaluation-open-ai")
        current = copy.deepcopy(previous)
        current["_id"] = "00000000-0000-0000-0000-000000000999"
        current["supersedes_evaluation_id"] = previous["_id"]
        attempt = next(a for a in fixture("attempt-contexts") if a["attempt_id"] == current["attempt_id"])
        qc.validate_evaluation_binding(current, attempt, previous)
        previous["user_id"] = 456
        with self.assertRaisesRegex(ValueError, "another attempt"):
            qc.validate_evaluation_binding(current, attempt, previous)

    def test_unsafe_evidence_uri_schemes_and_out_of_bounds_metadata_fail(self):
        q_urn = fixture("question-obsolete")
        q_urn["validity"]["decision"]["evidence"][0]["uri"] = "urn:cogniva:admin-review:672f8b2c4e1a2b3c4d5e6f70"
        qc.validate_question(q_urn, self.catalog)
        q_pdf = fixture("question-obsolete")
        q_pdf["validity"]["decision"]["evidence"][0]["uri"] = "file:///C:/Users/danie/Cogniva/questoes/959_BCB_CB1_01.pdf"
        qc.validate_question(q_pdf, self.catalog)
        for bad_uri in (
            "javascript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "file:///etc/passwd",
            "file:///C:/Users/../secret.pdf",
            "urn:evil:payload",
        ):
            q = fixture("question-obsolete")
            q["validity"]["decision"]["evidence"][0]["uri"] = bad_uri
            with self.assertRaisesRegex(ValueError, "(schema|unsafe evidence uri)"):
                qc.validate_question(q, self.catalog)
        for bad_year in (1850, 2250):
            q = fixture("question-multiple-choice")
            q["exam_year"] = bad_year
            with self.assertRaisesRegex(ValueError, "(schema|exam_year)"):
                qc.validate_question(q, self.catalog)
        q = fixture("question-multiple-choice")
        q["concursos"] = [f"CONCURSO_{i}" for i in range(51)]
        with self.assertRaisesRegex(ValueError, "(schema|concursos)"):
            qc.validate_question(q, self.catalog)

    def test_v2_1_parity_vectors_hashes_errors_and_eligibility(self):
        bundle = fixture("parity-vectors-v2-1")
        for vec in bundle["validation_vectors"]:
            with self.subTest(vector=vec["id"]):
                doc = copy.deepcopy(vec["document"])
                if vec["valid"]:
                    if vec.get("untrusted_input"):
                        qc.validate_untrusted_client_question(doc, self.catalog)
                    else:
                        qc.validate_question(doc, self.catalog)
                    self.assertEqual(qc.content_revision(doc), vec["expected_hashes"]["content_revision"])
                    self.assertEqual(qc.semantic_revision(doc), vec["expected_hashes"]["semantic_revision"])
                    self.assertEqual(qc.grading_revision(doc), vec["expected_hashes"]["grading_revision"])
                else:
                    with self.assertRaises(qc.ContractValidationError) as ctx:
                        if vec.get("untrusted_input"):
                            qc.validate_untrusted_client_question(doc, self.catalog)
                        else:
                            qc.validate_question(doc, self.catalog)
                    self.assertEqual(ctx.exception.code, vec["expected_code"])
                    self.assertEqual(ctx.exception.field, vec["expected_field"])

        for vec in bundle["eligibility_vectors"]:
            with self.subTest(eligibility=vec["id"]):
                res = qc.evaluate_question_eligibility(
                    question=copy.deepcopy(vec["question"]),
                    catalog=self.catalog,
                    exam_context=vec["exam_context"],
                    evaluation_date=vec["evaluation_date"],
                    trusted_review_context=vec["trusted_review_context"],
                )
                self.assertEqual(res["eligible"], vec["expected"]["eligible"])
                self.assertEqual(res["reason_codes"], vec["expected"]["reason_codes"])
                self.assertEqual(res["coverage_mode"], vec["expected"]["coverage_mode"])
                self.assertEqual(res["is_borrowed_analogous"], vec["expected"]["is_borrowed_analogous"])

    def test_semantic_vs_grading_revision_separation_and_substantive_criteria(self):
        # T04, T05, T06, T24
        open_manual = fixture("question-open-manual")
        open_ai = fixture("question-open-ai")
        open_sem = fixture("question-open-semantic")
        # Identical stem, expected_responses, and substantive criteria across RUBRIC / AI / SEMANTIC!
        self.assertEqual(qc.semantic_revision(open_manual), qc.semantic_revision(open_ai))
        self.assertEqual(qc.semantic_revision(open_manual), qc.semantic_revision(open_sem))
        self.assertNotEqual(qc.grading_revision(open_manual), qc.grading_revision(open_ai))
        self.assertNotEqual(qc.content_revision(open_manual), qc.content_revision(open_ai))

        q = copy.deepcopy(open_manual)
        qc.populate_v2_1_hashes(q)
        q["validity"]["evaluated_semantic_revision"] = q["semantic_revision"]
        q["validity"]["evaluation_mode"] = "SEMANTIC"
        qc.validate_question(q, self.catalog)

        # Numeric-only score change preserves semantic_revision and CURRENT validity
        q_num = copy.deepcopy(q)
        q_num["grading"]["max_score"] = 50
        q_num["grading"]["pass_score"] = 30
        q_num["grading"]["rubric"]["criteria"][0]["max_points"] = 25
        q_num["grading"]["rubric"]["criteria"][1]["max_points"] = 25
        qc.populate_v2_1_hashes(q_num)
        self.assertEqual(q_num["semantic_revision"], q["semantic_revision"])
        self.assertNotEqual(q_num["grading_revision"], q["grading_revision"])
        self.assertNotEqual(q_num["content_revision"], q["content_revision"])
        qc.validate_question(q_num, self.catalog)

        # Substantive criterion essential/description change alters semantic_revision and invalidates stale validity
        q_sub = copy.deepcopy(q)
        q_sub["grading"]["rubric"]["criteria"][0]["essential"] = False
        qc.populate_v2_1_hashes(q_sub)
        self.assertNotEqual(q_sub["semantic_revision"], q["semantic_revision"])
        with self.assertRaises(qc.ContractValidationError) as ctx:
            qc.validate_question(q_sub, self.catalog)
        self.assertEqual(ctx.exception.code, "SEMANTIC_REVISION_MISMATCH")

    def test_strict_bson_object_id_and_integer_boundaries(self):
        # T19, T20
        from bson import ObjectId

        q = fixture("question-multiple-choice")
        qc.populate_v2_1_hashes(q)
        bson_doc = qc.contract_to_bson_question(q)
        self.assertIsInstance(bson_doc["_id"], ObjectId)
        roundtrip = qc.validate_bson_question(bson_doc, self.catalog)
        self.assertEqual(roundtrip["_id"], q["_id"])

        # Storing Extended JSON dict directly in BSON _id is rejected
        bad_extjson = copy.deepcopy(bson_doc)
        bad_extjson["_id"] = {"$oid": "000000000000000000000001"}
        with self.assertRaises(qc.ContractValidationError) as ctx:
            qc.validate_bson_question(bad_extjson, self.catalog)
        self.assertEqual(ctx.exception.code, "INVALID_OBJECT_ID")

        # Storing float 1.0 or bool True in BSON revision is rejected
        for bad_val in (1.0, True, "1"):
            bad_int = copy.deepcopy(bson_doc)
            bad_int["revision"] = bad_val
            with self.assertRaises(qc.ContractValidationError) as ctx:
                qc.validate_bson_question(bad_int, self.catalog)
            self.assertEqual(ctx.exception.code, "INVALID_INTEGER")

        # API question_id boundary
        self.assertEqual(qc.validate_api_question_id("000000000000000000000001"), "000000000000000000000001")
        for bad_api_id in ("00000000000000000000000G", "000000000000000000000001AA", "ABCD00000000000000000001", {"$oid": "00000000000000000000001"}):
            with self.assertRaises(qc.ContractValidationError) as ctx:
                qc.validate_api_question_id(bad_api_id)
            self.assertEqual(ctx.exception.code, "INVALID_OBJECT_ID")

    def test_editorial_card_validates_and_preserves_canonical_hashes(self):
        q = fixture("question-multiple-choice")
        qc.populate_v2_1_hashes(q)
        orig_content = q["content_revision"]
        orig_semantic = q["semantic_revision"]

        q_ed = copy.deepcopy(q)
        q_ed["editorial_card"] = {
            "legal_provisions": ["CF/88, art. 37, caput", "Lei nº 9.784/1999, art. 53"],
            "tested_rule": "A Administração deve anular atos ilegais e pode revogar atos convenientes ou oportunos.",
            "exception_clause": "Respeitados os direitos adquiridos e o prazo decadencial de 5 anos salvo comprovada má-fé.",
            "decisive_stem_excerpt": "eivados de vício insanável de legalidade",
            "official_source_excerpt": "Art. 53. A Administração deve anular seus próprios atos, quando eivados de vício de legalidade...",
            "plausible_conceptual_errors": [
                {
                    "error_id": "ERR_REVOGACAO_ILEGALIDADE",
                    "label": "Confusão entre revogação e anulação",
                    "description": "Tratar vício de legalidade como hipótese de revogação por mérito administrativo."
                }
            ],
        }
        qc.populate_v2_1_hashes(q_ed)
        self.assertEqual(q_ed["content_revision"], orig_content)
        self.assertEqual(q_ed["semantic_revision"], orig_semantic)
        qc.validate_question(q_ed, self.catalog)

        bad_dup = copy.deepcopy(q_ed)
        bad_dup["editorial_card"]["plausible_conceptual_errors"].append(
            bad_dup["editorial_card"]["plausible_conceptual_errors"][0]
        )
        with self.assertRaises(qc.ContractValidationError) as ctx:
            qc.validate_question(bad_dup, self.catalog)
        self.assertEqual(ctx.exception.code, "SCHEMA_VALIDATION_FAILED")
        self.assertEqual(ctx.exception.field, "editorial_card.plausible_conceptual_errors")


if __name__ == "__main__":
    unittest.main()
