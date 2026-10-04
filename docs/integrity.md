# Integrity

New records use `integrity.scheme: "burp-es-logger-v2"`. The chain authenticates all
stored document fields, including headers, text or base64 bodies, truncation flags,
identity, timestamps and captured-message digests. The top-level `integrity` object
is excluded from the payload digest to avoid a circular dependency; its required
fields and scheme are validated separately.

The captured-message digests have a different purpose:

| Field | Covers |
|---|---|
| `request.raw_sha256` / `response.raw_sha256` | The complete message captured by Burp |
| `request.body_sha256` / `response.body_sha256` | The original complete body, when computed |
| `websocket.body_sha256` | The complete captured WebSocket payload |
| `integrity.payload_sha256` | The values of all stored document fields except top-level `integrity` |
| `integrity.record_sha256` | The v2 scheme, payload digest, and previous record's hash |

Stored text can contain replacement characters after UTF-8 decoding, and stored
bodies can be truncated or omitted. The payload digest authenticates that stored
representation. It does **not** claim that omitted bytes can be recovered from a
hash. Original raw/body digests remain available to compare with an independently
retained original message.

Before hashing and UTF-8 serialization, the writer replaces unpaired UTF-16
surrogates in metadata with U+FFFD. Valid Unicode, including supplementary
characters, is preserved. This prevents the transport encoder from changing a
string after its digest was computed.

## The v2 formula

```
payload_sha256 = SHA256(canonical(document without top-level integrity))
fields = [ "burp-es-logger-v2", payload_sha256 ]
material = prev_hash || for each field: len(UTF-8 bytes) || ":" || field
record_sha256 = SHA256(UTF-8(material))
```

`prev_hash` is 64 lowercase hexadecimal characters. A new chain starts with 64
zeros. A v2 record can continue a legacy chain using the previous legacy record's
hash; upgrading does not rewrite old records or restart the sequence.

The canonical encoding is a typed byte encoding. Tags, counts, lengths, colons,
and normalized numbers below are ASCII; lengths/counts are unsigned decimal with
no leading zeros. Values are encoded recursively:

| JSON value | Encoding |
|---|---|
| null | `n` |
| true / false | `t` / `f` |
| string | `s<byte length>:` followed by its UTF-16 big-endian code units, without a BOM |
| number | `d<byte length>:` followed by its normalized decimal representation |
| array | `a<element count>:` followed by each encoded element in order |
| object | `o<field count>:` followed by each encoded key string and encoded value |

Object keys are sorted lexicographically by unsigned UTF-16 code units. This is
Java string order; Python sorts the UTF-16BE bytes, rather than its default Unicode
code-point order. Strings preserve all code units, including unpaired surrogates;
no Unicode normalization is performed. Numbers are exact finite decimals, written
without an exponent, redundant fractional trailing zeros, or a trailing decimal
point. All representations of zero become `0`. For example, `1.2300e2` becomes
`123`, and `-0.00` becomes `0`. JSON whitespace, object key order, and equivalent
number spellings therefore do not affect the digest. Nested fields named
`integrity` remain covered; only the root field is excluded.

The shared test vector in `tools/fixtures/canonical-v2.json` checks these rules in
both Kotlin and Python, including Unicode ordering, surrogate code units, large
integers, arrays, booleans and decimal number forms.

## Verifying

Stop capture and finish uploading before verification, or export a stable snapshot.
The live Elasticsearch reader assumes **one writer chain per index** and sequences
that are unique within that index. Independent Burp installations must use separate
project/index names. Concurrent capture and multiple independent writers in one
index are not supported by this verifier's `search_after` reader.

```bash
# Directly from Elasticsearch, using a key that can read the index
ES=https://your-endpoint API_KEY=... python3 tools/verify_chain.py burp-log-demo --strict

# One complete _source JSON document per line
python3 tools/verify_chain.py --file export.ndjson --strict

# Also require full stored-payload protection; rejects legacy v1 records
python3 tools/verify_chain.py --file export.ndjson --strict --require-v2
```

Exit status is 1 for malformed/unsupported records, invalid hashes, broken links,
duplicate sequences, or an empty input. `--strict` additionally rejects sequence
gaps; without it, available records can verify while the tool reports an incomplete
chain. A gap cannot establish whether a record failed to upload or was deleted
later. `--require-v2` additionally rejects legacy records that cannot authenticate
their stored payloads. An Elasticsearch timeout or partial shard result is an error.

CI runs the Python verifier tests and feeds actual Kotlin writer output through the
Python command for HTTP, request-only, response-only, and WebSocket records, as
well as binary, truncated, excluded and Unicode payloads. It also checks that
editing stored bodies or headers fails verification.

## Legacy v1 records

Older records store a long formula string in `integrity.scheme`. The verifier
recognizes that exact scheme and reconstructs the original format, including its
WebSocket-specific field order and the historical response-only quirk where the
stored tool was `"unknown"` but the hashed value was empty.

The HTTP field order was:

```
seq, doc_id, type, session_id, tester_id, project_id, capture_host,
request.time, response.time, tool, request.method, request.url, response.status,
request.raw_sha256, request.body_sha256, response.raw_sha256, response.body_sha256
```

WebSocket records instead used:

```
seq, doc_id, type, session_id, tester_id, project_id, capture_host,
@timestamp, "", tool, websocket.direction, websocket.url, websocket.is_text,
websocket.body_sha256, "", "", ""
```

Both used the same length-prefixed UTF-8 formula with `prev_hash` in front. Missing
fields were empty strings and booleans were lowercase `true`/`false`.

**Legacy bodies, headers, and other omitted fields were never authenticated by the
record hash.** A successful legacy check only verifies its listed fields and links.
The verifier always prints this limitation; historical payload integrity cannot be
recovered by upgrading the verifier. Changing the scheme label does not migrate a
record: it causes verification to fail. Do not relabel old records as v2.

## What this does and does not prove

For v2, editing a stored payload or metadata field without recomputing the chain is
detected. Missing sequence numbers are visible, but upload losses and later
deletions are indistinguishable. Tail truncation leaves no following record that
could expose the deletion. Someone with write access to the entire index can also
recompute the chain. A hash chain is not a signature or independent attestation.

For evidence that must withstand those changes, retain a trusted final sequence
number and its `record_sha256` outside the index and outside the writer key's
permissions, then compare the verified final record against that anchor. An anchor
only authenticates history through that recorded point, not subsequent records.
