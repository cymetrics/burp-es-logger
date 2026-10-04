# Architecture

```
Burp request threads          writer thread                 uploader thread
─────────────────────         ─────────────                 ───────────────
HttpCaptureHandler            RecordWriter                  ElasticUploader
  raw bytes + offset   ──▶      pair by messageId    ──▶       _bulk create
  (no parsing, no hash)         body policy                    byte-capped batch
         │                      SHA-256 + chain                exponential backoff
         │                      batch into spool                     │
         ▼                            │                              ▼
  64 MB byte budget            RecordSpool (outbox)            Elasticsearch
  blocks when full          MemorySpool │ SqliteStore         purge on success
```

## Why it is shaped this way

**The capture callback does as little as possible.** It runs on Burp's request path, so every
microsecond there is paid by the person using Burp. It copies nothing: the handler keeps Montoya's
own byte array plus the body offset, and header decoding, hashing and JSON serialisation all happen
on the writer thread.

**One writer thread owns the chain.** `seq` and `prev_hash` have to advance in a single order, so
exactly one thread assigns them. That also means the request/response pairing map needs no locking.

**The spool is an outbox, not an archive.** Records are deleted as soon as Elasticsearch confirms
them, so its size tracks the backlog rather than the volume of traffic. `MemorySpool` is the
default and never touches disk; `SqliteStore` is opt-in for engagements where losing unsent records
is unacceptable.

**Uploads bypass Burp.** A plain JDK `HttpClient` pinned to `NO_PROXY`, so the extension never logs
its own traffic and the API key cannot reach the proxy history — not even if Burp was started
behind another proxy.

## Bounds

Retained capture buffers have independent limits. These are not a process heap ceiling:
processing can temporarily hold an oversized message or document, and the SQLite backlog grows on disk.

| Stage | Limit | Behaviour at the limit |
|---|---|---|
| Capture queue | 64 MiB raw bytes + estimated overhead, or 20,000 events | Blocks the Burp thread at either limit |
| Pending requests | 64 MiB raw bytes or 20,000 requests | Finalizes oldest requests as request-only; late responses are recorded separately |
| Write buffer | 32 MiB serialized UTF-8 or 1,280 records | Drops oldest, counts them, raises a critical event; a single record larger than the whole budget is dropped alone |
| Memory spool | 32 MiB | Drops oldest, counts them, raises a critical event; a single record larger than the whole budget is dropped alone |
| `_bulk` request | 8 MiB, reduced on 413 | Reads SQLite incrementally within a byte budget; an oversized first record is attempted alone |
| SQLite spool | Disk | Grows with the backlog; the WAL is truncated back to 8 MiB after checkpoints |

Pending-request pressure does not discard requests: it finalizes them early and reports the change.
Spool write failures retain a bounded buffer and retry with at least a one-second cooldown.

Anything dropped from the spool or write buffer leaves a gap in `seq`, which is visible at verification time. The one exception is
traffic that arrives after the extension has been told to unload: it never receives a `seq`, so it
is counted and reported separately.

## Threads and shutdown

`RecordWriter` and `ElasticUploader` each own one daemon thread; the panel runs on the EDT. Unload
order matters and is enforced in `ExtensionMain`:

1. Deregister the HTTP handler — otherwise traffic keeps arriving with nothing to record it.
2. Stop the writer and join it, so the queue drains and pending records reach the spool.
3. Stop the uploader and join it, then drain the spool exclusively for up to 15 seconds.
4. Close the spool, then stop the panel's timer.

## Project isolation and upgrades

The writer, uploader and SQLite instance use the project/index selected at extension load.
Saving another destination takes effect after reload, so queued records keep their original target.
SQLite keys both outbox records and chain tips by index. Its batch reader checks the persisted UTF-8
byte count before materializing each JSON document, avoiding a full 500-document allocation.

Legacy unscoped SQLite data is preserved until its stored tip matches the original index's saved
preferences and its pending documents match the original project. Unidentified data stays in legacy
tables and raises a critical event; see [recovery](troubleshooting.md#upgrading-an-existing-sqlite-spool).
