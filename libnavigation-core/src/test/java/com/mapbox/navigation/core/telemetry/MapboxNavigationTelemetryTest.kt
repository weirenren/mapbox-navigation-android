package com.mapbox.navigation.core.telemetry

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.media.AudioManager
import android.telephony.TelephonyManager
import com.mapbox.android.telemetry.AppUserTurnstile
import com.mapbox.android.telemetry.MapboxTelemetryConstants
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.metrics.MetricEvent
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState.ROUTE_COMPLETE
import com.mapbox.navigation.base.trip.model.RouteStepProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.NavigationSession.State.ACTIVE_GUIDANCE
import com.mapbox.navigation.core.NavigationSession.State.FREE_DRIVE
import com.mapbox.navigation.core.NavigationSessionStateObserver
import com.mapbox.navigation.core.telemetry.NewRoute.ExternalRoute
import com.mapbox.navigation.core.telemetry.NewRoute.RerouteRoute
import com.mapbox.navigation.core.telemetry.events.NavigationArriveEvent
import com.mapbox.navigation.core.telemetry.events.NavigationCancelEvent
import com.mapbox.navigation.core.telemetry.events.NavigationDepartEvent
import com.mapbox.navigation.core.telemetry.events.NavigationFeedbackEvent
import com.mapbox.navigation.core.telemetry.events.NavigationRerouteEvent
import com.mapbox.navigation.metrics.MapboxMetricsReporter
import com.mapbox.navigation.metrics.internal.event.NavigationAppUserTurnstileEvent
import com.mapbox.navigation.testing.MainCoroutineRule
import com.mapbox.navigation.utils.internal.JobControl
import com.mapbox.navigation.utils.internal.ThreadController
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@InternalCoroutinesApi
@ExperimentalCoroutinesApi
class MapboxNavigationTelemetryTest {

    companion object {
        private const val FIRST_LOCATION_LAT = 123.1
        private const val FIRST_LOCATION_LON = 222.2
        private const val LAST_LOCATION_LAT = 55.5
        private const val LAST_LOCATION_LON = 88.8

        private const val ORIGINAL_ROUTE_GEOMETRY = ""
        private const val ORIGINAL_ROUTE_DISTANCE = 1.1
        private const val ORIGINAL_ROUTE_DURATION = 2.2
        private const val ORIGINAL_ROUTE_ROUTE_INDEX = "10"

        private const val ORIGINAL_ROUTE_OPTIONS_PROFILE = "original_profile"
        private const val ORIGINAL_ROUTE_OPTIONS_REQUEST_UUID = "original_requestUuid"

        private const val PROGRESS_ROUTE_GEOMETRY = ""
        private const val PROGRESS_ROUTE_ROUTE_INDEX = "1"
        private const val PROGRESS_ROUTE_DISTANCE = 123.1
        private const val PROGRESS_ROUTE_DURATION = 235.2

        private const val PROGRESS_ROUTE_OPTIONS_PROFILE = "progress_profile"
        private const val PROGRESS_ROUTE_OPTIONS_REQUEST_UUID = "progress_requestUuid"

        private const val ROUTE_PROGRESS_DISTANCE_REMAINING = 11f
        private const val ROUTE_PROGRESS_DURATION_REMAINING = 22.22
        private const val ROUTE_PROGRESS_DISTANCE_TRAVELED = 15f

        private const val ORIGINAL_STEP_MANEUVER_LOCATION_LATITUDE = 135.21
        private const val ORIGINAL_STEP_MANEUVER_LOCATION_LONGITUDE = 436.5
        private const val PROGRESS_STEP_MANEUVER_LOCATION_LATITUDE = 42.2
        private const val PROGRESS_STEP_MANEUVER_LOCATION_LONGITUDE = 12.4

        private const val STEP_INDEX = 5
        private const val SDK_IDENTIFIER = "mapbox-navigation-android"
    }

    @get:Rule
    var coroutineRule = MainCoroutineRule()

    private val context: Context = mockk(relaxed = true)
    private val applicationContext: Context = mockk(relaxed = true)
    private val mapboxNavigation = mockk<MapboxNavigation>(relaxed = true)
    private val navigationOptions: NavigationOptions = mockk(relaxed = true)
    private val callbackDispatcher: TelemetryLocationAndProgressDispatcher = mockk()
    private val parentJob = SupervisorJob()
    private val testScope = CoroutineScope(parentJob + coroutineRule.testDispatcher)
    private val mainJobControl = JobControl(parentJob, testScope)
    private val ioJobControl = JobControl(parentJob, testScope)
    private val sessionObserverSlot = slot<NavigationSessionStateObserver>()
    private val routeProgressChannel = Channel<RouteProgress>(Channel.CONFLATED)
    private val newRouteChannel = Channel<NewRoute>(Channel.CONFLATED)
    private val routeProgress = mockk<RouteProgress>()
    private val originalRoute = mockk<DirectionsRoute>()
    private val routeFromProgress = mockk<DirectionsRoute>()
    private val firstLocation = mockk<Location>()
    private val lastLocation = mockk<Location>()
    private val originalRouteOptions = mockk<RouteOptions>()
    private val progressRouteOptions = mockk<RouteOptions>()
    private val originalRouteLeg = mockk<RouteLeg>()
    private val progressRouteLeg = mockk<RouteLeg>()
    private val originalRouteStep = mockk<LegStep>()
    private val progressRouteStep = mockk<LegStep>()
    private val originalRouteSteps = listOf(originalRouteStep)
    private val progressRouteSteps = listOf(progressRouteStep)
    private val originalRouteLegs = listOf(originalRouteLeg)
    private val progressRouteLegs = listOf(progressRouteLeg)
    private val originalStepManeuver = mockk<StepManeuver>()
    private val progressStepManeuver = mockk<StepManeuver>()
    private val originalStepManeuverLocation = mockk<Point>()
    private val progressStepManeuverLocation = mockk<Point>()
    private val legProgress = mockk<RouteLegProgress>()
    private val stepProgress = mockk<RouteStepProgress>()

    @Before
    fun setup() {
        initMapboxMetricsReporter()

        mockkObject(ThreadController)
        mockkObject(MapboxMetricsReporter)

        every { MapboxMetricsReporter.addEvent(any()) } just Runs

        every { ThreadController.getIOScopeAndRootJob() } returns ioJobControl
        every { ThreadController.getMainScopeAndRootJob() } returns mainJobControl
        every { ThreadController.IODispatcher } returns coroutineRule.testDispatcher

        every { navigationOptions.applicationContext } returns applicationContext
        every { context.applicationContext } returns applicationContext

        val audioManager = mockk<AudioManager>()
        every {
            applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        } returns audioManager
        every { audioManager.getStreamVolume(any()) } returns 1
        every { audioManager.getStreamMaxVolume(any()) } returns 2
        every { audioManager.isBluetoothScoOn } returns true

        val telephonyManager = mockk<TelephonyManager>()
        every {
            applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        } returns telephonyManager
        every { telephonyManager.dataNetworkType } returns 5
        every { telephonyManager.networkType } returns 6

        val activityManager = mockk<ActivityManager>()
        every {
            applicationContext.getSystemService(Context.ACTIVITY_SERVICE)
        } returns activityManager
        every { activityManager.runningAppProcesses } returns listOf()

        every {
            mapboxNavigation.registerNavigationSessionObserver(capture(sessionObserverSlot))
        } just Runs

        coEvery { callbackDispatcher.originalRoute } returns CompletableDeferred(originalRoute)
        every { originalRoute.geometry() } returns ORIGINAL_ROUTE_GEOMETRY
        every { originalRoute.legs() } returns originalRouteLegs
        every { originalRoute.distance() } returns ORIGINAL_ROUTE_DISTANCE
        every { originalRoute.duration() } returns ORIGINAL_ROUTE_DURATION
        every { originalRoute.routeOptions() } returns originalRouteOptions
        every { originalRoute.routeIndex() } returns ORIGINAL_ROUTE_ROUTE_INDEX
        every { originalRouteOptions.profile() } returns ORIGINAL_ROUTE_OPTIONS_PROFILE
        every { originalRouteLeg.steps() } returns originalRouteSteps
        every { originalRouteStep.maneuver() } returns originalStepManeuver
        every { originalStepManeuver.location() } returns originalStepManeuverLocation
        every { originalStepManeuverLocation.latitude() } returns
            ORIGINAL_STEP_MANEUVER_LOCATION_LATITUDE
        every { originalStepManeuverLocation.longitude() } returns
            ORIGINAL_STEP_MANEUVER_LOCATION_LONGITUDE
        every { originalRouteOptions.requestUuid() } returns
            ORIGINAL_ROUTE_OPTIONS_REQUEST_UUID

        every { callbackDispatcher.firstLocation } returns firstLocation
        every { callbackDispatcher.lastLocation } returns lastLocation
        every { firstLocation.latitude } returns FIRST_LOCATION_LAT
        every { firstLocation.longitude } returns FIRST_LOCATION_LON
        every { lastLocation.latitude } returns LAST_LOCATION_LAT
        every { lastLocation.longitude } returns LAST_LOCATION_LON

        every { callbackDispatcher.routeProgress } returns routeProgress
        every { routeProgress.currentLegProgress } returns legProgress
        every { routeProgress.distanceRemaining } returns ROUTE_PROGRESS_DISTANCE_REMAINING
        every { routeProgress.durationRemaining } returns ROUTE_PROGRESS_DURATION_REMAINING
        every { routeProgress.distanceTraveled } returns ROUTE_PROGRESS_DISTANCE_TRAVELED
        every { routeProgress.route } returns routeFromProgress

        every { routeFromProgress.geometry() } returns PROGRESS_ROUTE_GEOMETRY
        every { routeFromProgress.distance() } returns PROGRESS_ROUTE_DISTANCE
        every { routeFromProgress.duration() } returns PROGRESS_ROUTE_DURATION
        every { routeFromProgress.legs() } returns progressRouteLegs
        every { routeFromProgress.routeIndex() } returns PROGRESS_ROUTE_ROUTE_INDEX
        every { routeFromProgress.routeOptions() } returns progressRouteOptions
        every { progressRouteOptions.profile() } returns PROGRESS_ROUTE_OPTIONS_PROFILE
        every { progressRouteOptions.requestUuid() } returns PROGRESS_ROUTE_OPTIONS_REQUEST_UUID
        every { progressRouteLeg.steps() } returns progressRouteSteps
        every { progressRouteStep.maneuver() } returns progressStepManeuver
        every { progressStepManeuver.location() } returns progressStepManeuverLocation
        every { progressStepManeuverLocation.latitude() } returns
            PROGRESS_STEP_MANEUVER_LOCATION_LATITUDE
        every { progressStepManeuverLocation.longitude() } returns
            PROGRESS_STEP_MANEUVER_LOCATION_LONGITUDE

        every { legProgress.currentStepProgress } returns stepProgress
        every { legProgress.upcomingStep } returns null
        every { legProgress.legIndex } returns 0
        every { legProgress.routeLeg } returns null
        every { stepProgress.stepIndex } returns STEP_INDEX
        every { stepProgress.step } returns null
        every { stepProgress.distanceRemaining } returns 0f
        every { stepProgress.durationRemaining } returns 0.0

        every { callbackDispatcher.routeProgressChannel } returns routeProgressChannel
        every { callbackDispatcher.newRouteChannel } returns newRouteChannel
        every { callbackDispatcher.resetRouteProgress() } just Runs
        every { callbackDispatcher.resetOriginalRoute(any()) } just Runs
        coEvery { callbackDispatcher.clearLocationEventBuffer() } just Runs
    }

    /**
     * Inside MapboxNavigationTelemetry.initialize method we call postTurnstileEvent and build
     * AppUserTurnstile. It checks a static context field inside MapboxTelemetry.
     * To set that context field we need to init MapboxTelemetry.
     * It is done inside MapboxMetricsReporter.
     * After that method we mock MapboxMetricsReporter to use it in tests.
     */
    private fun initMapboxMetricsReporter() {
        val alarmManager = mockk<AlarmManager>()
        every {
            applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        } returns alarmManager
        every { context.applicationContext } returns applicationContext

        val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
        every {
            applicationContext.getSharedPreferences(
                MapboxTelemetryConstants.MAPBOX_SHARED_PREFERENCES,
                Context.MODE_PRIVATE
            )
        } returns sharedPreferences
        every {
            sharedPreferences.getString("mapboxTelemetryState", "ENABLED")
        } returns "DISABLED"

        MapboxMetricsReporter.init(context, "pk.token", "userAgent")
    }

    @After
    fun cleanUp() {
        parentJob.cancel()

        unmockkObject(ThreadController)
        unmockkObject(MapboxMetricsReporter)
    }

    @Test
    fun turnstileEvent_sent_on_telemetry_init() = runBlocking {
        initTelemetry()

        val eventSlot = slot<MetricEvent>()
        verify(exactly = 1) { MapboxMetricsReporter.addEvent(capture(eventSlot)) }
        assertTrue(eventSlot.captured is NavigationAppUserTurnstileEvent)

        val actualEvent = eventSlot.captured as NavigationAppUserTurnstileEvent
        val expectedTurnstileEvent = AppUserTurnstile("mock", "mock").also { it.setSkuId("08") }

        // there is only one accessible field - skuId
        assertEquals(expectedTurnstileEvent.skuId, actualEvent.event.skuId)
    }

    @Test
    fun departEvent_sent_on_active_guidance() = runBlocking {
        // onDepart routeProgress.route will be original route
        every { routeProgress.route } returns originalRoute

        initTelemetry()
        sessionObserverSlot.captured.onNavigationSessionStateChanged(ACTIVE_GUIDANCE)

        val events = mutableListOf<MetricEvent>()
        verify(exactly = 2) { MapboxMetricsReporter.addEvent(capture(events)) }
        assertTrue(events[0] is NavigationAppUserTurnstileEvent)
        assertTrue(events[1] is NavigationDepartEvent)

        val departEvent = events[1] as NavigationDepartEvent
        assertEquals(SDK_IDENTIFIER, departEvent.sdkIdentifier)
        assertEquals(STEP_INDEX, departEvent.stepIndex)
        assertEquals(ROUTE_PROGRESS_DISTANCE_REMAINING.toInt(), departEvent.distanceRemaining)
        assertEquals(ROUTE_PROGRESS_DURATION_REMAINING.toInt(), departEvent.durationRemaining)
        assertEquals(ROUTE_PROGRESS_DISTANCE_TRAVELED.toInt(), departEvent.distanceCompleted)
        assertEquals(ORIGINAL_ROUTE_GEOMETRY, departEvent.geometry)
        assertEquals(ORIGINAL_ROUTE_OPTIONS_PROFILE, departEvent.profile)
        assertEquals(ORIGINAL_ROUTE_ROUTE_INDEX.toInt(), departEvent.legIndex)
        assertEquals(obtainStepCount(originalRoute), departEvent.stepCount)
        assertEquals(originalRoute.legs()?.size, departEvent.legCount)

        assertEquals(obtainStepCount(originalRoute), departEvent.originalStepCount)
        assertEquals(ORIGINAL_ROUTE_DISTANCE.toInt(), departEvent.originalEstimatedDistance)
        assertEquals(ORIGINAL_ROUTE_DURATION.toInt(), departEvent.originalEstimatedDuration)
        assertEquals(ORIGINAL_ROUTE_OPTIONS_REQUEST_UUID, departEvent.originalRequestIdentifier)
        assertEquals(ORIGINAL_ROUTE_GEOMETRY, departEvent.originalGeometry)

        assertEquals(FIRST_LOCATION_LAT, departEvent.lat)
        assertEquals(FIRST_LOCATION_LON, departEvent.lng)
        assertEquals(false, departEvent.simulation)

        assertEquals(0, departEvent.rerouteCount)
        assertEquals(7, departEvent.eventVersion)

        assertEquals(
            obtainAbsoluteDistance(lastLocation, obtainRouteDestination(originalRoute)),
            departEvent.absoluteDistanceToDestination
        )
        assertEquals(originalRoute.distance().toInt(), departEvent.estimatedDistance)
        assertEquals(originalRoute.duration().toInt(), departEvent.estimatedDuration)
        assertEquals(obtainStepCount(originalRoute), departEvent.totalStepCount)
    }

    @Test
    fun cancelEvent_sent_on_active_guidance_stop() = runBlocking {
        initTelemetry()
        sessionObserverSlot.captured.onNavigationSessionStateChanged(ACTIVE_GUIDANCE)
        sessionObserverSlot.captured.onNavigationSessionStateChanged(FREE_DRIVE)

        val events = mutableListOf<MetricEvent>()
        verify(exactly = 3) { MapboxMetricsReporter.addEvent(capture(events)) }
        assertTrue(events[0] is NavigationAppUserTurnstileEvent)
        assertTrue(events[1] is NavigationDepartEvent)
        assertTrue(events[2] is NavigationCancelEvent)
        coVerify { callbackDispatcher.clearLocationEventBuffer() }
        verify { callbackDispatcher.resetOriginalRoute(null) }
    }

    @Test
    fun arriveEvent_sent_on_arrival() = runBlocking {
        every { routeProgress.currentState } returns ROUTE_COMPLETE

        initTelemetry()
        sessionObserverSlot.captured.onNavigationSessionStateChanged(ACTIVE_GUIDANCE)

        routeProgressChannel.offer(routeProgress)

        val events = mutableListOf<MetricEvent>()
        verify(exactly = 3) { MapboxMetricsReporter.addEvent(capture(events)) }
        assertTrue(events[0] is NavigationAppUserTurnstileEvent)
        assertTrue(events[1] is NavigationDepartEvent)
        assertTrue(events[2] is NavigationArriveEvent)
    }

    @Test
    fun cancel_and_depart_events_sent_on_external_route() = runBlocking {
        initTelemetry()
        sessionObserverSlot.captured.onNavigationSessionStateChanged(ACTIVE_GUIDANCE)

        newRouteChannel.offer(ExternalRoute(originalRoute))

        val events = mutableListOf<MetricEvent>()
        verify(exactly = 4) { MapboxMetricsReporter.addEvent(capture(events)) }
        assertTrue(events[0] is NavigationAppUserTurnstileEvent)
        assertTrue(events[1] is NavigationDepartEvent)
        assertTrue(events[2] is NavigationCancelEvent)
        assertTrue(events[3] is NavigationDepartEvent)
    }

    @Test
    fun rerouteEvent_sent_on_offRoute() = runBlocking {
        val actionSlot = slot<suspend (List<Location>, List<Location>) -> Unit>()
        every { callbackDispatcher.accumulatePostEventLocations(capture(actionSlot)) } just Runs

        initTelemetry()
        sessionObserverSlot.captured.onNavigationSessionStateChanged(ACTIVE_GUIDANCE)

        newRouteChannel.offer(RerouteRoute(routeFromProgress))

        actionSlot.captured.invoke(listOf(), listOf())

        val events = mutableListOf<MetricEvent>()
        verify(exactly = 3) { MapboxMetricsReporter.addEvent(capture(events)) }
        assertTrue(events[0] is NavigationAppUserTurnstileEvent)
        assertTrue(events[1] is NavigationDepartEvent)
        assertTrue(events[2] is NavigationRerouteEvent)

        val rerouteEvent = events[2] as NavigationRerouteEvent
        assertEquals(SDK_IDENTIFIER, rerouteEvent.sdkIdentifier)
        assertEquals(STEP_INDEX, rerouteEvent.stepIndex)
        assertEquals(ROUTE_PROGRESS_DISTANCE_REMAINING.toInt(), rerouteEvent.distanceRemaining)
        assertEquals(ROUTE_PROGRESS_DURATION_REMAINING.toInt(), rerouteEvent.durationRemaining)
        assertEquals(ROUTE_PROGRESS_DISTANCE_TRAVELED.toInt(), rerouteEvent.distanceCompleted)
        assertEquals(PROGRESS_ROUTE_GEOMETRY, rerouteEvent.geometry)
        assertEquals(PROGRESS_ROUTE_OPTIONS_PROFILE, rerouteEvent.profile)
        assertEquals(PROGRESS_ROUTE_ROUTE_INDEX.toInt(), rerouteEvent.legIndex)
        assertEquals(obtainStepCount(routeFromProgress), rerouteEvent.stepCount)
        assertEquals(routeFromProgress.legs()?.size, rerouteEvent.legCount)

        assertEquals(obtainStepCount(originalRoute), rerouteEvent.originalStepCount)
        assertEquals(ORIGINAL_ROUTE_DISTANCE.toInt(), rerouteEvent.originalEstimatedDistance)
        assertEquals(ORIGINAL_ROUTE_DURATION.toInt(), rerouteEvent.originalEstimatedDuration)
        assertEquals(ORIGINAL_ROUTE_OPTIONS_REQUEST_UUID, rerouteEvent.originalRequestIdentifier)
        assertEquals(ORIGINAL_ROUTE_GEOMETRY, rerouteEvent.originalGeometry)

        assertEquals(LAST_LOCATION_LAT, rerouteEvent.lat)
        assertEquals(LAST_LOCATION_LON, rerouteEvent.lng)
        assertEquals(false, rerouteEvent.simulation)

        assertEquals(1, rerouteEvent.rerouteCount)
        assertEquals(7, rerouteEvent.eventVersion)

        assertEquals(
            obtainAbsoluteDistance(lastLocation, obtainRouteDestination(routeFromProgress)),
            rerouteEvent.absoluteDistanceToDestination
        )
        assertEquals(routeFromProgress.distance().toInt(), rerouteEvent.estimatedDistance)
        assertEquals(routeFromProgress.duration().toInt(), rerouteEvent.estimatedDuration)
        assertEquals(obtainStepCount(routeFromProgress), rerouteEvent.totalStepCount)

        assertEquals(PROGRESS_ROUTE_DISTANCE.toInt(), rerouteEvent.newDistanceRemaining)
        assertEquals(PROGRESS_ROUTE_DURATION.toInt(), rerouteEvent.newDurationRemaining)
        assertEquals(PROGRESS_ROUTE_GEOMETRY, rerouteEvent.newGeometry)
    }

    @Test
    fun feedback_and_reroute_events_sent_on_arrive() = runBlocking {
        val actions = mutableListOf<suspend (List<Location>, List<Location>) -> Unit>()
        every { callbackDispatcher.accumulatePostEventLocations(capture(actions)) } just Runs
        every { routeProgress.currentState } returns ROUTE_COMPLETE
        coEvery { callbackDispatcher.clearLocationEventBuffer() } coAnswers {
            actions.forEach { it.invoke(listOf(), listOf()) }
        }

        initTelemetry()
        sessionObserverSlot.captured.onNavigationSessionStateChanged(ACTIVE_GUIDANCE)

        postUserFeedback()
        newRouteChannel.offer(RerouteRoute(originalRoute))
        postUserFeedback()

        routeProgressChannel.offer(routeProgress)

        val events = mutableListOf<MetricEvent>()
        verify(exactly = 6) { MapboxMetricsReporter.addEvent(capture(events)) }
        assertTrue(events[0] is NavigationAppUserTurnstileEvent)
        assertTrue(events[1] is NavigationDepartEvent)
        assertTrue(events[2] is NavigationFeedbackEvent)
        assertTrue(events[3] is NavigationRerouteEvent)
        assertTrue(events[4] is NavigationFeedbackEvent)
        assertTrue(events[5] is NavigationArriveEvent)
    }

    @Test
    fun feedback_and_reroute_events_sent_on_free_drive() = runBlocking {
        val actions = mutableListOf<suspend (List<Location>, List<Location>) -> Unit>()
        every { callbackDispatcher.accumulatePostEventLocations(capture(actions)) } just Runs
        coEvery { callbackDispatcher.clearLocationEventBuffer() } coAnswers {
            actions.forEach { it.invoke(listOf(), listOf()) }
        }

        initTelemetry()
        sessionObserverSlot.captured.onNavigationSessionStateChanged(ACTIVE_GUIDANCE)

        postUserFeedback()
        newRouteChannel.offer(RerouteRoute(originalRoute))
        postUserFeedback()

        sessionObserverSlot.captured.onNavigationSessionStateChanged(FREE_DRIVE)

        val events = mutableListOf<MetricEvent>()
        verify(exactly = 6) { MapboxMetricsReporter.addEvent(capture(events)) }
        assertTrue(events[0] is NavigationAppUserTurnstileEvent)
        assertTrue(events[1] is NavigationDepartEvent)
        assertTrue(events[2] is NavigationFeedbackEvent)
        assertTrue(events[3] is NavigationRerouteEvent)
        assertTrue(events[4] is NavigationFeedbackEvent)
        assertTrue(events[5] is NavigationCancelEvent)
    }

    @Test
    fun feedback_and_reroute_events_sent_on_master_job_canceled() = runBlocking {
        val actions = mutableListOf<suspend (List<Location>, List<Location>) -> Unit>()
        every { callbackDispatcher.accumulatePostEventLocations(capture(actions)) } just Runs
        coEvery { callbackDispatcher.clearLocationEventBuffer() } coAnswers {
            actions.forEach { it.invoke(listOf(), listOf()) }
        }

        initTelemetry()
        sessionObserverSlot.captured.onNavigationSessionStateChanged(ACTIVE_GUIDANCE)

        postUserFeedback()
        newRouteChannel.offer(RerouteRoute(originalRoute))
        postUserFeedback()

        parentJob.cancel()

        val events = mutableListOf<MetricEvent>()
        verify(exactly = 6) { MapboxMetricsReporter.addEvent(capture(events)) }
        assertTrue(events[0] is NavigationAppUserTurnstileEvent)
        assertTrue(events[1] is NavigationDepartEvent)
        assertTrue(events[2] is NavigationFeedbackEvent)
        assertTrue(events[3] is NavigationRerouteEvent)
        assertTrue(events[4] is NavigationFeedbackEvent)
        assertTrue(events[5] is NavigationCancelEvent)
    }

    @Test
    fun onInit_registerRouteProgressObserver_called() {
        onInit { verify(exactly = 1) { mapboxNavigation.registerRouteProgressObserver(any()) } }
    }

    @Test
    fun onInit_registerLocationObserver_called() {
        onInit { verify(exactly = 1) { mapboxNavigation.registerLocationObserver(any()) } }
    }

    @Test
    fun onInit_registerRoutesObserver_called() {
        onInit { verify(exactly = 1) { mapboxNavigation.registerRoutesObserver(any()) } }
    }

    @Test
    fun onInit_registerOffRouteObserver_called() {
        onInit { verify(exactly = 1) { mapboxNavigation.registerOffRouteObserver(any()) } }
    }

    @Test
    fun onInit_registerNavigationSessionObserver_called() {
        onInit { verify(exactly = 1) { mapboxNavigation.registerNavigationSessionObserver(any()) } }
    }

    @Test
    fun onUnregisterListener_unregisterRouteProgressObserver_called() {
        onUnregister {
            verify(exactly = 1) { mapboxNavigation.unregisterRouteProgressObserver(any()) }
        }
    }

    @Test
    fun onUnregisterListener_unregisterLocationObserver_called() {
        onUnregister { verify(exactly = 1) { mapboxNavigation.unregisterLocationObserver(any()) } }
    }

    @Test
    fun onUnregisterListener_unregisterRoutesObserver_called() {
        onUnregister { verify(exactly = 1) { mapboxNavigation.unregisterRoutesObserver(any()) } }
    }

    @Test
    fun onUnregisterListener_unregisterOffRouteObserver_called() {
        onUnregister { verify(exactly = 1) { mapboxNavigation.unregisterOffRouteObserver(any()) } }
    }

    @Test
    fun onUnregisterListener_unregisterNavigationSessionObserver_called() {
        onUnregister {
            verify(exactly = 1) { mapboxNavigation.unregisterNavigationSessionObserver(any()) }
        }
    }

    @Test
    fun after_unregister_onInit_registers_all_listeners_again() {
        initTelemetry()
        resetTelemetry()
        initTelemetry()

        verify(exactly = 2) { mapboxNavigation.registerRouteProgressObserver(any()) }
        verify(exactly = 2) { mapboxNavigation.registerLocationObserver(any()) }
        verify(exactly = 2) { mapboxNavigation.registerRoutesObserver(any()) }
        verify(exactly = 2) { mapboxNavigation.registerOffRouteObserver(any()) }
        verify(exactly = 2) { mapboxNavigation.registerNavigationSessionObserver(any()) }

        resetTelemetry()
    }

    private fun initTelemetry() {
        MapboxNavigationTelemetry.initialize(
            mapboxNavigation,
            navigationOptions,
            MapboxMetricsReporter,
            mainJobControl,
            callbackDispatcher
        )
    }

    private fun resetTelemetry() {
        MapboxNavigationTelemetry.unregisterListeners(mapboxNavigation)
    }

    private fun onInit(block: () -> Unit) {
        initTelemetry()
        block()
        resetTelemetry()
    }

    private fun onUnregister(block: () -> Unit) {
        initTelemetry()
        resetTelemetry()
        block()
    }

    private fun postUserFeedback() {
        MapboxNavigationTelemetry.postUserFeedback("", "", "", null, null, null)
    }
}
