package com.mapbox.navigation.examples.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibrationEffect.DEFAULT_AMPLITUDE
import android.os.Vibrator
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapLoadError
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.Style.Companion.MAPBOX_STREETS
import com.mapbox.maps.plugin.animation.CameraAnimationsPlugin
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.getCameraAnimationsPlugin
import com.mapbox.maps.plugin.delegates.listeners.OnMapLoadErrorListener
import com.mapbox.maps.plugin.gestures.GesturesPlugin
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.getGesturesPlugin
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import com.mapbox.maps.plugin.locationcomponent.getLocationComponentPlugin
import com.mapbox.navigation.base.internal.extensions.applyDefaultParams
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.replay.MapboxReplayer
import com.mapbox.navigation.core.replay.ReplayLocationEngine
import com.mapbox.navigation.core.replay.route.ReplayProgressObserver
import com.mapbox.navigation.core.replay.route.ReplayRouteMapper
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.examples.util.Slackline
import com.mapbox.navigation.examples.util.Utils.getMapboxAccessToken
import com.mapbox.navigation.ui.base.api.snapshotter.SnapshotReadyCallback
import com.mapbox.navigation.ui.base.api.snapshotter.SnapshotterApi
import com.mapbox.navigation.ui.base.model.snapshotter.SnapshotState
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.snapshotter.api.MapboxSnapshotterApi
import com.mapbox.navigation.ui.maps.snapshotter.model.MapboxSnapshotterOptions
import com.mapbox.navigation.ui.utils.internal.ifNonNull
import kotlinx.android.synthetic.main.layout_activity_signboard.mapView
import kotlinx.android.synthetic.main.layout_activity_snapshot.*
import java.util.Objects

class MapboxSnapshotActivity : AppCompatActivity(), OnMapLongClickListener {

    private lateinit var mapboxMap: MapboxMap
    private lateinit var mapCamera: CameraAnimationsPlugin
    private lateinit var mapboxNavigation: MapboxNavigation
    private lateinit var snapshotApi: SnapshotterApi
    private val navigationLocationProvider = NavigationLocationProvider()
    private lateinit var locationComponent: LocationComponentPlugin

    private val mapboxReplayer = MapboxReplayer()
    private val replayRouteMapper = ReplayRouteMapper()
    private val slackLine = Slackline(this)
    private val replayProgressObserver = ReplayProgressObserver(mapboxReplayer)

    private val snapshotCallback: SnapshotReadyCallback = object : SnapshotReadyCallback {
        override fun onSnapshotReady(bitmap: SnapshotState.SnapshotReady) {
            snapshotView.render(bitmap)
        }

        override fun onFailure(error: SnapshotState.SnapshotFailure) {
            snapshotView.render(error)
        }
    }

    private val locationObserver = object : LocationObserver {
        override fun onRawLocationChanged(rawLocation: Location) {}
        override fun onEnhancedLocationChanged(
            enhancedLocation: Location,
            keyPoints: List<Location>
        ) {
            navigationLocationProvider.changePosition(
                enhancedLocation,
                keyPoints,
            )
            updateCamera(enhancedLocation)
        }
    }

    private val routesObserver = object : RoutesObserver {
        override fun onRoutesChanged(routes: List<DirectionsRoute>) {
            if (routes.isNotEmpty()) {
                startSimulation(routes[0])
            }
        }
    }

    private val routeProgressObserver = object : RouteProgressObserver {
        override fun onRouteProgressChanged(routeProgress: RouteProgress) {
            snapshotApi.generateSnapshot(routeProgress, snapshotCallback)
        }
    }

    private fun startSimulation(route: DirectionsRoute) {
        mapboxReplayer.stop()
        mapboxReplayer.clearEvents()
        mapboxReplayer.pushRealLocation(this, 0.0)
        val replayEvents = replayRouteMapper.mapDirectionsRouteGeometry(route)
        mapboxReplayer.pushEvents(replayEvents)
        mapboxReplayer.seekTo(replayEvents.first())
        mapboxReplayer.play()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_activity_snapshot)
        mapboxMap = mapView.getMapboxMap()
        locationComponent = mapView.getLocationComponentPlugin().apply {
            setLocationProvider(navigationLocationProvider)
            enabled = true
        }
        mapCamera = getMapCamera()
        init()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        if (::mapboxNavigation.isInitialized) {
            mapboxNavigation.registerRoutesObserver(routesObserver)
            mapboxNavigation.registerLocationObserver(locationObserver)
            mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.registerRouteProgressObserver(replayProgressObserver)
        }
    }

    override fun onStop() {
        super.onStop()
        if (::mapboxNavigation.isInitialized) {
            mapboxNavigation.unregisterRoutesObserver(routesObserver)
            mapboxNavigation.unregisterLocationObserver(locationObserver)
            mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.unregisterRouteProgressObserver(replayProgressObserver)
        }
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        mapboxNavigation.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onMapLongClick(point: Point): Boolean {
        vibrate()
        ifNonNull(navigationLocationProvider.lastLocation) {
            val or = Point.fromLngLat(-3.5870, 40.5719)
            val de = Point.fromLngLat(-3.607835, 40.551486)
            findRoute(or, de)
        }
        return false
    }

    private fun init() {
        initNavigation()
        initStyle()
        slackLine.initialize(mapView, mapboxNavigation)
    }

    @SuppressLint("MissingPermission")
    private fun initNavigation() {
        mapboxNavigation = MapboxNavigation(
            NavigationOptions.Builder(this)
                .accessToken(getMapboxAccessTokenFromResources())
                .locationEngine(ReplayLocationEngine(mapboxReplayer))
                .build()
        )
        mapboxNavigation.startTripSession()
        val density = resources.displayMetrics.density
        val options = MapboxSnapshotterOptions.Builder(applicationContext)
            .edgeInsets(EdgeInsets(250.0 * density, 0.0 * density, 0.0 * density, 0.0 * density))
            .styleUri("mapbox://styles/mapbox-map-design/ckkfnaak605mv17pgmmxgmlvd")
            .build()
        snapshotApi = MapboxSnapshotterApi(this, mapboxMap, options, mapView)
        mapboxReplayer.pushRealLocation(this, 0.0)
        mapboxReplayer.play()
    }

    @SuppressLint("MissingPermission")
    private fun initStyle() {
        mapboxMap.loadStyleUri(
            MAPBOX_STREETS,
            { style: Style ->
                getGesturePlugin().addOnMapLongClickListener(this)
            },
            object : OnMapLoadErrorListener {
                override fun onMapLoadError(mapViewLoadError: MapLoadError, msg: String) {
                }
            }
        )
    }

    private fun findRoute(origin: Point, destination: Point) {
        mapboxNavigation.requestRoutes(
            RouteOptions.builder()
                .applyDefaultParams()
                .accessToken(Objects.requireNonNull(getMapboxRouteAccessToken(this)))
                .coordinates(listOf(origin, destination))
                .alternatives(true)
                .build()
        )
    }

    @SuppressLint("MissingPermission")
    private fun vibrate() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100L, DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(100L)
        }
    }

    private fun getMapCamera(): CameraAnimationsPlugin {
        return mapView.getCameraAnimationsPlugin()
    }

    private fun getGesturePlugin(): GesturesPlugin {
        return mapView.getGesturesPlugin()
    }

    private fun getMapboxAccessTokenFromResources(): String? {
        return getMapboxAccessToken(this)
    }

    private fun updateCamera(location: Location) {
        val mapAnimationOptionsBuilder = MapAnimationOptions.Builder()
        mapAnimationOptionsBuilder.duration = 1500L
        mapCamera.easeTo(
            CameraOptions.Builder()
                .center(Point.fromLngLat(location.longitude, location.latitude))
                .bearing(location.bearing.toDouble())
                .pitch(45.0)
                .zoom(17.0)
                .padding(EdgeInsets(1000.0, 0.0, 0.0, 0.0))
                .build(),
            mapAnimationOptionsBuilder.build()
        )
    }

    /**
     * Returns the Mapbox access token set in the app resources.
     *
     * @param context The [Context] of the [android.app.Activity] or [android.app.Fragment].
     * @return The Mapbox access token or null if not found.
     */
    private fun getMapboxRouteAccessToken(context: Context): String {
        val tokenResId = context.resources
            .getIdentifier("mapbox_route_token", "string", context.packageName)
        return if (tokenResId != 0) {
            context.getString(tokenResId)
        } else {
            throw RuntimeException("mapbox_route_token needed (see code comments for details)")
        }
    }
}