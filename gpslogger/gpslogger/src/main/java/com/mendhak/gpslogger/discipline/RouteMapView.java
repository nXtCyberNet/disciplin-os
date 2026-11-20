package com.mendhak.gpslogger.discipline;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Real road map view embedding OpenStreetMap/OSMDroid with live runner breadcrumbs.
 * Supports:
 * 1. CartoDB Dark Matter (default dark mode real roads, highways, buildings, parks)
 * 2. OpenStreetMap Standard (Mapnik bright road map)
 * 3. Ola Maps raster tile services (India-optimized, API key configurable)
 * 4. Pace-color segmented breadcrumb polyline (Green on-pace / Red slow)
 * 5. Pinned start beacon, live runner marker with bearing rotation, and auto-follow/recenter.
 */
public class RouteMapView extends FrameLayout {

    public static class RoutePoint {
        public final double lat;
        public final double lng;
        public final float speedMps;
        public final float paceMinPerKm;
        public final float bearing;
        public final long timestampMs;

        public RoutePoint(double lat, double lng, float speedMps, float paceMinPerKm, float bearing, long timestampMs) {
            this.lat = lat;
            this.lng = lng;
            this.speedMps = speedMps;
            this.paceMinPerKm = paceMinPerKm;
            this.bearing = bearing;
            this.timestampMs = timestampMs;
        }
    }

    public static final String STYLE_DARK_MAP = "dark_map";
    public static final String STYLE_OSM_STANDARD = "osm_standard";
    public static final String STYLE_OLA_MAPS = "ola_maps";
    public static final String STYLE_CARTO_DARK = "carto_dark";

    private static final String PREF_MAP_STYLE = "hud_map_style";
    private static final String PREF_OLA_MAPS_KEY = "hud_ola_maps_key";

    // CartoDB Dark Matter Tile Source
    public static final OnlineTileSourceBase CARTO_DARK = new XYTileSource(
            "CartoDark",
            0, 20, 256, ".png",
            new String[]{
                    "https://a.basemaps.cartocdn.com/rastertiles/dark_all/",
                    "https://b.basemaps.cartocdn.com/rastertiles/dark_all/",
                    "https://c.basemaps.cartocdn.com/rastertiles/dark_all/"
            },
            "© OpenStreetMap, © CARTO"
    );

    private MapView mapView;
    private final List<RoutePoint> points = Collections.synchronizedList(new ArrayList<>());
    private float targetPaceMinPerKm = 6.0f;
    private boolean autoCenter = true;

    // Overlays
    private Marker startMarker;
    private Marker runnerMarker;
    private Marker currentLocationMarker;
    private Drawable runnerIcon;
    private Drawable startIcon;
    private Drawable currentLocationIcon;
    private GeoPoint previousGeoPoint;

    // Floating UI elements
    private TextView txtStyleBadge;
    private TextView txtStatusBadge;
    private TextView txtCurrentLocationBadge;
    private TextView btnRecenter;
    private TextView txtEmptyPlaceholder;

    private String currentStyle = STYLE_DARK_MAP;
    private String olaMapsApiKey = "";

    public RouteMapView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public RouteMapView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public RouteMapView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // Initialize OSMDroid cache in app cache directory
        try {
            Configuration.getInstance().setUserAgentValue(context.getPackageName());
            File basePath = new File(context.getCacheDir(), "osmdroid");
            File tileCache = new File(basePath, "tiles");
            if (!tileCache.exists()) tileCache.mkdirs();
            Configuration.getInstance().setOsmdroidBasePath(basePath);
            Configuration.getInstance().setOsmdroidTileCache(tileCache);
        } catch (Exception ignored) {}

        // Load saved preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        currentStyle = prefs.getString(PREF_MAP_STYLE, STYLE_DARK_MAP);
        olaMapsApiKey = prefs.getString(PREF_OLA_MAPS_KEY, "");

        float density = getResources().getDisplayMetrics().density;

        // 1. MapView Setup
        mapView = new MapView(context);
        mapView.setDestroyMode(false);
        mapView.setMultiTouchControls(true);
        mapView.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        mapView.getController().setZoom(16.5);
        mapView.setBackgroundColor(Color.parseColor("#0B111E"));

        // Disable standard repetition
        mapView.setHorizontalMapRepetitionEnabled(false);
        mapView.setVerticalMapRepetitionEnabled(false);

        // Set initial center: try device's last known location, fallback to Bangalore / India coordinates
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Location loc = null;
            if (lm != null) {
                loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
            }
            if (loc != null) {
                mapView.getController().setCenter(new GeoPoint(loc.getLatitude(), loc.getLongitude()));
            } else {
                mapView.getController().setCenter(new GeoPoint(12.9716, 77.5946)); // Bangalore, India
            }
        } catch (SecurityException ignored) {
            mapView.getController().setCenter(new GeoPoint(12.9716, 77.5946));
        }

        addView(mapView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Create marker icons
        runnerIcon = createRunnerIcon(density);
        startIcon = createStartIcon(density);

        // Apply selected tile style
        applyTileStyle(currentStyle);

        // 3. Floating Overlay Controls
        buildFloatingControls(context, density);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_MOVE) {
            if (autoCenter) {
                autoCenter = false;
                updateRecenterButton();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void buildFloatingControls(Context context, float density) {
        int dp8 = Math.round(8 * density);
        int dp6 = Math.round(6 * density);
        int dp12 = Math.round(12 * density);

        // Top-Left: Style Badge ("🗺️ Carto Dark ▾")
        txtStyleBadge = new TextView(context);
        txtStyleBadge.setText(getStyleDisplayName(currentStyle) + " ▾");
        txtStyleBadge.setTextColor(Color.parseColor("#E2E8F0"));
        txtStyleBadge.setTextSize(11f);
        txtStyleBadge.setTypeface(Typeface.DEFAULT_BOLD);
        txtStyleBadge.setPadding(dp12, dp6, dp12, dp6);

        GradientDrawable styleBg = new GradientDrawable();
        styleBg.setColor(Color.parseColor("#E60F172A"));
        styleBg.setCornerRadius(16 * density);
        styleBg.setStroke(Math.round(1f * density), Color.parseColor("#334155"));
        txtStyleBadge.setBackground(styleBg);

        LayoutParams lpStyle = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpStyle.gravity = Gravity.TOP | Gravity.START;
        lpStyle.setMargins(dp8, dp8, 0, 0);
        txtStyleBadge.setLayoutParams(lpStyle);
        txtStyleBadge.setElevation(8f * density);
        txtStyleBadge.setOnClickListener(v -> showStyleChooserDialog());
        addView(txtStyleBadge);

        // Top-Right: Status Badge ("📍 0 pts")
        txtStatusBadge = new TextView(context);
        txtStatusBadge.setText("📍 0 pts");
        txtStatusBadge.setTextColor(Color.parseColor("#94A3B8"));
        txtStatusBadge.setTextSize(11f);
        txtStatusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        txtStatusBadge.setPadding(dp12, dp6, dp12, dp6);

        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(Color.parseColor("#E60F172A"));
        statusBg.setCornerRadius(16 * density);
        statusBg.setStroke(Math.round(1f * density), Color.parseColor("#334155"));
        txtStatusBadge.setBackground(statusBg);

        LayoutParams lpStatus = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpStatus.gravity = Gravity.TOP | Gravity.END;
        lpStatus.setMargins(0, dp8, dp8, 0);
        txtStatusBadge.setLayoutParams(lpStatus);
        txtStatusBadge.setElevation(8f * density);
        addView(txtStatusBadge);

        // Bottom-Right: Recenter Button ("🎯 Recenter")
        btnRecenter = new TextView(context);
        btnRecenter.setText("🎯 Recenter");
        btnRecenter.setTextColor(Color.BLACK);
        btnRecenter.setTextSize(11.5f);
        btnRecenter.setTypeface(Typeface.DEFAULT_BOLD);
        btnRecenter.setPadding(dp12, dp6, dp12, dp6);

        GradientDrawable recenterBg = new GradientDrawable();
        recenterBg.setColor(Color.parseColor("#00E5FF"));
        recenterBg.setCornerRadius(16 * density);
        btnRecenter.setBackground(recenterBg);

        LayoutParams lpRecenter = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpRecenter.gravity = Gravity.BOTTOM | Gravity.END;
        lpRecenter.setMargins(0, 0, dp8, dp8);
        btnRecenter.setLayoutParams(lpRecenter);
        btnRecenter.setElevation(10f * density);
        btnRecenter.setOnClickListener(v -> recenterOnRunner());
        btnRecenter.setVisibility(View.GONE);
        addView(btnRecenter);

        // Bottom-Left: Coordinates Badge ("📍 28.6326° N, 77.2215° E")
        txtCurrentLocationBadge = new TextView(context);
        txtCurrentLocationBadge.setTextColor(Color.parseColor("#E2E8F0"));
        txtCurrentLocationBadge.setTextSize(10.5f);
        txtCurrentLocationBadge.setTypeface(Typeface.MONOSPACE);
        txtCurrentLocationBadge.setPadding(dp12, dp6, dp12, dp6);

        GradientDrawable locBg = new GradientDrawable();
        locBg.setColor(Color.parseColor("#E60F172A"));
        locBg.setCornerRadius(14 * density);
        locBg.setStroke(Math.round(1f * density), Color.parseColor("#334155"));
        txtCurrentLocationBadge.setBackground(locBg);

        LayoutParams lpLoc = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpLoc.gravity = Gravity.BOTTOM | Gravity.START;
        lpLoc.setMargins(dp8, 0, 0, dp8);
        txtCurrentLocationBadge.setLayoutParams(lpLoc);
        txtCurrentLocationBadge.setElevation(8f * density);
        txtCurrentLocationBadge.setVisibility(View.GONE);
        txtCurrentLocationBadge.setOnClickListener(v -> recenterOnCurrentLocation());
        addView(txtCurrentLocationBadge);

        // Center: Empty Placeholder
        txtEmptyPlaceholder = new TextView(context);
        txtEmptyPlaceholder.setText("🗺️ Real Map Primed • Waiting for workout movement...");
        txtEmptyPlaceholder.setTextColor(Color.parseColor("#94A3B8"));
        txtEmptyPlaceholder.setTextSize(12f);
        txtEmptyPlaceholder.setGravity(Gravity.CENTER);
        txtEmptyPlaceholder.setPadding(dp12, dp8, dp12, dp8);

        GradientDrawable placeholderBg = new GradientDrawable();
        placeholderBg.setColor(Color.parseColor("#CC0B111E"));
        placeholderBg.setCornerRadius(12 * density);
        txtEmptyPlaceholder.setBackground(placeholderBg);

        LayoutParams lpPlaceholder = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpPlaceholder.gravity = Gravity.CENTER;
        txtEmptyPlaceholder.setLayoutParams(lpPlaceholder);
        txtEmptyPlaceholder.setElevation(8f * density);
        addView(txtEmptyPlaceholder);
    }

    private void applyTileStyle(String styleKey) {
        this.currentStyle = styleKey;
        if (STYLE_OSM_STANDARD.equals(styleKey)) {
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            if (mapView.getOverlayManager().getTilesOverlay() != null) {
                mapView.getOverlayManager().getTilesOverlay().setColorFilter(null);
            }
            mapView.setBackgroundColor(Color.parseColor("#E2E8F0"));
        } else if (STYLE_OLA_MAPS.equals(styleKey)) {
            if (mapView.getOverlayManager().getTilesOverlay() != null) {
                mapView.getOverlayManager().getTilesOverlay().setColorFilter(null);
            }
            mapView.setBackgroundColor(Color.parseColor("#0B111E"));
            if (olaMapsApiKey != null && !olaMapsApiKey.trim().isEmpty()) {
                mapView.setTileSource(createOlaTileSource(olaMapsApiKey.trim(), true));
            } else {
                Toast.makeText(getContext(), "Please configure your Ola Maps API key.", Toast.LENGTH_SHORT).show();
                promptOlaMapsApiKey();
                return;
            }
        } else if (STYLE_CARTO_DARK.equals(styleKey)) {
            if (mapView.getOverlayManager().getTilesOverlay() != null) {
                mapView.getOverlayManager().getTilesOverlay().setColorFilter(null);
            }
            mapView.setBackgroundColor(Color.parseColor("#0B111E"));
            mapView.setTileSource(CARTO_DARK);
        } else {
            // Default: Dark Road Map (OpenStreetMap with high-contrast inverted cyber filter)
            this.currentStyle = STYLE_DARK_MAP;
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            mapView.setBackgroundColor(Color.parseColor("#0B111E"));
            float[] darkMatrix = {
                    -0.85f, 0, 0, 0, 220, // red
                    0, -0.85f, 0, 0, 220, // green
                    0, 0, -0.85f, 0, 220, // blue
                    0, 0, 0, 1.0f, 0      // alpha
            };
            ColorMatrix colorMatrix = new ColorMatrix(darkMatrix);
            if (mapView.getOverlayManager().getTilesOverlay() != null) {
                mapView.getOverlayManager().getTilesOverlay().setColorFilter(new ColorMatrixColorFilter(colorMatrix));
            }
        }

        if (txtStyleBadge != null) {
            txtStyleBadge.setText(getStyleDisplayName(currentStyle) + " ▾");
        }

        // Save preference
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .edit()
                .putString(PREF_MAP_STYLE, currentStyle)
                .apply();

        mapView.invalidate();
    }

    private OnlineTileSourceBase createOlaTileSource(final String apiKey, boolean darkMode) {
        String style = darkMode ? "default-dark" : "default-light";
        return new XYTileSource(
                "OlaMapsDark",
                0, 19, 256, ".png",
                new String[]{
                        "https://api.olamaps.io/tiles/v1/styles/" + style + "/tiles/"
                },
                "© Ola Maps"
        ) {
            @Override
            public String getTileURLString(long pMapTileIndex) {
                return super.getTileURLString(pMapTileIndex) + "?api_key=" + apiKey;
            }
        };
    }

    private String getStyleDisplayName(String styleKey) {
        if (STYLE_OSM_STANDARD.equals(styleKey)) return "☀️ OpenStreetMap";
        if (STYLE_OLA_MAPS.equals(styleKey)) return "🇮🇳 Ola Maps";
        if (STYLE_CARTO_DARK.equals(styleKey)) return "🗺️ Carto Dark";
        return "🌙 Dark Road Map";
    }

    public void showStyleChooserDialog() {
        String[] options = {
                "🌙 Dark Road Map (Default - Free Real Roads)",
                "☀️ OpenStreetMap Standard (Light Mode)",
                "🇮🇳 Ola Maps (India Optimized)",
                "🗺️ CartoDB Dark Matter",
                "🔑 Configure Ola Maps API Key..."
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Select Map Style");
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                applyTileStyle(STYLE_DARK_MAP);
            } else if (which == 1) {
                applyTileStyle(STYLE_OSM_STANDARD);
            } else if (which == 2) {
                if (olaMapsApiKey == null || olaMapsApiKey.trim().isEmpty()) {
                    promptOlaMapsApiKey();
                } else {
                    applyTileStyle(STYLE_OLA_MAPS);
                }
            } else if (which == 3) {
                applyTileStyle(STYLE_CARTO_DARK);
            } else if (which == 4) {
                promptOlaMapsApiKey();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    public void promptOlaMapsApiKey() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Ola Maps API Key");
        builder.setMessage("Enter your Ola Maps API key to stream vector/raster real map tiles.");

        final EditText input = new EditText(getContext());
        input.setHint("e.g. 0LaMap5_xxxxxxxxxxxxxxxx");
        input.setText(olaMapsApiKey);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);

        builder.setPositiveButton("Save & Use", (dialog, which) -> {
            String key = input.getText().toString().trim();
            this.olaMapsApiKey = key;
            PreferenceManager.getDefaultSharedPreferences(getContext())
                    .edit()
                    .putString(PREF_OLA_MAPS_KEY, key)
                    .apply();

            if (!key.isEmpty()) {
                applyTileStyle(STYLE_OLA_MAPS);
                Toast.makeText(getContext(), "Ola Maps activated!", Toast.LENGTH_SHORT).show();
            } else {
                applyTileStyle(STYLE_DARK_MAP);
                Toast.makeText(getContext(), "Key cleared. Switched to Dark Road Map.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    public void addPoint(double lat, double lng, float speedMps, float paceMinPerKm, float bearing) {
        if (Double.isNaN(lat) || Double.isNaN(lng) || (lat == 0.0 && lng == 0.0)) return;

        RoutePoint pt = new RoutePoint(lat, lng, speedMps, paceMinPerKm, bearing, System.currentTimeMillis());
        points.add(pt);

        post(() -> {
            GeoPoint gp = new GeoPoint(lat, lng);

            if (txtEmptyPlaceholder != null && txtEmptyPlaceholder.getVisibility() == View.VISIBLE) {
                txtEmptyPlaceholder.setVisibility(View.GONE);
            }

            // 1. Initial point: Create Start Marker
            if (points.size() == 1 || startMarker == null) {
                startMarker = new Marker(mapView);
                startMarker.setPosition(gp);
                startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                startMarker.setIcon(startIcon);
                startMarker.setTitle("Start Point");
                startMarker.setInfoWindow(null);
                mapView.getOverlayManager().add(startMarker);

                mapView.getController().setZoom(17.0);
                mapView.getController().setCenter(gp);
                previousGeoPoint = gp;
            }

            // 2. Draw Polyline Segment between previous point and current point
            if (previousGeoPoint != null && (previousGeoPoint.getLatitude() != lat || previousGeoPoint.getLongitude() != lng)) {
                Polyline segment = new Polyline(mapView);
                boolean isOnPace = (paceMinPerKm > 0.1f && paceMinPerKm <= targetPaceMinPerKm);
                int segmentColor = isOnPace ? Color.parseColor("#00E676") : Color.parseColor("#FF5252");

                segment.getOutlinePaint().setColor(segmentColor);
                segment.getOutlinePaint().setStrokeWidth(Math.round(6f * getResources().getDisplayMetrics().density));
                segment.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
                segment.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
                segment.getOutlinePaint().setAntiAlias(true);

                List<GeoPoint> segmentPoints = new ArrayList<>();
                segmentPoints.add(previousGeoPoint);
                segmentPoints.add(gp);
                segment.setPoints(segmentPoints);

                // Insert polyline before markers so markers remain on top
                mapView.getOverlayManager().add(0, segment);
                previousGeoPoint = gp;
            }

            // 3. Update Runner Marker
            if (runnerMarker == null) {
                runnerMarker = new Marker(mapView);
                runnerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                runnerMarker.setIcon(runnerIcon);
                runnerMarker.setTitle("Current Runner Location");
                runnerMarker.setInfoWindow(null);
                mapView.getOverlayManager().add(runnerMarker);
            }
            runnerMarker.setPosition(gp);
            runnerMarker.setRotation(bearing);

            // 4. Auto-center camera if enabled
            if (autoCenter) {
                mapView.getController().animateTo(gp);
            }

            // 5. Update Status Badge
            if (txtStatusBadge != null) {
                boolean isOnPace = (paceMinPerKm > 0.1f && paceMinPerKm <= targetPaceMinPerKm);
                txtStatusBadge.setText(String.format(Locale.getDefault(), "📍 %d pts • %s",
                        points.size(), isOnPace ? "🟢 On Pace" : "🔴 Slower"));
            }

            mapView.invalidate();
        });
    }

    public void clearPoints() {
        points.clear();
        previousGeoPoint = null;
        post(() -> {
            if (mapView != null) {
                mapView.getOverlayManager().clear();
                startMarker = null;
                runnerMarker = null;
                currentLocationMarker = null;
                mapView.invalidate();
            }
            if (txtEmptyPlaceholder != null) {
                txtEmptyPlaceholder.setVisibility(View.VISIBLE);
            }
            if (txtStatusBadge != null) {
                txtStatusBadge.setText("📍 0 pts");
            }
            autoCenter = true;
            updateRecenterButton();
        });
    }

    public void setCurrentLocation(Location loc) {
        if (loc == null || Double.isNaN(loc.getLatitude()) || Double.isNaN(loc.getLongitude())) return;
        post(() -> {
            GeoPoint gp = new GeoPoint(loc.getLatitude(), loc.getLongitude());

            // If no workout points recorded yet, keep camera centered on current GPS fix
            if (points.isEmpty()) {
                if (autoCenter) {
                    mapView.getController().setCenter(gp);
                }
                if (txtEmptyPlaceholder != null && txtEmptyPlaceholder.getVisibility() == View.VISIBLE) {
                    txtEmptyPlaceholder.setText("📍 GPS Fixed • Ready to start workout");
                }
            }

            // Create or update current location marker
            if (currentLocationMarker == null) {
                currentLocationMarker = new Marker(mapView);
                currentLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                if (currentLocationIcon == null) {
                    currentLocationIcon = createCurrentLocationIcon(getResources().getDisplayMetrics().density);
                }
                currentLocationMarker.setIcon(currentLocationIcon);
                currentLocationMarker.setTitle("Current GPS Location");
                currentLocationMarker.setInfoWindow(null);
                mapView.getOverlayManager().add(currentLocationMarker);
            }
            currentLocationMarker.setPosition(gp);
            if (loc.hasBearing()) {
                currentLocationMarker.setRotation(loc.getBearing());
            }

            // If workout is actively tracking, runnerMarker represents current position, hide static pin
            if (!points.isEmpty()) {
                currentLocationMarker.setEnabled(false);
            } else {
                currentLocationMarker.setEnabled(true);
            }

            // Update bottom-left coordinates badge
            if (txtCurrentLocationBadge != null) {
                String latStr = String.format(Locale.US, "%.5f°%s", Math.abs(loc.getLatitude()), loc.getLatitude() >= 0 ? "N" : "S");
                String lonStr = String.format(Locale.US, "%.5f°%s", Math.abs(loc.getLongitude()), loc.getLongitude() >= 0 ? "E" : "W");
                String accStr = loc.hasAccuracy() ? String.format(Locale.US, " (±%.0fm)", loc.getAccuracy()) : "";
                txtCurrentLocationBadge.setText(String.format("📍 %s, %s%s", latStr, lonStr, accStr));
                txtCurrentLocationBadge.setVisibility(View.VISIBLE);
            }

            mapView.invalidate();
        });
    }

    public void recenterOnCurrentLocation() {
        autoCenter = true;
        updateRecenterButton();
        if (!points.isEmpty()) {
            recenterOnRunner();
            return;
        }
        if (currentLocationMarker != null && currentLocationMarker.getPosition() != null) {
            mapView.getController().setZoom(17.0);
            mapView.getController().animateTo(currentLocationMarker.getPosition());
        }
    }

    public void recenterOnRunner() {
        autoCenter = true;
        updateRecenterButton();
        if (!points.isEmpty()) {
            RoutePoint last = points.get(points.size() - 1);
            GeoPoint gp = new GeoPoint(last.lat, last.lng);
            mapView.getController().setZoom(17.5);
            mapView.getController().animateTo(gp);
        } else if (currentLocationMarker != null && currentLocationMarker.getPosition() != null) {
            mapView.getController().setZoom(17.0);
            mapView.getController().animateTo(currentLocationMarker.getPosition());
        }
    }

    private void updateRecenterButton() {
        if (btnRecenter != null) {
            btnRecenter.setVisibility(autoCenter ? View.GONE : View.VISIBLE);
        }
    }

    public List<RoutePoint> getPoints() {
        return new ArrayList<>(points);
    }

    public void setTargetPace(float pace) {
        this.targetPaceMinPerKm = pace;
    }

    public void openExternalMap() {
        if (points.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=current+location"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(intent);
            } catch (Exception ignored) {}
            return;
        }

        RoutePoint last = points.get(points.size() - 1);
        Uri gmmIntentUri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(Runner+Location)",
                last.lat, last.lng, last.lat, last.lng));
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(mapIntent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "No external maps app found.", Toast.LENGTH_SHORT).show();
        }
    }

    public void onResume() {
        if (mapView != null) {
            mapView.onResume();
        }
    }

    public void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
    }

    private Drawable createRunnerIcon(float density) {
        int size = Math.round(36 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint.setColor(Color.parseColor("#4D00E5FF"));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, haloPaint);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawCircle(size / 2f, size / 2f, 9 * density, dotPaint);

        Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerPaint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, 4 * density, innerPaint);

        Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStyle(Paint.Style.FILL);
        Path arrow = new Path();
        arrow.moveTo(size / 2f, 2 * density);
        arrow.lineTo(size / 2f - 4 * density, 8 * density);
        arrow.lineTo(size / 2f + 4 * density, 8 * density);
        arrow.close();
        canvas.drawPath(arrow, arrowPaint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    private Drawable createStartIcon(float density) {
        int size = Math.round(28 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint.setColor(Color.parseColor("#4D10B981"));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, haloPaint);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.parseColor("#10B981"));
        canvas.drawCircle(size / 2f, size / 2f, 9 * density, circlePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(9 * density);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("S", size / 2f, size / 2f + 3.2f * density, textPaint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    private Drawable createCurrentLocationIcon(float density) {
        int size = Math.round(32 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint.setColor(Color.parseColor("#4D00E5FF"));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, haloPaint);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.parseColor("#00E5FF"));
        canvas.drawCircle(size / 2f, size / 2f, 8 * density, circlePaint);

        Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerPaint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, 4 * density, innerPaint);

        return new BitmapDrawable(getResources(), bitmap);
    }
}
