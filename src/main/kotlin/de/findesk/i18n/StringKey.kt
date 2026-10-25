package de.findesk.i18n

enum class StringKey {
    // Common
    Cancel,
    Save,
    Add,
    Delete,
    Close,
    Ok,
    Open,
    Back,
    Dismiss,
    More,
    ClearSearch,
    Uncategorized,
    TaxRelevant,
    Tax,
    ToggleTheme,
    Link,
    Unlink,
    OpenDetails,
    ChangeCategory,
    OpenAccount,
    OpenDocument,
    OpenPreview,
    ViewPendingTasks,
    EnterValidDate,
    EnterValidAmount,
    EnterAmountGreaterThan0,
    EnterValidBalance,
    InvalidDateRange,
    PatternInvalid,
    OpticalTanCode,

    // Window / navigation
    WindowTitle,
    NavDashboard,
    NavAccounts,
    NavTransactions,
    NavPayments,
    NavDocuments,
    NavReports,
    NavSettings,

    // Account types
    TypeChecking,
    TypeSavings,
    TypeCreditCard,
    TypeLoan,
    TypeDepot,

    // Periods
    PeriodThisMonth,
    PeriodLastMonth,
    PeriodLast3Months,
    PeriodThisYear,
    PeriodLastYear,
    PeriodAllTime,

    // Direction
    DirectionAll,
    DirectionIncome,
    DirectionExpenses,

    // Cadence
    CadenceWeekly,
    CadenceMonthly,
    CadenceQuarterly,
    CadenceYearly,

    // Dashboard
    WelcomeTitle,
    WelcomeDescription,
    AddAccount,
    TotalBalance,

    /** First stat card: money on checking, savings and credit-card accounts (no loans, no depots). */
    AvailableBalance,

    /** [AvailableBalance]'s subtitle when loan accounts exist: the loans' balance and the total balance. */
    LoansSubtitle,
    IncomeThisMonth,
    ExpensesThisMonth,
    PendingTasks,
    PendingSubtitle,
    ExpensesByCategory,
    NoExpensesThisMonth,
    IncomeVsExpenses,
    Upcoming30Days,
    AmountVaries,
    BudgetsThisMonth,
    SpentOfLimit,
    OverBudget,
    LeftBudget,
    TaxDeductibleYtd,
    TaxDeductibleHint,
    RecentTransactions,
    ViewAll,
    NoTransactionsYet,
    GettingStartedIntro,
    StepAddBank,
    StepAddBankHint,
    StepSync,
    StepSyncHint,
    StepCategorize,
    StepCategorizeHint,
    OpenSettings,
    NothingPending,
    NoBookingsSixMonths,

    /** Month navigation on the dashboard: the ◀/▶ arrows' content descriptions and the jump-back button. */
    PreviousMonth,
    NextMonth,
    CurrentMonth,

    /** Stat-card and card titles for a past month, with the month label ([Strings.month]) as the argument. */
    IncomeInMonth,
    ExpensesInMonth,
    BudgetsInMonth,
    NoExpensesInMonth,
    ExpensesByCategoryInMonth,
    NoBookingsSixMonthsTo,

    // Accounts
    AccountsTotal,
    AccountCount,
    AddBank,
    NoBanksYet,
    NoBanksDescription,
    BlzPrefix,
    BicPrefix,
    SyncedPrefix,
    Syncing,
    Sync,
    SyncAll,
    SyncAllProgress,
    IncludeInSyncAll,
    NotInSyncAll,
    SyncAllNothingIncluded,
    SyncAllStopped,
    FetchHistory,
    FetchHistoryFor,
    FetchHistoryDate,
    FetchHistoryHint,
    Fetch,
    Reset,
    NextSyncFrom,
    ConnectionSettings,
    RenameConnection,
    ConnectionName,
    ConnectionNameHint,
    AddAccountShort,
    RemoveBank,
    NoAccountsYet,
    Transactions,
    EditBalance,
    DeleteAccount,

    /** Menu item on an account row: later imports and syncs may bring the account's deleted bookings back. */
    AllowDeletedAgain,
    BlzDigits,
    BankName,
    BicOptional,
    OnlineUserIdOptional,
    NoFintsServer,
    OnlineSyncAvailable,
    BankSearchHint,
    BankSearchNoMatch,
    BankManualEntry,
    BankUnknownBlz,
    BankFintsProposed,
    BankUseSuggestion,
    AddAccountAt,
    Iban,
    CurrentBalanceEuro,
    BalanceNotANumber,
    UpdateBalance,
    BalanceEuro,
    OnlineBankingFor,
    UserIdLogin,
    FintsServerUrl,
    LeaveEmptyToUse,
    AskBankForFints,
    RememberedTan,
    PinNeverStored,
    PinSavedForSyncs,
    ForgetPin,
    PinAskedEverySync,
    DaysCount,
    OneYear,
    LastDaysSummary,
    RecentBookings,
    NoneInPeriod,
    HistoryReconstructed,
    AllTransactions,
    DeleteAccountTitle,
    DeleteAccountText,
    RenameAccount,
    ChangeAccountType,
    AccountTypeLabel,
    LoanBalanceHint,
    AccountName,
    AccountNameHint,
    RemoveBankTitle,
    RemoveBankText,

    /** Shown under the BLZ field of the add-bank dialog when a branch bank code was mapped to its central one. */
    CentralBlzNote,

    /** Hint on a bank card whose branch BLZ is not the one its FinTS server accepts; %s is the central BLZ. */
    CentralBlzHint,

    /** Button that opens the connection dialog with the central BLZ; %s is the central BLZ. */
    UseBlz,

    /** Connection-dialog BLZ field: the typed BLZ is not in the bundled directory. */
    BlzNotInDirectory,

    /** Connection-dialog BLZ field: the typed BLZ belongs to another bank; %s is that bank's name. */
    BlzOtherBank,

    /** Connection-dialog note that changing the login resets FinDesk's registration with the bank. */
    LoginResetNote,

    // Transactions
    Import,
    AutoCategorize,
    ExportCsv,
    SearchPartnerPurpose,
    AllAccounts,
    TransactionsSummary,

    /** Toolbar summary suffix when the running balance column is shown; %s is the account balance. */
    TransactionsBalance,

    /** Tooltip on a row whose bank balance differs from the reconstructed one; %s is the bank's value. */
    BalanceBankMismatch,
    ColDate,
    ColDescription,
    ColCategory,
    ColAmount,
    NoTransactions,
    AddAccountFirstThen,
    NothingMatches,
    ImportBankStatement,
    AttachReceipt,
    ExportTransactions,
    AllCategories,
    MinEuro,
    MaxEuro,
    AmountFilterAny,
    AddTransaction,
    Expense,
    Income,
    DateFormat,
    AmountEuro,
    PartnerPayee,
    Purpose,
    CategoryAuto,
    AutoRules,
    ImportFile,
    BookingCount,
    PendingLeftOut,
    UnreadableCount,
    IntoAccount,
    ChooseAccount,
    FileBelongsTo,
    ImportFiles,
    FileBelongsToNamed,
    ColumnsApplyToSame,
    ImportFilesSkipped,
    UnlockWindowTitle,
    UnlockProfile,
    UnlockHeading,
    UnlockText,
    Passphrase,
    CurrentPassphrase,
    NewPassphrase,
    RepeatPassphrase,
    ShowPassphrase,
    Unlock,
    ChangePassphrase,
    ChangePassphraseText,
    ChangeAndOpen,
    CreatePassphraseHeading,
    CreatePassphraseText,
    EncryptPassphraseHeading,
    EncryptPassphraseText,
    PassphraseHint,
    DocumentsNotEncrypted,
    PassphraseNoRecovery,
    EncryptAndOpen,
    Encrypting,
    Unlocking,
    WrongPassphrase,
    PassphraseTooShort,
    PassphraseSpaces,
    PassphraseMismatch,
    PassphraseAcknowledge,
    UnlockFailed,
    RestoredEncryptedHint,
    RestoredPlainHint,
    PlaintextLeftoversTitle,
    PlaintextLeftoversText,
    PlaintextLeftoverPreRestore,
    PlaintextLeftoverDeletedProfile,
    PlaintextLeftoverPendingRestore,
    PlaintextLeftoverOtherProfile,
    EncryptionStatus,
    EncryptionOff,
    BackupPassphraseTitle,
    BackupPassphraseText,
    BackupNote,
    Execution,
    ExecuteNow,
    ExecuteOnDate,
    ExecuteStanding,
    ExecutionDate,
    FirstExecution,
    LastExecutionOptional,
    IntervalWeekly,
    IntervalMonthly,
    IntervalQuarterly,
    IntervalHalfYearly,
    IntervalYearly,
    EveryN,
    ScheduleFrom,
    ScheduleUntil,
    OnDate,
    NextOn,
    StandingOrders,
    LoadFromBank,
    StandingOrdersHint,
    NoStandingOrders,
    ConfirmStandingOrder,
    StandingOrderAcknowledgement,
    DeleteStandingOrder,
    DeleteStandingOrderTo,
    DeleteStandingOrderAcknowledgement,
    Profiles,
    ProfilesHint,
    NewProfile,
    ProfileName,
    RenameProfile,
    DeleteProfile,
    DeleteProfileText,
    SwitchProfile,
    ProfileOpen,
    ProfileNextStart,
    RestartToOpenProfile,
    WrongCurrencySkipped,
    PlusOtherCurrencies,
    OtherCurrenciesExcluded,
    PrimaryCurrency,
    PrimaryCurrencyHint,
    CurrencyIs,
    CurrentBalanceIn,
    Columns,
    Preview,
    ColDebit,
    ColCredit,
    ColPartner,
    ColPurpose,
    ColBalance,
    NoneDash,
    ColumnN,
    AndMore,
    SetAccountBalance,
    SetAccountBalanceClosing,
    SetAccountBalanceClosingPending,
    NewerBookingsUnchanged,
    BookingsSkippedHint,
    Importing,
    ImportCount,
    ErrorImportTooManyFiles,
    ErrorImportFilesTooLarge,
    ErrorImportNoReadableFile,
    ErrorImportDifferentAccounts,
    ErrorImportStopped,
    DeleteTransactionTitle,
    DocumentsCount,
    DetailDate,
    DetailAccount,
    DetailPartner,
    DetailPurpose,
    DetailCategory,
    AttachReceiptEllipsis,
    NoDocumentsLinked,
    CategorizeSimilar,
    PatternRegex,
    CreateRule,
    TabDelimiter,

    // Account panel (Transactions)
    NewGroup,
    GroupName,
    RenameGroup,
    DeleteGroup,
    DeleteGroupText,
    MoveUp,
    MoveDown,
    MoveToGroup,
    DefaultGroup,
    FilterAccounts,
    AccountsShown,
    LastFetched,
    Collapse,
    Expand,
    NewBookingsCount,
    NewBooking,
    MarkAsRead,
    DragToGroupHint,
    NoAccountsInGroup,
    CollapseSidebar,
    ExpandSidebar,
    ChangeLanguage,
    LanguageBusy,
    SwitchProfileMenu,
    ManageProfiles,
    OpenProfile,
    BackToProfile,

    // Payments
    SepaTransfer,
    NoAccounts,
    AddAndSyncFirst,
    From,
    RecipientName,
    RecipientIban,
    IbanNotValid,
    ReviewTransfer,
    RecentTransfersLabel,
    NoneYet,
    ConfirmTransfer,
    ReviewTo,
    To,
    TransferAcknowledgement,
    WarningUnconfirmedOrder,
    WaitingForBank,
    ConfirmAndSend,
    PurposeVerwendungszweck,

    // Documents
    OcrNeedsTesseract,
    Upload,
    UploadDocuments,
    SearchDocuments,
    TabAllDocuments,
    TabUnlinked,
    TabLinked,
    NoDocuments,
    NoMatches,
    UploadHint,
    TryAnotherSearch,
    DeleteDocumentTitle,
    DeleteDocumentText,
    NotLinked,
    TextExtracted,
    NoText,
    ReExtractText,
    LinkedTransaction,
    SuggestedMatches,
    FindTransactionToLink,
    ExtractedText,
    ExtractedTextConfidence,
    NoTextExtracted,
    MatchPercent,
    PreviewOf,

    // Reports
    Export,
    ExportPdf,
    ExportReport,
    TaxYear,
    CustomRange,
    PreviousYear,
    NextYear,
    TabTaxSummary,
    TabMonthlyBreakdown,
    TabCategoryAnalysis,
    TabRecurring,
    NoTransactionsIn,
    ReportsBuiltFrom,
    TotalIncome,
    TotalExpenses,
    NetIncome,
    TaxRelevantAmount,
    TaxRelevantByCategory,
    NoTaxRelevantExpenses,
    QuickStatistics,
    StatTransactions,
    CategoriesUsed,
    AvgMonthlyExpenses,
    HighestMonth,
    MonthlyIncomeExpenses,
    ColMonth,
    ColIncome,
    ColExpenses,
    ColNet,
    SpendingByCategory,
    TopVendors,
    FromDate,
    ToDate,
    Apply,
    NoRecurringFound,
    RecurringHint,
    RecurringPayments,
    CostPerYear,
    PerMonth,
    SubscriptionsTitle,
    ColName,
    ColEvery,
    ColNext,
    ColPerYear,
    TimesSeen,

    // Settings
    Appearance,
    DarkTheme,
    MenuFile,
    MenuView,
    MenuWindow,
    MenuHelp,
    MenuSettings,
    MenuExit,
    MenuEdit,
    MenuDetails,
    SyncNow,
    ShowTransactions,
    ShowAllFromPartner,
    ShowInFinder,
    ShowInExplorer,
    ShowInFolder,
    TransferAgain,
    MenuNewTransaction,
    MenuImportStatement,
    MenuMinimize,
    MenuZoom,
    MenuGitHub,
    MenuReportProblem,
    AboutTitle,
    AboutVersion,
    AboutText,
    AboutTypeface,
    Language,
    Categories,
    AddCategory,

    /** Settings > categories: how many bookings use this category. */
    CategoryUsage,

    /** Colour swatch content description in the category dialog: "Colour %1$d of %2$d". */
    ColourSwatch,

    /** Settings > categories button that gives every shared colour its own category. */
    FixDuplicateColours,
    NoCategoriesYet,
    AddDefaultCategories,
    CategoryIsTransfer,
    CategoryIsTransferHint,
    CategoryTransferLabel,
    CategorySplitMore,
    CategorySplitTransfers,
    DefaultCategoryGroceries,
    DefaultCategoryDrugstore,
    DefaultCategoryHousing,
    DefaultCategoryEnergy,
    DefaultCategoryPhoneInternetMedia,
    DefaultCategoryInsurance,
    DefaultCategoryLoans,
    DefaultCategorySavings,
    DefaultCategoryTransport,
    DefaultCategoryHealth,
    DefaultCategoryClothing,
    DefaultCategoryOnlineShopping,
    DefaultCategoryLeisure,
    DefaultCategoryRestaurants,
    DefaultCategoryTaxes,
    DefaultCategoryCash,
    DefaultCategoryCreditCard,
    DefaultCategoryOwnTransfers,
    DefaultCategorySalary,
    DefaultCategoryChildBenefit,
    DefaultCategoryInterestFees,
    DefaultCategoryFamily,
    DefaultCategoryChildrenSchool,
    DefaultCategoryOtherIncome,
    BudgetAmount,
    AutoCategorizationRules,
    AddRule,
    RulesHint,
    Prio,
    Manual,
    RuleSearchIn,
    RuleFieldAny,
    RuleFieldPartner,
    RuleFieldPurpose,
    RuleAppliesTo,
    RuleDirectionAny,
    RuleDirectionExpense,
    RuleDirectionIncome,
    RuleCategoryLabel,

    /** Settings > rules: header buttons and the preview dialog for rule-file import and export. */
    ImportRules,
    ExportRules,
    RuleImportTitle,
    RuleImportCategorize,
    RuleImportNewRules,
    RuleImportCategories,
    RuleImportDuplicates,
    RuleImportConflicts,
    RuleImportErrors,
    RuleImportConflictLine,
    RuleImportErrorLine,
    MessageRulesExported,
    MessageRulesExportSkipped,
    MessageRulesImported,

    /** Rule file: one reason per rejected data row (with the file's line number). */
    RuleFileErrorColumns,
    RuleFileErrorCategory,
    RuleFileErrorPattern,
    RuleFileErrorPatternLength,
    RuleFileErrorSearchIn,
    RuleFileErrorAppliesTo,
    RuleFileErrorPriority,
    RuleFileErrorAutoApply,

    /** Rule file: whole-file rejections. */
    ErrorRuleFileHeader,
    ErrorRuleFileTooLarge,
    ErrorRuleFileEncoding,
    DataAndOcr,
    Database,
    DocumentsFolder,
    TesseractAvailable,
    TesseractMissing,
    BackupAndRestore,
    BackupHint,
    CreateBackup,
    RestoreFromBackup,
    RestoreWaiting,
    CancelRestore,
    BackupRestoredAt,
    AuditLogLatest,
    AuditLogShort,
    AuditLogEmpty,
    RestoreFileTitle,
    RestoreCreated,
    RestoreExplanation,
    PrepareRestore,
    DeleteCategoryTitle,
    DeleteCategoryText,
    EditCategory,
    Name,
    MonthlyBudgetOptional,
    TaxRelevantHint,
    BudgetNotANumber,
    EditRule,
    PatternExample,
    Priority,
    ApplyAutomatically,
    PriorityMustBeNumber,
    SaveBackup,
    RestoreBackup,

    // Banking prompts
    PinFor,
    PinExplanation,
    RememberPinForSyncs,
    RememberPinExplanation,
    Pin,
    ChooseTanProcedure,
    ChooseTanMedium,
    TanRequired,
    TanDefaultMessage,
    TanChallenge,
    ConfirmInBankingApp,
    IConfirmedIt,
    CheckRecipient,
    BankComparedName,
    NameMismatchWarning,
    SendAnyway,
    CancelTransfer,
    MessageFromBank,

    // Charts
    NotEnoughData,
    Max,
    MinAmount,
    BalanceHistory,

    // Status messages (view models & services)
    MessageAdded,
    MessageAccountAdded,
    MessageAccountDeleted,

    /** Status message after "Allow deleted bookings again"; %d is the number of remembered deletions removed. */
    MessageDeletedForgotten,

    /** Status message when "Allow deleted bookings again" finds nothing remembered. */
    MessageNoDeletedRemembered,
    MessageBankRemoved,
    MessageConnectionSaved,
    SyncConnecting,
    MessageTransferSent,
    MessageTransferScheduled,
    MessageStandingOrderSetUp,
    MessageStandingOrderDeleted,
    MessageTransferCancelled,
    MessageTransactionAdded,
    MessageRuleCreated,
    MessageDefaultCategoriesAdded,
    MessageDefaultCategoriesNone,

    /** Result of the duplicate-colour fix: how many categories were recoloured, or that nothing was shared. */
    MessageColoursFixed,
    MessageColoursAlreadyDistinct,
    MessageAutoCategorized,
    MessageExportedCount,
    MessageBackupSaved,
    MessageRestorePrepared,
    MessageRestoreCancelled,
    MessageProfileMoved,
    MessageSavedTo,
    PinNotSaved,
    MessageUploaded,
    MessageDocumentLinked,
    MessageDocumentDeleted,
    MessageNoTextExtracted,

    // Validation errors
    BankNameRequired,
    BlzMustBe8Digits,
    ErrorConnectionBusy,
    IbanRequired,
    IbanChecksumFailed,
    AccountIbanExists,
    ErrorAmountNotZero,
    DuplicateTransactionTitle,
    DuplicateTransactionText,
    AddAnyway,
    ErrorExportFailed,
    ErrorNameRequired,
    ErrorColorFormat,
    ErrorCategoryExists,
    ErrorPatternRequired,
    ErrorInvalidRegex,
    ErrorPdfExportFailed,
    ErrorCsvExportFailed,
    ErrorSyncFromDateInvalid,
    ErrorSyncFromDateFuture,
    ErrorAccountTypeInvalid,

    // Banking errors & progress
    ErrorBankConnectionNotFound,
    ErrorAccountNotFound,
    ErrorRecipientNameRequired,
    ErrorRecipientNameTooLong,
    ErrorRecipientIbanInvalid,
    ErrorAmountGreaterThanZero,
    ErrorAmountMaxDecimals,
    ErrorPurposeTooLong,
    ErrorIntervalUnsupported,
    ErrorFirstExecutionFuture,
    ErrorLastBeforeFirst,
    ErrorExecutionDayMonth,
    ErrorExecutionDayWeek,
    ErrorExecutionDateFuture,
    ErrorOrderNotConfirmed,
    ErrorOrderOutcomeUnknown,
    ErrorDeleteNotConfirmed,
    ErrorCannotDeleteStandingOrder,
    ErrorStandingOrderOtherAccount,
    ErrorSepaEurOnly,
    ErrorSyncFailed,
    ErrorBankRejected,
    ErrorBankRejectedStatements,
    StatementsNotSupported,
    ErrorNoFintsServer,
    ErrorBadServerUrl,
    ErrorEnterUserId,
    ErrorConnectionNotFound,
    ErrorCancelled,
    ErrorCancelledByUser,
    ErrorDialogFailed,
    ErrorOrderNotOffered,
    NotOfferedByBank,
    HintNeedsAccount,
    HintNeedsCategory,
    HintNothingToExport,
    ErrorBankUnreachable,
    ErrorServerHttpStatus,
    ErrorServerNotFints,
    ErrorNoStandingOrderId,
    ErrorBankRejectedPin,
    ErrorPayeeCancelled,
    ErrorPassportReset,
    ProtocolTraceTitle,
    ProtocolTraceHint,
    ProtocolTraceEnable,
    ProtocolTraceSave,
    ProtocolTraceAvailable,
    ProtocolTraceNone,
    MessageProtocolTraceSaved,
    ErrorNoProtocolTrace,
    ProgressConnecting,
    ProgressSendingTransfer,
    ProgressSendingScheduled,
    ProgressLoadingStandingOrders,
    ProgressSendingStandingOrder,
    ProgressDeletingStandingOrder,
    ProgressSavingAccount,
    SyncSummary,
    SyncSummaryNew,
    SyncSummaryTransactions,
    SyncSummaryCategorized,
    SyncSummaryDuplicates,
    SyncSummaryHistoryFrom,
    BalanceNotProvided,
    BalanceNotProvidedNoCode,

    // Import
    ImportSummary,
    ImportSummaryFiles,
    ImportSummaryAuto,
    ImportSummaryKnown,
    ImportSummaryProbable,
    ImportSummaryWrongCurrency,
    ImportSummaryBalance,

    /** Summary suffix naming bookings the user deleted before; %d is the count. */
    ImportSummaryDeleted,
    ErrorFileNotFound,
    ErrorFileTooLarge,
    ErrorImportSeveralAccounts,
    ErrorXlsxTooLarge,
    ErrorXlsxUnreadable,

    // Documents & reports
    ErrorFileDoesNotExist,
    ErrorUnsupportedFileType,
    ErrorUnreadableImage,
    ErrorDocumentTooLarge,
    ErrorImageTooLarge,
    ReportUnknownCategory,
    ReportTitleTax,
    ReportTitleRange,
    PdfGenerated,
    PdfSummary,
    PdfTotalIncome,
    PdfTotalExpenses,
    PdfNetIncome,
    PdfTaxRelevantExpenses,
    PdfTransactionCount,
    PdfAllAmountsIn,
    PdfTaxByCategory,
    PdfNoTaxRelevant,
    PdfMonthlyBreakdown,
    PdfMonth,
    PdfIncomeExpensesNet,
    PdfExpensesByCategory,
    PdfTaxTransactions,
    ColCurrency,
    ColAccount,

    // UI components
    ChartOther,
    ChartIncome,
    ChartExpenses,
    FlickerSpeed,
    FlickerSize,
    FlickerHint,
    TanLabel,
    AppLogo,

    // Accessibility labels
    EditCategoryNamed,
    EditRuleNamed,
    OpenPreviewNamed,

    // Payment order status
    PaymentStatusPending,
    PaymentStatusAccepted,
    PaymentStatusRejected,
    PaymentStatusTanRequired,
    PaymentStatusCancelled,
    PaymentStatusUnknown,
    PaymentActionTransfer,
    PaymentActionScheduled,
    PaymentActionStandingOrder,
    PaymentActionStandingOrderDelete,
    PaymentOrderId,

    // Offline banks (no online banking; statements are imported from files)

    /** Hint shown on an offline bank's card. */
    BankOfflineHint,

    /** Label of the add-account field for an offline bank: an IBAN or the card's last 4 digits. */
    IbanOrCardLast4,

    /** Validation error when the field holds more than the card's last 4 digits (a pasted card number). */
    CardLast4Only,

    /** Banking error: the bank access is offline, so no FinTS dialog is opened. */
    ErrorBankOffline,

    /** Order error: the source account belongs to an offline bank or has no IBAN. */
    ErrorOrderOfflineAccount,

    /** Add-bank dialog choice: a normal online banking (FinTS) connection. */
    BankOnlineMode,

    /** Add-bank dialog choice: an offline bank whose statements are imported from files only. */
    BankOfflineMode,

    // Transactions: re-apply rules to shown bookings

    /** Overflow menu action that re-categorizes the shown bookings from the rules again, with a preview first. */
    ReapplyRules,

    /** Title of the preview dialog. */
    ReapplyRulesTitle,

    /** Preview dialog: how many bookings would change, of how many shown. */
    ReapplyRulesSummary,

    /** Preview dialog: the recategorization also overwrites categories the user set by hand. */
    ReapplyRulesWarning,

    /** Confirm button; %1$d is the number of bookings in the ticked groups. */
    ReapplyRulesConfirm,

    /** Overflow menu action, shown only while an undo of the last re-apply is possible. */
    UndoReapplyRules,

    /** Status message when no shown booking would change. */
    MessageReapplyNothing,

    /** Status message after the re-apply: %d is the number of changed bookings. */
    MessageReapplied,

    /** Status message after the undo: %d is the number of restored bookings. */
    MessageReapplyUndone,

    /** Disabled hint on the re-apply action when the filters hide every booking. */
    HintNothingShown,

    // Transactions: select several bookings in the list and delete them together

    /** Selection bar: how many bookings the multi-selection holds. */
    SelectedCount,

    /** Selection bar button that opens the multi-delete confirmation. */
    DeleteSelected,

    /** Selection bar button that empties the multi-selection. */
    ClearSelection,

    /** Row context menu on a row inside a selection of two or more; %d is the selection's size. */
    DeleteSelectedCount,

    /** Title of the multi-delete confirmation; %d is the number of bookings. */
    DeleteTransactionsTitle,

    /** Warning in the delete confirmations: deleted bookings are remembered, so they don't come back. */
    DeleteSyncedHint,

    /** Status message after the batch delete: %d is the number of deleted bookings. */
    MessageTransactionsDeleted,

    // Tax relevance per booking: the manual override, a rule flag and the category flag (highest layer wins)

    /** Dropdown choice: no override, rule and category decide. */
    TaxAutomatic,

    /** Dropdown choice and base of the details row: the booking is tax relevant. */
    TaxRelevantChoice,

    /** Dropdown choice and base of the details row: the booking is not tax relevant. */
    TaxNotRelevantChoice,

    /** Details dialog: the state was set by the user; %s is "Tax relevant" or "Not tax relevant". */
    TaxSetByYou,

    /** Details dialog: a rule's flag decided; %1$s is the state, %2$s the rule's pattern. */
    TaxFromRule,

    /** Details dialog: the category's flag decided; %1$s is the state, %2$s the category's name. */
    TaxFromCategory,

    /** Selection bar: the Category menu's button, placed before the Tax menu. */
    CategoryMenu,

    /** The Tax label of the details dialog's tax row, and the selection bar's menu button before "Clear selection". */
    TaxMenu,

    /** Selection bar Tax menu: mark the selection as tax relevant. */
    MarkTaxRelevant,

    /** Selection bar Tax menu: mark the selection as not tax relevant. */
    MarkNotTaxRelevant,

    /** Selection bar Tax menu: reset the selection to automatic. */
    ResetTaxAutomatic,

    /** Rule dialog: the tax flag follows the booking's category (no rule flag). */
    RuleTaxAsCategory,

    /** Rules list marker for a rule that makes matching bookings tax relevant. */
    RuleTaxMarkerYes,

    /** Rules list marker for a rule that makes matching bookings not tax relevant. */
    RuleTaxMarkerNo,

    /** Status message after a tax override change: %d is the number of bookings. */
    MessageTaxOverrideSet,

    /** Status message after a category change on a multi-selection: %d is the number of bookings. */
    MessageCategorySet,

    // Reports: the Tax bookings tab

    /** Reports tab next to the Tax summary: every tax-relevant booking of the period by category. */
    TabTaxBookings,

    /** First stat card: the sum of the income sections' nets. */
    TaxIncomeTotal,

    /** Second stat card: the sum of the deduction sections' absolute nets. */
    TaxDeductionTotal,

    /** Fourth stat card title: bookings with a receipt. */
    TaxReceipts,

    /** Fourth stat card value: "%1$d of %2$d" is "bookings with a receipt of all tax-relevant bookings". */
    TaxReceiptsOf,

    /** Suggestions card title; %1$d is the count, %2$d repeats it for the plural form. */
    TaxSuggestionsTitle,

    /** Suggestions card explanation: keyword match, what accept and dismiss do, and where to edit keywords. */
    TaxSuggestionsHint,

    /** Section header above the income groups. */
    TaxSectionIncome,

    /** Section header above the deduction groups. */
    TaxSectionDeductions,

    /** Row indicator for a booking without a receipt. */
    NoReceipt,

    /** Toggle under the summary: show only the rows without a receipt (totals stay whole-period). */
    MissingReceiptsOnly,

    /** Button inside the suggestions card that reveals the rows beyond the first twenty. */
    ShowAllCount,

    /** Empty state title; %s is the selected year or range. */
    NoTaxBookingsIn,

    /** Empty state hint on how a booking becomes tax relevant. */
    TaxBookingsHowTo,

    // Settings: the tax suggestion keywords

    /** Small card in the categories section with the keyword list behind the tax suggestions. */
    TaxSuggestionKeywords,

    /** Card button next to Save that removes the custom keyword list. */
    ResetToDefaults,

    /** Suggestions card row button that marks the booking tax relevant (accepts the suggestion). */
    Accept,

    /** Suggestions card row button that rejects the suggestion for good (stores "not tax relevant"). */
    DismissSuggestion,

    // Reports: exporting and printing the Tax bookings tab

    /** Tax tab action button: exports the tab as an Excel workbook. */
    ExportExcel,

    /** Tax tab action button: prints the report through the system print dialog. */
    Print,

    /** Accessibility click label of the dashboard's tax-deductible card, which opens the Tax bookings tab. */
    OpenTaxBookings,

    /** First Excel sheet's tab name. */
    XlsxSheetOverview,

    /** Second Excel sheet's tab name. */
    XlsxSheetBookings,

    /** First line of the exports and the print job's name; %s is the year or the "from – to" range. */
    TaxExportTitle,

    /** Overview and bookings column: which of the two sections a row belongs to. */
    TaxColSection,

    /** Overview column: how many bookings a group has. */
    TaxColBookings,

    /** Overview column: how many of a group's bookings carry a receipt. */
    TaxColWithReceipt,

    /** Bookings table column header for the receipt state. */
    TaxColReceipt,

    /** Bookings table column header: which layer decided the booking's tax relevance. */
    TaxColSource,

    /** Receipt column value for a booking with a receipt. */
    Yes,

    /** Receipt column value for a booking without a receipt. */
    No,

    /** Footer on every report page: %1$d is the page number, %2$d the page count. */
    PdfPageOf,

    /** Status message once the print job was handed to the printer. */
    MessagePrintSent,

    /** Error when the tax export (Excel or PDF) failed; never carries exception or bank data. */
    ErrorTaxExportFailed,

    /** Error when printing failed; never carries exception or bank data. */
    ErrorPrintFailed,
}
