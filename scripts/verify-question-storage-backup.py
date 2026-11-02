"""Verify additive question-storage installation using private, read-only backups.

Only hashes/counts/options metadata are emitted. No production connection or write.
"""
import argparse
import hashlib
import json
import pathlib
import zipfile
from bson import decode_all

p = argparse.ArgumentParser()
p.add_argument("before", type=pathlib.Path)
p.add_argument("after", type=pathlib.Path)
p.add_argument("--output", type=pathlib.Path, required=True)
a = p.parse_args()
before, after = [json.loads(path.read_text(encoding="utf-8-sig")) for path in [a.before, a.after]]
old_root, new_root = [pathlib.Path(v["private_backup_directory"]) for v in [before, after]]
assert before["database"] == after["database"] == "CNU"

def data(root, name):
    with zipfile.ZipFile(root / f"{name}.zip") as archive:
        return archive.read(f"{name}.bson")

def indexes(name):
    with zipfile.ZipFile(new_root / f"{name}.zip") as archive:
        return {row["name"]: row for row in json.loads(archive.read("indexes.json"))}

preserved = {}
for name in ["todasQuestoes", "user_exams"]:
    old, new = data(old_root, name), data(new_root, name)
    assert old == new, f"{name}: documents changed; inspect differences before claiming preservation"
    preserved[name] = {"documents": after["collections"][name], "sha256": hashlib.sha256(new).hexdigest(), "byte_identical": True}
assert before["protected_history"] == after["protected_history"], "Learning history changed"

options = json.loads((new_root / "collection-options.json").read_text(encoding="utf-8"))
receipts = {row["_id"]: row for row in decode_all(data(new_root, "cogniva_schema_versions"))}
validated = []
for name in ["todasQuestoes", "question_classification_suggestions", "question_exam_compatibility", "answer_evaluations"]:
    setting = options[name]["options"]
    receipt = receipts[f"question-v2:{name}"]
    assert setting["validator"] == receipt["validator"], f"{name}: receipt/validator mismatch"
    assert setting.get("validationLevel", "strict") == "strict"
    assert setting.get("validationAction", "error") == "error"
    assert len(receipt["source_sha256"]) == 64
    validated.append(name)
assert receipts["question-v2:ready"]["ready"] is True
assert receipts["question-v2:ready"]["version"] == 2

expected = {
    "todasQuestoes": ["qv2_legacy_topic", "qv2_classification_validity", "qv2_validity_type"],
    "question_classification_suggestions": ["qv2_pending_classification"],
    "question_exam_compatibility": ["qv2_owner_exam_question"],
    "answer_evaluations": ["qv2_owner_attempt"],
    "question_revision_events": ["qv2_question_audit"],
    "knowledge_catalog_versions": ["qv2_catalog_version"],
}
for name, names in expected.items():
    actual = indexes(name)
    assert all(index in actual for index in names), f"{name}: missing index"
assert indexes("knowledge_catalog_versions")["qv2_catalog_version"]["unique"] is True
catalog = json.loads((pathlib.Path(__file__).resolve().parents[1] / "shared/knowledge-catalog.json").read_text(encoding="utf-8"))
snapshots = decode_all(data(new_root, "knowledge_catalog_versions"))
snapshot = next(row for row in snapshots if row["catalog_id"] == catalog["catalog_id"] and row["version"] == catalog["catalog_version"])
assert snapshot["payload"] == catalog

result = {
    "before_capture": before["captured_at"], "after_capture": after["captured_at"],
    "question_and_exam_documents": preserved, "protected_history": after["protected_history"],
    "history_unchanged": True, "strict_validated_collections": validated,
    "installation_ready": True, "indexes_verified": expected,
    "index_count": sum(map(len, expected.values())),
    "catalog_snapshot_matches_source": True, "catalog_version": catalog["catalog_version"],
    "no_question_reclassification": True,
}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
