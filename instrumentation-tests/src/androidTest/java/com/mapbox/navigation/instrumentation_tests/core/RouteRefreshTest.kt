package com.mapbox.navigation.instrumentation_tests.core

import android.location.Location
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.options.RoutingTilesOptions
import com.mapbox.navigation.base.route.RouteRefreshOptions
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.instrumentation_tests.R
import com.mapbox.navigation.instrumentation_tests.activity.EmptyTestActivity
import com.mapbox.navigation.instrumentation_tests.utils.MapboxNavigationRule
import com.mapbox.navigation.instrumentation_tests.utils.http.MockAvailableTilesVersionsRequestHandler
import com.mapbox.navigation.instrumentation_tests.utils.http.MockDirectionsRefreshHandler
import com.mapbox.navigation.instrumentation_tests.utils.http.MockDirectionsRequestHandler
import com.mapbox.navigation.instrumentation_tests.utils.idling.IdlingPolicyTimeoutRule
import com.mapbox.navigation.instrumentation_tests.utils.idling.RouteRequestIdlingResource
import com.mapbox.navigation.instrumentation_tests.utils.idling.RoutesObserverIdlingResource
import com.mapbox.navigation.instrumentation_tests.utils.location.MockLocationReplayerRule
import com.mapbox.navigation.instrumentation_tests.utils.readRawFileText
import com.mapbox.navigation.instrumentation_tests.utils.runOnMainSync
import com.mapbox.navigation.testing.ui.BaseTest
import com.mapbox.navigation.testing.ui.utils.getMapboxAccessTokenFromResources
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

class RouteRefreshTest : BaseTest<EmptyTestActivity>(EmptyTestActivity::class.java) {

    @get:Rule
    val mapboxNavigationRule = MapboxNavigationRule()

    @get:Rule
    val mockLocationReplayerRule = MockLocationReplayerRule(mockLocationUpdatesRule)

    @get:Rule
    val idlingPolicyRule = IdlingPolicyTimeoutRule(35, TimeUnit.SECONDS)

    private lateinit var mapboxNavigation: MapboxNavigation
    private val coordinates = listOf(
        Point.fromLngLat(-121.495975, 38.57774),
        Point.fromLngLat(-121.480279, 38.57674)
    )

    override fun setupMockLocation(): Location = mockLocationUpdatesRule.generateLocationUpdate {
        latitude = coordinates[0].latitude()
        longitude = coordinates[0].longitude()
    }

    @Before
    fun setup() {
        setupMockRequestHandlers(coordinates)

        mapboxNavigation = MapboxNavigationProvider.create(
            NavigationOptions.Builder(activity)
                .accessToken(getMapboxAccessTokenFromResources(activity))
                .routeRefreshOptions(
                    RouteRefreshOptions.Builder()
                        .intervalMillis(TimeUnit.SECONDS.toMillis(30))
                        .build()
                )
                .routingTilesOptions(
                    RoutingTilesOptions.Builder()
                        .tilesBaseUri(URI(mockWebServerRule.baseUrl))
                        .build()
                )
                .build()
        )
    }

    @Test
    fun expect_route_refresh_to_update_traffic_annotations() {
        // Request a route.
        val routes = requestDirectionsRouteSync(coordinates).reversed()

        // Create an observer resource that captures the routes.
        val initialRouteIdlingResource = RoutesObserverIdlingResource(mapboxNavigation)
            .register()

        // Set navigation with the route.
        runOnMainSync {
            mapboxNavigation.setRoutes(routes)
        }

        // Wait for the initial route.
        val initialRoutes = initialRouteIdlingResource.next()

        // Wait for the route refresh.
        val refreshedRoutes = initialRouteIdlingResource.next()
        initialRouteIdlingResource.unregister()

        // Only the annotations are refreshed. So sum up the duration from the old and new.
        val sanityLeg = routes.first().legs()!!
        val sanityDuration = sanityLeg.first().annotation()?.duration()?.sum()!!
        val initialLeg = initialRoutes.first().legs()?.first()!!
        val initialDuration = initialLeg.annotation()?.duration()?.sum()!!
        val refreshedLeg = refreshedRoutes.first().legs()?.first()!!
        val refreshedDuration = refreshedLeg.annotation()?.duration()?.sum()!!

        assertEquals(sanityDuration, initialDuration, 0.0)
        assertEquals(227.918, initialDuration, 0.0001)
        assertEquals(230.651, refreshedDuration, 0.0001)
    }

    // Will be ignored when .baseUrl(mockWebServerRule.baseUrl) is commented out
    // in the requestDirectionsRouteSync function.
    private fun setupMockRequestHandlers(coordinates: List<Point>) {
        mockWebServerRule.requestHandlers.add(
            MockDirectionsRequestHandler(
                "driving-traffic",
                readRawFileText(activity, R.raw.route_response_route_refresh),
                coordinates
            )
        )
        mockWebServerRule.requestHandlers.add(
            MockDirectionsRefreshHandler(
                "route_response_route_refresh",
                readRawFileText(activity, R.raw.route_response_route_refresh_annotations)
            )
        )
        mockWebServerRule.requestHandlers.add(
            MockAvailableTilesVersionsRequestHandler(
                readRawFileText(activity, R.raw.nn_response_available_versions)
            )
        )
    }

    private fun requestDirectionsRouteSync(coordinates: List<Point>): List<DirectionsRoute> {
        val routeOptions = RouteOptions.builder().applyDefaultNavigationOptions()
            .profile(DirectionsCriteria.PROFILE_DRIVING_TRAFFIC)
            .alternatives(true)
            .coordinatesList(coordinates)
            .baseUrl(mockWebServerRule.baseUrl) // Comment out to test a real server
            .build()
        val routeRequestIdlingResource = RouteRequestIdlingResource(mapboxNavigation, routeOptions)
        return routeRequestIdlingResource.requestRoutesSync()
    }
}
