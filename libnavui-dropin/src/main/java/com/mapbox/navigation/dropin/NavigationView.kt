package com.mapbox.navigation.dropin

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.bindgen.Expected
import com.mapbox.geojson.Point
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.delegates.listeners.OnStyleLoadedListener
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.trip.session.BannerInstructionsObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.TripSessionStateObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.dropin.contract.ContainerVisibilityAction
import com.mapbox.navigation.dropin.contract.NavigationStateAction
import com.mapbox.navigation.dropin.databinding.MapboxLayoutDropInViewBinding
import com.mapbox.navigation.dropin.state.ContainerVisibilityState
import com.mapbox.navigation.dropin.util.MapboxDropInUtils
import com.mapbox.navigation.dropin.viewmodel.CameraViewModel
import com.mapbox.navigation.dropin.viewmodel.ContainerVisibilityViewModel
import com.mapbox.navigation.dropin.viewmodel.MapboxNavigationViewModel
import com.mapbox.navigation.dropin.viewmodel.NavigationStateViewModel
import com.mapbox.navigation.dropin.viewmodel.RouteArrowViewModel
import com.mapbox.navigation.dropin.viewmodel.RouteLineViewModel
import com.mapbox.navigation.ui.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.maps.camera.view.MapboxRecenterButton
import com.mapbox.navigation.ui.maps.camera.view.MapboxRouteOverviewButton
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.speedlimit.view.MapboxSpeedLimitView
import com.mapbox.navigation.ui.tripprogress.view.MapboxTripProgressView
import com.mapbox.navigation.ui.voice.view.MapboxSoundButton
import com.mapbox.navigation.utils.internal.ifNonNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.lang.IllegalStateException
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

@ExperimentalCoroutinesApi
class NavigationView : ConstraintLayout {

    val navigationViewApi: MapboxNavigationViewApi by lazy {
        MapboxNavigationViewApiImpl(this@NavigationView)
    }

    private val activity: FragmentActivity by lazy {
        try {
            context as FragmentActivity
        } catch (exception: ClassCastException) {
            throw ClassCastException(
                "Please ensure that the provided Context is a valid FragmentActivity"
            )
        }
    }
    private val mapView: MapView by lazy {
        MapView(context, mapInitOptions).also {
            it.getMapboxMap().addOnStyleLoadedListener(onStyleLoadedListener)
        }
    }
    private val lifeCycleOwner: LifecycleOwner
    private val binding = MapboxLayoutDropInViewBinding.inflate(
        LayoutInflater.from(context),
        this
    )

    @VisibleForTesting
    lateinit var navigationViewOptions: NavigationViewOptions
        private set

    private lateinit var accessToken: String
    private lateinit var maneuverView: View
    private lateinit var speedLimitView: View
    private lateinit var soundButtonView: View
    private lateinit var tripProgressView: View
    private lateinit var recenterButtonView: View
    private lateinit var routeOverviewButtonView: View
    private val mapInitOptions: MapInitOptions
    private val navigationLocationProvider = NavigationLocationProvider()
    @VisibleForTesting
    internal val externalRouteProgressObservers = CopyOnWriteArraySet<RouteProgressObserver>()
    @VisibleForTesting
    internal val externalLocationObservers = CopyOnWriteArraySet<LocationObserver>()
    @VisibleForTesting
    internal val externalRoutesObservers = CopyOnWriteArraySet<RoutesObserver>()
    @VisibleForTesting
    internal val externalArrivalObservers = CopyOnWriteArraySet<ArrivalObserver>()
    @VisibleForTesting
    internal val externalBannerInstructionObservers =
        CopyOnWriteArraySet<BannerInstructionsObserver>()
    @VisibleForTesting
    internal val externalTripSessionStateObservers = CopyOnWriteArraySet<TripSessionStateObserver>()
    @VisibleForTesting
    internal val externalVoiceInstructionsObservers =
        CopyOnWriteArraySet<VoiceInstructionsObserver>()

    private val mapboxNavigationViewModel: MapboxNavigationViewModel by lazy {
        ViewModelProvider(
            activity,
            MapboxNavigationViewModelFactory(
                DropInUIMapboxNavigationFactory(
                    this.activity.applicationContext,
                    this.accessToken
                )
            )
        )[MapboxNavigationViewModel::class.java]
    }

    private val navigationStateViewModel: NavigationStateViewModel by lazy {
        ViewModelProvider(activity)[NavigationStateViewModel::class.java]
    }

    private val containerVisibilityViewModel: ContainerVisibilityViewModel by lazy {
        ViewModelProvider(activity)[ContainerVisibilityViewModel::class.java]
    }

    private val cameraViewModel: CameraViewModel by lazy {
        ViewModelProvider(activity)[CameraViewModel::class.java]
    }

    private val routeLineViewModel: RouteLineViewModel by lazy {
        ViewModelProvider(
            activity,
            RouteLineViewModelFactory(navigationViewOptions.mapboxRouteLineOptions)
        )[RouteLineViewModel::class.java]
    }

    private val routeArrowViewModel: RouteArrowViewModel by lazy {
        ViewModelProvider(
            activity,
            RouteArrowViewModelFactory(navigationViewOptions.routeArrowOptions)
        )[RouteArrowViewModel::class.java]
    }

    constructor(context: Context, attrs: AttributeSet?) : this(
        context,
        attrs,
        null,
        MapInitOptions(context),
        NavigationViewOptions.Builder(context).build()
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        accessToken: String?,
        mapInitializationOptions: MapInitOptions = MapInitOptions(context),
        navigationViewOptions: NavigationViewOptions = NavigationViewOptions
            .Builder(context)
            .build()
    ) : super(context, attrs) {
        this.mapInitOptions = mapInitializationOptions
        if (accessToken != null) {
            this.accessToken = accessToken
        } else {
            val attrsAccessToken = context.obtainStyledAttributes(
                attrs,
                R.styleable.NavigationView,
                0,
                0
            ).use {
                it.getString(R.styleable.NavigationView_accessToken)
            }
            ifNonNull(attrsAccessToken) { token ->
                this.accessToken = token
            } ?: throw IllegalStateException(
                "Access token must be provided through xml attributes or constructor injection."
            )
        }
        this.navigationViewOptions = navigationViewOptions
        this.lifeCycleOwner = context as? LifecycleOwner ?: throw LifeCycleOwnerNotFoundException()
        this.lifeCycleOwner.lifecycle.addObserver(lifecycleObserver)
        this.lifeCycleOwner.lifecycle.addObserver(mapboxNavigationViewModel)
    }

    /**
     * Creates references to view components. Views provided via the [viewProvider] will be given preference.
     * If null, default Mapbox designed views will be used.
     */
    internal fun configure(viewProvider: ViewProvider) {
        // add views
        binding.mapContainer.addView(mapView)
        maneuverView = viewProvider.maneuverProvider?.invoke() ?: MapboxManeuverView(context)
        binding.maneuverContainer.addView(maneuverView)
        speedLimitView = viewProvider.speedLimitProvider?.invoke() ?: MapboxSpeedLimitView(context)
        binding.speedLimitContainer.addView(speedLimitView)
        tripProgressView =
            viewProvider.tripProgressProvider?.invoke() ?: MapboxTripProgressView(context)
        binding.speedLimitContainer.addView(tripProgressView)
        soundButtonView = viewProvider.soundButtonProvider?.invoke() ?: MapboxSoundButton(context)
        binding.volumeContainer.addView(soundButtonView)
        recenterButtonView =
            viewProvider.recenterButtonProvider?.invoke() ?: MapboxRecenterButton(context)
        binding.recenterContainer.addView(recenterButtonView)
        routeOverviewButtonView =
            viewProvider.routeOverviewButtonProvider?.invoke() ?: MapboxRouteOverviewButton(context)
        binding.routeOverviewContainer.addView(routeOverviewButtonView)

        renderStateMutations()
        performActions()

        updateNavigationViewOptions(navigationViewOptions)

        // This was added to facilitate getting a route into mapbox navigation so work could go forward.
        // It may be temporary.
        mapView.gestures.addOnMapLongClickListener(onMapLongClickListener)
    }

    internal fun updateNavigationViewOptions(navigationViewOptions: NavigationViewOptions) {
        this.navigationViewOptions = navigationViewOptions
        // todo trigger relayout
    }

    internal fun retrieveMapView(): MapView = mapView

    private fun performActions() {
        lifeCycleOwner.lifecycleScope.launch {
            navigationStateViewModel.processAction(
                flowOf(
                    NavigationStateAction.ToEmpty
                )
            )
            containerVisibilityViewModel.processAction(
                flowOf(
                    ContainerVisibilityAction.ForEmpty
                )
            )
        }
    }

    private fun renderStateMutations() {
        lifeCycleOwner.lifecycleScope.launch {
            navigationStateViewModel.navigationState().collect { state ->
            }
        }
        lifeCycleOwner.lifecycleScope.launch {
            containerVisibilityViewModel.containerVisibilityState().collect { state ->
                renderVisibility(state)
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            cameraViewModel.cameraUpdates.collect {
                mapView.camera.easeTo(it.first, it.second)
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.rawLocationUpdates().collect { locationUpdate ->
                // view models that need location updates should be added here
                cameraViewModel.consumeLocationUpdate(locationUpdate)

                externalLocationObservers.forEach {
                    lifeCycleOwner.lifecycleScope.launch {
                        it.onNewRawLocation(locationUpdate)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.newLocationMatcherResults.collect { locationMatcherResult ->
                // view models that need LocationMatcherResults should be added here
                val transitionOptions = getTransitionOptions(locationMatcherResult)
                navigationLocationProvider.changePosition(
                    locationMatcherResult.enhancedLocation,
                    locationMatcherResult.keyPoints,
                    latLngTransitionOptions = transitionOptions,
                    bearingTransitionOptions = transitionOptions
                )

                externalLocationObservers.forEach {
                    lifeCycleOwner.lifecycleScope.launch {
                        it.onNewLocationMatcherResult(locationMatcherResult)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.routeProgressUpdates.collect { routeProgress ->
                // view models that need route progress updates should be added here
                mapView.getMapboxMap().getStyle()?.let { style ->
                    routeLineViewModel.routeProgressUpdated(routeProgress, style)
                    routeArrowViewModel.routeProgressUpdated(routeProgress, style)
                }
                externalRouteProgressObservers.forEach {
                    lifeCycleOwner.lifecycleScope.launch {
                        it.onRouteProgressChanged(routeProgress)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.routesUpdatedResults.collect { routesUpdatedResult ->
                // view models that need route progress updates should be added here
                mapView.getMapboxMap().getStyle()?.let { style ->
                    routeLineViewModel.routesUpdated(routesUpdatedResult, style)
                }
                externalRoutesObservers.forEach {
                    lifeCycleOwner.lifecycleScope.launch {
                        it.onRoutesChanged(routesUpdatedResult)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            // view models that need route progress updates should be added here
            mapboxNavigationViewModel.finalDestinationArrivals.collect { routeProgress ->
                externalArrivalObservers.forEach {
                    lifeCycleOwner.lifecycleScope.launch {
                        it.onFinalDestinationArrival(routeProgress)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.nextRouteLegStartUpdates.collect { routeLegProgress ->
                // view models that need route progress updates should be added here
                externalArrivalObservers.forEach {
                    lifeCycleOwner.lifecycleScope.launch {
                        it.onNextRouteLegStart(routeLegProgress)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.wayPointArrivals.collect { routeProgress ->
                // view models that need route progress updates should be added here
                externalArrivalObservers.forEach {
                    lifeCycleOwner.lifecycleScope.launch {
                        it.onWaypointArrival(routeProgress)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.bannerInstructions.collect { bannerInstructions ->
                // view models that need route progress updates should be added here
                externalBannerInstructionObservers.forEach {
                    lifeCycleOwner.lifecycleScope.launch {
                        it.onNewBannerInstructions(bannerInstructions)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.tripSessionStateUpdates.collect { tripSessionState ->
                // view models that need route progress updates should be added here

                lifeCycleOwner.lifecycleScope.launch {
                    externalTripSessionStateObservers.forEach {
                        it.onSessionStateChanged(tripSessionState)
                    }
                }
            }
        }

        lifeCycleOwner.lifecycleScope.launch {
            mapboxNavigationViewModel.voiceInstructions.collect { voiceInstructions ->
                // view models that need voice instruction updates should be added here
                lifeCycleOwner.lifecycleScope.launch {
                    externalVoiceInstructionsObservers.forEach {
                        it.onNewVoiceInstructions(voiceInstructions)
                    }
                }
            }
        }
    }

    private fun renderVisibility(state: ContainerVisibilityState) {
        binding.volumeContainer.visibility = state.volumeContainerVisible.toVisibility()
        binding.recenterContainer.visibility = state.recenterContainerVisible.toVisibility()
        binding.maneuverContainer.visibility = state.maneuverContainerVisible.toVisibility()
        binding.infoPanelContainer.visibility = state.infoPanelContainerVisible.toVisibility()
        binding.speedLimitContainer.visibility = state.speedLimitContainerVisible.toVisibility()
        binding.routeOverviewContainer.visibility =
            state.routeOverviewContainerVisible.toVisibility()
    }

    @VisibleForTesting
    internal val lifecycleObserver = object : DefaultLifecycleObserver {

        override fun onCreate(owner: LifecycleOwner) {
            super.onCreate(owner)
            mapView.location.apply {
                this.locationPuck = LocationPuck2D(
                    bearingImage = ContextCompat.getDrawable(
                        this@NavigationView.context,
                        R.drawable.mapbox_navigation_puck_icon
                    )
                )
                setLocationProvider(navigationLocationProvider)
                enabled = true
                this.addOnIndicatorPositionChangedListener(onPositionChangedListener)
            }
            MapboxDropInUtils.getLastLocation(
                this@NavigationView.context,
                WeakReference<(Expected<Exception, LocationEngineResult>) -> Unit> { result ->
                    result.fold(
                        {
                            Log.e(TAG, "Error obtaining current location", it)
                        },
                        {
                            it.lastLocation?.apply {
                                navigationLocationProvider.changePosition(
                                    this,
                                    listOf(),
                                    null,
                                    null
                                )
                                cameraViewModel.consumeLocationUpdate(this)
                            }
                        }
                    )
                }
            )
        }
    }

    internal fun addRouteProgressObserver(observer: RouteProgressObserver) {
        externalRouteProgressObservers.add(observer)
    }

    internal fun removeRouteProgressObserver(observer: RouteProgressObserver) {
        externalRouteProgressObservers.remove(observer)
    }

    internal fun addLocationObserver(observer: LocationObserver) {
        externalLocationObservers.add(observer)
    }

    internal fun removeLocationObserver(observer: LocationObserver) {
        externalLocationObservers.remove(observer)
    }

    internal fun addRoutesObserver(observer: RoutesObserver) {
        externalRoutesObservers.add(observer)
    }

    internal fun removeRoutesObserver(observer: RoutesObserver) {
        externalRoutesObservers.remove(observer)
    }

    internal fun addArrivalObserver(observer: ArrivalObserver) {
        externalArrivalObservers.add(observer)
    }

    internal fun removeArrivalObserver(observer: ArrivalObserver) {
        externalArrivalObservers.remove(observer)
    }

    internal fun addBannerInstructionsObserver(observer: BannerInstructionsObserver) {
        externalBannerInstructionObservers.add(observer)
    }

    internal fun removeBannerInstructionsObserver(observer: BannerInstructionsObserver) {
        externalBannerInstructionObservers.remove(observer)
    }

    internal fun addTripSessionStateObserver(observer: TripSessionStateObserver) {
        externalTripSessionStateObservers.add(observer)
    }

    internal fun removeTripSessionStateObserver(observer: TripSessionStateObserver) {
        externalTripSessionStateObservers.remove(observer)
    }

    internal fun addVoiceInstructionObserver(observer: VoiceInstructionsObserver) {
        externalVoiceInstructionsObservers.add(observer)
    }

    internal fun removeVoiceInstructionObserver(observer: VoiceInstructionsObserver) {
        externalVoiceInstructionsObservers.remove(observer)
    }

    internal val onPositionChangedListener = OnIndicatorPositionChangedListener { point ->
        mapView.getMapboxMap().getStyle()?.let { style ->
            routeLineViewModel.positionChanged(point, style)
        }
    }

    // this is temporary so that we can use the replay engine or otherwise start navigation
    // for further development.
    internal fun temporaryStartNavigation() {
        when (navigationViewOptions.useReplayEngine) {
            false -> mapboxNavigationViewModel.startTripSession()
            true -> {
                ifNonNull(navigationLocationProvider.lastLocation) { location ->
                    mapboxNavigationViewModel.startSimulatedTripSession(location)
                }
            }
        }
    }

    private fun getTransitionOptions(
        locationMatcherResult: LocationMatcherResult
    ): (ValueAnimator.() -> Unit) {
        return if (locationMatcherResult.isTeleport) {
            {
                duration = 0
            }
        } else {
            {
                duration = 1000
            }
        }
    }

    // This was added to facilitate getting a route into mapbox navigation so work could go forward.
    // It may be temporary.
    internal val onMapLongClickListener = OnMapLongClickListener { point ->
        val currentLocation = navigationLocationProvider.lastLocation
        if (currentLocation != null) {
            val originPoint = Point.fromLngLat(
                currentLocation.longitude,
                currentLocation.latitude
            )
            mapboxNavigationViewModel.findRoute(originPoint, point, this@NavigationView.context)
            true
        } else {
            false
        }
    }

    private val onStyleLoadedListener = OnStyleLoadedListener {
        mapView.getMapboxMap().getStyle { style ->
            routeLineViewModel.mapStyleUpdated(style)
        }
    }

    companion object {
        private val TAG = NavigationView::class.java.simpleName
    }
}

private fun Boolean.toVisibility() = if (this) {
    View.VISIBLE
} else {
    View.GONE
}
