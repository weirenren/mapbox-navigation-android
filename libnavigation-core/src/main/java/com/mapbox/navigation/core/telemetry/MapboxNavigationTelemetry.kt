package com.mapbox.navigation.core.telemetry

import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Build
import android.util.Log
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.telemetry.AppUserTurnstile
import com.mapbox.android.telemetry.TelemetryUtils.generateCreateDateFormatted
import com.mapbox.android.telemetry.TelemetryUtils.obtainUniversalUniqueIdentifier
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.metrics.MetricEvent
import com.mapbox.navigation.base.metrics.MetricsReporter
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteProgressState.ROUTE_COMPLETE
import com.mapbox.navigation.core.BuildConfig
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.NavigationSession.State
import com.mapbox.navigation.core.NavigationSession.State.ACTIVE_GUIDANCE
import com.mapbox.navigation.core.NavigationSession.State.FREE_DRIVE
import com.mapbox.navigation.core.NavigationSession.State.IDLE
import com.mapbox.navigation.core.NavigationSessionStateObserver
import com.mapbox.navigation.core.internal.accounts.MapboxNavigationAccounts
import com.mapbox.navigation.core.telemetry.NewRoute.ExternalRoute
import com.mapbox.navigation.core.telemetry.NewRoute.RerouteRoute
import com.mapbox.navigation.core.telemetry.events.AppMetadata
import com.mapbox.navigation.core.telemetry.events.FeedbackEvent
import com.mapbox.navigation.core.telemetry.events.MetricsRouteProgress
import com.mapbox.navigation.core.telemetry.events.NavigationArriveEvent
import com.mapbox.navigation.core.telemetry.events.NavigationCancelEvent
import com.mapbox.navigation.core.telemetry.events.NavigationDepartEvent
import com.mapbox.navigation.core.telemetry.events.NavigationEvent
import com.mapbox.navigation.core.telemetry.events.NavigationFeedbackEvent
import com.mapbox.navigation.core.telemetry.events.NavigationRerouteEvent
import com.mapbox.navigation.core.telemetry.events.PhoneState
import com.mapbox.navigation.core.telemetry.events.TelemetryLocation
import com.mapbox.navigation.metrics.MapboxMetricsReporter
import com.mapbox.navigation.metrics.internal.event.NavigationAppUserTurnstileEvent
import com.mapbox.navigation.utils.internal.JobControl
import com.mapbox.navigation.utils.internal.ThreadController
import com.mapbox.navigation.utils.internal.Time
import com.mapbox.navigation.utils.internal.ifNonNull
import com.mapbox.navigation.utils.internal.monitorChannelWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private data class DynamicSessionValues(
    val rerouteCount: AtomicInteger = AtomicInteger(0),
    val timeOfReroute: AtomicLong = AtomicLong(0),
    val timeSinceLastReroute: AtomicInteger = AtomicInteger(0),
    var sessionId: String = obtainUniversalUniqueIdentifier(),
    val tripIdentifier: AtomicReference<String> =
        AtomicReference(obtainUniversalUniqueIdentifier()),
    var sessionStartTime: Date = Date(),
    var sessionArrivalTime: AtomicReference<Date?> = AtomicReference(null),
    val sessionStarted: AtomicBoolean = AtomicBoolean(false),
    val originalRoute: AtomicReference<DirectionsRoute?> = AtomicReference(null)
) {
    fun reset() {
        rerouteCount.set(0)
        timeOfReroute.set(0)
        sessionId = obtainUniversalUniqueIdentifier()
        timeSinceLastReroute.set(0)
        tripIdentifier.set(obtainUniversalUniqueIdentifier())
        sessionArrivalTime.set(null)
        sessionStarted.set(false)
    }
}

/**
 * The one and only Telemetry class. This class handles all telemetry events.
 * Event List:
- appUserTurnstile
- navigation.depart
- navigation.feedback
- navigation.reroute
- navigation.fasterRoute
- navigation.arrive
- navigation.cancel
The class must be initialized before any telemetry events are reported. Attempting to use telemetry before initialization is called will throw an exception. Initialization may be called multiple times, the call is idempotent.
The class has two public methods, postUserFeedback() and initialize().
 */
internal object MapboxNavigationTelemetry {
    private const val ONE_SECOND = 1000
    private const val MOCK_PROVIDER = "com.mapbox.navigation.core.replay.ReplayLocationEngine"
    private const val EVENT_VERSION = 7
    internal const val TAG = "MAPBOX_TELEMETRY"

    private lateinit var context: Context // Must be context.getApplicationContext
    private lateinit var telemetryThreadControl: JobControl
    private val telemetryScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + ThreadController.IODispatcher
    )
    private var monitorSession: Job? = null
    private lateinit var metricsReporter: MetricsReporter
    private lateinit var navigationOptions: NavigationOptions
    private var lifecycleMonitor: ApplicationLifecycleMonitor? = null
    private var appInstance: Application? = null
        set(value) {
            // Don't set it multiple times to the same value, it will cause multiple registration calls.
            if (field == value) {
                return
            }
            field = value
            ifNonNull(value) { app ->
                Log.d(TAG, "Lifecycle monitor created")
                lifecycleMonitor = ApplicationLifecycleMonitor(app)
            }
        }
    private val dynamicValues = DynamicSessionValues()
    private var locationEngineNameExternal: String = LocationEngine::javaClass.name
    private lateinit var callbackDispatcher: TelemetryLocationAndProgressDispatcher
    private lateinit var sdkIdentifier: String

    private val navigationSessionObserver = object : NavigationSessionStateObserver {
        override fun onNavigationSessionStateChanged(navigationSession: State) {
            Log.d(TAG, "Navigation state is $navigationSession")
            when (navigationSession) {
                FREE_DRIVE, IDLE -> switchToNotActiveGuidanceBehavior()
                ACTIVE_GUIDANCE -> sessionStart()
            }
        }
    }

    /**
     * This method must be called before using the Telemetry object
     */
    fun initialize(
        mapboxNavigation: MapboxNavigation,
        options: NavigationOptions,
        reporter: MetricsReporter,
        jobControl: JobControl,
        callbackDispatcher: TelemetryLocationAndProgressDispatcher =
            TelemetryLocationAndProgressDispatcherImpl(jobControl.scope)
    ) {
        this.callbackDispatcher = callbackDispatcher
        telemetryThreadControl = jobControl
        navigationOptions = options
        context = options.applicationContext
        locationEngineNameExternal = options.locationEngine.javaClass.name
        sdkIdentifier = if (options.isFromNavigationUi) {
            "mapbox-navigation-ui-android"
        } else {
            "mapbox-navigation-android"
        }
        metricsReporter = reporter

        registerListeners(mapboxNavigation)
        monitorNewRoutes()
        monitorJobCancellation()
        postTurnstileEvent()
        Log.i(TAG, "Valid initialization")
    }

    private fun switchToNotActiveGuidanceBehavior() {
        telemetryThreadControl.scope.launch {
            if (dynamicValues.sessionStarted.get()) {
                sessionStop()
            }
            stopProgressMonitoring()
        }
    }

    private fun sessionStart() {
        Log.d(TAG, "sessionStart")
        telemetryThreadControl.scope.launch {
            callbackDispatcher.resetRouteProgress()
            callbackDispatcher.originalRoute.await().let { route ->
                dynamicValues.run {
                    originalRoute.set(route)
                    sessionId = obtainUniversalUniqueIdentifier()
                    sessionStartTime = Date()
                    sessionStarted.set(true)
                }

                val departureEvent = NavigationDepartEvent(PhoneState(context))
                populateNavigationEvent(
                    departureEvent,
                    route,
                    callbackDispatcher.firstLocation
                )
                sendMetricEvent(departureEvent)

                stopProgressMonitoring()
                startProgressMonitoring()
            }
        }
    }

    private suspend fun sessionStop() {
        Log.d(TAG, "sessionStop")
        handleSessionCanceled()
        dynamicValues.reset()
        callbackDispatcher.resetOriginalRoute()
    }

    private fun sendMetricEvent(event: MetricEvent) {
        if (isTelemetryAvailable()) {
            Log.d(TAG, "${event::class.java} event sent")
            metricsReporter.addEvent(event)
        } else {
            Log.d(
                TAG,
                "${event::class.java} not sent. Caused by: " +
                    "Navigation Session started: ${dynamicValues.sessionStarted.get()}. " +
                    "Route exists: ${dynamicValues.originalRoute.get() != null}"
            )
        }
    }

    /**
     * The Navigation session is considered to be guided if it has been started and at least one route is active,
     * it is a free drive / idle session otherwise
     */
    private fun isTelemetryAvailable(): Boolean {
        return dynamicValues.originalRoute.get() != null && dynamicValues.sessionStarted.get()
    }

    fun setApplicationInstance(app: Application) {
        appInstance = app
    }

    private fun monitorNewRoutes() {
        telemetryThreadControl.scope.monitorChannelWithException(
            callbackDispatcher.newRouteChannel,
            {
                when (it) {
                    is RerouteRoute -> handleReroute(it.route)
                    is ExternalRoute -> handleExternalRoute(it.route)
                }
            }
        )
    }

    private fun handleReroute(newRoute: DirectionsRoute) {
        Log.d(TAG, "handleReroute")
        dynamicValues.run {
            val currentTime = Time.SystemImpl.millis()
            timeSinceLastReroute.set((currentTime - timeOfReroute.get()).toInt())
            timeOfReroute.set(currentTime)
            rerouteCount.addAndGet(1)
        }

        val currentProgress = callbackDispatcher.routeProgress
        callbackDispatcher.accumulatePostEventLocations { preEventBuffer, postEventBuffer ->
            val navigationRerouteEvent = NavigationRerouteEvent(
                PhoneState(context),
                MetricsRouteProgress(currentProgress)
            ).apply {
                locationsBefore = preEventBuffer.toTelemetryLocations()
                locationsAfter = postEventBuffer.toTelemetryLocations()
                secondsSinceLastReroute = dynamicValues.timeSinceLastReroute.get() / ONE_SECOND
                newDistanceRemaining = newRoute.distance().toInt()
                newDurationRemaining = newRoute.duration().toInt()
                newGeometry = obtainGeometry(newRoute)
            }
            populateNavigationEvent(navigationRerouteEvent)
            sendMetricEvent(navigationRerouteEvent)
        }
    }

    private suspend fun handleExternalRoute(route: DirectionsRoute) {
        Log.d(TAG, "handleExternalRoute")
        sessionStop()
        callbackDispatcher.resetOriginalRoute(route)
        sessionStart()
    }

    private fun monitorJobCancellation() {
        telemetryScope.launch {
            select {
                telemetryThreadControl.job.onJoin {
                    Log.d(TAG, "master job canceled")
                    sessionStop()
                    MapboxMetricsReporter.disable()
                }
            }
        }
    }

    fun postUserFeedback(
        @FeedbackEvent.Type feedbackType: String,
        description: String,
        @FeedbackEvent.Source feedbackSource: String,
        screenshot: String?,
        feedbackSubType: Array<String>?,
        appMetadata: AppMetadata?
    ) {
        if (dynamicValues.sessionStarted.get()) {
            Log.d(TAG, "collect post event locations for user feedback")
            val currentProgress = callbackDispatcher.routeProgress
            callbackDispatcher.accumulatePostEventLocations { preEventBuffer, postEventBuffer ->
                val feedbackEvent = NavigationFeedbackEvent(
                    PhoneState(context),
                    MetricsRouteProgress(currentProgress)
                ).apply {
                    this.feedbackType = feedbackType
                    this.source = feedbackSource
                    this.description = description
                    this.screenshot = screenshot
                    this.locationsBefore = preEventBuffer.toTelemetryLocations()
                    this.locationsAfter = postEventBuffer.toTelemetryLocations()
                    this.feedbackSubType = feedbackSubType
                    this.appMetadata = appMetadata
                }

                populateNavigationEvent(feedbackEvent)
                sendMetricEvent(feedbackEvent)
            }
        }
    }

    private suspend fun handleSessionCanceled() {
        Log.d(TAG, "handleSessionCanceled")
        callbackDispatcher.clearLocationEventBuffer()

        val cancelEvent = NavigationCancelEvent(PhoneState(context))
        ifNonNull(dynamicValues.sessionArrivalTime.get()) {
            cancelEvent.arrivalTimestamp = generateCreateDateFormatted(it)
        }
        populateNavigationEvent(cancelEvent)
        sendMetricEvent(cancelEvent)
    }

    private fun postTurnstileEvent() {
        val turnstileEvent =
            AppUserTurnstile(sdkIdentifier, BuildConfig.MAPBOX_NAVIGATION_VERSION_NAME).also {
                it.setSkuId(MapboxNavigationAccounts.getInstance(context).obtainSkuId())
            }
        val event = NavigationAppUserTurnstileEvent(turnstileEvent)
        metricsReporter.addEvent(event)
    }

    private suspend fun processArrival() {
        if (dynamicValues.sessionStarted.get()) {
            Log.d(TAG, "you have arrived")
            callbackDispatcher.clearLocationEventBuffer()

            dynamicValues.run {
                tripIdentifier.set(obtainUniversalUniqueIdentifier())
                sessionArrivalTime.set(Date())
            }

            val arriveEvent = NavigationArriveEvent(PhoneState(context))
            populateNavigationEvent(arriveEvent)
            sendMetricEvent(arriveEvent)
        } else {
            Log.d(TAG, "route arrival received before a session start")
        }
    }

    private fun stopProgressMonitoring() {
        monitorSession?.cancel()
        monitorSession = null
    }

    private fun startProgressMonitoring() {
        monitorSession = telemetryThreadControl.scope.monitorChannelWithException(
            callbackDispatcher.routeProgressChannel,
            {
                if (it.currentState == ROUTE_COMPLETE) {
                    processArrival()
                    stopProgressMonitoring()
                }
            }
        )
    }

    private fun registerListeners(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.run {
            registerRouteProgressObserver(callbackDispatcher)
            registerLocationObserver(callbackDispatcher)
            registerRoutesObserver(callbackDispatcher)
            registerOffRouteObserver(callbackDispatcher)
            registerNavigationSessionObserver(navigationSessionObserver)
        }
    }

    fun unregisterListeners(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.run {
            unregisterRouteProgressObserver(callbackDispatcher)
            unregisterLocationObserver(callbackDispatcher)
            unregisterRoutesObserver(callbackDispatcher)
            unregisterOffRouteObserver(callbackDispatcher)
            unregisterNavigationSessionObserver(navigationSessionObserver)
        }
    }

    private suspend fun populateNavigationEvent(
        navigationEvent: NavigationEvent,
        route: DirectionsRoute? = null,
        newLocation: Location? = null
    ) {
        val directionsRoute = route ?: callbackDispatcher.routeProgress?.route
        val location = newLocation ?: callbackDispatcher.lastLocation

        navigationEvent.apply {
            sdkIdentifier = this@MapboxNavigationTelemetry.sdkIdentifier

            callbackDispatcher.routeProgress?.let { routeProgress ->
                stepIndex = routeProgress.currentLegProgress?.currentStepProgress?.stepIndex ?: 0

                distanceRemaining = routeProgress.distanceRemaining.toInt()
                durationRemaining = routeProgress.durationRemaining.toInt()
                distanceCompleted = routeProgress.distanceTraveled.toInt()

                routeProgress.route.let {
                    geometry = it.geometry()
                    profile = it.routeOptions()?.profile()
                    requestIdentifier = it.routeOptions()?.requestUuid()
                    stepCount = obtainStepCount(it)
                    legIndex = it.routeIndex()?.toInt() ?: 0
                    legCount = it.legs()?.size ?: 0
                }
            }

            callbackDispatcher.originalRoute.await().let {
                originalStepCount = obtainStepCount(it)
                originalEstimatedDistance = it.distance().toInt()
                originalEstimatedDuration = it.duration().toInt()
                originalRequestIdentifier = it.routeOptions()?.requestUuid()
                originalGeometry = it.geometry()
            }

            locationEngine = locationEngineNameExternal
            tripIdentifier = obtainUniversalUniqueIdentifier()
            lat = location?.latitude ?: 0.0
            lng = location?.longitude ?: 0.0
            simulation = locationEngineNameExternal == MOCK_PROVIDER
            percentTimeInPortrait = lifecycleMonitor?.obtainPortraitPercentage() ?: 100
            percentTimeInForeground = lifecycleMonitor?.obtainForegroundPercentage() ?: 100

            dynamicValues.let {
                startTimestamp = generateCreateDateFormatted(it.sessionStartTime)
                rerouteCount = it.rerouteCount.get()
                sessionIdentifier = it.sessionId
            }

            eventVersion = EVENT_VERSION

            directionsRoute?.let {
                absoluteDistanceToDestination = obtainAbsoluteDistance(
                    callbackDispatcher.lastLocation,
                    obtainRouteDestination(it)
                )
                estimatedDistance = it.distance().toInt()
                estimatedDuration = it.duration().toInt()

                // TODO:OZ voiceIndex is not available in SDK 1.0 and was not set in the legacy telemetry        navigationEvent.voiceIndex
                // TODO:OZ bannerIndex is not available in SDK 1.0 and was not set in the legacy telemetry        navigationEvent.bannerIndex
                totalStepCount = obtainStepCount(it)
            }
        }
    }

    private fun List<Location>.toTelemetryLocations(): Array<TelemetryLocation> {
        val feedbackLocations = mutableListOf<TelemetryLocation>()
        this.forEach {
            feedbackLocations.add(
                TelemetryLocation(
                    it.latitude,
                    it.longitude,
                    it.speed,
                    it.bearing,
                    it.altitude,
                    it.time.toString(),
                    it.accuracy,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.verticalAccuracyMeters
                    } else {
                        0f
                    }
                )
            )
        }

        return feedbackLocations.toTypedArray()
    }
}
