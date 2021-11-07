package com.mapbox.navigation.ui.shield.model

/**
 * Data structure that holds information about errors in downloading route shields.
 * @property url that was downloaded and resulted in an error
 * @property errorMessage explains the reason for failure to download the shield.
 */
class RouteShieldError constructor(
    val url: String,
    val errorMessage: String
)
