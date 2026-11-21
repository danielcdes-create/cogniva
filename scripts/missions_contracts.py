"""Validate Cogniva Missions declarative contracts, referential integrity, and media digests."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
from typing import Any

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "shared" / "schemas" / "missions.schema.json"
CATALOG_PATH = ROOT / "shared" / "missions-catalog.json"
KNOWLEDGE_CATALOG_PATH = ROOT / "shared" / "knowledge-catalog.json"
DEFAULT_MEDIA_DIR = ROOT / "missoes" / "videos"

CANONICAL_TOPIC_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
WINDOWS_PATH_RE = re.compile(r"([A-Za-z]:\\|\\\\|file://)", re.IGNORECASE)


class MissionContractError(ValueError):
    """Raised when a mission catalog or definition violates Cogniva contracts."""


def load_schema() -> dict[str, Any]:
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


def load_missions_catalog() -> dict[str, Any]:
    return json.loads(CATALOG_PATH.read_text(encoding="utf-8"))


def load_knowledge_catalog() -> dict[str, Any]:
    return json.loads(KNOWLEDGE_CATALOG_PATH.read_text(encoding="utf-8"))


def _scan_for_forbidden_paths(node: Any, path: str = "$") -> None:
    if isinstance(node, str):
        if WINDOWS_PATH_RE.search(node):
            raise MissionContractError(
                f"Caminho local absoluto proibido encontrado em {path}: {node!r}"
            )
    elif isinstance(node, list):
        for idx, item in enumerate(node):
            _scan_for_forbidden_paths(item, f"{path}[{idx}]")
    elif isinstance(node, dict):
        for key, val in node.items():
            _scan_for_forbidden_paths(val, f"{path}.{key}")


def validate_missions_catalog(
    catalog: dict[str, Any],
    *,
    schema: dict[str, Any] | None = None,
    knowledge_catalog: dict[str, Any] | None = None,
    media_dir: Path | None = None,
    require_media_files: bool = False,
) -> dict[str, Any]:
    schema_obj = schema or load_schema()
    Draft202012Validator.check_schema(schema_obj)
    validator = Draft202012Validator(schema_obj)
    errors = sorted(validator.iter_errors(catalog), key=lambda e: list(e.path))
    if errors:
        first = errors[0]
        loc = ".".join(str(p) for p in first.path) or "$"
        raise MissionContractError(f"Violação de schema em {loc}: {first.message}")

    _scan_for_forbidden_paths(catalog)

    knowledge = knowledge_catalog or load_knowledge_catalog()
    valid_trunks = {t["trunk_id"] for t in knowledge.get("trunks", [])}
    valid_subjects = {s["subject_id"] for s in knowledge.get("subjects", [])}
    base_topics = {t["topic_id"] for t in knowledge.get("topics", [])}

    assets_by_id: dict[str, dict[str, Any]] = {}
    for asset in catalog["media_assets"]:
        aid = asset["asset_id"]
        if aid in assets_by_id:
            raise MissionContractError(f"asset_id duplicado: {aid}")
        max_ms = int(math.ceil(asset["duration_seconds"] * 1000.0)) + 500
        prev_start = -1
        for cue in asset["captions"]:
            if cue["start_ms"] >= cue["end_ms"]:
                raise MissionContractError(
                    f"Legenda inválida em {aid} ({cue['cue_id']}): start_ms >= end_ms"
                )
            if cue["end_ms"] > max_ms:
                raise MissionContractError(
                    f"Legenda excede duração do vídeo em {aid} ({cue['cue_id']})"
                )
            if cue["start_ms"] < prev_start:
                raise MissionContractError(
                    f"Legendas fora de ordem cronológica em {aid}"
                )
            prev_start = cue["start_ms"]
        assets_by_id[aid] = asset

    characters_by_id: dict[str, dict[str, Any]] = {}
    for char in catalog["characters"]:
        cid = char["character_id"]
        if cid in characters_by_id:
            raise MissionContractError(f"character_id duplicado: {cid}")
        characters_by_id[cid] = char

    career_ids: set[str] = set()
    discipline_ids: set[str] = set()
    season_ids: set[str] = set()
    mission_ids: set[str] = set()
    total_scenes = 0
    total_interactions = 0

    for career in catalog["careers"]:
        cid = career["career_id"]
        if cid in career_ids:
            raise MissionContractError(f"career_id duplicado: {cid}")
        career_ids.add(cid)
        if career["trunk_id"] not in valid_trunks:
            raise MissionContractError(
                f"trunk_id inexistente no catálogo canônico: {career['trunk_id']}"
            )

        for disc in career["disciplines"]:
            did = disc["discipline_id"]
            if did in discipline_ids:
                raise MissionContractError(f"discipline_id duplicado: {did}")
            discipline_ids.add(did)
            if disc["canonical_subject_id"] not in valid_subjects:
                raise MissionContractError(
                    f"canonical_subject_id inexistente: {disc['canonical_subject_id']}"
                )

            for season in disc["seasons"]:
                sid = season["season_id"]
                if sid in season_ids:
                    raise MissionContractError(f"season_id duplicado: {sid}")
                season_ids.add(sid)

                for mission in season["missions"]:
                    mid = mission["mission_id"]
                    if mid in mission_ids:
                        raise MissionContractError(f"mission_id duplicado: {mid}")
                    mission_ids.add(mid)

                    curr = mission["curriculum"]
                    if curr["canonical_subject_id"] not in valid_subjects:
                        raise MissionContractError(
                            f"Missão {mid}: canonical_subject_id inválido {curr['canonical_subject_id']}"
                        )
                    for rel_subj in curr.get("related_subject_ids", []):
                        if rel_subj not in valid_subjects:
                            raise MissionContractError(
                                f"Missão {mid}: related_subject_id inválido {rel_subj}"
                            )
                    for tid in curr["primary_topic_ids"]:
                        if not CANONICAL_TOPIC_RE.match(tid):
                            raise MissionContractError(
                                f"Missão {mid}: primary_topic_id fora do padrão canônico: {tid}"
                            )
                    for f_tid in curr.get("fallback_topic_ids", []):
                        if f_tid not in base_topics:
                            raise MissionContractError(
                                f"Missão {mid}: fallback_topic_id ausente no catálogo base: {f_tid}"
                            )

                    scenes = mission["scenes"]
                    scene_ids = [s["scene_id"] for s in scenes]
                    if len(scene_ids) != len(set(scene_ids)):
                        raise MissionContractError(f"Missão {mid}: scene_id duplicado")
                    scene_id_set = set(scene_ids)

                    expected_orders = list(range(1, len(scenes) + 1))
                    actual_orders = [s["order"] for s in scenes]
                    if actual_orders != expected_orders:
                        raise MissionContractError(
                            f"Missão {mid}: ordem das cenas deve ser sequencial {expected_orders}, obtido {actual_orders}"
                        )

                    concept_keys: set[str] = set()
                    for concept in curr["worked_concepts"]:
                        ckey = concept["concept_key"]
                        if ckey in concept_keys:
                            raise MissionContractError(
                                f"Missão {mid}: concept_key duplicado {ckey}"
                            )
                        concept_keys.add(ckey)
                        if concept["canonical_subject_id"] not in valid_subjects:
                            raise MissionContractError(
                                f"Conceito {ckey}: canonical_subject_id inválido"
                            )
                        for tid in concept["canonical_topic_ids"]:
                            if not CANONICAL_TOPIC_RE.match(tid):
                                raise MissionContractError(
                                    f"Conceito {ckey}: canonical_topic_id inválido {tid}"
                                )
                        if not any(
                            tid in base_topics for tid in concept["canonical_topic_ids"]
                        ) and not curr.get("fallback_topic_ids"):
                            raise MissionContractError(
                                f"Conceito {ckey}: exige pelo menos um tópico base de fallback"
                            )
                        for r_scene in concept["related_scene_ids"]:
                            if r_scene not in scene_id_set:
                                raise MissionContractError(
                                    f"Conceito {ckey}: related_scene_id inexistente {r_scene}"
                                )

                    interaction_ids: set[str] = set()
                    for idx, scene in enumerate(scenes):
                        total_scenes += 1
                        if scene["asset_id"] not in assets_by_id:
                            raise MissionContractError(
                                f"Cena {scene['scene_id']}: asset_id inexistente {scene['asset_id']}"
                            )
                        if scene["character_id"] not in characters_by_id:
                            raise MissionContractError(
                                f"Cena {scene['scene_id']}: character_id inexistente {scene['character_id']}"
                            )
                        next_sid = scene.get("next_scene_id")
                        if idx < len(scenes) - 1:
                            if not next_sid or next_sid not in scene_id_set:
                                raise MissionContractError(
                                    f"Cena {scene['scene_id']}: next_scene_id obrigatório para cena não-final"
                                )
                        elif next_sid is not None and next_sid not in scene_id_set:
                            raise MissionContractError(
                                f"Cena {scene['scene_id']}: next_scene_id inválido {next_sid}"
                            )

                        interaction = scene.get("interaction")
                        if scene["requires_decision_to_advance"] and interaction is None:
                            raise MissionContractError(
                                f"Cena {scene['scene_id']} exige decisão mas não declara interaction"
                            )
                        if interaction is not None:
                            total_interactions += 1
                            iid = interaction["interaction_id"]
                            if iid in interaction_ids:
                                raise MissionContractError(
                                    f"Missão {mid}: interaction_id duplicado {iid}"
                                )
                            interaction_ids.add(iid)
                            if scene["auto_advance_on_video_end"]:
                                raise MissionContractError(
                                    f"Cena {scene['scene_id']}: cena interativa não pode ter auto_advance_on_video_end=true"
                                )
                            opt_ids = [o["option_id"] for o in interaction["options"]]
                            if len(opt_ids) != len(set(opt_ids)):
                                raise MissionContractError(
                                    f"Interação {iid}: option_id duplicado"
                                )
                            if interaction["expected_option_id"] not in opt_ids:
                                raise MissionContractError(
                                    f"Interação {iid}: expected_option_id ausente nas opções"
                                )
                            for opt in interaction["options"]:
                                branch = opt.get("branch_scene_id")
                                if branch is not None and branch not in scene_id_set:
                                    raise MissionContractError(
                                        f"Interação {iid}: branch_scene_id inválido {branch}"
                                    )
                            for ckey in interaction["revisit_concept_keys"]:
                                if ckey not in concept_keys:
                                    raise MissionContractError(
                                        f"Interação {iid}: revisit_concept_key inexistente {ckey}"
                                    )

    verified_media = 0
    effective_media_dir = media_dir or DEFAULT_MEDIA_DIR
    if require_media_files or effective_media_dir.is_dir():
        for asset in catalog["media_assets"]:
            fpath = effective_media_dir / asset["relative_file_name"]
            if not fpath.is_file():
                if require_media_files:
                    raise MissionContractError(
                        f"Arquivo de vídeo ausente para {asset['asset_id']}: {fpath.name}"
                    )
                continue
            raw = fpath.read_bytes()
            if len(raw) != asset["size_bytes"]:
                raise MissionContractError(
                    f"Tamanho divergente em {asset['asset_id']}: esperado {asset['size_bytes']}, obtido {len(raw)}"
                )
            digest = hashlib.sha256(raw).hexdigest()
            if digest != asset["sha256"]:
                raise MissionContractError(
                    f"SHA-256 divergente em {asset['asset_id']}: esperado {asset['sha256']}, obtido {digest}"
                )
            verified_media += 1

    return {
        "catalog_id": catalog["catalog_id"],
        "catalog_version": catalog["catalog_version"],
        "careers": len(career_ids),
        "disciplines": len(discipline_ids),
        "seasons": len(season_ids),
        "missions": len(mission_ids),
        "scenes": total_scenes,
        "interactions": total_interactions,
        "media_assets": len(assets_by_id),
        "verified_local_media_files": verified_media,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate missions catalog")
    parser.add_argument(
        "--verify-media",
        action="store_true",
        help="Require physical MP4 files to exist and match SHA-256",
    )
    args = parser.parse_args()
    summary = validate_missions_catalog(
        load_missions_catalog(),
        require_media_files=args.verify_media,
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
