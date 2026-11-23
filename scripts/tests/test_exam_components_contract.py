"""Wire-shape parity for editable exam components and global exam templates."""
import copy
import json
import pathlib
import unittest
from jsonschema import Draft202012Validator, FormatChecker

ROOT = pathlib.Path(__file__).resolve().parents[2]


class ExamComponentContractTest(unittest.TestCase):
    def setUp(self):
        component_schema = json.loads(
            (ROOT / "shared/schemas/exam-component.schema.json").read_text(encoding="utf-8")
        )
        templates_schema = json.loads(
            (ROOT / "shared/schemas/exam-templates.schema.json").read_text(encoding="utf-8")
        )
        Draft202012Validator.check_schema(component_schema)
        Draft202012Validator.check_schema(templates_schema)
        self.validator = Draft202012Validator(component_schema, format_checker=FormatChecker())
        self.templates_validator = Draft202012Validator(templates_schema, format_checker=FormatChecker())
        self.templates_bundle = json.loads(
            (ROOT / "shared/exam-templates.json").read_text(encoding="utf-8")
        )
        self.templates = self.templates_bundle["templates"]
        self.components = self.templates[0]["subjects"]

    def test_global_templates_and_same_subject_in_two_stages(self):
        self.templates_validator.validate(self.templates_bundle)
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

    def test_exam_templates_schema_enforces_prova_realizada_and_iso_dates(self):
        bundle_realizada = copy.deepcopy(self.templates_bundle)
        bundle_realizada["templates"][0]["status"] = "PROVA_REALIZADA"
        bundle_realizada["templates"][0]["target_date"] = {
            "type": "EXACT_DATE",
            "iso_date": "2026-09-20",
        }
        self.templates_validator.validate(bundle_realizada)

        bundle_window = copy.deepcopy(self.templates_bundle)
        bundle_window["templates"][0]["target_date"] = {
            "type": "ESTIMATED_WINDOW",
            "label": "2º semestre de 2027",
            "estimated_sort_date": "2027-10-01",
        }
        self.templates_validator.validate(bundle_window)

        bundle_legacy_status = copy.deepcopy(self.templates_bundle)
        bundle_legacy_status["templates"][0]["status"] = "POS_PROVA"
        self.assertFalse(self.templates_validator.is_valid(bundle_legacy_status))

        bad_updated_at = copy.deepcopy(self.templates_bundle)
        bad_updated_at["updated_at"] = "2026-13-40"
        self.assertFalse(self.templates_validator.is_valid(bad_updated_at))

        bad_target_date = copy.deepcopy(bundle_realizada)
        bad_target_date["templates"][0]["target_date"]["iso_date"] = "not-an-iso-date"
        self.assertFalse(self.templates_validator.is_valid(bad_target_date))


if __name__ == "__main__":
    unittest.main()
