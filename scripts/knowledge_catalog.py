"""Offline validation and readable export of the unified Cogniva knowledge catalog and exam templates."""
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
    return " ".join(
        "".join(
            c if c.isalnum() else " "
            for c in unicodedata.normalize("NFKD", value.casefold())
            if not unicodedata.combining(c)
        ).split()
    )


def digest(value):
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def validate_templates(templates_doc, catalog, templates_schema=None, component_schema=None):
    if templates_schema is None:
        templates_schema = read(ROOT / "shared/schemas/exam-templates.schema.json")
    if component_schema is None:
        component_schema = read(ROOT / "shared/schemas/exam-component.schema.json")

    Draft202012Validator.check_schema(templates_schema)
    Draft202012Validator.check_schema(component_schema)

    t_errors = sorted(
        Draft202012Validator(templates_schema, format_checker=FormatChecker()).iter_errors(templates_doc),
        key=lambda e: str(list(e.path)),
    )
    if t_errors:
        raise ValueError(f"exam-templates schema {list(t_errors[0].path)}: {t_errors[0].message}")

    comp_validator = Draft202012Validator(component_schema, format_checker=FormatChecker())
    trunks = {t["trunk_id"]: t for t in catalog["trunks"] if t["status"] == "ACTIVE"}
    subjects = {s["subject_id"]: s for s in catalog["subjects"] if s["status"] == "ACTIVE"}
    topics = {t["topic_id"]: t for t in catalog["topics"] if t["status"] == "ACTIVE"}

    seen_ids = set()
    for tpl in templates_doc["templates"]:
        tid = tpl["_id"]
        if tid in seen_ids:
            raise ValueError(f"duplicate template _id: {tid}")
        seen_ids.add(tid)
        if tpl["trunk_slug"] not in trunks:
            raise ValueError(f"template {tid} uses unknown/inactive trunk: {tpl['trunk_slug']}")
        total_q = sum(s.get("question_count") or 0 for s in tpl["subjects"])
        if total_q > 500:
            raise ValueError(f"template {tid} exceeds 500 questions: {total_q}")
        local_sids = set()
        local_tids = set()
        for comp in tpl["subjects"]:
            c_errors = sorted(comp_validator.iter_errors(comp), key=lambda e: str(list(e.path)))
            if c_errors:
                raise ValueError(f"template {tid} component {comp.get('local_subject_id')}: {c_errors[0].message}")
            if comp["local_subject_id"] in local_sids:
                raise ValueError(f"template {tid} duplicate local_subject_id: {comp['local_subject_id']}")
            local_sids.add(comp["local_subject_id"])
            csid = comp["canonical_subject_id"]
            if csid not in subjects:
                raise ValueError(f"template {tid} references unknown/inactive subject: {csid}")
            for top in comp["topics"]:
                if top["local_topic_id"] in local_tids:
                    raise ValueError(f"template {tid} duplicate local_topic_id: {top['local_topic_id']}")
                local_tids.add(top["local_topic_id"])
                ctid = top["canonical_topic_id"]
                if ctid not in topics or topics[ctid]["subject_id"] != csid or top["canonical_subject_id"] != csid:
                    raise ValueError(f"template {tid} invalid topic link: {csid}/{ctid}")
    return len(templates_doc["templates"])


def validate(catalog, registry, schema, templates_doc=None):
    Draft202012Validator.check_schema(schema)
    errors = sorted(
        Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(catalog),
        key=lambda e: str(list(e.path)),
    )
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
            require(key not in alias_owners or alias_owners[key] == identity, f"ambiguous alias: {identity}/{name}")
            alias_owners[key] = identity

    for rows in (trunks, subjects, topics):
        for identity, row in rows.items():
            require(set(row["source_ids"]) <= sources.keys(), f"unknown source: {identity}")
            scope = (
                ("TRUNK",)
                if rows is trunks
                else (("SUBJECT",) if rows is subjects else ("TOPIC", row["subject_id"], row["parent_topic_id"]))
            )
            names(row, identity, scope)

    for identity, subject in subjects.items():
        seen = set()
        for link in subject["trunk_links"]:
            require(link["trunk_id"] in trunks, f"unknown trunk: {identity}")
            require(link["trunk_id"] not in seen, f"duplicate trunk relation: {identity}")
            seen.add(link["trunk_id"])
        require(
            any(t["subject_id"] == identity and t["kind"] == "TOPIC" for t in topics.values()),
            f"subject without topics: {identity}",
        )

    for identity, topic in topics.items():
        require(topic["subject_id"] in subjects, f"unknown subject: {identity}")
        parent = topic["parent_topic_id"]
        require((parent is None) == (topic["kind"] == "TOPIC"), f"kind/parent mismatch: {identity}")
        if parent is not None:
            require(parent in topics, f"unknown parent: {identity}")
            require(parent != identity and topics[parent]["kind"] == "TOPIC", f"invalid hierarchy: {identity}")
            require(topics[parent]["subject_id"] == topic["subject_id"], f"cross-subject parent: {identity}")
        else:
            require(any(t["parent_topic_id"] == identity for t in topics.values()), f"topic without subtopics: {identity}")

    relations = set()
    for link in catalog["relations"]:
        a, b = link["from_topic_id"], link["to_topic_id"]
        require(a in topics and b in topics and a != b, "invalid related topic")
        require(set(link["source_ids"]) <= sources.keys(), "unknown relation source")
        key = (*sorted((a, b)), link["relation"])
        require(key not in relations, "duplicate symmetric relation")
        relations.add(key)

    compat = catalog["compatibility"]
    require(set(compat["legacy_subject_ids"]) <= set(subjects), "legacy subject removed")
    for tid in compat["legacy_topic_ids"]:
        require(tid in topics and topics[tid]["parent_topic_id"] is None, f"legacy topic removed or repurposed: {tid}")

    require(registry.get("catalog_id") == catalog["catalog_id"], "registry catalog mismatch")
    registered = unique(registry["entries"], "id")
    require(set(registered) == set(all_ids), "unregistered or removed identity")
    for entity, rows in (("TRUNK", trunks), ("SUBJECT", subjects), ("TOPIC", topics)):
        for identity, row in rows.items():
            pin = registered[identity]
            require(
                pin["entity"] == entity
                and pin["subject_id"] == row.get("subject_id")
                and pin["parent_topic_id"] == row.get("parent_topic_id"),
                f"identity repurposed: {identity}",
            )
            require(pin["initial_label"] in [row["label"], *row["aliases"]], f"original label lost: {identity}")

    templates_count = 0
    if templates_doc is not None:
        templates_count = validate_templates(templates_doc, catalog)

    return {
        "catalog_version": catalog["catalog_version"],
        "status": catalog["status"],
        "trunks": len(trunks),
        "subjects": len(subjects),
        "topics": sum(t["kind"] == "TOPIC" for t in topics.values()),
        "subtopics": sum(t["kind"] == "SUBTOPIC" for t in topics.values()),
        "subject_aliases": sum(len(s["aliases"]) for s in subjects.values()),
        "legacy_subjects_preserved": len(compat["legacy_subject_ids"]),
        "legacy_topics_preserved": len(compat["legacy_topic_ids"]),
        "exam_templates": templates_count,
        "catalog_sha256": digest(catalog),
    }


def render(catalog):
    lines = [
        "# Catálogo unificado de conhecimentos — Cogniva 2.0",
        "",
        "Gerado automaticamente a partir de `shared/knowledge-catalog.json` (`python3 scripts/knowledge_catalog.py --export`). "
        "Este é o catálogo único ativo utilizado pelo backend Rust (`cogniva-api`), pelo painel `/admin/` e pelo aplicativo Android.",
        "",
        "## Troncos de carreira",
        "",
        "| ID | Nome | Ícone | Status |",
        "| --- | --- | --- | --- |",
    ]
    lines += [f"| `{t['trunk_id']}` | {t['label']} | `{t['icon_key']}` | `{t['status']}` |" for t in catalog["trunks"]]
    for subject in catalog["subjects"]:
        aliases = "; ".join(subject["aliases"]) if subject["aliases"] else "—"
        links = "; ".join(
            f"`{l['trunk_id']}` ({l['role']}, peso padrão `{l['default_weight']}`)"
            for l in subject["trunk_links"]
        )
        lines += [
            "",
            f"## {subject['subject_id']} — {subject['label']}",
            "",
            subject["scope_note"],
            "",
            f"Nomes alternativos: {aliases}.",
            "",
            f"Carreiras vinculadas: {links}.",
            "",
            "| ID | Nível | Nome | Assunto pai |",
            "| --- | --- | --- | --- |",
        ]
        for topic in [t for t in catalog["topics"] if t["subject_id"] == subject["subject_id"]]:
            lines.append(
                f"| `{topic['topic_id']}` | {'Assunto' if topic['kind'] == 'TOPIC' else 'Subassunto'} | "
                f"{topic['label']} | {topic['parent_topic_id'] or '—'} |"
            )
    lines += [
        "",
        "## Relações entre conteúdos",
        "",
        "Estas relações apoiam navegação e diagnóstico editorial; não transferem domínio automaticamente sem confirmação.",
        "",
        "| Origem | Destino | Relação | Justificativa |",
        "| --- | --- | --- | --- |",
    ]
    lines += [
        f"| `{r['from_topic_id']}` | `{r['to_topic_id']}` | {r['relation']} | {r['note']} |"
        for r in catalog["relations"]
    ]
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--check", action="store_true", help="also check the committed readable export")
    p.add_argument("--export", action="store_true", help="regenerate the readable export")
    a = p.parse_args()
    catalog = read(ROOT / "shared/knowledge-catalog.json")
    templates = read(ROOT / "shared/exam-templates.json")
    summary = validate(
        catalog,
        read(ROOT / "shared/knowledge-id-registry.json"),
        read(ROOT / "shared/knowledge-catalog.schema.json"),
        templates,
    )
    output = ROOT / "docs/catalogo/CONHECIMENTOS.md"
    if a.export:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(render(catalog), encoding="utf-8")
    if a.check and output.read_text(encoding="utf-8") != render(catalog):
        raise ValueError("Readable export is stale; run --export")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
