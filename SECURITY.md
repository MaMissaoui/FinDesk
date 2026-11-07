# Security Policy

## Alpha status

FinDesk is alpha software. Its automated tests use fakes only and never contact a
live bank, and each bank implements FinTS a little differently, so a sync,
transfer, scheduled transfer or standing order can fail, hang or behave
unexpectedly with your bank — and those orders move real money. Check balances,
statements and reports against your online banking before relying on them.

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
  logged; TANs are never stored. A PIN is stored only when you tick "Remember PIN
  for syncs", and then only in the operating system's credential store (macOS
  Keychain, Linux Secret Service via `secret-tool`, Windows Credential Manager),
  never in the database or a file. It is used for syncs only, and deleted when
  the bank rejects it, a dialog fails, the bank is removed or its login changes.
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
- The **protocol trace** feature is opt-in, captured in memory only and never
  uploaded automatically. PIN/TAN/passphrase/user id/account numbers are masked;
  the bank's answers (names, purposes, amounts, balances) are not, so the saved
  file is owner-only and users are told to review it before sharing.
- Money-moving operations (transfers, scheduled transfers, standing orders) go
  through a single, guarded code path that requires explicit confirmation and
  an EUR account, and writes an audit-log entry.
- **Never run this software against a real bank in an automated test.**

## Supported versions

Only the latest release gets fixes. The project moves quickly: fixes land on
`main` and ship in the next release, so please test against the latest release
(or `main`) before reporting.
