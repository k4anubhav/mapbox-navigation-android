package com.mapbox.navigation.core.geodeeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeoDeeplinkParserTest {

    @Test
    fun `correct geo string`() {
        listOf(
            GeoTestParams(
                "geo:37.788151,-122.407543",
                37.788151,
                -122.407543
            ),
            GeoTestParams(
                "geo:37.788151, -122.407543",
                37.788151,
                -122.407543
            ),
            GeoTestParams(
                "geo:37.788151,%20-122.407543",
                37.788151,
                -122.407543
            ),
            GeoTestParams(
                "geo:37.79576,-122.39395?q=1 Ferry Building, San Francisco, CA 94111",
                37.79576,
                -122.39395,
                "1 Ferry Building, San Francisco, CA 94111"
            ),
            GeoTestParams(
                "geo:0.0,-62.785138",
                0.0,
                -62.785138
            ),
            GeoTestParams(
                "geo:37.788151,0.0",
                37.788151,
                0.0
            ),
            GeoTestParams(
                // Multi-lined to fix ktlint max_line_length
                "geo:0,0?q=%E5%93%81%E5%B7%9D%E5%8C%BA%E5%A4%A7%E4%BA%95%206-16-16%2" +
                    "0%E3%83%A1%E3%82%BE%E3%83%B3%E9%B9%BF%E5%B3%B6%E3%81%AE%E7%A2" +
                    "%A7201%4035.595404%2C139.731737",
                35.595404,
                139.731737,
                "品川区大井 6-16-16 メゾン鹿島の碧201"
            ),
            GeoTestParams(
                "geo:0,0?q=54.356152,18.642736(ul. 3 maja 12, 80-802 Gdansk, Poland)",
                54.356152,
                18.642736,
                placeQuery = "ul. 3 maja 12, 80-802 Gdansk, Poland"
            ),
            GeoTestParams(
                "geo:0,0?q=1600 Amphitheatre Parkway, Mountain+View, California",
                latitude = null,
                longitude = null,
                placeQuery = "1600 Amphitheatre Parkway, Mountain View, California"
            ),
            GeoTestParams(
                "geo:0,0?q=Coffee Shop@37.757527,-122.392937",
                37.757527,
                -122.392937,
                "Coffee Shop"
            ),
        ).forEach { expected ->
            val geoDeeplink = GeoDeeplinkParser.parse(expected.dataString)

            assertNotNull(geoDeeplink)
            assertEquals(expected.latitude, geoDeeplink?.point?.latitude())
            assertEquals(expected.longitude, geoDeeplink?.point?.longitude())
            assertEquals(expected.placeQuery, geoDeeplink?.placeQuery)
        }
    }

    @Test
    fun `incorrect geo string`() {
        listOf(
            GeoTestParams("geo:0,0"),
            GeoTestParams("geo:,"),
            GeoTestParams("geo:,35.595404"),
            GeoTestParams("geo:35.595404,")
        ).forEach { params ->
            val geoDeeplink = GeoDeeplinkParser.parse(params.dataString)

            assertNull(geoDeeplink)
        }
    }
}

data class GeoTestParams(
    val dataString: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeQuery: String? = null
)
