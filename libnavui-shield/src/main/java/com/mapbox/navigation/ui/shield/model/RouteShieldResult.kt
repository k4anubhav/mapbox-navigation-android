package com.mapbox.navigation.ui.shield.model

/**
 * Data structure that wraps the information contained in [RouteShield] and [RouteShieldOrigin]
 * and is used to render the shield.
 *
 * @property shield [RouteShield]
 * @property origin [RouteShieldOrigin]
 */
data class RouteShieldResult(
    val shield: RouteShield,
    val origin: RouteShieldOrigin
)
