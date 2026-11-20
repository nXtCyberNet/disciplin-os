package com.mendhak.gpslogger.ui.fragments.display;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.discipline.WorkoutDatabaseHelper;
import com.mendhak.gpslogger.discipline.WorkoutSummary;
import com.mendhak.gpslogger.discipline.WorkoutSummaryDialog;

import java.util.List;
import java.util.Locale;

public class DisciplineHistoryFragment extends Fragment {

    private ListView listView;
    private TextView txtEmpty;
    private TextView txtCount;
    private TextView btnRefresh;

    public static DisciplineHistoryFragment newInstance() {
        return new DisciplineHistoryFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_discipline_history, container, false);

        listView = view.findViewById(R.id.history_list_view);
        txtEmpty = view.findViewById(R.id.history_txt_empty);
        txtCount = view.findViewById(R.id.history_txt_count);
        btnRefresh = view.findViewById(R.id.history_btn_clear);

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadHistory());
        }

        loadHistory();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        if (getContext() == null) return;
        WorkoutDatabaseHelper db = WorkoutDatabaseHelper.getInstance(requireContext());
        List<WorkoutDatabaseHelper.WorkoutRecord> list = db.getAllWorkouts();

        if (list.isEmpty()) {
            if (txtEmpty != null) txtEmpty.setVisibility(View.VISIBLE);
            if (listView != null) listView.setVisibility(View.GONE);
            if (txtCount != null) txtCount.setText("No workout sessions recorded yet");
        } else {
            if (txtEmpty != null) txtEmpty.setVisibility(View.GONE);
            if (listView != null) listView.setVisibility(View.VISIBLE);
            if (txtCount != null) {
                txtCount.setText(String.format(Locale.getDefault(), "%d total completed workouts", list.size()));
            }

            HistoryAdapter adapter = new HistoryAdapter(requireContext(), list);
            listView.setAdapter(adapter);

            listView.setOnItemClickListener((parent, view, position, id) -> {
                WorkoutDatabaseHelper.WorkoutRecord r = list.get(position);
                float speedMps = r.durationSeconds > 0 ? (float) (r.distanceMeters / r.durationSeconds) : 0f;
                WorkoutSummary summary = new WorkoutSummary(
                        r.distanceMeters,
                        r.durationSeconds * 1000L,
                        speedMps,
                        r.avgPaceMinPerKm,
                        r.targetDistanceMeters,
                        r.targetPaceMinPerKm,
                        r.steps,
                        r.cadence,
                        r.splits
                );
                WorkoutSummaryDialog.show(getContext(), summary);
            });
        }
    }

    private static class HistoryAdapter extends ArrayAdapter<WorkoutDatabaseHelper.WorkoutRecord> {
        public HistoryAdapter(Context context, List<WorkoutDatabaseHelper.WorkoutRecord> items) {
            super(context, 0, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_workout_history, parent, false);
            }

            WorkoutDatabaseHelper.WorkoutRecord item = getItem(position);
            if (item != null) {
                TextView txtDate = convertView.findViewById(R.id.item_txt_date);
                TextView txtDistance = convertView.findViewById(R.id.item_txt_distance);
                TextView txtPace = convertView.findViewById(R.id.item_txt_pace);
                TextView txtDuration = convertView.findViewById(R.id.item_txt_duration);
                TextView txtBadge = convertView.findViewById(R.id.item_txt_verified_badge);
                TextView txtSubstats = convertView.findViewById(R.id.item_txt_substats);

                if (txtDate != null) txtDate.setText(item.getFormattedDate());
                if (txtDistance != null) txtDistance.setText(item.getFormattedDistance());
                if (txtPace != null) txtPace.setText(item.getFormattedPace());
                if (txtDuration != null) txtDuration.setText(item.getFormattedDuration());

                if (txtBadge != null) {
                    txtBadge.setText(item.verified ? "✅ Verified" : "⚠️ Unverified");
                    txtBadge.setTextColor(item.verified ? 0xFF34D399 : 0xFFF87171);
                }

                if (txtSubstats != null) {
                    txtSubstats.setText(String.format(Locale.getDefault(),
                            "🎯 Target: %.2f km @ < %.1f /km • %,d steps",
                            item.targetDistanceMeters / 1000.0, item.targetPaceMinPerKm, item.steps));
                }
            }
            return convertView;
        }
    }
}
