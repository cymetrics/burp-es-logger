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

Nothing in the pipeline can grow without limit:

| Stage | Limit | Behaviour at the limit |
|---|---|---|
| Capture queue | 64 MB (UTF-8 bytes) | Blocks the Burp thread — slow rather than lossy |
| Write buffer | 1280 records | Drops oldest, counts them, raises a critical event |
| Memory spool | 32 MB | Drops oldest, counts them, raises a critical event |
| `_bulk` request | 8 MB, halved on 413 | Splits the batch |
| SQLite spool | Disk | Grows with the backlog; WAL truncated at 8 MB |

Anything dropped leaves a gap in `seq`, which is visible at verification time. The one exception is
traffic that arrives after the extension has been told to unload: it never receives a `seq`, so it
is counted and reported separately.

## Threads and shutdown

`RecordWriter` and `ElasticUploader` each own one daemon thread; the panel runs on the EDT. Unload
order matters and is enforced in `ExtensionMain`:

1. Deregister the HTTP handler — otherwise traffic keeps arriving with nothing to record it.
2. Stop the writer and join it, so the queue drains and pending records reach the spool.
3. Stop the uploader and join it, then drain the spool exclusively for up to 15 seconds.
4. Close the spool, then stop the panel's timer.
