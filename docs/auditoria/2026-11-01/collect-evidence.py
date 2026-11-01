"""Read-only inventory. Does not load .env or access user-data endpoints."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo
import urllib.request

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]

def command(args, timeout=20):
    try:
        p = subprocess.run(args, cwd=ROOT, capture_output=True, timeout=timeout)
        return {"exit_code": p.returncode, "stdout": p.stdout.decode("utf-8", "replace"), "stderr": p.stderr.decode("utf-8", "replace")}
    except Exception as e:
        return {"error": str(e)}

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

try:
    captured = datetime.now(ZoneInfo("America/Sao_Paulo")).isoformat()
except Exception:
    captured = datetime.now().isoformat()
evidence = {"captured_at": captured, "root": str(ROOT), "head": command(["git", "rev-parse", "HEAD"]),
            "status": command(["git", "status", "--short"]), "rust": command(["rustc", "--version"]),
            "cargo": command(["cargo", "--version"])}
extensions = {".rs", ".kt", ".kts", ".md", ".txt", ".yml", ".toml", ".properties", ".xml"}
fingerprints = []
for folder in [ROOT / "backend-rust" / "src", ROOT / "android-app" / "app" / "src", ROOT / "Notas Conversao Kotlin_Rust", ROOT / ".github"]:
    for p in sorted(folder.rglob("*")):
        if p.is_file() and p.suffix in extensions:
            fingerprints.append({"path": p.relative_to(ROOT).as_posix(), "sha256": digest(p), "bytes": p.stat().st_size})
evidence["source_fingerprints"] = fingerprints
evidence["apk_artifacts"] = []
sdk = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
tools = sorted((sdk / "build-tools").glob("*/aapt.exe"))
for apk in [ROOT / "Cogniva-v1.2.2.apk", ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"]:
    if apk.exists():
        row = {"path": apk.relative_to(ROOT).as_posix(), "bytes": apk.stat().st_size, "sha256": digest(apk)}
        if tools:
            result = command([str(tools[-1]), "dump", "badging", str(apk)])
            row["badging"] = [line for line in result.get("stdout", "").splitlines() if line.startswith(("package:", "sdkVersion:", "targetSdkVersion:", "application-label:"))]
        evidence["apk_artifacts"].append(row)
evidence["adb"] = command([str(sdk / "platform-tools/adb.exe"), "devices", "-l"])
evidence["public_read_only_probes"] = []
for route in ["/api/v1/health", "/api/v1/apk/info", "/api/app/latest-apk"]:
    url = "http://198.199.65.103" + route
    row = {"url": url}
    try:
        with urllib.request.urlopen(url, timeout=8) as r:
            row.update(status=r.status, body=r.read(12000).decode("utf-8", "replace"))
    except Exception as e:
        row["error"] = str(e)
    evidence["public_read_only_probes"].append(row)
(OUT / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({k: v for k, v in evidence.items() if k not in {"source_fingerprints", "status"}}, ensure_ascii=False, indent=2))
