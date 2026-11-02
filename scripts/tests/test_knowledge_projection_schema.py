import json
import pathlib
import unittest
from jsonschema import Draft202012Validator

ROOT = pathlib.Path(__file__).resolve().parents[2]


class KnowledgeProjectionSchemaTest(unittest.TestCase):
    def test_projection_and_cache_request_shapes(self):
        schema = json.loads((ROOT / "shared/schemas/knowledge-projection.schema.json").read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        def validator(kind):
            return Draft202012Validator({"$ref": "#/$defs/" + kind, "$defs": schema["$defs"]})
        evidence = dict(canonical_subject_id="MAT", canonical_topic_id="MAT_01_001", question_revision="a"*64, attempts=1, correct=1, accuracy_pct=100, confidence_avg=3, recent_confidences=[3], high_confidence_error=False, mastered=False, review_due_on=None)
        validator("evidence").validate(evidence)
        self.assertFalse(validator("evidence").is_valid(dict(evidence, attempts=-1)))
        validator("cache_request").validate({"question_ids": ["a"*24]})
        self.assertFalse(validator("cache_request").is_valid({"question_ids": ["a"*24]*2}))
