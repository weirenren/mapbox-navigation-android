package com.mapbox.navigation.core.telemetry

import android.location.Location
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

internal interface TelemetryLocationAndProgressDispatcher :
    RouteProgressObserver, LocationObserver, RoutesObserver, OffRouteObserver {
    val newRouteChannel: ReceiveChannel<NewRoute>
    val routeProgressChannel: ReceiveChannel<RouteProgress>

    val lastLocation: Location?
    val routeProgress: RouteProgress?
    val firstLocation: Location?
    val originalRoute: Deferred<DirectionsRoute>

    suspend fun clearLocationEventBuffer()
    fun resetOriginalRoute(route: DirectionsRoute? = null)
    fun resetRouteProgress()

    fun accumulatePostEventLocations(onBufferFull: suspend (List<Location>, List<Location>) -> Unit)
}

internal sealed class NewRoute {
    data class ExternalRoute(val route: DirectionsRoute) : NewRoute()
    data class RerouteRoute(val route: DirectionsRoute) : NewRoute()
}
