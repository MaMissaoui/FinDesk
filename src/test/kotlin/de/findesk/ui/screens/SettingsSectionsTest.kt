package de.findesk.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.SettingsSection
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class SettingsSectionsTest {
    private val strings = Translations.of(AppLanguage.EN)

    private fun ComposeUiTest.showSettings(args: NavigationArgs = NavigationArgs()) {
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    SettingsScreen(
                        darkTheme = false,
                        onDarkThemeChange = {},
                        language = AppLanguage.EN,
                        onLanguageChange = {},
                        args = args,
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.sectionTab(section: SettingsSection) = onNode(hasText(strings[section.title]) and hasRole(Role.Tab))

    @Test
    fun `starts on Appearance`() = runSkikoComposeUiTest(Size(1440f, 900f)) {
        TestDatabase.init()
        showSettings()

        onNodeWithText(strings[StringKey.DarkTheme]).assertExists()
        onNodeWithText(strings[StringKey.ProfilesHint]).assertDoesNotExist()
    }

    @Test
    fun `clicking Categories shows that section and hides the Appearance content`() = runSkikoComposeUiTest(Size(1440f, 900f)) {
        TestDatabase.init()
        showSettings()

        sectionTab(SettingsSection.CATEGORIES).performClick()
        waitForIdle()

        onNodeWithText(strings[StringKey.AddCategory]).assertExists()
        onNodeWithText(strings[StringKey.DarkTheme]).assertDoesNotExist()
    }

    @Test
    fun `starting with a section argument shows that section first`() = runSkikoComposeUiTest(Size(1440f, 900f)) {
        TestDatabase.init()
        showSettings(NavigationArgs(settingsSection = SettingsSection.PROFILES))

        onNodeWithText(strings[StringKey.ProfilesHint]).assertExists()
        onNodeWithText(strings[StringKey.DarkTheme]).assertDoesNotExist()
    }

    @Test
    fun `only the selected section row reports selected`() = runSkikoComposeUiTest(Size(1440f, 900f)) {
        TestDatabase.init()
        showSettings()

        sectionTab(SettingsSection.APPEARANCE).assertIsSelected()
        sectionTab(SettingsSection.PROFILES).assertIsNotSelected()
        sectionTab(SettingsSection.CATEGORIES).assertIsNotSelected()

        sectionTab(SettingsSection.CATEGORIES).performClick()
        waitForIdle()

        sectionTab(SettingsSection.CATEGORIES).assertIsSelected()
        sectionTab(SettingsSection.APPEARANCE).assertIsNotSelected()
    }
}

private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
