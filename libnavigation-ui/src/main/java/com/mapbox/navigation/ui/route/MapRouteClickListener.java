package com.mapbox.navigation.ui.route;

import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.LineString;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.Projection;

import java.util.List;
import java.util.Map;

import static com.mapbox.navigation.ui.internal.route.RouteConstants.ALTERNATIVE_ROUTE_CASING_LAYER_ID;
import static com.mapbox.navigation.ui.internal.route.RouteConstants.ALTERNATIVE_ROUTE_LAYER_ID;
import static com.mapbox.navigation.ui.internal.route.RouteConstants.PRIMARY_ROUTE_CASING_LAYER_ID;
import static com.mapbox.navigation.ui.internal.route.RouteConstants.PRIMARY_ROUTE_LAYER_ID;

class MapRouteClickListener implements MapboxMap.OnMapClickListener {

  private static final int DEFAULT_ROUTE_CLICK_PADDING = 50;
  private final MapRouteLine routeLine;
  private MapboxMap mapboxMap;
  private RectF routeClickRectF;

  private OnRouteSelectionChangeListener onRouteSelectionChangeListener;
  private boolean alternativesVisible = true;
  private int leftPadding;
  private int topPadding;
  private int rightPadding;
  private int bottomPadding;

  MapRouteClickListener(MapRouteLine routeLine, MapboxMap mapboxMap, @Nullable int[] routeClickRectFPadding) {
	this.routeLine = routeLine;
	this.mapboxMap = mapboxMap;
	initPaddingValues(routeClickRectFPadding);
  }

  private void initPaddingValues(@Nullable int[] routeClickRectFPadding) {
	if (routeClickRectFPadding == null) {
		leftPadding = DEFAULT_ROUTE_CLICK_PADDING;
		topPadding = DEFAULT_ROUTE_CLICK_PADDING;
		rightPadding = DEFAULT_ROUTE_CLICK_PADDING;
		bottomPadding = DEFAULT_ROUTE_CLICK_PADDING;
	} else {
		leftPadding = routeClickRectFPadding[0];
		topPadding = routeClickRectFPadding[1];
		rightPadding = routeClickRectFPadding[2];
		bottomPadding = routeClickRectFPadding[3];
	}
  }

  @Override
  public boolean onMapClick(@NonNull LatLng mapClickPoint) {
	if (!isRouteVisible()) {
		return false;
	}
	Map<LineString, DirectionsRoute> routeLineStrings = routeLine.retrieveRouteLineStrings();
	if (invalidMapClick(routeLineStrings)) {
		return false;
	}
	List<DirectionsRoute> directionsRoutes = routeLine.retrieveDirectionsRoutes();
	findClickedRoute(mapClickPoint, directionsRoutes);
	return false;
  }

  void setOnRouteSelectionChangeListener(OnRouteSelectionChangeListener listener) {
	onRouteSelectionChangeListener = listener;
  }

  OnRouteSelectionChangeListener getOnRouteSelectionChangeListener() {
	return onRouteSelectionChangeListener;
  }

  void updateAlternativesVisible(boolean alternativesVisible) {
	this.alternativesVisible = alternativesVisible;
  }

  private boolean invalidMapClick(@Nullable Map<LineString, DirectionsRoute> routeLineStrings) {
	return routeLineStrings == null || routeLineStrings.isEmpty() || !alternativesVisible;
  }

  private boolean isRouteVisible() {
	return routeLine.retrieveVisibility();
  }

  /**
   * Updates the padding values used to build the query {@link RectF} box that is
   * used to determine whether a {@link DirectionsRoute} should be considered selected
   * by the {@link MapboxMap} click.
   *
   * @param newRouteClickPadding the new array of integers to be used for the four
   *                             padding dimensions of the {@link RectF}.
   */
  void updateRouteClickPadding(int[] newRouteClickPadding) {
	leftPadding = newRouteClickPadding[0];
	topPadding = newRouteClickPadding[1];
	rightPadding = newRouteClickPadding[2];
	bottomPadding = newRouteClickPadding[3];
  }

  private void findClickedRoute(@NonNull LatLng mapClickLatLng, @NonNull List<DirectionsRoute> directionsRoutes) {
	mapboxMap.getStyle(style -> {
		Projection mapProjection = mapboxMap.getProjection();
		PointF mapClickPointF = mapProjection.toScreenLocation(mapClickLatLng);
		float leftFloat = (mapClickPointF.x - leftPadding);
		float topFloat = (mapClickPointF.y - topPadding);
		float rightFloat = (mapClickPointF.x + rightPadding);
		float bottomFloat = (mapClickPointF.y + bottomPadding);
		routeClickRectF = new RectF(leftFloat, topFloat, rightFloat, bottomFloat);
		int newPrimaryRouteIndex = 0;
		List<Feature> selectedPointFPrimaryRouteLayerFeatureList = mapboxMap.queryRenderedFeatures(mapClickPointF, PRIMARY_ROUTE_LAYER_ID, PRIMARY_ROUTE_CASING_LAYER_ID);
		List<Feature> selectedPointFAlternativeRouteLayerFeatureList = mapboxMap.queryRenderedFeatures(mapClickPointF, ALTERNATIVE_ROUTE_LAYER_ID, ALTERNATIVE_ROUTE_CASING_LAYER_ID);
		List<Feature> selectedBoxPrimaryRouteLayerFeatureList = mapboxMap.queryRenderedFeatures(routeClickRectF, PRIMARY_ROUTE_LAYER_ID, PRIMARY_ROUTE_CASING_LAYER_ID);
		List<Feature> selectedBoxAlternativeRouteLayerFeatureList = mapboxMap.queryRenderedFeatures(routeClickRectF, ALTERNATIVE_ROUTE_LAYER_ID, ALTERNATIVE_ROUTE_CASING_LAYER_ID);
		if (selectedPointFPrimaryRouteLayerFeatureList.isEmpty() && !selectedPointFAlternativeRouteLayerFeatureList.isEmpty() ||
			selectedBoxPrimaryRouteLayerFeatureList.isEmpty() && !selectedBoxAlternativeRouteLayerFeatureList.isEmpty()) {
			newPrimaryRouteIndex = 1;
		}
		DirectionsRoute selectedRoute = directionsRoutes.get(newPrimaryRouteIndex);
		routeLine.updatePrimaryRouteIndex(selectedRoute);
		if (onRouteSelectionChangeListener != null) {
			onRouteSelectionChangeListener.onNewPrimaryRouteSelected(selectedRoute);
		}
	});
  }
}
