# Integrity

Each record carries three digests:

| Field | Covers |
|---|---|
| `request.raw_sha256` / `response.raw_sha256` | The whole message as it crossed the wire |
| `request.body_sha256` / `response.body_sha256` | The body alone, even when the body is not stored |
| `integrity.record_sha256` | This record's fields plus the previous record's hash |

The third one is what makes it a chain.

## The formula

```
fields = [ seq, doc_id, type,
           session_id, tester_id, project_id, capture_host,
           request.time, response.time,
           tool, request.method, request.url, response.status,
           request.raw_sha256, request.body_sha256,
           response.raw_sha256, response.body_sha256 ]

material = prev_hash  ‖  for each field:  len(field in UTF-8 bytes) ‖ ":" ‖ field

record_sha256 = SHA256(material)
```

The first record's `prev_hash` is 64 zeros, and `prev_hash` leads the material because its length is
fixed at 64 hex characters. A field the record does not have — a request with no response, or a
digest skipped by fast mode — participates as an empty string.

Every field is prefixed with its own byte length rather than separated by a delimiter. With a
delimiter, a field whose content contains that delimiter can forge a boundary, so two different
records hash identically; `url` is attacker-influenced, which makes that reachable. Length prefixing
gives every combination of fields exactly one encoding.

Each document also states this formula in `integrity.scheme`, so a single document is enough to know
how to verify it.

## Verifying

[`tools/verify_chain.py`](../tools/verify_chain.py) implements the formula independently of the
extension and reports breaks and gaps:

```bash
# straight from Elasticsearch, with a key that can read the index
ES=https://your-endpoint API_KEY=... python3 tools/verify_chain.py burp-log-demo

# or from an export, one JSON document per line
python3 tools/verify_chain.py --file export.ndjson
```

It exits non-zero when a record does not match its own hash, so it can run in a close-out script.

The project's own test suite re-derives every `record_sha256` from a second implementation of this
same formula, which is what keeps the code and this document from drifting apart.

## What this does and does not prove

**Detected:** a stored document edited after capture, and a record removed from the middle of a run
— the next record's `prev_hash` then points at something that is not there.

**Visible but not prevented:** records that never arrived. `seq` keeps counting when the spool drops
records or the extension is unloaded under traffic, so gaps show up at verification time. The
extension reports them while running, too.

**Not detected:** truncation of the tail. Deleting the newest N records leaves a chain that verifies
end to end, because nothing in the index records what the head should be. Anyone who can delete can
also recompute the whole chain from scratch.

The fix for both is an anchor outside the index. At the points you care about, copy the current
`record_sha256` somewhere the Burp key cannot write — the report, a ticket, a second index. Anything
removed after that anchor then stops verifying.
