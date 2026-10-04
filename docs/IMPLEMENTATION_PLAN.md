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
- [ ] HBCI4Java PIN/TAN client: accounts, balances, statements (KUmsAll), imported with hash dedup.
- [ ] SEPA transfer with explicit confirmation and TAN dialog. Tested with fakes only; never against a live bank.
- [ ] Payments screen.

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
  - `AuditLogTable` - Security audit trail
- [x] Entity classes with relationships
- [x] Repository interfaces and implementations
- [x] Unit tests for repositories

### Technical Notes
- Use Exposed ORM (already in dependencies)
- SQLite for local storage (SQLCipher for encryption later)
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
**Status:** 🟡 Partial (repositories done; UI shows hardcoded sample data)

### Goals
- Implement core financial data management screens
- Dashboard with key metrics
- Transaction filtering and categorization

### Deliverables
- [ ] **Dashboard Screen:**
  - Balance overview widgets
  - Recent transactions list
  - Expense pie chart
  - Tax deduction bar chart
  - Pending tasks/notifications
- [ ] **Accounts Screen:**
  - Account list with balances
  - Account detail view
  - Balance history line chart
  - Upcoming payments section
- [ ] **Transactions Screen:**
  - Transaction list with filters (date, account, category, amount, text search)
  - Transaction detail panel
  - Category assignment
  - Document attachment
  - Tax relevance marking
- [ ] **Categories & Rules:**
  - Category management (CRUD)
  - Rule editor for auto-categorization
  - Budget limits per category
- [x] ViewModel layer for state management
- [ ] Unit tests for business logic

---

## Phase 5: Banking Integration (HBCI4Java)
**Status:** 🔴 Stubbed (HBCI4Java not a dependency; client returns empty results)

### Goals
- Integrate HBCI4Java for FinTS/HBCI banking
- Account synchronization
- Transaction import
- Payment initiation

### Deliverables
- [ ] **FinTS Client Wrapper:**
  - Clean Kotlin API around HBCI4Java
  - Bank connection management
  - BPD/UPD caching
- [ ] **Account Synchronization:**
  - Connect to bank via FinTS
  - Fetch account list and balances
  - Sync transaction history
  - Handle delta updates
- [ ] **Transaction Import:**
  - MT940/CAMT parsing
  - Duplicate detection (hash-based)
  - Auto-categorization via rules engine
- [ ] **Payment System (SEPA):**
  - Single transfers
  - Standing orders
  - TAN management (chipTAN, photoTAN, pushTAN)
  - Payment status tracking
- [ ] **TAN Management:**
  - TAN method selection
  - TAN entry dialogs
  - Session management
- [ ] Security best practices for credential storage

---

## Phase 6: Documents, OCR & Reports
**Status:** 🔴 Stubbed (no Tesseract/PDFBox; OCR returns placeholder text)

### Goals
- Document management system
- OCR pipeline for receipt processing
- Tax reporting and export

### Deliverables
- [ ] **Document Management:**
  - File upload (PDF, images)
  - Document storage (local filesystem)
  - Document list with search
  - Link documents to transactions
- [ ] **OCR Pipeline:**
  - Tesseract integration (JNI or external process)
  - Text extraction from receipts
  - OCR confidence scoring
  - Searchable OCR text
- [ ] **Document-Transaction Matching:**
  - Automatic matching based on amounts/dates
  - Manual linking interface
  - Receipt preview
- [ ] **Tax Reports:**
  - Annual income summary
  - Deductions by category
  - Tax-relevant transaction filtering
  - Export to PDF/CSV
- [ ] **Reports & Analytics:**
  - Monthly/yearly spending reports
  - Category breakdowns
  - Trend analysis
  - Custom date range selection

---

## Future Enhancements (Post v1.0)
- [ ] SQLCipher encryption for database
- [ ] Plugin system
- [ ] Multi-currency support
- [ ] Import from other formats (CSV, QIF, OFX)
- [ ] Budget planning and tracking
- [ ] Recurring transaction detection
- [ ] Multi-profile support (personal/business)
- [ ] Backup and restore functionality
- [ ] Dark mode toggle
- [ ] Keyboard shortcuts
- [ ] Accessibility improvements

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
- SQLite JDBC 3.47.1.0
- HBCI4Java (Phase 5)
- Tesseract (Phase 6)

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

*Last Updated: October 3, 2026*
*Current Phase: Audit corrections — see "Work Queue" at top*

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

#### Current Platform
```bash
# Build installer for current OS
./gradlew buildDistribution
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
# Output: build/compose/binaries/main/deb/findesk_1.0.0-1_amd64.deb

# Fedora/RHEL package
./gradlew packageRpm
# Output: build/compose/binaries/main/rpm/findesk-1.0.0-1.x86_64.rpm

# Universal AppImage
./gradlew packageAppImage
# Output: build/compose/binaries/main/appimage/FinDesk-1.0.0.AppImage
```

### Output Locations
| Platform | Format | Location |
|----------|--------|----------|
| macOS | DMG | `build/compose/binaries/main/dmg/` |
| Windows | MSI | `build/compose/binaries/main/msi/` |
| Windows | EXE | `build/compose/binaries/main/exe/` |
| Linux | DEB | `build/compose/binaries/main/deb/` |
| Linux | RPM | `build/compose/binaries/main/rpm/` |
| Linux | AppImage | `build/compose/binaries/main/appimage/` |
| All | JAR | `build/libs/FinDesk-0.1.0-SNAPSHOT.jar` |