# Architecture

FinDesk is a single-module Kotlin JVM + Compose Desktop (Material 3)
application in the package `de.findesk`. This document describes the layering
and the invariants that keep it correct. For the work queue, see
[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).

## Layers

- `data/table/*Table.kt` – Exposed `IntIdTable` definitions.
- `data/entity/*.kt` – Exposed `IntEntity` DAO classes.
- `repository/` – one file per aggregate: an interface plus a `*RepositoryImpl`
  that wraps every call in `transaction { }`. View models and services receive
  repositories as constructor parameters, defaulting to the `*Impl()`.
- `model/` – immutable `*Item` snapshots produced inside a transaction by
  `Mappers.kt`.
- `viewmodel/` – plain classes extending `BaseViewModel`. They expose
  `StateFlow<XxxState>`, run work on an IO scope through `launchSafely`, and
  every action returns a `Job` so tests can `join()` it. Screens obtain them via
  `rememberViewModel { }`, which closes them on leaving.
- `ui/` – Compose theme, navigation, reusable components and screens.

### The one rule that bites

**Never let a DAO entity leave a transaction for display or reporting.** Reading
a reference (e.g. `txn.category`) afterwards throws *"Can't init value outside
the transaction"*. Always use the `de.findesk.model` `*Item` snapshots from
`findItems`, `findAllItems` and `findItem`, which are mapped inside the
transaction.

## Database

- Schema changes go through `DatabaseFactory.updateSchema`, which creates
  missing tables, columns and indices for `DatabaseFactory.tables`; it never
  alters existing columns. New columns must be nullable or have a default so
  existing databases migrate. Remember to add new tables to that array and to
  create the matching `data/entity` class and `repository`.
- Do **not** use Exposed's `createMissingTablesAndColumns`: with sqlite-jdbc
  3.50.2+ it emits `ALTER TABLE … MODIFY`, which SQLite rejects.
- SQLite foreign keys are not enforced — deletes must cascade by hand
  (`deleteAccountCascade`, `deleteWithAccounts`).
- Every user-visible mutation is recorded via
  `AuditLogRepository.log(action, actor, details)`.
- Money totals never mix currencies and are never converted. Use
  `TransactionFilter.currency` or `Currencies.totals`; overviews use the primary
  currency (`Currencies.primary`).

## Data directory and encryption

- `util/AppPaths.rootDir` is `FINDESK_HOME` or `~/.findesk`.
  `AppPaths.dataDir` is the active profile's directory, holding `findesk.db`,
  `documents/` and `passports/`. The default profile is the root itself; other
  profiles live in `profiles/<slug>/` (`util/ProfileStore`).
  `AppPaths.activeProfile` is set once in `main()` and never changes at runtime:
  switching applies at the next start. A test that sets it must restore it in a
  `finally` block.
- The database is encrypted with SQLCipher v4 via
  `io.github.willena:sqlite-jdbc` (which replaces xerial's driver in the same
  `org.sqlite` package — never add `org.xerial:sqlite-jdbc`). The app opens it
  only through the startup gate (`UnlockViewModel` / `UnlockScreen`, before the
  main window). Every key operation (create, encrypt plaintext, change
  passphrase) happens there, before any connection is pooled.
  `DatabaseFactory.init(dir, passphrase)` with a null passphrase is plaintext
  and only for tests.
- The key goes in connection properties only (`DatabaseCrypto`), never in a JDBC
  URL or in SQL run through Exposed.
- SQL logging is off unless `FINDESK_SQL_LOG=1`, because it prints sensitive
  values. Raw connections (e.g. `VACUUM INTO`) come from
  `DatabaseFactory.rawConnection`. `documents/` is not encrypted.

## Banking (FinTS via HBCI4Java)

- `FinTSGateway` is the port. `Hbci4JavaGateway` is the only class that touches
  HBCI4Java; tests use fakes. `FinDeskHbciCallback` maps HBCI4Java callback
  reason codes to `BankingInteraction`, which the UI implements in
  `UiBankingInteraction` (blocking prompts rendered as dialogs by
  `BankingPromptHost`).
- One sync is one FinTS dialog (`fetchAll`), so the user is asked for the PIN
  once. PINs and TANs are never stored or logged.
- **Never run code against a real bank** in development or tests. The gateway is
  verified only against fakes and a local closed port
  (`Hbci4JavaGatewaySmokeTest`), plus an opt-in live test gated behind
  `FINDESK_FINTS_ONLINE_URL` for a public test endpoint.
- Transfers, scheduled transfers and standing orders move real money. They all
  go through `BankingService.sendOrder`, which requires `confirmed = true` from
  an explicit review step, a valid order and schedule, and an EUR account, and
  writes the audit log. New order types must use it rather than copying the
  checks. An unconfirmed Verification-of-Payee result aborts.
- Bank capability detection (`BankCapabilities`) records which jobs a login
  offers (from the bank's BPD/UPD) on the bank access, so unsupported operations
  are hidden and refused.
- Import de-duplication uses `FinTSClient.importHash`, which includes the bank
  booking id or the running balance. Identical same-day bookings are legitimate.
- Protocol traces can be captured per dialog (opt-in) and exported, redacted,
  for support. See [SECURITY.md](../SECURITY.md) and the README.

## OCR and documents

- PDFs with a text layer are read with PDFBox. Images and scanned PDFs go
  through Tesseract via tess4j, which needs the native library plus `deu` and
  `eng` traineddata (`brew install tesseract tesseract-lang`).
  `TesseractOcrEngine.createOrNull()` returns null when they are missing, and
  tests that need it are skipped via `assumeTrue`.

## Testing

- `DatabaseFactory.init()` with no argument uses the real data directory. In
  tests, always use `TestDatabase.init()` (fresh temp DB) and `TestData` for
  fixtures. The Gradle `test` task also points `user.home` at
  `build/test-home` as a backstop.
- Run `./gradlew ktlintCheck test` before reporting a change as done. Passing
  tests do not prove the Compose UI works; render screenshots
  (`*ScreenshotTest`) or run the app.
