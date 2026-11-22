#!/usr/bin/env python3
"""Fast, idempotent package importer for Cogniva V2 / V2.1 ready-to-study questions.

Executes the complete readiness pipeline inside a single SSH session against
loopback (http://127.0.0.1:8001) with HTTP Keep-Alive auto-reconnect:
  1. Local pre-flight contract & editorial validation (--check-local)
  2. Runtime Knowledge Catalog check & versioned activation (SUBJECT / TOPIC / SUBTOPIC)
  3. Global Exam Template leaf-subtopic registration & user_exams sync (adopt-template)
  4. Auto-chunked Preview (<= 50 items & <= 480 KiB) with duplicate protection
  5. Promotion to classification=CONFIRMED + validity=CURRENT (V2.0 & V2.1 SEMANTIC)
  6. Transactional Commit + Idempotent Replay check (already_applied)
  7. Explicit DIRECT Compatibility binding per item (question_exam_compatibility)
  8. Live quiz-draw verification per imported subtopic (/api/v1/quiz/generate)

See docs/PACOTES-QUESTOES.md for the complete authoring and operational guide.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Any
import unicodedata
import zlib

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SSH_HOST = os.environ.get("COGNIVA_SSH_HOST", "root@198.199.65.103")
DEFAULT_EXAM_ID = "c02bb92e-f015-4fae-8d52-92814babe02c"
MAX_BATCH_ITEMS = 50
MAX_CHUNK_BYTES = 480 * 1024  # Safe margin under backend 512 KiB limit

ID_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
LETTER_PREFIX_RE = re.compile(r"^\s*[A-Ea-e]\s*[\)\.\-:]\s+")

ALLOWED_SIMPLIFIED_KEYS = {
    "question_type",
    "question",
    "answers",
    "correct_answer_indices",
    "grading",
    "subject_id",
    "topic_id",
    "confirm_classification",
    "origin_kind",
    "source_exam_code",
    "institution",
    "organizer",
    "exam_year",
    "question_number",
    "source_reference",
    "confirm_origin",
    "explanation",
    "level",
}


def normalize_nfkd(value: str) -> str:
    return " ".join(
        "".join(
            c if c.isalnum() else " "
            for c in unicodedata.normalize("NFKD", value.casefold())
            if not unicodedata.combining(c)
        ).split()
    )


def is_strict_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def validate_items_locally(
    items: list[dict[str, Any]],
    *,
    source_label: str,
    default_subject_id: str | None = None,
    default_topic_id: str | None = None,
    allow_letter_prefixes: bool = False,
) -> list[dict[str, Any]]:
    """Validate and normalize simplified items before contacting the server."""
    if not isinstance(items, list) or not items:
        raise ValueError(f"{source_label}: 'items' deve ser uma lista não vazia")

    normalized_items: list[dict[str, Any]] = []
    seen_questions: dict[str, int] = {}

    for idx, raw in enumerate(items):
        loc = f"{source_label}[{idx}]"
        if not isinstance(raw, dict):
            raise ValueError(f"{loc}: cada item deve ser um objeto JSON")

        item = dict(raw)
        if not item.get("subject_id") and default_subject_id:
            item["subject_id"] = default_subject_id
        if not item.get("topic_id") and default_topic_id:
            item["topic_id"] = default_topic_id
        if "confirm_classification" not in item:
            item["confirm_classification"] = True

        if "schema_version" not in item:
            extra = sorted(set(item.keys()) - ALLOWED_SIMPLIFIED_KEYS)
            if extra:
                raise ValueError(
                    f"{loc}: chaves não permitidas no formato simplificado: {extra}. "
                    f"Permitidas: {sorted(ALLOWED_SIMPLIFIED_KEYS)}"
                )

        sid = item.get("subject_id")
        tid = item.get("topic_id")
        if not isinstance(sid, str) or not ID_RE.match(sid):
            raise ValueError(f"{loc}: subject_id inválido ({sid!r}); esperado ^[A-Z][A-Z0-9_]*$")
        if not isinstance(tid, str) or not ID_RE.match(tid):
            raise ValueError(f"{loc}: topic_id inválido ({tid!r}); esperado ^[A-Z][A-Z0-9_]*$")
        if item.get("confirm_classification") is not True:
            raise ValueError(f"{loc}: confirm_classification deve ser true para pacotes prontos")

        qtype = item.get("question_type")
        if qtype not in {"TRUE_FALSE", "MULTIPLE_CHOICE", "OPEN_ENDED"}:
            raise ValueError(f"{loc}: question_type inválido ({qtype!r})")

        qtext = item.get("question")
        if not isinstance(qtext, str) or len(qtext.strip()) < 15:
            raise ValueError(f"{loc}: enunciado ('question') ausente ou curto demais")
        qnorm = " ".join(qtext.split())
        if qnorm in seen_questions:
            raise ValueError(
                f"{loc}: enunciado duplicado dentro do mesmo pacote (coincide com índice {seen_questions[qnorm]})"
            )
        seen_questions[qnorm] = idx

        answers = item.get("answers")
        indices = item.get("correct_answer_indices")
        if not isinstance(answers, list) or not isinstance(indices, list):
            raise ValueError(f"{loc}: 'answers' e 'correct_answer_indices' devem ser listas")

        if qtype == "TRUE_FALSE":
            if answers != ["Certo", "Errado"]:
                raise ValueError(f"{loc}: TRUE_FALSE exige answers == ['Certo', 'Errado']")
            if len(indices) != 1 or not is_strict_int(indices[0]) or indices[0] not in (0, 1):
                raise ValueError(f"{loc}: TRUE_FALSE exige correct_answer_indices == [0] ou [1] (inteiro estrito)")
            if item.get("grading") is not None:
                raise ValueError(f"{loc}: questões objetivas exigem grading = null")
        elif qtype == "MULTIPLE_CHOICE":
            if len(answers) != 5 or any(not isinstance(a, str) or not a.strip() for a in answers):
                raise ValueError(f"{loc}: MULTIPLE_CHOICE exige exatamente 5 alternativas não vazias")
            if len({a.strip() for a in answers}) != 5:
                raise ValueError(f"{loc}: MULTIPLE_CHOICE possui alternativas repetidas")
            if not allow_letter_prefixes:
                for a_idx, alt in enumerate(answers):
                    if LETTER_PREFIX_RE.match(alt):
                        raise ValueError(
                            f"{loc}: alternativa {a_idx} começa com prefixo de letra ({alt[:6]!r}); "
                            "remova 'A) '/'B) ' pois a UI já renderiza as letras"
                        )
            if len(indices) != 1 or not is_strict_int(indices[0]) or indices[0] not in range(5):
                raise ValueError(f"{loc}: MULTIPLE_CHOICE exige correct_answer_indices com 1 inteiro estrito em 0..4")
            if item.get("grading") is not None:
                raise ValueError(f"{loc}: questões objetivas exigem grading = null")

        origin_kind = item.get("origin_kind", "AUTHOR_CREATED")
        if origin_kind not in {"AUTHOR_CREATED", "OFFICIAL_EXAM", "LEGACY_IMPORT"}:
            raise ValueError(f"{loc}: origin_kind inválido ({origin_kind!r})")
        if item.get("exam_year") is not None and not is_strict_int(item["exam_year"]):
            raise ValueError(f"{loc}: exam_year deve ser inteiro estrito ou null")
        if item.get("confirm_origin") is True and not item.get("source_reference"):
            raise ValueError(f"{loc}: confirm_origin=true exige source_reference não nulo")

        expl = item.get("explanation")
        if not isinstance(expl, str) or len(expl.strip()) < 20:
            raise ValueError(f"{loc}: explanation ausente ou curta demais (mínimo recomendado >= 20 caracteres)")

        normalized_items.append(item)

    return normalized_items


def chunk_items(items: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    """Split items respecting both <= 50 items and <= 480 KiB serialized UTF-8 size."""
    chunks: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    current_bytes = 2
    for item in items:
        encoded_len = len(json.dumps(item, ensure_ascii=False).encode("utf-8")) + 2
        if current and (len(current) >= MAX_BATCH_ITEMS or current_bytes + encoded_len > MAX_CHUNK_BYTES):
            chunks.append(current)
            current = []
            current_bytes = 2
        current.append(item)
        current_bytes += encoded_len
    if current:
        chunks.append(current)
    return chunks


def normalize_validity_basis(raw_basis: str) -> str:
    basis = (raw_basis or "FACTUAL").strip().upper()
    if basis in ("NORMATIVE", "JURISPRUDENTIAL"):
        return "LEGAL"
    if basis in ("FACTUAL", "LEGAL", "TECHNICAL", "TIMELESS"):
        return basis
    raise ValueError(f"validity_basis inválida: {raw_basis!r}")


def resolve_review_due_on(effective_basis: str, explicit_due: str | None, today: dt.date | None = None) -> str | None:
    if explicit_due:
        parsed = dt.date.fromisoformat(explicit_due.strip())
        return parsed.isoformat()
    if effective_basis == "LEGAL":
        base_day = today or dt.datetime.now(dt.timezone.utc).date()
        return (base_day + dt.timedelta(days=365)).isoformat()
    return None


REMOTE_RUNNER = r"""
import base64
import copy
import datetime as dt
import hashlib
import hmac
import http.client
import json
import subprocess
import sys
import time
import uuid
import zlib

raw_payload = __B64_PAYLOAD__
req_bundle = json.loads(zlib.decompress(base64.b64decode(raw_payload)).decode("utf-8"))

t0 = time.time()

core = json.loads(subprocess.check_output(["docker", "inspect", "api-rust-core"]))[0]
env = dict(row.split("=", 1) for row in core["Config"]["Env"] if "=" in row)
jwt_secret = env["JWT_SECRET"]

def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

def mint_ephemeral_admin_session(admin_email: str | None) -> tuple[int, str]:
    cmd = ["docker", "exec", "-e", "RUST_LOG=error", "api-rust-core", "/usr/local/bin/cogniva-api", "--mint-admin-token"]
    if admin_email:
        cmd.append(admin_email)
    out = subprocess.check_output(cmd, text=True).strip()
    jpos = out.find("{")
    minted = json.loads(out[jpos:] if jpos >= 0 else out)
    return int(minted["user_id"]), str(minted["token"])

ephemeral_session = False
explicit_uid = req_bundle.get("admin_user_id")
explicit_sid = req_bundle.get("admin_sid")
admin_email = req_bundle.get("admin_email")
if explicit_uid and explicit_sid:
    user_id = int(explicit_uid)
    sid = str(explicit_sid)
    now = int(time.time())
    header = b64u(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64u(json.dumps({
        "v": 2,
        "uid": user_id,
        "sid": sid,
        "purpose": "mobile",
        "iat": now,
        "exp": now + 900,
    }, separators=(",", ":")).encode())
    sig = b64u(hmac.new(jwt_secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
    token = f"{header}.{payload}.{sig}"
else:
    user_id, token = mint_ephemeral_admin_session(admin_email)
    ephemeral_session = True

conn = http.client.HTTPConnection("127.0.0.1", 8001, timeout=120)

def api(path: str, body=None, method: str | None = None):
    headers = {
        "Authorization": f"Bearer {token}",
        "Connection": "keep-alive",
    }
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    m = method or ("POST" if body is not None else "GET")
    try:
        conn.request(m, path, body=data, headers=headers)
        resp = conn.getresponse()
        raw = resp.read()
    except Exception:
        conn.close()
        conn.connect()
        conn.request(m, path, body=data, headers=headers)
        resp = conn.getresponse()
        raw = resp.read()
    if resp.status >= 400:
        raise RuntimeError(f"HTTP {resp.status} {m} {path}: {raw.decode('utf-8', errors='replace')}")
    return json.loads(raw.decode("utf-8"))

op_ns = req_bundle["operation_ns"]
def stable(s: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"{op_ns}:{s}"))

dry_run = bool(req_bundle.get("dry_run", False))
acknowledge_duplicates = bool(req_bundle.get("acknowledge_duplicates", False))
verify_quiz = bool(req_bundle.get("verify_quiz", True))
validity_basis = req_bundle.get("validity_basis", "FACTUAL")
review_due_on = req_bundle.get("review_due_on")
exam_id = req_bundle["exam_id"]
replace_custom = bool(req_bundle.get("replace_custom_placeholders", False))
taxonomy = req_bundle.get("taxonomy") or {}
packages = req_bundle["packages"]

today_iso = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
now_iso = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

summary_before = api("/api/v1/admin/summary")

# 1. Check & update runtime Knowledge Catalog if needed
base_cat = api("/api/v1/admin/catalog/base")
cat = copy.deepcopy(base_cat["catalog"])
existing_subjects = {s["subject_id"]: s for s in cat["subjects"]}
existing_topics = {t["topic_id"]: t for t in cat["topics"]}
added_catalog_ids = []

for s_spec in taxonomy.get("subjects") or []:
    sid_key = s_spec["subject_id"]
    if sid_key not in existing_subjects:
        new_subj = {
            "subject_id": sid_key,
            "label": s_spec["label"],
            "aliases": s_spec.get("aliases") or [],
            "scope_note": s_spec.get("scope_note") or f"Conteúdo programático de {s_spec['label']}.",
            "status": "ACTIVE",
            "revision": 1,
            "source_ids": s_spec.get("source_ids") or ["EDITORIAL_V1"],
            "trunk_links": s_spec["trunk_links"],
        }
        cat["subjects"].append(new_subj)
        existing_subjects[sid_key] = new_subj
        added_catalog_ids.append(sid_key)

for p_spec in taxonomy.get("parent_topics") or []:
    tid = p_spec["topic_id"]
    if tid not in existing_topics:
        new_parent = {
            "topic_id": tid,
            "subject_id": p_spec["subject_id"],
            "parent_topic_id": None,
            "kind": "TOPIC",
            "label": p_spec["label"],
            "aliases": p_spec.get("aliases") or [],
            "status": "ACTIVE",
            "revision": 1,
            "source_ids": p_spec.get("source_ids") or ["EDITORIAL_V1"],
        }
        cat["topics"].append(new_parent)
        existing_topics[tid] = new_parent
        added_catalog_ids.append(tid)

syllabus_title_overrides = {}
for st_spec in taxonomy.get("subtopics") or []:
    tid = st_spec["topic_id"]
    if st_spec.get("syllabus_display_title"):
        syllabus_title_overrides[tid] = st_spec["syllabus_display_title"]
    if tid not in existing_topics:
        parent_id = st_spec["parent_topic_id"]
        new_sub = {
            "topic_id": tid,
            "subject_id": st_spec["subject_id"],
            "parent_topic_id": parent_id,
            "kind": "SUBTOPIC",
            "label": st_spec["label"],
            "aliases": st_spec.get("aliases") or [],
            "status": "ACTIVE",
            "revision": 1,
            "source_ids": st_spec.get("source_ids") or ["EDITORIAL_V1"],
        }
        cat["topics"].append(new_sub)
        existing_topics[tid] = new_sub
        added_catalog_ids.append(tid)

# Verify every (subject_id, topic_id) referenced by items exists and is a leaf SUBTOPIC (or active TOPIC)
referenced_pairs = set()
for pkg in packages:
    for chunk in pkg["chunks"]:
        for it in chunk:
            s_id = it["subject_id"]
            t_id = it["topic_id"]
            if s_id not in existing_subjects:
                raise RuntimeError(f"Disciplina '{s_id}' ausente no catálogo ativo; declare em taxonomy.subjects.")
            if t_id not in existing_topics:
                raise RuntimeError(f"Tópico '{t_id}' ausente no catálogo ativo; declare em taxonomy.parent_topics / taxonomy.subtopics.")
            if existing_topics[t_id]["subject_id"] != s_id:
                raise RuntimeError(f"Tópico '{t_id}' pertence a '{existing_topics[t_id]['subject_id']}', não a '{s_id}'.")
            referenced_pairs.add((s_id, t_id))

catalog_activated_version = base_cat["catalog"]["catalog_version"]
if added_catalog_ids:
    cat["catalog_version"] = int(cat["catalog_version"]) + 1
    cat["updated_at"] = dt.datetime.now(dt.timezone(dt.timedelta(hours=-3))).date().isoformat()
    val = api("/api/v1/admin/catalog/validate", cat)
    if not val.get("valid"):
        raise RuntimeError(f"Falha na validação do catálogo v{cat['catalog_version']}: {val}")
    if not dry_run:
        draft_id = stable(f"catalog-draft-v{cat['catalog_version']}-{hashlib.sha256(','.join(added_catalog_ids).encode()).hexdigest()[:8]}")
        draft_res = api("/api/v1/admin/catalog/drafts", {
            "draft_id": draft_id,
            "expected_revision": 0,
            "base_catalog_sha256": base_cat["sha256"],
            "status": "REVIEW_READY",
            "catalog": cat,
        })
        draft_rev = draft_res.get("revision") or draft_res["draft"]["revision"]
        act_res = api("/api/v1/admin/catalog/activate", {
            "operation_id": stable(f"catalog-activate-v{cat['catalog_version']}-{draft_id}"),
            "draft_id": draft_id,
            "expected_draft_revision": draft_rev,
            "expected_catalog_hash": base_cat["sha256"],
            "reviewed": True,
            "reason": f"Ativação de {len(added_catalog_ids)} entidades no catálogo via cogniva_import.",
        })
        catalog_activated_version = act_res["activated_version"]

# 2. Ensure leaf subtopics are registered in the target exam template & sync user_exams
templates = api("/api/v1/admin/exam-templates")
exam_tpl = next((t for t in templates if t["_id"] == exam_id), None)
if exam_tpl is None:
    raise RuntimeError(f"Template de concurso '{exam_id}' não encontrado em /api/v1/admin/exam-templates")

subject_meta = {}
for s in exam_tpl["subjects"]:
    subject_meta[s["canonical_subject_id"]] = {
        "component_id": s["local_subject_id"],
        "disciplina": s["display_name"],
        "subject_ref": s,
    }

topics_with_children = {
    t["parent_topic_id"] for t in existing_topics.values() if t.get("parent_topic_id")
}

exam_modified = False
for s_id, t_id in sorted(referenced_pairs):
    if t_id in topics_with_children:
        raise RuntimeError(
            f"O tópico '{t_id}' é um TOPIC pai com subassuntos filhos. "
            "Classifique as questões sempre em um SUBTOPIC folha para preservar o rastreamento de domínio."
        )
    if s_id not in subject_meta:
        raise RuntimeError(
            f"A disciplina '{s_id}' não possui componente na grade do concurso '{exam_id}'. "
            "Adicione o componente da disciplina ao template do concurso antes de vincular."
        )
    s_obj = subject_meta[s_id]["subject_ref"]
    if replace_custom:
        filtered = [t for t in s_obj["topics"] if t.get("canonical_topic_id") is not None]
        if len(filtered) != len(s_obj["topics"]):
            s_obj["topics"] = filtered
            exam_modified = True
    comp_topic_ids = {t.get("canonical_topic_id") for t in s_obj["topics"]}
    if t_id not in comp_topic_ids:
        t_obj = existing_topics[t_id]
        parent_id = t_obj.get("parent_topic_id")
        if t_id in syllabus_title_overrides:
            display_title = syllabus_title_overrides[t_id]
        elif parent_id and parent_id in existing_topics:
            display_title = f"{existing_topics[parent_id]['label']} › {t_obj['label']}"
        else:
            display_title = t_obj["label"]
        prefix = s_obj["local_subject_id"].lower()
        s_obj["topics"].append({
            "local_topic_id": f"{prefix}-cat-{t_id}",
            "canonical_topic_id": t_id,
            "canonical_subject_id": s_id,
            "display_title": display_title,
            "diff_status": "PRE_EDITAL_BASE",
        })
        exam_modified = True

if exam_modified and not dry_run:
    exam_tpl = api(f"/api/v1/admin/exam-templates/{exam_id}", exam_tpl, method="PUT")
if not dry_run:
    user_exam = api(f"/api/v1/exams/{exam_id}/adopt-template", {})
else:
    user_exam = exam_tpl

# 3. Preview, stamp CONFIRMED + CURRENT, Commit, Replay, and Bind DIRECT Compatibility
package_reports = []
all_imported = []

for pkg in packages:
    pkg_key = pkg["package_key"]
    explicit_comp_id = pkg.get("component_id")
    committed_count = 0
    replayed_count = 0
    compat_count = 0

    for chunk_idx, chunk in enumerate(pkg["chunks"]):
        preview = api("/api/v1/admin/questions/import/preview", {
            "operation_id": stable(f"preview:{pkg_key}:c{chunk_idx}"),
            "items": chunk,
        })
        if not preview.get("ready") or len(preview.get("items", [])) != len(chunk):
            raise RuntimeError(f"Preview reprovado em {pkg_key} chunk {chunk_idx}: {json.dumps(preview, ensure_ascii=False)}")

        dup_warnings = preview.get("duplicate_warnings") or []
        if dup_warnings and not acknowledge_duplicates:
            raise RuntimeError(
                f"Duplicidades potenciais detectadas em {pkg_key} chunk {chunk_idx} "
                f"(passe --acknowledge-duplicates se intencional): {json.dumps(dup_warnings, ensure_ascii=False)}"
            )

        if dry_run:
            committed_count += len(chunk)
            continue

        is_v2_1_commit = "idempotency_key" in preview
        commands = []
        for entry in preview["items"]:
            cmd = copy.deepcopy(entry["command"])
            doc = cmd["document"]
            qid = doc["_id"]["$oid"]
            plink = doc["classification"]["links"][0]
            s_id = plink["subject_id"]
            t_id = plink["topic_id"]
            crev = doc["content_revision"]
            srev = doc.get("semantic_revision")

            doc["disciplina"] = subject_meta[s_id]["disciplina"]
            doc["canonical_subject_id"] = s_id
            doc["canonical_topic_id"] = t_id
            doc["concursos"] = [exam_id]

            validity_obj = {
                "revision": 1,
                "status": "CURRENT",
                "basis": validity_basis,
                "reference_date": today_iso,
                "review_due_on": review_due_on,
                "reason_codes": [],
                "evaluated_content_revision": crev,
                "decision": {
                    "actor": {"type": "HUMAN", "id": str(user_id)},
                    "method": "MANUAL_REVIEW",
                    "rationale": "Validade atual conferida na importação administrativa revisada.",
                    "recorded_at": now_iso,
                    "evidence": [{
                        "uri": f"urn:cogniva:admin-review:{qid}",
                        "title": "Revisão administrativa explícita na importação de pacote Cogniva",
                        "locator": None,
                        "retrieved_at": now_iso,
                        "sha256": None,
                    }],
                    "model": None,
                    "prompt_id": None,
                    "prompt_version": None,
                    "source_suggestion_id": None,
                },
                "scope": "CURRENT_KNOWLEDGE",
                "context_date": None,
            }
            if srev is not None:
                validity_obj["evaluated_semantic_revision"] = srev
                validity_obj["evaluation_mode"] = "SEMANTIC"
            doc["validity"] = validity_obj
            commands.append(cmd)

        commit_payload = {"reviewed": True, "commands": commands}
        if is_v2_1_commit:
            commit_payload["idempotency_key"] = stable(f"commit:{pkg_key}:c{chunk_idx}")
            if dup_warnings and acknowledge_duplicates:
                commit_payload["acknowledge_duplicates"] = True

        commit_res = api("/api/v1/admin/questions/import/commit", commit_payload)
        res_items = commit_res.get("items") or commit_res.get("results", [])
        if not commit_res.get("complete") or len(res_items) != len(chunk) or not all(x.get("saved") for x in res_items):
            raise RuntimeError(f"Commit falhou em {pkg_key} chunk {chunk_idx}: {commit_res}")
        committed_count += len(res_items)

        replay_res = api("/api/v1/admin/questions/import/commit", commit_payload)
        rep_items = replay_res.get("items") or replay_res.get("results", [])
        if len(rep_items) != len(chunk) or not all(x.get("saved") and x["receipt"]["status"] == "already_applied" for x in rep_items):
            raise RuntimeError(f"Verificação de replay idempotente falhou em {pkg_key} chunk {chunk_idx}: {replay_res}")
        replayed_count += len(rep_items)

        for cmd in commands:
            doc = cmd["document"]
            qid = doc["_id"]["$oid"]
            plink = doc["classification"]["links"][0]
            s_id = plink["subject_id"]
            t_id = plink["topic_id"]
            tlabel = existing_topics[t_id]["label"]
            comp_id = explicit_comp_id or subject_meta[s_id]["component_id"]
            rec = api(f"/api/v1/exams/{exam_id}/compatibility", {
                "operation_id": stable(f"compat:{pkg_key}:{qid}:rev{user_exam['revision']}"),
                "question_id": qid,
                "exam_id": exam_id,
                "component_id": comp_id,
                "relation": "DIRECT",
                "matched_topic_ids": [t_id],
                "reason": f"Vínculo direto confirmado ao concurso {exam_id} ({comp_id} → {tlabel}).",
            })
            assert rec["question_id"] == qid and rec["relation"] == "DIRECT" and rec["status"] == "CONFIRMED"
            compat_count += 1
            all_imported.append({
                "package_key": pkg_key,
                "question_id": qid,
                "subject_id": s_id,
                "topic_id": t_id,
                "topic_label": tlabel,
                "content_revision": doc["content_revision"],
                "import_operation_id": cmd["operation_id"],
                "compatibility_id": rec["_id"],
            })

    package_reports.append({
        "package_key": pkg_key,
        "chunks": len(pkg["chunks"]),
        "count": pkg["count"],
        "committed": committed_count,
        "replayed": replayed_count,
        "compatibility_bound": compat_count,
    })

# 4. Live quiz-draw verification per imported leaf subtopic
quiz_verified_topics = {}
if not dry_run and verify_quiz:
    for s_id, t_id in sorted(referenced_pairs):
        comp_id = subject_meta[s_id]["component_id"]
        disciplina = subject_meta[s_id]["disciplina"]
        drawn = api("/api/v1/quiz/generate", {
            "user_id": user_id,
            "mode": "STANDARD",
            "quantity": 3,
            "contest_id": exam_id,
            "disciplina": disciplina,
            "component_id": comp_id,
            "syllabus_topic_id": t_id,
        })
        if not drawn:
            raise RuntimeError(f"Sorteio de verificação retornou 0 questões para {s_id} / {t_id}")
        for q in drawn:
            if q.get("canonical_subject_id") != s_id or q.get("canonical_topic_id") != t_id or not q.get("knowledge_binding_verified"):
                raise RuntimeError(f"Questão sorteada sem vínculo verificado em {t_id}: {q}")
        quiz_verified_topics[t_id] = len(drawn)

summary_after = api("/api/v1/admin/summary")
if ephemeral_session:
    try:
        api("/api/v1/auth/logout", {}, method="POST")
    except Exception:
        pass
conn.close()

print(json.dumps({
    "complete": True,
    "dry_run": dry_run,
    "elapsed_seconds": round(time.time() - t0, 2),
    "catalog_version": catalog_activated_version,
    "added_catalog_ids": added_catalog_ids,
    "exam_id": exam_id,
    "exam_template_revision": exam_tpl["revision"],
    "user_exam_revision": user_exam["revision"],
    "total_questions": sum(p["count"] for p in package_reports),
    "quiz_verified_topics": quiz_verified_topics,
    "summary_before": summary_before,
    "summary_after": summary_after,
    "packages": package_reports,
    "items": all_imported,
}, ensure_ascii=False))
"""


def merge_taxonomy(accum: dict[str, list[dict[str, Any]]], extra: dict[str, Any] | None) -> None:
    if not extra:
        return
    for key, id_field in (
        ("subjects", "subject_id"),
        ("parent_topics", "topic_id"),
        ("subtopics", "topic_id"),
    ):
        existing_ids = {row[id_field] for row in accum[key]}
        for entry in extra.get(key) or []:
            if entry[id_field] not in existing_ids:
                accum[key].append(entry)
                existing_ids.add(entry[id_field])


def load_package_file(
    file_path: str,
    *,
    cli_subject_id: str | None,
    cli_parent_id: str | None,
    cli_parent_label: str | None,
    cli_topic_id: str | None = None,
    cli_topic_label: str | None = None,
    allow_letter_prefixes: bool = False,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    path = Path(file_path)
    if not path.is_absolute():
        path = ROOT / path
    raw = json.loads(path.read_text(encoding="utf-8"))

    target: dict[str, Any] = {}
    taxonomy: dict[str, Any] = {}
    if isinstance(raw, dict) and "items" in raw:
        target = dict(raw.get("target") or {})
        taxonomy = dict(raw.get("taxonomy") or {})
        raw_items = raw["items"]
    elif isinstance(raw, list):
        raw_items = raw
    else:
        raise ValueError(f"Raiz JSON inválida em {path}: esperado objeto com 'items' ou array de questões")

    eff_subject = cli_subject_id or target.get("subject_id")
    eff_parent = cli_parent_id or target.get("parent_topic_id")
    eff_parent_label = cli_parent_label or target.get("parent_topic_label")
    eff_topic = cli_topic_id or target.get("topic_id")
    eff_topic_label = cli_topic_label or target.get("topic_label")

    if eff_subject and eff_parent and eff_parent_label:
        taxonomy.setdefault("parent_topics", []).append(
            {
                "topic_id": eff_parent,
                "subject_id": eff_subject,
                "label": eff_parent_label,
                "aliases": [],
            }
        )
    if eff_subject and eff_topic and eff_topic_label and eff_parent:
        taxonomy.setdefault("subtopics", []).append(
            {
                "topic_id": eff_topic,
                "subject_id": eff_subject,
                "parent_topic_id": eff_parent,
                "label": eff_topic_label,
                "aliases": target.get("aliases") or [],
                "syllabus_display_title": target.get("syllabus_display_title"),
            }
        )

    validated = validate_items_locally(
        raw_items,
        source_label=path.name,
        default_subject_id=eff_subject,
        default_topic_id=eff_topic,
        allow_letter_prefixes=allow_letter_prefixes,
    )
    digest = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
    pkg = {
        "package_key": f"{path.stem}-{digest}",
        "file": str(path),
        "component_id": target.get("component_id"),
        "count": len(validated),
        "chunks": chunk_items(validated),
    }
    return pkg, target, taxonomy


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", default=DEFAULT_SSH_HOST, help="Host SSH da VPS Cogniva")
    parser.add_argument("--exam-id", default=None, help="ID do template de concurso (padrão: target.exam_id ou BCB 2027)")
    parser.add_argument("--subject-id", default=None, help="subject_id canônico padrão (ex.: MFIN, ECON)")
    parser.add_argument("--parent-topic-id", default=None, help="parent_topic_id canônico padrão (ex.: ECON_01)")
    parser.add_argument("--parent-topic-label", default=None, help="Rótulo do TOPIC pai caso precise criá-lo")
    parser.add_argument(
        "--batch",
        action="append",
        default=[],
        help="Especificação rápida de lote: 'TOPIC_ID|TOPIC_LABEL|CAMINHO_JSON'",
    )
    parser.add_argument(
        "--file",
        action="append",
        default=[],
        help="Pacote JSON autodescritivo ('target'+'taxonomy'+'items') ou array com 'subject_id'/'topic_id' por item",
    )
    parser.add_argument(
        "--replace-custom-placeholders",
        action="store_true",
        help="Remove tópicos legados 'custom-*' sem canonical_topic_id no componente alvo antes de registrar os subtópicos",
    )
    parser.add_argument(
        "--validity-basis",
        choices=["FACTUAL", "LEGAL", "TECHNICAL", "TIMELESS", "NORMATIVE", "JURISPRUDENTIAL"],
        default="FACTUAL",
        help="Base de validade canônica (FACTUAL, LEGAL, TECHNICAL, TIMELESS; NORMATIVE/JURISPRUDENTIAL mapeiam para LEGAL)",
    )
    parser.add_argument(
        "--review-due-on",
        default=None,
        help="Data ISO YYYY-MM-DD de revisão obrigatória para base LEGAL (padrão automático: hoje + 365 dias quando LEGAL)",
    )
    parser.add_argument("--admin-email", default=None, help="E-mail da conta admin para emissão de sessão efêmera na VPS")
    parser.add_argument("--admin-user-id", type=int, default=None, help="ID opcional de usuário admin com sessão ativa")
    parser.add_argument("--admin-sid", default=None, help="SID opcional de sessão admin ativa")
    parser.add_argument("--acknowledge-duplicates", action="store_true", help="Autoriza gravação caso haja POTENTIAL_DUPLICATE")
    parser.add_argument("--allow-letter-prefixes", action="store_true", help="Não falha caso alternativas comecem com 'A) '")
    parser.add_argument("--no-verify-quiz", action="store_true", help="Pula sorteio de conferência /api/v1/quiz/generate pós-commit")
    parser.add_argument("--check-local", action="store_true", help="Executa apenas a validação local offline dos pacotes e encerra")
    parser.add_argument("--dry-run", action="store_true", help="Valida catálogo e preview no servidor sem gravar alterações")
    parser.add_argument("--operation-ns", default="cogniva-package-import-v2", help="Namespace determinístico UUIDv5")
    parser.add_argument("--evidence-out", default=None, help="Caminho opcional para salvar o recibo completo em JSON")
    args = parser.parse_args()

    if not args.batch and not args.file:
        parser.error("Informe ao menos um --file ou --batch")

    packages: list[dict[str, Any]] = []
    merged_taxonomy: dict[str, list[dict[str, Any]]] = {
        "subjects": [],
        "parent_topics": [],
        "subtopics": [],
    }
    inferred_exam_id: str | None = None
    inferred_replace_custom = False

    for spec in args.batch:
        parts = [p.strip() for p in spec.split("|")]
        if len(parts) == 3:
            t_id, t_label, f_path = parts
        elif len(parts) == 2:
            t_id, f_path = parts
            t_label = t_id
        else:
            parser.error(f"Formato inválido em --batch '{spec}'. Esperado 'TOPIC_ID|TOPIC_LABEL|JSON_PATH'")
        pkg, target, tax = load_package_file(
            f_path,
            cli_subject_id=args.subject_id,
            cli_parent_id=args.parent_topic_id,
            cli_parent_label=args.parent_topic_label,
            cli_topic_id=t_id,
            cli_topic_label=t_label,
            allow_letter_prefixes=args.allow_letter_prefixes,
        )
        packages.append(pkg)
        merge_taxonomy(merged_taxonomy, tax)
        inferred_exam_id = inferred_exam_id or target.get("exam_id")
        inferred_replace_custom = inferred_replace_custom or bool(target.get("replace_custom_placeholders"))

    for f_path in args.file:
        pkg, target, tax = load_package_file(
            f_path,
            cli_subject_id=args.subject_id,
            cli_parent_id=args.parent_topic_id,
            cli_parent_label=args.parent_topic_label,
            allow_letter_prefixes=args.allow_letter_prefixes,
        )
        packages.append(pkg)
        merge_taxonomy(merged_taxonomy, tax)
        inferred_exam_id = inferred_exam_id or target.get("exam_id")
        inferred_replace_custom = inferred_replace_custom or bool(target.get("replace_custom_placeholders"))

    effective_basis = normalize_validity_basis(args.validity_basis)
    effective_review_due_on = resolve_review_due_on(effective_basis, args.review_due_on)
    if (args.admin_user_id is None) ^ (args.admin_sid is None):
        parser.error("Informe ambos --admin-user-id e --admin-sid, ou omita ambos para sessão efêmera automática")

    total_items = sum(p["count"] for p in packages)
    if args.check_local:
        print(
            json.dumps(
                {
                    "local_validation": "PASSED",
                    "packages": len(packages),
                    "total_items": total_items,
                    "total_chunks": sum(len(p["chunks"]) for p in packages),
                    "taxonomy_counts": {k: len(v) for k, v in merged_taxonomy.items()},
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    exam_id = args.exam_id or inferred_exam_id or DEFAULT_EXAM_ID
    bundle = {
        "operation_ns": args.operation_ns,
        "dry_run": args.dry_run,
        "acknowledge_duplicates": args.acknowledge_duplicates,
        "verify_quiz": not args.no_verify_quiz,
        "validity_basis": effective_basis,
        "review_due_on": effective_review_due_on,
        "admin_email": args.admin_email,
        "admin_user_id": args.admin_user_id,
        "admin_sid": args.admin_sid,
        "exam_id": exam_id,
        "replace_custom_placeholders": args.replace_custom_placeholders or inferred_replace_custom,
        "taxonomy": merged_taxonomy,
        "packages": packages,
    }
    compressed = zlib.compress(json.dumps(bundle, ensure_ascii=False).encode("utf-8"), level=6)
    b64_payload = base64.b64encode(compressed).decode("ascii")
    script_source = REMOTE_RUNNER.replace("__B64_PAYLOAD__", repr(b64_payload)).encode("utf-8")

    t0 = time.time()
    ssh_cmd = [
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "StrictHostKeyChecking=yes",
        "-o",
        "ConnectTimeout=10",
        args.ssh_host,
        "python3",
        "-",
    ]
    proc = subprocess.run(ssh_cmd, input=script_source, capture_output=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr.decode("utf-8", errors="replace"))
        return proc.returncode

    result = json.loads(proc.stdout.decode("utf-8"))
    result["total_wall_seconds"] = round(time.time() - t0, 2)

    if args.evidence_out:
        out_path = Path(args.evidence_out)
        if not out_path.is_absolute():
            out_path = ROOT / out_path
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    summary = {k: v for k, v in result.items() if k != "items"}
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
