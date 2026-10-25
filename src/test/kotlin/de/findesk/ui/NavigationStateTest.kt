package de.findesk.ui

import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.NavigationState
import de.findesk.ui.navigation.Screen
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.YearMonth

class NavigationStateTest {
    @Test
    fun `a locked screen can't be left until it unlocks`() {
        val nav = NavigationState()
        nav.navigateTo(Screen.PAYMENTS)
        nav.lock(Screen.PAYMENTS) // an order is being sent

        nav.navigateTo(Screen.DASHBOARD)
        nav.navigateTo(Screen.TRANSACTIONS, NavigationArgs(accountId = 1))
        assertEquals(Screen.PAYMENTS, nav.currentScreen)
        assertEquals(NavigationArgs(), nav.args, "a refused navigation must not leave its args behind")

        nav.unlock(Screen.DASHBOARD) // only the screen holding the lock releases it
        nav.navigateTo(Screen.DASHBOARD)
        assertEquals(Screen.PAYMENTS, nav.currentScreen)

        nav.unlock(Screen.PAYMENTS)
        nav.navigateTo(Screen.DASHBOARD)
        assertEquals(Screen.DASHBOARD, nav.currentScreen)
        assertNull(nav.lockedOn)
    }

    @Test
    fun `follow leaves a back entry that back opens`() {
        val nav = NavigationState()

        nav.follow(Screen.TRANSACTIONS, NavigationArgs(categoryId = 3))
        assertEquals(Screen.TRANSACTIONS, nav.currentScreen)
        assertEquals(Screen.DASHBOARD, nav.backTarget)

        assertTrue(nav.back())
        assertEquals(Screen.DASHBOARD, nav.currentScreen)
        assertEquals(NavigationArgs(), nav.args, "back reopens the dashboard as it was")

        assertNull(nav.backTarget, "an emptied stack offers no back")
        assertFalse(nav.back())
    }

    @Test
    fun `backArgs win when the left screen must reopen on its own args`() {
        val nav = NavigationState()
        val august = NavigationArgs(month = YearMonth.of(2026, 8))

        nav.follow(Screen.TRANSACTIONS, NavigationArgs(categoryId = 3, backArgs = august))
        assertTrue(nav.back())

        assertEquals(Screen.DASHBOARD, nav.currentScreen)
        assertEquals(august, nav.args)
    }

    @Test
    fun `back walks the stack one entry at a time`() {
        val nav = NavigationState()

        nav.follow(Screen.REPORTS)
        nav.follow(Screen.TRANSACTIONS, NavigationArgs(categoryId = 1))
        assertEquals(Screen.TRANSACTIONS, nav.currentScreen)

        assertTrue(nav.back())
        assertEquals(Screen.REPORTS, nav.currentScreen)
        assertTrue(nav.back())
        assertEquals(Screen.DASHBOARD, nav.currentScreen)
        assertFalse(nav.back())
    }

    @Test
    fun `a direct navigation starts a new trail`() {
        val nav = NavigationState()

        nav.follow(Screen.REPORTS)
        nav.follow(Screen.TRANSACTIONS)
        nav.navigateTo(Screen.ACCOUNTS)
        assertNull(nav.backTarget)
    }

    @Test
    fun `a locked screen neither offers nor accepts back`() {
        val nav = NavigationState()
        nav.navigateTo(Screen.DASHBOARD)
        nav.follow(Screen.PAYMENTS) // the link right before the lock applies
        nav.lock(Screen.PAYMENTS) // an order is being sent

        nav.follow(Screen.DASHBOARD)
        assertEquals(Screen.PAYMENTS, nav.currentScreen)
        assertEquals(NavigationArgs(), nav.args, "a refused navigation must not leave its args behind")

        assertFalse(nav.back(), "back must refuse while the screen opening last is locked")
        assertEquals(Screen.PAYMENTS, nav.currentScreen, "a refused back changes nothing")

        nav.unlock(Screen.PAYMENTS)
        assertTrue(nav.back())
        assertEquals(Screen.DASHBOARD, nav.currentScreen)
    }

    @Test
    fun `a refused follow pushes nothing`() {
        val nav = NavigationState()
        nav.navigateTo(Screen.PAYMENTS)
        nav.lock(Screen.PAYMENTS)

        nav.follow(Screen.DASHBOARD)
        assertEquals(Screen.PAYMENTS, nav.currentScreen)
        assertEquals(NavigationArgs(), nav.args, "a refused navigation must not leave its args behind")
        assertNull(nav.backTarget)
    }

    @Test
    fun `the back stack holds at most ten entries`() {
        val nav = NavigationState()

        repeat(12) {
            nav.follow(Screen.REPORTS)
            nav.follow(Screen.TRANSACTIONS, NavigationArgs(categoryId = it + 1))
        }
        repeat(10) { assertTrue(nav.back(), "the ${it + 1}. back must work") }
        assertFalse(nav.back(), "the 11th back must refuse: the stack was capped at 10")
        assertNull(nav.backTarget)
    }

    @Test
    fun `a link to the screen already shown pushes nothing`() {
        val nav = NavigationState()

        nav.follow(Screen.DASHBOARD, NavigationArgs(month = YearMonth.of(2026, 8)))
        assertEquals(Screen.DASHBOARD, nav.currentScreen)
        assertNull(nav.backTarget)
    }
}
