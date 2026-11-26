from __future__ import annotations

import base64
import copy
import datetime as dt
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zlib

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import cogniva_import as ci  # noqa: E402
import knowledge_catalog as kc  # noqa: E402


class _FakeHttpConnResponse:
    def __init__(self, status: int, payload: dict):
        self.status = status
        self._raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")

    def read(self) -> bytes:
        return self._raw


class CognivaImportValidationAndChunkingTest(unittest.TestCase):
    def _valid_mc(self, idx: int = 1) -> dict:
        return {
            "question_type": "MULTIPLE_CHOICE",
            "question": f"Qual é o efeito de uma elevação da taxa Selic meta no teste #{idx}?",
            "answers": [
                "Reduz o valor presente de fluxos nominais fixos.",
                "Eleva o valor presente de títulos prefixados.",
                "Elimina o risco de mercado de títulos longos.",
                "Mantém inalterada a marcação a mercado.",
                "Converte LTN em LFT automaticamente.",
            ],
            "correct_answer_indices": [0],
            "subject_id": "MFIN",
            "topic_id": "MFIN_01_004",
            "confirm_classification": True,
            "explanation": "Explicação pedagógica completa com mais de vinte caracteres.",
        }

    def _valid_tf(self, idx: int = 1) -> dict:
        return {
            "question_type": "TRUE_FALSE",
            "question": f"Julgue o item sobre liquidação financeira no STR (#{idx}): ocorre em tempo real.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [0],
            "subject_id": "MFIN",
            "topic_id": "MFIN_01_004",
            "confirm_classification": True,
            "explanation": "O STR liquida ordens irrevogáveis em tempo real.",
        }

    def _valid_open(self, idx: int = 1) -> dict:
        return {
            "question_type": "OPEN_ENDED",
            "question": f"Discorra sobre o mecanismo de transmissão da política monetária via expectativas (#{idx}).",
            "answers": [],
            "correct_answer_indices": [],
            "grading": {
                "strategy": "RUBRIC",
                "max_score": 100,
                "pass_score": 60,
                "expected_responses": [
                    "A ancoragem das expectativas desloca a curva a termo e afeta decisões de investimento."
                ],
                "rubric": {
                    "revision": 1,
                    "criteria": [
                        {
                            "criterion_id": "ANCORAGEM",
                            "description": "Explicar o papel das expectativas na estrutura a termo de juros.",
                            "max_points": 60,
                            "essential": True,
                        },
                        {
                            "criterion_id": "TRANSMISSAO",
                            "description": "Relacionar juros longos ao hiato do produto e inflação.",
                            "max_points": 40,
                            "essential": False,
                        },
                    ],
                },
                "engine": None,
                "confirmation_policy": "HUMAN_REVIEW",
                "policy_version": "open-rubric-human-review/1",
            },
            "subject_id": "ECON",
            "topic_id": "ECON_01_001",
            "confirm_classification": True,
            "explanation": "Síntese editorial detalhada para conferência dos critérios discursivos.",
        }

    def test_validity_basis_and_review_due_on_helpers(self):
        self.assertEqual(ci.normalize_validity_basis("NORMATIVE"), "LEGAL")
        self.assertEqual(ci.normalize_validity_basis("JURISPRUDENTIAL"), "LEGAL")
        self.assertEqual(ci.normalize_validity_basis("FACTUAL"), "FACTUAL")
        self.assertEqual(ci.normalize_validity_basis("TECHNICAL"), "TECHNICAL")
        self.assertEqual(ci.normalize_validity_basis("TIMELESS"), "TIMELESS")
        with self.assertRaises(ValueError):
            ci.normalize_validity_basis("UNKNOWN_BASIS")
        self.assertEqual(
            ci.resolve_review_due_on("LEGAL", None, today=dt.date(2026, 11, 21)),
            "2027-11-21",
        )
        self.assertIsNone(ci.resolve_review_due_on("FACTUAL", None, today=dt.date(2026, 11, 21)))
        self.assertNotIn("03422120-65eb-4819-9ea7-3f88fb95c37a", ci.REMOTE_RUNNER)

    def test_validate_items_locally_covers_tf_mc_open_ended_origin_and_duplicates(self):
        items = [self._valid_mc(1), self._valid_tf(2), self._valid_open(3)]
        validated = ci.validate_items_locally(items, source_label="pkg.json")
        self.assertEqual(len(validated), 3)

        # OPEN_ENDED must have answers == [] and correct_answer_indices == []
        bad_open_ans = self._valid_open(10)
        bad_open_ans["answers"] = ["Fake"]
        with self.assertRaisesRegex(ValueError, "OPEN_ENDED exige answers == \\[\\]"):
            ci.validate_items_locally([bad_open_ans], source_label="pkg.json")

        bad_open_idx = self._valid_open(11)
        bad_open_idx["correct_answer_indices"] = [0]
        with self.assertRaisesRegex(ValueError, "OPEN_ENDED exige correct_answer_indices == \\[\\]"):
            ci.validate_items_locally([bad_open_idx], source_label="pkg.json")

        # OPEN_ENDED requires valid grading with expected_responses + rubric.criteria
        bad_open_no_grading = self._valid_open(12)
        bad_open_no_grading["grading"] = None
        with self.assertRaisesRegex(ValueError, "OPEN_ENDED exige objeto grading"):
            ci.validate_items_locally([bad_open_no_grading], source_label="pkg.json")

        bad_open_no_resp = self._valid_open(13)
        bad_open_no_resp["grading"]["expected_responses"] = []
        with self.assertRaisesRegex(ValueError, "expected_responses"):
            ci.validate_items_locally([bad_open_no_resp], source_label="pkg.json")

        bad_open_no_rubric = self._valid_open(14)
        bad_open_no_rubric["grading"]["rubric"] = {"revision": 1, "criteria": []}
        with self.assertRaisesRegex(ValueError, "rubric.criteria"):
            ci.validate_items_locally([bad_open_no_rubric], source_label="pkg.json")

        # Letter-prefix rejection on MULTIPLE_CHOICE unless allow_letter_prefixes=True
        prefixed_mc = self._valid_mc(15)
        prefixed_mc["answers"][0] = "A) " + prefixed_mc["answers"][0]
        with self.assertRaisesRegex(ValueError, "prefixo de letra"):
            ci.validate_items_locally([prefixed_mc], source_label="pkg.json")
        self.assertEqual(
            len(ci.validate_items_locally([prefixed_mc], source_label="pkg.json", allow_letter_prefixes=True)),
            1,
        )

        # confirm_origin=True requires source_reference
        bad_origin = self._valid_tf(16)
        bad_origin["confirm_origin"] = True
        bad_origin["source_reference"] = None
        with self.assertRaisesRegex(ValueError, "source_reference"):
            ci.validate_items_locally([bad_origin], source_label="pkg.json")

        # Duplicate normalized question text in same package
        dup1 = self._valid_tf(17)
        dup2 = copy.deepcopy(dup1)
        dup2["question"] = "  " + dup1["question"] + "  "
        with self.assertRaisesRegex(ValueError, "enunciado duplicado"):
            ci.validate_items_locally([dup1, dup2], source_label="pkg.json")

    def test_chunk_items_splits_on_50_item_cap_and_480kib_byte_budget(self):
        # 1. Count cap (> 50 items)
        sixty_items = [self._valid_tf(i) for i in range(60)]
        by_count = ci.chunk_items(sixty_items)
        self.assertEqual([len(c) for c in by_count], [50, 10])

        # 2. Byte budget (> 480 KiB with fewer than 50 items)
        heavy_items = []
        for i in range(6):
            it = self._valid_tf(i)
            it["explanation"] = f"Bloco extenso #{i}: " + ("X" * 100_000)
            heavy_items.append(it)
        by_bytes = ci.chunk_items(heavy_items)
        self.assertGreater(len(by_bytes), 1)
        for chunk in by_bytes:
            self.assertLessEqual(len(json.dumps(chunk, ensure_ascii=False).encode("utf-8")), ci.MAX_CHUNK_BYTES)

    def test_merge_taxonomy_and_check_local_preserve_sources(self):
        accum: dict[str, list[dict]] = {"subjects": [], "parent_topics": [], "subtopics": []}
        tax1 = {
            "sources": [
                {
                    "source_id": "BCB_EDITAL_2027",
                    "kind": "EDITORIAL_PROPOSAL",
                    "reference": "Edital BCB",
                    "note": "Fonte do pacote 1",
                }
            ],
            "subjects": [{"subject_id": "MFIN", "label": "Mercado Financeiro", "trunk_links": []}],
        }
        tax2 = {
            "sources": [
                {
                    "source_id": "BCB_EDITAL_2027",
                    "kind": "EDITORIAL_PROPOSAL",
                    "reference": "Duplicada",
                    "note": "Ignorada",
                },
                {
                    "source_id": "CMN_NORMAS",
                    "kind": "REFERENCE_PORTAL",
                    "reference": "Resoluções CMN",
                    "note": "Fonte 2",
                },
            ]
        }
        ci.merge_taxonomy(accum, tax1)
        ci.merge_taxonomy(accum, tax2)
        self.assertEqual([s["source_id"] for s in accum["sources"]], ["BCB_EDITAL_2027", "CMN_NORMAS"])
        self.assertEqual([s["subject_id"] for s in accum["subjects"]], ["MFIN"])

        with tempfile.TemporaryDirectory() as tmp:
            pkg_file = Path(tmp) / "pacote.json"
            pkg_file.write_text(
                json.dumps(
                    {
                        "target": {"exam_id": "CPNU"},
                        "taxonomy": tax1,
                        "items": [self._valid_open(1)],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            buf = io.StringIO()
            with mock.patch("sys.stdout", buf):
                rc = ci.main(["--file", str(pkg_file), "--check-local"])
            self.assertEqual(rc, 0)
            report = json.loads(buf.getvalue())
            self.assertEqual(report["local_validation"], "PASSED")
            self.assertEqual(report["taxonomy_counts"]["sources"], 1)


class CognivaImportRemoteRunnerTest(unittest.TestCase):
    def _encode_bundle(self, bundle: dict) -> str:
        compressed = zlib.compress(json.dumps(bundle, ensure_ascii=False).encode("utf-8"), level=6)
        b64 = base64.b64encode(compressed).decode("ascii")
        return ci.REMOTE_RUNNER.replace("__B64_PAYLOAD__", repr(b64))

    def test_remote_runner_ephemeral_session_always_logs_out_and_closes_on_error_without_jwt_secret(self):
        bundle = {
            "operation_ns": "test-ns",
            "dry_run": True,
            "exam_id": "CPNU",
            "packages": [],
            "taxonomy": {},
        }
        script = self._encode_bundle(bundle)
        requests_seen: list[tuple[str, str, str | None]] = []
        conn_closed = {"count": 0}

        class FakeConn:
            def __init__(self, host, port, timeout=120):
                self._last_path = ""

            def connect(self):
                pass

            def request(self, method, path, body=None, headers=None):
                self._last_path = path
                requests_seen.append((method, path, (headers or {}).get("Authorization")))

            def getresponse(self):
                if self._last_path == "/api/v1/admin/summary":
                    return _FakeHttpConnResponse(500, {"error": "simulated backend failure"})
                if self._last_path == "/api/v1/auth/logout":
                    return _FakeHttpConnResponse(200, {"logged_out": True})
                return _FakeHttpConnResponse(200, {})

            def close(self):
                conn_closed["count"] += 1

        def fake_check_output(cmd, text=False):
            # Notice docker inspect is NOT called when minting ephemeral session (no JWT_SECRET needed!)
            self.assertIn("--mint-admin-token", cmd)
            return '{"user_id": 77, "token": "ephemeral-jwt-token"}'

        with mock.patch("subprocess.check_output", side_effect=fake_check_output), mock.patch(
            "http.client.HTTPConnection", FakeConn
        ):
            with self.assertRaisesRegex(RuntimeError, "HTTP 500 GET /api/v1/admin/summary"):
                exec(compile(script, "<remote_runner>", "exec"), {})

        self.assertEqual(
            requests_seen,
            [
                ("GET", "/api/v1/admin/summary", "Bearer ephemeral-jwt-token"),
                ("POST", "/api/v1/auth/logout", "Bearer ephemeral-jwt-token"),
            ],
        )
        self.assertGreaterEqual(conn_closed["count"], 1)

    def test_remote_runner_explicit_sid_reads_jwt_secret_and_skips_ephemeral_logout(self):
        catalog = kc.read(kc.ROOT / "shared/knowledge-catalog.json")
        templates = kc.read(kc.ROOT / "shared/exam-templates.json")["templates"]
        cpnu = copy.deepcopy(next(t for t in templates if t["_id"] == "CPNU"))
        subj_id = cpnu["subjects"][0]["canonical_subject_id"]
        leaf_topic = next(
            t["topic_id"]
            for t in catalog["topics"]
            if t["subject_id"] == subj_id and t["kind"] == "SUBTOPIC" and t["status"] == "ACTIVE"
        )

        item = {
            "question_type": "TRUE_FALSE",
            "question": "Julgue o item de verificação em dry-run para sessão explícita.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [0],
            "subject_id": subj_id,
            "topic_id": leaf_topic,
            "confirm_classification": True,
            "explanation": "Explicação válida com mais de vinte caracteres.",
        }
        bundle = {
            "operation_ns": "test-explicit",
            "dry_run": True,
            "verify_quiz": False,
            "admin_user_id": 1,
            "admin_sid": "sid-explicit-123",
            "exam_id": "CPNU",
            "taxonomy": {
                "sources": [
                    {
                        "source_id": "SRC_IMPORT_TEST",
                        "kind": "EDITORIAL_PROPOSAL",
                        "reference": "Teste",
                        "note": "Teste de fonte",
                    }
                ]
            },
            "packages": [{"package_key": "pkg-1", "count": 1, "chunks": [[item]]}],
        }
        script = self._encode_bundle(bundle)
        paths_called: list[str] = []

        class FakeConn:
            def __init__(self, host, port, timeout=120):
                self._path = ""

            def connect(self):
                pass

            def request(self, method, path, body=None, headers=None):
                self._path = path
                paths_called.append(path)

            def getresponse(self):
                if self._path == "/api/v1/admin/summary":
                    return _FakeHttpConnResponse(200, {"questions": 10})
                if self._path == "/api/v1/admin/catalog/base":
                    return _FakeHttpConnResponse(200, {"catalog": catalog, "sha256": "a" * 64})
                if self._path == "/api/v1/admin/catalog/validate":
                    return _FakeHttpConnResponse(200, {"valid": True})
                if self._path == "/api/v1/admin/exam-templates":
                    return _FakeHttpConnResponse(200, [cpnu])
                if self._path == "/api/v1/admin/questions/import/preview":
                    return _FakeHttpConnResponse(200, {"ready": True, "items": [{"ok": True}], "duplicate_warnings": []})
                raise AssertionError(f"Unexpected path: {self._path}")

            def close(self):
                pass

        def fake_check_output(cmd, text=False):
            self.assertEqual(cmd, ["docker", "inspect", "api-rust-core"])
            return json.dumps([{"Config": {"Env": ["JWT_SECRET=supersecretkey"]}}]).encode("utf-8")

        out = io.StringIO()
        with mock.patch("subprocess.check_output", side_effect=fake_check_output), mock.patch(
            "http.client.HTTPConnection", FakeConn
        ), mock.patch("sys.stdout", out):
            exec(compile(script, "<remote_runner>", "exec"), {})

        result = json.loads(out.getvalue())
        self.assertTrue(result["complete"])
        self.assertIn("SRC_IMPORT_TEST", result["added_catalog_ids"])
        self.assertNotIn("/api/v1/auth/logout", paths_called)

    def test_remote_runner_disambiguates_component_id_and_replaces_custom_placeholders(self):
        catalog = kc.read(kc.ROOT / "shared/knowledge-catalog.json")
        estat_leaf = next(
            t["topic_id"]
            for t in catalog["topics"]
            if t["subject_id"] == "ESTAT" and t["kind"] == "SUBTOPIC" and t["status"] == "ACTIVE"
        )
        exam_tpl = {
            "_id": "BCB2027_TEST",
            "revision": 3,
            "subjects": [
                {
                    "local_subject_id": "bcb2027-ef-RLM_EST",
                    "canonical_subject_id": "ESTAT",
                    "display_name": "Noções de lógica e estatística",
                    "topics": [
                        {
                            "local_topic_id": "rlm-custom-1",
                            "canonical_topic_id": "custom-rlm-1",
                            "canonical_subject_id": "ESTAT",
                            "display_title": "Lógica",
                            "diff_status": "PRE_EDITAL_BASE",
                        }
                    ],
                },
                {
                    "local_subject_id": "bcb2027-ef-ECONM",
                    "canonical_subject_id": "ESTAT",
                    "display_name": "Estatística e Econometria",
                    "topics": [
                        {
                            "local_topic_id": "econm-custom-1",
                            "canonical_topic_id": "custom-econm-1",
                            "canonical_subject_id": "ESTAT",
                            "display_title": "Placeholder antigo",
                            "diff_status": "PRE_EDITAL_BASE",
                        }
                    ],
                },
            ],
        }
        item = {
            "question_type": "TRUE_FALSE",
            "question": "Julgue o item de teste sobre econometria e desambiguação por component_id.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [0],
            "subject_id": "ESTAT",
            "topic_id": estat_leaf,
            "confirm_classification": True,
            "explanation": "Explicação válida com mais de vinte caracteres para teste.",
        }
        bundle = {
            "operation_ns": "test-comp-disambig",
            "dry_run": False,
            "replace_custom_placeholders": True,
            "verify_quiz": False,
            "admin_user_id": 1,
            "admin_sid": "sid-explicit-456",
            "exam_id": "BCB2027_TEST",
            "taxonomy": {},
            "packages": [
                {
                    "package_key": "pkg-econm",
                    "component_id": "bcb2027-ef-ECONM",
                    "count": 1,
                    "chunks": [[item]],
                }
            ],
        }
        script = self._encode_bundle(bundle)
        saved_templates: list[dict] = []
        committed_payloads: list[dict] = []
        compat_payloads: list[dict] = []

        class FakeConn:
            def __init__(self, host, port, timeout=120):
                self._path = ""
                self._method = ""
                self._body = None

            def connect(self):
                pass

            def request(self, method, path, body=None, headers=None):
                self._method = method
                self._path = path
                self._body = json.loads(body.decode("utf-8")) if body else None

            def getresponse(self):
                if self._path == "/api/v1/admin/summary":
                    return _FakeHttpConnResponse(200, {"questions": 11})
                if self._path == "/api/v1/admin/catalog/base":
                    return _FakeHttpConnResponse(200, {"catalog": catalog, "sha256": "b" * 64})
                if self._path == "/api/v1/admin/exam-templates" and self._method == "GET":
                    return _FakeHttpConnResponse(200, [copy.deepcopy(exam_tpl)])
                if self._path == "/api/v1/admin/exam-templates/BCB2027_TEST" and self._method == "PUT":
                    saved_templates.append(copy.deepcopy(self._body))
                    return _FakeHttpConnResponse(200, self._body)
                if self._path == "/api/v1/exams/BCB2027_TEST/adopt-template":
                    return _FakeHttpConnResponse(200, {"_id": "BCB2027_TEST", "revision": 4})
                if self._path == "/api/v1/admin/questions/import/preview":
                    return _FakeHttpConnResponse(
                        200,
                        {
                            "ready": True,
                            "idempotency_key": "idem-1",
                            "duplicate_warnings": [],
                            "items": [
                                {
                                    "ok": True,
                                    "command": {
                                        "operation_id": "op-1",
                                        "document": {
                                            "_id": {"$oid": "65f000000000000000000001"},
                                            "content_revision": "crev-1",
                                            "semantic_revision": "srev-1",
                                            "classification": {
                                                "revision": 1,
                                                "links": [{"subject_id": "ESTAT", "topic_id": estat_leaf}],
                                            },
                                        },
                                    },
                                }
                            ],
                        },
                    )
                if self._path == "/api/v1/admin/questions/import/commit":
                    committed_payloads.append(copy.deepcopy(self._body))
                    status_str = "created" if len(committed_payloads) == 1 else "already_applied"
                    return _FakeHttpConnResponse(
                        200,
                        {
                            "complete": True,
                            "items": [{"saved": True, "receipt": {"status": status_str}}],
                        },
                    )
                if self._path == "/api/v1/exams/BCB2027_TEST/compatibility":
                    compat_payloads.append(copy.deepcopy(self._body))
                    return _FakeHttpConnResponse(
                        200,
                        {
                            "_id": "compat-1",
                            "question_id": "65f000000000000000000001",
                            "status": "CONFIRMED",
                            "relation": "DIRECT",
                        },
                    )
                raise AssertionError(f"Unexpected request: {self._method} {self._path}")

            def close(self):
                pass

        def fake_check_output(cmd, text=False):
            return json.dumps([{"Config": {"Env": ["JWT_SECRET=supersecretkey"]}}]).encode("utf-8")

        out = io.StringIO()
        with mock.patch("subprocess.check_output", side_effect=fake_check_output), mock.patch(
            "http.client.HTTPConnection", FakeConn
        ), mock.patch("sys.stdout", out):
            exec(compile(script, "<remote_runner>", "exec"), {})

        self.assertEqual(len(saved_templates), 1)
        rlm_subj = saved_templates[0]["subjects"][0]
        econm_subj = saved_templates[0]["subjects"][1]
        # RLM_EST was NOT touched; ECONM had custom-* stripped and leaf topic added
        self.assertEqual([t["canonical_topic_id"] for t in rlm_subj["topics"]], ["custom-rlm-1"])
        self.assertEqual([t["canonical_topic_id"] for t in econm_subj["topics"]], [estat_leaf])
        # Committed question got ECONM's display_name and DIRECT compatibility got ECONM's component_id
        doc = committed_payloads[0]["commands"][0]["document"]
        self.assertEqual(doc["disciplina"], "Estatística e Econometria")
        self.assertEqual(compat_payloads[0]["component_id"], "bcb2027-ef-ECONM")

    def test_remote_runner_reuses_synced_user_exam_revision_when_template_unmodified(self):
        catalog = kc.read(kc.ROOT / "shared/knowledge-catalog.json")
        estat_leaf = next(
            t["topic_id"]
            for t in catalog["topics"]
            if t["subject_id"] == "ESTAT" and t["kind"] == "SUBTOPIC" and t["status"] == "ACTIVE"
        )
        exam_tpl = {
            "_id": "BCB2027_SYNCED",
            "revision": 14,
            "subjects": [
                {
                    "local_subject_id": "bcb2027-ef-ECONM",
                    "canonical_subject_id": "ESTAT",
                    "display_name": "Estatística e Econometria",
                    "topics": [
                        {
                            "local_topic_id": "econm-1",
                            "canonical_topic_id": estat_leaf,
                            "canonical_subject_id": "ESTAT",
                            "display_title": "Tópico já sincronizado",
                            "diff_status": "PRE_EDITAL_BASE",
                        }
                    ],
                }
            ],
        }
        item = {
            "question_type": "TRUE_FALSE",
            "question": "Julgue o item de teste quando a grade já está sincronizada na revisão vigente.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [0],
            "subject_id": "ESTAT",
            "topic_id": estat_leaf,
            "confirm_classification": True,
            "explanation": "Explicação válida com mais de vinte caracteres para teste.",
        }
        bundle = {
            "operation_ns": "test-synced-no-bump",
            "dry_run": False,
            "replace_custom_placeholders": False,
            "verify_quiz": False,
            "admin_user_id": 1,
            "admin_sid": "sid-explicit-789",
            "exam_id": "BCB2027_SYNCED",
            "taxonomy": {},
            "packages": [
                {
                    "package_key": "pkg-synced",
                    "component_id": "bcb2027-ef-ECONM",
                    "count": 1,
                    "chunks": [[item]],
                }
            ],
        }
        script = self._encode_bundle(bundle)
        adopt_calls = {"count": 0}
        commit_calls = {"count": 0}

        class FakeConn:
            def __init__(self, host, port, timeout=120):
                self._path = ""

            def connect(self):
                pass

            def request(self, method, path, body=None, headers=None):
                self._path = path

            def getresponse(self):
                if self._path == "/api/v1/admin/summary":
                    return _FakeHttpConnResponse(200, {"questions": 11})
                if self._path == "/api/v1/admin/catalog/base":
                    return _FakeHttpConnResponse(200, {"catalog": catalog, "sha256": "c" * 64})
                if self._path == "/api/v1/admin/exam-templates":
                    return _FakeHttpConnResponse(200, [copy.deepcopy(exam_tpl)])
                if self._path == "/api/v1/exams":
                    return _FakeHttpConnResponse(
                        200,
                        [
                            {
                                "_id": "BCB2027_SYNCED",
                                "revision": 7,
                                "sync_status": "SYNCED",
                                "base_template_revision": 14,
                            }
                        ],
                    )
                if self._path == "/api/v1/exams/BCB2027_SYNCED/adopt-template":
                    adopt_calls["count"] += 1
                    return _FakeHttpConnResponse(200, {"_id": "BCB2027_SYNCED", "revision": 8})
                if self._path == "/api/v1/admin/questions/import/preview":
                    return _FakeHttpConnResponse(
                        200,
                        {
                            "ready": True,
                            "idempotency_key": "idem-2",
                            "duplicate_warnings": [],
                            "items": [
                                {
                                    "ok": True,
                                    "command": {
                                        "operation_id": "op-2",
                                        "document": {
                                            "_id": {"$oid": "65f000000000000000000002"},
                                            "content_revision": "crev-2",
                                            "semantic_revision": "srev-2",
                                            "classification": {
                                                "revision": 1,
                                                "links": [{"subject_id": "ESTAT", "topic_id": estat_leaf}],
                                            },
                                        },
                                    },
                                }
                            ],
                        },
                    )
                if self._path == "/api/v1/admin/questions/import/commit":
                    commit_calls["count"] += 1
                    st = "created" if commit_calls["count"] == 1 else "already_applied"
                    return _FakeHttpConnResponse(
                        200,
                        {"complete": True, "items": [{"saved": True, "receipt": {"status": st}}]},
                    )
                if self._path == "/api/v1/exams/BCB2027_SYNCED/compatibility":
                    return _FakeHttpConnResponse(
                        200,
                        {
                            "_id": "compat-2",
                            "question_id": "65f000000000000000000002",
                            "status": "CONFIRMED",
                            "relation": "DIRECT",
                        },
                    )
                raise AssertionError(f"Unexpected path: {self._path}")

            def close(self):
                pass

        def fake_check_output(cmd, text=False):
            return json.dumps([{"Config": {"Env": ["JWT_SECRET=supersecretkey"]}}]).encode("utf-8")

        out = io.StringIO()
        with mock.patch("subprocess.check_output", side_effect=fake_check_output), mock.patch(
            "http.client.HTTPConnection", FakeConn
        ), mock.patch("sys.stdout", out):
            exec(compile(script, "<remote_runner>", "exec"), {})

        res = json.loads(out.getvalue())
        self.assertEqual(adopt_calls["count"], 0)
        self.assertEqual(res["user_exam_revision"], 7)


if __name__ == "__main__":
    unittest.main()
