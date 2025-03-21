package com.mapbox.navigation.examples.core

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.Style
import com.mapbox.maps.extension.observable.eventdata.MapLoadingErrorEventData
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.delegates.listeners.OnMapLoadErrorListener
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.replay.MapboxReplayer
import com.mapbox.navigation.core.replay.history.ReplayEventBase
import com.mapbox.navigation.core.replay.history.ReplaySetRoute
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.examples.core.databinding.ActivityReplayHistoryLayoutBinding
import com.mapbox.navigation.examples.core.replay.HistoryFileLoader
import com.mapbox.navigation.examples.core.replay.HistoryFilesActivity
import com.mapbox.navigation.examples.util.Utils
import com.mapbox.navigation.ui.maps.NavigationStyles
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLine
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections

class ReplayHistoryActivity : AppCompatActivity() {

    private var loadNavigationJob: Job? = null
    private val navigationLocationProvider = NavigationLocationProvider()
    private lateinit var historyFileLoader: HistoryFileLoader
    private lateinit var mapboxNavigation: MapboxNavigation
    private lateinit var mapboxReplayer: MapboxReplayer
    private lateinit var locationComponent: LocationComponentPlugin
    private lateinit var navigationCamera: NavigationCamera
    private lateinit var viewportDataSource: MapboxNavigationViewportDataSource
    private lateinit var binding: ActivityReplayHistoryLayoutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplayHistoryLayoutBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        initNavigation()
        handleHistoryFileSelected()
        initMapStyle()

        findViewById<Button>(R.id.selectHistoryButton).setOnClickListener {
            val activityIntent = Intent(this, HistoryFilesActivity::class.java)
                .putExtra(
                    HistoryFilesActivity.EXTRA_HISTORY_FILE_DIRECTORY,
                    mapboxNavigation.historyRecorder.fileDirectory()
                )
            startActivityForResult(activityIntent, HistoryFilesActivity.REQUEST_CODE)
        }
        setupReplayControls()
    }

    override fun onStart() {
        super.onStart()

        if (::mapboxNavigation.isInitialized) {
            mapboxNavigation.registerLocationObserver(locationObserver)
            mapboxNavigation.registerRoutesObserver(routesObserver)
            mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
        }
    }

    override fun onStop() {
        super.onStop()
        if (::mapboxNavigation.isInitialized) {
            mapboxNavigation.unregisterLocationObserver(locationObserver)
            mapboxNavigation.unregisterRoutesObserver(routesObserver)
            mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        routeLineApi.cancel()
        routeLineView.cancel()
        mapboxReplayer.finish()
        mapboxNavigation.onDestroy()
        if (::locationComponent.isInitialized) {
            locationComponent.removeOnIndicatorPositionChangedListener(onPositionChangedListener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun initMapStyle() {
        viewportDataSource = MapboxNavigationViewportDataSource(
            binding.mapView.getMapboxMap()
        )
        binding.mapView.getMapboxMap().loadStyleUri(
            NavigationStyles.NAVIGATION_DAY_STYLE,
            { style: Style ->
                locationComponent = binding.mapView.location.apply {
                    this.locationPuck = LocationPuck2D(
                        bearingImage = ContextCompat.getDrawable(
                            this@ReplayHistoryActivity,
                            R.drawable.mapbox_navigation_puck_icon
                        )
                    )
                    setLocationProvider(navigationLocationProvider)
                    enabled = true
                }
                locationComponent.addOnIndicatorPositionChangedListener(onPositionChangedListener)
                navigationCamera = NavigationCamera(
                    binding.mapView.getMapboxMap(),
                    binding.mapView.camera,
                    viewportDataSource
                )

                viewportDataSource.evaluate()
            },
            object : OnMapLoadErrorListener {
                override fun onMapLoadError(eventData: MapLoadingErrorEventData) {
                    // intentionally blank
                }
            }
        )
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) {}
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            navigationLocationProvider.changePosition(
                locationMatcherResult.enhancedLocation,
                locationMatcherResult.keyPoints,
            )
            updateCamera(locationMatcherResult.enhancedLocation)
        }
    }

    private fun updateCamera(location: Location) {
        val mapAnimationOptionsBuilder = MapAnimationOptions.Builder()
            .duration(1500L)
        binding.mapView.camera.easeTo(
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

    /** Rendering the set route event **/

    private val options: MapboxRouteLineOptions by lazy {
        MapboxRouteLineOptions.Builder(this)
            .withRouteLineResources(
                RouteLineResources.Builder()
                    .routeLineColorResources(
                        RouteLineColorResources.Builder().build()
                    )
                    .build()
            )
            .withRouteLineBelowLayerId("road-label-navigation")
            .withVanishingRouteLineEnabled(true)
            .build()
    }

    private val routeLineView by lazy {
        MapboxRouteLineView(options)
    }

    private val routeLineApi: MapboxRouteLineApi by lazy {
        MapboxRouteLineApi(options)
    }

    private val routeArrowApi: MapboxRouteArrowApi by lazy {
        MapboxRouteArrowApi()
    }

    private val routeArrowView: MapboxRouteArrowView by lazy {
        MapboxRouteArrowView(
            RouteArrowOptions.Builder(this)
                .withAboveLayerId(RouteLayerConstants.TOP_LEVEL_ROUTE_LINE_LAYER_ID)
                .build()
        )
    }

    private val routesObserver: RoutesObserver = RoutesObserver { result ->
        val routeLines = result.routes.map { RouteLine(it, null) }
        routeLineApi.setRoutes(
            routeLines
        ) { value ->
            binding.mapView.getMapboxMap().getStyle()?.apply {
                routeLineView.renderRouteDrawData(this, value)
            }
        }
    }

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        routeLineApi.updateWithRouteProgress(routeProgress) { result ->
            binding.mapView.getMapboxMap().getStyle()?.apply {
                routeLineView.renderRouteLineUpdate(this, result)
            }
        }
        val arrowUpdate = routeArrowApi.addUpcomingManeuverArrow(routeProgress)
        binding.mapView.getMapboxMap().getStyle()?.apply {
            routeArrowView.renderManeuverUpdate(this, arrowUpdate)
        }
    }

    private val onPositionChangedListener = OnIndicatorPositionChangedListener { point ->
        val result = routeLineApi.updateTraveledRouteLine(point)
        binding.mapView.getMapboxMap().getStyle()?.apply {
            // Render the result to update the map.
            routeLineView.renderRouteLineUpdate(this, result)
        }
    }

    @SuppressLint("MissingPermission")
    private fun initNavigation() {
        historyFileLoader = HistoryFileLoader()
        mapboxNavigation = MapboxNavigationProvider.create(
            NavigationOptions.Builder(this)
                .accessToken(Utils.getMapboxAccessToken(this))
                .build()
        )
        startReplayTripSession()
    }

    /**
     * This is showcasing a new way to replay rides at runtime.
     */
    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    private fun startReplayTripSession() {
        mapboxReplayer = mapboxNavigation.mapboxReplayer
        mapboxNavigation.startReplayTripSession()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == HistoryFilesActivity.REQUEST_CODE) {
            handleHistoryFileSelected()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleHistoryFileSelected() {
        loadNavigationJob = CoroutineScope(Dispatchers.Main).launch {
            val events = historyFileLoader
                .loadReplayHistory(this@ReplayHistoryActivity)
            mapboxReplayer.clearEvents()
            mapboxReplayer.pushEvents(events)
            binding.playReplay.visibility = View.VISIBLE
            mapboxNavigation.resetTripSession()
            mapboxReplayer.playFirstLocation()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateReplayStatus(playbackEvents: List<ReplayEventBase>) {
        playbackEvents.lastOrNull()?.eventTimestamp?.let {
            val currentSecond = mapboxReplayer.eventSeconds(it).toInt()
            val durationSecond = mapboxReplayer.durationSeconds().toInt()
            binding.playerStatus.text = "$currentSecond:$durationSecond"
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupReplayControls() {
        binding.seekBar.max = 8
        binding.seekBar.progress = 1
        binding.seekBarText.text = getString(
            R.string.replay_playback_speed_seekbar,
            binding.seekBar.progress
        )
        binding.seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    mapboxReplayer.playbackSpeed(progress.toDouble())
                    binding.seekBarText.text = getString(
                        R.string.replay_playback_speed_seekbar,
                        progress
                    )
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            }
        )

        binding.playReplay.setOnClickListener {
            mapboxReplayer.play()
            binding.playReplay.visibility = View.GONE
        }

        mapboxReplayer.registerObserver { events ->
            updateReplayStatus(events)
            events.forEach {
                when (it) {
                    is ReplaySetRoute -> setRoute(it)
                }
            }
        }
    }

    private fun setRoute(replaySetRoute: ReplaySetRoute) {
        replaySetRoute.route?.let { directionRoute ->
            mapboxNavigation.setRoutes(Collections.singletonList(directionRoute))
        }
    }
}
