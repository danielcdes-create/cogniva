"""Wire-shape parity for editable exam components and global exam templates."""
import copy
import json
import pathlib
import unittest
from jsonschema import Draft202012Validator

ROOT = pathlib.Path(__file__).resolve().parents[2]


class ExamComponentContractTest(unittest.TestCase):
    def setUp(self):
        self.validator = Draft202012Validator(
            json.loads((ROOT / "shared/schemas/exam-component.schema.json").read_text(encoding="utf-8"))
        )
        self.templates = json.loads((ROOT / "shared/exam-templates.json").read_text(encoding="utf-8"))["templates"]
        self.components = self.templates[0]["subjects"]

    def test_global_templates_and_same_subject_in_two_stages(self):
        self.assertEqual({t["_id"] for t in self.templates}, {"CPNU", "RFB", "BNDES", "AFT"})
        for tpl in self.templates:
            for component in tpl["subjects"]:
                self.validator.validate(component)
        second = copy.deepcopy(self.components[0])
        second.update(local_subject_id="second-stage", stage="2ª etapa")
        self.validator.validate(second)
        self.assertEqual(second["canonical_subject_id"], self.components[0]["canonical_subject_id"])

    def test_pending_custom_component_and_invalid_weight(self):
        custom = copy.deepcopy(self.components[0])
        custom.update(canonical_subject_id="custom-00000000-0000-4000-8000-000000000001", topics=[])
        self.validator.validate(custom)
        custom["weight"] = 0
        self.assertFalse(self.validator.is_valid(custom))
