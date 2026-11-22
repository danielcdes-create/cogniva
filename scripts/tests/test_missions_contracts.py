import copy
from pathlib import Path
import sys
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import missions_contracts as mc  # noqa: E402


class MissionsContractsTest(unittest.TestCase):
    def test_shipped_missions_catalog_and_five_videos_pass_strict_validation(self):
        catalog = mc.load_missions_catalog()
        has_local_videos = mc.DEFAULT_MEDIA_DIR.is_dir() and any(
            mc.DEFAULT_MEDIA_DIR.glob("*.mp4")
        )
        summary = mc.validate_missions_catalog(
            catalog, require_media_files=has_local_videos
        )
        self.assertEqual(summary["catalog_id"], "cogniva-missions")
        self.assertEqual(summary["missions"], 1)
        self.assertEqual(summary["scenes"], 5)
        self.assertEqual(summary["interactions"], 2)
        self.assertEqual(summary["media_assets"], 5)
        if has_local_videos:
            self.assertEqual(summary["verified_local_media_files"], 5)

    def test_pilot_mission_preserves_epistemological_separation_and_curriculum_links(self):
        catalog = mc.load_missions_catalog()
        mission = catalog["careers"][0]["disciplines"][0]["seasons"][0]["missions"][0]
        self.assertEqual(mission["mission_id"], "bcb-ef-t1-m01-choque-dos-juros")
        self.assertEqual(mission["title"], "O Choque dos Juros")
        policy = mission["curriculum"]["learning_evidence_policy"]
        self.assertFalse(policy["video_completion_grants_mastery"])
        self.assertFalse(policy["narrative_decisions_grant_mastery"])
        self.assertTrue(policy["requires_independent_human_questions"])
        self.assertEqual(policy["min_human_questions_for_mastery"], 15)

        scene3 = mission["scenes"][2]
        self.assertEqual(scene3["interaction"]["expected_option_id"], "B")
        self.assertFalse(scene3["interaction"]["is_editorial_question"])
        self.assertFalse(scene3["interaction"]["contributes_to_formal_mastery"])
        self.assertEqual(len(scene3["interaction"]["options"]), 3)

        scene5 = mission["scenes"][4]
        self.assertEqual(scene5["interaction"]["expected_option_id"], "B")
        self.assertEqual(len(scene5["interaction"]["options"]), 2)

    def test_rejects_windows_paths_formal_mastery_smuggling_and_broken_references(self):
        base = mc.load_missions_catalog()

        with_windows_path = copy.deepcopy(base)
        with_windows_path["media_assets"][0]["relative_file_name"] = r"C:\fixtures\video.mp4"
        with self.assertRaises(mc.MissionContractError):
            mc.validate_missions_catalog(with_windows_path)

        smuggled_mastery = copy.deepcopy(base)
        smuggled_mastery["careers"][0]["disciplines"][0]["seasons"][0]["missions"][0][
            "scenes"
        ][2]["interaction"]["contributes_to_formal_mastery"] = True
        with self.assertRaises(mc.MissionContractError):
            mc.validate_missions_catalog(smuggled_mastery)

        invented_subject = copy.deepcopy(base)
        invented_subject["careers"][0]["disciplines"][0]["canonical_subject_id"] = "INVENTED_SUBJ"
        with self.assertRaises(mc.MissionContractError):
            mc.validate_missions_catalog(invented_subject)

    def test_supports_adding_second_mission_declaratively_without_schema_changes(self):
        extended = copy.deepcopy(mc.load_missions_catalog())
        season = extended["careers"][0]["disciplines"][0]["seasons"][0]
        second = copy.deepcopy(season["missions"][0])
        second["mission_id"] = "bcb-ef-t1-m02-curva-a-termo"
        second["mission_number"] = 2
        second["display_code"] = "Missão 02"
        second["title"] = "A Inclinação da Curva"
        second["full_title"] = "Missão 02: A Inclinação da Curva"
        season["missions"].append(second)
        summary = mc.validate_missions_catalog(extended)
        self.assertEqual(summary["missions"], 2)
        self.assertEqual(summary["scenes"], 10)


if __name__ == "__main__":
    unittest.main()
