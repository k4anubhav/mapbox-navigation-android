package com.mapbox.navigation.ui.shield.model

/**
 * Data structure that holds information about the original shield request that was made.
 *
 * @property isFallback is set to false if the original shield request was successful, false otherwise,
 * @property originalUrl is set to the original url used to make shield request
 * @property originalErrorMessage is empty if the original shield request was successful, otherwise
 * contains error pointing to the reason behind the failure of original shield request.
 */
data class RouteShieldOrigin(
    val isFallback: Boolean,
    val originalUrl: String?,
    val originalErrorMessage: String
)
