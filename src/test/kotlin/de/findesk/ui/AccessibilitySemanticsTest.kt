package de.findesk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.ui.components.DonutChart
import de.findesk.ui.components.MessageBanner
import de.findesk.ui.components.SectionCard
import de.findesk.ui.components.Sidebar
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.viewmodel.ChartSlice
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalTestApi::class)
class AccessibilitySemanticsTest {

    private val strings = Translations.of(AppLanguage.EN)

    @Test
    fun `Sidebar items expose tab role and selection state`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    Sidebar(currentScreen = Screen.TRANSACTIONS, onScreenSelected = {})
                }
            }
        }

        onNode(hasText(strings[StringKey.NavTransactions]) and hasRole(Role.Tab))
            .assertIsSelected()
        onNode(hasText(strings[StringKey.NavAccounts]) and hasRole(Role.Tab))
            .assertExists()
    }

    @Test
    fun `Sidebar icon does not duplicate its visible label`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    Sidebar(currentScreen = Screen.DASHBOARD, onScreenSelected = {})
                }
            }
        }

        onNode(hasContentDescription(strings[StringKey.NavDashboard])).assertDoesNotExist()
    }

    @Test
    fun `MessageBanner error announces assertively`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    MessageBanner(message = null, error = "Something went wrong", onDismiss = {})
                }
            }
        }

        onNodeWithText("Something went wrong")
            .assert(hasLiveRegion(LiveRegionMode.Assertive))
    }

    @Test
    fun `Donut chart exposes a textual summary of its data`() = runComposeUiTest {
        val slices = listOf(
            ChartSlice("Groceries", BigDecimal("320.00"), "#4CAF50"),
            ChartSlice("Rent", BigDecimal("900.00"), "#2196F3"),
        )
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    DonutChart(slices = slices, title = strings[StringKey.ExpensesByCategory])
                }
            }
        }

        onNode(hasContentDescription("Groceries", substring = true))
            .assertExists()
        onNode(hasContentDescription("Rent", substring = true))
            .assertExists()
    }

    @Test
    fun `SectionCard title is marked as a heading`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    SectionCard(title = "Section Title") {
                        Text("content")
                    }
                }
            }
        }

        onNodeWithText("Section Title")
            .assert(hasHeading())
    }
}

private fun hasRole(role: Role) = androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

private fun hasLiveRegion(mode: LiveRegionMode) = androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

private fun hasHeading() = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
