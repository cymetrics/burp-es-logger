# Useful queries

Replace `burp-log-demo` with your index. These run in Kibana's Dev Tools as-is.

## What did I send, and with which tool

```
GET burp-log-demo/_search
{
  "size": 20,
  "sort": [{ "seq": "asc" }],
  "query": { "bool": { "filter": [
    { "term": { "tool": "Repeater" } },
    { "term": { "request.host": "target.example.com" } }
  ]}},
  "_source": ["seq", "@timestamp", "request.method", "request.url", "response.status"]
}
```

## Everything in a time window

The window a client asks about after an incident:

```
GET burp-log-demo/_search
{
  "query": { "range": { "@timestamp": { "gte": "2026-10-02T06:00:00Z", "lt": "2026-10-02T07:00:00Z" } } },
  "sort": [{ "seq": "asc" }]
}
```

## Volume by tool and by tester

```
GET burp-log-demo/_search
{
  "size": 0,
  "aggs": {
    "by_tool":   { "terms": { "field": "tool" } },
    "by_tester": { "terms": { "field": "tester_id" } },
    "by_status": { "terms": { "field": "response.status" } }
  }
}
```

## Which records cannot be evidenced

Fast mode marks the messages whose body digest was skipped:

```
GET burp-log-demo/_count
{ "query": { "term": { "response.hashes_skipped": true } } }
```

The complement — records that do carry a body digest — is the `must_not exists` of the same field.

## Find the gaps

`seq` is contiguous unless records were dropped. The cheapest check is the count against the range:

```
GET burp-log-demo/_search
{ "size": 0, "aggs": { "seq": { "stats": { "field": "seq" } } } }
```

`count` below `max - min + 1` means records are missing. `tools/verify_chain.py` reports exactly
where.

## Searching bodies

Request and response bodies are indexed as `match_only_text`, so phrase search works:

```
GET burp-log-demo/_search
{ "query": { "match_phrase": { "response.body": "internal server error" } } }
```

Bodies excluded by the capture rules are absent — `body_stored: false` with a `body_skip_reason` —
but their length and digest are still there.
