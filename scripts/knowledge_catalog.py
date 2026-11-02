"""Offline validation and readable export of the staged knowledge catalog.

Never reads/writes Mongo, calls AI, or changes the runtime catalog.
"""
import argparse
import hashlib
import json
from pathlib import Path
import unicodedata

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def normalized(value):
    return " ".join("".join(c if c.isalnum() else " " for c in
        unicodedata.normalize("NFKD", value.casefold()) if not unicodedata.combining(c)).split())


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")).encode()).hexdigest()


def validate(catalog, legacy, registry, schema):
    Draft202012Validator.check_schema(schema)
    errors = sorted(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(catalog),
                    key=lambda e: str(list(e.path)))
    if errors:
        raise ValueError(f"schema {list(errors[0].path)}: {errors[0].message}")

    def require(condition, message):
        if not condition:
            raise ValueError(message)

    def unique(rows, key):
        result = {r[key]: r for r in rows}
        require(len(result) == len(rows), f"duplicate {key}")
        return result

    sources = unique(catalog["sources"], "source_id")
    trunks = unique(catalog["trunks"], "trunk_id")
    subjects = unique(catalog["subjects"], "subject_id")
    topics = unique(catalog["topics"], "topic_id")
    all_ids = list(trunks) + list(subjects) + list(topics)
    require(len(all_ids) == len(set(all_ids)), "identity reused between entity types")
    alias_owners = {}

    def names(row, identity, scope):
        seen = set()
        for name in [row["label"], *row["aliases"]]:
            n = normalized(name)
            require(bool(n) and name == name.strip(), f"blank or padded name: {identity}")
            require(n not in seen, f"duplicate normalized alias: {identity}/{name}")
            seen.add(n)
            key = (scope, n)
            require(key not in alias_owners or alias_owners[key] == identity,
                    f"ambiguous alias: {identity}/{name}")
            alias_owners[key] = identity

    for rows in (trunks, subjects, topics):
        for identity, row in rows.items():
            require(set(row["source_ids"]) <= sources.keys(), f"unknown source: {identity}")
            scope = ("TRUNK",) if rows is trunks else (("SUBJECT",) if rows is subjects
                     else ("TOPIC", row["subject_id"], row["parent_topic_id"]))
            names(row, identity, scope)

    for identity, subject in subjects.items():
        seen = set()
        for link in subject["trunk_links"]:
            require(link["trunk_id"] in trunks, f"unknown trunk: {identity}")
            require(link["trunk_id"] not in seen, f"duplicate trunk relation: {identity}")
            seen.add(link["trunk_id"])
        require(any(t["subject_id"] == identity and t["kind"] == "TOPIC" for t in topics.values()),
                f"subject without topics: {identity}")

    for identity, topic in topics.items():
        require(topic["subject_id"] in subjects, f"unknown subject: {identity}")
        parent = topic["parent_topic_id"]
        require((parent is None) == (topic["kind"] == "TOPIC"), f"kind/parent mismatch: {identity}")
        if parent is not None:
            require(parent in topics, f"unknown parent: {identity}")
            require(parent != identity and topics[parent]["kind"] == "TOPIC", f"invalid hierarchy: {identity}")
            require(topics[parent]["subject_id"] == topic["subject_id"], f"cross-subject parent: {identity}")
        else:
            require(any(t["parent_topic_id"] == identity for t in topics.values()),
                    f"topic without subtopics: {identity}")

    relations = set()
    for link in catalog["relations"]:
        a, b = link["from_topic_id"], link["to_topic_id"]
        require(a in topics and b in topics and a != b, "invalid related topic")
        require(set(link["source_ids"]) <= sources.keys(), "unknown relation source")
        key = (*sorted((a, b)), link["relation"])
        require(key not in relations, "duplicate symmetric relation")
        relations.add(key)

    compat = catalog["compatibility"]
    require(digest(legacy) == compat["runtime_catalog_sha256"], "runtime catalog changed: review compatibility")
    require(set(compat["legacy_subject_ids"]) == {s["canonical_subject_id"] for s in legacy["subjects"]},
            "legacy subject inventory changed")
    require(set(compat["legacy_topic_ids"]) == {t["topic_id"] for s in legacy["subjects"] for t in s["topics"]},
            "legacy topic inventory changed")
    for subject in legacy["subjects"]:
        sid = subject["canonical_subject_id"]
        require(sid in subjects and subject["name"] in [subjects[sid]["label"], *subjects[sid]["aliases"]], f"legacy subject renamed: {sid}")
        require(set(subject["aliases"]) <= set(subjects[sid]["aliases"]), f"legacy alias removed: {sid}")
        require(set(subject["trunk_slugs"]) <= {l["trunk_id"] for l in subjects[sid]["trunk_links"]},
                f"legacy trunk link removed: {sid}")
        for old in subject["topics"]:
            require(old["topic_id"] in topics, f"legacy topic removed: {old['topic_id']}")
            new = topics[old["topic_id"]]
            require(new["subject_id"] == sid and old["title"] in [new["label"], *new["aliases"]] and new["parent_topic_id"] is None,
                    f"legacy topic repurposed: {old['topic_id']}")

    require(registry.get("catalog_id") == catalog["catalog_id"], "registry catalog mismatch")
    registered = unique(registry["entries"], "id")
    require(set(registered) == set(all_ids), "unregistered or removed identity")
    for entity, rows in (("TRUNK", trunks), ("SUBJECT", subjects), ("TOPIC", topics)):
        for identity, row in rows.items():
            pin = registered[identity]
            require(pin["entity"] == entity and pin["subject_id"] == row.get("subject_id")
                    and pin["parent_topic_id"] == row.get("parent_topic_id"), f"identity repurposed: {identity}")
            require(pin["initial_label"] in [row["label"], *row["aliases"]], f"original label lost: {identity}")

    return {"catalog_version": catalog["catalog_version"], "trunks": len(trunks), "subjects": len(subjects),
            "topics": sum(t["kind"] == "TOPIC" for t in topics.values()),
            "subtopics": sum(t["kind"] == "SUBTOPIC" for t in topics.values()),
            "subject_aliases": sum(len(s["aliases"]) for s in subjects.values()),
            "legacy_subjects_preserved": len(compat["legacy_subject_ids"]),
            "legacy_topics_preserved": len(compat["legacy_topic_ids"]),
            "runtime_catalog_sha256": digest(legacy), "catalog_sha256": digest(catalog),
            "runtime_transition": compat["runtime_transition"]}


def render(catalog):
    lines = ["# Catálogo de conhecimentos — consulta", "", "Gerado de `shared/knowledge-catalog.json`. "
             "Base editorial para revisão; relações de carreira não são exigências de edital. "
             "Ainda não substitui o catálogo usado pelo aplicativo.", "", "## Troncos de carreira", "",
             "| ID | Nome |", "| --- | --- |"]
    lines += [f"| `{t['trunk_id']}` | {t['label']} |" for t in catalog["trunks"]]
    for subject in catalog["subjects"]:
        lines += ["", f"## {subject['subject_id']} — {subject['label']}", "", subject["scope_note"], "",
                  "Nomes alternativos: " + "; ".join(subject["aliases"]) + ".", "",
                  "Carreiras: " + "; ".join(f"`{l['trunk_id']}` ({l['role']}; {l['basis']})"
                      for l in subject["trunk_links"]) + ".", "",
                  "| ID | Nível | Nome | Assunto pai |", "| --- | --- | --- | --- |"]
        for topic in [t for t in catalog["topics"] if t["subject_id"] == subject["subject_id"]]:
            lines.append(f"| `{topic['topic_id']}` | {'Assunto' if topic['kind']=='TOPIC' else 'Subassunto'} | "
                         f"{topic['label']} | {topic['parent_topic_id'] or '—'} |")
    lines += ["", "## Relações entre conteúdos", "", "Estas relações não representam equivalência nem transferência automática de domínio.", "",
              "| Origem | Destino | Relação | Justificativa |", "| --- | --- | --- | --- |"]
    lines += [f"| `{r['from_topic_id']}` | `{r['to_topic_id']}` | {r['relation']} | {r['note']} |"
              for r in catalog["relations"]]
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--check", action="store_true", help="also check the committed readable export")
    p.add_argument("--export", action="store_true", help="regenerate the readable export")
    a = p.parse_args()
    catalog = read(ROOT / "shared/knowledge-catalog.json")
    summary = validate(catalog, read(ROOT / "shared/canonical-catalog.json"),
                       read(ROOT / "shared/knowledge-id-registry.json"), read(ROOT / "shared/knowledge-catalog.schema.json"))
    output = ROOT / "docs/catalogo/CONHECIMENTOS.md"
    if a.export:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(render(catalog), encoding="utf-8")
    if a.check and output.read_text(encoding="utf-8") != render(catalog):
        raise ValueError("Readable export is stale; run --export")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
