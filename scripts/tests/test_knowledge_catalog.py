import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import knowledge_catalog as kc


class KnowledgeCatalogTest(unittest.TestCase):
    def setUp(self):
        self.catalog = kc.read(kc.ROOT / "shared/knowledge-catalog.json")
        self.legacy = kc.read(kc.ROOT / "shared/canonical-catalog.json")
        self.registry = kc.read(kc.ROOT / "shared/knowledge-id-registry.json")
        self.schema = kc.read(kc.ROOT / "shared/knowledge-catalog.schema.json")

    def validate(self):
        return kc.validate(self.catalog, self.legacy, self.registry, self.schema)

    def test_preserves_every_legacy_subject_topic_and_trunk_link(self):
        result = self.validate()
        self.assertEqual(result["legacy_subjects_preserved"], 24)
        self.assertEqual(result["legacy_topics_preserved"], 26)
        self.assertEqual(result["runtime_transition"], "NOT_APPLIED")
        self.assertEqual(result["trunks"], 7)

    def test_duplicate_identity_is_rejected_even_with_different_content(self):
        duplicate = copy.deepcopy(self.catalog["subjects"][0])
        duplicate["label"] = "Outra disciplina"
        self.catalog["subjects"].append(duplicate)
        with self.assertRaisesRegex(ValueError, "duplicate subject_id"):
            self.validate()

    def test_unknown_subject_parent_and_cross_subject_parent_are_rejected(self):
        child = next(t for t in self.catalog["topics"] if t["kind"] == "SUBTOPIC")
        for field, value, message in [("subject_id", "UNKNOWN", "unknown subject"),
                                     ("parent_topic_id", "UNKNOWN", "unknown parent"),
                                     ("parent_topic_id", "PORT_01", "cross-subject parent")]:
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
        self.catalog["subjects"][0]["aliases"].append("  PORTUGUÊS  ".strip())
        with self.assertRaisesRegex(ValueError, "ambiguous alias"):
            self.validate()

    def test_original_name_may_change_only_if_preserved_as_alias(self):
        subject = next(s for s in self.catalog["subjects"] if s["subject_id"] == "PORT")
        subject["label"] = "Português"
        subject["aliases"] = ["Língua Portuguesa"]
        self.validate()
        subject["aliases"] = []
        with self.assertRaisesRegex(ValueError, "legacy subject renamed"):
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

    def test_unregistered_ids_unknown_sources_and_removed_legacy_links_fail(self):
        self.registry["entries"].pop()
        with self.assertRaisesRegex(ValueError, "unregistered or removed"):
            self.validate()
        self.setUp()
        self.catalog["subjects"][0]["source_ids"] = ["UNKNOWN"]
        with self.assertRaisesRegex(ValueError, "unknown source"):
            self.validate()
        self.setUp()
        self.catalog["subjects"][0]["trunk_links"] = [dict(self.catalog["subjects"][0]["trunk_links"][0], trunk_id="fiscal")]
        with self.assertRaisesRegex(ValueError, "legacy trunk link removed"):
            self.validate()

    def test_invalid_shape_and_changed_runtime_are_rejected(self):
        self.catalog["topics"][0]["unexpected"] = True
        with self.assertRaisesRegex(ValueError, "schema"):
            self.validate()
        self.setUp()
        self.legacy["exams"][0]["title"] = "Changed runtime template"
        with self.assertRaisesRegex(ValueError, "runtime catalog changed"):
            self.validate()

    def test_readable_export_matches_the_machine_catalog(self):
        rendered = kc.render(self.catalog)
        self.assertEqual(rendered, (kc.ROOT / "docs/catalogo/CONHECIMENTOS.md").read_text(encoding="utf-8"))
        for topic in self.catalog["topics"]:
            self.assertIn(f"`{topic['topic_id']}`", rendered)


if __name__ == "__main__":
    unittest.main()
