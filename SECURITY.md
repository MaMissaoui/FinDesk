# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Instead, report
them privately through GitHub's
[Report a vulnerability](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
form for this repository.

Include a description, reproduction steps and the affected version or commit.
We aim to acknowledge reports within a few days.

## Scope

FinDesk is a local desktop application that talks to banks over FinTS/HBCI and
stores everything on your machine. The most relevant areas are:

- **Data at rest** – the database is encrypted with SQLCipher (AES-256). The
  passphrase is never written to disk in plain text.
- **Credentials** – banking PINs and TANs are requested per dialog and are never
  stored or logged.
- **Network** – only the FinTS server of a configured bank is contacted, over
  TLS.
- **Attachments** – files under `documents/` are stored unencrypted; do not put
  anything there you are not comfortable keeping on disk.

## Design principles we rely on

- The SQLCipher key is passed only through JDBC **connection properties**
  (`de.findesk.data.database.DatabaseCrypto`), never in a URL or in SQL.
- SQL logging is disabled unless `FINDESK_SQL_LOG=1` is set, because it would
  print sensitive values.
- The HBCI4Java log callback keeps only error-level lines during normal
  operation; higher levels may contain message dumps and are dropped.
- The **protocol trace** feature is opt-in, captured in memory only, redacted
  (PIN/TAN/passphrase/account numbers) and never uploaded automatically.
- Money-moving operations (transfers, scheduled transfers, standing orders) go
  through a single, guarded code path that requires explicit confirmation and
  an EUR account, and writes an audit-log entry.
- **Never run this software against a real bank in an automated test.**

## Supported versions

The project is pre-1.0 and moves quickly. Fixes land on `main`; please test
against the latest commit before reporting.
