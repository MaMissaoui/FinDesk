package de.findesk.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.model.AccountGroupItem
import de.findesk.model.AccountItem
import de.findesk.ui.components.AccountPanel
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.util.GroupKey
import de.findesk.util.dropTarget
import de.findesk.util.grandTotal
import de.findesk.util.group
import de.findesk.util.groupIdFor
import de.findesk.viewmodel.AccountSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Hit-testing and the mouse drag in the account panel. The pure [dropTarget] decides what a drop does; the Compose
 * test drives the real pointer input so a regression in the click/drag conflict shows up.
 */
@OptIn(ExperimentalTestApi::class)
class AccountPanelDragTest {
    private fun account(id: Int, type: String = "checking", groupId: Int? = null, name: String? = null) = AccountItem(
        id = id,
        bankAccessId = 1,
        bankName = "Bank",
        iban = "DE%022d".format(id),
        accountNumber = null,
        balance = BigDecimal("0"),
        currency = "EUR",
        accountType = type,
        lastUpdate = null,
        name = name,
        groupId = groupId,
    )

    // Four stacked headers, each 40 dp tall, in one shared (root) coordinate space
    private val checking = GroupKey.Type("checking")
    private val savings = GroupKey.Type("savings")
    private val travel = GroupKey.Custom(10)
    private val insurance = GroupKey.Custom(20)
    private val headers = mapOf(
        checking to Rect(0f, 0f, 200f, 40f),
        savings to Rect(0f, 40f, 200f, 80f),
        travel to Rect(0f, 80f, 200f, 120f),
        insurance to Rect(0f, 120f, 200f, 160f),
    )

    private fun at(y: Float) = Offset(100f, y)

    @Test
    fun `a custom group accepts any account, whatever its type or current group`() {
        assertEquals(travel, dropTarget(at(100f), headers, account(1, "checking"), checking))
        assertEquals(travel, dropTarget(at(100f), headers, account(1, "savings", groupId = 20), insurance))
    }

    @Test
    fun `dropping on the group the account is already in does nothing`() {
        assertNull(dropTarget(at(20f), headers, account(1, "checking"), checking))
        assertNull(dropTarget(at(140f), headers, account(1, "savings", groupId = 20), insurance))
    }

    @Test
    fun `a type group of the account's type accepts it only when it sits in a custom group`() {
        assertEquals(savings, dropTarget(at(60f), headers, account(1, "savings", groupId = 10), travel))
        assertNull(dropTarget(at(60f), headers, account(1, "savings"), savings), "already in its type group")
    }

    @Test
    fun `a type group of another type is not a target`() {
        assertNull(dropTarget(at(60f), headers, account(1, "checking", groupId = 10), travel))
        assertNull(dropTarget(at(20f), headers, account(1, "savings", groupId = 10), travel))
    }

    @Test
    fun `a pointer outside every header is not a target`() {
        assertNull(dropTarget(Offset(400f, 400f), headers, account(1, "checking", groupId = 10), travel))
    }

    @Test
    fun `groupIdFor maps a custom group to its id and a type group to null`() {
        assertEquals(10, groupIdFor(travel))
        assertNull(groupIdFor(checking))
    }

    @Test
    fun `dragging an account onto a custom group moves it once`() = runComposeUiTest {
        val moves = mutableListOf<Pair<Int, Int?>>()
        val panel = panelContent(moves = moves)
        setContent { panel.content() }

        val source = onNodeWithText("Girokonto").fetchSemanticsNode().boundsInRoot
        val target = onNodeWithText("Reisen").fetchSemanticsNode().boundsInRoot
        // Injected through the root: the row's merged button semantics node is not a reliable injection origin
        onRoot().performMouseInput {
            moveTo(source.center)
            press()
            // A first horizontal move locks the drag before it heads down to the header
            moveTo(Offset(source.center.x + 40f, source.center.y))
            moveTo(target.center)
            release()
        }
        waitForIdle()

        assertEquals(listOf(1 to 10), moves)
        assertEquals(emptyList<AccountSelection>(), panel.selected, "a drag is not a click")
    }

    @Test
    fun `dragging onto a type group of another type does nothing`() = runComposeUiTest {
        val moves = mutableListOf<Pair<Int, Int?>>()
        val panel = panelContent(moves = moves)
        setContent { panel.content() }

        val source = onNodeWithText("Tagesgeld").fetchSemanticsNode().boundsInRoot
        val target = onNodeWithText("Checking").fetchSemanticsNode().boundsInRoot
        onRoot().performMouseInput {
            moveTo(source.center)
            press()
            moveTo(Offset(source.center.x + 40f, source.center.y))
            moveTo(target.center)
            release()
        }
        waitForIdle()

        assertEquals(emptyList<Pair<Int, Int?>>(), moves)
    }

    @Test
    fun `a plain click still selects and never moves`() = runComposeUiTest {
        val moves = mutableListOf<Pair<Int, Int?>>()
        val panel = panelContent(moves = moves)
        setContent { panel.content() }

        onNodeWithText("Girokonto").performClick()
        waitForIdle()

        assertEquals(listOf(AccountSelection.Account(1)), panel.selected)
        assertEquals(emptyList<Pair<Int, Int?>>(), moves)
    }

    /** The rendered panel with two type groups ("Girokonto" checking, "Tagesgeld" savings) and one custom group. */
    private class Panel(
        val content: @Composable () -> Unit,
        val selected: MutableList<AccountSelection>,
    )

    private fun panelContent(moves: MutableList<Pair<Int, Int?>>): Panel {
        val selected = mutableListOf<AccountSelection>()
        val accounts = listOf(
            account(1, "checking", name = "Girokonto"),
            account(2, "savings", name = "Tagesgeld"),
        )
        val customGroups = listOf(AccountGroupItem(10, "Reisen", 0))
        val views = group(accounts, customGroups, "EUR")
        val total = grandTotal(accounts, "EUR")
        return Panel(
            content = {
                CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                    FinDeskTheme(darkTheme = false) {
                        AccountPanel(
                            accounts = accounts,
                            groups = views,
                            customGroups = customGroups,
                            grandTotal = total,
                            primaryCurrency = "EUR",
                            selection = AccountSelection.All,
                            collapsed = emptySet(),
                            filterText = "",
                            onSelect = { selected += it },
                            onToggleCollapsed = {},
                            onFilterText = {},
                            onCreateGroup = {},
                            onRenameGroup = { _, _ -> },
                            onDeleteGroup = {},
                            onMoveGroup = { _, _ -> },
                            onMoveAccount = { id, groupId -> moves += id to groupId },
                            onMarkRead = {},
                            modifier = Modifier.width(260.dp).height(600.dp),
                        )
                    }
                }
            },
            selected = selected,
        )
    }
}
