package de.findesk.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.repository.NewTransaction
import de.findesk.repository.TransactionRepositoryImpl
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.platform.Platform
import de.findesk.ui.screens.TransactionsScreen
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.viewmodel.Period
import de.findesk.viewmodel.TransactionsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * Drives the real transactions screen headlessly with mouse and key events, because the row's click handling only
 * shows its behaviour at runtime: a Cmd/Ctrl-click once opened the details instead of selecting the row.
 */
@OptIn(InternalComposeUiApi::class)
class TransactionSelectionUiTest {
    @Test
    fun `modifier clicks select rows, list keys act on the selection and a plain click opens the details`() {
        TestDatabase.init()
        val bank = BankAccessRepositoryImpl().create("Test Bank", "37040044", "COBADEFFXXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        val day = LocalDate.of(2026, 3, 10)
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(account, day.minusDays(3), BigDecimal("-20.00"), "EUR", "Bakery", "Rolls", null, "ui-1"),
                NewTransaction(account, day.minusDays(2), BigDecimal("-80.00"), "EUR", "Utility", "Instalment", null, "ui-2"),
                NewTransaction(account, day.minusDays(1), BigDecimal("120.00"), "EUR", "Employer Ltd", "Salary", null, "ui-3"),
                NewTransaction(account, day, BigDecimal("-49.63"), "EUR", "Kiosk", "Water", null, "ui-4"),
            ),
        )

        val holder = arrayOfNulls<TransactionsViewModel>(1)
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        val scene = ImageComposeScene(width = 1440, height = 900, density = Density(1f), coroutineContext = dispatcher) {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        TransactionsScreen(NavigationArgs(period = Period.ALL), createViewModel = { TransactionsViewModel().also { holder[0] = it } }, onNavigate = { _, _ -> })
                    }
                }
            }
        }
        fun frames(count: Int) = repeat(count) {
            Thread.sleep(20)
            while (true) (tasks.poll() ?: break).run()
            scene.render(0L)
            while (true) (tasks.poll() ?: break).run()
        }
        fun click(y: Float, toggle: Boolean = false, shift: Boolean = false) {
            val mods = PointerKeyboardModifiers(isMetaPressed = toggle && Platform.isMac, isCtrlPressed = toggle && !Platform.isMac, isShiftPressed = shift)
            scene.sendPointerEvent(PointerEventType.Press, Offset(700f, y), type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), keyboardModifiers = mods)
            scene.sendPointerEvent(PointerEventType.Release, Offset(700f, y), type = PointerType.Mouse, keyboardModifiers = mods)
            frames(5)
        }
        fun key(key: Key, shortcut: Boolean = false) {
            val meta = shortcut && Platform.isMac
            val ctrl = shortcut && !Platform.isMac
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isMetaPressed = meta, isCtrlPressed = ctrl))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isMetaPressed = meta, isCtrlPressed = ctrl))
            frames(3)
        }
        try {
            frames(50)
            val vm = holder[0] ?: error("the screen did not create its view model")
            val ids = vm.state.value.transactions.map { it.id }
            assertEquals(4, ids.size, "the list shows the four bookings, newest first")
            // Row centres at 1440x900, density 1; the selection bar above the header moves them down by 64 px
            val rows = listOf(221f, 276f, 331f, 386f)
            val withBar = rows.map { it + 64f }

            click(rows[0], toggle = true)
            assertEquals(setOf(ids[0]), vm.state.value.selectedIds, "Cmd/Ctrl-click selects the row")
            assertNull(vm.state.value.selected, "Cmd/Ctrl-click must not open the details")

            click(withBar[2], shift = true)
            assertEquals(setOf(ids[0], ids[1], ids[2]), vm.state.value.selectedIds, "Shift-click selects the range from the anchor")
            assertNull(vm.state.value.selected)

            click(withBar[1], toggle = true)
            assertEquals(setOf(ids[0], ids[2]), vm.state.value.selectedIds, "Cmd/Ctrl-click on a selected row removes it")

            key(Key.A, shortcut = true)
            assertEquals(ids.toSet(), vm.state.value.selectedIds, "Cmd/Ctrl+A selects every shown booking")

            key(Key.Escape)
            assertTrue(vm.state.value.selectedIds.isEmpty(), "Escape clears the selection")

            click(rows[0], toggle = true)
            click(withBar[1])
            assertEquals(ids[1], vm.state.value.selected?.id, "a plain click opens the details")
            assertTrue(vm.state.value.selectedIds.isEmpty(), "a plain click clears the selection")
            vm.close()
        } finally {
            scene.close()
        }
    }
}
