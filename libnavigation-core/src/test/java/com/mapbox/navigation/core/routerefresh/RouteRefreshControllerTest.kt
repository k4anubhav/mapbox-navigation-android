package com.mapbox.navigation.core.routerefresh

import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.base.common.logger.Logger
import com.mapbox.base.common.logger.model.Message
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.route.RouteRefreshCallback
import com.mapbox.navigation.base.route.RouteRefreshOptions
import com.mapbox.navigation.core.directions.session.DirectionsSession
import com.mapbox.navigation.core.trip.session.TripSession
import com.mapbox.navigation.navigator.internal.MapboxNativeNavigatorImpl.OFFLINE_UUID
import com.mapbox.navigation.testing.MainCoroutineRule
import com.mapbox.navigation.utils.internal.ThreadController
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val routeDiff1 = "Updated distance, duration, speed, congestion at leg 1"
private const val routeDiff2 = "Updated duration, speed, maxSpeed, congestion at leg 3"

@ExperimentalCoroutinesApi
class RouteRefreshControllerTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val routeRefreshCallbackSlot = slot<RouteRefreshCallback>()
    private val directionsSession: DirectionsSession = mockk(relaxUnitFun = true) {
        every { routes } returns listOf(mockk())
    }
    private val tripSession: TripSession = mockk()
    private val logger: Logger = mockk {
        every { w(any(), any()) } just Runs
        every { i(any(), any()) } just Runs
        every { e(any(), any()) } just Runs
        every { e(any(), any(), any()) } just Runs
    }
    private val routeDiffProvider = mockk<DirectionsRouteDiffProvider>()
    private val routeOptions: RouteOptions = mockk {
        every {
            coordinatesList()
        } returns listOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(1.1, 1.1))
    }
    private val validRoute: DirectionsRoute = mockk {
        every { routeOptions() } returns routeOptions
        every { requestUuid() } returns "test_uuid"
        every { legs() } returns null
    }

    private val routeRefreshOptions = RouteRefreshOptions.Builder().build()
    private val threadController = ThreadController()

    private val routeRefreshController = RouteRefreshController(
        routeRefreshOptions,
        directionsSession,
        tripSession,
        logger,
        threadController,
        routeDiffProvider,
    )

    private val requestId = 1L

    @Before
    fun setup() {
        mockkStatic("com.mapbox.navigation.base.extensions.RouteOptionsExtensions")
        every { tripSession.getRouteProgress() } returns mockk {
            every { currentLegProgress } returns mockk {
                every { legIndex } returns 0
            }
        }
        every {
            directionsSession.requestRouteRefresh(any(), any(), capture(routeRefreshCallbackSlot))
        } returns requestId
    }

    @Test
    fun `should refresh route every 5 minutes by default`() = coroutineRule.runBlockingTest {
        every { routeOptions.enableRefresh() } returns true

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(TimeUnit.MINUTES.toMillis(15))
        routeRefreshController.stop()

        verify(exactly = 3) { directionsSession.requestRouteRefresh(any(), any(), any()) }
    }

    @Test
    fun `should refresh route according to options`() = coroutineRule.runBlockingTest {
        val routeRefreshController = RouteRefreshController(
            RouteRefreshOptions.Builder()
                .intervalMillis(TimeUnit.MINUTES.toMillis(1))
                .build(),
            directionsSession,
            tripSession,
            logger,
            threadController,
        )
        every { routeOptions.enableRefresh() } returns true

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(TimeUnit.MINUTES.toMillis(15))
        routeRefreshController.stop()

        verify(exactly = 15) { directionsSession.requestRouteRefresh(any(), any(), any()) }
    }

    @Test
    fun `should refresh route with correct properties`() = coroutineRule.runBlockingTest {
        every { routeOptions.enableRefresh() } returns true

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(TimeUnit.MINUTES.toMillis(6))
        routeRefreshController.stop()

        verify(exactly = 1) { directionsSession.requestRouteRefresh(any(), any(), any()) }
    }

    @Test
    fun `should refresh route with any annotation`() =
        coroutineRule.runBlockingTest {
            every { routeOptions.enableRefresh() } returns true

            routeRefreshController.restart(validRoute)
            coroutineRule.testDispatcher.advanceTimeBy(TimeUnit.MINUTES.toMillis(6))
            routeRefreshController.stop()

            verify(exactly = 1) { directionsSession.requestRouteRefresh(any(), any(), any()) }
        }

    @Test
    fun `should log warning when route is not supported`() = coroutineRule.runBlockingTest {
        every { routeOptions.enableRefresh() } returns true
        every { validRoute.requestUuid() } returns null

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(TimeUnit.MINUTES.toMillis(6))
        routeRefreshController.stop()

        verify(exactly = 0) { directionsSession.requestRouteRefresh(any(), any(), any()) }
        verify(exactly = 1) {
            logger.w(RouteRefreshController.TAG, any())
        }
    }

    @Test
    fun `cancel request when stopped (nothing started)`() {
        routeRefreshController.stop()

        verify(exactly = 0) { directionsSession.cancelRouteRefreshRequest(any()) }
    }

    @Test
    fun `cancel request when stopped`() = coroutineRule.runBlockingTest {
        every { routeOptions.enableRefresh() } returns true

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(TimeUnit.MINUTES.toMillis(6))
        routeRefreshController.stop()

        verify(exactly = 1) { directionsSession.cancelRouteRefreshRequest(requestId) }
    }

    @Test
    fun `do not send a request when route options is null`() {
        every { validRoute.routeOptions() } returns null

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(routeRefreshOptions.intervalMillis * 2)
        routeRefreshController.stop()

        verify(exactly = 0) { directionsSession.requestRouteRefresh(any(), any(), any()) }
    }

    @Test
    fun `do not send a request when uuid is empty`() {
        every { routeOptions.enableRefresh() } returns true
        every { validRoute.requestUuid() } returns ""

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(routeRefreshOptions.intervalMillis * 2)
        routeRefreshController.stop()

        verify(exactly = 0) { directionsSession.requestRouteRefresh(any(), any(), any()) }
    }

    @Test
    fun `do not send a request when uuid is offline`() {
        every { routeOptions.enableRefresh() } returns true
        every { validRoute.requestUuid() } returns OFFLINE_UUID

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(routeRefreshOptions.intervalMillis * 2)
        routeRefreshController.stop()

        verify(exactly = 0) { directionsSession.requestRouteRefresh(any(), any(), any()) }
    }

    @Test
    fun `should cancel the previous request before starting a new one`() {
        every { routeOptions.enableRefresh() } returns true

        // Create 2 requests.
        //   Let the first one create a requestId equal to 1.
        //   Cancel during the second call, and then we expect the first request to be canceled.
        val countDownLatch = CountDownLatch(2)
        every { directionsSession.requestRouteRefresh(any(), any(), any()) } answers {
            if (countDownLatch.count == 1L) {
                routeRefreshController.restart(validRoute)
            }
            countDownLatch.countDown()
            countDownLatch.count
        }

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(routeRefreshOptions.intervalMillis * 2)
        countDownLatch.await()
        routeRefreshController.stop()

        verify(exactly = 2) { directionsSession.requestRouteRefresh(any(), any(), any()) }
        verify { directionsSession.cancelRouteRefreshRequest(1) }
    }

    @Test
    fun `restart should cancel outgoing requests and restart the refresh interval`() {
        every { routeOptions.enableRefresh() } returns true

        // We're expecting 2 requests in this test. The interruptions will
        // happen in between the requests.
        val countDownLatch = CountDownLatch(2)
        every { directionsSession.requestRouteRefresh(any(), any(), any()) } answers {
            countDownLatch.countDown()
            countDownLatch.count
        }

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(
            (routeRefreshOptions.intervalMillis * 1.9).toLong()
        )
        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(
            (routeRefreshOptions.intervalMillis * 1.9).toLong()
        )
        countDownLatch.await()

        // If the timer did not restart, the count would be 3.
        verify(exactly = 2) { directionsSession.requestRouteRefresh(any(), any(), any()) }
        verify { directionsSession.cancelRouteRefreshRequest(1) }

        // Clean up coroutines
        routeRefreshController.stop()
    }

    @Test
    fun `clear the request when there is a successful response`() {
        every { routeOptions.enableRefresh() } returns true
        every { routeDiffProvider.buildRouteDiffs(validRoute, any(), 0) } returns emptyList()

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(routeRefreshOptions.intervalMillis)
        routeRefreshCallbackSlot.captured.onRefresh(
            mockk {
                every { legs() } returns null
            },
        )
        routeRefreshController.stop()

        verify(exactly = 1) { directionsSession.requestRouteRefresh(any(), any(), any()) }
        verify(exactly = 0) { directionsSession.cancelRouteRefreshRequest(any()) }
    }

    @Test
    fun `clear the request when there is a failure response`() {
        every { routeOptions.enableRefresh() } returns true

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(routeRefreshOptions.intervalMillis)
        routeRefreshCallbackSlot.captured.onError(
            mockk {
                every { message } returns "test error"
                every { throwable } returns mockk()
            }
        )
        routeRefreshController.stop()

        verify(exactly = 1) { directionsSession.requestRouteRefresh(any(), any(), any()) }
        verify(exactly = 0) { directionsSession.cancelRouteRefreshRequest(any()) }
    }

    @Test
    fun `should log warning when route options are null`() = coroutineRule.runBlockingTest {
        every { validRoute.routeOptions() } returns null

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(TimeUnit.MINUTES.toMillis(6))
        routeRefreshController.stop()

        verify(exactly = 0) { directionsSession.requestRouteRefresh(any(), any(), any()) }
        verify(exactly = 1) {
            logger.w(
                RouteRefreshController.TAG,
                Message(
                    """
                        Unable to refresh the route because routeOptions are missing.
                        Use #fromJson(json, routeOptions, requestUuid)
                        when deserializing the route or route response.
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `should log route diffs when there is a successful response`() {
        val newRoute = mockk<DirectionsRoute>()
        val routeDiffs = listOf(routeDiff1, routeDiff2)

        every { routeOptions.enableRefresh() } returns true
        every { routeDiffProvider.buildRouteDiffs(validRoute, newRoute, 0) } returns routeDiffs

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(routeRefreshOptions.intervalMillis)
        routeRefreshCallbackSlot.captured.onRefresh(newRoute)
        routeRefreshController.stop()

        verify(exactly = 1) {
            logger.i(RouteRefreshController.TAG, Message(routeDiff1))
            logger.i(RouteRefreshController.TAG, Message(routeDiff2))
        }
    }

    @Test
    fun `should log message when there is a successful response without route diffs`() {
        val newRoute = mockk<DirectionsRoute>()

        every { routeOptions.enableRefresh() } returns true
        every { routeDiffProvider.buildRouteDiffs(validRoute, newRoute, 0) } returns emptyList()

        routeRefreshController.restart(validRoute)
        coroutineRule.testDispatcher.advanceTimeBy(routeRefreshOptions.intervalMillis)
        routeRefreshCallbackSlot.captured.onRefresh(newRoute)
        routeRefreshController.stop()

        verify(exactly = 1) {
            logger.i(RouteRefreshController.TAG, Message("No changes to route annotations"))
        }
    }

    @After
    fun tearDown() {
        unmockkStatic("com.mapbox.navigation.base.extensions.RouteOptionsExtensions")
    }
}
