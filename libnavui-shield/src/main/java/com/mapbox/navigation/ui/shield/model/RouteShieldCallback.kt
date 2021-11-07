package com.mapbox.navigation.ui.shield.model

import com.mapbox.bindgen.Expected

/**
 * An interface that is triggered when road shields are available.
 */
fun interface RouteShieldCallback {

    /**
     * The callback is invoked when road shields are ready.
     *
     * This returns a list of possible [RouteShieldResult] or [RouteShieldError] that can be used
     * to render the shield on a UI.
     *
     * @param shields list of [Expected] wither containing [RouteShieldError] or [RouteShieldResult]
     */
    fun onRoadShields(
        shields: List<Expected<RouteShieldError, RouteShieldResult>>
    )
}
