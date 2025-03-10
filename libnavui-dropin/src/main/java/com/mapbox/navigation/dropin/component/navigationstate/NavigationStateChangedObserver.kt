package com.mapbox.navigation.dropin.component.navigationstate

/**
 * Observer that gets notified whenever [NavigationState] changes.
 */
internal interface NavigationStateChangedObserver {

    /**
     * Called whenever [NavigationState] changes.
     * @param state current states
     */
    fun onNavigationStateChanged(state: NavigationState)
}
