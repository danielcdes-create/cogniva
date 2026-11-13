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

sys.path.insert(0, str(Path(__file__).resolve().parent))
import knowledge_catalog as kc
import question_contracts as qc

ROOT = kc.ROOT
MIGRATION_TIMESTAMP = "2026-11-12T00:00:00Z"

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


def deterministic_uuid(seed: str) -> str:
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest[:16], version=4))


def _is_valid_custom_id(val: str) -> bool:
    if not isinstance(val, str) or not val.startswith("custom-"):
        return False
    try:
        uuid.UUID(val[len("custom-") :])
        return True
    except Exception:
        return False


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
) -> dict:
    """Normalize a legacy CNU contest (`user_exams` / `exam_templates`) into a valid Cogniva 2.0 global template."""
    if not isinstance(raw_exam, dict):
        raise ValueError("exam document must be a dict")

    source_labels = source_catalog_labels or {}
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
        raw_csid = str(raw_s.get("canonical_subject_id") or "").strip()
        disp_name = (
            _clean_optional_str(raw_s.get("display_name"), 300)
            or _clean_optional_str(source_labels.get(raw_csid), 300)
            or _clean_optional_str(raw_csid, 300)
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
            raw_ctid = str(raw_t.get("canonical_topic_id") or "").strip()
            t_title = (
                _clean_optional_str(raw_t.get("display_title"), 1000)
                or _clean_optional_str(source_labels.get(raw_ctid), 1000)
                or _clean_optional_str(raw_ctid, 1000)
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
                # Avoid duplicate canonical_topic_id inside same subject
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
) -> dict:
    """Pure, deterministic transformation of a raw CNU V1 or V2 question into Cogniva 2.0 V2 format."""
    if not isinstance(raw_doc, dict):
        raise ValueError("document must be a dict")

    oid = _oid_hex(raw_doc.get("_id", ""))
    question_text = _clean_optional_str(raw_doc.get("question"), 20000)
    if not question_text:
        raise ValueError("empty question text")

    source_labels = source_catalog_labels or {}
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

        # Preserve or infer human-readable disciplina before adjusting classification links
        raw_disc = _clean_optional_str(doc.get("disciplina"), 300)
        if not raw_disc and isinstance(doc.get("classification"), dict):
            for lk in doc["classification"].get("links") or []:
                if isinstance(lk, dict) and lk.get("subject_id"):
                    sid = str(lk["subject_id"])
                    raw_disc = (
                        (active_subjects[sid]["label"] if sid in active_subjects else None)
                        or source_labels.get(sid)
                        or sid
                    )
                    break
        if raw_disc:
            matched_subj = subj_lookup.get(kc.normalized(raw_disc))
            doc["disciplina"] = matched_subj["label"] if matched_subj else raw_disc
        elif "disciplina" not in doc or not isinstance(doc.get("disciplina"), str):
            doc["disciplina"] = "Geral"

        if isinstance(doc.get("classification"), dict):
            cls = doc["classification"]
            links = cls.get("links") if isinstance(cls.get("links"), list) else []
            links_valid = bool(links) and all(
                isinstance(lk, dict)
                and (lk.get("subject_id"), lk.get("topic_id")) in active_topics
                and qc.human_review(lk.get("provenance"))
                for lk in links
            )
            if cls.get("state") in ("CONFIRMED", "PARTIAL") and links_valid:
                cls["catalog_version"] = catalog_ver
                cls["revision"] = max(1, int(cls.get("revision") or 1))
                primaries = [
                    lk
                    for lk in links
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

    raw_csid = _clean_optional_str(raw_doc.get("canonical_subject_id"), 300)
    raw_disc = (
        _clean_optional_str(raw_doc.get("disciplina"), 300)
        or (source_labels.get(raw_csid) if raw_csid else None)
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
) -> tuple[list[dict], dict]:
    """Normalize a batch of raw documents and return `(valid_v2_docs, stats)` without PII/content."""
    subj_lookup = subject_lookup_table(catalog)
    migrated: list[dict] = []
    skipped: list[dict] = []
    mapped_subjects = 0
    unmapped_subjects = 0

    for raw in raw_docs:
        oid = _oid_hex(raw.get("_id", "")) if isinstance(raw, dict) else "unknown"
        try:
            v2 = normalize_question_to_v2(
                raw,
                catalog,
                source_catalog_labels=source_catalog_labels,
                default_contest_tag=default_contest_tag,
            )
            if kc.normalized(v2.get("disciplina", "")) in subj_lookup:
                mapped_subjects += 1
            else:
                unmapped_subjects += 1
            migrated.append(v2)
        except Exception as exc:
            skipped.append({"id": oid, "reason": str(exc)})

    stats = {
        "source_documents_read": len(raw_docs),
        "normalized_v2_valid": len(migrated),
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

    # Deduplicate matched contests by normalized _id, preferring non-EXCLUIDO, higher collection priority, higher revision
    best_by_id: dict[str, tuple[tuple[int, int, int], dict, dict]] = {}
    for c_prio, raw_c in matched_raw_contests:
        norm_tpl = normalize_contest_to_v2_template(raw_c, catalog, source_labels)
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


def _read_source_catalog_labels(src_db) -> dict[str, str]:
    """Extract subject/topic ID -> label mappings from legacy CNU catalog collections if present."""
    labels: dict[str, str] = {}
    existing_colls = set(src_db.list_collection_names())
    for coll_name, payload_key in (
        ("knowledge_catalog_versions", "payload"),
        ("knowledge_catalog_activations", "catalog"),
    ):
        if coll_name not in existing_colls:
            continue
        for doc in src_db.get_collection(coll_name).find({}):
            cat = doc.get(payload_key)
            if isinstance(cat, dict):
                for s in cat.get("subjects") or []:
                    if isinstance(s, dict) and s.get("subject_id") and s.get("label"):
                        labels[str(s["subject_id"])] = str(s["label"])
                for t in cat.get("topics") or []:
                    if isinstance(t, dict) and t.get("topic_id") and t.get("label"):
                        labels[str(t["topic_id"])] = str(t["label"])
    return labels


def run_mongo_migration(
    source_uri: str,
    source_db_name: str,
    target_uri: str,
    target_db_name: str,
    dry_run: bool,
    contest_filter: str | None = None,
    include_contest_subjects: bool = False,
) -> dict:
    from bson import json_util
    from pymongo import MongoClient, ReplaceOne

    if source_uri == target_uri and source_db_name == target_db_name:
        raise ValueError("source and target database cannot be identical; CNU must remain untouched")

    catalog = kc.read(ROOT / "shared/knowledge-catalog.json")
    templates_bundle = kc.read(ROOT / "shared/exam-templates.json")

    src_client = MongoClient(source_uri, appname="cogniva-2.0-readonly-migrator")
    try:
        src_db = src_client.get_database(source_db_name)
        coll_names = sorted(src_db.list_collection_names())
        source_collections = {
            name: src_db.get_collection(name).estimated_document_count()
            for name in coll_names
        }
        source_labels = _read_source_catalog_labels(src_db)
        raw_cursor = src_db.get_collection("todasQuestoes").find({})
        all_raw_questions = [json.loads(json_util.dumps(d)) for d in raw_cursor]

        raw_contests_by_coll: dict[str, list[dict]] = {}
        if contest_filter:
            for c_coll in ("exam_templates", "user_exams", "concursos"):
                if c_coll in coll_names:
                    raw_contests_by_coll[c_coll] = [
                        json.loads(json_util.dumps(d))
                        for d in src_db.get_collection(c_coll).find({})
                    ]
    finally:
        src_client.close()

    matched_templates: list[dict] = []
    default_tag: str | None = None
    selective_diag: dict | None = None

    if contest_filter:
        matched_templates, raw_docs, selective_diag = filter_contests_and_questions(
            raw_contests_by_coll=raw_contests_by_coll,
            raw_questions=all_raw_questions,
            catalog=catalog,
            contest_filter=contest_filter,
            include_contest_subjects=include_contest_subjects,
            source_catalog_labels=source_labels,
        )
        if len(matched_templates) == 1:
            default_tag = matched_templates[0]["_id"]
    else:
        raw_docs = all_raw_questions

    migrated, stats = migrate_batch(
        raw_docs,
        catalog,
        source_catalog_labels=source_labels,
        default_contest_tag=default_tag,
    )
    stats["dry_run"] = dry_run
    stats["source_db"] = source_db_name
    stats["target_db"] = target_db_name
    stats["source_writes"] = 0
    stats["source_collections"] = source_collections
    if selective_diag is not None:
        stats["selective_migration"] = selective_diag

    if dry_run:
        return stats

    tgt_client = MongoClient(target_uri, appname="cogniva-2.0-target-writer")
    try:
        tgt_db = tgt_client.get_database(target_db_name)
        if migrated:
            ops = []
            for v2 in migrated:
                mdoc = _to_mongo_doc(v2)
                ops.append(ReplaceOne({"_id": mdoc["_id"]}, mdoc, upsert=True))
            res = tgt_db.get_collection("todasQuestoes").bulk_write(ops, ordered=False)
            stats["questions_upserted"] = res.upserted_count + res.modified_count + res.matched_count
        else:
            stats["questions_upserted"] = 0

        # Seed unified catalog v2 idempotently
        cat_key = f"{catalog['catalog_id']}:{catalog['catalog_version']}"
        tgt_db.get_collection("knowledge_catalog_versions").update_one(
            {"_id": cat_key},
            {
                "$setOnInsert": {
                    "catalog_id": catalog["catalog_id"],
                    "version": catalog["catalog_version"],
                    "payload": catalog,
                }
            },
            upsert=True,
        )

        tpl_coll = tgt_db.get_collection("exam_templates")
        now_dt = datetime.now(timezone.utc)

        # Upsert selectively migrated contests into global exam_templates
        migrated_tpl_ids: list[str] = []
        for mtpl in matched_templates:
            tpl_doc = copy.deepcopy(mtpl)
            tpl_doc["exam_id"] = tpl_doc["_id"]
            tpl_doc["updated_at"] = now_dt
            tpl_coll.replace_one({"_id": tpl_doc["_id"]}, tpl_doc, upsert=True)
            migrated_tpl_ids.append(tpl_doc["_id"])
        stats["migrated_exam_templates_upserted"] = migrated_tpl_ids

        # Seed bundled global exam templates idempotently (insert only if absent)
        seeded_templates = 0
        for tpl in templates_bundle["templates"]:
            tpl_doc = copy.deepcopy(tpl)
            tpl_doc["exam_id"] = tpl_doc["_id"]
            tpl_doc["updated_at"] = now_dt
            up = tpl_coll.update_one({"_id": tpl_doc["_id"]}, {"$setOnInsert": tpl_doc}, upsert=True)
            if up.upserted_id is not None:
                seeded_templates += 1
        stats["exam_templates_seeded"] = seeded_templates
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
