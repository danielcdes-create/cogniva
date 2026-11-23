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

    def test_media_verification_captions_deprecated_catalog_autoloop_and_fallbacks(self):
        import hashlib
        import io
        import tempfile
        from unittest import mock

        base = mc.load_missions_catalog()
        knowledge = mc.load_knowledge_catalog()

        # 1. require_media_files=True negative cases (missing, wrong size, wrong sha256) + valid synthetic dir
        with tempfile.TemporaryDirectory() as tmp:
            empty_media = Path(tmp) / "empty"
            empty_media.mkdir()
            with self.assertRaisesRegex(mc.MissionContractError, "Arquivo de vídeo ausente"):
                mc.validate_missions_catalog(base, media_dir=empty_media, require_media_files=True)

            synth_cat = copy.deepcopy(base)
            synth_dir = Path(tmp) / "synth"
            synth_dir.mkdir()
            for idx, asset in enumerate(synth_cat["media_assets"]):
                payload = f"mp4-fixture-bytes-{idx}".encode("utf-8")
                (synth_dir / asset["relative_file_name"]).write_bytes(payload)
                asset["size_bytes"] = len(payload)
                asset["sha256"] = hashlib.sha256(payload).hexdigest()

            ok_summary = mc.validate_missions_catalog(synth_cat, media_dir=synth_dir, require_media_files=True)
            self.assertEqual(ok_summary["verified_local_media_files"], len(synth_cat["media_assets"]))

            wrong_size = copy.deepcopy(synth_cat)
            wrong_size["media_assets"][0]["size_bytes"] += 10
            with self.assertRaisesRegex(mc.MissionContractError, "Tamanho divergente"):
                mc.validate_missions_catalog(wrong_size, media_dir=synth_dir, require_media_files=True)

            wrong_sha = copy.deepcopy(synth_cat)
            wrong_sha["media_assets"][0]["sha256"] = "0" * 64
            with self.assertRaisesRegex(mc.MissionContractError, "SHA-256 divergente"):
                mc.validate_missions_catalog(wrong_sha, media_dir=synth_dir, require_media_files=True)

        # 2. Caption timing errors: start_ms >= end_ms, end_ms > max_ms, out-of-order start_ms
        bad_cue_span = copy.deepcopy(base)
        bad_cue_span["media_assets"][0]["captions"][0]["start_ms"] = 5000
        bad_cue_span["media_assets"][0]["captions"][0]["end_ms"] = 5000
        with self.assertRaisesRegex(mc.MissionContractError, "start_ms >= end_ms"):
            mc.validate_missions_catalog(bad_cue_span)

        bad_cue_exceeds = copy.deepcopy(base)
        bad_cue_exceeds["media_assets"][0]["captions"][0]["end_ms"] = 999_999
        with self.assertRaisesRegex(mc.MissionContractError, "excede duração"):
            mc.validate_missions_catalog(bad_cue_exceeds)

        bad_cue_order = copy.deepcopy(base)
        bad_cue_order["media_assets"][1]["captions"][1]["start_ms"] = 100
        with self.assertRaisesRegex(mc.MissionContractError, "ordem cronológica"):
            mc.validate_missions_catalog(bad_cue_order)

        # 3. DEPRECATED catalog entities (trunk, subject, topic) and fallback_subject_ids / fallback_topic_ids
        dep_trunk_k = copy.deepcopy(knowledge)
        for tr in dep_trunk_k["trunks"]:
            if tr["trunk_id"] == "bancaria":
                tr["status"] = "DEPRECATED"
        with self.assertRaisesRegex(mc.MissionContractError, "trunk_id"):
            mc.validate_missions_catalog(base, knowledge_catalog=dep_trunk_k)

        dep_subj_k = copy.deepcopy(knowledge)
        for sb in dep_subj_k["subjects"]:
            if sb["subject_id"] == "MAT_FIN":
                sb["status"] = "DEPRECATED"
        with self.assertRaisesRegex(mc.MissionContractError, "(related_subject_id|fallback_subject_id)"):
            mc.validate_missions_catalog(base, knowledge_catalog=dep_subj_k)

        dep_topic_k = copy.deepcopy(knowledge)
        for tp in dep_topic_k["topics"]:
            if tp["topic_id"] == "MFIN_01_004":
                tp["status"] = "DEPRECATED"
        with self.assertRaisesRegex(mc.MissionContractError, "fallback_topic_id"):
            mc.validate_missions_catalog(base, knowledge_catalog=dep_topic_k)

        bad_fb_subj = copy.deepcopy(base)
        bad_fb_subj["careers"][0]["disciplines"][0]["seasons"][0]["missions"][0]["curriculum"][
            "worked_concepts"
        ][0]["fallback_subject_ids"] = ["NON_EXISTENT_SUBJ"]
        with self.assertRaisesRegex(mc.MissionContractError, "fallback_subject_id"):
            mc.validate_missions_catalog(bad_fb_subj)

        schema_with_fb_topics = mc.load_schema()
        schema_with_fb_topics["$defs"]["worked_concept"]["properties"]["fallback_topic_ids"] = {
            "type": "array",
            "items": {"type": "string"},
        }
        bad_fb_top = copy.deepcopy(base)
        bad_fb_top["careers"][0]["disciplines"][0]["seasons"][0]["missions"][0]["curriculum"][
            "worked_concepts"
        ][0]["fallback_topic_ids"] = ["NOT_IN_BASE_CATALOG"]
        with self.assertRaisesRegex(mc.MissionContractError, "fallback_topic_id"):
            mc.validate_missions_catalog(bad_fb_top, schema=schema_with_fb_topics)

        # 4. Auto-loop next_scene_id == scene_id and branch_scene_id == scene_id
        autoloop = copy.deepcopy(base)
        scene0 = autoloop["careers"][0]["disciplines"][0]["seasons"][0]["missions"][0]["scenes"][0]
        scene0["next_scene_id"] = scene0["scene_id"]
        with self.assertRaisesRegex(mc.MissionContractError, "auto-loop"):
            mc.validate_missions_catalog(autoloop)

        branch_loop = copy.deepcopy(base)
        scene2 = branch_loop["careers"][0]["disciplines"][0]["seasons"][0]["missions"][0]["scenes"][2]
        scene2["interaction"]["options"][0]["branch_scene_id"] = scene2["scene_id"]
        with self.assertRaisesRegex(mc.MissionContractError, "auto-loop"):
            mc.validate_missions_catalog(branch_loop)

        # 5. CLI --check flag enforcement
        with self.assertRaises(SystemExit):
            with mock.patch("sys.stderr", io.StringIO()):
                mc.main([])
        with mock.patch("sys.stdout", io.StringIO()):
            self.assertEqual(mc.main(["--check"]), 0)


if __name__ == "__main__":
    unittest.main()
