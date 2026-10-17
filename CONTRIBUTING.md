# Contributing to FinDesk

Thanks for your interest in improving FinDesk! This document describes how to
build, test and submit changes.

FinDesk is alpha software and must not be used with real bank accounts yet — see
the [disclaimer](README.md#disclaimer) in the README.

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

## Installer icons

The installers need the icon in the format each OS expects: `.icns` for macOS,
`.ico` for Windows and `.png` for Linux (see the `macOS`/`windows`/`linux`
blocks in `build.gradle.kts`). jpackage silently substitutes its own default
icon (Java's "Duke") when the format does not match, so a wrong file type means
the shipped installer has the wrong icon even though the build succeeds.

`src/main/resources/icons/icon.icns` and `icon.ico` are generated from
`src/main/resources/FinDesk-icon.svg`:

```bash
# .icns (macOS) — render a full icon set from the vector, then pack it
mkdir -p /tmp/icon.iconset
for s in 16 32 64 128 256 512 1024; do
  rsvg-convert -w "$s" -h "$s" src/main/resources/FinDesk-icon.svg -o "/tmp/s$s.png"
done
cp /tmp/s16.png  /tmp/icon.iconset/icon_16x16.png
cp /tmp/s32.png  /tmp/icon.iconset/icon_16x16@2x.png
cp /tmp/s32.png  /tmp/icon.iconset/icon_32x32.png
cp /tmp/s64.png  /tmp/icon.iconset/icon_32x32@2x.png
cp /tmp/s128.png /tmp/icon.iconset/icon_128x128.png
cp /tmp/s256.png /tmp/icon.iconset/icon_128x128@2x.png
cp /tmp/s256.png /tmp/icon.iconset/icon_256x256.png
cp /tmp/s512.png /tmp/icon.iconset/icon_256x256@2x.png
cp /tmp/s512.png /tmp/icon.iconset/icon_512x512.png
cp /tmp/s1024.png /tmp/icon.iconset/icon_512x512@2x.png
iconutil -c icns /tmp/icon.iconset -o src/main/resources/icons/icon.icns

# .ico (Windows) — one file with the common sizes
magick /tmp/s256.png /tmp/s128.png /tmp/s64.png /tmp/s32.png /tmp/s16.png \
  src/main/resources/icons/icon.ico
```

`rsvg-convert` (librsvg) and `magick` (ImageMagick) are the only extra tools
needed. The PNGs used inside the app (`icon-512.png`, `icon-64.png`) stay at the
root of `src/main/resources/` because the UI loads them from the classpath.

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
