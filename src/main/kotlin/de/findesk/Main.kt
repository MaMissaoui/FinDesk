package de.findesk

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.findesk.data.database.DatabaseFactory
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.repository.SettingsRepositoryImpl
import de.findesk.ui.components.Sidebar
import de.findesk.ui.components.TopBar
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.NavigationState
import de.findesk.ui.navigation.Screen
import de.findesk.ui.navigation.shortcutScreen
import de.findesk.ui.screens.*
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.util.AppPaths
import de.findesk.util.ProfileStore
import de.findesk.viewmodel.SettingsViewModel
import de.findesk.viewmodel.UnlockViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Composable
@Preview
fun App(
    navigationState: NavigationState = remember { NavigationState() },
    language: AppLanguage = AppLanguage.EN,
    onLanguageChange: (AppLanguage) -> Unit = {},
    profileLabel: String? = null,
) {
    val settings = remember { SettingsRepositoryImpl() }
    val scope = rememberCoroutineScope()
    var isDarkTheme by remember {
        mutableStateOf(runCatching { settings.getValue(SettingsViewModel.KEY_DARK_THEME) == "true" }.getOrDefault(false))
    }
    val setDarkTheme: (Boolean) -> Unit = { enabled ->
        isDarkTheme = enabled
        scope.launch(Dispatchers.IO) { settings.setValue(SettingsViewModel.KEY_DARK_THEME, enabled.toString()) }
    }
    val navigate: (Screen, NavigationArgs) -> Unit = { screen, args -> navigationState.navigateTo(screen, args) }
    val strings = Translations.of(language)

    CompositionLocalProvider(LocalStrings provides strings) {
        FinDeskTheme(darkTheme = isDarkTheme) {
            Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Sidebar(
                    currentScreen = navigationState.currentScreen,
                    onScreenSelected = { navigationState.navigateTo(it) },
                    profileName = profileLabel,
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    TopBar(
                        title = navigationState.currentScreen.title(strings),
                        isDarkTheme = isDarkTheme,
                        onThemeToggle = { setDarkTheme(!isDarkTheme) },
                    )

                    HorizontalDivider()

                    Box(modifier = Modifier.fillMaxSize()) {
                        // key() gives each visit a fresh screen (and view model) so navigation args are applied
                        key(navigationState.currentScreen, navigationState.args, language) {
                            when (navigationState.currentScreen) {
                                Screen.DASHBOARD -> DashboardScreen(onNavigate = navigate)

                                Screen.ACCOUNTS -> AccountsScreen(onNavigate = navigate)

                                Screen.TRANSACTIONS -> TransactionsScreen(args = navigationState.args)

                                Screen.PAYMENTS -> PaymentsScreen()

                                Screen.DOCUMENTS -> DocumentsScreen()

                                Screen.REPORTS -> ReportsScreen()

                                Screen.SETTINGS -> SettingsScreen(
                                    darkTheme = isDarkTheme,
                                    onDarkThemeChange = setDarkTheme,
                                    language = language,
                                    onLanguageChange = onLanguageChange,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun main() {
    // Profile first: it decides which data directory the database, documents and passports come from
    val profiles = ProfileStore()
    val profile = profiles.active()
    AppPaths.activeProfile = profile.slug
    val dataDir = AppPaths.dataDir.apply { mkdirs() }
    // Before the gate looks at the database file, so a restored backup is what gets unlocked or encrypted
    DatabaseFactory.applyPendingRestore(dataDir)
    val profileLabel = profile.name.takeIf { profiles.list().size > 1 }
    application {
        var unlocked by remember { mutableStateOf(false) }
        if (unlocked) FinDeskWindow(profileLabel) else UnlockWindow(dataDir, profileLabel) { unlocked = true }
    }
}

/** Nothing can be read before the database is open, so language and theme come from the system here. */
@Composable
private fun ApplicationScope.UnlockWindow(dataDir: File, profileLabel: String?, onOpened: () -> Unit) {
    val vm = remember { UnlockViewModel(dataDir) }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.opened) {
        if (state.opened) {
            vm.close()
            onOpened()
        }
    }
    val strings = Translations.of(AppLanguage.fromSystem())
    Window(
        onCloseRequest = ::exitApplication,
        title = strings[StringKey.UnlockWindowTitle] + profileLabel?.let { " · $it" }.orEmpty(),
        state = rememberWindowState(size = DpSize(560.dp, 720.dp)),
        icon = painterResource("icon-512.png"),
    ) {
        CompositionLocalProvider(LocalStrings provides strings) {
            FinDeskTheme(darkTheme = isSystemInDarkTheme()) {
                UnlockScreen(vm, profileLabel)
            }
        }
    }
}

@Composable
private fun ApplicationScope.FinDeskWindow(profileLabel: String?) {
    val settings = SettingsRepositoryImpl()
    val windowState = rememberWindowState()
    val navigationState = remember { NavigationState() }
    val scope = rememberCoroutineScope()
    var language by remember {
        mutableStateOf(
            AppLanguage.fromCode(runCatching { settings.getValue(AppLanguage.KEY) }.getOrNull()),
        )
    }
    val setLanguage: (AppLanguage) -> Unit = { lang ->
        language = lang
        scope.launch(Dispatchers.IO) { settings.setValue(AppLanguage.KEY, lang.code) }
    }
    val strings = Translations.of(language)

    Window(
        onCloseRequest = ::exitApplication,
        onPreviewKeyEvent = { event ->
            val target = if (event.type == KeyEventType.KeyDown) shortcutScreen(event.key, event.isCtrlPressed || event.isMetaPressed) else null
            if (target != null) navigationState.navigateTo(target)
            target != null
        },
        title = strings[StringKey.WindowTitle] + profileLabel?.let { " · $it" }.orEmpty(),
        state = windowState,
        icon = painterResource("icon-512.png"),
    ) {
        App(navigationState, language, setLanguage, profileLabel)
    }
}
