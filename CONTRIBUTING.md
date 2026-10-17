# Contributing to FinDesk

Thanks for your interest in improving FinDesk! This document describes how to
build, test and submit changes.

## Getting started

1. Install **JDK 21** or newer.
2. Clone the repository and run the app:

   ```bash
   git clone https://github.com/MaMissaoui/FinDesk.git
   cd FinDesk
   FINDESK_HOME=/tmp/findesk-dev ./gradlew run --no-daemon
   ```

   Always point `FINDESK_HOME` at a throwaway directory while developing so you
   never touch a real data directory (`~/.findesk`).

3. Run the tests and linter:

   ```bash
   ./gradlew ktlintCheck test
   ```

## Making changes

- Keep commits small and focused, with a message that explains the *why*.
- Match the existing Kotlin style and the invariants described in
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Wildcard imports are allowed
  (see `.editorconfig`).
- Cover new behaviour with tests. Repository tests use `TestDatabase` and
  `TestData` for isolated fixtures — never the real data directory.
- Run `./gradlew ktlintFormat` if your editor does not format on save.
- Update [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) when you
  complete or discover work items.

## Bank safety rules

These are non-negotiable, because they protect users' money and credentials:

- **Never run code against a real bank** in development or tests. Use the fakes
  and the local closed-port smoke test.
- Any new order type must go through `BankingService.sendOrder`, which enforces
  explicit confirmation, validation and audit logging.
- Never store or log PINs, TANs or the connection passphrase.
- Keep the database-fetching rules intact: do not let DAO entities escape a
  transaction — use the `de.findesk.model` snapshot types.

## Pull requests

- Describe the problem and the approach, and reference the related issue.
- Ensure `./gradlew ktlintCheck test` is green.
- For UI changes, attach a screenshot (render one with the
  `*ScreenshotTest` headless renderer described in the README).

## License

By contributing, you agree that your contributions are licensed under the
MIT License (see [LICENSE](LICENSE)).
