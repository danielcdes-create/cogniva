import json
import pathlib
import unittest
from jsonschema import Draft202012Validator, FormatChecker

ROOT = pathlib.Path(__file__).resolve().parents[2]


class KnowledgeProjectionSchemaTest(unittest.TestCase):
    def setUp(self):
        self.schema = json.loads(
            (ROOT / "shared/schemas/knowledge-projection.schema.json").read_text(encoding="utf-8")
        )
        Draft202012Validator.check_schema(self.schema)

    def validator(self, kind: str) -> Draft202012Validator:
        return Draft202012Validator(
            {"$ref": "#/$defs/" + kind, "$defs": self.schema["$defs"]},
            format_checker=FormatChecker(),
        )

    def test_projection_and_cache_request_shapes(self):
        ev_validator = self.validator("evidence")
        evidence = dict(
            canonical_subject_id="MAT",
            canonical_topic_id="MAT_01_001",
            question_revision="a" * 64,
            attempts=1,
            correct=1,
            accuracy_pct=100,
            confidence_avg=3,
            recent_confidences=[3],
            high_confidence_error=False,
            mastered=False,
            review_due_on=None,
        )
        ev_validator.validate(evidence)
        ev_validator.validate(dict(evidence, review_due_on="2026-12-31"))
        self.assertFalse(ev_validator.is_valid(dict(evidence, attempts=-1)))
        self.assertFalse(ev_validator.is_valid(dict(evidence, review_due_on="not-a-date")))
        self.assertFalse(ev_validator.is_valid(dict(evidence, review_due_on="2026-02-30")))

        cache_validator = self.validator("cache_request")
        cache_validator.validate({"question_ids": ["a" * 24]})
        self.assertFalse(cache_validator.is_valid({"question_ids": ["a" * 24] * 2}))
        self.assertFalse(cache_validator.is_valid({"question_ids": ["not-an-oid"]}))

    def test_question_binding_and_compatibility_decision_shapes(self):
        binding_validator = self.validator("question_binding")
        binding_validator.validate(
            {
                "question_revision": "b" * 64,
                "knowledge_projection_version": 1,
                "knowledge_binding_verified": True,
                "extra_dto_field": "allowed",
            }
        )
        self.assertFalse(
            binding_validator.is_valid(
                {
                    "question_revision": "short",
                    "knowledge_projection_version": 1,
                    "knowledge_binding_verified": True,
                }
            )
        )
        self.assertFalse(
            binding_validator.is_valid(
                {
                    "question_revision": "b" * 64,
                    "knowledge_projection_version": 2,
                    "knowledge_binding_verified": True,
                }
            )
        )

        decision_validator = self.validator("decision")
        decision = {
            "question_id": "c" * 24,
            "question_revision": "d" * 64,
            "exam_id": "CPNU",
            "exam_revision": 2,
            "component_id": "cpnu-mat",
            "relation": "DIRECT",
            "revision": 1,
            "catalog_version": 2,
        }
        for rel in ("DIRECT", "ANALOGOUS", "EXCLUDED"):
            decision_validator.validate(dict(decision, relation=rel))
        self.assertFalse(decision_validator.is_valid(dict(decision, relation="BORROWED")))
        self.assertFalse(decision_validator.is_valid(dict(decision, question_id="invalid")))
        self.assertFalse(decision_validator.is_valid(dict(decision, unexpected=True)))


if __name__ == "__main__":
    unittest.main()
