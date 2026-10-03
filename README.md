# FinDesk - Your Financial Workspace

A modern personal financial workspace that unifies multi-banking, transactions, documents and tax-relevant information in a secure local desktop application.

## Features

- **Multi-Banking**: Connect multiple bank accounts via FinTS/HBCI
- **Transaction Management**: Import, categorize, and track all transactions
- **Document Management**: Upload receipts, invoices, and statements with OCR
- **Tax Reports**: Generate annual tax summaries and export to PDF/CSV
- **Auto-Categorization**: Rule-based automatic transaction categorization
- **Local & Secure**: All data stored locally on your machine

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin JVM 2.1.10 |
| UI Framework | Compose Desktop 1.7.3 |
| Database | SQLite + Exposed ORM |
| Banking | HBCI4Java (FinTS) |
| OCR | Tesseract |
| Build | Gradle |

## Quick Start

### Prerequisites

- **JDK 21+** (OpenJDK or Oracle)
- **Gradle** (wrapper included)

### Run the Application

```bash
# Clone the repository
git clone https://github.com/yourusername/FinDesk.git
cd FinDesk

# Run the application
./gradlew run
```

### Run Tests

```bash
./gradlew test
```

### Build Distribution Packages

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

# Portable EXE
./gradlew packageExe

# Output: build/compose/binaries/main/msi/FinDesk-1.0.0.msi
# Output: build/compose/binaries/main/exe/FinDesk-1.0.0.exe
```

#### Linux
```bash
# Debian/Ubuntu package
./gradlew packageDeb

# Fedora/RHEL package
./gradlew packageRpm

# Universal AppImage
./gradlew packageAppImage

# Output: build/compose/binaries/main/deb/findesk_1.0.0-1_amd64.deb
# Output: build/compose/binaries/main/rpm/findesk-1.0.0-1.x86_64.rpm
# Output: build/compose/binaries/main/appimage/FinDesk-1.0.0.AppImage
```

#### All Platforms
```bash
# Build all distribution packages
./gradlew buildAllDistributions

# Create portable ZIP
./gradlew createPortableZip
```

## Project Structure

```
FinDesk/
├── src/
│   ├── main/
│   │   ├── kotlin/de/findesk/
│   │   │   ├── Main.kt                 # Entry point
│   │   │   ├── data/
│   │   │   │   ├── database/           # Database connection
│   │   │   │   ├── entity/             # DAO entities
│   │   │   │   └── table/              # Table definitions
│   │   │   ├── repository/             # Data access layer
│   │   │   ├── viewmodel/              # State management
│   │   │   ├── banking/                # FinTS/HBCI integration
│   │   │   ├── document/               # Document & OCR services
│   │   │   └── ui/
│   │   │       ├── theme/              # Colors, typography
│   │   │       ├── navigation/         # Screen routing
│   │   │       ├── components/         # Reusable UI components
│   │   │       └── screens/            # Feature screens
│   │   └── resources/
│   │       └── icons/                  # Application icons
│   └── test/
│       └── kotlin/de/findesk/
│           └── repository/             # Unit tests
├── docs/
│   ├── FinDesk.html                   # Design document
│   ├── IMPLEMENTATION_PLAN.md          # Phase plan
│   └── IMPLEMENTATION_COMPLETE.md      # Summary
├── build.gradle.kts                    # Build configuration
└── README.md                           # This file
```

## Screenshots

### Dashboard
![Dashboard](docs/screenshots/dashboard.png)

### Transactions
![Transactions](docs/screenshots/transactions.png)

### Documents
![Documents](docs/screenshots/documents.png)

## Configuration

### Database Location
The SQLite database is stored at:
- **macOS/Linux**: `~/.findesk/findesk.db`
- **Windows**: `%USERPROFILE%\.findesk\findesk.db`

### Document Storage
Uploaded documents are stored at:
- **macOS/Linux**: `~/.findesk/documents/`
- **Windows**: `%USERPROFILE%\.findesk\documents\`

## Development

### Building from Source

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Clean build
./gradlew clean build

# Run with debug logging
./gradlew run --debug
```

### Creating Icons

Place your application icons in `src/main/resources/icons/`:
- **macOS**: `icon.icns`
- **Windows**: `icon.ico`
- **Linux**: `icon.png` (512x512 recommended)

### Code Style

The project follows Kotlin coding conventions:
- 4 spaces indentation
- camelCase for variables and functions
- PascalCase for classes and interfaces
- UPPER_SNAKE_CASE for constants

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [JetBrains Compose](https://www.jetbrains.com/lp/compose-multiplatform/) - UI framework
- [Exposed](https://github.com/JetBrains/Exposed) - ORM library
- [HBCI4Java](https://hbci4java.kapott.org/) - Banking library
- [Tesseract](https://github.com/tesseract-ocr/tesseract) - OCR engine

## Support

For support, email support@findesk.de or open an issue on GitHub.

---

**FinDesk** - Your Financial Workspace. Finances. Clear. Local.