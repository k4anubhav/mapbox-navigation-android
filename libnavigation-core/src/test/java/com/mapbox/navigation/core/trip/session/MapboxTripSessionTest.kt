package com.mapbox.navigation.core.trip.session

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.base.common.logger.Logger
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.roadobject.UpcomingRoadObject
import com.mapbox.navigation.core.directions.session.RoutesExtra
import com.mapbox.navigation.core.navigator.RouteInitInfo
import com.mapbox.navigation.core.navigator.getLocationMatcherResult
import com.mapbox.navigation.core.navigator.getRouteInitInfo
import com.mapbox.navigation.core.navigator.getRouteProgressFrom
import com.mapbox.navigation.core.navigator.getTripStatusFrom
import com.mapbox.navigation.core.navigator.mapToDirectionsApi
import com.mapbox.navigation.core.navigator.toFixLocation
import com.mapbox.navigation.core.navigator.toLocation
import com.mapbox.navigation.core.navigator.toLocations
import com.mapbox.navigation.core.trip.NativeRouteProcessingListener
import com.mapbox.navigation.core.trip.service.TripService
import com.mapbox.navigation.core.trip.session.eh.EHorizonObserver
import com.mapbox.navigation.core.trip.session.eh.EHorizonSubscriptionManager
import com.mapbox.navigation.navigator.internal.MapboxNativeNavigator
import com.mapbox.navigation.navigator.internal.MapboxNativeNavigatorImpl
import com.mapbox.navigation.navigator.internal.NativeNavigatorRecreationObserver
import com.mapbox.navigation.navigator.internal.TripStatus
import com.mapbox.navigation.testing.MainCoroutineRule
import com.mapbox.navigation.utils.internal.JobControl
import com.mapbox.navigation.utils.internal.ThreadController
import com.mapbox.navigator.BannerInstruction
import com.mapbox.navigator.FixLocation
import com.mapbox.navigator.NavigationStatus
import com.mapbox.navigator.NavigationStatusOrigin
import com.mapbox.navigator.NavigatorObserver
import com.mapbox.navigator.RouteInfo
import com.mapbox.navigator.RouteState
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.verifySequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runBlockingTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@InternalCoroutinesApi
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MapboxTripSessionTest {

    @get:Rule
    var coroutineRule = MainCoroutineRule()

    private lateinit var tripSession: MapboxTripSession

    private val tripService: TripService = mockk(relaxUnitFun = true) {
        every { hasServiceStarted() } returns false
    }
    private lateinit var locationUpdateAnswers: (Location) -> Unit
    private val tripSessionLocationEngine: TripSessionLocationEngine = mockk(relaxUnitFun = true) {
        every { startLocationUpdates(any(), captureLambda()) } answers {
            locationUpdateAnswers = secondArg()
        }
    }
    private val routes: List<DirectionsRoute> = listOf(mockk(relaxed = true))
    private val legIndex = 2
    private val updateReason = RoutesExtra.ROUTES_UPDATE_REASON_NEW

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var navigationOptions: NavigationOptions
    private val location: Location = mockLocation()
    private val fixLocation: FixLocation = mockk(relaxed = true)
    private val keyPoints: List<Location> = listOf(mockk(relaxUnitFun = true))
    private val keyFixPoints: List<FixLocation> = listOf(mockk(relaxed = true))

    private val navigator: MapboxNativeNavigator = mockk(relaxUnitFun = true)
    private val tripStatus: TripStatus = mockk(relaxUnitFun = true)
    private val navigationStatusOrigin: NavigationStatusOrigin = mockk()
    private val navigationStatus: NavigationStatus = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxUnitFun = true)
    private val routeProgress: RouteProgress = mockk()
    private val threadController = spyk<ThreadController>()

    private val parentJob = SupervisorJob()
    private val testScope = CoroutineScope(parentJob + coroutineRule.testDispatcher)

    private val stateObserver: TripSessionStateObserver = mockk(relaxUnitFun = true)

    private val locationMatcherResult: LocationMatcherResult = mockk(relaxUnitFun = true) {
        every { enhancedLocation } returns location
    }
    private val eHorizonSubscriptionManager: EHorizonSubscriptionManager = mockk(relaxed = true)
    private val navigatorObserverImplSlot = slot<NavigatorObserver>()
    private val navigatorRecreationObserverImplSlot = slot<NativeNavigatorRecreationObserver>()

    @Before
    fun setUp() {
        mockkObject(MapboxNativeNavigatorImpl)
        mockkStatic("com.mapbox.navigation.core.navigator.NavigatorMapper")
        mockkStatic("com.mapbox.navigation.core.navigator.LocationEx")
        every { location.toFixLocation() } returns fixLocation
        every { fixLocation.toLocation() } returns location
        every { keyFixPoints.toLocations() } returns keyPoints
        every { threadController.getMainScopeAndRootJob() } returns JobControl(parentJob, testScope)
        navigationOptions = NavigationOptions.Builder(context).build()
        tripSession = buildTripSession()

        coEvery { navigator.updateLocation(any()) } returns false
        coEvery { navigator.setRoute(any(), any()) } returns null
        coEvery { navigator.updateAnnotations(any()) } returns Unit
        every { navigationStatus.getTripStatusFrom(any(), any()) } returns tripStatus

        every { navigationStatus.location } returns fixLocation
        every { navigationStatus.keyPoints } returns keyFixPoints
        every { navigationStatus.routeState } returns RouteState.TRACKING
        every { navigationStatus.bannerInstruction } returns mockk(relaxed = true)

        every { tripStatus.navigationStatus } returns navigationStatus
        every { tripStatus.route } returns routes[0]

        every {
            tripStatus.getLocationMatcherResult(any(), any(), any())
        } returns locationMatcherResult
        every { routeProgress.bannerInstructions } returns null
        every { routeProgress.voiceInstructions } returns null
        every { routeProgress.currentLegProgress } returns mockk(relaxed = true)
        every {
            getRouteProgressFrom(any(), any(), any(), any(), any(), any())
        } returns routeProgress
        every { routes[0].requestUuid() } returns "uuid"

        every {
            navigator.addNavigatorObserver(capture(navigatorObserverImplSlot))
        } answers {}
        every {
            navigator.setNativeNavigatorRecreationObserver(
                capture(navigatorRecreationObserverImplSlot)
            )
        } answers {}
    }

    private fun buildTripSession(): MapboxTripSession {
        return MapboxTripSession(
            tripService,
            tripSessionLocationEngine,
            navigator,
            threadController,
            logger,
            eHorizonSubscriptionManager,
        )
    }

    @Test
    fun startSessionWithService() {
        every { tripService.hasServiceStarted() } returns true

        tripSession.start(true)

        assertTrue(tripSession.isRunningWithForegroundService())
        verify { tripService.startService() }
        verify { tripSessionLocationEngine.startLocationUpdates(any(), any()) }

        tripSession.stop()
    }

    @Test
    fun startSessionWithoutTripService() {
        every { tripService.hasServiceStarted() } returns false

        tripSession.start(false)

        assertFalse(tripSession.isRunningWithForegroundService())
        verify(exactly = 0) { tripService.startService() }
        verify { tripSessionLocationEngine.startLocationUpdates(any(), any()) }

        tripSession.stop()
    }

    @Test
    fun startSessionWithTripServiceCallStartAgain() {
        tripSession.start(true)

        tripSession.start(false)

        every { tripService.hasServiceStarted() } returns true

        assertTrue(tripSession.isRunningWithForegroundService())
        verify(exactly = 1) { tripService.startService() }
        verify(exactly = 1) { tripSessionLocationEngine.startLocationUpdates(any(), any()) }

        tripSession.stop()
    }

    @Test
    fun stopSessionCallsTripServiceStopService() {
        tripSession.start(true)

        tripSession.stop()

        verify { tripService.stopService() }
    }

    @Test
    fun stopSessionCallsLocationEngineRemoveLocationUpdates() {
        tripSession.start(true)
        locationUpdateAnswers.invoke(location)

        tripSession.stop()

        verify { tripSessionLocationEngine.stopLocationUpdates() }
    }

    @Test
    fun stopSessionDoesNotClearUpRoute() {
        tripSession.setRoutes(routes, legIndex, updateReason)
        tripSession.start(true)

        tripSession.stop()

        assertEquals(routes, tripSession.routes)
    }

    @Test
    fun stopTripSessionShouldStopRouteProgress() = coroutineRule.runBlockingTest {
        tripSession.setRoutes(routes, legIndex, updateReason)
        tripSession.start(true)
        tripSession.setRoutes(emptyList(), 0, RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP)
        tripSession.stop()

        coVerify(exactly = 1) {
            navigator.removeNavigatorObserver(navigatorObserverImplSlot.captured)
        }
    }

    @Test
    fun locationObserverSuccess() = coroutineRule.runBlockingTest {
        tripSession.start(true)
        val observer: LocationObserver = mockk(relaxUnitFun = true)
        tripSession.registerLocationObserver(observer)

        updateLocationAndJoin()

        verify { observer.onNewRawLocation(location) }
        assertEquals(location, tripSession.getRawLocation())

        tripSession.stop()
    }

    @Test
    fun locationObserverSuccessWhenMultipleSamples() = coroutineRule.runBlockingTest {
        tripSession.start(true)
        val observer: LocationObserver = mockk(relaxUnitFun = true)
        tripSession.registerLocationObserver(observer)
        locationUpdateAnswers.invoke(mockLocation())
        updateLocationAndJoin()
        verify { observer.onNewRawLocation(location) }
        assertEquals(location, tripSession.getRawLocation())

        tripSession.stop()
    }

    @Test
    fun locationObserverImmediate() = coroutineRule.runBlockingTest {
        tripSession.start(true)
        val observer: LocationObserver = mockk(relaxUnitFun = true)
        updateLocationAndJoin()

        tripSession.registerLocationObserver(observer)

        verify { observer.onNewRawLocation(location) }

        tripSession.stop()
    }

    @Test
    fun unregisterLocationObserver() = coroutineRule.runBlockingTest {
        tripSession.start(true)
        val observer: LocationObserver = mockk(relaxUnitFun = true)
        tripSession.registerLocationObserver(observer)
        tripSession.unregisterLocationObserver(observer)
        updateLocationAndJoin()
        verify(exactly = 0) {
            observer.onNewRawLocation(any())
            observer.onNewLocationMatcherResult(any())
        }

        tripSession.stop()
    }

    @Test
    fun locationPush() = coroutineRule.runBlockingTest {
        tripSession.start(true)
        updateLocationAndJoin()
        coVerify { navigator.updateLocation(fixLocation) }
        tripSession.stop()
    }

    @Test
    fun locationPushWhenMultipleSamples() = coroutineRule.runBlockingTest {
        tripSession.start(true)
        locationUpdateAnswers.invoke(mockLocation())
        locationUpdateAnswers.invoke(mockLocation())
        updateLocationAndJoin()
        coVerify { navigator.updateLocation(fixLocation) }
        tripSession.stop()
    }

    @Test
    fun routeProgressObserverSuccess() = coroutineRule.runBlockingTest {
        every { routeProgress.currentLegProgress } returns null
        tripSession = buildTripSession()
        tripSession.start(true)
        val observer: RouteProgressObserver = mockk(relaxUnitFun = true)
        tripSession.registerRouteProgressObserver(observer)
        updateLocationAndJoin()

        verify { observer.onRouteProgressChanged(routeProgress) }
        assertEquals(routeProgress, tripSession.getRouteProgress())
        tripSession.stop()
    }

    @Test
    fun routeProgressObserverNotCalledWhenInFreeDrive() = coroutineRule.runBlockingTest {
        every { getRouteProgressFrom(any(), any(), any(), any(), any(), any()) } returns null
        tripSession = buildTripSession()
        tripSession.start(true)
        val observer: RouteProgressObserver = mockk(relaxUnitFun = true)
        tripSession.registerRouteProgressObserver(observer)
        updateLocationAndJoin()

        verify(exactly = 0) { observer.onRouteProgressChanged(routeProgress) }
        assertNull(tripSession.getRouteProgress())
        tripSession.stop()
    }

    @Test
    fun routeProgressObserverImmediate() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        updateLocationAndJoin()
        val observer: RouteProgressObserver = mockk(relaxUnitFun = true)
        tripSession.registerRouteProgressObserver(observer)

        verify(exactly = 1) { observer.onRouteProgressChanged(routeProgress) }
        assertEquals(routeProgress, tripSession.getRouteProgress())
        tripSession.stop()
    }

    @Test
    fun routeProgressObserverImmediateEmittedBelongsToCurrentRoute() =
        coroutineRule.runBlockingTest {
            tripSession = buildTripSession()
            tripSession.start(true)
            updateLocationAndJoin()
            tripSession.setRoutes(emptyList(), 0, RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP)
            val observer: RouteProgressObserver = mockk(relaxUnitFun = true)
            tripSession.registerRouteProgressObserver(observer)

            verify(exactly = 0) { observer.onRouteProgressChanged(routeProgress) }
            assertEquals(null, tripSession.getRouteProgress())
            tripSession.stop()
        }

    @Test
    fun routeProgressObserverUnregister() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        val observer: RouteProgressObserver = mockk(relaxUnitFun = true)
        tripSession.registerRouteProgressObserver(observer)
        tripSession.unregisterRouteProgressObserver(observer)
        updateLocationAndJoin()

        verify(exactly = 0) { observer.onRouteProgressChanged(routeProgress) }
        tripSession.stop()
    }

    @Test
    fun routeProgressObserverDoubleRegister() = coroutineRule.runBlockingTest {
        every { routeProgress.currentLegProgress } returns null
        tripSession = buildTripSession()
        tripSession.start(true)
        val observer: RouteProgressObserver = mockk(relaxUnitFun = true)
        tripSession.registerRouteProgressObserver(observer)
        updateLocationAndJoin()
        tripSession.unregisterRouteProgressObserver(observer)
        tripSession.registerRouteProgressObserver(observer)

        verify(exactly = 2) { observer.onRouteProgressChanged(routeProgress) }
        tripSession.stop()
    }

    @Test
    fun offRouteObserverCalledWhenStatusIsDifferentToCurrent() = coroutineRule.runBlockingTest {
        every { routeProgress.currentLegProgress } returns null
        tripSession = buildTripSession()
        tripSession.start(true)
        val offRouteObserver: OffRouteObserver = mockk(relaxUnitFun = true)
        tripSession.registerOffRouteObserver(offRouteObserver)

        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE
        updateLocationAndJoin()

        verify(exactly = 1) { offRouteObserver.onOffRouteStateChanged(false) }
        verify(exactly = 1) { offRouteObserver.onOffRouteStateChanged(true) }
        verifyOrder {
            offRouteObserver.onOffRouteStateChanged(false)
            offRouteObserver.onOffRouteStateChanged(true)
        }
        tripSession.stop()
    }

    @Test
    fun offRouteObserverNotCalledWhenStatusIsEqualToCurrent() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        val offRouteObserver: OffRouteObserver = mockk(relaxUnitFun = true)
        tripSession.registerOffRouteObserver(offRouteObserver)

        updateLocationAndJoin()

        verify(exactly = 1) { offRouteObserver.onOffRouteStateChanged(false) }
        verify(exactly = 0) { offRouteObserver.onOffRouteStateChanged(true) }
        tripSession.stop()
    }

    @Test
    fun isOffRouteIsSetToFalseWhenSettingARoute() = coroutineRule.runBlockingTest {
        every { routeProgress.currentLegProgress } returns null
        every { routes[0].legs() } returns null
        tripSession = buildTripSession()
        tripSession.setRoutes(routes, legIndex, updateReason)
        tripSession.start(true)
        val offRouteObserver: OffRouteObserver = mockk(relaxUnitFun = true)
        tripSession.registerOffRouteObserver(offRouteObserver)
        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE
        locationUpdateAnswers.invoke(mockLocation())
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)

        tripSession.setRoutes(routes, legIndex, RoutesExtra.ROUTES_UPDATE_REASON_REROUTE)

        parentJob.cancelAndJoin()
        verify(exactly = 2) { offRouteObserver.onOffRouteStateChanged(false) }
        verify(exactly = 1) { offRouteObserver.onOffRouteStateChanged(true) }
        verifyOrder {
            offRouteObserver.onOffRouteStateChanged(false)
            offRouteObserver.onOffRouteStateChanged(true)
            offRouteObserver.onOffRouteStateChanged(false)
        }
        tripSession.stop()
    }

    @Test
    fun isOffRouteIsSetToFalseWhenSettingANullRoute() = coroutineRule.runBlockingTest {
        every { routeProgress.currentLegProgress } returns null
        every { routes[0].legs() } returns null
        tripSession = buildTripSession()
        tripSession.setRoutes(routes, legIndex, updateReason)
        tripSession.start(true)
        val offRouteObserver: OffRouteObserver = mockk(relaxUnitFun = true)
        tripSession.registerOffRouteObserver(offRouteObserver)
        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE
        locationUpdateAnswers.invoke(mockLocation())
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)

        tripSession.setRoutes(emptyList(), 0, RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP)

        parentJob.cancelAndJoin()
        verify(exactly = 2) { offRouteObserver.onOffRouteStateChanged(false) }
        verify(exactly = 1) { offRouteObserver.onOffRouteStateChanged(true) }
        verifyOrder {
            offRouteObserver.onOffRouteStateChanged(false)
            offRouteObserver.onOffRouteStateChanged(true)
            offRouteObserver.onOffRouteStateChanged(false)
        }
        tripSession.stop()
    }

    /**
     * Test for a workaround for https://github.com/mapbox/mapbox-navigation-android/issues/4727.
     */
    @Test
    fun `banner instruction fallback for missing native events`() = coroutineRule.runBlockingTest {
        val step = mockk<LegStep>(relaxed = true)
        val nativeBanner = mockk<BannerInstruction>(relaxed = true)
        val banner = mockk<BannerInstructions>(relaxed = true)
        every { navigationStatus.legIndex } returns 0
        every { navigationStatus.stepIndex } returns 1
        every { routes[0].legs() } returns listOf(
            mockk {
                every { steps() } returns listOf(
                    mockk(relaxed = true),
                    step
                )
            }
        )
        every { nativeBanner.mapToDirectionsApi() } returns banner
        coEvery { MapboxNativeNavigatorImpl.getCurrentBannerInstruction() } returns nativeBanner
        val bannerInstructionsObserver: BannerInstructionsObserver = mockk(relaxUnitFun = true)

        tripSession = buildTripSession()
        tripSession.registerBannerInstructionsObserver(bannerInstructionsObserver)
        tripSession.setRoutes(routes, legIndex, updateReason)
        tripSession.start(true)
        every { navigationStatus.routeState } returns RouteState.TRACKING
        every { navigationStatus.bannerInstruction } returns null
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)

        verify { getRouteProgressFrom(routes[0], navigationStatus, any(), banner, 0, any()) }
        tripSession.stop()
    }

    @Test
    fun bannerInstructionObserverNotInvokedWhenRouteStateInvalid() = coroutineRule.runBlockingTest {
        val bannerInstructionsObserver: BannerInstructionsObserver = mockk(relaxUnitFun = true)
        val bannerInstructions: BannerInstructions = mockk()

        every { routeProgress.bannerInstructions } returns bannerInstructions
        every { routeProgress.bannerInstructions?.view() } returns null
        every { routeProgress.voiceInstructions } returns null
        every { navigationStatus.routeState } returns RouteState.INVALID

        tripSession = buildTripSession()
        tripSession.start(true)

        updateLocationAndJoin()

        tripSession.stop()

        verify(exactly = 0) { bannerInstructionsObserver.onNewBannerInstructions(any()) }
    }

    @Test
    fun getTripService() {
        assertEquals(tripService, tripSession.tripService)
    }

    @Test
    fun setRoute() {
        tripSession.setRoutes(routes, legIndex, updateReason)

        coVerify(exactly = 1) { navigator.setRoute(routes, legIndex) }
    }

    @Test
    fun setRoute_nullable() {
        tripSession.setRoutes(emptyList(), 0, RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP)

        coVerify(exactly = 1) { navigator.setRoute(emptyList(), 0) }
    }

    @Test
    fun checkNavigatorUpdateAnnotationsWhenReasonIsRefresh() {
        tripSession.start(true)

        tripSession.setRoutes(routes, legIndex, RoutesExtra.ROUTES_UPDATE_REASON_REFRESH)

        coVerify(exactly = 1) { navigator.updateAnnotations(routes[0]) }
        coVerify(exactly = 0) { navigator.setRoute(any(), any()) }
    }

    @Test
    fun checkNavigatorUpdateAnnotationsWhenRouteIsAlternative() {
        tripSession.start(true)

        tripSession.setRoutes(
            routes,
            legIndex,
            RoutesExtra.ROUTES_UPDATE_REASON_ALTERNATIVE
        )

        coVerify(exactly = 1) { navigator.setRoute(routes, legIndex) }
        coVerify(exactly = 0) { navigator.updateAnnotations(any()) }
    }

    @Test
    fun stateObserverImmediateStop() {
        tripSession.registerStateObserver(stateObserver)
        verify(exactly = 1) { stateObserver.onSessionStateChanged(TripSessionState.STOPPED) }
    }

    @Test
    fun stateObserverImmediateStart() {
        tripSession.start(true)
        tripSession.registerStateObserver(stateObserver)
        verify(exactly = 1) { stateObserver.onSessionStateChanged(TripSessionState.STARTED) }
    }

    @Test
    fun stateObserverStart() {
        tripSession.registerStateObserver(stateObserver)
        tripSession.start(true)
        verify(exactly = 1) { stateObserver.onSessionStateChanged(TripSessionState.STARTED) }
    }

    @Test
    fun stateObserverStop() {
        tripSession.start(true)
        tripSession.registerStateObserver(stateObserver)
        tripSession.stop()
        verify(exactly = 1) { stateObserver.onSessionStateChanged(TripSessionState.STOPPED) }
    }

    @Test
    fun stateObserverDoubleStart() {
        tripSession.registerStateObserver(stateObserver)
        tripSession.start(true)
        tripSession.start(true)
        verify(exactly = 1) { stateObserver.onSessionStateChanged(TripSessionState.STARTED) }
    }

    @Test
    fun stateObserverDoubleStop() {
        tripSession.start(true)
        tripSession.registerStateObserver(stateObserver)
        tripSession.stop()
        tripSession.stop()
        verify(exactly = 1) { stateObserver.onSessionStateChanged(TripSessionState.STOPPED) }
    }

    @Test
    fun stateObserverUnregister() {
        tripSession.registerStateObserver(stateObserver)
        clearMocks(stateObserver)
        tripSession.unregisterStateObserver(stateObserver)
        tripSession.start(true)
        tripSession.stop()
        verify(exactly = 0) { stateObserver.onSessionStateChanged(any()) }
    }

    @Test
    fun unregisterAllLocationObservers() = coroutineRule.runBlockingTest {
        every { routeProgress.bannerInstructions } returns null
        every { routeProgress.voiceInstructions } returns null

        tripSession.start(true)
        val observer: LocationObserver = mockk(relaxUnitFun = true)
        tripSession.registerLocationObserver(observer)
        tripSession.unregisterAllLocationObservers()

        updateLocationAndJoin()

        verify(exactly = 0) {
            observer.onNewRawLocation(any())
            observer.onNewLocationMatcherResult(any())
        }
        assertEquals(location, tripSession.getRawLocation())

        tripSession.stop()
    }

    @Test
    fun unregisterAllRouteProgressObservers() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        val routeProgressObserver: RouteProgressObserver = mockk(relaxUnitFun = true)
        tripSession.registerRouteProgressObserver(routeProgressObserver)
        tripSession.unregisterAllRouteProgressObservers()
        updateLocationAndJoin()

        verify(exactly = 0) { routeProgressObserver.onRouteProgressChanged(any()) }

        tripSession.stop()
    }

    @Test
    fun unregisterAllOffRouteObservers() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        val offRouteObserver: OffRouteObserver = mockk(relaxUnitFun = true)
        tripSession.registerOffRouteObserver(offRouteObserver)
        tripSession.unregisterAllOffRouteObservers()

        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE
        updateLocationAndJoin()

        // registerOffRouteObserver will call onOffRouteStateChanged() on
        // the offRouteObserver so that accounts for the verify 1 time
        // below. However there shouldn't be any additional calls when
        // the locationCallback.onSuccess() is called because the collection
        // of offRouteObservers should be empty.
        verify(exactly = 1) { offRouteObserver.onOffRouteStateChanged(false) }

        tripSession.stop()
    }

    @Test
    fun unregisterAllStateObservers() = coroutineRule.runBlockingTest {
        tripSession.registerStateObserver(stateObserver)
        clearMocks(stateObserver)
        tripSession.unregisterAllStateObservers()

        tripSession.stop()

        verify(exactly = 0) { stateObserver.onSessionStateChanged(any()) }
    }

    @Test
    fun unregisterAllBannerInstructionsObservers() = coroutineRule.runBlockingTest {
        val step = mockk<LegStep>(relaxed = true)
        every { step.bannerInstructions() } returns listOf(mockk(relaxed = true))
        val leg = mockk<RouteLeg>(relaxed = true)
        every { leg.steps() } returns listOf(step)
        every { routes[0].legs() } returns listOf(leg)
        val bannerInstructionsObserver: BannerInstructionsObserver = mockk(relaxUnitFun = true)
        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE

        tripSession = buildTripSession()
        tripSession.start(true)
        tripSession.setRoutes(routes, legIndex, updateReason)
        tripSession.registerBannerInstructionsObserver(bannerInstructionsObserver)
        tripSession.unregisterAllBannerInstructionsObservers()
        updateLocationAndJoin()
        tripSession.stop()
        verify(exactly = 0) { bannerInstructionsObserver.onNewBannerInstructions(any()) }
    }

    @Test
    fun unregisterAllVoiceInstructionsObservers() = coroutineRule.runBlockingTest {
        every { routeProgress.currentLegProgress } returns null
        val voiceInstructionsObserver: VoiceInstructionsObserver = mockk(relaxUnitFun = true)
        val voiceInstructions: VoiceInstructions = mockk()
        every { routeProgress.bannerInstructions } returns null
        every { routeProgress.voiceInstructions } returns voiceInstructions
        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE

        tripSession = buildTripSession()
        tripSession.start(true)
        tripSession.registerVoiceInstructionsObserver(voiceInstructionsObserver)

        updateLocationAndJoin()

        tripSession.stop()

        tripSession.start(true)
        tripSession.unregisterAllVoiceInstructionsObservers()

        updateLocationAndJoin()

        verify(exactly = 1) { voiceInstructionsObserver.onNewVoiceInstructions(any()) }

        tripSession.stop()
    }

    @Test
    fun `road objects observer gets successfully called`() = coroutineRule.runBlockingTest {
        val roadObjectsObserver: RoadObjectsOnRouteObserver = mockk(relaxUnitFun = true)
        val roadObjects: List<UpcomingRoadObject> = listOf(mockk())
        val mockedRouteInitInfo: RouteInitInfo = mockk()
        every { mockedRouteInitInfo.roadObjects } returns roadObjects
        val mockedRouteInfo: RouteInfo = mockk()
        every { getRouteInitInfo(mockedRouteInfo) } returns mockedRouteInitInfo
        coEvery { navigator.setRoute(any(), any()) } returns mockedRouteInfo
        tripSession = buildTripSession()

        tripSession.registerRoadObjectsOnRouteObserver(roadObjectsObserver)
        tripSession.setRoutes(
            mockk(relaxed = true),
            0,
            RoutesExtra.ROUTES_UPDATE_REASON_NEW
        )

        verify(exactly = 1) { roadObjectsObserver.onNewRoadObjectsOnTheRoute(roadObjects) }
    }

    @Test
    fun `road objects observer gets called only one on duplicate updates`() =
        coroutineRule.runBlockingTest {
            val roadObjectsObserver: RoadObjectsOnRouteObserver = mockk(relaxUnitFun = true)
            val roadObjects: List<UpcomingRoadObject> = listOf(mockk())
            val mockedRouteInitInfo: RouteInitInfo = mockk()
            every { mockedRouteInitInfo.roadObjects } returns roadObjects
            val mockedRouteInfo: RouteInfo = mockk()
            every { getRouteInitInfo(mockedRouteInfo) } returns mockedRouteInitInfo
            coEvery { navigator.setRoute(any(), any()) } returns mockedRouteInfo
            tripSession = buildTripSession()

            tripSession.registerRoadObjectsOnRouteObserver(roadObjectsObserver)
            tripSession.setRoutes(
                mockk(relaxed = true),
                0,
                RoutesExtra.ROUTES_UPDATE_REASON_NEW
            )
            tripSession.setRoutes(
                mockk(relaxed = true),
                0,
                RoutesExtra.ROUTES_UPDATE_REASON_REFRESH
            )

            verify(exactly = 1) { roadObjectsObserver.onNewRoadObjectsOnTheRoute(roadObjects) }
        }

    @Test
    fun `road objects observer doesn't get called when unregistered`() =
        coroutineRule.runBlockingTest {
            val roadObjectsObserver: RoadObjectsOnRouteObserver = mockk(relaxUnitFun = true)
            val roadObjects: List<UpcomingRoadObject> = listOf(mockk())
            val mockedRouteInitInfo: RouteInitInfo = mockk()
            every { mockedRouteInitInfo.roadObjects } returns roadObjects
            val mockedRouteInfo: RouteInfo = mockk()
            every { getRouteInitInfo(mockedRouteInfo) } returns mockedRouteInitInfo
            coEvery { navigator.setRoute(any(), any()) } returns mockedRouteInfo
            tripSession = buildTripSession()

            tripSession.registerRoadObjectsOnRouteObserver(roadObjectsObserver)
            tripSession.setRoutes(
                mockk(relaxed = true),
                0,
                RoutesExtra.ROUTES_UPDATE_REASON_NEW
            )
            tripSession.unregisterRoadObjectsOnRouteObserver(roadObjectsObserver)
            tripSession.setRoutes(
                mockk(relaxed = true),
                0,
                RoutesExtra.ROUTES_UPDATE_REASON_NEW
            )

            verify(exactly = 1) { roadObjectsObserver.onNewRoadObjectsOnTheRoute(roadObjects) }
        }

    @Test
    fun `road objects observer gets immediately notified`() = coroutineRule.runBlockingTest {
        val roadObjectsObserver: RoadObjectsOnRouteObserver = mockk(relaxUnitFun = true)
        val roadObjects: List<UpcomingRoadObject> = listOf(mockk())
        val mockedRouteInitInfo: RouteInitInfo = mockk()
        every { mockedRouteInitInfo.roadObjects } returns roadObjects
        val mockedRouteInfo: RouteInfo = mockk()
        every { getRouteInitInfo(mockedRouteInfo) } returns mockedRouteInitInfo
        coEvery { navigator.setRoute(any(), any()) } returns mockedRouteInfo
        tripSession = buildTripSession()

        tripSession.setRoutes(
            mockk(relaxed = true),
            0,
            RoutesExtra.ROUTES_UPDATE_REASON_NEW
        )
        tripSession.registerRoadObjectsOnRouteObserver(roadObjectsObserver)

        verify(exactly = 1) { roadObjectsObserver.onNewRoadObjectsOnTheRoute(roadObjects) }
    }

    @Test
    fun `road objects get cleared when route is cleared`() = coroutineRule.runBlockingTest {
        val roadObjectsObserver: RoadObjectsOnRouteObserver = mockk(relaxUnitFun = true)
        val roadObjects: List<UpcomingRoadObject> = listOf(mockk())
        tripSession = buildTripSession()
        val mockedRouteInitInfo: RouteInitInfo = mockk()
        every { mockedRouteInitInfo.roadObjects } returns roadObjects
        val mockedRouteInfo: RouteInfo = mockk()
        every { getRouteInitInfo(mockedRouteInfo) } returns mockedRouteInitInfo
        coEvery { navigator.setRoute(any(), any()) } returns mockedRouteInfo
        tripSession.setRoutes(
            mockk(relaxed = true),
            0,
            RoutesExtra.ROUTES_UPDATE_REASON_NEW
        )
        tripSession.registerRoadObjectsOnRouteObserver(roadObjectsObserver)
        coEvery { navigator.setRoute(any(), any()) } returns null
        tripSession.setRoutes(
            emptyList(),
            0,
            RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP
        )

        verifySequence {
            roadObjectsObserver.onNewRoadObjectsOnTheRoute(roadObjects)
            roadObjectsObserver.onNewRoadObjectsOnTheRoute(emptyList())
        }
    }

    @Test
    fun `road objects observer is notified with null if there's no route`() =
        coroutineRule.runBlockingTest {
            val roadObjectsObserver: RoadObjectsOnRouteObserver = mockk(relaxUnitFun = true)
            tripSession = buildTripSession()

            tripSession.registerRoadObjectsOnRouteObserver(roadObjectsObserver)

            verify(exactly = 1) { roadObjectsObserver.onNewRoadObjectsOnTheRoute(emptyList()) }
        }

    @Test
    fun unregisterAllRouteAlertsObservers() = coroutineRule.runBlockingTest {
        val roadObjectsObserver: RoadObjectsOnRouteObserver = mockk(relaxUnitFun = true)
        val roadObjects: List<UpcomingRoadObject> = listOf(mockk())
        val mockedRouteInitInfo: RouteInitInfo = mockk()
        every { mockedRouteInitInfo.roadObjects } returns roadObjects
        val mockedRouteInfo: RouteInfo = mockk()
        every { getRouteInitInfo(mockedRouteInfo) } returns mockedRouteInitInfo
        coEvery { navigator.setRoute(any(), any()) } returns mockedRouteInfo
        tripSession = buildTripSession()

        tripSession.registerRoadObjectsOnRouteObserver(roadObjectsObserver)
        tripSession.setRoutes(
            mockk(relaxed = true),
            0,
            RoutesExtra.ROUTES_UPDATE_REASON_NEW
        )
        tripSession.unregisterAllRoadObjectsOnRouteObservers()
        tripSession.setRoutes(
            mockk(relaxed = true),
            0,
            RoutesExtra.ROUTES_UPDATE_REASON_NEW
        )

        verify(exactly = 1) { roadObjectsObserver.onNewRoadObjectsOnTheRoute(roadObjects) }
    }

    @Test
    fun `location matcher result success`() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        val observer: LocationObserver = mockk(relaxUnitFun = true)
        tripSession.registerLocationObserver(observer)
        updateLocationAndJoin()

        verify(exactly = 1) { observer.onNewLocationMatcherResult(locationMatcherResult) }
        assertEquals(location, tripSession.locationMatcherResult?.enhancedLocation)
        tripSession.stop()
    }

    @Test
    fun `location matcher result immediate`() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        updateLocationAndJoin()
        val observer: LocationObserver = mockk(relaxUnitFun = true)
        tripSession.registerLocationObserver(observer)

        verify(exactly = 1) { observer.onNewLocationMatcherResult(locationMatcherResult) }
        assertEquals(location, tripSession.locationMatcherResult?.enhancedLocation)
        tripSession.stop()
    }

    @Test
    fun `location matcher result cleared on reset`() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        updateLocationAndJoin()
        tripSession.stop()
        val observer: LocationObserver = mockk(relaxUnitFun = true)
        tripSession.registerLocationObserver(observer)

        verify(exactly = 0) { observer.onNewLocationMatcherResult(any()) }
    }

    @Test
    fun `eHorizonObserver added to subscriptionManager on registerEHorizonObserver`() {
        tripSession = buildTripSession()
        tripSession.start(true)
        val observer: EHorizonObserver = mockk(relaxUnitFun = true)

        tripSession.registerEHorizonObserver(observer)

        verify(exactly = 1) { eHorizonSubscriptionManager.registerObserver(observer) }
        tripSession.stop()
    }

    @Test
    fun `eHorizonObserver removed from subscriptionManager on unregisterObserver`() {
        tripSession = buildTripSession()
        tripSession.start(true)
        val observer: EHorizonObserver = mockk(relaxUnitFun = true)

        tripSession.unregisterEHorizonObserver(observer)

        verify(exactly = 1) { eHorizonSubscriptionManager.unregisterObserver(observer) }
        tripSession.stop()
    }

    @Test
    fun `all eHorizonObservers removed from subscriptionManager on unregisterAllObservers`() {
        tripSession = buildTripSession()
        tripSession.start(true)

        tripSession.unregisterAllEHorizonObservers()

        verify(exactly = 1) { eHorizonSubscriptionManager.unregisterAllObservers() }
        tripSession.stop()
    }

    @Test
    fun `when session is started and navigator is recreated observer is reset`() {
        tripSession = buildTripSession()
        tripSession.start(true)

        navigatorRecreationObserverImplSlot.captured.onNativeNavigatorRecreated()

        verify(exactly = 2) { navigator.addNavigatorObserver(any()) }
        tripSession.stop()
    }

    @Test
    fun `when session is not started and navigator is recreated observer is not reset`() {
        tripSession = buildTripSession()

        navigatorRecreationObserverImplSlot.captured.onNativeNavigatorRecreated()

        verify(exactly = 0) { navigator.addNavigatorObserver(any()) }
    }

    @Test
    fun `when not empty fallback observers and navigator is recreated fallback observer reset`() {
        tripSession = buildTripSession()
        tripSession.registerFallbackVersionsObserver(mockk())

        navigatorRecreationObserverImplSlot.captured.onNativeNavigatorRecreated()

        verify(exactly = 2) { navigator.setFallbackVersionsObserver(any()) }
    }

    @Test
    fun `when no fallback observers and navigator is recreated fallback observer is not reset`() {
        tripSession = buildTripSession()

        navigatorRecreationObserverImplSlot.captured.onNativeNavigatorRecreated()

        verify(exactly = 0) { navigator.setFallbackVersionsObserver(any()) }
    }

    @Test
    fun routeIsSetAfterNativeJobComplete() = runBlockingTest {
        coEvery { navigator.setRoute(any(), any()) } coAnswers {
            delay(100)
            null
        }

        tripSession.setRoutes(routes, legIndex, updateReason)

        assertTrue(tripSession.routes.isEmpty())

        coroutineRule.testDispatcher.advanceTimeBy(200)

        assertEquals(tripSession.routes, routes)
    }

    @Test
    fun `offRoute state is reset when setRoute is called`() = runBlockingTest {
        coEvery { navigator.setRoute(any(), any()) } coAnswers {
            delay(100)
            null
        }

        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE

        val offRouteObserver: OffRouteObserver = mockk(relaxed = true)

        tripSession = buildTripSession()
        tripSession.registerOffRouteObserver(offRouteObserver)

        tripSession.start(true)

        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)

        tripSession.setRoutes(routes, legIndex, updateReason)

        verifyOrder {
            offRouteObserver.onOffRouteStateChanged(false)
            offRouteObserver.onOffRouteStateChanged(true)
            offRouteObserver.onOffRouteStateChanged(false)
        }

        coroutineRule.testDispatcher.advanceTimeBy(200)
    }

    @Test
    fun `routeProgress is reset when setRoute is called`() = runBlockingTest {
        coEvery { navigator.setRoute(any(), any()) } coAnswers {
            delay(100)
            null
        }

        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE

        tripSession = buildTripSession()
        tripSession.start(true)
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)

        assertNotNull(tripSession.getRouteProgress())

        tripSession.setRoutes(routes, legIndex, updateReason)

        assertNull(tripSession.getRouteProgress())

        coroutineRule.testDispatcher.advanceTimeBy(200)
    }

    @Test
    fun `roadObjects are reset when setRoute is called`() = runBlockingTest {
        coEvery { navigator.setRoute(any(), any()) } coAnswers {
            delay(100)
            null
        }

        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE

        val roadObjectsObserver: RoadObjectsOnRouteObserver = mockk(relaxed = true)
        tripSession = buildTripSession()
        tripSession.registerRoadObjectsOnRouteObserver(roadObjectsObserver)
        tripSession.start(true)

        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)

        tripSession.setRoutes(routes, legIndex, updateReason)

        verify(exactly = 1) {
            roadObjectsObserver.onNewRoadObjectsOnTheRoute(emptyList())
        }

        coroutineRule.testDispatcher.advanceTimeBy(200)
    }

    @Test
    fun `routeProgress updated after route is set`() = runBlockingTest {
        coEvery { navigator.setRoute(any(), any()) } coAnswers {
            delay(100)
            null
        }

        val observerOne: RouteProgressObserver = mockk(relaxUnitFun = true)
        val observerTwo: RouteProgressObserver = mockk(relaxUnitFun = true)
        every { observerOne.onRouteProgressChanged(any()) } just Runs
        every { observerTwo.onRouteProgressChanged(any()) } just Runs
        every { routes[0].legs() } returns null

        tripSession = buildTripSession()
        tripSession.registerRouteProgressObserver(observerOne)
        tripSession.registerRouteProgressObserver(observerTwo)
        tripSession.start(true)
        tripSession.setRoutes(routes, legIndex, updateReason)

        repeat(5) {
            navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        }

        coroutineRule.testDispatcher.advanceTimeBy(200)

        repeat(2) {
            navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        }

        verify(exactly = 2) { observerOne.onRouteProgressChanged(any()) }
        verify(exactly = 2) { observerTwo.onRouteProgressChanged(any()) }
    }

    @Test
    fun `enhancedLocation, locationMatcherResult, zLevel are updating while setting a route, routeProgress, bannerInstructions and offRoute state are skipped`() = runBlockingTest {
        coEvery { navigator.setRoute(any(), any()) } coAnswers {
            delay(100)
            null
        }

        val routeProgressObserver: RouteProgressObserver = mockk(relaxUnitFun = true)
        val locationObserver: LocationObserver = mockk(relaxUnitFun = true)
        val offRouteObserver: OffRouteObserver = mockk(relaxUnitFun = true)
        val bannerInstructionsObserver: BannerInstructionsObserver = mockk(relaxUnitFun = true)
        every { routeProgressObserver.onRouteProgressChanged(any()) } just Runs
        every { locationObserver.onNewLocationMatcherResult(any()) } just Runs
        every { offRouteObserver.onOffRouteStateChanged(any()) } just Runs
        every { bannerInstructionsObserver.onNewBannerInstructions(any()) } just Runs

        val leg: RouteLeg = mockk(relaxed = true)
        val legs = listOf(leg)
        val steps: List<LegStep> = mockk(relaxed = true)
        every { navigationStatus.legIndex } returns 0
        every { routes[0].legs() } returns legs
        every { leg.steps() } returns steps

        tripSession = buildTripSession()
        tripSession.registerRouteProgressObserver(routeProgressObserver)
        tripSession.registerLocationObserver(locationObserver)
        tripSession.registerOffRouteObserver(offRouteObserver)
        tripSession.registerBannerInstructionsObserver(bannerInstructionsObserver)
        tripSession.start(true)
        // it will notify offRouteObserver for the first time
        tripSession.setRoutes(routes, legIndex, updateReason)

        every { navigationStatus.routeState } returns RouteState.OFF_ROUTE

        // first status update
        every { navigationStatus.layer } returns 100
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        assertEquals(100, tripSession.zLevel)

        // second status update
        every { navigationStatus.layer } returns 200
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        assertEquals(200, tripSession.zLevel)

        // finish setRoute
        coroutineRule.testDispatcher.advanceTimeBy(200)

        // third status update
        every { navigationStatus.layer } returns 300
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        assertEquals(300, tripSession.zLevel)

        // locationObserver is notified on each status update
        verify(exactly = 3) { locationObserver.onNewLocationMatcherResult(any()) }

        // routeProgressObserver and bannerInstructionsObserver are notified when setRoute is finished
        verify(exactly = 1) { routeProgressObserver.onRouteProgressChanged(any()) }
        verify(exactly = 1) { bannerInstructionsObserver.onNewBannerInstructions(any()) }
        // offRouteObserver is notified twice:
        // when setRoute starts, and on a new status when setRoute is finished
        verify(exactly = 2) { offRouteObserver.onOffRouteStateChanged(any()) }
    }

    @Test
    fun `zLevel is null before session is started`() {
        assertNull(tripSession.zLevel)
    }

    @Test
    fun `zLevel is null after session is started, but before navigator observer is invoked`() {
        tripSession.start(withTripService = true)
        assertNull(tripSession.zLevel)
    }

    @Test
    fun `zLevel returns layer from navigator status`() {
        tripSession.start(withTripService = true)
        every { navigationStatus.layer } returns 3
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        assertEquals(3, tripSession.zLevel)
    }

    @Test
    fun `zLevel returns null after session is stopped`() {
        tripSession.start(withTripService = true)
        every { navigationStatus.layer } returns 3
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        tripSession.stop()
        assertNull(tripSession.zLevel)
    }

    @Test
    fun `updateLegIndexJob is cancelled and callback is fired when setRoute is called`() = runBlockingTest {
        coEvery { navigator.setRoute(any(), any()) } coAnswers {
            delay(100)
            null
        }

        coEvery { navigator.updateLegIndex(any()) } coAnswers {
            delay(100) // doesn't affect test duration
            true
        }

        tripSession = buildTripSession()
        tripSession.start(true)

        val legIndexUpdatedCallback: LegIndexUpdatedCallback = mockk(relaxed = true)
        tripSession.updateLegIndex(1, legIndexUpdatedCallback)

        tripSession.setRoutes(routes, legIndex, RoutesExtra.ROUTES_UPDATE_REASON_NEW)

        verify(exactly = 1) {
            legIndexUpdatedCallback.onLegIndexUpdatedCallback(false)
        }

        coroutineRule.testDispatcher.advanceTimeBy(200)
    }

    @Test
    fun `updateRouteProgressJob is cancelled onStatus update`() = runBlockingTest {
        coEvery { MapboxNativeNavigatorImpl.getCurrentBannerInstruction() } coAnswers {
            delay(100) // doesn't affect test duration
            null
        }

        val leg: RouteLeg = mockk(relaxed = true)
        val legs = listOf(leg)
        val steps: List<LegStep> = mockk(relaxed = true)
        every { navigationStatus.legIndex } returns 0
        every { navigationStatus.bannerInstruction } returns null
        every { routes[0].legs() } returns legs
        every { leg.steps() } returns steps

        val routeProgressObserver: RouteProgressObserver = mockk(relaxUnitFun = true)
        tripSession = buildTripSession()
        tripSession.registerRouteProgressObserver(routeProgressObserver)
        tripSession.start(true)

        // each status update cancels updateRouteProgressJob,
        // only the last one will update routeProgress
        repeat(5) {
            navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        }

        coroutineRule.testDispatcher.advanceTimeBy(200)

        verify(exactly = 1) { routeProgressObserver.onRouteProgressChanged(any()) }
    }

    @Test
    fun `NativeRouteProcessingListeners called when route processing starts`() =
        coroutineRule.runBlockingTest {
            tripSession = buildTripSession()
            val listener = mockk<NativeRouteProcessingListener>()
            tripSession.registerNativeRouteProcessingListener(listener)
            tripSession.start(true)
            tripSession.setRoutes(routes, legIndex, updateReason)

            verify(exactly = 1) { listener.onNativeRouteProcessingStarted() }
        }

    @Test
    fun unregisterAllNativeRouteProcessingListeners() = coroutineRule.runBlockingTest {
        tripSession = buildTripSession()
        tripSession.start(true)
        val listener = mockk<NativeRouteProcessingListener>()
        tripSession.registerNativeRouteProcessingListener(listener)
        tripSession.unregisterAllNativeRouteProcessingListeners()
        tripSession.setRoutes(routes, legIndex, updateReason)

        verify(exactly = 0) { listener.onNativeRouteProcessingStarted() }

        tripSession.stop()
    }

    @After
    fun cleanUp() {
        unmockkObject(MapboxNativeNavigatorImpl)
        unmockkStatic("com.mapbox.navigation.core.navigator.NavigatorMapper")
        unmockkStatic("com.mapbox.navigation.core.navigator.LocationEx")
    }

    private fun mockLocation(): Location = mockk(relaxed = true)

    private suspend fun updateLocationAndJoin() {
        locationUpdateAnswers.invoke(location)
        navigatorObserverImplSlot.captured.onStatus(navigationStatusOrigin, navigationStatus)
        parentJob.cancelAndJoin()
    }
}
