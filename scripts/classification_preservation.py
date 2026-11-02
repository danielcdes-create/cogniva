"""Compare question snapshots after an authorized classification write.

Historical learning events and neighboring collections must stay identical.
A different document hash is not permission to change content, caches, answers, or extra fields.
"""
AUTHORIZED_FIELDS = frozenset({
    "classification",
    "canonical_subject_id",
    "canonical_topic_id",
    "revision",
    "content_revision",
    "content_hash_version",
})
CONTENT_FIELDS = (
    "question",
    "question_type",
    "answers",
    "correct_answers",
    "correct_answer_indices",
    "grading",
)


def _identity(document):
    identifier = document.get("_id")
    if isinstance(identifier, dict):
        return identifier.get("$oid") or json_key(identifier)
    return identifier


def json_key(value):
    import json
    return json.dumps(value, sort_keys=True, ensure_ascii=False, default=str)


def compare_snapshots(before_questions, after_questions, before_history, after_history, before_neighbor, after_neighbor):
    if before_history != after_history:
        raise ValueError("Learning history changed")
    if before_neighbor != after_neighbor:
        raise ValueError("Neighboring service documents changed")
    before_map = {_identity(document): document for document in before_questions}
    after_map = {_identity(document): document for document in after_questions}
    if len(before_map)!=len(before_questions) or len(after_map)!=len(after_questions):
        raise ValueError("Duplicate question identities")
    if set(before_map) != set(after_map):
        raise ValueError("Question identities changed")
    changed = []
    for identifier, before in before_map.items():
        after = after_map[identifier]
        for field in CONTENT_FIELDS:
            if before.get(field) != after.get(field):
                raise ValueError(f"Content field changed: {field}")
        keys = set(before) | set(after)
        delta = sorted(key for key in keys if (key in before)!=(key in after) or before.get(key) != after.get(key))
        unexpected = [key for key in delta if key not in AUTHORIZED_FIELDS]
        if unexpected:
            raise ValueError("Unauthorized question field changed: " + ", ".join(unexpected))
        if delta:
            changed.append({"id": identifier, "fields": delta})
    return {"history_unchanged": True, "neighbor_unchanged": True, "authorized_changes": changed}
