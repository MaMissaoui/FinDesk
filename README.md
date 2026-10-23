# FinDesk – Your Financial Workspace

[![CI](https://github.com/MaMissaoui/FinDesk/actions/workflows/ci.yml/badge.svg)](https://github.com/MaMissaoui/FinDesk/actions/workflows/ci.yml)
[![Package](https://github.com/MaMissaoui/FinDesk/actions/workflows/package.yml/badge.svg)](https://github.com/MaMissaoui/FinDesk/actions/workflows/package.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#disclaimer)

A free, local-first personal finance desktop application that unifies
multi-banking (FinTS/HBCI), transactions, documents and tax-relevant
information in one place. Similar in spirit to
[Hibiscus](https://github.com/willuhn/hibiscus), [GBanking](https://github.com/GBanking)
and [Pecunia](https://pecunia.zum-quellcode.de/), it keeps all of your data on
your own machine — no account, no cloud, no telemetry.

> ## Disclaimer
>
> **FinDesk is ALPHA software. It is not released for production use, and it must
> not be used with real bank accounts.**
>
> The FinTS/HBCI integration has never been run against a live bank — only against
> simulated banks and the public Subsembly/FinAPI test endpoints. The database
> schema and file formats still change between commits, so data loss or wrong
> balances cannot be ruled out. Transfers, scheduled transfers and standing orders
> move real money and may fail, hang or behave unexpectedly.
>
> Use sample data or a FinTS test/demo account. Balances and reports produced by
> this application are unverified and must not be relied on for accounting, tax or
> financial decisions.

## Features

- **Multi-banking** – connect multiple accounts via FinTS 3.0 PIN/TAN (HBCI),
  including pushTAN/decoupled, photoTAN, QR-TAN, chipTAN (optical and manual)
  and SMS-TAN.
- **Transactions** – fetch statements from the bank, import CAMT, MT940, OFX,
  QIF and CSV files, and de-duplicate automatically.
- **Categorization** – rule-based auto-categorization with a manual review.
- **Documents** – attach receipts, invoices and statements, with OCR for images
  and scanned PDFs.
- **Reports & tax** – yearly summaries with PDF and CSV export.
- **Local and encrypted** – the database is encrypted with SQLCipher (AES-256);
  nothing leaves your machine.

## Technology stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin JVM (JDK 21) |
| UI | Compose Desktop (Material 3) |
| Database | SQLite via SQLCipher + Exposed ORM |
| Banking | HBCI4Java (FinTS 3.0 PIN/TAN) |
| OCR | Tesseract (tess4j) + Apache PDFBox |
| Build | Gradle (Kotlin DSL) |

## Requirements

- **JDK 21+**
- **Tesseract** (optional, for OCR of images and scanned PDFs):
  - macOS: `brew install tesseract tesseract-lang`
  - Debian/Ubuntu: `apt install tesseract-ocr tesseract-ocr-deu`

  PDFs that already contain a text layer work without Tesseract.

## Quick start

```bash
git clone https://github.com/MaMissaoui/FinDesk.git
cd FinDesk
./gradlew run
```

Run against a throwaway data directory instead of your real one:

```bash
FINDESK_HOME=/tmp/findesk-dev ./gradlew run --no-daemon
```

## Tests

```bash
./gradlew test          # full suite
./gradlew ktlintCheck   # lint
```

## Downloads

Prebuilt installers for macOS (DMG), Windows (MSI/portable EXE) and Linux
(DEB/RPM) are attached to every
[release](https://github.com/MaMissaoui/FinDesk/releases). These are alpha
builds — read the [disclaimer](#disclaimer) first.

## Build installers

Packages are built for the operating system you run the build on.

```bash
./gradlew packageDmg    # macOS  -> build/compose/binaries/main/dmg/FinDesk-1.8.1.dmg
./gradlew packageMsi    # Windows (MSI)
./gradlew packageExe    # Windows (portable EXE)
./gradlew packageDeb    # Debian/Ubuntu
./gradlew packageRpm    # Fedora/RHEL
```

### Install on this Mac from source

Double-click **`install-mac.command`** in Finder (or run `./gradlew installMacApp`).
It installs the release checked out here and refuses uncommitted or unreleased code unless
`-Pforce` is passed. It builds a self-contained `FinDesk.app` (it carries its own Java runtime) and
copies it into `/Applications`. A previously installed `FinDesk.app` is moved to
the Trash rather than deleted, so you can drag it back. Quit FinDesk first; the
installer refuses to replace an app that is running. Building needs a JDK 21.
Your data in `~/.findesk` is not touched.

## Data, privacy and security

- All data lives in a single directory: `~/.findesk`
  (`%USERPROFILE%\.findesk` on Windows). Override it with the `FINDESK_HOME`
  environment variable. Multiple profiles are supported under
  `~/.findesk/profiles/<name>/`.
- The database (`findesk.db`) is encrypted with SQLCipher. The passphrase is
  entered at startup, is never stored on disk in plain text and is never logged.
- PINs and TANs are requested per sync and never persisted or logged. The
  database key is passed to the driver only through connection properties.
- `documents/` (attached files) is **not** encrypted.
- Encryption covers the database FinDesk opens. Copies it left behind earlier
  (data a restore replaced, deleted profiles, profiles not opened yet) are
  detected and listed on the unlock screen, so you can delete them.

See [SECURITY.md](SECURITY.md) for the threat model and how to report issues.

## Online banking (FinTS)

Add your bank by searching for it — by name, BLZ, BIC or IBAN. Suggestions show
the matching branch (city, BLZ, BIC) and whether the bundled bank directory
lists a FinTS server; picking one fills in name, BLZ, BIC and proposes the FinTS
endpoint. Prefer to type the BLZ directly? That still works, and the bank name,
BIC and FinTS server are filled in when it matches. Banks outside the directory
can be added manually. Enter your online-banking user ID under the bank's
connection settings and press **Sync**. FinDesk asks for your PIN on every sync
unless you tick **Remember PIN for syncs**; that PIN is kept in the operating
system's credential store, never in `findesk.db` or a file. On Windows, the
installed MSI is checked in CI by the packaged app's self-check, and you can run
the same check by hand in PowerShell:

```powershell
Start-Process "$env:LOCALAPPDATA\FinDesk\FinDesk.exe" -ArgumentList '--credential-self-check', "$env:TEMP\findesk-check.txt" -Wait; Get-Content "$env:TEMP\findesk-check.txt"
```

It prints `OK:` when the store works. The per-user MSI installs into the
`FinDesk` folder under `%LOCALAPPDATA%`; adjust the path if you chose another
folder. Transfers are only sent after a review step and explicit confirmation.

### Troubleshooting with a protocol trace

If a sync fails and you want help, FinDesk can record the FinTS/HBCI dialog so
it can be attached to a bug report:

1. Open **Settings → Online banking** and enable **Collect FinTS protocol trace**.
2. Reproduce the problem (e.g. press **Sync**).
3. In **Settings**, press **Save protocol trace** and attach the resulting
   `.txt` file to your issue.

Traces are captured in memory only and are **never** uploaded automatically —
you always save and share the file yourself. PINs, TANs, the connection
passphrase, the user id and account numbers are masked as `X`, but the trace
contains the bank's full answers: counterparty names, purposes, amounts and
balances. Read the file before you attach it, and remove what you don't want to
share. The saved file is readable only by your user.

## Project structure

```
src/main/kotlin/de/findesk/
├── Main.kt              # entry point
├── banking/             # FinTS/HBCI integration (HBCI4Java port)
├── data/                # database, tables and DAO entities
├── repository/          # data access layer
├── viewmodel/           # state management
├── document/            # document, OCR and reporting services
├── importer/            # CAMT/MT940/OFX/QIF/CSV parsers
├── model/               # transaction-safe snapshot models
├── i18n/                # EN/DE/FR localization
└── ui/                  # theme, navigation, components, screens
docs/                    # architecture, plan and design documents
```

Localization: the UI ships in English, German and French
(`de.findesk.i18n`).

## Rendering screenshots

Screens can be rendered headlessly (no window) for review or docs:

```bash
./gradlew test --tests '*ScreenshotTest' -Pscreenshots=build/screenshots
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please run
`./gradlew ktlintCheck test` before opening a pull request.

## License

This project is licensed under the MIT License – see [LICENSE](LICENSE).

FinDesk bundles the IBM Plex Sans typeface, © IBM Corp., under the SIL Open Font License 1.1 (see [OFL.txt](src/main/resources/fonts/OFL.txt)).

## Acknowledgments

- [HBCI4Java](https://github.com/hbci4j/hbci4java) – FinTS/HBCI library (LGPL-2.1)
- [JetBrains Compose](https://www.jetbrains.com/lp/compose-multiplatform/) – UI toolkit (Apache-2.0)
- [Exposed](https://github.com/JetBrains/Exposed) – ORM (Apache-2.0)
- [Apache PDFBox](https://pdfbox.apache.org/) – PDF parsing (Apache-2.0)
- [Tesseract](https://github.com/tesseract-ocr/tesseract) / [tess4j](http://tess4j.sourceforge.net/) – OCR (Apache-2.0)
- [SQLCipher](https://www.zetetic.net/sqlcipher/) – database encryption (BSD-style)
