#!/usr/bin/env python3
"""Verify ES Logger records from Elasticsearch or an NDJSON export.

New v2 records authenticate all stored JSON fields, including headers and bodies.
Legacy v1 records can only authenticate their selected metadata and captured digest
strings; the verifier always warns that their stored payload is unprotected.

    ES=https://endpoint API_KEY=... python3 tools/verify_chain.py burp-log-demo
    python3 tools/verify_chain.py --file export.ndjson

Exit 1 indicates invalid records. --strict also fails on sequence gaps. A gap
never proves whether records failed to upload or were subsequently deleted.
"""
from __future__ import annotations

import argparse
from decimal import Decimal
import hashlib
import json
import os
import re
import sys
import urllib.request

GENESIS = "0" * 64
SCHEME_V2 = "burp-es-logger-v2"
LEGACY_SCHEME = (
    "record_sha256 = sha256(prev_hash || for each field: len(utf8) || ':' || field); "
    "fields = seq, doc_id, type, session_id, tester_id, project_id, capture_host, "
    "request.time, response.time, tool, request.method, request.url, response.status, "
    "request.raw_sha256, request.body_sha256, response.raw_sha256, response.body_sha256"
)
HEX_HASH = re.compile(r"[0-9a-f]{64}\Z")


def _decimal_text(value) -> str:
    number = value if isinstance(value, Decimal) else Decimal(str(value))
    if not number.is_finite():
        raise ValueError("non-finite JSON number")
    if number == 0:
        return "0"
    text = format(number, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def payload_hash(doc: dict) -> str:
    """Hash the typed canonical encoding specified in docs/integrity.md."""
    digest = hashlib.sha256()

    def ascii_text(value: str):
        digest.update(value.encode("ascii"))

    def string(value: str):
        data = value.encode("utf-16-be", errors="surrogatepass")
        ascii_text(f"s{len(data)}:")
        digest.update(data)

    def encode(value, root=False):
        if value is None:
            ascii_text("n")
        elif isinstance(value, bool):
            ascii_text("t" if value else "f")
        elif isinstance(value, dict):
            keys = [key for key in value if not (root and key == "integrity")]
            keys.sort(key=lambda key: key.encode("utf-16-be", errors="surrogatepass"))
            ascii_text(f"o{len(keys)}:")
            for key in keys:
                string(key)
                encode(value[key])
        elif isinstance(value, list):
            ascii_text(f"a{len(value)}:")
            for child in value:
                encode(child)
        elif isinstance(value, (int, float, Decimal)):
            number = _decimal_text(value)
            ascii_text(f"d{len(number)}:{number}")
        elif isinstance(value, str):
            string(value)
        else:
            raise ValueError(f"unsupported JSON value: {type(value).__name__}")

    encode(doc, root=True)
    return digest.hexdigest()


def field(doc: dict, path: str) -> str:
    node = doc
    for part in path.split("."):
        if not isinstance(node, dict) or part not in node:
            return ""
        node = node[part]
    if node is None:
        return ""
    if isinstance(node, bool):
        return "true" if node else "false"
    return str(node)


MATERIAL_FIELDS = [
    "seq", "doc_id", "type",
    "session_id", "tester_id", "project_id", "capture_host",
    "request.time", "response.time",
    "tool", "request.method", "request.url", "response.status",
    "request.raw_sha256", "request.body_sha256",
    "response.raw_sha256", "response.body_sha256",
]
WS_MATERIAL_FIELDS = [
    "seq", "doc_id", "type",
    "session_id", "tester_id", "project_id", "capture_host",
    "@timestamp", "", "tool", "websocket.direction", "websocket.url",
    "websocket.is_text", "websocket.body_sha256", "", "", "",
]


def hash_fields(fields, prev_hash: str) -> str:
    digest = hashlib.sha256(prev_hash.encode("ascii"))
    for value in fields:
        # Match JVM UTF-8's replacement of an unpaired UTF-16 surrogate.
        data = value.encode("utf-8", errors="replace")
        digest.update(f"{len(data)}:".encode("ascii"))
        digest.update(data)
    return digest.hexdigest()


def record_hash(doc: dict, prev_hash: str) -> str:
    if doc.get("integrity", {}).get("scheme") == SCHEME_V2:
        return hash_fields([SCHEME_V2, payload_hash(doc)], prev_hash)
    paths = WS_MATERIAL_FIELDS if doc.get("type") == "websocket" else MATERIAL_FIELDS
    values = [field(doc, path) if path else "" for path in paths]
    # Historical response-only records stored tool="unknown" but hashed "".
    if doc.get("type") == "http_response_only":
        values[9] = ""
    return hash_fields(values, prev_hash)


def fetch_from_elasticsearch(endpoint: str, api_key: str, index: str):
    """Read a single writer's index ordered by seq; stop capture before verifying."""
    url = f"{endpoint.rstrip('/')}/{index}/_search"
    after = None
    while True:
        body = {"size": 1000, "sort": [{"seq": "asc"}], "query": {"match_all": {}}}
        if after is not None:
            body["search_after"] = [after]
        request = urllib.request.Request(
            url, data=json.dumps(body).encode(),
            headers={"Authorization": f"ApiKey {api_key}", "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            result = json.load(response, parse_float=Decimal)
        if result.get("timed_out") or result.get("_shards", {}).get("failed", 0):
            raise ValueError("Elasticsearch returned an incomplete search; verification aborted")
        hits = result["hits"]["hits"]
        if not hits:
            return
        for hit in hits:
            yield hit["_source"]
        after = hits[-1]["_source"]["seq"]


def verify(documents, *, strict=False, require_v2=False) -> int:
    prev_hash, expected_seq = GENESIS, 1
    checked = breaks = gaps = legacy = 0

    for doc in documents:
        checked += 1
        if not isinstance(doc, dict):
            print(f"BREAK record {checked}: expected a JSON object")
            breaks += 1
            continue
        seq = doc.get("seq")
        if isinstance(seq, bool) or not isinstance(seq, int) or seq < 1:
            print(f"BREAK record {checked}: seq must be a positive integer")
            breaks += 1
            continue
        integrity = doc.get("integrity")
        if not isinstance(integrity, dict):
            print(f"BREAK seq {seq}: missing integrity metadata")
            breaks += 1
            continue
        scheme = integrity.get("scheme")
        if scheme not in (SCHEME_V2, LEGACY_SCHEME) or integrity.get("algo") != "sha256":
            print(f"BREAK seq {seq}: unsupported or missing integrity scheme/algorithm")
            breaks += 1
            continue
        stated_prev = integrity.get("prev_hash")
        stated_hash = integrity.get("record_sha256")
        if not all(isinstance(value, str) and HEX_HASH.fullmatch(value)
                   for value in (stated_prev, stated_hash)):
            print(f"BREAK seq {seq}: malformed integrity digest")
            breaks += 1
            continue

        if seq < expected_seq:
            print(f"BREAK seq {seq}: duplicate or out-of-order sequence; expected {expected_seq}")
            breaks += 1
        elif seq > expected_seq:
            print(f"GAP   missing seq {expected_seq} through {seq - 1}; cause unknown")
            gaps += 1
            prev_hash = stated_prev
        if seq >= expected_seq and stated_prev != prev_hash:
            print(f"BREAK seq {seq}: prev_hash points at {stated_prev[:16]}…, expected {prev_hash[:16]}…")
            breaks += 1

        if scheme == SCHEME_V2:
            expected_keys = {"algo", "scheme", "payload_sha256", "prev_hash", "record_sha256"}
            computed_payload = payload_hash(doc)
            if set(integrity) != expected_keys or integrity.get("payload_sha256") != computed_payload:
                print(f"BREAK seq {seq}: stored payload or integrity metadata does not match payload_sha256")
                breaks += 1
            computed = hash_fields([SCHEME_V2, computed_payload], stated_prev)
        else:
            legacy += 1
            computed = record_hash(doc, stated_prev)
        if computed != stated_hash:
            print(f"BREAK seq {seq}: record_sha256 is {stated_hash[:16]}…, recomputed {computed[:16]}…")
            breaks += 1
        prev_hash, expected_seq = stated_hash, seq + 1

    print(f"\n{checked} record(s) checked, {breaks} break(s), {gaps} gap(s)")
    if legacy:
        print(f"WARNING: {legacy} legacy v1 record(s): stored bodies, headers and other unchained fields "
              "are NOT authenticated. Original payload integrity cannot be recovered retroactively.")
    if not checked:
        print("No records to verify; no completeness or integrity claim can be made.")
    elif breaks:
        print("Verification failed: stored records, chain links or schema do not match.")
    elif gaps:
        print("Available records match their hashes, but the chain is incomplete. "
              "Missing uploads and later deletions cannot be distinguished.")
    else:
        print("Available chain links and authenticated fields match; no sequence gaps found. "
              "Tail truncation requires an external anchor to detect.")
    return int(not checked or bool(breaks) or (bool(gaps) and strict) or (bool(legacy) and require_v2))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("index", nargs="?", help="Elasticsearch index, e.g. burp-log-demo")
    parser.add_argument("--file", help="one JSON document per line")
    parser.add_argument("--strict", action="store_true", help="also fail on sequence gaps")
    parser.add_argument("--require-v2", action="store_true", help="fail if legacy records cannot authenticate stored payloads")
    args = parser.parse_args()
    try:
        if args.file:
            if args.index:
                parser.error("use either an index or --file, not both")
            with open(args.file, encoding="utf-8") as handle:
                documents = [json.loads(line, parse_float=Decimal) for line in handle if line.strip()]
            # Keep malformed values for verify() to diagnose without comparing incompatible types.
            documents.sort(key=lambda d: d.get("seq", 0) if isinstance(d, dict) and type(d.get("seq")) is int else 0)
            return verify(documents, strict=args.strict, require_v2=args.require_v2)
        if not args.index:
            parser.error("give an index name, or --file")
        endpoint, api_key = os.environ.get("ES"), os.environ.get("API_KEY")
        if not endpoint or not api_key:
            parser.error("set ES and API_KEY, or use --file")
        return verify(fetch_from_elasticsearch(endpoint, api_key, args.index),
                      strict=args.strict, require_v2=args.require_v2)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
