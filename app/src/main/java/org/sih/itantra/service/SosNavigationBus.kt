package org.sih.itantra.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Set by [MeshForegroundService]/[org.sih.itantra.MainActivity] when the user taps the SOS
 * notification. `AppNavigation` observes this and jumps straight to the Emergency tab, then
 * resets it - a lightweight deep-link bus that avoids threading extra params through every screen.
 */
object SosNavigationBus {
    val pendingOpenEmergency = MutableStateFlow(false)
}
