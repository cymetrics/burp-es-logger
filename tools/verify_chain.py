#!/usr/bin/env python3
"""Verify the hash chain of an ES Logger index.

Reads documents either from Elasticsearch or from a file of JSON documents
(one per line), recomputes every record_sha256 from the formula documented in
the README, and reports breaks and gaps.

    # straight from Elasticsearch (needs a key that can read the index)
    ES=https://your-endpoint API_KEY=... python3 tools/verify_chain.py burp-log-demo

    # or from an export
    python3 tools/verify_chain.py --file export.ndjson

Exit status is 0 when the chain verifies, 1 when it does not, so this can run
in a close-out script.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.request

GENESIS = "0" * 64


def field(doc: dict, path: str) -> str:
    """Fetch a dotted path, rendering a missing or null value as an empty string.

    The writer puts "" in the material for fields a record does not have (a
    request without a response, or hashes skipped in fast mode), so a missing
    key and an empty string have to hash the same way.
    """
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


def record_hash(doc: dict, prev_hash: str) -> str:
    """record_sha256 = sha256(prev_hash || for each field: len(utf8) || ':' || field)

    Every field is prefixed with its own byte length so that no field's content
    can forge a boundary between fields.
    """
    material = prev_hash
    for path in MATERIAL_FIELDS:
        value = field(doc, path)
        material += f"{len(value.encode('utf-8'))}:{value}"
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def fetch_from_elasticsearch(endpoint: str, api_key: str, index: str):
    """Page through the index with search_after, ordered by seq."""
    url = f"{endpoint.rstrip('/')}/{index}/_search"
    after = None
    while True:
        body = {"size": 1000, "sort": [{"seq": "asc"}], "query": {"match_all": {}}}
        if after is not None:
            body["search_after"] = [after]
        request = urllib.request.Request(
            url,
            data=json.dumps(body).encode(),
            headers={"Authorization": f"ApiKey {api_key}", "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request) as response:
            hits = json.load(response)["hits"]["hits"]
        if not hits:
            return
        for hit in hits:
            yield hit["_source"]
        after = hits[-1]["_source"]["seq"]


def verify(documents) -> int:
    prev_hash = GENESIS
    expected_seq = None
    checked = breaks = gaps = 0

    for doc in documents:
        checked += 1
        seq = doc.get("seq")

        if expected_seq is not None and seq != expected_seq:
            missing = seq - expected_seq
            print(f"GAP   after seq {expected_seq - 1}: {missing} record(s) never reached the index")
            gaps += 1
            # A gap means the predecessor is absent, so the next link cannot be
            # checked. Resume from this record's own stated predecessor.
            prev_hash = doc.get("integrity", {}).get("prev_hash", GENESIS)

        stated_prev = doc.get("integrity", {}).get("prev_hash", "")
        stated_hash = doc.get("integrity", {}).get("record_sha256", "")

        if stated_prev != prev_hash:
            print(f"BREAK seq {seq}: prev_hash points at {stated_prev[:16]}…, expected {prev_hash[:16]}…")
            breaks += 1
        computed = record_hash(doc, stated_prev)
        if computed != stated_hash:
            print(f"BREAK seq {seq}: record_sha256 is {stated_hash[:16]}…, recomputed {computed[:16]}…")
            breaks += 1

        prev_hash = stated_hash
        expected_seq = seq + 1

    print(f"\n{checked} record(s) checked, {breaks} break(s), {gaps} gap(s)")
    if breaks:
        print("The stored records do not match their own hashes: they were altered after capture.")
    elif gaps:
        print("Chain intact. The gaps are records that never reached the index, not deletions"
              " — unless someone removed a whole run, which this cannot distinguish.")
    else:
        print("Chain intact, no gaps.")
    return 1 if breaks else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("index", nargs="?", help="index to read from Elasticsearch, e.g. burp-log-demo")
    parser.add_argument("--file", help="read documents from a file instead (one JSON document per line)")
    args = parser.parse_args()

    if args.file:
        with open(args.file, encoding="utf-8") as handle:
            documents = [json.loads(line) for line in handle if line.strip()]
        documents.sort(key=lambda d: d.get("seq", 0))
        return verify(documents)

    if not args.index:
        parser.error("give an index name, or --file")
    endpoint, api_key = os.environ.get("ES"), os.environ.get("API_KEY")
    if not endpoint or not api_key:
        parser.error("set ES and API_KEY, or use --file")
    return verify(fetch_from_elasticsearch(endpoint, api_key, args.index))


if __name__ == "__main__":
    sys.exit(main())
