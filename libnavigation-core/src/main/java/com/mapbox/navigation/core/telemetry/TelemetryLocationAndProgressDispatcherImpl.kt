package com.mapbox.navigation.core.telemetry

import android.location.Location
import android.util.Log
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.telemetry.MapboxNavigationTelemetry.TAG
import com.mapbox.navigation.core.telemetry.NewRoute.ExternalRoute
import com.mapbox.navigation.core.telemetry.NewRoute.RerouteRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections.synchronizedList

internal class TelemetryLocationAndProgressDispatcherImpl(
    private val scope: CoroutineScope
) :
    TelemetryLocationAndProgressDispatcher {

    companion object {
        private const val LOCATION_BUFFER_MAX_SIZE = 20
    }

    private val locationsBuffer = synchronizedList(mutableListOf<Location>())
    private val eventsLocationsBuffer = synchronizedList(mutableListOf<EventLocations>())

    override val routeProgressChannel = Channel<RouteProgress>(Channel.CONFLATED)
    override val newRouteChannel = Channel<NewRoute>(Channel.CONFLATED)

    override val lastLocation: Location?
        get() = locationsBuffer.lastOrNull()
    override var firstLocation: Location? = null
    override var routeProgress: RouteProgress? = null
    override var originalRoute = CompletableDeferred<DirectionsRoute>()
    private val mutex = Mutex()
    private var needHandleReroute = false

    private suspend fun accumulatePostEventLocation(location: Location) {
        mutex.withLock {
            val iterator = eventsLocationsBuffer.iterator()
            while (iterator.hasNext()) {
                iterator.next().let {
                    it.addPostEventLocation(location)
                    if (it.postEventLocationsSize() >= LOCATION_BUFFER_MAX_SIZE) {
                        it.onBufferFull()
                        iterator.remove()
                    }
                }
            }
        }
    }

    private suspend fun flushLocationEventBuffer() {
        Log.d(TAG, "flushing eventsLocationsBuffer. Pending events = ${eventsLocationsBuffer.size}")
        eventsLocationsBuffer.forEach { it.onBufferFull() }
    }

    private fun accumulateLocation(location: Location) {
        locationsBuffer.run {
            if (size >= LOCATION_BUFFER_MAX_SIZE) {
                removeAt(0)
            }
            add(location)
        }
    }

    override fun accumulatePostEventLocations(
        onBufferFull: suspend (List<Location>, List<Location>) -> Unit
    ) {
        eventsLocationsBuffer.add(
            EventLocations(
                locationsBuffer.getCopy(),
                mutableListOf(),
                onBufferFull
            )
        )
    }

    override suspend fun clearLocationEventBuffer() {
        flushLocationEventBuffer()
        eventsLocationsBuffer.clear()
    }

    override fun onRouteProgressChanged(routeProgress: RouteProgress) {
        Log.d(TAG, "route progress state = ${routeProgress.currentState}")
        this.routeProgress = routeProgress
        routeProgressChannel.offer(routeProgress)
    }

    override fun resetOriginalRoute(route: DirectionsRoute?) {
        originalRoute = if (route != null) {
            CompletableDeferred(route)
        } else {
            CompletableDeferred()
        }
    }

    override fun resetRouteProgress() {
        routeProgress = null
        routeProgressChannel.poll() // remove element from the channel if exists
    }

    override fun onRawLocationChanged(rawLocation: Location) {
        scope.launch {
            accumulateLocation(rawLocation)
            accumulatePostEventLocation(rawLocation)
            if (firstLocation == null) {
                Log.d(TAG, "set first location")
                firstLocation = rawLocation
            }
        }
    }

    override fun onEnhancedLocationChanged(enhancedLocation: Location, keyPoints: List<Location>) {
        // Do nothing
    }

    override fun onRoutesChanged(routes: List<DirectionsRoute>) {
        Log.d(TAG, "onRoutesChanged received. Route list size = ${routes.size}")
        routes.getOrNull(0)?.let {
            if (originalRoute.isCompleted) {
                newRouteChannel.offer(
                    if (needHandleReroute) {
                        needHandleReroute = false
                        RerouteRoute(it)
                    } else {
                        ExternalRoute(it)
                    }
                )
            }
            originalRoute.complete(it)
        }
    }

    override fun onOffRouteStateChanged(offRoute: Boolean) {
        Log.d(TAG, "onOffRouteStateChanged $offRoute")
        if (offRoute) {
            needHandleReroute = true
        }
    }

    @Synchronized
    private fun <T> MutableList<T>.getCopy(): List<T> {
        return mutableListOf<T>().also {
            it.addAll(this)
        }
    }
}
