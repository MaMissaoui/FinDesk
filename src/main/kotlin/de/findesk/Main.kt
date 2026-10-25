package de.findesk

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.findesk.banking.CredentialSelfCheck
import de.findesk.data.database.DatabaseFactory
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.SettingsRepository
import de.findesk.repository.SettingsRepositoryImpl
import de.findesk.ui.components.AboutDialog
import de.findesk.ui.components.ProfileMenuModel
import de.findesk.ui.components.Sidebar
import de.findesk.ui.components.TopBar
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.NavigationState
import de.findesk.ui.navigation.Screen
import de.findesk.ui.navigation.ScreenRequest
import de.findesk.ui.navigation.SettingsSection
import de.findesk.ui.navigation.shortcutScreen
import de.findesk.ui.platform.AppMenuBar
import de.findesk.ui.platform.MacAppMenuHandlers
import de.findesk.ui.platform.Platform
import de.findesk.ui.screens.*
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.util.AppPaths
import de.findesk.util.ProfileStore
import de.findesk.util.ProfileSwitch
import de.findesk.viewmodel.Busy
import de.findesk.viewmodel.SettingsViewModel
import de.findesk.viewmodel.UnlockViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.io.File
import kotlin.system.exitProcess

@Composable
@Preview
fun App(
    navigationState: NavigationState = remember { NavigationState() },
    language: AppLanguage = AppLanguage.EN,
    onLanguageChange: (AppLanguage) -> Unit = {},
    profileLabel: String? = null,
    isDarkTheme: Boolean = false,
    setDarkTheme: (Boolean) -> Unit = {},
    aboutOpen: Boolean = false,
    onAboutClose: () -> Unit = {},
    sidebarCollapsed: Boolean = false,
    onToggleSidebar: () -> Unit = {},
    currentProfile: String? = null,
    profiles: ProfileStore? = null,
    onSwitchProfile: ((String?) -> Unit)? = null,
) {
    // A language change recreates the screen (see the key below), so the top bar refuses it while work would be lost
    val busy by Busy.active.collectAsState()
    // Links inside a screen: they leave a back entry; the sidebar, shortcuts and menus use navigateTo (see below)
    val navigate: (Screen, NavigationArgs) -> Unit = { screen, args -> navigationState.follow(screen, args) }
    val strings = Translations.of(language)
    LaunchedEffect(language) { AppStrings.set(language) }

    CompositionLocalProvider(LocalStrings provides strings) {
        FinDeskTheme(darkTheme = isDarkTheme) {
            // Surface sets the content colour too: text drawn straight on the background is otherwise black in dark mode
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Sidebar(
                        topInset = Platform.titleBarHeight,
                        currentScreen = navigationState.currentScreen,
                        onScreenSelected = { navigationState.navigateTo(it) },
                        lockedOn = navigationState.lockedOn,
                        collapsed = sidebarCollapsed,
                    )

                    Column(modifier = Modifier.fillMaxSize()) {
                        TopBar(
                            title = navigationState.currentScreen.title(strings),
                            isDarkTheme = isDarkTheme,
                            onThemeToggle = { setDarkTheme(!isDarkTheme) },
                            sidebarCollapsed = sidebarCollapsed,
                            onToggleSidebar = onToggleSidebar,
                            language = language,
                            onLanguageChange = onLanguageChange,
                            languageEnabled = !busy && navigationState.lockedOn == null,
                            // While an order is being sent there is nowhere to go back to
                            backTarget = if (navigationState.lockedOn == null) navigationState.backTarget else null,
                            onBack = { navigationState.back() },
                            profileMenu = profiles?.let { store ->
                                ProfileMenuModel(
                                    current = currentProfile,
                                    load = { store.list().map { it.slug to it.name } },
                                    onSwitch = { onSwitchProfile?.invoke(it) },
                                    onManage = { navigationState.navigateTo(Screen.SETTINGS, NavigationArgs(settingsSection = SettingsSection.PROFILES)) },
                                )
                            },
                        )

                        HorizontalDivider()

                        Box(modifier = Modifier.fillMaxSize()) {
                            // key() gives each visit a fresh screen (and view model) so navigation args are applied
                            key(navigationState.currentScreen, navigationState.args, language) {
                                when (navigationState.currentScreen) {
                                    Screen.DASHBOARD -> DashboardScreen(onNavigate = navigate, args = navigationState.args)

                                    Screen.ACCOUNTS -> AccountsScreen(onNavigate = navigate)

                                    Screen.TRANSACTIONS -> TransactionsScreen(args = navigationState.args, onNavigate = navigate)

                                    Screen.PAYMENTS -> PaymentsScreen(
                                        onSendingChange = { sending ->
                                            if (sending) navigationState.lock(Screen.PAYMENTS) else navigationState.unlock(Screen.PAYMENTS)
                                        },
                                        onNavigate = navigate,
                                    )

                                    Screen.DOCUMENTS -> DocumentsScreen()

                                    Screen.REPORTS -> ReportsScreen(args = navigationState.args, onNavigate = navigate)

                                    Screen.SETTINGS -> SettingsScreen(
                                        darkTheme = isDarkTheme,
                                        onDarkThemeChange = setDarkTheme,
                                        language = language,
                                        onLanguageChange = onLanguageChange,
                                        onSwitchProfile = onSwitchProfile,
                                        profileSwitchEnabled = !busy && navigationState.lockedOn == null,
                                        args = navigationState.args,
                                    )
                                }
                            }
                        }
                    }
                }
                if (aboutOpen) AboutDialog(onAboutClose)
            }
        }
    }
}

fun main(args: Array<String>) {
    // Diagnostic entry point: no window, no data directory, no pending restore
    if (args.firstOrNull() == "--credential-self-check") exitProcess(CredentialSelfCheck.run(args.getOrNull(1)))
    Platform.configureProcess()
    // Profile first: it decides which data directory the database, documents and passports come from
    val profiles = ProfileStore()
    val profile = profiles.active()
    AppPaths.activeProfile = profile.slug
    // Before the gate looks at the database file, so a restored backup is what gets unlocked or encrypted
    DatabaseFactory.applyPendingRestore(AppPaths.dataDir.apply { mkdirs() })
    application {
        var session by remember { mutableStateOf(ProfileSession(profile.slug)) }
        var prefs by remember { mutableStateOf<UiPrefs?>(null) }
        val scope = rememberCoroutineScope()
        // Completed when the main window has left the composition, i.e. after every screen's view model was closed
        var mainWindowGone by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
        // Set synchronously, so a second request (top bar and Settings) can't close the next profile's database
        var switching by remember { mutableStateOf(false) }
        val switchProfile: (String?) -> Unit = switch@{ target ->
            if (switching || target == session.slug || Busy.active.value) return@switch
            switching = true
            val from = session.slug
            val fromMain = prefs != null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        // Into the profile being left, while its database is still open
                        if (fromMain) runCatching { AuditLogRepositoryImpl().log("PROFILE_SWITCH", "user", "Switched from profile ${from ?: "default"} to ${target ?: "default"}") }
                        DatabaseFactory.applyPendingRestore(ProfileSwitch.dataDir(target).apply { mkdirs() })
                    }
                    val gone = CompletableDeferred<Unit>().also { if (fromMain) mainWindowGone = it else it.complete(Unit) }
                    // One snapshot: the main window goes and the target's unlock window comes, so a window is always open
                    prefs = null
                    session = ProfileSession(target, previous = from, ready = false)
                    // Only once the main window and its view models are disposed is the database closed under them
                    gone.await()
                    withContext(Dispatchers.IO) { ProfileSwitch.closeAndActivate(target, profiles) }
                    session = session.copy(ready = true)
                } finally {
                    mainWindowGone = null
                    switching = false
                }
            }
        }
        // The name shows in the window titles once there is more than one profile
        val profileLabel = remember(session.slug) { profiles.list().takeIf { it.size > 1 }?.firstOrNull { it.slug == session.slug }?.name }
        prefs?.let { FinDeskWindow(profileLabel, it, session.slug, profiles, switchProfile, onDisposed = { mainWindowGone?.complete(Unit) }) }
            ?: key(session.slug) {
                val back = session.previous?.let { previous -> profiles.list().firstOrNull { it.slug == previous } }
                UnlockWindow(
                    dataDir = ProfileSwitch.dataDir(session.slug),
                    profileLabel = profileLabel,
                    ready = session.ready,
                    back = back?.let { it.name to { switchProfile(it.slug) } },
                ) { prefs = it }
            }
    }
}

/** The profile whose unlock screen or main window is shown; [previous] is offered as "Back to …" after a switch. */
private data class ProfileSession(val slug: String?, val previous: String? = null, val ready: Boolean = true)

/** Language, theme and sidebar, read off the UI thread once the database is open, before the main window shows them. */
private data class UiPrefs(val language: AppLanguage, val darkTheme: Boolean, val sidebarCollapsed: Boolean = false) {
    companion object {
        fun load(settings: SettingsRepository = SettingsRepositoryImpl()) = UiPrefs(
            language = AppLanguage.fromCode(runCatching { settings.getValue(AppLanguage.KEY) }.getOrNull()),
            darkTheme = runCatching { settings.getValue(SettingsViewModel.KEY_DARK_THEME) == "true" }.getOrDefault(false),
            sidebarCollapsed = runCatching { settings.getValue(KEY_SIDEBAR_COLLAPSED) == "true" }.getOrDefault(false),
        )
    }
}

/** Nothing can be read before the database is open, so language and theme come from the system here. */
@Composable
private fun ApplicationScope.UnlockWindow(
    dataDir: File,
    profileLabel: String?,
    /** False while the previous profile's database is still being closed after a switch. */
    ready: Boolean = true,
    /** After a switch: the previous profile's name and how to go back to its unlock screen. */
    back: Pair<String, () -> Unit>? = null,
    onOpened: (UiPrefs) -> Unit,
) {
    val vm = remember { UnlockViewModel(dataDir) }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.opened) {
        if (state.opened) {
            vm.close()
            onOpened(withContext(Dispatchers.IO) { UiPrefs.load() })
        }
    }
    val strings = Translations.of(AppLanguage.fromSystem())
    LaunchedEffect(Unit) { AppStrings.set(AppLanguage.fromSystem()) }
    Window(
        onCloseRequest = ::exitApplication,
        title = strings[StringKey.UnlockWindowTitle] + profileLabel?.let { " · $it" }.orEmpty(),
        state = rememberWindowState(size = DpSize(560.dp, 720.dp)),
        icon = painterResource("icon-512.png"),
    ) {
        CompositionLocalProvider(LocalStrings provides strings) {
            FinDeskTheme(darkTheme = isSystemInDarkTheme()) {
                if (ready) {
                    UnlockScreen(vm, profileLabel, back)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
private fun ApplicationScope.FinDeskWindow(
    profileLabel: String?,
    prefs: UiPrefs,
    currentProfile: String?,
    profiles: ProfileStore,
    onSwitchProfile: (String?) -> Unit,
    onDisposed: () -> Unit = {},
) {
    // First in this window, so it is disposed last: after the screens below and their view models
    DisposableEffect(Unit) { onDispose { onDisposed() } }
    val settings = remember { SettingsRepositoryImpl() }
    // Maximized: the tables and charts want the room. On macOS this zooms to fill the screen, not a full-screen Space
    val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
    val navigationState = remember { NavigationState() }
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf(prefs.language) }
    val setLanguage: (AppLanguage) -> Unit = { lang ->
        language = lang
        scope.launch(Dispatchers.IO) { settings.setValue(AppLanguage.KEY, lang.code) }
    }
    val strings = Translations.of(language)
    // Up here rather than in App: the macOS View menu toggles it too
    var darkTheme by remember { mutableStateOf(prefs.darkTheme) }
    val setDarkTheme: (Boolean) -> Unit = { enabled ->
        darkTheme = enabled
        scope.launch(Dispatchers.IO) { settings.setValue(SettingsViewModel.KEY_DARK_THEME, enabled.toString()) }
    }
    var sidebarCollapsed by remember { mutableStateOf(prefs.sidebarCollapsed) }
    val toggleSidebar: () -> Unit = {
        val next = !sidebarCollapsed
        sidebarCollapsed = next
        scope.launch(Dispatchers.IO) { settings.setValue(KEY_SIDEBAR_COLLAPSED, next.toString()) }
    }
    var aboutOpen by remember { mutableStateOf(false) }
    var requestId by remember { mutableLongStateOf(0L) }
    val request: (ScreenRequest) -> Unit = { navigationState.navigateTo(Screen.TRANSACTIONS, NavigationArgs(request = it, requestId = ++requestId)) }

    Window(
        onCloseRequest = ::exitApplication,
        onPreviewKeyEvent = { event ->
            val down = event.type == KeyEventType.KeyDown
            val target = if (down) shortcutScreen(event.key, event.isCtrlPressed || event.isMetaPressed) else null
            if (target != null) navigationState.navigateTo(target)
            when {
                // Back: ⌘/Ctrl + [ and Alt + Left arrow; only consumed when something was opened, so typing keeps the keys
                down && ((event.key == Key.LeftBracket && (event.isCtrlPressed || event.isMetaPressed)) || (event.key == Key.DirectionLeft && event.isAltPressed)) ->
                    navigationState.back()

                else -> target != null
            }
        },
        title = strings[StringKey.WindowTitle] + profileLabel?.let { " · $it" }.orEmpty(),
        state = windowState,
        icon = painterResource("icon-512.png"),
    ) {
        LaunchedEffect(Unit) {
            Platform.unifyTitleBar(window)
            // Below this the stat cards and table columns break words mid-way
            window.minimumSize = Dimension(1024, 700)
        }
        LaunchedEffect(darkTheme) { Platform.setDarkAppearance(window, darkTheme) }
        MacAppMenuHandlers(onAbout = { aboutOpen = true }, onSettings = { navigationState.navigateTo(Screen.SETTINGS) })
        AppMenuBar(
            strings,
            navigationState,
            windowState,
            darkTheme,
            setDarkTheme,
            onRequest = request,
            onSettings = { navigationState.navigateTo(Screen.SETTINGS) },
            onAbout = { aboutOpen = true },
            onExit = ::exitApplication,
        )
        App(
            navigationState,
            language,
            setLanguage,
            profileLabel,
            darkTheme,
            setDarkTheme,
            aboutOpen,
            onAboutClose = { aboutOpen = false },
            sidebarCollapsed = sidebarCollapsed,
            onToggleSidebar = toggleSidebar,
            currentProfile = currentProfile,
            profiles = profiles,
            onSwitchProfile = onSwitchProfile,
        )
    }
}

/** Settings key: whether the sidebar shows icons only, per profile. */
private const val KEY_SIDEBAR_COLLAPSED = "ui.sidebarCollapsed"
