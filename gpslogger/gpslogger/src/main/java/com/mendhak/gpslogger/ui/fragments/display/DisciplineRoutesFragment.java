package com.mendhak.gpslogger.ui.fragments.display;

import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.discipline.RouteMapView;

import java.util.Locale;

public class DisciplineRoutesFragment extends Fragment {

    private RouteMapView routeMapView;
    private TextView txtGpsInfo;
    private Button btnExternalMap;
    private Session session = Session.getInstance();

    public static DisciplineRoutesFragment newInstance() {
        return new DisciplineRoutesFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_discipline_routes, container, false);

        routeMapView = view.findViewById(R.id.routes_map_view);
        txtGpsInfo = view.findViewById(R.id.routes_txt_gps_info);
        btnExternalMap = view.findViewById(R.id.routes_btn_external_map);

        if (btnExternalMap != null) {
            btnExternalMap.setOnClickListener(v -> {
                if (routeMapView != null) routeMapView.openExternalMap();
            });
        }

        updateLocationInfo();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (routeMapView != null) {
            routeMapView.onResume();
        }
        updateLocationInfo();
    }

    @Override
    public void onPause() {
        if (routeMapView != null) {
            routeMapView.onPause();
        }
        super.onPause();
    }

    private void updateLocationInfo() {
        if (session.hasValidLocation() && routeMapView != null) {
            Location loc = session.getCurrentLocationInfo();
            routeMapView.setCurrentLocation(loc);

            if (txtGpsInfo != null) {
                String latStr = String.format(Locale.getDefault(), "%.5f°%s", Math.abs(loc.getLatitude()), loc.getLatitude() >= 0 ? "N" : "S");
                String lonStr = String.format(Locale.getDefault(), "%.5f°%s", Math.abs(loc.getLongitude()), loc.getLongitude() >= 0 ? "E" : "W");
                String acc = loc.hasAccuracy() ? String.format(Locale.getDefault(), " • ±%.1fm", loc.getAccuracy()) : "";
                txtGpsInfo.setText(String.format("🛰 %s, %s%s", latStr, lonStr, acc));
            }
        }
    }
}
