# ES Logger

[![Build](https://github.com/cymetrics/burp-es-logger/actions/workflows/build.yml/badge.svg)](https://github.com/cymetrics/burp-es-logger/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A Burp Suite extension that mirrors the HTTP and WebSocket traffic Burp handles into
Elasticsearch, so an engagement's requests stay searchable long after the Burp project is closed.
Records are hash-chained, which makes later edits to the stored copy detectable.

**What it is not: a complete record of an engagement.** It sees what passes through Burp and
nothing else. sqlmap, ffuf, nuclei, nmap, your own scripts and any traffic you did not proxy are
simply absent — see [Scope](#what-this-does-not-capture).

> 中文說明請見 [README.zh-TW.md](README.zh-TW.md)

## What it does

- **Captures everything Burp sends.** Hooks `api.http()`, so Proxy, Repeater, Intruder, Scanner
  and other extensions are all covered — not just proxied browser traffic.
- **Pairs requests with responses** by `messageId`. A request that never gets a response is still
  written as `request_only` once it times out, so nothing silently disappears.
- **Hash-chains every record.** Each document carries `raw_sha256` and `body_sha256`, plus
  `record_sha256 = SHA256(material ‖ prev_hash)`. Deleting, inserting or editing a record in the
  middle of a run breaks the chain.
- **Never duplicates.** `_bulk` uses `create` with a client-generated `_id`, so a resend after a
  network failure returns 409 and is treated as already stored.
- **Stays out of your way.** The capture callback only grabs bytes and hands them to a background
  thread. Uploads use a plain JDK `HttpClient` that does not go through Burp, so the extension
  never logs its own traffic and the API key never lands in the proxy history.

## What this does not capture

The extension hooks `api.http()`, so everything **Burp** sends is covered: Proxy, Repeater,
Intruder, Scanner, other extensions. Nothing outside Burp is.

Most tools can be pointed at Burp's proxy, which brings them into the same log:

```bash
sqlmap --proxy http://127.0.0.1:8080
ffuf   -x http://127.0.0.1:8080
nuclei -proxy http://127.0.0.1:8080
curl   -x http://127.0.0.1:8080 -k
export HTTP_PROXY=http://127.0.0.1:8080 HTTPS_PROXY=http://127.0.0.1:8080
```

Anything that does not speak HTTP through a proxy — nmap, DNS, raw sockets, an SSH tunnel — stays
outside the record no matter what. Treat the index as "the Burp half of the engagement", and say so
in the report rather than implying it is the whole of it.

## Design trade-offs

These are deliberate. Read them before deploying this on an engagement.

**The local spool is not an archive.** By default nothing is written to disk: pending records live
in a 32 MB in-memory queue and are deleted the moment Elasticsearch confirms them. If Burp exits
or the backlog overflows, unsent records are lost — and because `seq` keeps counting, the gap is
visible rather than silent. Enable *Spool pending records to SQLite* if you would rather trade disk
for completeness.

**Elasticsearch is the only source of truth.** A hash chain proves that what you have was not
altered. It does not prove that nothing is missing. Combine it with an append-only API key
(see [`elasticsearch/setup.md`](elasticsearch/setup.md)) so a compromised testing machine cannot
rewrite history.

**Fast mode skips the body hash for excluded static assets.** Images, fonts and CSS are the client's
own content, and their bodies are not stored anyway, so the separate `body_sha256` pass buys little
evidence and costs real time. `raw_sha256` is always computed, so the message as a whole — headers,
status line and body bytes — stays provable; only the standalone body digest is omitted, and the
record is marked `hashes_skipped: true`. Turn it off if you want every digest unconditionally.

**A record Elasticsearch will never accept is eventually dropped.** Server errors, throttling and
authentication failures are retried indefinitely — the data is fine, the server or the key is not.
But a document Elasticsearch rejects on its own merits (a mapping conflict, say) would otherwise
block every record behind it forever. Such a batch is halved repeatedly to isolate the offending
record, which is then dropped after three attempts. The extension says so loudly in Burp's Extensions
log, keeps a visible counter in the tab, and leaves the gap in `seq` as evidence.

**Bodies are filtered, but their fingerprints are not.** Excluded or truncated bodies still record
`body_len` and (unless fast mode applies) `body_sha256`, which is enough to prove a specific
payload passed through without storing it.

## Build from source

Requires JDK 17. The Gradle wrapper is included.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew shadowJar
# → build/libs/burp-es-logger.jar
```

The jar bundles native SQLite binaries for macOS, Windows and Linux (including musl), so the same
artifact works everywhere Burp runs.

## Install

Download the latest `burp-es-logger.jar` from
[Releases](https://github.com/cymetrics/burp-es-logger/releases), or build it yourself (below).
Each release ships a `.sha256` next to the jar — verify it before loading anything into Burp:

```bash
shasum -a 256 -c burp-es-logger.jar.sha256
```

Burp → Extensions → Add → Extension type **Java** → select the jar. An **ES Logger** tab appears.

Enable **Auto-reload** on the extension if you plan to rebuild it — Burp then picks up a new jar on
its own.

## Set up Elasticsearch

Follow [`elasticsearch/setup.md`](elasticsearch/setup.md): it creates the index template and an
append-only API key that can create documents but cannot read, overwrite or delete them.
[`elasticsearch/dev-tools.txt`](elasticsearch/dev-tools.txt) is the same thing ready to paste into
Kibana's Dev Tools console.

Then fill in the tab: endpoint, API key, index prefix, tester ID, project ID → **Save settings** →
**Test connection**.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Elasticsearch endpoint | — | Full URL, no trailing slash |
| API key (encoded) | — | Needs only `create_doc` on `burp-log-*` |
| Index prefix | `burp-log` | Actual index is `<prefix>-<project id>` |
| Tester / Project ID | — | Recorded on every document; project ID also names the index |
| Skip bodies for extensions | `js,gif,jpg,jpeg,png,ico,css,woff,woff2,ttf,svg` | Matched on both extension and Content-Type |
| Max body size | 2 MB | Larger bodies keep a truncated slice plus the full length and hash |
| Response timeout | 120 s | After this a request is written as `request_only` |
| Store bodies | on | Off keeps hashes only |
| Record WebSocket messages | on | |
| Fast mode | **on** | Skip hashing for excluded static assets |
| Upload interval | 15 s | Idle polling only; a backlog is sent back-to-back |
| Batch size | 500 | Also capped at 8 MB per `_bulk` request |
| Upload automatically | on | |
| Spool to SQLite | **off** | On = survive restarts at the cost of disk |

## Document shape

```jsonc
{
  "@timestamp": "2026-10-02T07:49:05.323Z",
  "seq": 1234, "doc_id": "uuid", "session_id": "uuid",
  "tester_id": "zet", "project_id": "acme2026", "capture_host": "laptop",
  "type": "http", "tool": "Proxy",
  "request":  { "time", "method", "url", "host", "port", "secure", "headers",
                "body" | "body_b64", "body_stored", "body_len", "body_sha256",
                "body_truncated", "body_skip_reason", "raw_sha256", "hashes_skipped" },
  "response": { "time", "status", "headers", "body" | "body_b64", "...": "same as request" },
  "integrity": { "algo": "sha256", "scheme": "...", "prev_hash": "...", "record_sha256": "..." }
}
```

`type` is one of `http`, `http_request_only`, `http_response_only`, `websocket`.

## Verifying the chain

Sort by `seq` and recompute. Each field is prefixed with its own length in UTF-8 bytes, so no
field content can forge a boundary between fields:

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
fixed at 64 hex characters. Absent fields are empty strings — which is also what fast mode produces
for skipped hashes. WebSocket records use a slightly different field list; see `RecordWriter.kt`.

Every document also carries this formula in `integrity.scheme`, so a single document is enough to
know how to verify it.

A gap in `seq` means records never reached Elasticsearch. A record whose hash does not match means
the stored document was altered.

## Before you run this on a real engagement

- These logs contain the target's **credentials, session tokens and personal data**, and they leave
  your machine for a third-party cloud. Confirm the engagement contract and NDA allow it, and check
  whether data residency is constrained.
- Scanner and Intruder can produce hundreds of thousands of records in one run. Estimate volume and
  cost first; consider turning bodies off or lowering the size limit for those tools.
- The API key is stored in plaintext in Burp's user preferences. Protect the testing machine, and
  revoke the key when the engagement closes.

## Known limitations

- The API key is not stored in an OS keychain.
- Binary bodies grow about 33% as base64 in Elasticsearch; the size limit and truncation bound this.
- Text bodies are decoded as UTF-8, so invalid bytes become U+FFFD and the stored text will not match
  `body_sha256`. `raw_sha256` still covers the original bytes.
- A hash chain detects tampering; it does not prevent it. Immutability comes from the append-only
  API key, and from whatever external notarization you add on top.
- **Truncating the tail is not detectable from the index alone.** Deleting a record in the middle
  breaks the links, but deleting the newest N records leaves a chain that verifies end to end,
  because nothing records what the head should be. If that matters for your engagement, export the
  current `record_sha256` out of band — into the report, a ticket, or a second index the Burp key
  cannot write — at the points you care about.
- Changing the index prefix or project ID mid-session sends records that were already captured to
  the new index, so one chain ends up split across two indices. Set them before you start capturing.

## License

MIT — see [LICENSE](LICENSE).
