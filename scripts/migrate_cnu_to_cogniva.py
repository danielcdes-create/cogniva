#!/usr/bin/env python3
"""Idempotent, strictly read-only on CNU migration of contests, questions and catalogs into Cogniva 2.0 (`cogniva`).

- Reads `todasQuestoes` (and optionally legacy contests in `exam_templates` / `user_exams` / `concursos`)
  from source database (`CNU` by default) with ZERO writes to source.
- Supports selective contest + question migration via `--contest-filter <regex>` (e.g. Banco Central / BACEN / BCB).
- Normalizes every legacy V1 or early V2 question into a strict `schema_version = 2` document
  with JCS SHA-256 `content_revision`, `LEGACY_IMPORT` provenance, preserved pedagogical caches,
  and NFKD-normalized discipline labels against `shared/knowledge-catalog.json`.
- Validates every normalized document against `scripts/question_contracts.py` BEFORE writing.
- Seeds `knowledge_catalog_versions` and `exam_templates` (including migrated contests) idempotently into `cogniva`.
"""
from __future__ import annotations

from collections import Counter
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import uuid

import rfc8785

sys.path.insert(0, str(Path(__file__).resolve().parent))
import knowledge_catalog as kc
import question_contracts as qc

ROOT = kc.ROOT
MIGRATION_TIMESTAMP = "2026-11-12T00:00:00Z"
CATALOG_MIGRATION_UPDATED_AT = "2026-11-13"
UPPER_ID_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
CNU_MIGRATION_SOURCE = {
    "source_id": "CNU_CATALOG_MIGRATION",
    "kind": "LEGACY_INTERNAL",
    "reference": "CNU.knowledge_catalog_activations",
    "note": "Extensões editoriais preservadas em leitura-única da base legada CNU para Cogniva 2.0.",
}

VALID_EXAM_STATUSES = {
    "RUMOR_PREVISTO",
    "AUTORIZADO",
    "BANCA_DEFINIDA",
    "EDITAL_PUBLICADO",
    "PROVA_REALIZADA",
    "ARQUIVADO",
    "EXCLUIDO",
}
VALID_SUBJECT_CATEGORIES = {
    "CONHECIMENTOS_GERAIS",
    "CONHECIMENTOS_ESPECIFICOS",
    "DISCURSIVA_ESTUDO_DE_CASO",
    "OUTROS",
}
VALID_RELEVANCE_TIERS = {"CORE", "COMPLEMENTARY", "SPECIFIC"}
VALID_TOPIC_STATUSES = {
    "PRE_EDITAL_BASE",
    "OFICIAL_CONFIRMADO",
    "OFICIAL_NOVO",
    "ARQUIVADO",
}


def rfc8785_sha256(value: dict) -> str:
    return hashlib.sha256(rfc8785.dumps(value)).hexdigest()


def deterministic_uuid(seed: str) -> str:
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest[:16], version=4))


def _unique_upper_id(candidate: str, used_ids: set[str]) -> str:
    cleaned = re.sub(r"[^A-Z0-9_]+", "_", candidate.upper()).strip("_")
    if not cleaned or not cleaned[0].isalpha():
        cleaned = f"ID_{cleaned}" if cleaned else "ID_CUSTOM"
    cleaned = cleaned[:80]
    if cleaned not in used_ids:
        return cleaned
    idx = 2
    while f"{cleaned}_{idx}" in used_ids:
        idx += 1
    return f"{cleaned}_{idx}"


def _is_valid_custom_id(val: str) -> bool:
    if not isinstance(val, str) or not val.startswith("custom-"):
        return False
    try:
        uuid.UUID(val[len("custom-") :])
        return True
    except Exception:
        return False


def merge_source_catalog_extensions(
    base_catalog: dict,
    base_registry: dict,
    source_catalogs: list[dict],
    updated_at: str = CATALOG_MIGRATION_UPDATED_AT,
) -> tuple[
    dict,
    dict,
    dict[str, str],
    dict[tuple[str, str], tuple[str, str]],
    dict[str, tuple[str, str]],
    bool,
]:
    """Merge valid subjects/topics/aliases from legacy CNU catalogs into `base_catalog` + 1 version.

    Guarantees full compliance with `shared/knowledge-catalog.schema.json` and
    `backend-rust/src/services/catalog_editor.rs::validate_against`.
    Returns `(effective_catalog, effective_registry, subject_id_map, topic_pair_map, topic_flat_map, extended)`.
    """
    merged = copy.deepcopy(base_catalog)
    sources_by_id: dict[str, dict] = {s["source_id"]: s for s in merged["sources"]}
    trunks_by_id: dict[str, dict] = {t["trunk_id"]: t for t in merged["trunks"]}
    subjects_by_id: dict[str, dict] = {s["subject_id"]: s for s in merged["subjects"]}
    topics_by_id: dict[str, dict] = {t["topic_id"]: t for t in merged["topics"]}
    all_entity_ids: set[str] = set(trunks_by_id) | set(subjects_by_id) | set(topics_by_id)

    alias_owners: dict[tuple, str] = {}
    for tid, row in trunks_by_id.items():
        for nm in [row["label"], *row["aliases"]]:
            alias_owners[(("TRUNK",), kc.normalized(nm))] = tid
    for sid, row in subjects_by_id.items():
        for nm in [row["label"], *row["aliases"]]:
            alias_owners[(("SUBJECT",), kc.normalized(nm))] = sid
    for tid, row in topics_by_id.items():
        scope = ("TOPIC", row["subject_id"], row["parent_topic_id"])
        for nm in [row["label"], *row["aliases"]]:
            alias_owners[(scope, kc.normalized(nm))] = tid

    candidate_sources: dict[str, dict] = {
        CNU_MIGRATION_SOURCE["source_id"]: copy.deepcopy(CNU_MIGRATION_SOURCE)
    }
    for sc in source_catalogs:
        if not isinstance(sc, dict):
            continue
        for src in sc.get("sources") or []:
            if not isinstance(src, dict):
                continue
            src_id = str(src.get("source_id") or "").strip()
            kind = str(src.get("kind") or "").strip()
            ref = str(src.get("reference") or "").strip()[:1000]
            note = str(src.get("note") or "").strip()[:1000]
            if (
                UPPER_ID_RE.fullmatch(src_id)
                and src_id not in sources_by_id
                and kind in ("LEGACY_INTERNAL", "EDITORIAL_PROPOSAL", "REFERENCE_PORTAL")
                and ref
                and note
            ):
                candidate_sources.setdefault(
                    src_id,
                    {"source_id": src_id, "kind": kind, "reference": ref, "note": note},
                )

    referenced_extra_sources: set[str] = set()

    def _resolve_source_ids(raw_sids) -> list[str]:
        valid: list[str] = []
        if isinstance(raw_sids, list):
            for x in raw_sids:
                sx = str(x or "").strip()
                if sx in sources_by_id and sx not in valid:
                    valid.append(sx)
                elif sx in candidate_sources and sx not in valid:
                    valid.append(sx)
                    referenced_extra_sources.add(sx)
        if not valid:
            valid.append(CNU_MIGRATION_SOURCE["source_id"])
            referenced_extra_sources.add(CNU_MIGRATION_SOURCE["source_id"])
        return valid

    mutated_existing_ids: set[str] = set()
    changed = False
    subject_id_map: dict[str, str] = {}
    topic_pair_map: dict[tuple[str, str], tuple[str, str]] = {}
    topic_flat_map: dict[str, tuple[str, str]] = {}

    def _merge_extra_aliases(target_row: dict, target_id: str, scope: tuple, extra_names: list[str]):
        nonlocal changed
        for raw_nm in extra_names:
            if not isinstance(raw_nm, str):
                continue
            nm = raw_nm.strip()[:1000]
            norm = kc.normalized(nm)
            if not nm or not norm or (scope, norm) in alias_owners:
                continue
            target_row["aliases"].append(nm)
            alias_owners[(scope, norm)] = target_id
            if target_id not in mutated_existing_ids:
                target_row["revision"] = int(target_row.get("revision") or 1) + 1
                mutated_existing_ids.add(target_id)
            changed = True

    default_trunk_id = "bancaria" if "bancaria" in trunks_by_id else next(iter(trunks_by_id))

    # 1. Merge subjects
    for sc in source_catalogs:
        if not isinstance(sc, dict):
            continue
        for raw_s in sc.get("subjects") or []:
            if not isinstance(raw_s, dict):
                continue
            raw_sid = str(raw_s.get("subject_id") or "").strip()
            label = str(raw_s.get("label") or "").strip()[:1000]
            norm_label = kc.normalized(label)
            if not UPPER_ID_RE.fullmatch(raw_sid) or not label or not norm_label:
                continue
            raw_aliases = [a for a in (raw_s.get("aliases") or []) if isinstance(a, str)]
            subj_scope = ("SUBJECT",)

            if raw_sid in subjects_by_id:
                subject_id_map[raw_sid] = raw_sid
                _merge_extra_aliases(subjects_by_id[raw_sid], raw_sid, subj_scope, [label, *raw_aliases])
                continue

            if (subj_scope, norm_label) in alias_owners:
                target_sid = alias_owners[(subj_scope, norm_label)]
                subject_id_map[raw_sid] = target_sid
                _merge_extra_aliases(subjects_by_id[target_sid], target_sid, subj_scope, [label, *raw_aliases])
                continue

            new_sid = raw_sid if raw_sid not in all_entity_ids else _unique_upper_id(f"SUBJ_{raw_sid}", all_entity_ids)
            clean_aliases: list[str] = []
            seen_norms = {norm_label}
            for a in raw_aliases:
                ac = a.strip()[:1000]
                an = kc.normalized(ac)
                if ac and an and an not in seen_norms and (subj_scope, an) not in alias_owners:
                    clean_aliases.append(ac)
                    seen_norms.add(an)

            seen_trunks: set[str] = set()
            trunk_links: list[dict] = []
            for lk in raw_s.get("trunk_links") or []:
                if not isinstance(lk, dict):
                    continue
                tid = str(lk.get("trunk_id") or "").strip()
                if tid not in trunks_by_id or tid in seen_trunks:
                    continue
                seen_trunks.add(tid)
                role = lk.get("role") if lk.get("role") in ("FOUNDATIONAL", "RELATED") else "FOUNDATIONAL"
                dw = lk.get("default_weight")
                if isinstance(dw, (int, float)) and not isinstance(dw, bool) and math.isfinite(dw) and 0.1 <= float(dw) <= 10.0:
                    weight = float(dw)
                else:
                    weight = 2.0 if role == "FOUNDATIONAL" else 1.0
                basis = lk.get("basis") if lk.get("basis") in ("LEGACY_TEMPLATE", "EDITORIAL_SUGGESTION") else "EDITORIAL_SUGGESTION"
                note = (str(lk.get("note") or "").strip()[:1000] or "Relação preservada da curadoria editorial legada.")
                trunk_links.append(
                    {
                        "trunk_id": tid,
                        "role": role,
                        "default_weight": weight,
                        "basis": basis,
                        "note": note,
                    }
                )
            if not trunk_links:
                trunk_links.append(
                    {
                        "trunk_id": default_trunk_id,
                        "role": "FOUNDATIONAL",
                        "default_weight": 2.0,
                        "basis": "EDITORIAL_SUGGESTION",
                        "note": "Disciplina preservada da curadoria editorial legada.",
                    }
                )

            scope_note = (
                str(raw_s.get("scope_note") or "").strip()[:1000]
                or "Disciplina preservada da curadoria editorial para concursos."
            )
            status = "DEPRECATED" if raw_s.get("status") == "DEPRECATED" else "ACTIVE"
            rev_raw = raw_s.get("revision")
            rev = rev_raw if type(rev_raw) is int and rev_raw >= 1 else 1
            new_subj = {
                "subject_id": new_sid,
                "label": label,
                "aliases": clean_aliases,
                "scope_note": scope_note,
                "status": status,
                "revision": rev,
                "source_ids": _resolve_source_ids(raw_s.get("source_ids")),
                "trunk_links": trunk_links,
            }
            merged["subjects"].append(new_subj)
            subjects_by_id[new_sid] = new_subj
            all_entity_ids.add(new_sid)
            mutated_existing_ids.add(new_sid)  # newly created row already has valid revision
            alias_owners[(subj_scope, norm_label)] = new_sid
            for ac in clean_aliases:
                alias_owners[(subj_scope, kc.normalized(ac))] = new_sid
            subject_id_map[raw_sid] = new_sid
            changed = True

    # 2. Merge topics in two passes: TOPIC first, then SUBTOPIC
    for target_kind in ("TOPIC", "SUBTOPIC"):
        for sc in source_catalogs:
            if not isinstance(sc, dict):
                continue
            for raw_t in sc.get("topics") or []:
                if not isinstance(raw_t, dict):
                    continue
                raw_parent = raw_t.get("parent_topic_id")
                inferred_kind = raw_t.get("kind") if raw_t.get("kind") in ("TOPIC", "SUBTOPIC") else (
                    "TOPIC" if raw_parent is None else "SUBTOPIC"
                )
                if inferred_kind != target_kind:
                    continue
                if target_kind == "TOPIC" and raw_parent is not None:
                    continue
                if target_kind == "SUBTOPIC" and not (isinstance(raw_parent, str) and raw_parent.strip()):
                    continue

                raw_tid = str(raw_t.get("topic_id") or "").strip()
                raw_sid = str(raw_t.get("subject_id") or "").strip()
                label = str(raw_t.get("label") or "").strip()[:1000]
                norm_label = kc.normalized(label)
                if not UPPER_ID_RE.fullmatch(raw_tid) or not label or not norm_label:
                    continue

                target_sid = subject_id_map.get(raw_sid) or (raw_sid if raw_sid in subjects_by_id else None)
                if not target_sid:
                    continue

                if target_kind == "TOPIC":
                    target_parent = None
                else:
                    raw_p = str(raw_parent).strip()
                    p_pair = (
                        topic_pair_map.get((raw_sid, raw_p))
                        or topic_flat_map.get(raw_p)
                        or ((target_sid, raw_p) if raw_p in topics_by_id else None)
                    )
                    if not p_pair:
                        continue
                    parent_sid, parent_tid = p_pair
                    if (
                        parent_tid not in topics_by_id
                        or parent_sid != target_sid
                        or topics_by_id[parent_tid]["subject_id"] != target_sid
                        or topics_by_id[parent_tid]["kind"] != "TOPIC"
                    ):
                        continue
                    target_parent = parent_tid

                top_scope = ("TOPIC", target_sid, target_parent)
                raw_aliases = [a for a in (raw_t.get("aliases") or []) if isinstance(a, str)]

                if (
                    raw_tid in topics_by_id
                    and topics_by_id[raw_tid]["subject_id"] == target_sid
                    and topics_by_id[raw_tid]["parent_topic_id"] == target_parent
                    and topics_by_id[raw_tid]["kind"] == target_kind
                ):
                    topic_pair_map[(raw_sid, raw_tid)] = (target_sid, raw_tid)
                    topic_flat_map.setdefault(raw_tid, (target_sid, raw_tid))
                    _merge_extra_aliases(topics_by_id[raw_tid], raw_tid, top_scope, [label, *raw_aliases])
                    continue

                if (top_scope, norm_label) in alias_owners:
                    existing_tid = alias_owners[(top_scope, norm_label)]
                    topic_pair_map[(raw_sid, raw_tid)] = (target_sid, existing_tid)
                    topic_flat_map.setdefault(raw_tid, (target_sid, existing_tid))
                    _merge_extra_aliases(topics_by_id[existing_tid], existing_tid, top_scope, [label, *raw_aliases])
                    continue

                new_tid = raw_tid if raw_tid not in all_entity_ids else _unique_upper_id(f"{target_sid}_{raw_tid}", all_entity_ids)
                clean_aliases = []
                seen_norms = {norm_label}
                for a in raw_aliases:
                    ac = a.strip()[:1000]
                    an = kc.normalized(ac)
                    if ac and an and an not in seen_norms and (top_scope, an) not in alias_owners:
                        clean_aliases.append(ac)
                        seen_norms.add(an)

                status = "DEPRECATED" if raw_t.get("status") == "DEPRECATED" else "ACTIVE"
                rev_raw = raw_t.get("revision")
                rev = rev_raw if type(rev_raw) is int and rev_raw >= 1 else 1
                new_top = {
                    "topic_id": new_tid,
                    "subject_id": target_sid,
                    "parent_topic_id": target_parent,
                    "kind": target_kind,
                    "label": label,
                    "aliases": clean_aliases,
                    "status": status,
                    "revision": rev,
                    "source_ids": _resolve_source_ids(raw_t.get("source_ids")),
                }
                merged["topics"].append(new_top)
                topics_by_id[new_tid] = new_top
                all_entity_ids.add(new_tid)
                mutated_existing_ids.add(new_tid)
                alias_owners[(top_scope, norm_label)] = new_tid
                for ac in clean_aliases:
                    alias_owners[(top_scope, kc.normalized(ac))] = new_tid
                topic_pair_map[(raw_sid, raw_tid)] = (target_sid, new_tid)
                topic_flat_map[raw_tid] = (target_sid, new_tid)
                changed = True

    if not changed:
        return (
            copy.deepcopy(base_catalog),
            copy.deepcopy(base_registry),
            subject_id_map,
            topic_pair_map,
            topic_flat_map,
            False,
        )

    # 3. Ensure every SUBJECT has >= 1 TOPIC and every TOPIC has >= 1 SUBTOPIC
    subjects_with_topics = {t["subject_id"] for t in merged["topics"] if t["kind"] == "TOPIC"}
    for sid in list(subjects_by_id):
        if sid not in subjects_with_topics:
            gen_tid = _unique_upper_id(f"{sid}_TOPICOS_GERAIS", all_entity_ids)
            gen_label = "Tópicos gerais da disciplina"
            gen_top = {
                "topic_id": gen_tid,
                "subject_id": sid,
                "parent_topic_id": None,
                "kind": "TOPIC",
                "label": gen_label,
                "aliases": [],
                "status": "ACTIVE",
                "revision": 1,
                "source_ids": _resolve_source_ids([]),
            }
            merged["topics"].append(gen_top)
            topics_by_id[gen_tid] = gen_top
            all_entity_ids.add(gen_tid)
            alias_owners[(("TOPIC", sid, None), kc.normalized(gen_label))] = gen_tid

    topics_with_subtopics = {
        t["parent_topic_id"] for t in merged["topics"] if t["parent_topic_id"] is not None
    }
    for tid, top in list(topics_by_id.items()):
        if top["kind"] == "TOPIC" and tid not in topics_with_subtopics:
            sid = top["subject_id"]
            gen_sub_id = _unique_upper_id(f"{tid}_GERAL", all_entity_ids)
            gen_sub_label = "Conceitos e aplicações gerais"
            gen_sub = {
                "topic_id": gen_sub_id,
                "subject_id": sid,
                "parent_topic_id": tid,
                "kind": "SUBTOPIC",
                "label": gen_sub_label,
                "aliases": [],
                "status": top["status"],
                "revision": 1,
                "source_ids": _resolve_source_ids(top.get("source_ids")),
            }
            merged["topics"].append(gen_sub)
            topics_by_id[gen_sub_id] = gen_sub
            all_entity_ids.add(gen_sub_id)
            alias_owners[(("TOPIC", sid, tid), kc.normalized(gen_sub_label))] = gen_sub_id

    # Attach referenced extra sources preserving existing baseline sources first
    for extra_sid in sorted(referenced_extra_sources):
        if extra_sid not in sources_by_id and extra_sid in candidate_sources:
            src_doc = candidate_sources[extra_sid]
            merged["sources"].append(src_doc)
            sources_by_id[extra_sid] = src_doc

    merged["catalog_version"] = int(base_catalog["catalog_version"]) + 1
    merged["updated_at"] = updated_at
    merged["status"] = "ACTIVE"
    merged["review_status"] = "APPROVED"

    # Build registry identically to backend-rust/src/services/catalog_editor.rs (lines 251-268)
    base_pins = {e["id"]: copy.deepcopy(e) for e in base_registry["entries"]}
    entries: list[dict] = []
    for entity, rows in (
        ("TRUNK", trunks_by_id),
        ("SUBJECT", subjects_by_id),
        ("TOPIC", topics_by_id),
    ):
        for eid, row in rows.items():
            if eid in base_pins:
                entries.append(base_pins[eid])
            else:
                entries.append(
                    {
                        "id": eid,
                        "entity": entity,
                        "subject_id": row.get("subject_id"),
                        "parent_topic_id": row.get("parent_topic_id"),
                        "initial_label": row["label"],
                    }
                )
    entries.sort(key=lambda e: e["id"])
    merged_registry = copy.deepcopy(base_registry)
    merged_registry["entries"] = entries
    merged_registry["registry_version"] = int(base_registry["registry_version"]) + 1

    kc.validate(merged, merged_registry, kc.read(ROOT / "shared/knowledge-catalog.schema.json"))
    return merged, merged_registry, subject_id_map, topic_pair_map, topic_flat_map, True


def subject_lookup_table(catalog: dict) -> dict[str, dict]:
    table: dict[str, dict] = {}
    for subj in catalog["subjects"]:
        if subj["status"] != "ACTIVE":
            continue
        table[kc.normalized(subj["subject_id"])] = subj
        table[kc.normalized(subj["label"])] = subj
        for alias in subj.get("aliases", []):
            table[kc.normalized(alias)] = subj
    return table


def topic_lookup_by_subject(catalog: dict) -> dict[str, dict[str, dict]]:
    by_subj: dict[str, dict[str, dict]] = {}
    for top in catalog["topics"]:
        if top["status"] != "ACTIVE":
            continue
        sid = top["subject_id"]
        bucket = by_subj.setdefault(sid, {})
        bucket[top["topic_id"]] = top
        bucket[kc.normalized(top["topic_id"])] = top
        bucket[kc.normalized(top["label"])] = top
        for alias in top.get("aliases", []):
            bucket[kc.normalized(alias)] = top
    return by_subj


def _oid_hex(raw_id) -> str:
    if isinstance(raw_id, dict) and "$oid" in raw_id:
        return str(raw_id["$oid"]).lower()
    text = str(raw_id).strip().lower()
    if len(text) == 24 and all(c in "0123456789abcdef" for c in text):
        return text
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:24]


def _resolve_objective_answers(raw: dict) -> tuple[str, list[str], list[int], list[str]]:
    raw_answers = raw.get("answers")
    if not isinstance(raw_answers, list) or not raw_answers:
        raise ValueError("missing answers list")
    answers = [str(a).strip() for a in raw_answers]
    if any(not a for a in answers):
        raise ValueError("blank answer option")
    if len(set(answers)) != len(answers):
        raise ValueError("duplicate answer options")

    raw_indices = raw.get("correct_answer_indices")
    raw_texts = raw.get("correct_answers")
    mapped: list[int] = []
    if isinstance(raw_texts, list) and raw_texts:
        for t in raw_texts:
            t_clean = str(t).strip()
            hits = [i for i, ans in enumerate(answers) if ans == t_clean]
            if len(hits) != 1:
                raise ValueError("ambiguous textual correct_answers")
            mapped.append(hits[0])

    indices: list[int] = []
    if isinstance(raw_indices, list) and raw_indices:
        if not all(type(idx) is int and 0 <= idx < len(answers) for idx in raw_indices):
            raise ValueError("invalid correct_answer_indices")
        indices = sorted(set(raw_indices))
        if mapped and sorted(set(mapped)) != indices:
            raise ValueError("contradictory correct_answers and correct_answer_indices")
    else:
        indices = sorted(set(mapped))

    if len(indices) != 1:
        raise ValueError("expected exactly 1 correct answer index")

    correct_idx = indices[0]

    explicit_type = raw.get("question_type")
    norm_pair = [kc.normalized(a) for a in answers]
    tf_true = {"certo", "verdadeiro", "c", "v"}
    tf_false = {"errado", "falso", "e", "f"}
    if len(answers) == 2 and (
        explicit_type == "TRUE_FALSE"
        or (norm_pair[0] in tf_true and norm_pair[1] in tf_false)
        or (norm_pair[0] in tf_false and norm_pair[1] in tf_true)
    ):
        is_correct_true = norm_pair[correct_idx] in tf_true or (
            explicit_type == "TRUE_FALSE" and answers == ["Certo", "Errado"] and correct_idx == 0
        )
        norm_answers = ["Certo", "Errado"]
        norm_idx = 0 if is_correct_true else 1
        return "TRUE_FALSE", norm_answers, [norm_idx], [norm_answers[norm_idx]]

    if len(answers) not in (4, 5):
        raise ValueError(f"unsupported multiple-choice option count: {len(answers)}")
    return "MULTIPLE_CHOICE", answers, [correct_idx], [answers[correct_idx]]


def _clean_optional_str(val, max_len: int = 20000) -> str | None:
    if not isinstance(val, str):
        return None
    cleaned = val.strip()
    if not cleaned:
        return None
    return cleaned[:max_len]


def _sanitize_exam_id(raw_id: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_-]+", "_", str(raw_id or "").strip()).strip("_")
    if not cleaned:
        cleaned = "CONCURSO_" + deterministic_uuid(str(raw_id))[:8].upper()
    return cleaned[:100]


def _valid_iso_date(val) -> bool:
    if not isinstance(val, str) or len(val) != 10:
        return False
    try:
        datetime.strptime(val, "%Y-%m-%d")
        return True
    except ValueError:
        return False


def normalize_contest_to_v2_template(
    raw_exam: dict,
    catalog: dict,
    source_catalog_labels: dict[str, str] | None = None,
    subject_id_map: dict[str, str] | None = None,
    topic_pair_map: dict[tuple[str, str], tuple[str, str]] | None = None,
    topic_flat_map: dict[str, tuple[str, str]] | None = None,
) -> dict:
    """Normalize a legacy CNU contest (`user_exams` / `exam_templates`) into a valid Cogniva 2.0 global template."""
    if not isinstance(raw_exam, dict):
        raise ValueError("exam document must be a dict")

    source_labels = source_catalog_labels or {}
    sid_map = subject_id_map or {}
    tpair_map = topic_pair_map or {}
    tflat_map = topic_flat_map or {}
    subj_lookup = subject_lookup_table(catalog)
    active_subjects = {s["subject_id"]: s for s in catalog["subjects"] if s["status"] == "ACTIVE"}
    topics_by_subj = topic_lookup_by_subject(catalog)
    active_trunks = {t["trunk_id"]: t for t in catalog["trunks"] if t["status"] == "ACTIVE"}

    raw_id = raw_exam.get("exam_id") or raw_exam.get("_id") or raw_exam.get("id") or raw_exam.get("short_name")
    if isinstance(raw_id, dict) and "$oid" in raw_id:
        raw_id = raw_exam.get("short_name") or raw_id["$oid"]
    exam_id = _sanitize_exam_id(str(raw_id or ""))

    title = (_clean_optional_str(raw_exam.get("title"), 300) or _clean_optional_str(raw_exam.get("short_name"), 300) or exam_id)
    short_name = (_clean_optional_str(raw_exam.get("short_name"), 120) or title[:120])
    institution = _clean_optional_str(raw_exam.get("institution"), 200) or short_name[:200]
    role = _clean_optional_str(raw_exam.get("role"), 200) or "Cargo Geral"

    raw_trunk = str(raw_exam.get("trunk_slug") or "").strip().lower()
    combined_norm = kc.normalized(f"{exam_id} {title} {short_name} {institution} {raw_trunk}")
    if raw_trunk in active_trunks:
        trunk_slug = raw_trunk
    elif any(tok in combined_norm for tok in ("banco central", "bacen", "bcb", "banca", "bancaria", "bndes", "financeir")) and "bancaria" in active_trunks:
        trunk_slug = "bancaria"
    else:
        trunk_slug = next(iter(active_trunks))

    raw_status = str(raw_exam.get("status") or "RUMOR_PREVISTO").strip().upper()
    if raw_status == "POS_PROVA":
        raw_status = "PROVA_REALIZADA"
    if raw_status not in VALID_EXAM_STATUSES or raw_status == "EXCLUIDO":
        raw_status = "RUMOR_PREVISTO"

    raw_boards = raw_exam.get("exam_boards")
    exam_boards = []
    if isinstance(raw_boards, list):
        for b in raw_boards:
            bc = _clean_optional_str(b, 120)
            if bc:
                exam_boards.append(bc)

    td = raw_exam.get("target_date")
    target_date: dict = {"type": "UNDEFINED"}
    if isinstance(td, dict):
        td_type = str(td.get("type") or "").upper()
        if td_type == "EXACT_DATE" and _valid_iso_date(td.get("iso_date")):
            target_date = {"type": "EXACT_DATE", "iso_date": td["iso_date"]}
        elif (
            td_type == "ESTIMATED_WINDOW"
            and _clean_optional_str(td.get("label"), 120)
            and _valid_iso_date(td.get("estimated_sort_date"))
        ):
            target_date = {
                "type": "ESTIMATED_WINDOW",
                "label": _clean_optional_str(td.get("label"), 120),
                "estimated_sort_date": td["estimated_sort_date"],
            }

    cutoff = raw_exam.get("historical_cutoff_score")
    if not isinstance(cutoff, (int, float)) or not math.isfinite(cutoff):
        cutoff = 0.0
    cutoff = float(min(100.0, max(0.0, cutoff)))

    penalty = raw_exam.get("wrong_answer_penalty")
    if not isinstance(penalty, (int, float)) or not math.isfinite(penalty):
        penalty = 0.0
    penalty = float(min(1.0, max(0.0, penalty)))

    prio = raw_exam.get("ladder_priority")
    if not isinstance(prio, int) or isinstance(prio, bool):
        prio = 5
    prio = min(100, max(1, prio))

    revision = raw_exam.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        revision = 1

    seen_local_sids: set[str] = set()
    seen_local_tids: set[str] = set()
    norm_subjects: list[dict] = []

    raw_subjects = raw_exam.get("subjects") if isinstance(raw_exam.get("subjects"), list) else []
    for s_idx, raw_s in enumerate(raw_subjects[:100]):
        if not isinstance(raw_s, dict):
            continue
        orig_csid = str(raw_s.get("canonical_subject_id") or "").strip()
        raw_csid = sid_map.get(orig_csid, orig_csid)
        disp_name = (
            _clean_optional_str(raw_s.get("display_name"), 300)
            or _clean_optional_str(source_labels.get(orig_csid), 300)
            or _clean_optional_str(source_labels.get(raw_csid), 300)
            or _clean_optional_str(orig_csid, 300)
            or f"Disciplina {s_idx + 1}"
        )

        if raw_csid in active_subjects:
            csid = raw_csid
        elif kc.normalized(raw_csid) in subj_lookup:
            csid = subj_lookup[kc.normalized(raw_csid)]["subject_id"]
        elif kc.normalized(disp_name) in subj_lookup:
            csid = subj_lookup[kc.normalized(disp_name)]["subject_id"]
        elif _is_valid_custom_id(raw_csid):
            csid = raw_csid
        else:
            csid = "custom-" + deterministic_uuid(f"cogniva-v2-subject:{exam_id}:{raw_csid or disp_name}")

        local_sid = _clean_optional_str(raw_s.get("local_subject_id"), 120) or f"{csid}-{s_idx}"
        if local_sid in seen_local_sids:
            local_sid = f"{local_sid}-{s_idx}"
        seen_local_sids.add(local_sid)

        cat = str(raw_s.get("category") or "CONHECIMENTOS_ESPECIFICOS").strip().upper()
        if cat == "DISCURSIVA":
            cat = "DISCURSIVA_ESTUDO_DE_CASO"
        if cat not in VALID_SUBJECT_CATEGORIES:
            cat = "CONHECIMENTOS_ESPECIFICOS"

        tier = str(raw_s.get("relevance_tier") or "CORE").strip().upper()
        if tier not in VALID_RELEVANCE_TIERS:
            tier = "COMPLEMENTARY"

        weight = raw_s.get("weight")
        if not isinstance(weight, (int, float)) or not math.isfinite(weight) or weight < 0.01:
            weight = 1.0
        weight = float(min(100.0, weight))

        q_count = raw_s.get("question_count")
        if q_count is not None:
            if not isinstance(q_count, int) or isinstance(q_count, bool) or q_count < 0:
                q_count = None
            else:
                q_count = min(500, q_count)

        stage = (_clean_optional_str(raw_s.get("stage"), 120) or "")

        subj_topic_lookup = topics_by_subj.get(csid, {})
        seen_ctids_in_subject: set[str] = set()
        norm_topics: list[dict] = []
        raw_topics = raw_s.get("topics") if isinstance(raw_s.get("topics"), list) else []
        for t_idx, raw_t in enumerate(raw_topics[:1000]):
            if not isinstance(raw_t, dict):
                continue
            orig_ctid = str(raw_t.get("canonical_topic_id") or "").strip()
            raw_ctid = orig_ctid
            if (orig_csid, orig_ctid) in tpair_map and tpair_map[(orig_csid, orig_ctid)][0] == csid:
                raw_ctid = tpair_map[(orig_csid, orig_ctid)][1]
            elif orig_ctid in tflat_map and tflat_map[orig_ctid][0] == csid:
                raw_ctid = tflat_map[orig_ctid][1]

            t_title = (
                _clean_optional_str(raw_t.get("display_title"), 1000)
                or _clean_optional_str(source_labels.get(orig_ctid), 1000)
                or _clean_optional_str(source_labels.get(raw_ctid), 1000)
                or _clean_optional_str(orig_ctid, 1000)
                or f"Tópico {t_idx + 1}"
            )

            if csid in active_subjects and raw_ctid in subj_topic_lookup:
                ctid = subj_topic_lookup[raw_ctid]["topic_id"]
            elif csid in active_subjects and kc.normalized(raw_ctid) in subj_topic_lookup:
                ctid = subj_topic_lookup[kc.normalized(raw_ctid)]["topic_id"]
            elif csid in active_subjects and kc.normalized(t_title) in subj_topic_lookup:
                ctid = subj_topic_lookup[kc.normalized(t_title)]["topic_id"]
            elif _is_valid_custom_id(raw_ctid):
                ctid = raw_ctid
            else:
                ctid = "custom-" + deterministic_uuid(
                    f"cogniva-v2-topic:{exam_id}:{csid}:{raw_ctid or t_title}:{t_idx}"
                )

            if ctid in seen_ctids_in_subject:
                ctid = "custom-" + deterministic_uuid(
                    f"cogniva-v2-topic-dedup:{exam_id}:{csid}:{ctid}:{t_idx}"
                )
            seen_ctids_in_subject.add(ctid)

            local_tid = _clean_optional_str(raw_t.get("local_topic_id"), 120) or f"{ctid}-{t_idx}"
            if local_tid in seen_local_tids:
                local_tid = f"{local_tid}-{s_idx}-{t_idx}"
            seen_local_tids.add(local_tid)

            diff_st = str(raw_t.get("diff_status") or "PRE_EDITAL_BASE").strip().upper()
            if diff_st not in VALID_TOPIC_STATUSES:
                diff_st = "PRE_EDITAL_BASE"

            norm_topics.append(
                {
                    "local_topic_id": local_tid,
                    "canonical_topic_id": ctid,
                    "canonical_subject_id": csid,
                    "display_title": t_title,
                    "diff_status": diff_st,
                }
            )

        norm_subjects.append(
            {
                "stage": stage,
                "local_subject_id": local_sid,
                "canonical_subject_id": csid,
                "display_name": disp_name,
                "category": cat,
                "weight": weight,
                "question_count": q_count,
                "relevance_tier": tier,
                "topics": norm_topics,
            }
        )

    # Enforce sum(question_count) <= 500
    total_q = sum(s["question_count"] or 0 for s in norm_subjects)
    if total_q > 500 and total_q > 0:
        scale = 500.0 / float(total_q)
        for s in norm_subjects:
            if s["question_count"] is not None:
                s["question_count"] = max(1, int(math.floor(s["question_count"] * scale)))

    edital_diff = raw_exam.get("edital_diff_summary")
    if not isinstance(edital_diff, dict) or not all(
        k in edital_diff for k in ("confirmed_count", "new_count", "archived_count", "published_at_iso")
    ):
        edital_diff = None

    return {
        "_id": exam_id,
        "exam_id": exam_id,
        "revision": revision,
        "owner_user_id": None,
        "title": title,
        "short_name": short_name,
        "institution": institution,
        "role": role,
        "trunk_slug": trunk_slug,
        "status": raw_status,
        "exam_boards": exam_boards,
        "target_date": target_date,
        "historical_cutoff_score": cutoff,
        "is_active_in_ladder": False,
        "ladder_priority": prio,
        "subjects": norm_subjects,
        "edital_diff_summary": edital_diff,
        "sync_status": "SYNCED",
        "wrong_answer_penalty": penalty,
    }


def normalize_question_to_v2(
    raw_doc: dict,
    catalog: dict,
    recorded_at: str = MIGRATION_TIMESTAMP,
    source_catalog_labels: dict[str, str] | None = None,
    default_contest_tag: str | None = None,
    subject_id_map: dict[str, str] | None = None,
    topic_pair_map: dict[tuple[str, str], tuple[str, str]] | None = None,
    topic_flat_map: dict[str, tuple[str, str]] | None = None,
) -> dict:
    """Pure, deterministic transformation of a raw CNU V1 or V2 question into Cogniva 2.0 V2 format."""
    if not isinstance(raw_doc, dict):
        raise ValueError("document must be a dict")

    oid = _oid_hex(raw_doc.get("_id", ""))
    question_text = _clean_optional_str(raw_doc.get("question"), 20000)
    if not question_text:
        raise ValueError("empty question text")

    source_labels = source_catalog_labels or {}
    sid_map = subject_id_map or {}
    tpair_map = topic_pair_map or {}
    tflat_map = topic_flat_map or {}
    subj_lookup = subject_lookup_table(catalog)
    active_subjects = {s["subject_id"]: s for s in catalog["subjects"] if s["status"] == "ACTIVE"}
    active_topics = {
        (t["subject_id"], t["topic_id"]): t
        for t in catalog["topics"]
        if t["status"] == "ACTIVE" and t["subject_id"] in active_subjects
    }
    catalog_ver = int(catalog["catalog_version"])

    # If already schema_version == 2, upgrade catalog_version & links safely and validate strictly
    if raw_doc.get("schema_version") == 2:
        doc = copy.deepcopy(raw_doc)
        doc["_id"] = {"$oid": oid}
        doc["question"] = question_text

        if isinstance(doc.get("classification"), dict):
            cls = doc["classification"]
            raw_links = cls.get("links") if isinstance(cls.get("links"), list) else []
            remapped_links: list[dict] = []
            seen_link_topics: set[str] = set()
            for raw_lk in raw_links:
                if not isinstance(raw_lk, dict):
                    continue
                lk = copy.deepcopy(raw_lk)
                orig_sid = str(lk.get("subject_id") or "").strip()
                orig_tid = str(lk.get("topic_id") or "").strip()
                if (orig_sid, orig_tid) in tpair_map:
                    lk["subject_id"], lk["topic_id"] = tpair_map[(orig_sid, orig_tid)]
                elif orig_tid in tflat_map:
                    lk["subject_id"], lk["topic_id"] = tflat_map[orig_tid]
                elif orig_sid in sid_map:
                    lk["subject_id"] = sid_map[orig_sid]
                if lk.get("topic_id") in seen_link_topics:
                    continue
                if isinstance(lk.get("topic_id"), str):
                    seen_link_topics.add(lk["topic_id"])
                remapped_links.append(lk)

            links_valid = bool(remapped_links) and all(
                (lk.get("subject_id"), lk.get("topic_id")) in active_topics
                and qc.human_review(lk.get("provenance"))
                for lk in remapped_links
            )
            if cls.get("state") in ("CONFIRMED", "PARTIAL") and links_valid:
                cls["links"] = remapped_links
                cls["catalog_version"] = catalog_ver
                cls["revision"] = max(1, int(cls.get("revision") or 1))
                primaries = [
                    lk
                    for lk in remapped_links
                    if lk.get("role") == "PRIMARY" and lk.get("coverage") == "DIRECT"
                ]
                if len(primaries) == 1:
                    doc["canonical_subject_id"] = primaries[0]["subject_id"]
                    doc["canonical_topic_id"] = primaries[0]["topic_id"]
                else:
                    doc["canonical_subject_id"] = None
                    doc["canonical_topic_id"] = None
            else:
                doc["classification"] = {
                    "revision": 0,
                    "catalog_version": catalog_ver,
                    "state": "UNCLASSIFIED",
                    "links": [],
                }
                doc["canonical_subject_id"] = None
                doc["canonical_topic_id"] = None

        raw_disc = _clean_optional_str(doc.get("disciplina"), 300)
        if doc.get("canonical_subject_id") in active_subjects and (
            not raw_disc or kc.normalized(raw_disc) not in subj_lookup
        ):
            doc["disciplina"] = active_subjects[doc["canonical_subject_id"]]["label"]
        elif raw_disc:
            matched_subj = subj_lookup.get(kc.normalized(raw_disc))
            doc["disciplina"] = matched_subj["label"] if matched_subj else raw_disc
        elif isinstance(doc.get("classification"), dict):
            inferred = None
            for lk in doc["classification"].get("links") or []:
                if isinstance(lk, dict) and lk.get("subject_id") in active_subjects:
                    inferred = active_subjects[lk["subject_id"]]["label"]
                    break
            doc["disciplina"] = inferred or "Geral"
        else:
            doc["disciplina"] = "Geral"

        if default_contest_tag and not doc.get("concursos"):
            doc["concursos"] = default_contest_tag

        doc["content_revision"] = qc.content_revision(doc)
        doc["content_hash_version"] = "question-v2-jcs-sha256"

        # Ensure validity matches recomputed content_revision or gracefully resets to UNKNOWN
        if isinstance(doc.get("validity"), dict):
            val = doc["validity"]
            if val.get("status") in ("CURRENT", "OBSOLETE"):
                decision = val.get("decision")
                has_evidence = val.get("basis") == "TIMELESS" or bool(
                    isinstance(decision, dict) and decision.get("evidence")
                )
                has_due = val.get("status") != "CURRENT" or val.get("basis") != "LEGAL" or bool(
                    val.get("review_due_on")
                )
                if (
                    val.get("revision", 0) > 0
                    and qc.human_review(decision)
                    and val.get("evaluated_content_revision") == doc["content_revision"]
                    and has_evidence
                    and has_due
                ):
                    pass
                else:
                    doc["validity"] = {
                        "revision": 0,
                        "status": "UNKNOWN",
                        "basis": val.get("basis") if val.get("basis") in ("TIMELESS", "FACTUAL", "LEGAL", "TECHNICAL") else "LEGAL",
                        "reference_date": None,
                        "review_due_on": None,
                        "reason_codes": [],
                        "evaluated_content_revision": None,
                        "decision": None,
                        "scope": "CURRENT_KNOWLEDGE",
                        "context_date": None,
                    }

        qc.validate_question(doc, catalog)
        return doc

    q_type, answers, correct_indices, correct_answers = _resolve_objective_answers(raw_doc)
    grading = {
        "strategy": "EXACT_OPTION",
        "max_score": 100,
        "pass_score": 60,
        "expected_responses": [],
        "rubric": None,
        "engine": None,
        "confirmation_policy": "AUTOMATIC_EXACT",
        "policy_version": "objective-exact/1",
    }

    orig_csid = _clean_optional_str(raw_doc.get("canonical_subject_id"), 300)
    raw_csid = sid_map.get(orig_csid, orig_csid) if orig_csid else None
    raw_disc = (
        _clean_optional_str(raw_doc.get("disciplina"), 300)
        or (active_subjects[raw_csid]["label"] if raw_csid and raw_csid in active_subjects else None)
        or (source_labels.get(orig_csid) if orig_csid else None)
        or raw_csid
        or "Geral"
    )
    matched_subj = subj_lookup.get(kc.normalized(raw_disc)) or (
        subj_lookup.get(kc.normalized(raw_csid)) if raw_csid else None
    )
    normalized_disciplina = matched_subj["label"] if matched_subj else raw_disc

    exam_year = raw_doc.get("exam_year")
    if not (type(exam_year) is int and 1900 <= exam_year <= 2200):
        exam_year = None

    source_str = _clean_optional_str(raw_doc.get("source"), 300)
    level_str = _clean_optional_str(raw_doc.get("level"), 300)
    inst_str = _clean_optional_str(raw_doc.get("institution"), 300)

    origins = [
        {
            "origin_id": deterministic_uuid(f"cogniva-v2-origin:{oid}"),
            "kind": "LEGACY_IMPORT",
            "verification": "PENDING",
            "source_exam_code": None,
            "institution": inst_str,
            "organizer": source_str,
            "exam_year": exam_year,
            "question_number": None,
            "source_reference": None,
            "provenance": {
                "actor": {"type": "SYSTEM", "id": "cogniva-2.0-migrator"},
                "method": "LEGACY_IMPORT",
                "rationale": "Questão importada de modo somente-leitura do acervo legado para curadoria editorial V2.",
                "recorded_at": recorded_at,
                "evidence": [],
                "model": None,
                "prompt_id": None,
                "prompt_version": None,
                "source_suggestion_id": None,
            },
        }
    ]

    legal_val = raw_doc.get("legal_validity") if isinstance(raw_doc.get("legal_validity"), dict) else {}
    legacy_status = str(legal_val.get("status") or "ACTIVE").upper()
    if legacy_status in ("ACTIVE", "VIGENTE"):
        val_status = "UNKNOWN"
        reason_codes = []
    else:
        val_status = "UNDER_REVIEW"
        reason_codes = ["REVIEW_REQUESTED"]

    validity = {
        "revision": 0,
        "status": val_status,
        "basis": "LEGAL",
        "reference_date": None,
        "review_due_on": None,
        "reason_codes": reason_codes,
        "evaluated_content_revision": None,
        "decision": None,
        "scope": "CURRENT_KNOWLEDGE",
        "context_date": None,
    }

    classification = {
        "revision": 0,
        "catalog_version": catalog_ver,
        "state": "UNCLASSIFIED",
        "links": [],
    }

    hints_raw = raw_doc.get("contextualHint")
    hints = []
    if isinstance(hints_raw, list):
        for h in hints_raw[:3]:
            cleaned = _clean_optional_str(h, 20000)
            if cleaned is not None:
                hints.append(cleaned)

    concursos_raw = raw_doc.get("concursos")
    if isinstance(concursos_raw, str) and concursos_raw.strip():
        concursos = concursos_raw.strip()
    elif isinstance(concursos_raw, list) and all(isinstance(x, str) for x in concursos_raw) and concursos_raw:
        concursos = concursos_raw
    else:
        concursos = default_contest_tag

    normalized_doc = {
        "_id": {"$oid": oid},
        "schema_version": 2,
        "revision": 1,
        "content_revision": "",
        "content_hash_version": "question-v2-jcs-sha256",
        "status": "ARCHIVED" if raw_doc.get("status") == "ARCHIVED" else "ACTIVE",
        "question": question_text,
        "question_type": q_type,
        "answers": answers,
        "correct_answer_indices": correct_indices,
        "correct_answers": correct_answers,
        "origins": origins,
        "classification": classification,
        "validity": validity,
        "grading": grading,
        "disciplina": normalized_disciplina,
        "canonical_subject_id": None,
        "canonical_topic_id": None,
        "source": source_str,
        "exam_year": exam_year,
        "level": level_str,
        "concursos": concursos,
        "explanation": _clean_optional_str(raw_doc.get("explanation"), 20000),
        "drSincero": _clean_optional_str(raw_doc.get("drSincero"), 20000),
        "ajudaInfantil": _clean_optional_str(raw_doc.get("ajudaInfantil"), 20000),
        "explicacaoInfantil": _clean_optional_str(raw_doc.get("explicacaoInfantil"), 20000),
        "explanationExtra": _clean_optional_str(raw_doc.get("explanationExtra"), 20000),
        "mnemonico": _clean_optional_str(raw_doc.get("mnemonico"), 20000),
        "plausibilityMarkdown": _clean_optional_str(raw_doc.get("plausibilityMarkdown"), 20000),
        "contextualHint": hints,
    }
    normalized_doc["content_revision"] = qc.content_revision(normalized_doc)
    qc.validate_question(normalized_doc, catalog)
    return normalized_doc


def migrate_batch(
    raw_docs: list[dict],
    catalog: dict,
    source_catalog_labels: dict[str, str] | None = None,
    default_contest_tag: str | None = None,
    subject_id_map: dict[str, str] | None = None,
    topic_pair_map: dict[tuple[str, str], tuple[str, str]] | None = None,
    topic_flat_map: dict[str, tuple[str, str]] | None = None,
) -> tuple[list[dict], dict]:
    """Normalize a batch of raw documents and return `(valid_v2_docs, stats)` without PII/content."""
    subj_lookup = subject_lookup_table(catalog)
    migrated: list[dict] = []
    skipped: list[dict] = []
    mapped_subjects = 0
    unmapped_subjects = 0
    confirmed_classified = 0

    for raw in raw_docs:
        oid = _oid_hex(raw.get("_id", "")) if isinstance(raw, dict) else "unknown"
        try:
            v2 = normalize_question_to_v2(
                raw,
                catalog,
                source_catalog_labels=source_catalog_labels,
                default_contest_tag=default_contest_tag,
                subject_id_map=subject_id_map,
                topic_pair_map=topic_pair_map,
                topic_flat_map=topic_flat_map,
            )
            if kc.normalized(v2.get("disciplina", "")) in subj_lookup:
                mapped_subjects += 1
            else:
                unmapped_subjects += 1
            if v2.get("classification", {}).get("state") in ("CONFIRMED", "PARTIAL"):
                confirmed_classified += 1
            migrated.append(v2)
        except Exception as exc:
            skipped.append({"id": oid, "reason": str(exc)})

    stats = {
        "source_documents_read": len(raw_docs),
        "normalized_v2_valid": len(migrated),
        "confirmed_classified_count": confirmed_classified,
        "mapped_discipline_count": mapped_subjects,
        "unmapped_discipline_count": unmapped_subjects,
        "skipped_invalid_count": len(skipped),
        "skipped_invalid": skipped[:50],
    }
    return migrated, stats


def _extract_question_topic_and_subject_tokens(raw_q: dict) -> tuple[set[str], set[str], set[str]]:
    """Return `(topic_ids, subject_ids, normalized_disciplina_names)` referenced on a raw question."""
    tids: set[str] = set()
    sids: set[str] = set()
    discs: set[str] = set()

    if isinstance(raw_q.get("canonical_topic_id"), str) and raw_q["canonical_topic_id"].strip():
        tids.add(raw_q["canonical_topic_id"].strip())
    if isinstance(raw_q.get("syllabus_topic_ids"), list):
        for t in raw_q["syllabus_topic_ids"]:
            if isinstance(t, str) and t.strip():
                tids.add(t.strip())
    if isinstance(raw_q.get("canonical_subject_id"), str) and raw_q["canonical_subject_id"].strip():
        sids.add(raw_q["canonical_subject_id"].strip())
    if isinstance(raw_q.get("classification"), dict):
        for lk in raw_q["classification"].get("links") or []:
            if isinstance(lk, dict):
                if isinstance(lk.get("topic_id"), str) and lk["topic_id"].strip():
                    tids.add(lk["topic_id"].strip())
                if isinstance(lk.get("subject_id"), str) and lk["subject_id"].strip():
                    sids.add(lk["subject_id"].strip())
    if isinstance(raw_q.get("disciplina"), str) and raw_q["disciplina"].strip():
        discs.add(kc.normalized(raw_q["disciplina"]))
    return tids, sids, discs


def _question_matches_contest_direct(raw_q: dict, pattern: re.Pattern, matched_contest_tokens: set[str]) -> bool:
    candidates: list[str] = []
    concursos = raw_q.get("concursos")
    if isinstance(concursos, str):
        candidates.append(concursos)
    elif isinstance(concursos, list):
        candidates.extend(str(x) for x in concursos if isinstance(x, str))

    for key in ("institution", "source", "common_trunk_id"):
        val = raw_q.get(key)
        if isinstance(val, str) and val.strip():
            candidates.append(val)

    origins = raw_q.get("origins")
    if isinstance(origins, list):
        for org in origins:
            if isinstance(org, dict):
                for key in ("institution", "source_exam_code", "source_reference", "organizer"):
                    val = org.get(key)
                    if isinstance(val, str) and val.strip():
                        candidates.append(val)

    for text in candidates:
        if pattern.search(text):
            return True
        norm = kc.normalized(text)
        if norm and norm in matched_contest_tokens:
            return True
    return False


def filter_contests_and_questions(
    raw_contests_by_coll: dict[str, list[dict]],
    raw_questions: list[dict],
    catalog: dict,
    contest_filter: str,
    include_contest_subjects: bool = False,
    source_catalog_labels: dict[str, str] | None = None,
    subject_id_map: dict[str, str] | None = None,
    topic_pair_map: dict[tuple[str, str], tuple[str, str]] | None = None,
    topic_flat_map: dict[str, tuple[str, str]] | None = None,
) -> tuple[list[dict], list[dict], dict]:
    """Select and normalize contests matching `contest_filter` plus their associated questions."""
    pattern = re.compile(contest_filter, re.IGNORECASE)
    source_labels = source_catalog_labels or {}

    contest_inventory: list[dict] = []
    matched_raw_contests: list[tuple[int, dict]] = []
    unmatched_raw_contests: list[dict] = []

    coll_priority = {"exam_templates": 2, "concursos": 1, "user_exams": 0}
    for coll_name, docs in raw_contests_by_coll.items():
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            eid = str(doc.get("exam_id") or doc.get("_id") or doc.get("id") or "")
            title = str(doc.get("title") or "")
            short_name = str(doc.get("short_name") or "")
            institution = str(doc.get("institution") or "")
            role = str(doc.get("role") or "")
            status = str(doc.get("status") or "")
            haystack = " | ".join([eid, title, short_name, institution, role])
            is_match = bool(pattern.search(haystack))
            subjects = doc.get("subjects") if isinstance(doc.get("subjects"), list) else []
            contest_inventory.append(
                {
                    "collection": coll_name,
                    "exam_id": eid[:100],
                    "title": title[:160],
                    "short_name": short_name[:120],
                    "institution": institution[:120],
                    "role": role[:120],
                    "trunk_slug": str(doc.get("trunk_slug") or ""),
                    "status": status,
                    "revision": doc.get("revision", 1),
                    "subject_count": len(subjects),
                    "subject_names": [
                        str(s.get("display_name") or s.get("canonical_subject_id") or "")[:80]
                        for s in subjects
                        if isinstance(s, dict)
                    ],
                    "matched_filter": is_match,
                }
            )
            if is_match:
                matched_raw_contests.append((coll_priority.get(coll_name, 0), doc))
            else:
                unmatched_raw_contests.append(doc)

    best_by_id: dict[str, tuple[tuple[int, int, int], dict, dict]] = {}
    for c_prio, raw_c in matched_raw_contests:
        norm_tpl = normalize_contest_to_v2_template(
            raw_c,
            catalog,
            source_labels,
            subject_id_map=subject_id_map,
            topic_pair_map=topic_pair_map,
            topic_flat_map=topic_flat_map,
        )
        not_deleted = 0 if str(raw_c.get("status") or "").upper() == "EXCLUIDO" else 1
        rev = int(raw_c.get("revision") or 1) if isinstance(raw_c.get("revision"), int) else 1
        rank = (not_deleted, c_prio, rev)
        tid = norm_tpl["_id"]
        if tid not in best_by_id or rank > best_by_id[tid][0]:
            best_by_id[tid] = (rank, raw_c, norm_tpl)

    matched_templates = [entry[2] for entry in best_by_id.values()]
    matched_raw_selected = [entry[1] for entry in best_by_id.values()]

    matched_contest_tokens: set[str] = set()
    matched_topic_ids: set[str] = set()
    matched_subject_ids: set[str] = set()
    matched_subject_names: set[str] = set()

    for raw_c, norm_c in zip(matched_raw_selected, matched_templates):
        for field in ("_id", "exam_id", "short_name", "title", "institution"):
            v = norm_c.get(field) or raw_c.get(field)
            if isinstance(v, str) and len(kc.normalized(v)) >= 3:
                matched_contest_tokens.add(kc.normalized(v))
        for container in (raw_c, norm_c):
            for s in container.get("subjects") or []:
                if not isinstance(s, dict):
                    continue
                if isinstance(s.get("canonical_subject_id"), str) and s["canonical_subject_id"]:
                    matched_subject_ids.add(s["canonical_subject_id"])
                if isinstance(s.get("display_name"), str) and s["display_name"]:
                    matched_subject_names.add(kc.normalized(s["display_name"]))
                for t in s.get("topics") or []:
                    if isinstance(t, dict) and isinstance(t.get("canonical_topic_id"), str) and t["canonical_topic_id"]:
                        matched_topic_ids.add(t["canonical_topic_id"])

    other_topic_ids: set[str] = set()
    for other_c in unmatched_raw_contests:
        for s in other_c.get("subjects") or []:
            if isinstance(s, dict):
                for t in s.get("topics") or []:
                    if isinstance(t, dict) and isinstance(t.get("canonical_topic_id"), str):
                        other_topic_ids.add(t["canonical_topic_id"])
    exclusive_topic_ids = matched_topic_ids - other_topic_ids

    concursos_counter: Counter[str] = Counter()
    inst_counter: Counter[str] = Counter()
    source_counter: Counter[str] = Counter()
    disc_counter: Counter[str] = Counter()
    schema_counter: Counter[str] = Counter()

    selected_questions: list[dict] = []
    direct_count = 0
    exclusive_topic_count = 0
    subject_match_count = 0

    for q in raw_questions:
        if not isinstance(q, dict):
            continue
        schema_counter[str(q.get("schema_version") or 1)] += 1
        c_val = q.get("concursos")
        if isinstance(c_val, str) and c_val.strip():
            concursos_counter[c_val.strip()] += 1
        elif isinstance(c_val, list):
            for x in c_val:
                if isinstance(x, str) and x.strip():
                    concursos_counter[x.strip()] += 1
        else:
            concursos_counter["<none>"] += 1

        if isinstance(q.get("source"), str) and q["source"].strip():
            source_counter[q["source"].strip()] += 1
        if isinstance(q.get("disciplina"), str) and q["disciplina"].strip():
            disc_counter[q["disciplina"].strip()] += 1
        if isinstance(q.get("origins"), list):
            for org in q["origins"]:
                if isinstance(org, dict) and isinstance(org.get("institution"), str) and org["institution"].strip():
                    inst_counter[org["institution"].strip()] += 1

        is_direct = _question_matches_contest_direct(q, pattern, matched_contest_tokens)
        q_tids, q_sids, q_discs = _extract_question_topic_and_subject_tokens(q)
        is_excl_topic = bool(q_tids & exclusive_topic_ids)
        is_subj = bool((q_tids & matched_topic_ids) or (q_sids & matched_subject_ids) or (q_discs & matched_subject_names))

        if is_direct:
            direct_count += 1
        elif is_excl_topic:
            exclusive_topic_count += 1
        if is_subj:
            subject_match_count += 1

        if is_direct or is_excl_topic or (include_contest_subjects and is_subj):
            selected_questions.append(q)

    diagnostics = {
        "contest_filter": contest_filter,
        "include_contest_subjects": include_contest_subjects,
        "source_contests_inventory": contest_inventory,
        "matched_templates_normalized": [
            {
                "_id": t["_id"],
                "title": t["title"],
                "short_name": t["short_name"],
                "institution": t["institution"],
                "role": t["role"],
                "trunk_slug": t["trunk_slug"],
                "status": t["status"],
                "subject_count": len(t["subjects"]),
                "custom_subject_count": sum(
                    1 for s in t["subjects"] if str(s["canonical_subject_id"]).startswith("custom-")
                ),
                "custom_topic_count": sum(
                    1
                    for s in t["subjects"]
                    for tp in s["topics"]
                    if str(tp["canonical_topic_id"]).startswith("custom-")
                ),
                "subjects": [
                    {
                        "canonical_subject_id": s["canonical_subject_id"],
                        "display_name": s["display_name"],
                        "question_count": s["question_count"],
                        "topic_count": len(s["topics"]),
                    }
                    for s in t["subjects"]
                ],
            }
            for t in matched_templates
        ],
        "source_questions_total_in_db": len(raw_questions),
        "matched_questions_direct": direct_count,
        "matched_questions_exclusive_topics": exclusive_topic_count,
        "matched_questions_any_contest_subject": subject_match_count,
        "selected_questions_for_migration": len(selected_questions),
        "distinct_concursos_top25": dict(concursos_counter.most_common(25)),
        "distinct_origins_institutions_top25": dict(inst_counter.most_common(25)),
        "distinct_sources_top25": dict(source_counter.most_common(25)),
        "distinct_disciplinas_top30": dict(disc_counter.most_common(30)),
        "source_question_schema_versions": dict(schema_counter),
    }
    return matched_templates, selected_questions, diagnostics


def _to_mongo_doc(v2_extjson: dict) -> dict:
    from bson import ObjectId

    doc = copy.deepcopy(v2_extjson)
    doc["_id"] = ObjectId(doc["_id"]["$oid"])
    return doc


def _read_source_catalogs_and_labels(src_db) -> tuple[list[dict], dict[str, str]]:
    """Read committed catalog snapshots and ID -> label mappings from legacy CNU database."""
    from bson import json_util

    catalogs: list[dict] = []
    labels: dict[str, str] = {}
    existing_colls = set(src_db.list_collection_names())
    for coll_name, payload_key in (
        ("knowledge_catalog_versions", "payload"),
        ("knowledge_catalog_activations", "catalog"),
    ):
        if coll_name not in existing_colls:
            continue
        rows = [
            json.loads(json_util.dumps(d, json_options=json_util.RELAXED_JSON_OPTIONS))
            for d in src_db.get_collection(coll_name).find({})
        ]
        rows.sort(key=lambda r: int(r.get("version") or 0))
        for doc in rows:
            cat = doc.get(payload_key)
            if isinstance(cat, dict):
                catalogs.append(cat)
                for s in cat.get("subjects") or []:
                    if isinstance(s, dict) and s.get("subject_id") and s.get("label"):
                        labels[str(s["subject_id"])] = str(s["label"])
                for t in cat.get("topics") or []:
                    if isinstance(t, dict) and t.get("topic_id") and t.get("label"):
                        labels[str(t["topic_id"])] = str(t["label"])
    return catalogs, labels


def _read_target_active_catalog(
    tgt_db,
    bundled_catalog: dict,
    bundled_registry: dict,
) -> tuple[dict, dict, str | None]:
    """Return `(active_catalog, active_registry, head_activation_id)` currently active in `cogniva`."""
    from bson import json_util

    existing_colls = set(tgt_db.list_collection_names())
    if "knowledge_catalog_head" not in existing_colls:
        return copy.deepcopy(bundled_catalog), copy.deepcopy(bundled_registry), None
    head = tgt_db.get_collection("knowledge_catalog_head").find_one({"_id": "cogniva-knowledge"})
    if not head:
        return copy.deepcopy(bundled_catalog), copy.deepcopy(bundled_registry), None
    act_id = head.get("activation_id")
    if not isinstance(act_id, str) or not act_id:
        return copy.deepcopy(bundled_catalog), copy.deepcopy(bundled_registry), None
    event_raw = tgt_db.get_collection("knowledge_catalog_activations").find_one({"_id": act_id})
    if not event_raw:
        return copy.deepcopy(bundled_catalog), copy.deepcopy(bundled_registry), None
    event = json.loads(json_util.dumps(event_raw, json_options=json_util.RELAXED_JSON_OPTIONS))
    cat = event.get("catalog")
    reg = event.get("registry")
    if isinstance(cat, dict) and isinstance(reg, dict):
        return cat, reg, act_id
    return copy.deepcopy(bundled_catalog), copy.deepcopy(bundled_registry), None


def _cast_catalog_doc_int64_fields(doc: dict, keys: tuple[str, ...], int64_cls) -> dict:
    """Return a shallow copy of `doc` with specified integer metadata fields cast to `bson.Int64`."""
    patched = copy.copy(doc)
    for k in keys:
        val = patched.get(k)
        if isinstance(val, int) and not isinstance(val, bool):
            patched[k] = int64_cls(int(val))
    return patched


def _ensure_catalog_bson_int64(tgt_db) -> dict:
    """Ensure `knowledge_catalog_head`, `knowledge_catalog_activations`, and `knowledge_catalog_versions` store i64 fields as BSON Int64."""
    from bson import Int64

    spec = (
        ("knowledge_catalog_head", ("version",)),
        ("knowledge_catalog_activations", ("version", "actor_user_id", "draft_revision")),
        ("knowledge_catalog_versions", ("version",)),
    )
    existing_colls = set(tgt_db.list_collection_names())
    normalized_counts: dict[str, int] = {}
    remaining_int32 = 0

    for coll_name, keys in spec:
        if coll_name not in existing_colls:
            normalized_counts[coll_name] = 0
            continue
        coll = tgt_db.get_collection(coll_name)
        count = 0
        for raw_doc in coll.find({}):
            patched = _cast_catalog_doc_int64_fields(raw_doc, keys, Int64)
            coll.replace_one({"_id": raw_doc["_id"]}, patched)
            count += 1
        normalized_counts[coll_name] = count
        remaining_int32 += coll.count_documents({"version": {"$type": "int"}})

    if remaining_int32 != 0:
        raise RuntimeError(f"failed to normalize catalog version fields to BSON Int64 (remaining Int32={remaining_int32})")

    return {
        "int64_verified": True,
        "documents_checked": normalized_counts,
    }


def run_mongo_migration(
    source_uri: str,
    source_db_name: str,
    target_uri: str,
    target_db_name: str,
    dry_run: bool,
    contest_filter: str | None = None,
    include_contest_subjects: bool = False,
) -> dict:
    from bson import Int64, json_util
    from pymongo import MongoClient, ReplaceOne

    if source_uri == target_uri and source_db_name == target_db_name:
        raise ValueError("source and target database cannot be identical; CNU must remain untouched")

    bundled_catalog = kc.read(ROOT / "shared/knowledge-catalog.json")
    bundled_registry = kc.read(ROOT / "shared/knowledge-id-registry.json")
    templates_bundle = kc.read(ROOT / "shared/exam-templates.json")

    src_client = MongoClient(source_uri, appname="cogniva-2.0-readonly-migrator")
    try:
        src_db = src_client.get_database(source_db_name)
        coll_names = sorted(src_db.list_collection_names())
        source_collections = {
            name: src_db.get_collection(name).estimated_document_count()
            for name in coll_names
        }
        source_catalogs, source_labels = _read_source_catalogs_and_labels(src_db)
        raw_cursor = src_db.get_collection("todasQuestoes").find({})
        all_raw_questions = [
            json.loads(json_util.dumps(d, json_options=json_util.RELAXED_JSON_OPTIONS))
            for d in raw_cursor
        ]

        raw_contests_by_coll: dict[str, list[dict]] = {}
        if contest_filter:
            for c_coll in ("exam_templates", "user_exams", "concursos"):
                if c_coll in coll_names:
                    raw_contests_by_coll[c_coll] = [
                        json.loads(json_util.dumps(d, json_options=json_util.RELAXED_JSON_OPTIONS))
                        for d in src_db.get_collection(c_coll).find({})
                    ]
    finally:
        src_client.close()

    tgt_reader = MongoClient(target_uri, appname="cogniva-2.0-target-inspector")
    try:
        current_target_catalog, current_target_registry, parent_activation_id = _read_target_active_catalog(
            tgt_reader.get_database(target_db_name),
            bundled_catalog,
            bundled_registry,
        )
    finally:
        tgt_reader.close()

    (
        effective_catalog,
        effective_registry,
        subject_id_map,
        topic_pair_map,
        topic_flat_map,
        catalog_extended,
    ) = merge_source_catalog_extensions(
        current_target_catalog,
        current_target_registry,
        source_catalogs,
    )

    matched_templates: list[dict] = []
    default_tag: str | None = None
    selective_diag: dict | None = None

    if contest_filter:
        matched_templates, raw_docs, selective_diag = filter_contests_and_questions(
            raw_contests_by_coll=raw_contests_by_coll,
            raw_questions=all_raw_questions,
            catalog=effective_catalog,
            contest_filter=contest_filter,
            include_contest_subjects=include_contest_subjects,
            source_catalog_labels=source_labels,
            subject_id_map=subject_id_map,
            topic_pair_map=topic_pair_map,
            topic_flat_map=topic_flat_map,
        )
        if len(matched_templates) == 1:
            default_tag = matched_templates[0]["_id"]
    else:
        raw_docs = all_raw_questions

    migrated, stats = migrate_batch(
        raw_docs,
        effective_catalog,
        source_catalog_labels=source_labels,
        default_contest_tag=default_tag,
        subject_id_map=subject_id_map,
        topic_pair_map=topic_pair_map,
        topic_flat_map=topic_flat_map,
    )
    stats["dry_run"] = dry_run
    stats["source_db"] = source_db_name
    stats["target_db"] = target_db_name
    stats["source_writes"] = 0
    stats["source_collections"] = source_collections
    stats["catalog_extension"] = {
        "extended": catalog_extended,
        "target_prior_version": int(current_target_catalog["catalog_version"]),
        "effective_version": int(effective_catalog["catalog_version"]),
        "subjects_before": len(current_target_catalog["subjects"]),
        "subjects_after": len(effective_catalog["subjects"]),
        "topics_before": len(current_target_catalog["topics"]),
        "topics_after": len(effective_catalog["topics"]),
        "catalog_sha256": rfc8785_sha256(effective_catalog),
    }
    if selective_diag is not None:
        stats["selective_migration"] = selective_diag

    if dry_run:
        return stats

    tgt_client = MongoClient(target_uri, appname="cogniva-2.0-target-writer")
    try:
        tgt_db = tgt_client.get_database(target_db_name)
        now_dt = datetime.now(timezone.utc)

        # 1. Seed bundled catalog v2 snapshot idempotently
        base_key = f"{bundled_catalog['catalog_id']}:{bundled_catalog['catalog_version']}"
        tgt_db.get_collection("knowledge_catalog_versions").update_one(
            {"_id": base_key},
            {
                "$setOnInsert": {
                    "catalog_id": bundled_catalog["catalog_id"],
                    "version": Int64(int(bundled_catalog["catalog_version"])),
                    "payload": bundled_catalog,
                }
            },
            upsert=True,
        )

        # 2. Activate extended catalog version (e.g. v3) before writing classified questions/templates
        if catalog_extended:
            base_sha = rfc8785_sha256(current_target_catalog)
            next_sha = rfc8785_sha256(effective_catalog)
            next_ver = int(effective_catalog["catalog_version"])
            op_uuid = deterministic_uuid(
                f"cogniva-v2-catalog-activation:{next_ver}:{base_sha}:{next_sha}"
            )
            act_id = f"0:{op_uuid}"
            req_sha = rfc8785_sha256(
                {"actor": 0, "operation_id": op_uuid, "catalog_sha256": next_sha}
            )
            event_doc = {
                "_id": act_id,
                "operation_id": op_uuid,
                "actor_user_id": Int64(0),
                "draft_id": op_uuid,
                "draft_revision": Int64(1),
                "request_sha256": req_sha,
                "reason": "Migração somente-leitura das extensões editoriais de catálogo CNU para Cogniva 2.0",
                "recorded_at": now_dt,
                "parent_activation_id": parent_activation_id,
                "base_catalog_sha256": base_sha,
                "catalog_sha256": next_sha,
                "version": Int64(next_ver),
                "catalog": effective_catalog,
                "registry": effective_registry,
            }
            tgt_db.get_collection("knowledge_catalog_activations").update_one(
                {"_id": act_id},
                {"$setOnInsert": event_doc},
                upsert=True,
            )
            ext_key = f"{effective_catalog['catalog_id']}:{next_ver}"
            tgt_db.get_collection("knowledge_catalog_versions").update_one(
                {"_id": ext_key},
                {
                    "$setOnInsert": {
                        "catalog_id": effective_catalog["catalog_id"],
                        "version": Int64(next_ver),
                        "payload": effective_catalog,
                    }
                },
                upsert=True,
            )
            head_doc = {
                "_id": "cogniva-knowledge",
                "activation_id": act_id,
                "version": Int64(next_ver),
                "catalog_sha256": next_sha,
                "updated_at": now_dt,
            }
            tgt_db.get_collection("knowledge_catalog_head").replace_one(
                {"_id": "cogniva-knowledge"},
                head_doc,
                upsert=True,
            )
            stats["activated_catalog_version"] = next_ver
            stats["activated_catalog_id"] = act_id

        # 2b. Ensure BSON Int64 parity with Rust catalog_runtime.rs on all active/historical catalog metadata
        stats["catalog_bson_int64"] = _ensure_catalog_bson_int64(tgt_db)

        # 3. Upsert selectively migrated contests into global exam_templates & activate on admin accounts
        tpl_coll = tgt_db.get_collection("exam_templates")
        migrated_tpl_ids: list[str] = []
        for mtpl in matched_templates:
            tpl_doc = copy.deepcopy(mtpl)
            tpl_doc["exam_id"] = tpl_doc["_id"]
            tpl_doc["revision"] = Int64(int(tpl_doc.get("revision") or 1))
            tpl_doc["updated_at"] = now_dt
            tpl_coll.replace_one({"_id": tpl_doc["_id"]}, tpl_doc, upsert=True)
            migrated_tpl_ids.append(tpl_doc["_id"])
        stats["migrated_exam_templates_upserted"] = migrated_tpl_ids

        if migrated_tpl_ids and "usuarios" in set(tgt_db.list_collection_names()):
            adm_res = tgt_db.get_collection("usuarios").update_many(
                {"role": "admin"},
                {"$addToSet": {"active_contest_ids": {"$each": migrated_tpl_ids}}},
            )
            stats["admin_ladders_activated"] = adm_res.matched_count

        # 4. Seed bundled global exam templates idempotently (insert only if absent)
        seeded_templates = 0
        for tpl in templates_bundle["templates"]:
            tpl_doc = copy.deepcopy(tpl)
            tpl_doc["exam_id"] = tpl_doc["_id"]
            tpl_doc["revision"] = Int64(int(tpl_doc.get("revision") or 1))
            tpl_doc["updated_at"] = now_dt
            up = tpl_coll.update_one({"_id": tpl_doc["_id"]}, {"$setOnInsert": tpl_doc}, upsert=True)
            if up.upserted_id is not None:
                seeded_templates += 1
        stats["exam_templates_seeded"] = seeded_templates

        # 5. Upsert validated Schema v2 questions
        if migrated:
            ops = []
            for v2 in migrated:
                mdoc = _to_mongo_doc(v2)
                ops.append(ReplaceOne({"_id": mdoc["_id"]}, mdoc, upsert=True))
            res = tgt_db.get_collection("todasQuestoes").bulk_write(ops, ordered=False)
            stats["questions_upserted"] = res.upserted_count + res.modified_count + res.matched_count
        else:
            stats["questions_upserted"] = 0
    finally:
        tgt_client.close()

    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-uri", default=os.environ.get("SOURCE_MONGODB_URI", ""))
    parser.add_argument("--source-db", default="CNU")
    parser.add_argument("--target-uri", default=os.environ.get("MONGODB_URI", ""))
    parser.add_argument("--target-db", default=os.environ.get("MONGODB_DB_NAME") or os.environ.get("DATABASE_NAME") or "cogniva")
    parser.add_argument("--contest-filter", default=None, help="Case-insensitive regex to selectively migrate matching contest(s) and questions")
    parser.add_argument("--include-contest-subjects", action="store_true", help="Also include questions matching any subject/topic of matched contest(s)")
    parser.add_argument("--input-json", type=Path, help="Optional offline JSON file instead of MongoDB source")
    parser.add_argument("--output-report", type=Path)
    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument("--dry-run", action="store_true")
    mode_group.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    effective_dry_run = bool(args.dry_run) or not bool(args.apply)
    # Preserve historic behavior if neither --dry-run nor --apply was explicitly given unless --dry-run is set:
    if not args.dry_run and not args.apply:
        effective_dry_run = False

    if args.input_json:
        catalog = kc.read(ROOT / "shared/knowledge-catalog.json")
        raw_docs = json.loads(args.input_json.read_text(encoding="utf-8"))
        _, report = migrate_batch(raw_docs, catalog)
        report["dry_run"] = True
        report["source_writes"] = 0
    else:
        source_uri = args.source_uri or args.target_uri
        if not source_uri:
            raise SystemExit("Informe --source-uri (ou SOURCE_MONGODB_URI / MONGODB_URI) ou --input-json.")
        target_uri = args.target_uri or source_uri
        report = run_mongo_migration(
            source_uri=source_uri,
            source_db_name=args.source_db,
            target_uri=target_uri,
            target_db_name=args.target_db,
            dry_run=effective_dry_run,
            contest_filter=args.contest_filter,
            include_contest_subjects=args.include_contest_subjects,
        )

    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output_report:
        args.output_report.parent.mkdir(parents=True, exist_ok=True)
        args.output_report.write_text(output + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
