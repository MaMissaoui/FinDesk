# FinDesk Implementation Plan

## Overview
A phased approach to building FinDesk - a Kotlin JVM + Compose Desktop financial workspace application with HBCI4Java banking integration.

---

## Work Queue (audit 2026-10-03)

The earlier "all phases complete" status was wrong. This is what the code actually does, and what's being worked on, in order.

**Cross-cutting bugs**
- [x] Repositories return Exposed DAO entities after the transaction closes. Reading a reference (`txn.category`) then throws, and `ReportService.generateTaxReport` silently returns an empty report for any categorized transaction. Fix: immutable UI models (`de.findesk.model`) built inside `transaction {}`.
- [x] `DashboardViewModel`: income and expenses both use `getMonthlyTotal` (net), so they always show the same value.
- [x] `TransactionsViewModel`: the category filter is stored but never applied. `searchByText` only matches `partnerName`, not `purpose`.
- [x] Tests wrote to the real `~/.findesk/findesk.db` (fixed: `TestDatabase`).
- [x] Sidebar navigation didn't recompose (`NavigationState.currentScreen` was a plain var).
- [x] Rule matching: null fields became the literal text "null", and one invalid regex threw for every transaction.
- [x] Deleting an account left orphaned transactions, because SQLite FKs aren't enforced (now cascades).

**Phase A: Wiring (foundation)**
- [x] ViewModels get a `CoroutineScope` and run repository and service calls on `Dispatchers.IO`.
- [x] Every screen observes its ViewModel instead of `sample*` lists (Dashboard, Accounts, Transactions, Documents, Reports).

**Phase B: Pure-JVM features** (plus manual transaction entry, receipt-to-transaction match suggestions, `FINDESK_HOME` data-dir override)
- [x] Documents: upload (file chooser), link/unlink to transaction, search by file name and OCR text, delete, open externally.
- [x] Transactions: text, category and date filters, CSV export, category assignment.
- [x] Accounts: add bank-access and account dialog, delete, manual balance edit.
- [x] Reports: tax and monthly reports from real data, CSV and PDF export (PDFBox).
- [x] PDF text extraction (PDFBox) for text-based PDFs.
- [x] Dashboard charts (Compose Canvas): expenses by category, monthly income vs expenses.
- [x] Settings screen: data directory info, categories and rules management, theme.

**Phase C: OCR**
- [x] Tesseract via tess4j (deu+eng), OCR text stored in `ocr_texts` and linked to the document.

**Phase D: Banking (unverified against real banks)**
- [x] HBCI4Java 4.1.17 PIN/TAN gateway: accounts, balances and statements (MT940, with CAMT fallback) in a single dialog, imported with hash dedup and rules.
- [x] TAN handling: procedure/medium selection (remembered), TAN entry, photoTAN/QR images, decoupled (app) confirmation.
- [x] SEPA transfer with review, explicit confirmation and Verification-of-Payee confirmation (abort unless confirmed).
- [x] Payments screen, Sync per bank, connection settings, offline BLZ lookup (name, BIC, FinTS URL).
- [x] **Live protocol check.** `Hbci4JavaGatewayLiveTest` is opt-in (skipped unless `FINDESK_FINTS_ONLINE_URL` is set, so never in CI) and drives the real HBCI4Java stack against a live *test* FinTS server. Verified against the Subsembly FinTS Dummy (`https://fints.subsembly.net/fints`, BLZ 99000354, PIN 123456): TLS and the dialog handshake reach the server, which answers at the FinTS level (`Nachricht ist nicht verschlüsselt`) and is classified as a bank answer, not a transport failure. No free public test account allows a full login: the Subsembly *Benutzerkennung* is issued on request, and the B+S Banksysteme Demobank FinTS3 (BLZ 70000997, `123456`/`88333`) is reachable only through finAPI. Real endpoints (Deutsche Bank, GAD) reject unregistered client products.
- [ ] **First real-bank sync.** Still unverified end-to-end with a real login: run a sync with one small real account and watch the result before relying on it.
- [x] chipTAN optical: flicker animation via HBCI4Java's FlickerRenderer, with speed and size controls.
- [x] Standing orders (list, create, delete) and scheduled transfers via DauerSEPA*/TermUebSEPA; same confirm/review/EUR/audit guards as transfers. Unverified against a real bank.

**Known gaps / next**
- [x] Statement file import: CSV (Sparkasse, ING, DKB, generic; encoding/delimiter/header detection, adjustable mapping, pending rows skipped) and CAMT.053 XML/ZIP, with re-import dedup.
- [x] SQLCipher encryption for the database, with a passphrase at every start (decided 2026-10-05).
    - [x] Driver: `io.github.willena:sqlite-jdbc` (SQLite3MultipleCiphers, same `org.sqlite` package) replaces xerial; SQLCipher v4 scheme (AES-256, PBKDF2-SHA512 256k). Key passed via connection properties only, never in SQL through Exposed or in the URL.
    - [x] Connection pool (HikariCP over our own DataSource): a keyed open costs ~100 ms, so connections are reused. SQL logger only with `FINDESK_SQL_LOG=1` (it printed every statement, incl. the passport passphrase setting).
    - [x] Startup gate before the main window, per profile: encrypted DB -> unlock (wrong passphrase rejected, change passphrase there too); plaintext or new DB -> set a passphrase (twice, min. length, "no recovery" acknowledgement), then encrypt via copy -> rekey -> verify row counts -> atomic swap. No way to skip. Pending restores are applied first, so a restored plaintext backup is re-encrypted.
    - [x] Backups use the keyed connection (`VACUUM INTO` keeps the encryption); a backup made under another passphrase is accepted and asks for that passphrase at the next start.
    - [x] Schema update without Exposed's createMissingTablesAndColumns (with driver 3.50.2+ it emitted `ALTER TABLE … MODIFY`).
    - [x] Leftover plaintext copies: the gate only converts the database it opens, so `PlaintextLeftovers` scans the places FinDesk writes (`pre-restore-*`, `profiles-deleted/`, `restore-pending/`, unopened profiles) and the unlock screen lists what is still readable without the passphrase.
    - Not covered: `documents/` stays unencrypted (the unlock screen points to FileVault/BitLocker); passport files are protected indirectly (their passphrase lives in the DB). The warning names the leftover copies (a `pre-restore` folder, a deleted profile, the 2026-10-04 reset backup) but doesn't erase them — that stays a manual step, and a backup ZIP copied outside the data directory can't be found at all.
- [x] Budget tracking per category: dashboard card with spent/limit, over-budget and ahead-of-pace warnings.

**Audit 2026-10-17** (fixes across views, banking and data integrity)

- [x] Verification-of-Payee bug: the gateway never wrote `HAVE_VOP_RESULT=true` into the returned buffer, so a confirmed payee was re-treated as unconfirmed. The flag is now set after `confirmPayee`, with a regression test.
- [x] Currency mixing: adding a manual transaction on a foreign-currency account always stored EUR; `getMonthlyTotal`/`getMonthlyByCategory` summed across currencies. Both are now scoped to the account's currency.
- [x] Data integrity: indices on the frequently filtered columns (transactions, documents, audit log, rules, accounts, bank access); `CategoryRepository.delete` unlinks transactions and deletes their rules; `BankAccessRepository.delete` cascades to accounts, transactions and documents (shared `deleteBankAccessCascade`); deleting a transaction unlinks its documents.
- [x] Banking hygiene: one `BankingService.sendOrder` guard path for all order types (explicit confirmation, validation, EUR account, audit log incl. rejected attempts); `deleteStandingOrder` routes through it; `FinTSClient.deleteStoredCredentials` + `BankingService.removeBankAccess` delete the passport and settings when a bank login is removed; audit entries for `updateConnection` and `deleteRule`.
- [x] Localisation: EN/DE/FR strings for all user-visible status, error and progress text in view models, banking, documents, reports, import and UI components, via a non-Compose `de.findesk.i18n.AppStrings` accessor kept in sync by `Main.kt`. Audit-log details stay English (records, not UI). Also fixed the dropped `%s` in the `EditCategory`/`EditRule`/`OpenPreview` accessibility labels.
- [x] Payment status tracking: `payment_orders` table records every order sent to a bank (action, account, recipient, amount, purpose/date/schedule, bank reference, status) before sending, and stores the bank's answer (accepted/rejected/TAN required/cancelled); shown in the Payments screen.
- [x] Bank capability detection: after a successful dialog the login's supported business transactions (BPD/UPD job names) are detected (`BankCapabilities`), stored on the bank access (`bank_access.capabilities`, null = unknown) and used to refuse scheduled transfers and standing orders the login doesn't offer, before anything is sent. Immediate transfers stay the bank's decision.
- [ ] Bank-specific overrides: hbci4j rewriter/`feature.*` options and Hibiscus-style per-BLZ workarounds; research done (rewriter modules, `Feature` flags, per-BLZ handling), no code yet.
- [x] Protocol trace for support/troubleshooting: opt-in capture of the FinTS/HBCI dialog (`ProtocolTraceStore`, hbci4java log level raised to 5), redacted before rendering (user id, customer id, PIN, TAN, passport passphrase, account numbers and IBAN-like tokens masked as `X`), held in memory only and exported from Settings → *FinTS protocol trace* / *Save protocol trace*. Modelled on Hibiscus' "Tracen der HBCI-Kommunikation" and its redacted-log support workflow.
- [x] Release packaging in CI: `ci.yml` runs `ktlintCheck` and the tests on pull requests and `main`; `package.yml` builds the installers on the matching runner (DMG; DEB/RPM; MSI/EXE) for pull requests and attaches them to a published release. Fixing this surfaced two long-standing bugs: the per-OS `targetFormats(...)` assignments shared one property, so only the Linux tasks were registered on every host (formats now declared at the `nativeDistributions` level), and the "bank unreachable" classification only matched hbci4java's *German* send-failure text, so it failed on an English-locale JVM (`BankingFailures` now checks the cause chain for an `IOException` and matches both languages).
- [x] Installer icons (audit 2026-10-17): every OS was given `icon-512.png`, but jpackage only accepts `.icns` on macOS and `.ico` on Windows. With a PNG it warns ("... is not an ICNS/ICO file and will not be used") and falls back to the JDK's `JavaApp.icns` (the Duke mascot) — so the macOS DMG shipped Duke as both the app icon and the volume icon, and the MSI/EXE would have too. macOS now uses `src/main/resources/icons/icon.icns` and Windows `icons/icon.ico` (both generated from `FinDesk-icon.svg`); Linux keeps the PNG, which is the format it expects.
- [ ] macOS DMG **volume** icon still shows the JDK default (Duke): the Compose plugin only passes `--icon` when it builds the app image, not when it builds the DMG from one, so `MacDmgBundler` falls back to `JavaApp.icns`. Fix (post-process the DMG, upstream fix, or accept) to be decided — see [#3](https://github.com/MaMissaoui/FinDesk/issues/3).
- [x] Bank setup by name/BIC/IBAN (2026-10-17): the add-bank dialog only accepted a BLZ. `BankDirectory` gained `search` (BLZ/BIC prefix, bank-name and city substring, ranked, FinTS banks first), `byBic`, `blzFromIban` and `byIban` (German IBAN → BLZ → bank). The dialog now opens with a "Search bank: name, BLZ, BIC or IBAN" field whose live suggestions show the branch (city, BLZ, BIC) and whether a FinTS server is known; picking one fills name/BLZ/BIC, and the resolved FinTS endpoint is proposed before saving. Typing a BLZ still auto-fills, manual entry stays possible for banks outside the directory, and `PaymentsViewModel.bicFor` reuses `byIban`. Modelled on Hibiscus/Jameica (BLZ → server URL prefilled) and GnuCash/AqBanking (pick the bank from a searchable directory by name/BIC before entering credentials).

**Audit 2026-10-17 (code structure, functionality, security, UI)**

- [x] Double payment after an ambiguous failure: every failed order except a cancel was recorded as *rejected*, and the review dialog stayed open with *Confirm and send* enabled, even when the dialog broke after the bank may have executed the order. The gateway now decides an order from its own job status (`Hbci4JavaGateway.orderOutcome`): accepted counts as sent even if the dialog failed afterwards; only a bank answer of 9xxx codes without local exceptions, a rejected PIN, an unknown account or a job the bank doesn't offer is a definite `OrderNotExecutedException`. Anything else becomes `OUTCOME_UNKNOWN` (status *Outcome unknown*): the review closes, the user is told to check online banking, and a new review of the same recipient and amount warns about the unconfirmed order.
- [x] Leaving the Payments screen mid-send lost the result: closing the view model cancelled the coroutine after the blocking bank call returned, so the order stayed *pending* and no audit line was written. `sendOrder` now records, sends, completes and audits under `NonCancellable`; orders still pending from an earlier run (quit or crash mid-send) are marked *Outcome unknown* (`PaymentOrderRepository.markInterrupted`, before `AppInfo.startedAt`).
- [ ] Payments: block navigation (sidebar, Ctrl/Cmd shortcuts) while an order is being sent; leaving now records the answer but cancels open PIN/TAN prompts and hides the result message.
- [ ] Protocol trace disclosure: at log level 5 the trace contains full FinTS message bodies (counterparty names, purposes, amounts, balances), but Settings and the README say only PIN/TAN/user id/account numbers are masked and invite attaching it to issues. Say so plainly; `ProtocolTraceStore.save` should also restrict the file to the owner (`FileSecurity.secureFile`).
- [ ] CSV export formula injection: `ReportService.escape` only quotes. Partner name and purpose are set by whoever sends money; prefix text cells starting with `= + - @` tab/CR with `'` (text fields only).
- [ ] Manual-entry dedup (`TransactionsViewModel.addManualTransaction`): the hash has no account or discriminator and is checked globally, so two identical same-day bookings (or the same booking in two accounts) are rejected.
- [ ] Import speed/atomicity: `RuleRepository.matchTransaction` reloads the rules and compiles every pattern per booking, and each booking is inserted in its own transaction. Load rules once per import and batch the inserts.
- [ ] Documents after a restore elsewhere: `DocumentService.reprocessOcr`/`deleteDocument` use the stored absolute path instead of `AppPaths.resolveDocument`; delete then leaves the plaintext file on disk.
- [ ] (Plausible) Trace redaction matches secrets literally; in raw FinTS syntax `+ : ' ? @` are escaped with `?`, so such a PIN would slip past this layer (HBCI4Java's LogFilter is the first line). Also mask the escaped form.
- [ ] Code hygiene: repository interfaces expose entity-returning methods next to the `*Item` snapshots (22 unused in main, e.g. `TransactionRepository.findByAccount/getMonthlyTotal`, `DocumentRepository.findUnlinked`); `matchTransaction` returns a `Category` entity. Dead code: `TanManager`, `BankingResult.TanRequired` (never produced), `FinTSClient.createTransactionHash/getTanMethods/SyncResult`, `PluginsTable`, the unused `transactions.document_id` link. DB reads on the UI thread in `Main.kt` (theme, language).
- [ ] Security, low: backup's temporary DB copy goes to the system temp dir; restore has a per-entry but no total size cap; `pre-restore-*/` and `profiles-deleted/` keep plaintext documents indefinitely (say so in the UI); `package.yml` grants `contents: write` to pull-request runs.
- [ ] UI: cards use `Card` defaults → `surfaceContainer*` roles, which the theme doesn't set, so M3's purple baseline shows through (light and dark); theme toggle is a heart/star; nav icons mislead (Payments = cart, Documents = hamburger, Reports = info, Accounts = contact); page titles duplicated in top bar and heading; dark-mode sidebar stays saturated blue; Donations and Uncategorized share a purple in the donut; income/expense bars have no axis; bank and account delete are identical trash icons side by side; disabled buttons without a hint (Add rule, Import, Load from bank); ragged stat-card heights; "account(s)"; Settings is one long scroll. Check in a live run: the tall dashboard screenshot showed a stray empty "Recent transactions" card where the budget card should be.
- [ ] Docs drift: CLAUDE.md says "no remote yet" (origin and CI exist); `IMPLEMENTATION_COMPLETE.md`, `PHASE2-4_SUMMARY.md`, `BUILD_SUMMARY.md` look stale.

---

## Phase 1: Project Foundation ✅
**Status:** Completed

### Goals
- Basic project structure and build configuration
- Compose Desktop entry point
- Initial UI scaffold

### Deliverables
- [x] Kotlin JVM + Compose Desktop setup
- [x] Gradle build configuration (build.gradle.kts)
- [x] Main.kt with basic window and composable
- [x] Material3 theme integration

---

## Phase 2: Data Layer & Database Schema
**Status:** ✅ Completed

### Goals
- Define all Exposed ORM entities matching the data model
- Set up SQLite database connection and migrations
- Create repository pattern for data access

### Deliverables
- [x] Database connection manager (SQLite)
- [x] Exposed table definitions:
  - `BankAccessTable` - FinTS/HBCI bank connections
  - `AccountsTable` - Bank accounts (checking, savings, etc.)
  - `TransactionsTable` - Financial transactions
  - `CategoriesTable` - Transaction categories
  - `RulesTable` - Auto-categorization rules
  - `DocumentsTable` - Document/receipt storage
  - `OcrTextsTable` - OCR extracted text
  - `SettingsTable` - Application settings
  - `PluginsTable` - Plugin registry
  - `PaymentOrdersTable` - Locally recorded payment orders and their status
  - `AuditLogTable` - Security audit trail
- [x] Entity classes with relationships
- [x] Repository interfaces and implementations
- [x] Unit tests for repositories

### Technical Notes
- Use Exposed ORM (already in dependencies)
- SQLite for local storage, encrypted with SQLCipher v4 (passphrase at every start)
- Follow repository pattern for clean separation

---

## Phase 3: UI Navigation & Core Layout
**Status:** ✅ Completed

### Goals
- Implement application shell with sidebar navigation
- Create screen routing system
- Establish design system and theme

### Deliverables
- [x] Main application layout:
  - Sidebar with navigation items (Dashboard, Accounts, Transactions, Payments, Documents, Reports/Tax, Settings)
  - Top bar with logo, bank profile, sync status, theme toggle
  - Content area with screen routing
- [x] Navigation system (state-based routing)
- [x] Theme configuration:
  - Primary blue (#2D6CDF)
  - Teal (#3BC7B0)
  - Dark gray (#2F2F2F)
  - Light background (#F5F7FA)
- [x] Reusable UI components:
  - Cards, tables, forms
  - Data display widgets
  - Loading states and error handling
- [x] Screen placeholders for all major sections

---

## Phase 4: Account & Transaction Management
**Status:** ✅ Done (open sub-items below)

### Goals
- Implement core financial data management screens
- Dashboard with key metrics
- Transaction filtering and categorization

### Deliverables
- [x] **Dashboard Screen:** balance/income/expense widgets, recent transactions, expense donut, tax-deduction donut, income vs expenses bars, pending tasks
- [x] **Accounts Screen:** banks with accounts and balances, add/edit balance/delete
  - [x] Account detail view with balance history chart (30/90/365 days, reconstructed from bookings)
  - [x] Upcoming payments (Dashboard, from detected recurring bookings)
- [x] **Transactions Screen:** filters (period, account, category, direction, text), inline category assignment, document indicator, manual entry, CSV export
  - [x] Amount-range filter, detail panel (documents, attach receipt, create rule from booking)
- [x] **Categories & Rules:** category CRUD with tax-relevance and budget, regex rule editor
  - [x] Budget limits evaluated on the dashboard
- [x] ViewModel layer for state management
- [x] Unit tests for business logic

---

## Phase 5: Banking Integration (HBCI4Java)
**Status:** 🟡 Implemented, not yet verified against a real bank (see Work Queue)

### Deliverables
- [x] **FinTS Client Wrapper:** `FinTSGateway` port + `Hbci4JavaGateway`; BPD/UPD cached in the encrypted HBCI4Java passport file
- [x] **Account Synchronization:** accounts matched by IBAN (new ones created), balances, statements; delta sync overlapping the last sync by 10 days
- [x] **Transaction Import:** MT940 with CAMT fallback (parsed by HBCI4Java), hash dedup incl. bank id/running balance, rules
- [x] **Payment System (SEPA):** single transfers with review + confirmation + Verification of Payee; audit-logged
  - [x] Standing orders and scheduled transfers (unverified against a real bank)
  - [x] Payment status tracking (`payment_orders`: bank reference and status, shown in Payments)
- [x] **TAN Management:** procedure/medium selection (remembered), TAN dialog, photoTAN/QR image, decoupled confirmation
  - [x] chipTAN optical flicker code
- [x] Credentials: PIN never stored; passport passphrase random per bank (settings table)

---

## Phase 6: Documents, OCR & Reports
**Status:** ✅ Done

### Deliverables
- [x] **Document Management:** upload (PDF, JPG, PNG, TIFF), local storage, search by name and text, link/unlink, open, delete
- [x] **OCR Pipeline:** PDF text layer via PDFBox; Tesseract (tess4j, deu+eng) for images and scans, with confidence; searchable
- [x] **Document-Transaction Matching:** suggestions from total and date found in the text; manual linking
  - [x] In-app preview (images, first PDF page)
- [x] **Tax Reports:** annual summary, tax-relevant deductions by category, PDF and CSV export
- [x] **Reports & Analytics:** monthly breakdown, category shares, top vendors, category trends (service)
  - [x] Custom date range selection in Reports (incl. PDF/CSV export)

---

## Future Enhancements (Post v1.0)
- [x] SQLCipher encryption for database (passphrase at every start; see Known gaps)
- [ ] Plugin system
- [x] Multi-currency support (no conversion: overviews total the primary currency from Settings, others shown side by side; import skips bookings in a different currency than the account; SEPA only from EUR accounts)
- [x] Import CSV and CAMT.053
  - [x] QIF, OFX, MT940 files (hand-written parsers, same import pipeline and dedup as CSV/CAMT)
- [x] Budget tracking (monthly limits per category)
- [x] Recurring transaction detection (weekly/monthly/quarterly/yearly; Reports "Recurring" tab with yearly cost)
- [x] Multi-profile support (personal/business): each profile is its own data directory (db, documents, passports); default stays at the root; switching applies at next start; delete moves the folder to profiles-deleted
- [x] Backup and restore functionality (ZIP with consistent DB copy, documents, passports; restore applied at next start, previous data kept)
- [x] Dark mode toggle (persisted)
- [x] Keyboard shortcuts (Ctrl/Cmd+1..7, Ctrl/Cmd+,)
- [x] Accessibility improvements (screen-reader roles/labels/headings, chart summaries, live status messages, text for field errors, WCAG contrast test; not yet checked with a real screen reader)

---

## Technical Architecture

### Layer Structure
```
UI Layer (Compose Desktop)
  ↓
ViewModel Layer (State management)
  ↓
Service Layer (Business logic)
  ↓
Repository Layer (Data access)
  ↓
Database Layer (Exposed ORM + SQLite)
```

### Key Dependencies
- Kotlin JVM 2.1.10
- Compose Desktop 1.7.3
- Exposed ORM 0.61.0
- SQLite JDBC 3.53.4.0 (SQLCipher via `io.github.willena:sqlite-jdbc`, same `org.sqlite` package as xerial — do not add both)
- HBCI4Java `com.github.hbci4j:hbci4j-core` 4.1.17
- Tesseract via tess4j, PDFBox

### Localisation
- EN/DE/FR, switchable in Settings. UI strings go through `de.findesk.i18n` (`StringKey`, `Translations`); `TranslationsCompletenessTest` requires every key in all three languages.
- View models and services build user-visible text via `de.findesk.i18n.AppStrings`, kept in sync by `Main.kt`; it defaults to English (what tests see).
- Audit-log details and low-level repository/SQLite exception messages stay English on purpose (they are records and diagnostics, not UI copy).

### Project Structure
```
src/main/kotlin/de/findesk/
├── Main.kt                    # Entry point
├── ui/                        # Compose UI
│   ├── theme/                 # Theme configuration
│   ├── components/            # Reusable components
│   ├── navigation/            # Navigation system
│   └── screens/               # Feature screens
├── viewmodel/                 # ViewModels
├── service/                   # Business logic
├── repository/                # Data access
├── data/                      # Database & entities
│   ├── entity/                # Exposed entities
│   ├── table/                 # Table definitions
│   └── database/              # DB connection
├── banking/                   # HBCI4Java integration
├── document/                  # Document & OCR
└── util/                      # Utilities
```

---

*Last Updated: October 17, 2026*
*Current Phase: Work queue complete except the items left open above (first real-bank test, plugin system, bank-specific handling)*

---

## Build Commands

### Development
```bash
# Run the application
./gradlew run

# Run tests
./gradlew test

# Build (compile + test)
./gradlew build

# Clean build
./gradlew clean build

# Show build info
./gradlew buildInfo
```

### Distribution Packages

#### Automatically (GitHub Actions)
`.github/workflows/package.yml` builds the installers on the matching runner and uploads them as
artifacts on every pull request; on a published release the same files are attached to it.
`ci.yml` runs `ktlintCheck` and the tests on pull requests and on `main`.

#### Current Platform
```bash
# Build every installer format configured for this OS
./gradlew packageDistributionForCurrentOS
```

#### macOS
```bash
# DMG installer
./gradlew packageDmg
# Output: build/compose/binaries/main/dmg/FinDesk-1.0.0.dmg
```

#### Windows
```bash
# MSI installer
./gradlew packageMsi
# Output: build/compose/binaries/main/msi/FinDesk-1.0.0.msi

# Portable EXE
./gradlew packageExe
# Output: build/compose/binaries/main/exe/FinDesk-1.0.0.exe
```

#### Linux
```bash
# Debian/Ubuntu package
./gradlew packageDeb
# Output: build/compose/binaries/main/deb/findesk_1.0.0_amd64.deb

# Fedora/RHEL package
./gradlew packageRpm
# Output: build/compose/binaries/main/rpm/findesk-1.0.0-1.x86_64.rpm
```

### Output Locations
| Platform | Format | Location |
|----------|--------|----------|
| macOS | DMG | `build/compose/binaries/main/dmg/` |
| Windows | MSI | `build/compose/binaries/main/msi/` |
| Windows | EXE | `build/compose/binaries/main/exe/` |
| Linux | DEB | `build/compose/binaries/main/deb/` |
| Linux | RPM | `build/compose/binaries/main/rpm/` |
| All | JAR | `build/libs/FinDesk-1.0.0.jar` |