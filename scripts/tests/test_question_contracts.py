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

    def test_five_options_and_one_correct_option_are_required(self):
        q = fixture("question-multiple-choice")
        q["answers"].pop()
        with self.assertRaisesRegex(ValueError, "schema"):
            qc.validate_question(q, self.catalog)
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
        q = fixture("question-unclassified")
        self.assertTrue(qc.practice_eligible(q, "GENERAL", date(2026, 11, 2)))
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


if __name__ == "__main__":
    unittest.main()
