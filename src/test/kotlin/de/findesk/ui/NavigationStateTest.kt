package de.findesk.ui

import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.NavigationState
import de.findesk.ui.navigation.Screen
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
}
