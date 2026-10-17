# FinDesk – Your Financial Workspace

A free, local-first personal finance desktop application that unifies
multi-banking (FinTS/HBCI), transactions, documents and tax-relevant
information in one place. Similar in spirit to
[Hibiscus](https://github.com/willuhn/hibiscus), [GBanking](https://github.com/GBanking)
and [Pecunia](https://pecunia.zum-quellcode.de/), it keeps all of your data on
your own machine — no account, no cloud, no telemetry.

> **Status:** early development. The FinTS integration is currently verified
> against simulated banks and the public Subsembly/FinAPI test endpoints only.
> Try your first real-bank sync carefully and with a single account.

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

## Build installers

Packages are built for the operating system you run the build on.

```bash
./gradlew packageDmg    # macOS  -> build/compose/binaries/main/dmg/FinDesk-1.0.0.dmg
./gradlew packageMsi    # Windows (MSI)
./gradlew packageExe    # Windows (portable EXE)
./gradlew packageDeb    # Debian/Ubuntu
./gradlew packageRpm    # Fedora/RHEL
```

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

See [SECURITY.md](SECURITY.md) for the threat model and how to report issues.

## Online banking (FinTS)

Add your bank by BLZ; name, BIC and the FinTS server are filled in from the
bundled bank directory. Enter your online-banking user ID under the bank's
connection settings and press **Sync**. FinDesk asks for your PIN on every sync
and never stores it. Transfers are only sent after a review step and explicit
confirmation.

### Troubleshooting with a protocol trace

If a sync fails and you want help, FinDesk can record the FinTS/HBCI dialog so
it can be attached to a bug report:

1. Open **Settings → Online banking** and enable **Collect FinTS protocol trace**.
2. Reproduce the problem (e.g. press **Sync**).
3. In **Settings**, press **Save protocol trace** and attach the resulting
   `.txt` file to your issue.

Traces are captured in memory only, redacted (PINs, TANs, the connection
passphrase and account numbers are masked as `X`), and are **never** uploaded
automatically — you always save and share the file yourself.

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

## Acknowledgments

- [HBCI4Java](https://github.com/hbci4j/hbci4java) – FinTS/HBCI library (LGPL-2.1)
- [JetBrains Compose](https://www.jetbrains.com/lp/compose-multiplatform/) – UI toolkit (Apache-2.0)
- [Exposed](https://github.com/JetBrains/Exposed) – ORM (Apache-2.0)
- [Apache PDFBox](https://pdfbox.apache.org/) – PDF parsing (Apache-2.0)
- [Tesseract](https://github.com/tesseract-ocr/tesseract) / [tess4j](http://tess4j.sourceforge.net/) – OCR (Apache-2.0)
- [SQLCipher](https://www.zetetic.net/sqlcipher/) – database encryption (BSD-style)
