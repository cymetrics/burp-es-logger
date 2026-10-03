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

Expected, and the message says so. The append-only key has no cluster privileges, so `GET /` is
refused while `_bulk` still works. A 401 is the real failure — the key is wrong or revoked.

## Nothing is uploading

In order of likelihood:

1. **Endpoint or API key not set.** The status line says so and the extension logs it once at load.
2. **Batch size is 0.** The settings clamp this now, but a value saved by an older build could still
   be 0, which makes every flush return nothing. Set it back to 500.
3. **The index template was never applied.** `_bulk` then fails on mapping, and the Extensions log
   names the rejected documents.
4. **Auto upload is off.** The checkbox is in the Upload section.

Burp's dashboard event log carries an error event once uploads have been failing for a minute, and
an info event when they recover.

## Records are being dropped

The dashboard raises a critical event when the in-memory spool overflows. It means Elasticsearch has
been unreachable, or slower than the capture rate, for long enough to accumulate 32 MB of pending
records. Enable **Spool pending records to local SQLite** to trade disk for completeness.

## Verification reports gaps

Gaps are records that never reached the index — the extension drops the oldest when the spool fills,
and traffic arriving during unload never gets a `seq` at all. Both are reported while running. A gap
is not proof of deletion, and the chain cannot tell the two apart; see
[integrity.md](integrity.md).

## Building locally fails

Gradle needs JDK 17 through 24. On a machine whose default JDK is newer, the Kotlin DSL compiler
fails with `IllegalArgumentException: 27` before anything is built:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test shadowJar
```
