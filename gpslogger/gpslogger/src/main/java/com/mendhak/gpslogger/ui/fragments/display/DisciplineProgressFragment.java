package com.mendhak.gpslogger.ui.fragments.display;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.discipline.WorkoutDatabaseHelper;

import java.util.Locale;

public class DisciplineProgressFragment extends Fragment {

    private TextView txtWeeklyDist;
    private TextView txtWeeklySubtext;
    private TextView txtStreakDays;
    private TextView txtPbSplit;
    private TextView txtPbDistance;
    private TextView txtPbPace;
    private TextView txtTotalWorkouts;

    public static DisciplineProgressFragment newInstance() {
        return new DisciplineProgressFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_discipline_progress, container, false);

        txtWeeklyDist = view.findViewById(R.id.progress_txt_weekly_dist);
        txtWeeklySubtext = view.findViewById(R.id.progress_txt_weekly_subtext);
        txtStreakDays = view.findViewById(R.id.progress_txt_streak_days);
        txtPbSplit = view.findViewById(R.id.progress_txt_pb_split);
        txtPbDistance = view.findViewById(R.id.progress_txt_pb_distance);
        txtPbPace = view.findViewById(R.id.progress_txt_pb_pace);
        txtTotalWorkouts = view.findViewById(R.id.progress_txt_total_workouts);

        loadProgressData();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadProgressData();
    }

    private void loadProgressData() {
        if (getContext() == null) return;
        WorkoutDatabaseHelper db = WorkoutDatabaseHelper.getInstance(requireContext());

        double weeklyKm = db.getWeeklyDistanceKm();
        if (txtWeeklyDist != null) {
            txtWeeklyDist.setText(String.format(Locale.getDefault(), "%.2f", weeklyKm));
        }
        if (txtWeeklySubtext != null) {
            txtWeeklySubtext.setText(weeklyKm > 0
                    ? String.format(Locale.getDefault(), "%.1f km completed this week. Keep up the morning momentum!", weeklyKm)
                    : "No workouts recorded yet this week. Start a morning run!");
        }

        int streak = db.getCurrentStreakDays();
        if (txtStreakDays != null) {
            txtStreakDays.setText("🔥 " + streak);
        }

        WorkoutDatabaseHelper.PersonalBests pb = db.getPersonalBests();
        if (txtPbSplit != null) {
            txtPbSplit.setText(formatPace(pb.fastestSplitPace));
        }
        if (txtPbDistance != null) {
            txtPbDistance.setText(pb.longestDistanceMeters > 0
                    ? String.format(Locale.getDefault(), "%.2f km", pb.longestDistanceMeters / 1000.0)
                    : "-- km");
        }
        if (txtPbPace != null) {
            txtPbPace.setText(formatPace(pb.bestAvgPace));
        }

        int totalCount = db.getAllWorkouts().size();
        if (txtTotalWorkouts != null) {
            txtTotalWorkouts.setText(String.format(Locale.getDefault(), "%d Total Workouts Logged", totalCount));
        }
    }

    private String formatPace(float pace) {
        if (pace <= 0.1f || Float.isInfinite(pace)) return "--:-- /km";
        int min = (int) pace;
        int sec = Math.round((pace - min) * 60f);
        if (sec >= 60) { min++; sec = 0; }
        return String.format(Locale.getDefault(), "%d:%02d /km", min, sec);
    }
}
