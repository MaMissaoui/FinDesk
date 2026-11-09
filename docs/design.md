# FinDesk design direction: "Kontoauszug, modern"

The look Finglass and FinDesk share. FinDesk got it in restyle phases 1–4 (v1.25.0). Finglass's `docs/design.md`
holds the same rules for Slint; a change to a rule here goes there too.

## Subject

A German household's books: accounts, bookings, receipts, payments, the tax year. One careful owner who reads
numbers. The app shows where the money stands and what needs doing, with the calm precision of a bank statement.

## Colour

All values live in `ui/theme/Color.kt` and reach screens through `MaterialTheme.colorScheme` (`ui/theme/Theme.kt`)
or the helpers below. Screens never write a hex value.

| Token | Light | Dark | Scheme role / helper |
|---|---|---|---|
| ink | `#1C2733` | `#E6EAEE` | `onSurface`, `onBackground` |
| slate | `#5B6875` | `#9AA5B1` | `onSurfaceVariant` |
| paper | `#FFFFFF` | `#1A2129` | `surface`, every `surfaceContainer*` |
| desk | `#EEF1F4` | `#11161C` | `background`, `surfaceVariant`, the sidebar |
| rule | `#DDE2E7` | `#2C3540` | `outlineVariant` (hairlines, field outlines) |
| accent | `#1F4FA8` | `#8AB0F0` | `primary` |
| selected fill | `#E4EBEF` | `#232D37` | `secondaryContainer` |
| selected accent | `#DCE6F5` | `#1E2F4D` | `selectedAccent()` |
| control fill | `#E6EAEE` | `#26303A` | `controlFill()`, hover `controlFillHover()` |
| inflow | `#1E7B4F` | `#5CC28F` | `inflowColor()` |
| alert | `#B3261E` | `#F2867E` | `error` |
| caution | `#9A5B00` | `#E0A54A` | `tertiary` (`Warning`) |

FinDesk's accent is its own deep blue; Finglass uses petrol. Everything else matches.

### Colour roles

| Role | Use |
|---|---|
| Ink | content, row titles, outgoing amounts, figures ≥ 0 |
| Slate | secondary lines, labels, column headers, inactive icons, chevrons |
| Accent | **only** interactive text (text buttons, links), focus, selection bars, the period arrows, chart lines |
| Inflow | incoming money, success states that aren't money |
| Alert | negative balances, errors, destructive actions |
| Caution | warnings, "steuerrelevant", open-task counts |

Amounts go through the helpers, never a hand-picked colour: `amountColor(isExpense)` (out is ink with a leading
minus, in is inflow with "+"), `balanceColor` (ink ≥ 0, alert < 0), `balanceToneColor`, `netColor`. Expense totals
are ink, never red. On the selected fill use `SuccessOnSelection*` / `ErrorOnSelection*`, which keep 4.5:1. Category
colours are user-picked: draw their text with `readableOn(…)`. `ContrastTest` checks the pairs.

## Type

IBM Plex Sans (bundled, `FinDeskFontFamily`), tabular figures on every style. Use the role, never a literal
`fontSize` or `fontWeight` outside `ui/theme/Type.kt`.

| Role | Style | Size/weight | Use |
|---|---|---|---|
| Figure | `typography.figure` | 24/600 | KPI figures, the statement result, the period title (`PeriodNavigator`), the amount in payment review, Accounts header figures |
| Page / dialog title | `titleLarge`; dialogs get it through `headlineSmall` | 20/600 | top-bar page title, settings section title, every dialog title, empty-state titles |
| Card title | `titleMedium` | 16/600 | card and section titles, the bank name on Accounts, the compact figures above the Transactions list |
| Navigation | sidebar as built; settings list `typography.navigation` | 15/500 | the main sidebar; the settings section list |
| Row title | `labelLarge` | 14/500 | names in lists, buttons |
| Body | `bodyMedium` | 14/400 | normal text |
| Row amount | `typography.rowAmount` | 14/600 | the amount in every list row |
| Secondary | `bodySmall` | 13/400 | second lines, notes, help text under fields |
| Small label | `labelMedium` / `labelSmall` | 13/500 | column headers, KPI labels, group headers, chips |
| Caption | `typography.caption` | 12/500 | chart axis labels and badge counters only |

Material 3's `AlertDialog` reads `headlineSmall` for its title, which is why `headlineSmall` is 20/600 and figures
use `figure`. `TypographyTest` pins the sizes. German amounts are written `2.450,37 €`; amounts are right-aligned in
columns.

## Shape and surfaces

One paper surface per page region, divided by hairlines (`outlineVariant`), instead of many equal cards. Radius 8 dp
for surfaces, dialogs, buttons, fields and toolbar controls (`FinDeskShapes`), 4 dp for small marks; no pills for
filter controls. No shadows except dialogs, menus and tooltips.

Controls come from `ui/components/Buttons.kt`, all 36 dp high: `AppButton` is the one primary action (accent fill),
`AppOutlinedButton` is a quiet filled control (`controlFill()`, ink content, no outline), `AppTextButton` is accent
text, `AppIconButton` a filled 36 dp square with a 20 dp ink icon (row actions stay transparent), `AppDropdownMenu`
the toolbar drop-down. `CompactSearchField` is a 36 dp paper field with a 1 dp rule outline, accent while focused.
`PeriodNavigator` is a pair of 40 dp outlined round arrows around a figure-size title. `SingleChoiceRow` is the
segmented choice; segments size to their content.

Selection is the selected fill plus a 3 dp accent bar on the leading edge (sidebar, account panel). Settings section
rows are the exception: an 8 dp `selectedAccent()` tint with ink text and no bar. Destructive icons in slate; alert
only on hover and in the confirmation.

## Charts

Charts follow the same roles; colour is chosen by the job it does, and the palette is checked with a validator, not
by eye.

- **Forms.** Part-to-whole (spending by category, tax-deductible by category) is `CategorySplit`: a thin 8 dp bar,
  fully rounded, 2 dp gaps between segments, and one row per category in the same order (dot, name, share, amount),
  extra categories folded into a muted "more" row. No donuts. Income against expenses is `IncomeExpenseBars`; a
  balance over time is `BalanceLineChart`. A single number is a figure, not a chart.
- **Marks.** Bars at most 24 dp wide, a 4 dp rounded data end and a square base, 2 dp between neighbours. Lines 2 dp
  with an 8 dp end dot in a 2 dp surface ring and the last value as a direct label. Area fills a flat 10 % wash.
  Gridlines and axes solid 1 px hairlines in `outlineVariant`. Axis labels use one money format per axis, cents only
  when a tick needs them, and are never clipped.
- **Colour.** Income bars inflow, expense bars a neutral slate (`chartOutColor()`), balance lines accent. Categories
  wear their own colour on marks only; text stays ink or slate. Budget meters show state: accent, caution when over or
  ahead of the month.
- **Category palette.** `DefaultCategories.colors` is 24 colours: six hues (orange, blue, yellow, magenta, violet,
  aqua) in four tones, ordered so neighbours differ in hue. In that order it passes the checks in light and dark:
  every colour in the lightness band and above the chroma floor, worst neighbouring pair ΔE 9.6 for colour-blind
  readers and 16.2 for full colour vision. One aqua is below 3:1 on white, which the rows' names and values make up
  for. Green and red are not category colours, because they mean money in and alert. Users can still pick any
  colour, and existing categories keep theirs. Within a chart, segments are ordered by amount, so any two colours can
  end up side by side; the gaps and the labelled rows carry identity.
- **Hover.** Bars and lines show a tooltip on hover (inverse surface); every value is also readable without it (rows,
  direct labels, the screen-reader description).

## The signature element

The Dashboard opens with the month's reconciliation set like the foot of a bank statement
(`ui/screens/DashboardStatement.kt`): one right-aligned column of figures — Übertrag, + Einnahmen, − Ausgaben,
± Umbuchungen und Kredite — a rule, then Verfügbar. It must add up exactly for the shown month, in the primary
currency only; loans, net worth and other currencies stay outside the column.

## Payments

Transfers move real money. A design change in `PaymentsScreen.kt`, `BankingPrompts.kt` or `AccountPicker.kt` changes
style references only: every `enabled` condition, the review step, the Verification-of-Payee display, validation and
the path into `BankingService.sendOrder` stay as they are, and the IBAN in the review dialog stays large and bold.

## Checking a change

Render the headless screenshots (`./gradlew test --tests '*ScreenshotTest' -Pscreenshots=<dir>`, made-up data only)
and look at every affected screen and dialog in English and German, light and dark. Text that elides or wraps badly
is fixed by layout, not by a smaller size.
