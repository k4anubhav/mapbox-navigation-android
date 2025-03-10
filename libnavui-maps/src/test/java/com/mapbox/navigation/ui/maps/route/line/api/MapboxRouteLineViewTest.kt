package com.mapbox.navigation.ui.maps.route.line.api

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.mapbox.bindgen.Expected
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.getLayer
import com.mapbox.maps.extension.style.layers.properties.generated.Visibility
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSource
import com.mapbox.navigation.testing.MainCoroutineRule
import com.mapbox.navigation.ui.maps.internal.route.line.MapboxRouteLineUtils
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.ALTERNATIVE_ROUTE1_CASING_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.ALTERNATIVE_ROUTE1_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.ALTERNATIVE_ROUTE1_SOURCE_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.ALTERNATIVE_ROUTE1_TRAFFIC_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.ALTERNATIVE_ROUTE2_CASING_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.ALTERNATIVE_ROUTE2_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.ALTERNATIVE_ROUTE2_SOURCE_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.ALTERNATIVE_ROUTE2_TRAFFIC_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.PRIMARY_ROUTE_CASING_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.PRIMARY_ROUTE_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.PRIMARY_ROUTE_SOURCE_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.PRIMARY_ROUTE_TRAFFIC_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.RESTRICTED_ROAD_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.TOP_LEVEL_ROUTE_LINE_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.WAYPOINT_LAYER_ID
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.WAYPOINT_SOURCE_ID
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineClearValue
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineData
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineDynamicData
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineError
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineUpdateValue
import com.mapbox.navigation.ui.maps.route.line.model.RouteSetValue
import com.mapbox.navigation.utils.internal.InternalJobControlFactory
import com.mapbox.navigation.utils.internal.JobControl
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MapboxRouteLineViewTest {

    @get:Rule
    var coroutineRule = MainCoroutineRule()
    private val parentJob = SupervisorJob()
    private val testScope = CoroutineScope(parentJob + coroutineRule.testDispatcher)

    lateinit var ctx: Context

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        ctx = ApplicationProvider.getApplicationContext()
        mockkObject(InternalJobControlFactory)
        every {
            InternalJobControlFactory.createDefaultScopeJobControl()
        } returns JobControl(parentJob, testScope)
    }

    @After
    fun cleanUp() {
        unmockkObject(InternalJobControlFactory)
    }

    private fun mockCheckForLayerInitialization(style: Style) {
        with(style) {
            every { styleSourceExists(PRIMARY_ROUTE_SOURCE_ID) } returns true
            every { styleSourceExists(ALTERNATIVE_ROUTE1_SOURCE_ID) } returns true
            every { styleSourceExists(ALTERNATIVE_ROUTE2_SOURCE_ID) } returns true
            every { styleLayerExists(PRIMARY_ROUTE_LAYER_ID) } returns true
            every {
                styleLayerExists(PRIMARY_ROUTE_TRAFFIC_LAYER_ID)
            } returns true
            every {
                styleLayerExists(RESTRICTED_ROAD_LAYER_ID)
            } returns true
            every {
                styleLayerExists(PRIMARY_ROUTE_CASING_LAYER_ID)
            } returns true
            every { styleLayerExists(ALTERNATIVE_ROUTE1_LAYER_ID) } returns true
            every { styleLayerExists(ALTERNATIVE_ROUTE2_LAYER_ID) } returns true
            every {
                styleLayerExists(ALTERNATIVE_ROUTE1_CASING_LAYER_ID)
            } returns true
            every {
                styleLayerExists(ALTERNATIVE_ROUTE2_CASING_LAYER_ID)
            } returns true
            every {
                styleLayerExists(ALTERNATIVE_ROUTE1_TRAFFIC_LAYER_ID)
            } returns true
            every {
                styleLayerExists(ALTERNATIVE_ROUTE2_TRAFFIC_LAYER_ID)
            } returns true
            every {
                styleLayerExists(TOP_LEVEL_ROUTE_LINE_LAYER_ID)
            } returns true
        }
    }

    @Test
    fun initializeLayers() {
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val style = mockk<Style> {
            every {
                setStyleSourceProperty(PRIMARY_ROUTE_SOURCE_ID, any(), any())
            } returns ExpectedFactory.createNone()
            every {
                setStyleSourceProperty(ALTERNATIVE_ROUTE1_SOURCE_ID, any(), any())
            } returns ExpectedFactory.createNone()
            every {
                setStyleSourceProperty(ALTERNATIVE_ROUTE2_SOURCE_ID, any(), any())
            } returns ExpectedFactory.createNone()
            every {
                setStyleSourceProperty(WAYPOINT_SOURCE_ID, any(), any())
            } returns ExpectedFactory.createNone()
        }.also {
            mockCheckForLayerInitialization(it)
        }

        MapboxRouteLineView(options).initializeLayers(style)

        verify { MapboxRouteLineUtils.initializeLayers(style, options) }
        unmockkObject(MapboxRouteLineUtils)
    }

    @Test
    fun renderClearRouteDataState() = coroutineRule.runBlockingTest {
        mockkStatic("com.mapbox.maps.extension.style.sources.SourceUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val primaryRouteFeatureCollection =
            FeatureCollection.fromFeatures(listOf(getEmptyFeature()))
        val altRoutesFeatureCollection = FeatureCollection.fromFeatures(listOf(getEmptyFeature()))
        val waypointsFeatureCollection = FeatureCollection.fromFeatures(listOf(getEmptyFeature()))
        val primaryRouteSource = mockk<GeoJsonSource>(relaxed = true)
        val altRoute1Source = mockk<GeoJsonSource>(relaxed = true)
        val altRoute2Source = mockk<GeoJsonSource>(relaxed = true)
        val wayPointSource = mockk<GeoJsonSource>(relaxed = true)
        val style = mockk<Style> {
            every { getSource(PRIMARY_ROUTE_SOURCE_ID) } returns primaryRouteSource
            every { getSource(ALTERNATIVE_ROUTE1_SOURCE_ID) } returns altRoute1Source
            every { getSource(ALTERNATIVE_ROUTE2_SOURCE_ID) } returns altRoute2Source
            every { getSource(WAYPOINT_SOURCE_ID) } returns wayPointSource
        }.also {
            mockCheckForLayerInitialization(it)
        }

        val state: Expected<RouteLineError, RouteLineClearValue> = ExpectedFactory.createValue(
            RouteLineClearValue(
                primaryRouteFeatureCollection,
                listOf(altRoutesFeatureCollection, altRoutesFeatureCollection),
                waypointsFeatureCollection
            )
        )

        pauseDispatcher {
            MapboxRouteLineView(options).renderClearRouteLineValue(style, state)
            verify { MapboxRouteLineUtils.initializeLayers(style, options) }
        }

        verify { primaryRouteSource.featureCollection(primaryRouteFeatureCollection) }
        verify { altRoute1Source.featureCollection(altRoutesFeatureCollection) }
        verify { altRoute2Source.featureCollection(altRoutesFeatureCollection) }
        verify { wayPointSource.featureCollection(waypointsFeatureCollection) }
        verify { MapboxRouteLineUtils.initializeLayers(style, options) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.sources.SourceUtils")
    }

    @Test
    fun renderTraveledRouteLineUpdate() = coroutineRule.runBlockingTest {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val trafficLineExp = mockk<Expression>()
        val routeLineExp = mockk<Expression>()
        val casingLineEx = mockk<Expression>()
        val restrictedRoadExp = mockk<Expression>()
        val state: Expected<RouteLineError, RouteLineUpdateValue> =
            ExpectedFactory.createValue(
                RouteLineUpdateValue(
                    primaryRouteLineDynamicData = RouteLineDynamicData(
                        { routeLineExp },
                        { casingLineEx },
                        { trafficLineExp },
                        { restrictedRoadExp }
                    ),
                    alternativeRouteLinesDynamicData = listOf(
                        RouteLineDynamicData(
                            { throw UnsupportedOperationException() },
                            { throw UnsupportedOperationException() },
                            { throw UnsupportedOperationException() },
                            { throw UnsupportedOperationException() }
                        ),
                        RouteLineDynamicData(
                            { throw UnsupportedOperationException() },
                            { throw UnsupportedOperationException() },
                            { throw UnsupportedOperationException() },
                            { throw UnsupportedOperationException() }
                        )
                    )
                )
            )
        val primaryRouteTrafficLayer = mockk<LineLayer>(relaxed = true)
        val primaryRouteLayer = mockk<LineLayer>(relaxed = true)
        val primaryRouteCasingLayer = mockk<LineLayer>(relaxed = true)
        val restrictedLineLayer = mockk<LineLayer>(relaxed = true)
        val topLevelLayer = mockk<LineLayer>(relaxed = true)
        val style = mockk<Style> {
            every {
                getLayer(PRIMARY_ROUTE_TRAFFIC_LAYER_ID)
            } returns primaryRouteTrafficLayer
            every {
                getLayer(PRIMARY_ROUTE_LAYER_ID)
            } returns primaryRouteLayer
            every {
                getLayer(PRIMARY_ROUTE_CASING_LAYER_ID)
            } returns primaryRouteCasingLayer
            every {
                getLayer(RESTRICTED_ROAD_LAYER_ID)
            } returns restrictedLineLayer
            every {
                getLayer(TOP_LEVEL_ROUTE_LINE_LAYER_ID)
            } returns topLevelLayer
        }.also {
            mockCheckForLayerInitialization(it)
        }

        pauseDispatcher {
            MapboxRouteLineView(options).renderRouteLineUpdate(style, state)
            verify { MapboxRouteLineUtils.initializeLayers(style, options) }
        }

        verify { primaryRouteTrafficLayer.lineGradient(trafficLineExp) }
        verify { primaryRouteLayer.lineGradient(routeLineExp) }
        verify { primaryRouteCasingLayer.lineGradient(casingLineEx) }
        verify { restrictedLineLayer.lineGradient(restrictedRoadExp) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
    }

    @Test
    fun renderDrawRouteState() = coroutineRule.runBlockingTest {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkStatic("com.mapbox.maps.extension.style.sources.SourceUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val primaryRouteFeatureCollection =
            FeatureCollection.fromFeatures(listOf(getEmptyFeature()))
        val alternativeRoute1FeatureCollection =
            FeatureCollection.fromFeatures(listOf(getEmptyFeature()))
        val alternativeRoute2FeatureCollection =
            FeatureCollection.fromFeatures(listOf(getEmptyFeature()))
        val waypointsFeatureCollection = FeatureCollection.fromFeatures(listOf(getEmptyFeature()))
        val trafficLineExp = mockk<Expression>()
        val routeLineExp = mockk<Expression>()
        val casingLineEx = mockk<Expression>()
        val alternativeRoute1Expression = mockk<Expression>()
        val alternativeRoute2Expression = mockk<Expression>()
        val restrictedRouteExpression = mockk<Expression>()
        val primaryRouteTrafficLayer = mockk<LineLayer>(relaxed = true)
        val primaryRouteLayer = mockk<LineLayer>(relaxed = true)
        val primaryRouteCasingLayer = mockk<LineLayer>(relaxed = true)
        val altRouteTrafficLayer1 = mockk<LineLayer>(relaxed = true)
        val altRouteTrafficLayer2 = mockk<LineLayer>(relaxed = true)
        val restrictedRouteLayer = mockk<LineLayer>(relaxed = true)
        val topLevelLayer = mockk<LineLayer>(relaxed = true)
        val primaryRouteSource = mockk<GeoJsonSource>(relaxed = true)
        val altRoute1Source = mockk<GeoJsonSource>(relaxed = true)
        val altRoute2Source = mockk<GeoJsonSource>(relaxed = true)
        val wayPointSource = mockk<GeoJsonSource>(relaxed = true)

        val state: Expected<RouteLineError, RouteSetValue> = ExpectedFactory.createValue(
            RouteSetValue(
                primaryRouteLineData = RouteLineData(
                    primaryRouteFeatureCollection,
                    RouteLineDynamicData(
                        { routeLineExp },
                        { casingLineEx },
                        { trafficLineExp },
                        { restrictedRouteExpression }
                    )
                ),
                alternativeRouteLinesData = listOf(
                    RouteLineData(
                        alternativeRoute1FeatureCollection,
                        RouteLineDynamicData(
                            { throw UnsupportedOperationException() },
                            { throw UnsupportedOperationException() },
                            { alternativeRoute1Expression },
                            { throw UnsupportedOperationException() }
                        )
                    ),
                    RouteLineData(
                        alternativeRoute2FeatureCollection,
                        RouteLineDynamicData(
                            { throw UnsupportedOperationException() },
                            { throw UnsupportedOperationException() },
                            { alternativeRoute2Expression },
                            { throw UnsupportedOperationException() }
                        )
                    )
                ),
                waypointsFeatureCollection
            )
        )
        val style = mockk<Style>(relaxed = true) {
            every {
                getLayer(PRIMARY_ROUTE_TRAFFIC_LAYER_ID)
            } returns primaryRouteTrafficLayer
            every {
                getLayer(PRIMARY_ROUTE_LAYER_ID)
            } returns primaryRouteLayer
            every {
                getLayer(PRIMARY_ROUTE_CASING_LAYER_ID)
            } returns primaryRouteCasingLayer
            every {
                getLayer(ALTERNATIVE_ROUTE1_TRAFFIC_LAYER_ID)
            } returns altRouteTrafficLayer1
            every {
                getLayer(ALTERNATIVE_ROUTE2_TRAFFIC_LAYER_ID)
            } returns altRouteTrafficLayer2
            every {
                getLayer(RESTRICTED_ROAD_LAYER_ID)
            } returns restrictedRouteLayer
            every {
                getLayer(TOP_LEVEL_ROUTE_LINE_LAYER_ID)
            } returns topLevelLayer
            every { getSource(PRIMARY_ROUTE_SOURCE_ID) } returns primaryRouteSource
            every { getSource(ALTERNATIVE_ROUTE1_SOURCE_ID) } returns altRoute1Source
            every { getSource(ALTERNATIVE_ROUTE2_SOURCE_ID) } returns altRoute2Source
            every { getSource(WAYPOINT_SOURCE_ID) } returns wayPointSource
        }.also {
            mockCheckForLayerInitialization(it)
        }

        pauseDispatcher {
            MapboxRouteLineView(options).renderRouteDrawData(style, state)
            verify { MapboxRouteLineUtils.initializeLayers(style, options) }
        }

        verify { primaryRouteTrafficLayer.lineGradient(Expression.color(Color.TRANSPARENT)) }
        verify { altRouteTrafficLayer1.lineGradient(Expression.color(Color.TRANSPARENT)) }
        verify { altRouteTrafficLayer2.lineGradient(Expression.color(Color.TRANSPARENT)) }
        verify { primaryRouteTrafficLayer.lineGradient(trafficLineExp) }
        verify { primaryRouteLayer.lineGradient(routeLineExp) }
        verify { primaryRouteCasingLayer.lineGradient(casingLineEx) }
        verify { altRouteTrafficLayer1.lineGradient(alternativeRoute1Expression) }
        verify { altRouteTrafficLayer2.lineGradient(alternativeRoute2Expression) }
        verify { primaryRouteSource.featureCollection(primaryRouteFeatureCollection) }
        verify { altRoute1Source.featureCollection(alternativeRoute1FeatureCollection) }
        verify { altRoute2Source.featureCollection(alternativeRoute2FeatureCollection) }
        verify { wayPointSource.featureCollection(waypointsFeatureCollection) }
        verify { restrictedRouteLayer.lineGradient(restrictedRouteExpression) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        unmockkStatic("com.mapbox.maps.extension.style.sources.SourceUtils")
    }

    @Test
    fun showPrimaryRoute() {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkStatic("com.mapbox.maps.extension.style.sources.SourceUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val primaryRouteTrafficLayer = mockk<LineLayer>(relaxed = true)
        val primaryRouteLayer = mockk<LineLayer>(relaxed = true)
        val primaryRouteCasingLayer = mockk<LineLayer>(relaxed = true)

        val style = mockk<Style> {
            every {
                getLayer(PRIMARY_ROUTE_TRAFFIC_LAYER_ID)
            } returns primaryRouteTrafficLayer
            every { getLayer(PRIMARY_ROUTE_LAYER_ID) } returns primaryRouteLayer
            every {
                getLayer(PRIMARY_ROUTE_CASING_LAYER_ID)
            } returns primaryRouteCasingLayer
        }.also {
            mockCheckForLayerInitialization(it)
        }

        MapboxRouteLineView(options).showPrimaryRoute(style)

        verify { primaryRouteTrafficLayer.visibility(Visibility.VISIBLE) }
        verify { primaryRouteLayer.visibility(Visibility.VISIBLE) }
        verify { primaryRouteCasingLayer.visibility(Visibility.VISIBLE) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        unmockkStatic("com.mapbox.maps.extension.style.sources.SourceUtils")
    }

    @Test
    fun hidePrimaryRoute() {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val trafficLayer = mockk<LineLayer>(relaxed = true)
        val routeLayer = mockk<LineLayer>(relaxed = true)
        val casingLayer = mockk<LineLayer>(relaxed = true)

        val style = mockk<Style> {
            every {
                getLayer(PRIMARY_ROUTE_TRAFFIC_LAYER_ID)
            } returns trafficLayer
            every { getLayer(PRIMARY_ROUTE_LAYER_ID) } returns routeLayer
            every {
                getLayer(PRIMARY_ROUTE_CASING_LAYER_ID)
            } returns casingLayer
        }.also {
            mockCheckForLayerInitialization(it)
        }

        MapboxRouteLineView(options).hidePrimaryRoute(style)

        verify { trafficLayer.visibility(Visibility.NONE) }
        verify { routeLayer.visibility(Visibility.NONE) }
        verify { casingLayer.visibility(Visibility.NONE) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
    }

    @Test
    fun showAlternativeRoutes() {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val altRoute1 = mockk<LineLayer>(relaxed = true)
        val altRouteCasing1 = mockk<LineLayer>(relaxed = true)
        val altRouteTraffic1 = mockk<LineLayer>(relaxed = true)
        val altRoute2 = mockk<LineLayer>(relaxed = true)
        val altRouteCasing2 = mockk<LineLayer>(relaxed = true)
        val altRouteTraffic2 = mockk<LineLayer>(relaxed = true)

        val style = mockk<Style> {
            every {
                getLayer(ALTERNATIVE_ROUTE1_LAYER_ID)
            } returns altRoute1
            every {
                getLayer(ALTERNATIVE_ROUTE1_CASING_LAYER_ID)
            } returns altRouteCasing1
            every {
                getLayer(ALTERNATIVE_ROUTE1_TRAFFIC_LAYER_ID)
            } returns altRouteTraffic1
            every {
                getLayer(ALTERNATIVE_ROUTE2_LAYER_ID)
            } returns altRoute2
            every {
                getLayer(ALTERNATIVE_ROUTE2_CASING_LAYER_ID)
            } returns altRouteCasing2
            every {
                getLayer(ALTERNATIVE_ROUTE2_TRAFFIC_LAYER_ID)
            } returns altRouteTraffic2
        }.also {
            mockCheckForLayerInitialization(it)
        }

        MapboxRouteLineView(options).showAlternativeRoutes(style)

        verify { altRoute1.visibility(Visibility.VISIBLE) }
        verify { altRouteCasing1.visibility(Visibility.VISIBLE) }
        verify { altRouteTraffic1.visibility(Visibility.VISIBLE) }
        verify { altRoute2.visibility(Visibility.VISIBLE) }
        verify { altRouteCasing2.visibility(Visibility.VISIBLE) }
        verify { altRouteTraffic2.visibility(Visibility.VISIBLE) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
    }

    @Test
    fun hideAlternativeRoutes() {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val altRoute1 = mockk<LineLayer>(relaxed = true)
        val altRouteCasing1 = mockk<LineLayer>(relaxed = true)
        val altRouteTraffic1 = mockk<LineLayer>(relaxed = true)
        val altRoute2 = mockk<LineLayer>(relaxed = true)
        val altRouteCasing2 = mockk<LineLayer>(relaxed = true)
        val altRouteTraffic2 = mockk<LineLayer>(relaxed = true)

        val style = mockk<Style> {
            every {
                getLayer(ALTERNATIVE_ROUTE1_LAYER_ID)
            } returns altRoute1
            every {
                getLayer(ALTERNATIVE_ROUTE1_CASING_LAYER_ID)
            } returns altRouteCasing1
            every {
                getLayer(ALTERNATIVE_ROUTE1_TRAFFIC_LAYER_ID)
            } returns altRouteTraffic1
            every {
                getLayer(ALTERNATIVE_ROUTE2_LAYER_ID)
            } returns altRoute2
            every {
                getLayer(ALTERNATIVE_ROUTE2_CASING_LAYER_ID)
            } returns altRouteCasing2
            every {
                getLayer(ALTERNATIVE_ROUTE2_TRAFFIC_LAYER_ID)
            } returns altRouteTraffic2
        }.also {
            mockCheckForLayerInitialization(it)
        }

        MapboxRouteLineView(options).hideAlternativeRoutes(style)

        verify { altRoute1.visibility(Visibility.NONE) }
        verify { altRouteCasing1.visibility(Visibility.NONE) }
        verify { altRouteTraffic1.visibility(Visibility.NONE) }
        verify { altRoute2.visibility(Visibility.NONE) }
        verify { altRouteCasing2.visibility(Visibility.NONE) }
        verify { altRouteTraffic2.visibility(Visibility.NONE) }

        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
    }

    @Test
    fun hideTraffic() {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val primaryRouteTraffic = mockk<LineLayer>(relaxed = true)
        val altRouteTraffic1 = mockk<LineLayer>(relaxed = true)
        val altRouteTraffic2 = mockk<LineLayer>(relaxed = true)
        val style = mockk<Style> {
            every {
                getLayer(PRIMARY_ROUTE_TRAFFIC_LAYER_ID)
            } returns primaryRouteTraffic
            every {
                getLayer(ALTERNATIVE_ROUTE1_TRAFFIC_LAYER_ID)
            } returns altRouteTraffic1
            every {
                getLayer(ALTERNATIVE_ROUTE2_TRAFFIC_LAYER_ID)
            } returns altRouteTraffic2
        }

        MapboxRouteLineView(options).hideTraffic(style)

        verify { primaryRouteTraffic.visibility(Visibility.NONE) }
        verify { altRouteTraffic1.visibility(Visibility.NONE) }
        verify { altRouteTraffic2.visibility(Visibility.NONE) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
    }

    @Test
    fun showTraffic() {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val primaryRouteTraffic = mockk<LineLayer>(relaxed = true)
        val altRouteTraffic1 = mockk<LineLayer>(relaxed = true)
        val altRouteTraffic2 = mockk<LineLayer>(relaxed = true)
        val style = mockk<Style> {
            every {
                getLayer(PRIMARY_ROUTE_TRAFFIC_LAYER_ID)
            } returns primaryRouteTraffic
            every {
                getLayer(ALTERNATIVE_ROUTE1_TRAFFIC_LAYER_ID)
            } returns altRouteTraffic1
            every {
                getLayer(ALTERNATIVE_ROUTE2_TRAFFIC_LAYER_ID)
            } returns altRouteTraffic2
        }

        MapboxRouteLineView(options).showTraffic(style)

        verify { primaryRouteTraffic.visibility(Visibility.VISIBLE) }
        verify { altRouteTraffic1.visibility(Visibility.VISIBLE) }
        verify { altRouteTraffic2.visibility(Visibility.VISIBLE) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
    }

    @Test
    fun getTrafficVisibility() {
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val style = mockk<Style>()
        every {
            MapboxRouteLineUtils.getLayerVisibility(
                style,
                PRIMARY_ROUTE_TRAFFIC_LAYER_ID
            )
        } returns Visibility.VISIBLE

        val result = MapboxRouteLineView(options).getTrafficVisibility(style)

        assertEquals(Visibility.VISIBLE, result)
        unmockkObject(MapboxRouteLineUtils)
    }

    @Test
    fun showOriginAndDestinationPoints() {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val waypointLayer = mockk<LineLayer>(relaxed = true)
        val style = mockk<Style> {
            every { getLayer(WAYPOINT_LAYER_ID) } returns waypointLayer
        }.also {
            mockCheckForLayerInitialization(it)
        }

        MapboxRouteLineView(options).showOriginAndDestinationPoints(style)

        verify { waypointLayer.visibility(Visibility.VISIBLE) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
    }

    @Test
    fun hideOriginAndDestinationPoints() {
        mockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
        mockkObject(MapboxRouteLineUtils)
        val waypointLayer = mockk<LineLayer>(relaxed = true)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val style = mockk<Style> {
            every { getLayer(WAYPOINT_LAYER_ID) } returns waypointLayer
        }.also {
            mockCheckForLayerInitialization(it)
        }

        MapboxRouteLineView(options).hideOriginAndDestinationPoints(style)

        verify { waypointLayer.visibility(Visibility.NONE) }
        unmockkObject(MapboxRouteLineUtils)
        unmockkStatic("com.mapbox.maps.extension.style.layers.LayerUtils")
    }

    @Test
    fun getPrimaryRouteVisibility() {
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val style = mockk<Style>()
        every {
            MapboxRouteLineUtils.getLayerVisibility(
                style,
                PRIMARY_ROUTE_LAYER_ID
            )
        } returns Visibility.VISIBLE

        val result = MapboxRouteLineView(options).getPrimaryRouteVisibility(style)

        assertEquals(Visibility.VISIBLE, result)
        unmockkObject(MapboxRouteLineUtils)
    }

    @Test
    fun getAlternativeRoutesVisibility() {
        mockkObject(MapboxRouteLineUtils)
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val style = mockk<Style>()
        every {
            MapboxRouteLineUtils.getLayerVisibility(
                style,
                ALTERNATIVE_ROUTE1_LAYER_ID
            )
        } returns Visibility.VISIBLE

        val result = MapboxRouteLineView(options).getAlternativeRoutesVisibility(style)

        assertEquals(Visibility.VISIBLE, result)
        unmockkObject(MapboxRouteLineUtils)
    }

    @Test
    fun cancel() {
        val options = MapboxRouteLineOptions.Builder(ctx).build()
        val mockParentJob = mockk<CompletableJob>(relaxed = true)
        val mockJobControl = mockk<JobControl> {
            every { job } returns mockParentJob
        }
        every { InternalJobControlFactory.createDefaultScopeJobControl() } returns mockJobControl

        MapboxRouteLineView(options).cancel()

        verify { mockParentJob.cancelChildren() }
    }

    private fun getEmptyFeature(): Feature {
        return Feature.fromJson(
            "{\"type\":\"Feature\",\"id\":\"${UUID.randomUUID()}\"," +
                "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[]}}"
        )
    }
}
