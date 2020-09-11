package com.mapbox.navigation.examples.core

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Projection
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.navigation.base.internal.extensions.applyDefaultParams
import com.mapbox.navigation.base.internal.extensions.coordinates
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesRequestCallback
import com.mapbox.navigation.core.replay.MapboxReplayer
import com.mapbox.navigation.core.replay.ReplayLocationEngine
import com.mapbox.navigation.core.replay.route.ReplayProgressObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.TripSessionState
import com.mapbox.navigation.core.trip.session.TripSessionStateObserver
import com.mapbox.navigation.examples.R
import com.mapbox.navigation.examples.utils.Utils
import com.mapbox.navigation.examples.utils.Utils.PRIMARY_ROUTE_BUNDLE_KEY
import com.mapbox.navigation.examples.utils.Utils.getRouteFromBundle
import com.mapbox.navigation.examples.utils.extensions.toPoint
import com.mapbox.navigation.ui.camera.NavigationCamera
import com.mapbox.navigation.ui.map.NavigationMapboxMap
import kotlinx.android.synthetic.main.activity_alternative_route_click_padding.padding_seekbar
import kotlinx.android.synthetic.main.activity_alternative_route_click_padding.padding_textview
import kotlinx.android.synthetic.main.activity_basic_navigation_layout.container
import kotlinx.android.synthetic.main.activity_basic_navigation_layout.fabToggleStyle
import kotlinx.android.synthetic.main.activity_basic_navigation_layout.mapView
import kotlinx.android.synthetic.main.activity_basic_navigation_layout.startNavigation
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.ArrayList

/**
 * This activity shows how to visualize and adjust what is usually an invisible RectF
 * query box. This Nav SDK builds this box around the map click location. If the
 * MapRouteClickListener determines that any route lines run through
 * this invisible box, this is used to figure out which route was selected and
 * for potentially firing the OnRouteSelectionChangeListener.
 */
open class CustomAlternativeRouteClickPaddingActivity : AppCompatActivity(), OnMapReadyCallback,
    MapboxMap.OnMapClickListener {

    companion object {
        const val TAG = "CustomAlternativeRouteClickPaddingActivity"
        const val DEFAULT_INTERVAL_IN_MILLISECONDS = 1000L
        const val DEFAULT_MAX_WAIT_TIME = DEFAULT_INTERVAL_IN_MILLISECONDS * 5
        const val CLICK_BOX_GEOJSON_SOURCE_ID = "CLICK_BOX_SOURCE_ID"
        const val CLICK_BOX_LAYER_ID = "CLICK_BOX_LAYER_ID"
        const val SEEKBAR_STEP = 1
        const val SEEKBAR_MAX = 150
    }

    private var mapboxNavigation: MapboxNavigation? = null
    private var navigationMapboxMap: NavigationMapboxMap? = null
    private var mapInstanceState: Bundle? = null
    private val mapboxReplayer = MapboxReplayer()
    private var directionRoute: DirectionsRoute? = null
    private var seekBarProgress: Int = 100
    private var lastMapClickLatLng: LatLng? = null
    private var clickToShowQuerySnackbarHasBeenShown = false

    private val mapStyles = listOf(
        Style.MAPBOX_STREETS,
        Style.OUTDOORS,
        Style.LIGHT,
        Style.DARK,
        Style.SATELLITE_STREETS
    )

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alternative_route_click_padding)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        val mapboxNavigationOptions = MapboxNavigation
            .defaultNavigationOptionsBuilder(this, Utils.getMapboxAccessToken(this))
            .locationEngine(getLocationEngine())
            .build()

        mapboxNavigation = MapboxNavigation(mapboxNavigationOptions).apply {
            registerTripSessionStateObserver(tripSessionStateObserver)
            registerRouteProgressObserver(routeProgressObserver)
        }

        initListeners()
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(mapboxMap: MapboxMap) {
        mapboxMap.setStyle(Style.MAPBOX_STREETS) {
            mapboxMap.moveCamera(CameraUpdateFactory.zoomTo(15.0))

            /**
             * Initialize the [SeekBar] that can be used to adjust the padding
             * of the querying box area used in the [MapRouteClickListener].
             */
            padding_seekbar.apply {
                max = (SEEKBAR_MAX - 0) / SEEKBAR_STEP;
                progress = (40).also {
                    seekBarProgress = it
                    padding_textview.text = String.format(getString(R.string.alternative_route_click_padding), it)
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                        progress.let {
                            seekBarProgress = it
                            padding_textview.text = String.format(getString(R.string.alternative_route_click_padding), it)
                            lastMapClickLatLng?.let { latLng ->
                                adjustPolygonFillLayerArea(latLng)
                            }
                        }
                    }

                    override fun onStartTrackingTouch(seek: SeekBar) {
                        // Empty because not needed in this example
                    }

                    override fun onStopTrackingTouch(seek: SeekBar) {
                        navigationMapboxMap?.updateClickDistancePadding(intArrayOf(
                            seekBarProgress, seekBarProgress, seekBarProgress, seekBarProgress
                        ))
                    }
                })
            }

            navigationMapboxMap = NavigationMapboxMap(mapView, mapboxMap, this,
                null, true, true,
                intArrayOf(seekBarProgress, seekBarProgress, seekBarProgress, seekBarProgress))
            navigationMapboxMap?.retrieveMap()?.addOnMapClickListener(this)

            initPaddingPolygonSourceAndLayer()

            mapInstanceState?.let { state ->
                navigationMapboxMap?.restoreStateFrom(state)
            }

            when (directionRoute) {
                null -> {
                    if (shouldSimulateRoute()) {
                        mapboxNavigation
                            ?.registerRouteProgressObserver(ReplayProgressObserver(mapboxReplayer))
                        mapboxReplayer.pushRealLocation(this, 0.0)
                        mapboxReplayer.play()
                    }
                    mapboxNavigation
                        ?.navigationOptions
                        ?.locationEngine
                        ?.getLastLocation(locationListenerCallback)
                    Snackbar
                        .make(
                            container,
                            R.string.msg_long_press_map_to_place_waypoint,
                            LENGTH_SHORT
                        )
                        .show()
                }
                else -> restoreNavigation()
            }
        }
        mapboxMap.addOnMapLongClickListener { latLng ->
            mapboxMap.locationComponent.lastKnownLocation?.let { originLocation ->
                mapboxNavigation?.requestRoutes(
                    RouteOptions.builder().applyDefaultParams()
                        .accessToken(Utils.getMapboxAccessToken(applicationContext))
                        .coordinates(originLocation.toPoint(), null, latLng.toPoint())
                        .alternatives(true)
                        .build(),
                    routesReqCallback
                )
            }
            true
        }
    }

    override fun onMapClick(mapClickLatLng: LatLng): Boolean {
        navigationMapboxMap?.retrieveMap()?.let { mapboxMap ->
            mapboxMap.getStyle { style ->
                lastMapClickLatLng = mapClickLatLng
                style.getSourceAs<GeoJsonSource>(CLICK_BOX_GEOJSON_SOURCE_ID)?.let { geoJsonSource ->
                    adjustPolygonFillLayerArea(mapClickLatLng)
                }
            }
        }
        return true
    }

    private fun adjustPolygonFillLayerArea(mapClickLatLng: LatLng) {
        navigationMapboxMap?.retrieveMap()?.let { mapboxMap ->
            mapboxMap.getStyle { style ->
                style.getSourceAs<GeoJsonSource>(CLICK_BOX_GEOJSON_SOURCE_ID)?.let { geoJsonSource ->
                    val mapProjection: Projection = mapboxMap.projection
                    val mapClickPointF = mapProjection.toScreenLocation(mapClickLatLng)
                    val leftFloat = (mapClickPointF.x - seekBarProgress)
                    val rightFloat = (mapClickPointF.x + seekBarProgress)
                    val topFloat = (mapClickPointF.y - seekBarProgress)
                    val bottomFloat = (mapClickPointF.y + seekBarProgress)

                    val listOfPointLists: MutableList<List<Point>> = ArrayList()
                    val pointList: MutableList<Point> = ArrayList()

                    val upperLeftLatLng: LatLng = mapProjection.fromScreenLocation(PointF(leftFloat, topFloat))
                    val upperRightLatLng: LatLng = mapProjection.fromScreenLocation(PointF(rightFloat, topFloat))
                    val lowerLeftLatLng: LatLng = mapProjection.fromScreenLocation(PointF(leftFloat, bottomFloat))
                    val lowerRightLatLng: LatLng = mapProjection.fromScreenLocation(PointF(rightFloat, bottomFloat))

                    pointList.apply {
                        add(Point.fromLngLat(upperLeftLatLng.longitude, upperLeftLatLng.latitude))
                        add(Point.fromLngLat(lowerLeftLatLng.longitude, lowerLeftLatLng.latitude))
                        add(Point.fromLngLat(lowerRightLatLng.longitude, lowerRightLatLng.latitude))
                        add(Point.fromLngLat(upperRightLatLng.longitude, upperRightLatLng.latitude))
                        add(Point.fromLngLat(upperLeftLatLng.longitude, upperLeftLatLng.latitude))
                        listOfPointLists.add(this)
                        geoJsonSource.setGeoJson(Polygon.fromLngLats(listOfPointLists))
                    }
                }
            }
        }
    }

    /**
     * Add a source and layer to the [Style] so that the click box [Polygon]
     * can be displayed to visualize the querying area.
     */
    private fun initPaddingPolygonSourceAndLayer() {
        navigationMapboxMap?.retrieveMap()?.getStyle {
            it.addSource(GeoJsonSource(CLICK_BOX_GEOJSON_SOURCE_ID))
            it.addLayer(FillLayer(CLICK_BOX_LAYER_ID, CLICK_BOX_GEOJSON_SOURCE_ID)
                .withProperties(
                    PropertyFactory.fillColor(Color.RED),
                    PropertyFactory.fillOpacity(.4f)))
        }
    }

    private val routeProgressObserver = object : RouteProgressObserver {
        override fun onRouteProgressChanged(routeProgress: RouteProgress) {
            // do something with the route progress
            Timber.i("route progress: ${routeProgress.currentState}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!shouldSimulateRoute()) {
            val requestLocationUpdateRequest =
                LocationEngineRequest.Builder(DEFAULT_INTERVAL_IN_MILLISECONDS)
                    .setPriority(LocationEngineRequest.PRIORITY_NO_POWER)
                    .setMaxWaitTime(DEFAULT_MAX_WAIT_TIME)
                    .build()

            mapboxNavigation?.navigationOptions?.locationEngine?.requestLocationUpdates(
                requestLocationUpdateRequest,
                locationListenerCallback,
                mainLooper
            )
        }
    }

    private val routesReqCallback = object : RoutesRequestCallback {
        override fun onRoutesReady(routes: List<DirectionsRoute>) {
            if (routes.isNotEmpty()) {
                directionRoute = routes[0]
                navigationMapboxMap?.drawRoutes(routes)
                startNavigation.visibility = View.VISIBLE
            } else {
                startNavigation.visibility = View.GONE
            }
            if (clickToShowQuerySnackbarHasBeenShown) {
                Snackbar
                    .make(
                        container,
                        R.string.alternative_route_click_instruction,
                        LENGTH_SHORT
                    )
                    .show()
                clickToShowQuerySnackbarHasBeenShown = true
            }
        }

        override fun onRoutesRequestFailure(throwable: Throwable, routeOptions: RouteOptions) {
            Timber.e("route request failure %s", throwable.toString())
        }

        override fun onRoutesRequestCanceled(routeOptions: RouteOptions) {
            Timber.d("route request canceled")
        }
    }

    @SuppressLint("MissingPermission")
    fun initListeners() {
        startNavigation.setOnClickListener {
            updateCameraOnNavigationStateChange(true)
            navigationMapboxMap?.addProgressChangeListener(mapboxNavigation!!)
            if (mapboxNavigation?.getRoutes()?.isNotEmpty() == true) {
                navigationMapboxMap?.startCamera(mapboxNavigation?.getRoutes()!![0])
            }
            mapboxNavigation?.startTripSession()
            startNavigation.visibility = View.GONE
            stopLocationUpdates()
        }

        fabToggleStyle.setOnClickListener {
            navigationMapboxMap?.retrieveMap()?.setStyle(mapStyles.shuffled().first()) {
                initPaddingPolygonSourceAndLayer()
                lastMapClickLatLng?.let {
                    Log.d(TAG,"lastMapClickLatLng != null")
                    adjustPolygonFillLayerArea(it)
                }
                navigationMapboxMap?.retrieveMap()?.addOnMapClickListener(this)

            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    public override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    public override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        stopLocationUpdates()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapboxReplayer.finish()
        mapboxNavigation?.unregisterTripSessionStateObserver(tripSessionStateObserver)
        mapboxNavigation?.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNavigation?.stopTripSession()
        mapboxNavigation?.onDestroy()
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navigationMapboxMap?.saveStateWith(outState)
        mapView.onSaveInstanceState(outState)

        // This is not the most efficient way to preserve the route on a device rotation.
        // This is here to demonstrate that this event needs to be handled in order to
        // redraw the route line after a rotation.
        directionRoute?.let {
            outState.putString(PRIMARY_ROUTE_BUNDLE_KEY, it.toJson())
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        mapInstanceState = savedInstanceState
        directionRoute = getRouteFromBundle(savedInstanceState)
    }

    private val locationListenerCallback = MyLocationEngineCallback(this)

    private fun stopLocationUpdates() {
        if (!shouldSimulateRoute()) {
            mapboxNavigation
                ?.navigationOptions
                ?.locationEngine
                ?.removeLocationUpdates(locationListenerCallback)
        }
    }

    private val tripSessionStateObserver = object : TripSessionStateObserver {
        override fun onSessionStateChanged(tripSessionState: TripSessionState) {
            when (tripSessionState) {
                TripSessionState.STARTED -> {
                    stopLocationUpdates()
                }
                TripSessionState.STOPPED -> {
                    startLocationUpdates()
                    navigationMapboxMap?.hideRoute()
                    updateCameraOnNavigationStateChange(false)
                }
            }
        }
    }

    // Used to determine if the ReplayRouteLocationEngine should be used to simulate the routing.
    // This is used for testing purposes.
    private fun shouldSimulateRoute(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(this.applicationContext)
            .getBoolean(this.getString(R.string.simulate_route_key), false)
    }

    // If shouldSimulateRoute is true a ReplayRouteLocationEngine will be used which is intended
    // for testing else a real location engine is used.
    private fun getLocationEngine(): LocationEngine {
        return if (shouldSimulateRoute()) {
            ReplayLocationEngine(mapboxReplayer)
        } else {
            LocationEngineProvider.getBestLocationEngine(this)
        }
    }

    private fun updateCameraOnNavigationStateChange(
        navigationStarted: Boolean
    ) {
        navigationMapboxMap?.apply {
            if (navigationStarted) {
                updateCameraTrackingMode(NavigationCamera.NAVIGATION_TRACKING_MODE_GPS)
                updateLocationLayerRenderMode(RenderMode.GPS)
            } else {
                updateCameraTrackingMode(NavigationCamera.NAVIGATION_TRACKING_MODE_NONE)
                updateLocationLayerRenderMode(RenderMode.COMPASS)
            }
        }
    }

    private class MyLocationEngineCallback(activity: CustomAlternativeRouteClickPaddingActivity) :
        LocationEngineCallback<LocationEngineResult> {

        private val activityRef = WeakReference(activity)

        override fun onSuccess(result: LocationEngineResult) {
            activityRef.get()?.navigationMapboxMap?.updateLocation(result.lastLocation)
        }

        override fun onFailure(exception: java.lang.Exception) {
            Timber.i(exception)
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreNavigation() {
        directionRoute?.let {
            mapboxNavigation?.setRoutes(listOf(it))
            navigationMapboxMap?.addProgressChangeListener(mapboxNavigation!!)
            navigationMapboxMap?.startCamera(mapboxNavigation?.getRoutes()!![0])
            updateCameraOnNavigationStateChange(true)
            mapboxNavigation?.startTripSession()
        }
    }
}
