import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import knowledge_catalog as kc


class KnowledgeCatalogTest(unittest.TestCase):
    def setUp(self):
        self.catalog = kc.read(kc.ROOT / "shared/knowledge-catalog.json")
        self.registry = kc.read(kc.ROOT / "shared/knowledge-id-registry.json")
        self.schema = kc.read(kc.ROOT / "shared/knowledge-catalog.schema.json")
        self.templates = kc.read(kc.ROOT / "shared/exam-templates.json")

    def validate(self):
        return kc.validate(self.catalog, self.registry, self.schema, self.templates)

    def test_unified_catalog_preserves_legacy_ids_weights_icons_and_templates(self):
        result = self.validate()
        self.assertEqual(result["catalog_version"], 2)
        self.assertEqual(result["status"], "ACTIVE")
        self.assertEqual(result["legacy_subjects_preserved"], 24)
        self.assertEqual(result["legacy_topics_preserved"], 26)
        self.assertEqual(result["trunks"], 7)
        self.assertEqual(result["exam_templates"], 4)
        # Verify calibrated weights from legacy templates are preserved
        dir_trib = next(s for s in self.catalog["subjects"] if s["subject_id"] == "DIR_TRIB")
        fiscal_link = next(l for l in dir_trib["trunk_links"] if l["trunk_id"] == "fiscal")
        self.assertEqual(fiscal_link["default_weight"], 3.0)

    def test_duplicate_identity_is_rejected_even_with_different_content(self):
        duplicate = copy.deepcopy(self.catalog["subjects"][0])
        duplicate["label"] = "Outra disciplina"
        self.catalog["subjects"].append(duplicate)
        with self.assertRaisesRegex(ValueError, "duplicate subject_id"):
            self.validate()

    def test_unknown_subject_parent_and_cross_subject_parent_are_rejected(self):
        child = next(t for t in self.catalog["topics"] if t["kind"] == "SUBTOPIC")
        for field, value, message in [
            ("subject_id", "UNKNOWN", "unknown subject"),
            ("parent_topic_id", "UNKNOWN", "unknown parent"),
            ("parent_topic_id", "PORT_01", "cross-subject parent"),
        ]:
            with self.subTest(field=field, value=value):
                original = child[field]
                child[field] = value
                with self.assertRaisesRegex(ValueError, message):
                    self.validate()
                child[field] = original

    def test_self_parent_and_cycle_are_rejected(self):
        children = [t for t in self.catalog["topics"] if t["kind"] == "SUBTOPIC"][:2]
        children[0]["parent_topic_id"] = children[0]["topic_id"]
        with self.assertRaisesRegex(ValueError, "invalid hierarchy"):
            self.validate()
        children[0]["parent_topic_id"] = children[1]["topic_id"]
        children[1]["parent_topic_id"] = children[0]["topic_id"]
        with self.assertRaisesRegex(ValueError, "invalid hierarchy"):
            self.validate()

    def test_alias_collision_ignores_case_accents_and_punctuation(self):
        self.catalog["subjects"][0]["aliases"].append("PORTUGUÊS")
        with self.assertRaisesRegex(ValueError, "ambiguous alias"):
            self.validate()

    def test_original_name_may_change_only_if_preserved_as_alias(self):
        subject = next(s for s in self.catalog["subjects"] if s["subject_id"] == "PORT")
        subject["label"] = "Português Geral"
        subject["aliases"] = ["Língua Portuguesa", "Português"]
        self.validate()
        subject["aliases"] = []
        with self.assertRaisesRegex(ValueError, "original label lost"):
            self.validate()

    def test_same_subject_reparenting_cannot_repurpose_registered_id(self):
        child = next(t for t in self.catalog["topics"] if t["topic_id"] == "FIN_PUB_01_001")
        child["parent_topic_id"] = "FIN_PUB_02"
        with self.assertRaisesRegex(ValueError, "identity repurposed"):
            self.validate()

    def test_no_silent_equivalence_or_reverse_duplicate_relation(self):
        relation = self.catalog["relations"][0]
        relation["relation"] = "EQUIVALENT_TO"
        with self.assertRaisesRegex(ValueError, "schema"):
            self.validate()
        relation["relation"] = "OVERLAPS_WITH"
        reverse = dict(relation, from_topic_id=relation["to_topic_id"], to_topic_id=relation["from_topic_id"])
        self.catalog["relations"].append(reverse)
        with self.assertRaisesRegex(ValueError, "duplicate symmetric relation"):
            self.validate()

    def test_unregistered_ids_unknown_sources_and_invalid_templates_fail(self):
        self.registry["entries"].pop()
        with self.assertRaisesRegex(ValueError, "unregistered or removed"):
            self.validate()
        self.setUp()
        self.catalog["subjects"][0]["source_ids"] = ["UNKNOWN"]
        with self.assertRaisesRegex(ValueError, "unknown source"):
            self.validate()
        self.setUp()
        self.templates["templates"][0]["trunk_slug"] = "nonexistent_trunk"
        with self.assertRaisesRegex(ValueError, "unknown/inactive trunk"):
            self.validate()

    def test_readable_export_matches_the_machine_catalog(self):
        rendered = kc.render(self.catalog)
        self.assertEqual(rendered, (kc.ROOT / "docs/catalogo/CONHECIMENTOS.md").read_text(encoding="utf-8"))
        self.assertNotIn("NOT_APPLIED", rendered)
        for topic in self.catalog["topics"]:
            self.assertIn(f"`{topic['topic_id']}`", rendered)


if __name__ == "__main__":
    unittest.main()
