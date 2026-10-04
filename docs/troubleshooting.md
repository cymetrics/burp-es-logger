# Troubleshooting

## The extension will not load

Burp reports no entry point, or a `NoClassDefFoundError`. Check that you loaded
`burp-es-logger.jar` and not a thin jar from somewhere else — the release asset bundles
sqlite-jdbc and gson, and the entry point is declared in `META-INF/services`:

```bash
unzip -p burp-es-logger.jar META-INF/services/burp.api.montoya.BurpExtension
# io.cymetrics.eslogger.ExtensionMain
```

## Test connection says HTTP 403

Test connection first calls `_security/_authenticate`, which any valid key may call, so the normal
result is "credentials valid". A 403 only appears when that call fails and the fallback `GET /` is
refused for lack of cluster privileges; `_bulk` can still work. A 401 is the real failure — the key
is wrong or revoked.

## Nothing is uploading

In order of likelihood:

1. **Endpoint or API key not set.** The status line says so and the extension logs it once at load.
2. **Batch size is 0.** The settings clamp this now, but a value saved by an older build could still
   be 0, which makes every flush return nothing. Set it back to 500.
3. **Uploads fail with 403.** The key lacks `create_doc` or `auto_configure` on the index, or the
   index prefix is not `burp-log`, which the template and the documented key do not cover. 403 is
   treated as temporary, so the records stay queued until the key or prefix is fixed.
4. **Auto upload is off.** The checkbox is in the Upload section.

If the index template was never applied, uploads still succeed, but fields such as `tool` and
`request.url` are mapped dynamically as `text`, so the `term` queries and aggregations in
[queries.md](queries.md) find nothing. A field's type cannot be changed afterwards: apply the template
and reindex, or start a new index.

Burp's dashboard event log carries an error event once uploads have been failing for a minute, and
an info event when they recover.

## Records are being dropped

The dashboard raises a critical event when the in-memory spool overflows. It means Elasticsearch has
been unreachable, or slower than the capture rate, for long enough to accumulate 32 MB of pending
records. Enable **Spool pending records to local SQLite** to trade disk for completeness.

## Verification reports gaps

Gaps can be records that never reached the index or records removed later. The extension leaves a
gap when the memory spool or the write buffer overflows, and when Elasticsearch rejects a document
three times as invalid. Each case is reported while running. Traffic arriving during unload never
gets a `seq`, so it leaves no gap at all; it is counted and reported instead. A gap is not proof of
deletion, and the chain cannot tell the two apart; see [integrity.md](integrity.md).

## Building locally fails

Gradle needs JDK 17 through 24. On a machine whose default JDK is newer, the Kotlin DSL compiler
fails with `IllegalArgumentException: 27` before anything is built:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test shadowJar
```

## Upgrading an existing SQLite spool

New SQLite tables separate each index's backlog and chain tip. Old tables did not record an index,
so the upgrade cannot safely infer their destination from the currently selected project.

If the old tip exactly matches the original index's saved Burp preferences and all pending documents
belong to that project, the migration moves those records transactionally without changing their
IDs, sequence numbers or payloads. Otherwise the old records remain in legacy tables and a critical
event explains that they were preserved but not uploaded. Other projects can use their own empty
scoped spool without touching those records.

To recover, restore the original index prefix and Project ID and reload using the original Burp
user preferences. If those preferences are missing or the old backlog spans projects, keep a backup
of the SQLite database for manual destination recovery; do not delete it or relabel records to
force an upload. Records from other scoped indices also remain on disk until that index is reopened.

New v2 records authenticate their stored bodies and headers. Legacy records retain their historical
limits and the verifier prints a warning; upgrading cannot establish the integrity of old payloads
retroactively. Use `--require-v2` only when legacy records should be rejected.
