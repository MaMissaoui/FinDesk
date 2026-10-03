# FinDesk Implementation Plan

## Overview
A phased approach to building FinDesk - a Kotlin JVM + Compose Desktop financial workspace application with HBCI4Java banking integration.

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
**Status:** ✅ Completed

### Goals
- Implement core financial data management screens
- Dashboard with key metrics
- Transaction filtering and categorization

### Deliverables
- [x] **Dashboard Screen:**
  - Balance overview widgets
  - Recent transactions list
  - Expense pie chart
  - Tax deduction bar chart
  - Pending tasks/notifications
- [x] **Accounts Screen:**
  - Account list with balances
  - Account detail view
  - Balance history line chart
  - Upcoming payments section
- [x] **Transactions Screen:**
  - Transaction list with filters (date, account, category, amount, text search)
  - Transaction detail panel
  - Category assignment
  - Document attachment
  - Tax relevance marking
- [x] **Categories & Rules:**
  - Category management (CRUD)
  - Rule editor for auto-categorization
  - Budget limits per category
- [x] ViewModel layer for state management
- [ ] Unit tests for business logic

---

## Phase 5: Banking Integration (HBCI4Java)
**Status:** ✅ Completed

### Goals
- Integrate HBCI4Java for FinTS/HBCI banking
- Account synchronization
- Transaction import
- Payment initiation

### Deliverables
- [x] **FinTS Client Wrapper:**
  - Clean Kotlin API around HBCI4Java
  - Bank connection management
  - BPD/UPD caching
- [x] **Account Synchronization:**
  - Connect to bank via FinTS
  - Fetch account list and balances
  - Sync transaction history
  - Handle delta updates
- [x] **Transaction Import:**
  - MT940/CAMT parsing
  - Duplicate detection (hash-based)
  - Auto-categorization via rules engine
- [x] **Payment System (SEPA):**
  - Single transfers
  - Standing orders
  - TAN management (chipTAN, photoTAN, pushTAN)
  - Payment status tracking
- [x] **TAN Management:**
  - TAN method selection
  - TAN entry dialogs
  - Session management
- [ ] Security best practices for credential storage

---

## Phase 6: Documents, OCR & Reports
**Status:** ✅ Completed

### Goals
- Document management system
- OCR pipeline for receipt processing
- Tax reporting and export

### Deliverables
- [x] **Document Management:**
  - File upload (PDF, images)
  - Document storage (local filesystem)
  - Document list with search
  - Link documents to transactions
- [x] **OCR Pipeline:**
  - Tesseract integration (JNI or external process)
  - Text extraction from receipts
  - OCR confidence scoring
  - Searchable OCR text
- [x] **Document-Transaction Matching:**
  - Automatic matching based on amounts/dates
  - Manual linking interface
  - Receipt preview
- [x] **Tax Reports:**
  - Annual income summary
  - Deductions by category
  - Tax-relevant transaction filtering
  - Export to PDF/CSV
- [x] **Reports & Analytics:**
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
*Current Phase: All Phases Complete*

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