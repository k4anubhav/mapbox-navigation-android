package com.mapbox.navigation.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mapbox.base.common.logger.model.Message
import com.mapbox.base.common.logger.model.Tag
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.utils.internal.logI
import java.util.concurrent.CopyOnWriteArraySet

@ExperimentalPreviewMapboxNavigationAPI
internal class MapboxNavigationOwner {
    internal lateinit var navigationOptions: NavigationOptions
        private set

    private val services = CopyOnWriteArraySet<MapboxNavigationObserver>()
    private var mapboxNavigation: MapboxNavigation? = null
    private var attached = false

    internal val carAppLifecycleObserver = object : DefaultLifecycleObserver {

        override fun onStart(owner: LifecycleOwner) {
            logI(TAG, Message("onStart"))
            check(!MapboxNavigationProvider.isCreated()) {
                "MapboxNavigation should only be created by the MapboxNavigationOwner"
            }
            val mapboxNavigation = MapboxNavigationProvider.create(navigationOptions)
            this@MapboxNavigationOwner.mapboxNavigation = mapboxNavigation
            attached = true
            services.forEach { it.onAttached(mapboxNavigation) }
        }

        override fun onStop(owner: LifecycleOwner) {
            logI(TAG, Message("onStop"))
            attached = false
            services.forEach { it.onDetached(mapboxNavigation!!) }
            MapboxNavigationProvider.destroy()
            mapboxNavigation = null
        }
    }

    fun setup(navigationOptions: NavigationOptions) {
        this.navigationOptions = navigationOptions
    }

    fun register(mapboxNavigationObserver: MapboxNavigationObserver) = apply {
        mapboxNavigation?.let { mapboxNavigationObserver.onAttached(it) }
        services.add(mapboxNavigationObserver)
    }

    fun unregister(mapboxNavigationObserver: MapboxNavigationObserver) {
        mapboxNavigation?.let { mapboxNavigationObserver.onDetached(it) }
        services.remove(mapboxNavigationObserver)
    }

    fun disable() {
        if (attached) {
            attached = false
            services.forEach { it.onDetached(mapboxNavigation!!) }
            MapboxNavigationProvider.destroy()
        }
    }

    fun current(): MapboxNavigation? = mapboxNavigation

    private companion object {
        private val TAG = Tag("MbxNavigationOwner")
    }
}
