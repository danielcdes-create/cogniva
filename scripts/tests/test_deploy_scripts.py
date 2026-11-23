from __future__ import annotations

import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest import mock
import urllib.error

ROOT = Path(__file__).resolve().parents[2]


def load_module(module_name: str, rel_path: str):
    path = ROOT / rel_path
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load module spec for {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class _FakeHttpResponse:
    def __init__(self, body: bytes | str | dict | list, status: int = 200, headers: dict[str, str] | None = None):
        if isinstance(body, (dict, list)):
            raw = json.dumps(body).encode("utf-8")
        elif isinstance(body, str):
            raw = body.encode("utf-8")
        else:
            raw = body
        self._stream = io.BytesIO(raw)
        self.status = status
        self.headers = headers or {}

    def read(self, size: int = -1) -> bytes:
        return self._stream.read(size)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


class DeployWorkflowAndNginxConfigTest(unittest.TestCase):
    def test_deploy_workflow_verifies_bundle_checksums_and_rollback(self):
        workflow = (ROOT / ".github/workflows/deploy.yml").read_text(encoding="utf-8")
        self.assertNotIn("--exercise-rollback || true", workflow)
        self.assertIn("--exercise-rollback", workflow)
        self.assertIn("sha256sum -c SHA256SUMS", workflow)

    def test_validate_workflow_enforces_clippy_zero_warnings_and_scoped_skip_ci(self):
        workflow = (ROOT / ".github/workflows/validate.yml").read_text(encoding="utf-8")
        self.assertIn("cargo clippy --all-targets --locked -- -D warnings", workflow)
        self.assertIn("github.event_name != 'push'", workflow)

    def test_emulator_workflow_avoids_world_writable_kvm(self):
        workflow = (ROOT / ".github/workflows/emulator-insets.yml").read_text(encoding="utf-8")
        self.assertNotIn('MODE="0666"', workflow)
        self.assertIn('MODE="0660"', workflow)

    def test_nginx_conf_rate_limits_and_tls_bootstrap_preserve_contracts(self):
        nginx = (ROOT / "deploy/nginx-cogniva.conf").read_text(encoding="utf-8")
        rates = (ROOT / "deploy/nginx-rate-limits.conf").read_text(encoding="utf-8")
        bootstrap = (ROOT / "deploy/bootstrap-ip-tls.sh").read_text(encoding="utf-8")

        self.assertIn("admin/questions/import/assist", nginx)
        self.assertIn(
            "location ~ ^/api/v1/auth/(login|register|guest|logout|web-session|web-logout|account|native-session|missions-session|missions-register|missions-logout)$",
            nginx,
        )
        self.assertIn('Retry-After "15"', nginx)
        self.assertIn('Strict-Transport-Security "max-age=604800"', nginx)
        self.assertIn("proxy_hide_header X-Content-Type-Options;", nginx)
        self.assertIn("proxy_hide_header Referrer-Policy;", nginx)
        self.assertIn("charset utf-8;", nginx)
        self.assertIn("cogniva_apk", nginx)
        self.assertIn("cogniva_ai_mut", nginx)
        self.assertIn("cogniva_media", nginx)

        # SIGHUP-safe exact key -> shared-memory zone bindings
        self.assertIn("limit_req_zone $cogniva_limit_key zone=cogniva_ai:4m rate=2r/s;", rates)
        self.assertIn("limit_req_zone $cogniva_ai_limit_key zone=cogniva_ai_mut:4m rate=2r/s;", rates)
        self.assertIn("limit_req_zone $cogniva_limit_key zone=cogniva_auth:4m rate=15r/m;", rates)
        self.assertIn("limit_req_zone $cogniva_limit_key zone=cogniva_apk:4m rate=6r/m;", rates)
        self.assertIn("limit_req_zone $cogniva_limit_key zone=cogniva_media:4m rate=60r/s;", rates)
        self.assertIn("ipv6_subnet", rates)

        # TLS bootstrap backs up and restores both site and rate-limits on nginx -t failure
        self.assertIn("-checkend 216000", bootstrap)
        self.assertIn('cp -a /etc/nginx/conf.d/cogniva-rate-limits.conf "$backup/cogniva-rate-limits.conf"', bootstrap)
        self.assertIn('cp -a "$backup/cogniva-rate-limits.conf" /etc/nginx/conf.d/cogniva-rate-limits.conf', bootstrap)


class SyncNginxSiteTest(unittest.TestCase):
    def setUp(self):
        self.sync_mod = load_module("cogniva_sync_nginx_site", "deploy/sync_nginx_site.py")
        self.site_tpl_text = (ROOT / "deploy/nginx-cogniva.conf").read_text(encoding="utf-8")
        self.rates_tpl_text = (ROOT / "deploy/nginx-rate-limits.conf").read_text(encoding="utf-8")

    def _write_templates(self, folder: Path) -> tuple[Path, Path]:
        tpl = folder / "nginx-cogniva.conf.tpl"
        rates_tpl = folder / "nginx-rate-limits.conf.tpl"
        tpl.write_text(self.site_tpl_text, encoding="utf-8")
        rates_tpl.write_text(self.rates_tpl_text, encoding="utf-8")
        return tpl, rates_tpl

    def test_preserves_ipv4_ipv6_server_name_cleans_stale_gate_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            app_root = root / "opt-apps"
            site = root / "sites-available" / "cogniva"
            rates_dest = root / "conf.d" / "cogniva-rate-limits.conf"
            gate = root / "cogniva-gate.conf"
            site.parent.mkdir(parents=True)
            rates_dest.parent.mkdir(parents=True)

            site.write_text(
                "server {\n"
                "    listen 203.0.113.77:443 ssl default_server;\n"
                "    listen [2001:db8::77]:443 ssl default_server;\n"
                "    server_name app.cogniva.example;\n"
                "}\n",
                encoding="utf-8",
            )
            gate.write_text("set $cogniva_maintenance 1;\n", encoding="utf-8")

            tpl, rates_tpl = self._write_templates(root)
            calls: list[list[str]] = []

            def fake_call(cmd):
                calls.append(list(cmd))
                return 0

            def fake_check_call(cmd):
                calls.append(list(cmd))

            with mock.patch.object(self.sync_mod.subprocess, "call", side_effect=fake_call), mock.patch.object(
                self.sync_mod.subprocess, "check_call", side_effect=fake_check_call
            ):
                changed = self.sync_mod.sync_nginx_site(
                    tpl,
                    rates_tpl,
                    site=site,
                    rates_dest=rates_dest,
                    gate_path=gate,
                    app_root=app_root,
                )

            self.assertTrue(changed)
            self.assertFalse(tpl.exists())
            self.assertFalse(rates_tpl.exists())
            self.assertEqual(calls, [["nginx", "-t"], ["systemctl", "reload", "nginx"]])
            rendered = site.read_text(encoding="utf-8")
            self.assertIn("server_name app.cogniva.example;", rendered)
            self.assertIn("listen 203.0.113.77:443 ssl default_server;", rendered)
            self.assertIn("listen [2001:db8::77]:443 ssl default_server;", rendered)
            self.assertEqual(rates_dest.read_text(encoding="utf-8"), self.rates_tpl_text)
            self.assertEqual(gate.read_text(encoding="utf-8"), "set $cogniva_maintenance 0;\n")
            self.assertTrue((app_root / "cogniva-nginx.bak").is_file())

            # Second run with identical templates and clean gate is a strict no-op without reload
            tpl2, rates_tpl2 = self._write_templates(root)
            calls.clear()
            with mock.patch.object(self.sync_mod.subprocess, "call", side_effect=fake_call), mock.patch.object(
                self.sync_mod.subprocess, "check_call", side_effect=fake_check_call
            ):
                changed_second = self.sync_mod.sync_nginx_site(
                    tpl2,
                    rates_tpl2,
                    site=site,
                    rates_dest=rates_dest,
                    gate_path=gate,
                    app_root=app_root,
                )
            self.assertFalse(changed_second)
            self.assertEqual(calls, [])
            self.assertFalse(tpl2.exists())
            self.assertFalse(rates_tpl2.exists())

    def test_disabled_ipv6_comment_matches_bootstrap_ip_tls(self):
        rendered = self.sync_mod.render_site_config(
            self.site_tpl_text,
            "server { listen 198.199.65.103:443 ssl default_server; server_name 198.199.65.103; }",
        )
        self.assertIn("# IPv6 SSL disabled (no IPv6 SAN configured)", rendered)

    def test_rolls_back_site_rates_and_gate_on_nginx_test_or_reload_failure_and_unlinks_tpl(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            app_root = root / "opt-apps"
            site = root / "sites-available" / "cogniva"
            rates_dest = root / "conf.d" / "cogniva-rate-limits.conf"
            gate = root / "cogniva-gate.conf"
            site.parent.mkdir(parents=True)
            rates_dest.parent.mkdir(parents=True)

            orig_site = "server { listen 198.199.65.103:443 ssl; server_name 198.199.65.103; }\n"
            site.write_text(orig_site, encoding="utf-8")
            gate.write_text("set $cogniva_maintenance 1;\n", encoding="utf-8")

            # Case 1: nginx -t fails when rates_dest did not previously exist -> removes rates_dest and restores site & gate
            tpl1, rates_tpl1 = self._write_templates(root)
            with mock.patch.object(self.sync_mod.subprocess, "call", return_value=1):
                with self.assertRaises(SystemExit):
                    self.sync_mod.sync_nginx_site(
                        tpl1,
                        rates_tpl1,
                        site=site,
                        rates_dest=rates_dest,
                        gate_path=gate,
                        app_root=app_root,
                    )
            self.assertEqual(site.read_text(encoding="utf-8"), orig_site)
            self.assertFalse(rates_dest.exists())
            self.assertEqual(gate.read_text(encoding="utf-8"), "set $cogniva_maintenance 1;\n")
            self.assertFalse(tpl1.exists())
            self.assertFalse(rates_tpl1.exists())

            # Case 2: systemctl reload nginx fails when rates_dest DID exist -> restores previous rates_dest, site, and gate
            orig_rates = "# previous rate limits\n"
            rates_dest.write_text(orig_rates, encoding="utf-8")
            tpl2, rates_tpl2 = self._write_templates(root)
            with mock.patch.object(self.sync_mod.subprocess, "call", return_value=0), mock.patch.object(
                self.sync_mod.subprocess,
                "check_call",
                side_effect=subprocess.CalledProcessError(1, ["systemctl", "reload", "nginx"]),
            ):
                with self.assertRaises(SystemExit):
                    self.sync_mod.sync_nginx_site(
                        tpl2,
                        rates_tpl2,
                        site=site,
                        rates_dest=rates_dest,
                        gate_path=gate,
                        app_root=app_root,
                    )
            self.assertEqual(site.read_text(encoding="utf-8"), orig_site)
            self.assertEqual(rates_dest.read_text(encoding="utf-8"), orig_rates)
            self.assertEqual(gate.read_text(encoding="utf-8"), "set $cogniva_maintenance 1;\n")
            self.assertFalse(tpl2.exists())
            self.assertFalse(rates_tpl2.exists())


class StageBundleTest(unittest.TestCase):
    def setUp(self):
        self.stage_mod = load_module("cogniva_stage_bundle", "deploy/stage_bundle.py")

    def test_initial_stage_idempotent_replay_and_immutability_guards(self):
        rev = "a" * 40
        with tempfile.TemporaryDirectory() as tmp:
            app_root = Path(tmp) / "opt-apps"
            incoming1 = app_root / "cogniva-incoming" / "run-1"
            incoming1.mkdir(parents=True)
            manifest_bytes = json.dumps({"revision": rev}).encode("utf-8")
            (incoming1 / "release.json").write_bytes(manifest_bytes)
            (incoming1 / "cogniva.apk").write_bytes(b"signed-apk-v1")

            with mock.patch.dict(os.environ, {"COGNIVA_APP_ROOT": str(app_root)}):
                rc = self.stage_mod.main([str(incoming1)])
            self.assertEqual(rc, 0)
            target = app_root / "cogniva-releases" / rev
            self.assertTrue(target.is_dir())
            self.assertFalse(incoming1.exists())
            self.assertEqual((target / "cogniva.apk").read_bytes(), b"signed-apk-v1")

            # Idempotent replay with identical bytes cleans incoming directory
            incoming2 = app_root / "cogniva-incoming" / "run-2"
            incoming2.mkdir(parents=True)
            (incoming2 / "release.json").write_bytes(manifest_bytes)
            (incoming2 / "cogniva.apk").write_bytes(b"signed-apk-v1")
            replayed = self.stage_mod.stage_bundle(incoming2, app_root=app_root)
            self.assertEqual(replayed, target)
            self.assertFalse(incoming2.exists())

            # Mutated bytes for an existing immutable revision must fail loudly
            incoming3 = app_root / "cogniva-incoming" / "run-3"
            incoming3.mkdir(parents=True)
            (incoming3 / "release.json").write_bytes(manifest_bytes)
            (incoming3 / "cogniva.apk").write_bytes(b"tampered-apk-bytes")
            with self.assertRaisesRegex(AssertionError, "Immutable bundle already exists"):
                self.stage_mod.stage_bundle(incoming3, app_root=app_root)

            # Staging from outside cogniva-incoming must fail
            outside = app_root / "outside-dir"
            outside.mkdir(parents=True)
            (outside / "release.json").write_bytes(manifest_bytes)
            with self.assertRaisesRegex(AssertionError, "Unexpected staging parent"):
                self.stage_mod.stage_bundle(outside, app_root=app_root)


class PromoteScriptTest(unittest.TestCase):
    def setUp(self):
        self.promote = load_module("cogniva_promote", "deploy/promote.py")

    def _prepare_env(self, root: Path, candidate_rev: str | None = None, *, image_override: str | None = None):
        rev = candidate_rev or ("b" * 40)
        root.mkdir(parents=True, exist_ok=True)
        self.promote.ROOT = root
        self.promote.COMPOSE = root / "docker-compose.yml"
        self.promote.OVERRIDE = root / "cogniva-runtime.override.json"
        self.promote.LOCK_PATH = root / ".cogniva-deploy.lock"
        self.promote.GATE_PATH = root / "cogniva-gate.conf"
        self.promote.PROJECT = "apps"
        self.promote.SERVICE = "api-rust-core"
        self.promote.LOCAL_API = "http://127.0.0.1:18001"
        self.promote.PUBLIC_ORIGIN = "https://198.199.65.103"
        self.promote.SKIP_NGINX_GATE = False

        self.promote.COMPOSE.write_text("services:\n  api-rust-core:\n    image: old\n", encoding="utf-8")
        self.promote.GATE_PATH.write_text("set $cogniva_maintenance 0;\n", encoding="utf-8")
        (root / "missoes" / "videos").mkdir(parents=True, exist_ok=True)
        (root / "apk" / "releases").mkdir(parents=True, exist_ok=True)

        rel_dir = root / "cogniva-releases" / rev
        rel_dir.mkdir(parents=True, exist_ok=True)
        apk_bytes = b"verified-release-apk-binary"
        apk_sha = hashlib.sha256(apk_bytes).hexdigest()
        (rel_dir / "cogniva.apk").write_bytes(apk_bytes)
        (rel_dir / "output-metadata.json").write_text(
            json.dumps({"elements": [{"versionCode": 32, "versionName": "2.4.2"}]}),
            encoding="utf-8",
        )
        candidate_img = image_override or (
            "ghcr.io/danielcdes-create/cogniva-api@sha256:" + ("c" * 64)
        )
        manifest = {
            "revision": rev,
            "image": candidate_img,
            "version_code": 32,
            "version_name": "2.4.2",
            "apk_sha256": apk_sha,
            "apk_size_bytes": len(apk_bytes),
            "contract_version": 5,
        }
        (rel_dir / "release.json").write_text(json.dumps(manifest), encoding="utf-8")
        return rel_dir, manifest

    def test_prune_old_releases_preserves_active_previous_and_three_latest(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            self.promote.ROOT = base
            rels = base / "cogniva-releases"
            apks = base / "apk" / "releases"
            incoming = base / "cogniva-incoming"
            rels.mkdir(parents=True)
            apks.mkdir(parents=True)
            incoming.mkdir(parents=True)
            shas = [f"{i:040x}" for i in range(1, 7)]
            now = time.time()
            for idx, rev in enumerate(shas):
                for folder in (rels / rev, apks / rev):
                    folder.mkdir()
                    (folder / "marker").write_text(rev, encoding="utf-8")
                    os.utime(folder, (now - (10 - idx) * 60, now - (10 - idx) * 60))
            stale_in = incoming / "stale-run"
            stale_in.mkdir()
            os.utime(stale_in, (now - 7200, now - 7200))
            fresh_in = incoming / "fresh-run"
            fresh_in.mkdir()
            self.promote.prune_old_releases({shas[0], shas[5]})
            remaining = {p.name for p in rels.iterdir()}
            self.assertEqual(remaining, {shas[0], shas[3], shas[4], shas[5]})
            self.assertEqual({p.name for p in apks.iterdir()}, remaining)
            self.assertFalse(stale_in.exists())
            self.assertTrue(fresh_in.exists())

    def test_snapshot_persistent_neighbors_covers_all_three_exclusion_branches_independently(self):
        inspect_map = {
            "self001": {
                "Id": "id-self",
                "Name": "/api-rust-core",
                "HostConfig": {"AutoRemove": False},
                "Config": {"Image": "ghcr.io/danielcdes-create/cogniva-api:latest"},
            },
            "autorm002": {
                "Id": "id-autorm",
                "Name": "/ephemeral-worker",
                "HostConfig": {"AutoRemove": True},
                "Config": {"Image": "alpine:3.20"},
            },
            "certbot003": {
                "Id": "id-certbot",
                "Name": "/certbot-manual",
                "HostConfig": {"AutoRemove": False},
                "Config": {"Image": "certbot/certbot:v5.4.0"},
            },
            "neighbor004": {
                "Id": "full-persistent-456",
                "Name": "/financial-twin-server",
                "HostConfig": {"AutoRemove": False},
                "Config": {"Image": "twin:latest"},
            },
        }

        def fake_run(*args):
            if args == ("docker", "ps", "-q"):
                return "vanished000\nself001\nautorm002\ncertbot003\nneighbor004\n"
            if args[0:2] == ("docker", "inspect"):
                cid = args[2]
                if cid == "vanished000":
                    raise subprocess.CalledProcessError(1, args, "No such object")
                return json.dumps([inspect_map[cid]])
            raise AssertionError(f"Unexpected command: {args}")

        with mock.patch.object(self.promote, "run", side_effect=fake_run):
            self.assertEqual(
                self.promote.snapshot_persistent_neighbors(),
                {"/financial-twin-server": "full-persistent-456"},
            )

    def test_link_or_copy_verifies_immutable_digest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "src.apk"
            dst = root / "dst.apk"
            src.write_bytes(b"immutable-apk-payload")
            self.promote.link_or_copy(src, dst)
            self.assertEqual(dst.read_bytes(), b"immutable-apk-payload")
            self.promote.link_or_copy(src, dst)
            dst.unlink()
            dst.write_bytes(b"tampered")
            with self.assertRaises(AssertionError):
                self.promote.link_or_copy(src, dst)

    def test_preflight_guards_reject_mutable_tag_oversized_runtime_and_tampered_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # 1. Mutable tag without @sha256:
            rel_bad_img, _ = self._prepare_env(
                root / "c1",
                image_override="ghcr.io/danielcdes-create/cogniva-api:latest",
            )
            with self.assertRaisesRegex(AssertionError, "Immutable digest required"):
                self.promote._promote_locked(rel_bad_img)

            # 2. Tampered APK bytes
            rel_bad_apk, _ = self._prepare_env(root / "c2")
            (rel_bad_apk / "cogniva.apk").write_bytes(b"corrupted")
            with self.assertRaises(AssertionError):
                self.promote._promote_locked(rel_bad_apk)

            # 3. Runtime >= 50 MB
            rel_big, manifest_big = self._prepare_env(root / "c3")
            old_digest = "ghcr.io/danielcdes-create/cogniva-api@sha256:" + ("1" * 64)

            def fake_run_big(*args):
                if args == ("docker", "inspect", "api-rust-core"):
                    return json.dumps(
                        [
                            {
                                "Image": "sha256:oldimg",
                                "Config": {
                                    "Image": "ghcr.io/danielcdes-create/cogniva-api:oldtag",
                                    "Labels": {
                                        "com.docker.compose.project": "apps",
                                        "com.docker.compose.service": "api-rust-core",
                                    },
                                },
                            }
                        ]
                    )
                if args == ("docker", "ps", "-q"):
                    return ""
                if args == ("docker", "image", "inspect", "sha256:oldimg"):
                    return json.dumps([{"RepoDigests": [old_digest]}])
                if args == ("docker", "pull", manifest_big["image"]):
                    return "pulled"
                if args == ("docker", "image", "inspect", manifest_big["image"]):
                    return json.dumps([{"Size": 50_000_000}])
                raise AssertionError(f"Unexpected cmd: {args}")

            with mock.patch.object(self.promote, "run", side_effect=fake_run_big):
                with self.assertRaisesRegex(AssertionError, "Runtime exceeds 50 MB"):
                    self.promote._promote_locked(rel_big)

    def test_pre_mutation_failure_restores_gate_and_reloads_nginx_without_masking_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            rel_dir, manifest = self._prepare_env(Path(tmp))
            old_digest = "ghcr.io/danielcdes-create/cogniva-api@sha256:" + ("1" * 64)
            commands: list[tuple[str, ...]] = []

            def fake_run(*args):
                commands.append(args)
                if args == ("docker", "inspect", "api-rust-core"):
                    return json.dumps(
                        [
                            {
                                "Image": "sha256:oldimg",
                                "Config": {
                                    "Image": "ghcr.io/danielcdes-create/cogniva-api:oldtag",
                                    "Labels": {
                                        "com.docker.compose.project": "apps",
                                        "com.docker.compose.service": "api-rust-core",
                                    },
                                },
                            }
                        ]
                    )
                if args == ("docker", "ps", "-q"):
                    return ""
                if args == ("docker", "image", "inspect", "sha256:oldimg"):
                    return json.dumps([{"RepoDigests": [old_digest]}])
                if args == ("docker", "image", "inspect", manifest["image"]):
                    return json.dumps([{"Size": 24_000_000}])
                if args in (("nginx", "-t"), ("systemctl", "reload", "nginx")):
                    return ""
                raise AssertionError(f"Unexpected command: {args}")

            # Gate never observes HTTP 503 -> raises RuntimeError BEFORE runtime_mutated=True
            with mock.patch.dict(os.environ, {"COGNIVA_SKIP_PULL": "1"}), mock.patch.object(
                self.promote, "run", side_effect=fake_run
            ), mock.patch.object(
                self.promote.urllib.request, "urlopen", side_effect=OSError("connection refused")
            ), mock.patch.object(self.promote.time, "sleep"):
                with self.assertRaisesRegex(RuntimeError, "Nginx gate did not reach HTTP 503"):
                    self.promote._promote_locked(rel_dir)

            self.assertEqual(self.promote.GATE_PATH.read_text(encoding="utf-8"), "set $cogniva_maintenance 0;\n")
            # Reload called once on gate(True) and once on recovery in `if not runtime_mutated:`!
            reload_calls = [c for c in commands if c == ("systemctl", "reload", "nginx")]
            self.assertEqual(len(reload_calls), 2)

    def test_promote_locked_happy_path_preserves_env_file_and_records_immutable_previous_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rel_dir, manifest = self._prepare_env(root)
            old_rev = "1" * 40
            old_digest = "ghcr.io/danielcdes-create/cogniva-api@sha256:" + ("2" * 64)
            self.promote.OVERRIDE.write_text(
                json.dumps({"services": {"api-rust-core": {"image": "old", "env_file": [".env.rust"]}}}),
                encoding="utf-8",
            )
            symlinks: dict[Path, str] = {root / "apk" / "current": f"releases/{old_rev}"}

            def fake_symlink(target: str, path: Path):
                symlinks[path] = target

            def fake_run(*args):
                if args == ("docker", "inspect", "api-rust-core"):
                    return json.dumps(
                        [
                            {
                                "Image": "sha256:oldimg",
                                "Config": {
                                    "Image": "ghcr.io/danielcdes-create/cogniva-api:mutable-tag",
                                    "Labels": {
                                        "com.docker.compose.project": "apps",
                                        "com.docker.compose.service": "api-rust-core",
                                    },
                                },
                            }
                        ]
                    )
                if args == ("docker", "ps", "-q"):
                    return "sib1\n"
                if args in (("docker", "inspect", "sib1"), ("docker", "inspect", "/sib")):
                    return json.dumps(
                        [
                            {
                                "Id": "sib-id-1",
                                "Name": "/sib",
                                "HostConfig": {"AutoRemove": False},
                                "Config": {"Image": "postgres:16"},
                            }
                        ]
                    )
                if args == ("docker", "image", "inspect", "sha256:oldimg"):
                    return json.dumps([{"RepoDigests": [old_digest]}])
                if args == ("docker", "image", "inspect", manifest["image"]):
                    return json.dumps([{"Size": 28_500_000}])
                if args[0:2] == ("docker", "compose"):
                    return "recreated"
                if args == ("docker", "exec", "api-rust-core", "/usr/local/bin/cogniva-api", "--prepare-storage"):
                    return '{"prepared":true}'
                if args in (("nginx", "-t"), ("systemctl", "reload", "nginx")):
                    return ""
                raise AssertionError(f"Unexpected cmd: {args}")

            gate_states: list[str] = []

            def fake_urlopen(url: str, timeout: int = 5):
                if url.endswith("/api/v1/exams"):
                    maint = "1;" in self.promote.GATE_PATH.read_text(encoding="utf-8")
                    code = 503 if maint else 401
                    gate_states.append(str(code))
                    raise urllib.error.HTTPError(url, code, "gate", {}, None)
                if url.endswith("/api/v1/ready"):
                    return _FakeHttpResponse({"status": "ready", "revision": rel_dir.name})
                if url.endswith("/api/v1/apk/info"):
                    return _FakeHttpResponse(
                        {"sha256_checksum": manifest["apk_sha256"], "version_code": manifest["version_code"]}
                    )
                if url.endswith("/api/v1/health"):
                    return _FakeHttpResponse({"status": "ok", "revision": rel_dir.name})
                raise AssertionError(f"Unexpected URL: {url}")

            with mock.patch.dict(os.environ, {"COGNIVA_SKIP_PULL": "1"}), mock.patch.object(
                self.promote, "run", side_effect=fake_run
            ), mock.patch.object(self.promote, "symlink", side_effect=fake_symlink), mock.patch.object(
                self.promote.os, "readlink", side_effect=lambda p: symlinks[Path(p)]
            ), mock.patch.object(
                pathlib_cls := type(rel_dir),
                "is_symlink",
                autospec=True,
                side_effect=lambda p: p in symlinks,
            ), mock.patch.object(
                self.promote.urllib.request, "urlopen", side_effect=fake_urlopen
            ), mock.patch.object(self.promote.time, "sleep"):
                self.promote._promote_locked(rel_dir, exercise_rollback=False)

            _ = pathlib_cls
            written_override = json.loads(self.promote.OVERRIDE.read_text(encoding="utf-8"))
            svc_cfg = written_override["services"]["api-rust-core"]
            self.assertEqual(svc_cfg["env_file"], [".env.rust"])
            self.assertIn("volumes", svc_cfg)

            current_release = json.loads((root / "cogniva-current-release.json").read_text(encoding="utf-8"))
            self.assertEqual(current_release["previous_image"], old_digest)
            self.assertIn("@sha256:", current_release["previous_image"])
            self.assertEqual(gate_states, ["503", "401"])
            self.assertEqual(symlinks[root / "apk" / "current"], f"releases/{rel_dir.name}")

    def test_exercise_rollback_and_post_mutation_failure_restore_previous_runtime(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rel_dir, manifest = self._prepare_env(root)
            self.promote.SKIP_NGINX_GATE = True
            old_rev = "9" * 40
            old_digest = "ghcr.io/danielcdes-create/cogniva-api@sha256:" + ("8" * 64)
            old_override_bytes = json.dumps(
                {"services": {"api-rust-core": {"image": old_digest, "env_file": [".env.rust"]}}}
            ).encode("utf-8")
            self.promote.OVERRIDE.write_bytes(old_override_bytes)

            symlinks: dict[Path, str] = {root / "apk" / "current": f"releases/{old_rev}"}
            active_image = {"id": "sha256:oldimg"}

            def fake_symlink(target: str, path: Path):
                symlinks[path] = target

            def fake_run(*args):
                if args == ("docker", "inspect", "api-rust-core"):
                    return json.dumps(
                        [
                            {
                                "Image": active_image["id"],
                                "Config": {
                                    "Image": old_digest,
                                    "Labels": {
                                        "com.docker.compose.project": "apps",
                                        "com.docker.compose.service": "api-rust-core",
                                    },
                                },
                            }
                        ]
                    )
                if args == ("docker", "ps", "-q"):
                    return ""
                if args == ("docker", "image", "inspect", "sha256:oldimg"):
                    return json.dumps([{"RepoDigests": [old_digest]}])
                if args == ("docker", "image", "inspect", manifest["image"]):
                    return json.dumps([{"Size": 25_000_000}])
                if args[0:2] == ("docker", "compose"):
                    ov = json.loads(self.promote.OVERRIDE.read_text(encoding="utf-8"))
                    cur_img = ov["services"]["api-rust-core"]["image"]
                    active_image["id"] = "sha256:oldimg" if cur_img == old_digest else "sha256:newimg"
                    return "recreated"
                raise AssertionError(f"Unexpected cmd: {args}")

            def fake_urlopen(url: str, timeout: int = 5):
                if url.endswith("/api/v1/ready"):
                    return _FakeHttpResponse({"status": "ready", "revision": rel_dir.name})
                if url.endswith("/api/v1/apk/info"):
                    return _FakeHttpResponse(
                        {"sha256_checksum": manifest["apk_sha256"], "version_code": manifest["version_code"]}
                    )
                if url.endswith("/api/v1/health"):
                    return _FakeHttpResponse({"status": "ok"}, status=200)
                raise AssertionError(f"Unexpected URL: {url}")

            buf = io.StringIO()
            with mock.patch.dict(os.environ, {"COGNIVA_SKIP_PULL": "1"}), mock.patch.object(
                self.promote, "run", side_effect=fake_run
            ), mock.patch.object(self.promote, "symlink", side_effect=fake_symlink), mock.patch.object(
                self.promote.os, "readlink", side_effect=lambda p: symlinks[Path(p)]
            ), mock.patch.object(
                type(rel_dir),
                "is_symlink",
                autospec=True,
                side_effect=lambda p: p in symlinks,
            ), mock.patch.object(
                self.promote.urllib.request, "urlopen", side_effect=fake_urlopen
            ), mock.patch.object(self.promote.time, "sleep"), mock.patch("sys.stdout", buf):
                self.promote._promote_locked(rel_dir, exercise_rollback=True)

            self.assertIn("ROLLBACK_HEALTHY", buf.getvalue())
            self.assertIn("CONTROLLED_ROLLBACK_VERIFIED", buf.getvalue())
            self.assertEqual(self.promote.OVERRIDE.read_bytes(), old_override_bytes)
            self.assertEqual(symlinks[root / "apk" / "current"], f"releases/{old_rev}")


class ReleasePackagingCleanupAndVerificationTest(unittest.TestCase):
    def test_package_release_generates_contract_v5_manifest_and_rejects_empty_apk(self):
        pkg_mod = load_module("cogniva_package_release", "scripts/package-release.py")
        rev = "d" * 40
        img = "ghcr.io/danielcdes-create/cogniva-api@sha256:" + ("e" * 64)
        with tempfile.TemporaryDirectory() as tmp:
            bundle = Path(tmp)
            apk_bytes = b"apk-v2-signed-payload"
            (bundle / "cogniva.apk").write_bytes(apk_bytes)
            # Include UTF-8 BOM as Android Gradle Plugin emits on some runners
            (bundle / "output-metadata.json").write_bytes(
                b"\xef\xbb\xbf"
                + json.dumps({"elements": [{"versionCode": 31, "versionName": "2.4.1"}]}).encode("utf-8")
            )
            out = io.StringIO()
            with mock.patch("sys.stdout", out):
                rc = pkg_mod.main([str(bundle), "--image", img, "--revision", rev])
            self.assertEqual(rc, 0)
            manifest = json.loads((bundle / "release.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["contract_version"], 5)
            self.assertEqual(manifest["revision"], rev)
            self.assertEqual(manifest["image"], img)
            self.assertEqual(manifest["version_code"], 31)
            self.assertEqual(manifest["version_name"], "2.4.1")
            self.assertEqual(manifest["apk_sha256"], hashlib.sha256(apk_bytes).hexdigest())
            self.assertEqual(manifest["apk_size_bytes"], len(apk_bytes))

            (bundle / "cogniva.apk").write_bytes(b"")
            with self.assertRaises(AssertionError):
                pkg_mod.package_release(bundle, image=img, revision=rev)

    def test_cleanup_registry_paginates_handles_404_and_protects_top5_latest_and_digests(self):
        cleanup_mod = load_module("cogniva_cleanup_registry", "scripts/cleanup-registry.py")
        cur_digest = "sha256:" + ("1" * 64)
        prev_digest = "sha256:" + ("2" * 64)

        page1 = [
            {"id": 100 + i, "name": f"sha256:{i:064x}", "created_at": f"2026-11-2{i}T00:00:00Z", "metadata": {"container": {"tags": []}}}
            for i in range(5, 0, -1)
        ]
        page2 = [
            # Old but protected by rollback digest
            {"id": 201, "name": prev_digest, "created_at": "2026-10-10T00:00:00Z", "metadata": {"container": {"tags": []}}},
            # Old but protected by 'latest' tag
            {"id": 202, "name": "sha256:" + ("3" * 64), "created_at": "2026-10-09T00:00:00Z", "metadata": {"container": {"tags": ["latest"]}}},
            # Old and unprotected -> must be deleted
            {"id": 203, "name": "sha256:" + ("4" * 64), "created_at": "2026-10-08T00:00:00Z", "metadata": {"container": {"tags": ["sha-old"]}}},
        ]
        deleted_ids: list[int] = []

        def fake_urlopen(req, timeout=30):
            url = req.full_url
            if req.get_method() == "DELETE":
                deleted_ids.append(int(url.rsplit("/", 1)[-1]))
                return _FakeHttpResponse(b"", status=204)
            if "page=2" in url:
                return _FakeHttpResponse(page2, status=200, headers={})
            return _FakeHttpResponse(
                page1,
                status=200,
                headers={"Link": '<https://api.github.com/users/o/packages/container/p/versions?page=2>; rel="next"'},
            )

        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = Path(tmp) / "current-release.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "image": f"ghcr.io/danielcdes-create/cogniva-api@{cur_digest}",
                        "previous_image": f"ghcr.io/danielcdes-create/cogniva-api@{prev_digest}",
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch.dict(os.environ, {"GH_TOKEN": "test-token"}), mock.patch.object(
                cleanup_mod.urllib.request, "urlopen", side_effect=fake_urlopen
            ):
                summary = cleanup_mod.cleanup_registry(manifest_path)

            self.assertEqual(summary["retained_recent"], 5)
            self.assertTrue(summary["protected_current_and_rollback"])
            self.assertEqual(summary["deleted_old_versions"], [203])
            self.assertEqual(deleted_ids, [203])

            # When previous_image has no '@' digest, protected_current_and_rollback is False (while tag is still spared)
            manifest_no_at = Path(tmp) / "legacy-release.json"
            manifest_no_at.write_text(
                json.dumps(
                    {
                        "image": f"ghcr.io/danielcdes-create/cogniva-api@{cur_digest}",
                        "previous_image": "ghcr.io/danielcdes-create/cogniva-api:sha-old",
                    }
                ),
                encoding="utf-8",
            )
            deleted_ids.clear()
            with mock.patch.dict(os.environ, {"GH_TOKEN": "test-token"}), mock.patch.object(
                cleanup_mod.urllib.request, "urlopen", side_effect=fake_urlopen
            ):
                summary_legacy = cleanup_mod.cleanup_registry(manifest_no_at)
            self.assertFalse(summary_legacy["protected_current_and_rollback"])
            self.assertNotIn(203, deleted_ids)
            self.assertEqual(deleted_ids, [201])

            # 404 package returns empty list without error
            with mock.patch.dict(os.environ, {"GH_TOKEN": "test-token"}), mock.patch.object(
                cleanup_mod.urllib.request,
                "urlopen",
                side_effect=urllib.error.HTTPError("https://api.github.com", 404, "Not Found", {}, None),
            ):
                summary_404 = cleanup_mod.cleanup_registry(manifest_path)
            self.assertEqual(summary_404["retained_recent"], 0)
            self.assertEqual(summary_404["deleted_old_versions"], [])

    def test_verify_production_release_enforces_tls_48h_401_portal_csp_and_apk_sha256(self):
        verify_mod = load_module("cogniva_verify_prod", "scripts/verify-production-release.py")

        # HTTP origin skips TLS check
        self.assertIsNone(verify_mod.check_tls_remaining_hours("http://127.0.0.1:8001"))

        # TLS < 48h fails
        soon = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=12)).strftime("%b %d %H:%M:%S %Y GMT")
        healthy_exp = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=96)).strftime("%b %d %H:%M:%S %Y GMT")

        class FakeTlsSock:
            def __init__(self, not_after: str):
                self._not_after = not_after

            def getpeercert(self):
                return {"notAfter": self._not_after}

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        class FakeCtx:
            def __init__(self, not_after: str):
                self._not_after = not_after

            def wrap_socket(self, sock, server_hostname=None):
                return FakeTlsSock(self._not_after)

        with mock.patch.object(verify_mod.socket, "create_connection", return_value=io.BytesIO()), mock.patch.object(
            verify_mod.ssl, "create_default_context", return_value=FakeCtx(soon)
        ):
            with self.assertRaisesRegex(AssertionError, "TLS certificate expires too soon"):
                verify_mod.check_tls_remaining_hours("https://198.199.65.103")

        # Full acceptance run
        rev = "f" * 40
        apk_bytes = b"production-apk-verified"
        apk_sha = hashlib.sha256(apk_bytes).hexdigest()
        apk_info = {
            "version_code": 31,
            "version_name": "2.4.1",
            "size_bytes": len(apk_bytes),
            "sha256_checksum": apk_sha,
            "download_path": "/api/v1/apk/download",
        }
        sec_headers = {
            "Strict-Transport-Security": "max-age=604800",
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Options": "DENY",
        }
        portal_headers = {
            "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
            "Cache-Control": "no-store",
        }

        def fake_urlopen(url: str, timeout: int = 30):
            if url.endswith("/api/v1/health"):
                return _FakeHttpResponse(
                    {"revision": rev, "contract_version": 5, "version": "2.4.1"},
                    headers=sec_headers,
                )
            if url.endswith("/api/v1/ready"):
                return _FakeHttpResponse({"revision": rev, "contract_version": 5})
            if url.endswith("/api/v1/apk/info"):
                return _FakeHttpResponse(apk_info)
            if url.endswith("/api/v1/exams"):
                raise urllib.error.HTTPError(url, 401, "Unauthorized", {}, None)
            if url.endswith("/admin/") or url.endswith("/missoes/"):
                return _FakeHttpResponse("<html></html>", status=200, headers=portal_headers)
            if url.endswith("/api/v1/apk/download"):
                return _FakeHttpResponse(apk_bytes, status=200)
            raise AssertionError(f"Unexpected URL: {url}")

        remote_payload = {
            "manifest": {
                "revision": rev,
                "contract_version": 5,
                "apk_sha256": apk_sha,
                "apk_size_bytes": len(apk_bytes),
                "runtime_size_bytes": 24_000_000,
            },
            "compose_sha256": "0" * 64,
            "core": {
                "user": "10001:10001",
                "read_only": True,
                "memory_bytes": 201326592,
                "nano_cpus": 750000000,
                "cap_drop": ["ALL"],
                "health": "healthy",
                "xai_model": "grok-4",
                "xai_base_url": "https://api.x.ai/v1",
                "xai_key_configured": True,
            },
            "gate": "set $cogniva_maintenance 0;",
            "renewal_timer": "ActiveState=active\n",
            "renewal_service": "Result=success\n",
        }

        with tempfile.TemporaryDirectory() as tmp:
            out_file = Path(tmp) / "acceptance.json"
            with mock.patch.object(verify_mod.socket, "create_connection", return_value=io.BytesIO()), mock.patch.object(
                verify_mod.ssl, "create_default_context", return_value=FakeCtx(healthy_exp)
            ), mock.patch.object(verify_mod.urllib.request, "urlopen", side_effect=fake_urlopen), mock.patch.object(
                verify_mod.subprocess, "check_output", return_value=json.dumps(remote_payload)
            ), mock.patch("sys.stdout", io.StringIO()):
                rc = verify_mod.main(
                    [
                        rev,
                        "--version-name",
                        "2.4.1",
                        "--version-code",
                        "31",
                        "--xai-model",
                        "grok-4",
                        "--output",
                        str(out_file),
                    ]
                )
            self.assertEqual(rc, 0)
            saved = json.loads(out_file.read_text(encoding="utf-8"))
            self.assertEqual(saved["anonymous_protected_status"], 401)
            self.assertGreaterEqual(saved["tls_remaining_hours"], 48.0)

    def test_verify_web_admin_assets_checks_bytes_nosniff_and_missions_media_csp(self):
        assets_mod = load_module("cogniva_verify_assets", "scripts/verify-web-admin-assets.py")
        rev = "a" * 40
        admin_csp = (
            "default-src 'none'; script-src 'self'; style-src 'self'; "
            "connect-src 'self'; frame-ancestors 'none'"
        )
        missions_csp = (
            "default-src 'none'; script-src 'self'; style-src 'self'; "
            "connect-src 'self'; media-src 'self'; frame-ancestors 'none'"
        )

        def fake_git_show(cmd, cwd=None):
            git_spec = cmd[2]
            rel_path = git_spec.split(":", 1)[1]
            return f"immutable-bytes-for:{rel_path}".encode("utf-8")

        def make_urlopen(*, drop_nosniff: bool = False, drop_media_src: bool = False):
            def _urlopen(url: str, timeout: int = 45):
                path = "/" + url.split("://", 1)[1].split("/", 1)[1]
                folder, name = assets_mod.FILES[path]
                body = f"immutable-bytes-for:{folder}/{name}".encode("utf-8")
                headers = {"Cache-Control": "no-store"}
                if not drop_nosniff:
                    headers["X-Content-Type-Options"] = "nosniff"
                if path == "/admin/":
                    headers["Content-Security-Policy"] = admin_csp
                elif path == "/missoes/":
                    headers["Content-Security-Policy"] = admin_csp if drop_media_src else missions_csp
                return _FakeHttpResponse(body, status=200, headers=headers)

            return _urlopen

        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "assets-report.json"
            with mock.patch.object(assets_mod.subprocess, "check_output", side_effect=fake_git_show), mock.patch.object(
                assets_mod.urllib.request, "urlopen", side_effect=make_urlopen()
            ), mock.patch("sys.stdout", io.StringIO()):
                rc = assets_mod.main([rev, "--origin", "https://198.199.65.103", "--output", str(out_path)])
            self.assertEqual(rc, 0)
            report = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(len(report["files"]), len(assets_mod.FILES))
            self.assertTrue(report["page_csp_verified"])

            # Missing X-Content-Type-Options: nosniff fails
            with mock.patch.object(assets_mod.subprocess, "check_output", side_effect=fake_git_show), mock.patch.object(
                assets_mod.urllib.request, "urlopen", side_effect=make_urlopen(drop_nosniff=True)
            ):
                with self.assertRaisesRegex(AssertionError, "nosniff"):
                    assets_mod.verify_web_admin_assets(rev)

            # Missing media-src 'self' on /missoes/ fails
            with mock.patch.object(assets_mod.subprocess, "check_output", side_effect=fake_git_show), mock.patch.object(
                assets_mod.urllib.request, "urlopen", side_effect=make_urlopen(drop_media_src=True)
            ):
                with self.assertRaisesRegex(AssertionError, "media-src"):
                    assets_mod.verify_web_admin_assets(rev)


if __name__ == "__main__":
    unittest.main()
