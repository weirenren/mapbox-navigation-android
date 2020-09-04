package com.mapbox.navigation.core.telemetry

import android.location.Location
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.telemetry.NewRoute.ExternalRoute
import com.mapbox.navigation.core.telemetry.NewRoute.RerouteRoute
import com.mapbox.navigation.testing.MainCoroutineRule
import io.mockk.mockk
import junit.framework.TestCase.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class TelemetryLocationAndProgressDispatcherTest {
    @get:Rule
    var coroutineRule = MainCoroutineRule()

    private val routeProgress: RouteProgress = mockk(relaxed = true)
    private val route: DirectionsRoute = mockk(relaxed = true)
    private val routes: List<DirectionsRoute> = listOf(route)
    private val callbackDispatcher =
        TelemetryLocationAndProgressDispatcherImpl(coroutineRule.coroutineScope)

    @Before
    fun setUp() {
        callbackDispatcher.onRoutesChanged(routes) // set originalRoute
    }

    @Test
    fun ignoreEnhancedLocationUpdates() {
        callbackDispatcher.onEnhancedLocationChanged(mockk(), mockk())

        assertNull(callbackDispatcher.lastLocation)
    }

    @Test
    fun useRawLocationUpdates() {
        val rawLocation: Location = mockk()
        callbackDispatcher.onRawLocationChanged(rawLocation)

        assertEquals(rawLocation, callbackDispatcher.lastLocation)
    }

    @Test
    fun originalRouteSetOnlyOnce() = runBlocking {
        callbackDispatcher.onRoutesChanged(routes)
        callbackDispatcher.onRoutesChanged(listOf(mockk()))
        callbackDispatcher.onRoutesChanged(listOf(mockk()))

        assertEquals(route, callbackDispatcher.originalRoute.await())
    }

    @Test
    fun when_Reroute_RerouteRoute_PassedToChannel() = runBlocking {
        callbackDispatcher.onOffRouteStateChanged(true)
        callbackDispatcher.onRoutesChanged(routes)

        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as RerouteRoute).route)
    }

    @Test
    fun when_RaceReroute_RerouteRoute_PassedToChannel() = runBlocking {
        callbackDispatcher.onOffRouteStateChanged(true)
        callbackDispatcher.onOffRouteStateChanged(false)
        callbackDispatcher.onRoutesChanged(routes)

        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as RerouteRoute).route)
    }

    @Test
    fun when_RouteSetExternally_ExternalRoute_PassedToChannel() = runBlocking {
        callbackDispatcher.onRoutesChanged(routes)

        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as ExternalRoute).route)
    }

    @Test
    fun valid_Routes_PassedToChannel() = runBlocking {
        callbackDispatcher.onRoutesChanged(routes)
        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as ExternalRoute).route)

        callbackDispatcher.onRoutesChanged(routes)
        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as ExternalRoute).route)

        callbackDispatcher.onOffRouteStateChanged(true)
        callbackDispatcher.onRoutesChanged(routes)
        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as RerouteRoute).route)

        callbackDispatcher.onRoutesChanged(routes)
        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as ExternalRoute).route)

        callbackDispatcher.onOffRouteStateChanged(false)
        callbackDispatcher.onRoutesChanged(routes)
        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as ExternalRoute).route)

        callbackDispatcher.onOffRouteStateChanged(true)
        callbackDispatcher.onRoutesChanged(routes)
        assertEquals(route, (callbackDispatcher.newRouteChannel.receive() as RerouteRoute).route)
    }

    @Test
    fun lastLocation() = runBlocking {
        val firstLocation = mockk<Location>()
        val secondLocation = mockk<Location>()

        callbackDispatcher.onRawLocationChanged(firstLocation)
        assertEquals(firstLocation, callbackDispatcher.lastLocation)

        callbackDispatcher.onRawLocationChanged(secondLocation)
        assertEquals(secondLocation, callbackDispatcher.lastLocation)
    }

    @Test
    fun preAndPostLocationsOrder() = runBlocking {
        val preEventLocation = mockk<Location>()
        val postEventLocation = mockk<Location>()

        callbackDispatcher.onRawLocationChanged(preEventLocation)
        callbackDispatcher.accumulatePostEventLocations { preEventLocations, postEventLocations ->
            assertEquals(1, preEventLocations.size)
            assertEquals(preEventLocation, preEventLocations[0])

            assertEquals(1, postEventLocations.size)
            assertEquals(postEventLocation, postEventLocations[0])
        }
        callbackDispatcher.onRawLocationChanged(postEventLocation)
        callbackDispatcher.clearLocationEventBuffer()
    }

    @Test
    fun preAndPostLocationsMaxSize() = runBlocking {
        repeat(25) { callbackDispatcher.onRawLocationChanged(mockk()) }
        callbackDispatcher.accumulatePostEventLocations { preEventLocations, postEventLocations ->
            assertEquals(20, preEventLocations.size)
            assertEquals(20, postEventLocations.size)
        }
        repeat(25) { callbackDispatcher.onRawLocationChanged(mockk()) }
        callbackDispatcher.clearLocationEventBuffer()
    }

    @Test
    fun routeProgress() = runBlocking {
        callbackDispatcher.onRouteProgressChanged(routeProgress)
        assertEquals(routeProgress, callbackDispatcher.routeProgress)
        assertEquals(routeProgress, callbackDispatcher.routeProgressChannel.receive())
    }

    @Test
    fun resetRouteProgress() = runBlocking {
        callbackDispatcher.onRouteProgressChanged(routeProgress)
        callbackDispatcher.resetRouteProgress()
        assertNull(callbackDispatcher.routeProgress)
        assertNull(callbackDispatcher.routeProgressChannel.poll())
    }

    @Test
    fun resetOriginalRoute() = runBlocking {
        callbackDispatcher.resetOriginalRoute()
        assertFalse(callbackDispatcher.originalRoute.isCompleted)
    }

    @Test
    fun resetOriginalRouteWithNewOne() = runBlocking {
        val route: DirectionsRoute = mockk()
        callbackDispatcher.resetOriginalRoute(route)
        assertTrue(callbackDispatcher.originalRoute.isCompleted)
        assertEquals(route, callbackDispatcher.originalRoute.await())
    }

    @Test
    fun proPostLocationsEvents() = runBlocking {
        val l = mutableListOf<Location>()
        repeat(42) { l.add(mockk()) }

        // before any location posted. preList will be empty. postList will have 20 items
        callbackDispatcher.accumulatePostEventLocations { preLocations, postLocations ->
            val preList = emptyList<Location>()
            val postList = mutableListOf<Location>().apply { for (i in 0 until 20) add(l[i]) }
            assertEquals(preList, preLocations)
            assertEquals(postList, postLocations)
        }

        for (i in 0 until 5) callbackDispatcher.onRawLocationChanged(l[i])

        // 5 locations posted. preList will have all of them. postList will have 20 items
        callbackDispatcher.accumulatePostEventLocations { preLocations, postLocations ->
            val preList = mutableListOf<Location>().apply { for (i in 0 until 5) add(l[i]) }
            val postList = mutableListOf<Location>().apply { for (i in 5 until 25) add(l[i]) }
            assertEquals(preList, preLocations)
            assertEquals(postList, postLocations)
        }

        for (i in 5 until 17) callbackDispatcher.onRawLocationChanged(l[i])

        // 17 locations posted. preList will have all of them. postList will have 20 items
        callbackDispatcher.accumulatePostEventLocations { preLocations, postLocations ->
            val preList = mutableListOf<Location>().apply { for (i in 0 until 17) add(l[i]) }
            val postList = mutableListOf<Location>().apply { for (i in 17 until 37) add(l[i]) }
            assertEquals(preList, preLocations)
            assertEquals(postList, postLocations)
        }

        for (i in 17 until 29) callbackDispatcher.onRawLocationChanged(l[i])

        // 29 locations posted. preList will have the last 20. postList will have 13 items
        callbackDispatcher.accumulatePostEventLocations { preLocations, postLocations ->
            val preList = mutableListOf<Location>().apply { for (i in 9 until 29) add(l[i]) }
            val postList = mutableListOf<Location>().apply { for (i in 29 until 42) add(l[i]) }
            assertEquals(preList, preLocations)
            assertEquals(postList, postLocations)
        }

        for (i in 29 until 42) callbackDispatcher.onRawLocationChanged(l[i])

        // 42 locations posted. preList will have the last 20. postList will be empty
        callbackDispatcher.accumulatePostEventLocations { preLocations, postLocations ->
            val preList = mutableListOf<Location>().apply { for (i in 22 until 42) add(l[i]) }
            val postList = emptyList<Location>()
            assertEquals(preList, preLocations)
            assertEquals(postList, postLocations)
        }

        callbackDispatcher.clearLocationEventBuffer()
    }
}
