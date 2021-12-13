package com.mapbox.navigation.examples.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.bindgen.Expected
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.dropin.MapboxNavigationViewApi
import com.mapbox.navigation.dropin.ViewProvider
import com.mapbox.navigation.examples.core.databinding.LayoutActivityDropInBinding
import java.lang.ref.WeakReference

class MapboxDropInActivity : AppCompatActivity() {

    private lateinit var mapboxNavigationViewApi: MapboxNavigationViewApi

    private lateinit var binding: LayoutActivityDropInBinding

    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutActivityDropInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapboxNavigationViewApi = binding.navigationView.navigationViewApi
        mapboxNavigationViewApi.getOptions()
            .toBuilder(this)
            .useReplayEngine(true)
            .build().apply {
                mapboxNavigationViewApi.update(this)
            }
        mapboxNavigationViewApi.configureNavigationView(ViewProvider())

        binding.tempStartNavigation.setOnClickListener {
            mapboxNavigationViewApi.temporaryStartNavigation()
        }

        mapboxNavigationViewApi.getMapView()
            .gestures
            .addOnMapLongClickListener(onMapLongClickListener)
    }

    private val onMapLongClickListener = OnMapLongClickListener { point ->
        getLastLocation(
            this@MapboxDropInActivity,
            WeakReference<(Expected<Exception, LocationEngineResult>) -> Unit> { result ->
                result.fold(
                    {
                        Log.e(TAG, "Error obtaining current location", it)
                    },
                    {
                        it.lastLocation?.let { currentLocation ->
                            mapboxNavigationViewApi.fetchAndSetRoute(
                                listOf(
                                    Point.fromLngLat(
                                        currentLocation.longitude,
                                        currentLocation.latitude
                                    ),
                                    point
                                )
                            )
                        }
                    }
                )
            }
        )
        false
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocation(
        context: Context,
        resultConsumer: WeakReference<(Expected<Exception, LocationEngineResult>) -> Unit>
    ) {
        LocationEngineProvider.getBestLocationEngine(context.applicationContext).getLastLocation(
            object : LocationEngineCallback<LocationEngineResult> {
                override fun onSuccess(p0: LocationEngineResult) {
                    resultConsumer.get()?.invoke(ExpectedFactory.createValue(p0))
                }

                override fun onFailure(p0: Exception) {
                    resultConsumer.get()?.invoke(ExpectedFactory.createError(p0))
                }
            }
        )
    }

    companion object {
        private const val TAG = "MapboxDropInActivity"
    }
}
