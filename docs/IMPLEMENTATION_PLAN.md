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
- [x] **First real-bank sync** (2026-10-18): the user's DKB login (three accounts) synced end-to-end in the installed app, after the missing-BIC fix. HVB still fails at the appTAN login (9040/9311) and is being followed up with the user.
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
- [x] Release packaging in CI: `ci.yml` runs `ktlintCheck` and the tests on pull requests and `main`; `package.yml` builds the installers on the matching runner (DMG; DEB/RPM; MSI/EXE) for pull requests that touch packaging and for every push to `main`, and attaches them to a published release. Fixing this surfaced two long-standing bugs: the per-OS `targetFormats(...)` assignments shared one property, so only the Linux tasks were registered on every host (formats now declared at the `nativeDistributions` level), and the "bank unreachable" classification only matched hbci4java's *German* send-failure text, so it failed on an English-locale JVM (`BankingFailures` now checks the cause chain for an `IOException` and matches both languages).
- [x] Installer icons (audit 2026-10-17): every OS was given `icon-512.png`, but jpackage only accepts `.icns` on macOS and `.ico` on Windows. With a PNG it warns ("... is not an ICNS/ICO file and will not be used") and falls back to the JDK's `JavaApp.icns` (the Duke mascot) — so the macOS DMG shipped Duke as both the app icon and the volume icon, and the MSI/EXE would have too. macOS now uses `src/main/resources/icons/icon.icns` and Windows `icons/icon.ico` (both generated from `FinDesk-icon.svg`); Linux keeps the PNG, which is the format it expects. A wrong icon file is invisible in a green build, so `verifyInstallerIcon` now compares the packaged app image against the committed icons in CI (and locally) and fails with both hashes when they differ.
- [x] macOS DMG **volume** icon showed the JDK default (Duke): the Compose plugin only passes `--icon` when it builds the app image, not the DMG, so `MacDmgBundler` fell back to `JavaApp.icns`. `setDmgVolumeIcon` (finalizes `packageDmg`) now patches the finished DMG, and `verifyInstallerIcon` checks the volume icon of any built DMG, which CI runs ([#3](https://github.com/MaMissaoui/FinDesk/issues/3)).
- [x] Context menus (2026-10-18, [#19](https://github.com/MaMissaoui/FinDesk/issues/19)): right-click shortcuts via `RowContextMenu` on transactions (open, attach receipt, show all from the partner, delete), documents (open, show in Finder/Explorer/folder, unlink, delete), banks (sync now, add account, connection settings, remove), accounts (transactions, details, edit balance, delete), recent transfers (**Transfer again** fills the form; sending still needs the review), categories and rules (edit, delete) and the dashboard's recent bookings (show all from the partner). Every action stays reachable through visible buttons. The menu style, also used by text fields' Cut/Copy/Paste, follows the app theme.
- [x] Native menus and window (2026-10-18, [#20](https://github.com/MaMissaoui/FinDesk/issues/20)): a menu bar on every platform (`AppMenuBar`): File (New Transaction, Import Bank Statement), View (the seven screens with ⌘/Ctrl+1–7, Dark Mode ⇧⌘D), Window on macOS (Minimize, Zoom), Help (GitHub, Report a Problem). macOS: screen menu bar, About and Settings… (⌘,) in the system app menu, a unified transparent title bar with the sidebar running under the window buttons, window appearance following the app theme; the JDK's own app-menu items (About, Preferences…, Hide, Quit) stay English. Windows/Linux: Settings…, Exit and About in File/Help, system look-and-feel for the Swing menu bar (not verified on those platforms yet). Minimum window size 1024×700. Menu requests reach a screen through `NavigationArgs.request`.
- [x] One-click local install on macOS (2026-10-18): `install-mac.command` (double-click) runs `./gradlew installMacApp`, which builds the self-contained `FinDesk.app`, checks its signature, refuses while FinDesk runs from the target, moves a previous `FinDesk.app` to the Trash and copies the new one into `/Applications` with `ditto` (`-PinstallDir` for another folder).
- [x] Banks that omit the account BIC (2026-10-18, v1.3.1): the first real sync (DKB) failed for every account with "Fehler beim Hinzufügen des Auftrages KUmsZeitCamt1 zum aktuellen Dialog". HBCI4Java needs the account's BIC for balances, CAMT statements and every SEPA order but reads it only from the UPD, which DKB leaves empty. `Hbci4JavaGateway.bicFor` fills it from the BLZ via `BankDirectory`; a statement job that can't be queued reports HBCI4Java's innermost reason. Verified against the HBCI4Java sources and with tests, not yet against DKB.
- [x] Dark-mode contrast (2026-10-18, v1.3.1): filled buttons (white on #5B8DEF, 3.2:1) and selected filter chips (white on teal, 2.1:1) use dark text; category badges move their text towards the theme's text colour until 4.5:1 (`readableOn`, also for user-picked colours); unselected tabs are grey. `ContrastTest` covers these pairs; `ScreenshotTest` renders every screen in both themes.
- [x] UI unification (2026-10-18): tabular figures in every text style; deep navy sidebar in dark mode; sentence case for 26 English screen strings (menus keep title case; strings used by exports, imports or banking unchanged); one `LedgerRow` for the dashboard's upcoming and recent bookings; budgets and tax-deductible expenses side by side; Reports' quick statistics as stat cards and its period selector in the toolbar row; Transactions and Documents start with one toolbar row (context left, actions right); the SEPA form is a bordered `SectionCard` like its neighbours; Settings' audit-log chip has a short label and the empty log says so. Implemented through OpenCode in three batches, reviewed and rendered in both themes.
- [x] Bank setup by name/BIC/IBAN (2026-10-17): the add-bank dialog only accepted a BLZ. `BankDirectory` gained `search` (BLZ/BIC prefix, bank-name and city substring, ranked, FinTS banks first), `byBic`, `blzFromIban` and `byIban` (German IBAN → BLZ → bank). The dialog now opens with a "Search bank: name, BLZ, BIC or IBAN" field whose live suggestions show the branch (city, BLZ, BIC) and whether a FinTS server is known; picking one fills name/BLZ/BIC, and the resolved FinTS endpoint is proposed before saving. Typing a BLZ still auto-fills, manual entry stays possible for banks outside the directory, and `PaymentsViewModel.bicFor` reuses `byIban`. Modelled on Hibiscus/Jameica (BLZ → server URL prefilled) and GnuCash/AqBanking (pick the bank from a searchable directory by name/BIC before entering credentials).
- [x] Editable bank connection name (2026-10-18, v1.4.0, [#31](https://github.com/MaMissaoui/FinDesk/issues/31)): a connection was named after the bank, so two logins at the same bank (personal and business DKB) showed two identical cards that only the IBANs could tell apart. Rename from the bank card's ⋮ menu or context menu, or edit the name in the connection settings, which opens with the field focused and pre-filled; a blank name is rejected. `BankAccessRepository.updateConnection` trims the name and caps it at 255 chars, `AccountsViewModel.updateConnection` writes a `RENAME_BANK_ACCESS` audit entry when it changes, and the new name shows in Accounts, the account pickers and sync messages. The name is only a label: BLZ, BIC, server URL and the FinTS login stay put, so syncs (PIN prompt shows the new name) and transfers keep working — covered with the fake gateway.
- [x] Custom account name (2026-10-18, v1.5.0, [#41](https://github.com/MaMissaoui/FinDesk/issues/41)): several checking accounts all read "Girokonto", so an account can be given a name of its own ("Haushalt", "Reisekasse"). It is stored in the new nullable `accounts.name` column (existing databases get it through `updateSchema` and read null); rename from the account row's context menu or the detail dialog, which opens a small dialog with the field focused and pre-filled, the type as placeholder and a hint that it is FinDesk-only. A blank name clears it, so the row falls back to the localized account type; names are trimmed and capped at 255 chars, and shown in Accounts, the account pickers and detail rows as "bank · name IBAN". `AccountsViewModel.renameAccount` writes a `RENAME_ACCOUNT` audit entry only when the stored name changed, and a sync never touches the name — covered with the fake gateway.
- [x] Fetch older history from a chosen date (2026-10-18, v1.4.0, [#30](https://github.com/MaMissaoui/FinDesk/issues/30)): a bank connection can be asked to fetch bookings from a chosen date, for example after deleting bookings or to reach past the 90-day initial window. The date is stored in the new `bank_access.sync_from` column and applies to every account of the connection, known or new, as-is (not coerced back to the initial window). A fully successful sync clears it so the normal delta logic resumes; a statement warning, a bank error or a cancel keeps it so the next sync retries. Dedup skips bookings that were already imported. Reached from the bank card's context menu and ⋮ menu ("Fetch history from…"), with a dialog that pre-fills the date, validates it against today, explains the bank's limits, and offers "Reset" when one is stored.
- [x] Running balance column (2026-10-18, v1.5.0, [#35](https://github.com/MaMissaoui/FinDesk/issues/35)): the Transactions list shows the account balance after each booking, reconstructed backwards from `accounts.balance` (`RunningBalance`), for a single non-depot account — a depot's balance is a market value, not the sum of its bookings, and "All accounts" hides the column since currencies can't be combined. Filters hide rows but never change their balances, because the column is computed from every booking of the account. Statements now store the bank's running balance (`transactions.balance_after`, a nullable column added to existing databases) and FinDesk prefers it, marking a row whose bank value disagrees with the reconstructed chain so a missing booking is visible and only that row is flagged; file imports (e.g. DKB, newest-first) are inserted oldest-first so same-day balances chain by booking order. The toolbar shows the current balance and the CSV export appends a Balance column only when the column is shown. Deferred: the "All accounts" combined balance.
- [x] Balance chart below the booking list (2026-10-18, v1.6.0, [#35](https://github.com/MaMissaoui/FinDesk/issues/35)): when one non-depot account is selected, a "Balance history" card under the list charts the account's balance over the shown period. The points come from the running-balance column's own entries (`BalanceHistory.fromRunning`, fed by the same `findItems(TransactionFilter(accountId = …))` list), so chart and column never disagree. One point per day, oldest first: a day carries the value after its newest booking, days before the account's oldest booking show the balance before it, and a booking dated after the period's end never affects that period; the end is clamped to today (`Period.ALL` starts at the account's oldest booking, ends today). Search, direction, category and amount filters never change the chart — it shows the account's balance, like the column — and the card is hidden for "All accounts", a group and a depot. Its header row folds the chart away; the collapsed state persists per profile under `transactions.chartCollapsed` and is read once like the collapsed account groups (view state, no audit entry). With fewer than two points the chart says "Not enough data", as before.
- [x] Account groups and an account panel on Transactions (2026-10-18, v1.5.0, [#34](https://github.com/MaMissaoui/FinDesk/issues/34)): the left side of the Transactions screen is a Banking4-style account list — "All accounts" with a grand total, one collapsible entry per group with per-currency totals, and the accounts inside (name, last four IBAN digits, balance, last-fetched date). Groups default to the account type (fixed order checking, savings, credit_card, depot, then any other type alphabetically) and users can add custom groups, rename, reorder and delete them, and move accounts in or out; an account's custom group overrides its type, and deleting a group returns its accounts to the type group in one transaction. The selection (`all`, a group or one account) and the collapsed groups are persisted per profile in the settings table; a stored selection whose account or (now empty) type group no longer exists falls back to All. Selecting a group filters the bookings by its accounts (`TransactionFilter.accountIds`, an empty set matches nothing), while a single account keeps driving the running-balance column, import default, CSV export and manual entry. The panel filters its rows by name, IBAN and bank, and every group mutation writes an audit entry. Replaces the account dropdown in the filter row.
- [x] Coloured balances and collapsible bank connections (2026-10-18, v1.5.0): one rule colours every balance — `balanceColor` reuses `amountColor` (green above zero, red below, neutral at exactly zero), so the light/dark contrast `ContrastTest` already covers applies. Account rows and the account detail dialog, the account panel's "All accounts" and group totals (one line per currency, each by its own sign) and account balances, the running-balance column and the Transactions header's current balance are coloured; income, expense and budget surfaces keep the colour that carries their meaning. Bank cards on Accounts gained a chevron that folds their account rows away; collapsed, the header shows the account count (new EN/DE/FR plural `AccountCount`) and the connection total via `Currencies.totals`/`Currencies.format`, in `balanceColor` when there is a single currency. Collapsed ids persist per profile under `accounts.collapsedBanks`, read once by `AccountsViewModel` (ids of deleted banks are dropped), and sync, the ⋮ menu and every header action keep working while collapsed.
- [x] Counter of new bookings (2026-10-18, v1.6.0, [#34](https://github.com/MaMissaoui/FinDesk/issues/34)): a bank sync's freshly imported bookings are "new" — a Banking4-style counter per account, per group and for "All accounts" in the Transactions account panel, with the account name and the booking rows shown in bold plus a dot in the list. Stored in the new non-null `transactions.is_new` column; existing databases get it through `updateSchema` (`ALTER TABLE transactions ADD is_new BOOLEAN DEFAULT 0 NOT NULL`, so an existing row reads false, never "new"). Only a bank sync sets it, and never on an account's first import (`hasBookings` is checked before inserting), so connecting a bank doesn't make its 90-day history bold; a chosen "Fetch history from…" on an account that already has bookings flags them as intended, while statement-file imports and manual entries stay read. `TransactionRepository.countNew()` groups once per account (`SELECT account_id, COUNT(*) … WHERE is_new GROUP BY account_id`) and `AccountRepository.findAllItems` fills `AccountItem.newCount` from the same grouped query inside one transaction; a group's count is the sum of its accounts. Opening a single account clears its bookings (the whole account, including a booking dated outside the period on screen), however it was selected — a click, the stored selection restored on open, or `start(accountId)` — while its rows stay bold until the selection changes (`boldIds`, replaced on a selection change, kept across a refresh); "All accounts" and groups never clear automatically, because All is the default view, and instead get a "Mark as read" context-menu item, as does any row with a count (`markRead`). Marking read is view state only, like collapsing a group, so it writes no audit entry.
- [x] Drag an account onto a group (2026-10-18, v1.6.0, [#34](https://github.com/MaMissaoui/FinDesk/issues/34)): an account row can be dragged with the mouse onto a group header. A custom group accepts every account; a type group only takes an account of its own type back from a custom group, and dropping on the account's current group, another type group, "All accounts" or outside a header does nothing. While dragging, a small label of the account name follows the pointer, the source row dims, and a valid header gets a 2 dp primary outline (invalid ones never highlight); a collapsed group works and stays collapsed. Esc or a cancelled gesture drops nothing. The pure `dropTarget`/`groupIdFor` hit test lives in `AccountGrouping`, the move goes through `TransactionsViewModel.moveAccountToGroup` (and its `MOVE_ACCOUNT_TO_GROUP` audit entry), and "Move to group" stays the keyboard path.
- [x] The account panel on the Accounts screen (2026-10-18, v1.6.0, [#34](https://github.com/MaMissaoui/FinDesk/issues/34)): the same `AccountPanel` sits left of the bank cards (at 1000 dp and wider; narrower windows get the `CompactAccountSelector` in the header, now shared from `AccountPanel.kt`). Selecting All, a group or an account filters the bank cards: a card shows only when it holds a visible account and then lists, counts and totals only those (`visibleAccounts`; an empty group shows a hint instead of the cards, and bank connections without accounts appear only under All). The selection and the collapsed groups are the same stored values as on Transactions (`AccountPanelSettings`, keys unchanged), so switching screens keeps them, as in Banking4. Selecting on Accounts never marks bookings read, since no bookings are shown there. The group actions and their audit entries moved from `TransactionsViewModel` into `AccountGroupActions`, which both view models call; behaviour on Transactions is unchanged. Implemented by Claude (OpenCode free models were rate-limited).
- [x] Collapsible sidebar and a language switcher in the top bar (2026-10-18, v1.7.0, Upcount-style): a menu button at the left of the top bar folds the sidebar to a 72 dp icon rail (logo and icons only; each item keeps its title as content description and a hover tooltip, locked items stay disabled), remembered per profile under `ui.sidebarCollapsed`. At the right of the top bar a flag button opens a menu of the languages (flag and native name, current one checked) and switches through the same path as Settings. Flags are drawn (`LanguageFlag`: DE and FR tricolours, the UK flag for English), since Windows has no flag emoji. A language change recreates the current screen, so a new process-wide `Busy` signal, wrapped around sync, sending a payment, statement import, backup and restore staging, and document upload and OCR at the view-model call sites, disables the switcher while such work runs (tooltip "Finish the running task first"), as does a locked screen. Implemented by Claude (OpenCode free models were rate-limited).
- [x] One toolbar pattern and a 40 dp filter bar (2026-10-19, v1.7.0): shared `CompactSearchField` (40 dp, outline/focus states, clear button), `SingleChoiceRow` (Material3 segmented buttons) and `OverflowMenu` (More with disabled reasons) replace the Transactions screen's five outlined actions with a summary line plus More and Add and put every filter in one 40 dp FlowRow, including an amount-menu pill (`Any amount`, `≥`/`≤`/range) with the old parsing. Documents' All/Unlinked/Linked tabs become a segmented filter row, Reports' period chips and two exports become a segmented choice plus one Export menu, and Payments' execution choice and the account detail's day range use segmented choices. No action, disabled condition or file dialog changed; `ToolbarComponentsTest` covers the components and the screenshot renders (including the open More menu) were checked in both themes.
- [x] Empty states that say what to do next (2026-10-19): a first-run Dashboard is one left-aligned getting-started card (three numbered steps: add bank, sync, categorize) instead of empty stat cards and charts; income and expenses use money-in/out arrows rather than expand chevrons; zero pending tasks show a green check and "Nothing to do"; an empty six-month chart says "No bookings in the last six months"; Payments without accounts shows only a centred empty state with "Add bank"; Documents and Transactions empty states offer the next action, while a search or filter with no hits keeps just its hint. Covered by `EmptyStatesTest` and the first-run screenshot.
- [x] Live profile switch from the top bar (2026-10-19, v1.7.0, Upcount-style): a profile button left of the language flag lists the profiles (current one checked) plus "Manage profiles…" (opens Settings); Settings' per-profile button now opens a profile right away too ("Open"). Each profile is its own encrypted database, so a switch goes through the unlock gate in a fixed order: the switch is refused while `Busy` work runs or a screen is locked; `PROFILE_SWITCH` is written to the profile being left; the main window is swapped for the target's unlock window in one snapshot (so the app always has a window and every view model closes first); `ProfileSwitch.closeAndActivate` then closes the database (`DatabaseFactory.close`: pool shut, key and file dropped, Exposed default cleared, so a leftover transaction fails instead of reaching the next profile), forgets the last bank protocol trace, sets `AppPaths.activeProfile` and stores the profile for the next start. The unlock window waits for that before taking a passphrase and offers "Back to <previous>" (which locks again, so that profile needs its passphrase). Language, theme and sidebar reload from the new profile. The profile name moved from the sidebar to the top bar. Implemented by Claude (OpenCode free models were rate-limited).
- [x] installMacApp refuses work-in-progress checkouts (2026-10-18, v1.5.0): `install-mac.command` / `./gradlew installMacApp` built whatever the checkout held, so an install from a feature branch with uncommitted, unreviewed changes became the user's real app. The new `checkInstallSource` task (which `installMacApp` depends on and `createDistributable`/`verifyInstallerIcon` wait for, so a failing check builds and installs nothing) fails on any `git status --porcelain` entry or a HEAD that is not exactly at a `v*` tag, warns and continues on a source zip or a machine without git, prints what it installs ("Installing FinDesk <version> from <tag or branch@sha>"), and lets `-Pforce` skip both checks with a warning naming the source it installs.
- [x] Readable FinTS transport errors (2026-10-18, v1.5.0): a real HypoVereinsbank sync (BLZ 79020076) showed "Fehler beim Empfangen der Daten vom HBCI-Server" followed by Java stack frames, because `Hbci4JavaGateway.unwrap` used an error-level diagnostics line that contained the trace. The message building now lives in the pure `BankingFailures.describe`, which cuts text at the first line break, drops `at`/`Caused by` lines and caps at 300 chars: an answer that isn't FinTS (a base64 failure, the HVB case) becomes `ErrorServerNotFints` naming the root cause, an HTTP status becomes `ErrorServerHttpStatus` (checked before the network branch, so HBCI4Java's lost IOException no longer reports the bank as unreachable), a bank message wins over the generic text, and a network failure keeps `ErrorBankUnreachable` with the root cause appended. EN/DE/FR strings; covered by `BankingFailuresTest` with the exact HVB trace.
- [x] Central FinTS bank code and connection edits that apply (2026-10-18, v1.5.0, [#46](https://github.com/MaMissaoui/FinDesk/issues/46)): HBCI4Java reads the login (BLZ, host, user id) from the passport file once, so since v1.4.0 editing the user id or server URL of a synced bank had no effect — the passport's stored values won. Editing the BLZ, user id or server URL now resets the login: the passport file and its passphrase are deleted (`FinTSClient.resetLogin`, under the gateway's lock), the stale capabilities and TAN method are cleared, and the saved PIN is forgotten when the login changed, so the next sync registers again with the new login. UniCredit Bank / HypoVereinsbank only accepts its central BLZ (70020270) for FinTS, so `BankDirectory.finTsBlz` maps the branch BLZ to it: the add-bank dialog notes the substitution, the connection dialog gets an editable BLZ field with an inline reset note, and the bank card offers "Use %s" instead of migrating silently. Stale passports from earlier edits heal themselves: before every dialog the gateway reads the passport's stored BLZ, user id and host offline and deletes the passport on any mismatch, so a connection edited in an older version (or saved again without detecting a change) picks up the new login at the next sync. A delete that fails, including a silent `File.delete()`, is reported as an error instead of being ignored.

**Audit 2026-10-17 (code structure, functionality, security, UI)**

- [x] Double payment after an ambiguous failure: every failed order except a cancel was recorded as *rejected*, and the review dialog stayed open with *Confirm and send* enabled, even when the dialog broke after the bank may have executed the order. The gateway now decides an order from its own job status (`Hbci4JavaGateway.orderOutcome`): accepted counts as sent even if the dialog failed afterwards; only a bank answer of 9xxx codes without local exceptions, a rejected PIN, an unknown account or a job the bank doesn't offer is a definite `OrderNotExecutedException`. Anything else becomes `OUTCOME_UNKNOWN` (status *Outcome unknown*): the review closes, the user is told to check online banking, and a new review of the same recipient and amount warns about the unconfirmed order.
- [x] Leaving the Payments screen mid-send lost the result: closing the view model cancelled the coroutine after the blocking bank call returned, so the order stayed *pending* and no audit line was written. `sendOrder` now records, sends, completes and audits under `NonCancellable`; orders still pending from an earlier run (quit or crash mid-send) are marked *Outcome unknown* (`PaymentOrderRepository.markInterrupted`, before `AppInfo.startedAt`).
- [x] Payments: navigation is locked while an order is being sent (`NavigationState.lock`, held by the Payments screen while `isSending`); the sidebar, Ctrl/Cmd shortcuts and dashboard links refuse other screens, and the sidebar shows them disabled.
- [x] Protocol trace disclosure: at log level 5 the trace contains the bank's full answers (counterparty names, purposes, amounts, balances), but Settings and the README said only PIN/TAN/user id/account numbers were masked and invited attaching it to issues. Settings (EN/DE/FR), the trace header, README and SECURITY.md now say so and ask the user to read it before sharing; `ProtocolTraceStore.save` restricts the file to the owner before writing.
- [x] Manual-entry dedup (`TransactionsViewModel.addManualTransaction`): the hash had no account and was checked globally, so the same booking couldn't be entered on two accounts. The check now compares date, amount, partner and purpose against this account's bookings (any source) and the hash includes the IBAN.
- [x] Two identical same-day manual entries on one account (two coffees): decided 2026-10-17 to ask instead of refusing. The second waits for an "Add anyway" confirmation, gets its own hash, and is audit-logged as a confirmed repeat.
- [x] Page headings were black in dark mode (no `Surface` set the content colour at the app root); found in the duplicate-dialog screenshot.
- [x] Import speed/atomicity: `RuleMatcher` loads and compiles the auto-apply rules once, and `TransactionRepository.createAll` inserts an import in one transaction (all or nothing). 2000 bookings with 20 rules: 1.38 s → 0.36 s. Used by file import, bank sync and auto-categorize.
- [x] Trace redaction also masks each secret's FinTS-escaped form (`+ : ' ? @` escaped with `?`), so a PIN containing them no longer slips past this layer (`BankingRedactor.fintsEscaped`).
- [x] Code hygiene: removed 11 repository methods nothing called, `BankingResult.TanRequired` (never constructed), `TanManager`/`getTanMethods`, `createTransactionHash`, `SyncResult` and `TransferResult.tanRequired`. Kept: `connectionStatus` (tested), `PluginsTable` (plugin system planned), the `transactions.document_id` column (existing databases have it).
- [x] `matchTransaction` (returned a `Category` entity) is now `matchCategoryId`, built on `RuleMatcher`. Theme and language are read on IO once the database is open and handed to the main window, instead of in composition. The other entity-returning methods stay: main code uses only their `id` or plain columns, which are safe outside a transaction; tests and `TestData` rely on them.
- [x] Security, low: the backup's temporary DB copy is made in the data dir, not the system temp dir; restore counts the bytes it unpacks (2 GiB per entry, 8 GiB in all) instead of trusting the ZIP's sizes; the restore and delete-profile dialogs say the moved folders keep unencrypted documents until deleted by hand; `package.yml` is read-only, and only a release-only job may write (attaching installers). The release job is first exercised by the next published release.
- [x] UI, cards: Material 3 draws cards/dialogs on `surfaceContainer*`, which the theme never set, so they used its purple baseline, on which secondary text, income/expense amounts and text buttons were below 4.5:1 (ContrastTest only checked `surface`). Light cards/dialogs are now white with a hairline outline (`FinDeskCard`), dark ones `#232529`; ContrastTest checks the card and dialog roles. Dark-theme sidebar uses `PrimaryBlueDark`.
- [x] UI, icons: heart/star theme toggle, cart/hamburger/info nav icons, contact-card balance and "share" export icons replaced by fitting Material icons (`AppIcons`, path data, no extended icon set); Export PDF icon was 8 dp. Uncategorized is grey in the donut (it hashed to the same purple as a category).
- [x] UI: one title per screen (the top bar); the income/expense chart has a value axis rounded to 1/2/2.5/5×10ⁿ; bank-wide actions (connection settings, remove bank) moved into a ⋮ menu so the only trash icon deletes one account; disabled buttons say why (tooltip and screen-reader state, `DisabledReason`); equal stat-card heights; real singular/plural in EN/DE/FR (`{n:one|other}` in strings, resolved by `Strings.format`); section shortcuts on Settings. The stray dashboard card no longer shows in the screenshot renders.
- [ ] Screenshot harness (low priority, test-only): Dashboard renders in `ScreenshotTest` sometimes mix initial and loaded state (€0.00 cards beside loaded charts, the reverse, or a stale item next to its loaded copy). The app is not affected: a UI test with a real clock captured correct pixels and semantics in 4 of 4 cold runs. Tried without success: a warm-up render, `Snapshot.sendApplyNotifications()` before each frame, keyed `LazyColumn` items (removed again: in the live app they opened the dashboard with the welcome card scrolled out of view). Likely the harness's fake frame clock with `LazyColumn`; until fixed, re-render when a Dashboard shot looks stale.
- [x] Dashboard: stat cards keep equal heights when titles or subtitles wrap in a narrow window (`IntrinsicSize.Min` row; a narrow render was added to `ScreenshotTest`); the income/expense axis shows only the €0 baseline when there is no data (it read "€1 / €1 / €0") and cents for scales under €10; long donut legend names are truncated instead of running into their amount.
- [x] Docs drift: CLAUDE.md describes the GitHub remote, CI and PR flow; `IMPLEMENTATION_COMPLETE.md`, `PHASE2-4_SUMMARY.md` and `BUILD_SUMMARY.md` carry a superseded banner.
- [x] Faster CI on pull requests (2026-10-18): `package.yml` gained a `push: branches: [main]` trigger — every merge still builds and verifies the installers, and because `gradle/actions/setup-gradle` only writes its cache from the default branch, the three runners now save it there so PR packaging runs start warm — while its `pull_request` trigger was narrowed from `paths-ignore` to the packaging-relevant `paths` (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**`, `src/main/resources/**`, `package.yml`, `install-mac.command`); Kotlin source changes are left out on purpose, since `ci.yml` compiles and tests them on the same commit and the `main` push packages them. `workflow_dispatch` and `release` stay, so a PR can still ask for installers. `ci.yml` skips prose-only changes (`paths-ignore: "**/*.md"` — which also covers `CLAUDE.md` — and `docs/**`) and caps the job at `timeout-minutes: 20`. `gradle.properties` enables the build cache (`org.gradle.caching`) and parallel task execution (`org.gradle.parallel`). Parallel test forks (`maxParallelForks`) were tried and dropped: green three times on macOS (28 s → 20 s), but on Linux CI a test JVM crashed natively in Tesseract (SIGSEGV in libstdc++), so tests stay in one JVM.
- [x] Remember the PIN for syncs in the OS keychain (2026-10-18, [#29](https://github.com/MaMissaoui/FinDesk/issues/29)): a sync may keep the online-banking PIN in the operating system's credential store, so recurring syncs stop asking. macOS uses the Keychain through the Security framework via JNA (`SecKeychainFind/Add…`; never `/usr/bin/security`, which would put the PIN on the command line); Linux uses the Secret Service via `secret-tool` with a 5 s timeout per call — **the Linux store is not verified on Linux yet** — and Windows uses the Credential Manager through `Advapi32.dll` via JNA ([#38](https://github.com/MaMissaoui/FinDesk/issues/38)). Verified on macOS: a write/read/overwrite/delete round trip through the real Keychain with the app's jars on JDK 21 limited to the bundle's module set (`--limit-modules`, since jpackage strips `bin/java`); not yet run from the signed `FinDesk.app` launcher, and with ad-hoc signing macOS may ask for Keychain access after an app update (denying it just asks for the PIN). The PIN is only ever offered for syncs: transfers, scheduled transfers and standing orders always ask again and never offer to remember. `CredentialStores.system()` resolves the store lazily (the test task sets `findesk.credentialStore=none` for `NoCredentialStore`), no PIN reaches a log or exception message (`PinEntry.toString` prints only whether it may be remembered; keychain failures report only the OS status). The entry is keyed by the profile's data directory and a settings flag `fints.pinSaved.<id>` holds `"<blz>|<userId>"`: a changed login forgets it, a missing entry clears the flag, a read failure just asks again, and a store failure never fails the sync (a short, non-secret note says the PIN couldn't be saved). The connection settings show whether a PIN is remembered and offer to forget it; deleting a bank or a profile removes that profile's entries (both use `FinTSClient.pinKey`). A PIN the bank rejects is deleted before the user is asked again, so a cancelled re-prompt can't keep it; any failed dialog that used it deletes it too. Implemented by OpenCode from a written spec; reviewed by Claude (native-load fallback, Keychain delete status, audit only for a saved PIN, failed Linux reads, checkbox below the field).

- [x] Remember the PIN on Windows (2026-10-18, v1.5.0, [#38](https://github.com/MaMissaoui/FinDesk/issues/38)): the Windows Credential Manager is used through `Advapi32.dll` via JNA (`CredWriteW`/`CredReadW`/`CredDeleteW`/`CredFree`, wide-char entry points only; `CREDENTIALW` is mapped at its 64-bit wincred layout of 80 bytes, with `FILETIME` inlined as two DWORDs). Entries are `CRED_TYPE_GENERIC` under `FinDesk:<key>` and persisted with `CRED_PERSIST_LOCAL_MACHINE`, never `CRED_PERSIST_ENTERPRISE`, so the PIN does not roam to another machine; `CredWriteW` overwrites in place, so there is no find-first, and a failed read reports the OS error instead of pretending there is no PIN (`ERROR_NOT_FOUND` is the only "no PIN"). Last errors are read from JNA's per-call `Native.getLastError()` on the same thread immediately after a `false` return. The JVM write/read/overwrite/delete round trip (plus a non-ASCII secret) is verified on the GitHub `windows-latest` runner (`.github/workflows/windows-credentials.yml`), which fails if either credential test is skipped so a no-op run cannot look green; the packaged MSI/EXE on Windows 11 is still unverified.
- [x] Sync all with a per-connection switch (2026-10-19, v1.7.0): a "Sync all" button left of "Add bank" syncs every connection whose new `bank_access.include_in_sync_all` column is true, strictly one FinTS dialog after another in bank-card order and under one `Busy` section. Existing connections default to true (the column arrives through `updateSchema`); the bank card's ⋮ menu toggles inclusion (`SET_SYNC_ALL_INCLUDED` audit entry) and an excluded card says "not in Sync all" in its grey subtitle, while its own Sync button keeps working. A run claims the screen synchronously so a double click cannot start two, refuses single syncs with `ErrorConnectionBusy`, records a failed connection and continues, stops on a cancelled PIN/TAN prompt with `SyncAllStopped`, and clears its progress in a `finally`; coroutine cancellation between connections stops the run too. New EN/DE/FR strings; covered by `SyncAllTest`, a schema-upgrade test and the `accounts-sync-all` screenshot.
- [x] Loan accounts and missing bank balances (2026-10-19, v1.7.0): FinTS account type codes 40–49 now map to the new type `loan` instead of `depot`, with EN/DE/FR labels and the group order checking, savings, credit_card, loan, depot; a sync corrects an existing `depot` only when the bank reports a loan, and the account type (and therefore the loan hint) can be changed by hand in the balance dialog (`CHANGE_ACCOUNT_TYPE` audit). When the bank sends no balance, the snapshot keeps the balance job's return codes (`3016`, never the message text with the full account), and `SyncReport.notes` says so once per affected account (last four IBAN digits only) — as a note, not a warning, so `lastSync` still advances and a balance the user entered is never overwritten. Screenshot `accounts-loans`; the loan hint is covered by a Compose UI test because the screenshot scene cannot open dialogs.

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
- [x] UI phase 1: one blue selection style, a neutral sidebar, and green/red only for money flows (WCAG contrast kept; 2026-10-19, v1.7.0)
- [x] UI phase 5: charts that can be read (2026-10-19): the balance chart gained a round y axis (`niceTicks`, 1/2/2.5/5 × 10ⁿ steps) with gridlines, an area fill under the line, a marked current balance and first/middle/last date labels, replacing the floating max/min texts. The income/expense bars keep their axis but round only their top corners. On pointer hover, the line chart shows a vertical guide, a point dot and a date/amount label, and the bars shade the hovered month and show its month/year with signed income and expenses. Hover is an extra only: every value remains in the charts' accessibility descriptions, so nothing is reachable by hover alone. Covered by extended `ChartAxisTest`, the new `ChartsTest` and the `transactions-balance-hover` render.
- [x] UI phase 7: small fixes from the UI audit (account-panel filter on top, account-row ⋮ menu, transfer review amount header and warning callout, Reports navigation label, unlock alignment and profile chip, Settings disabled reason and profile badge)
- [x] UI phase 4: Settings as two panes (2026-10-19): the eight section chips and the 2400 dp page are gone. A 220 dp section list on the left (tab semantics, selection bar, section headers), a divider, and only the selected section on the right, flush under a divider instead of in cards. Help texts are `bodyMedium` at most 640 dp wide; below 840 dp the list becomes a dropdown. `NavigationArgs.settingsSection` opens a section directly (profile menu → Profiles, dashboard step 3 → Categories); narrow and per-section screenshots cover the new layout.

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

*Last Updated: October 18, 2026*
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
artifacts on pull requests that touch packaging-relevant paths (and on every push to `main`, which
also writes the runners' Gradle caches); on a published release the same files are attached to it.
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
# Output: build/compose/binaries/main/dmg/FinDesk-1.7.0.dmg
```

#### Windows
```bash
# MSI installer
./gradlew packageMsi
# Output: build/compose/binaries/main/msi/FinDesk-1.7.0.msi

# Portable EXE
./gradlew packageExe
# Output: build/compose/binaries/main/exe/FinDesk-1.7.0.exe
```

#### Linux
```bash
# Debian/Ubuntu package
./gradlew packageDeb
# Output: build/compose/binaries/main/deb/findesk_1.7.0_amd64.deb

# Fedora/RHEL package
./gradlew packageRpm
# Output: build/compose/binaries/main/rpm/findesk-1.7.0-1.x86_64.rpm
```

### Output Locations
| Platform | Format | Location |
|----------|--------|----------|
| macOS | DMG | `build/compose/binaries/main/dmg/` |
| Windows | MSI | `build/compose/binaries/main/msi/` |
| Windows | EXE | `build/compose/binaries/main/exe/` |
| Linux | DEB | `build/compose/binaries/main/deb/` |
| Linux | RPM | `build/compose/binaries/main/rpm/` |
| All | JAR | `build/libs/FinDesk-1.7.0.jar` |