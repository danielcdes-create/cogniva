import ast
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load_promote_helpers():
    source = (ROOT / "deploy/promote.py").read_text(encoding="utf-8")
    tree = ast.parse(source, filename="promote.py")
    keep_nodes = []
    for node in tree.body:
        if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.Try)):
            keep_nodes.append(node)
        elif isinstance(node, ast.Assign):
            names = [t.id for t in node.targets if isinstance(t, ast.Name)]
            if set(names) <= {
                "ROOT",
                "COMPOSE",
                "OVERRIDE",
                "PROJECT",
                "SERVICE",
                "LOCAL_API",
                "PUBLIC_ORIGIN",
                "SKIP_NGINX_GATE",
                "LOCK_PATH",
                "IMAGE_RE",
            }:
                keep_nodes.append(node)
    mod = ast.Module(body=keep_nodes, type_ignores=[])
    namespace = {}
    exec(compile(mod, "promote.py", "exec"), namespace)
    return namespace


class DeployScriptsTest(unittest.TestCase):
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

    def test_package_and_verify_release_use_contract_version_5(self):
        pkg = (ROOT / "scripts/package-release.py").read_text(encoding="utf-8")
        verify = (ROOT / "scripts/verify-production-release.py").read_text(encoding="utf-8")
        self.assertIn("'contract_version': 5", pkg)
        self.assertIn("== 5", verify)
        self.assertIn("check_tls_remaining_hours", verify)

    def test_nginx_conf_protects_auth_ai_apk_and_sets_security_headers(self):
        nginx = (ROOT / "deploy/nginx-cogniva.conf").read_text(encoding="utf-8")
        rates = (ROOT / "deploy/nginx-rate-limits.conf").read_text(encoding="utf-8")
        sync = (ROOT / "deploy/sync_nginx_site.py").read_text(encoding="utf-8")
        bootstrap = (ROOT / "deploy/bootstrap-ip-tls.sh").read_text(encoding="utf-8")
        self.assertIn("admin/questions/import/assist", nginx)
        self.assertIn("login|register|guest|logout|web-session|web-logout|account|native-session", nginx)
        self.assertIn('Retry-After "15"', nginx)
        self.assertIn('Strict-Transport-Security "max-age=604800"', nginx)
        self.assertIn("proxy_hide_header X-Content-Type-Options;", nginx)
        self.assertIn("proxy_hide_header Referrer-Policy;", nginx)
        self.assertIn("charset utf-8;", nginx)
        self.assertIn("cogniva_apk", nginx)
        self.assertIn("cogniva_media", nginx)
        self.assertIn("$cogniva_limit_key", rates)
        self.assertIn("$cogniva_ai_limit_key", rates)
        self.assertIn("zone=cogniva_media:4m", rates)
        self.assertIn("ipv6_subnet", rates)
        self.assertNotIn("if 'location /missoes/' in cur", sync)
        self.assertIn("-checkend 216000", bootstrap)

    def test_cogniva_import_normalizes_validity_basis_and_avoids_hardcoded_session(self):
        import datetime as dt
        import sys
        sys.path.insert(0, str(ROOT / "scripts"))
        import cogniva_import as ci

        self.assertEqual(ci.normalize_validity_basis("NORMATIVE"), "LEGAL")
        self.assertEqual(ci.normalize_validity_basis("JURISPRUDENTIAL"), "LEGAL")
        self.assertEqual(ci.normalize_validity_basis("FACTUAL"), "FACTUAL")
        self.assertEqual(ci.normalize_validity_basis("TECHNICAL"), "TECHNICAL")
        self.assertEqual(
            ci.resolve_review_due_on("LEGAL", None, today=dt.date(2026, 11, 21)),
            "2027-11-21",
        )
        self.assertIsNone(ci.resolve_review_due_on("FACTUAL", None, today=dt.date(2026, 11, 21)))
        self.assertNotIn("03422120-65eb-4819-9ea7-3f88fb95c37a", ci.REMOTE_RUNNER)

    def test_prune_old_releases_preserves_active_previous_and_three_latest(self):
        ns = load_promote_helpers()
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            ns["ROOT"] = base
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
            ns["prune_old_releases"]({shas[0], shas[5]})
            remaining = {p.name for p in rels.iterdir()}
            self.assertEqual(remaining, {shas[0], shas[3], shas[4], shas[5]})
            self.assertEqual({p.name for p in apks.iterdir()}, remaining)
            self.assertFalse(stale_in.exists())
            self.assertTrue(fresh_in.exists())

    def test_snapshot_persistent_neighbors_ignores_vanished_ephemeral_container(self):
        ns = load_promote_helpers()

        def fake_run(*args):
            if args == ("docker", "ps", "-q"):
                return "vanished123\npersistent456\ncertbot789\n"
            if args == ("docker", "inspect", "vanished123"):
                raise subprocess.CalledProcessError(1, args, "No such object: vanished123")
            if args == ("docker", "inspect", "persistent456"):
                return json.dumps(
                    [
                        {
                            "Id": "full-persistent-456",
                            "Name": "/financial-twin-server",
                            "HostConfig": {"AutoRemove": False},
                            "Config": {"Image": "twin:latest"},
                        }
                    ]
                )
            if args == ("docker", "inspect", "certbot789"):
                return json.dumps(
                    [
                        {
                            "Id": "full-certbot-789",
                            "Name": "/certbot-renew",
                            "HostConfig": {"AutoRemove": True},
                            "Config": {"Image": "certbot/certbot:v5.4.0"},
                        }
                    ]
                )
            raise AssertionError(f"Unexpected command: {args}")

        ns["run"] = fake_run
        self.assertEqual(
            ns["snapshot_persistent_neighbors"](),
            {"/financial-twin-server": "full-persistent-456"},
        )

    def test_link_or_copy_verifies_immutable_digest(self):
        ns = load_promote_helpers()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "src.apk"
            dst = root / "dst.apk"
            src.write_bytes(b"immutable-apk-payload")
            ns["link_or_copy"](src, dst)
            self.assertEqual(dst.read_bytes(), b"immutable-apk-payload")
            ns["link_or_copy"](src, dst)
            dst.unlink()
            dst.write_bytes(b"tampered")
            with self.assertRaises(AssertionError):
                ns["link_or_copy"](src, dst)


if __name__ == "__main__":
    unittest.main()
