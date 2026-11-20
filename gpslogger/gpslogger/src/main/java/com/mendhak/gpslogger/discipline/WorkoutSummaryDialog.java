package com.mendhak.gpslogger.discipline;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.mendhak.gpslogger.R;

import java.util.Locale;

/**
 * Modern dark-mode modal presenting post-run workout summary, kilometer splits,
 * target pace verification result, and direct return to Discipline Lock.
 */
public class WorkoutSummaryDialog {

    public static void show(Context context, WorkoutSummary summary) {
        if (context == null || summary == null) return;

        float density = context.getResources().getDisplayMetrics().density;
        int dp8 = Math.round(8 * density);
        int dp12 = Math.round(12 * density);
        int dp16 = Math.round(16 * density);
        int dp20 = Math.round(20 * density);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Color.parseColor("#0F172A")); // Slate 900

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp20, dp20, dp20, dp20);
        scrollView.addView(root);

        // 1. Mission Status Banner
        LinearLayout banner = new LinearLayout(context);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp16, dp16, dp16, dp16);
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setCornerRadius(14 * density);

        TextView txtStatusTitle = new TextView(context);
        txtStatusTitle.setTextSize(16);
        txtStatusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        txtStatusTitle.setGravity(Gravity.CENTER);

        TextView txtStatusSub = new TextView(context);
        txtStatusSub.setTextSize(12);
        txtStatusSub.setGravity(Gravity.CENTER);
        txtStatusSub.setPadding(0, dp8 / 2, 0, 0);

        if (summary.isMissionCompleted) {
            bannerBg.setColor(Color.parseColor("#142E1F"));
            bannerBg.setStroke(Math.round(1.5f * density), Color.parseColor("#00E676"));
            txtStatusTitle.setText("🎉 MORNING MISSION COMPLETED!");
            txtStatusTitle.setTextColor(Color.parseColor("#00E676"));
            txtStatusSub.setText("Target distance & pace matched! Alarm silenced & day unlocked.");
            txtStatusSub.setTextColor(Color.parseColor("#A7F3D0"));
        } else {
            bannerBg.setColor(Color.parseColor("#3B1717"));
            bannerBg.setStroke(Math.round(1.5f * density), Color.parseColor("#FF5252"));
            txtStatusTitle.setText("⚠️ TARGET NOT MATCHED - ALARM RESTARTED");
            txtStatusTitle.setTextColor(Color.parseColor("#FF5252"));
            if (!summary.isDistanceMet) {
                txtStatusSub.setText(String.format(Locale.getDefault(),
                        "Distance not reached: %s < %s target! Run further!",
                        summary.getFormattedDistance(), summary.getFormattedTargetDistance()));
            } else {
                txtStatusSub.setText(String.format(Locale.getDefault(),
                        "Pace too slow: %s > %s target! Run faster!",
                        summary.getFormattedAvgPace(), summary.getFormattedTargetPace()));
            }
            txtStatusSub.setTextColor(Color.parseColor("#FECACA"));
        }
        banner.setBackground(bannerBg);
        banner.addView(txtStatusTitle);
        banner.addView(txtStatusSub);
        root.addView(banner);

        // 2. Metrics Grid
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp16, 0, dp16);

        // Row 1: Distance & Duration
        LinearLayout row1 = createMetricRow(context, density,
                "TOTAL DISTANCE", summary.getFormattedDistance(),
                "DURATION", summary.getFormattedDuration());
        // Row 2: Average Pace & Steps
        String stepsText = summary.stepCount > 0 ?
                String.format(Locale.getDefault(), "%d (%d spm)", summary.stepCount, summary.cadenceSpm) : "--";
        LinearLayout row2 = createMetricRow(context, density,
                "AVG PACE", summary.getFormattedAvgPace(),
                "STEPS / CADENCE", stepsText);

        grid.addView(row1);
        grid.addView(row2);
        root.addView(grid);

        // 3. Kilometer Splits Section
        TextView lblSplits = new TextView(context);
        lblSplits.setText("📊 KILOMETER SPLITS");
        lblSplits.setTextSize(13);
        lblSplits.setTypeface(Typeface.DEFAULT_BOLD);
        lblSplits.setTextColor(Color.parseColor("#94A3B8"));
        lblSplits.setPadding(0, 0, 0, dp8);
        root.addView(lblSplits);

        LinearLayout splitsContainer = new LinearLayout(context);
        splitsContainer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable splitsBg = new GradientDrawable();
        splitsBg.setColor(Color.parseColor("#1E293B"));
        splitsBg.setCornerRadius(12 * density);
        splitsContainer.setBackground(splitsBg);
        splitsContainer.setPadding(dp12, dp8, dp12, dp8);

        if (summary.splits.isEmpty()) {
            TextView noSplits = new TextView(context);
            noSplits.setText("Not enough distance recorded for 1km splits.");
            noSplits.setTextColor(Color.parseColor("#64748B"));
            noSplits.setTextSize(12);
            noSplits.setPadding(dp8, dp8, dp8, dp8);
            splitsContainer.addView(noSplits);
        } else {
            for (KmSplit split : summary.splits) {
                LinearLayout splitRow = new LinearLayout(context);
                splitRow.setOrientation(LinearLayout.HORIZONTAL);
                splitRow.setPadding(0, dp8, 0, dp8);
                splitRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView txtIndex = new TextView(context);
                txtIndex.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
                txtIndex.setText(String.format(Locale.getDefault(), "Km %d (%s)", split.splitNumber, split.getFormattedDistance()));
                txtIndex.setTextColor(Color.parseColor("#E2E8F0"));
                txtIndex.setTextSize(12.5f);
                txtIndex.setTypeface(Typeface.DEFAULT_BOLD);

                TextView txtTime = new TextView(context);
                txtTime.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                txtTime.setText(split.getFormattedDuration());
                txtTime.setTextColor(Color.parseColor("#94A3B8"));
                txtTime.setTextSize(12f);
                txtTime.setGravity(Gravity.CENTER);

                TextView txtPace = new TextView(context);
                txtPace.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
                txtPace.setText((split.isOnPace ? "🟢 " : "🔴 ") + split.getFormattedPace());
                txtPace.setTextColor(split.isOnPace ? Color.parseColor("#00E676") : Color.parseColor("#FF5252"));
                txtPace.setTextSize(12.5f);
                txtPace.setTypeface(Typeface.DEFAULT_BOLD);
                txtPace.setGravity(Gravity.END);

                splitRow.addView(txtIndex);
                splitRow.addView(txtTime);
                splitRow.addView(txtPace);
                splitsContainer.addView(splitRow);
            }
        }
        root.addView(splitsContainer);

        // 4. Action Buttons
        LinearLayout buttonContainer = new LinearLayout(context);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setPadding(0, dp16, 0, 0);

        Button btnReturn = new Button(context);
        btnReturn.setText("📲 Return to Discipline Lock");
        btnReturn.setBackgroundResource(R.drawable.hud_button_start);
        btnReturn.setTextColor(Color.BLACK);
        btnReturn.setTypeface(Typeface.DEFAULT_BOLD);
        btnReturn.setTextSize(14);
        btnReturn.setPadding(0, dp12, 0, dp12);

        Button btnClose = new Button(context);
        btnClose.setText("Close Summary");
        btnClose.setTextColor(Color.parseColor("#94A3B8"));
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setTextSize(13);
        btnClose.setPadding(0, dp8, 0, dp8);

        buttonContainer.addView(btnReturn);
        buttonContainer.addView(btnClose);
        root.addView(buttonContainer);

        builder.setView(scrollView);
        AlertDialog dialog = builder.create();

        btnReturn.setOnClickListener(v -> {
            dialog.dismiss();
            launchDisciplineLock(context);
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private static LinearLayout createMetricRow(Context context, float density,
                                                String lbl1, String val1,
                                                String lbl2, String val2) {
        int dp8 = Math.round(8 * density);
        int dp12 = Math.round(12 * density);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp8 / 2, 0, dp8 / 2);

        row.addView(createCard(context, density, dp8, dp12, lbl1, val1));
        row.addView(createCard(context, density, dp8, dp12, lbl2, val2));
        return row;
    }

    private static View createCard(Context context, float density, int dp8, int dp12, String label, String value) {
        LinearLayout card = new LinearLayout(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp8 / 2, 0, dp8 / 2, 0);
        card.setLayoutParams(lp);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp12, dp8, dp12, dp8);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E293B"));
        bg.setCornerRadius(10 * density);
        card.setBackground(bg);

        TextView lbl = new TextView(context);
        lbl.setText(label);
        lbl.setTextColor(Color.parseColor("#64748B"));
        lbl.setTextSize(10.5f);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);

        TextView val = new TextView(context);
        val.setText(value);
        val.setTextColor(Color.WHITE);
        val.setTextSize(16);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setPadding(0, dp8 / 4, 0, 0);

        card.addView(lbl);
        card.addView(val);
        return card;
    }

    private static void launchDisciplineLock(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage("com.personal.disciplinelock.debug");
            if (intent == null) {
                intent = context.getPackageManager().getLaunchIntentForPackage("com.personal.disciplinelock");
            }
            if (intent == null) {
                intent = new Intent();
                intent.setComponent(new ComponentName("com.personal.disciplinelock.debug", "com.personal.disciplinelock.ui.LockGateActivity"));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent();
                fallback.setComponent(new ComponentName("com.personal.disciplinelock", "com.personal.disciplinelock.ui.LockGateActivity"));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(fallback);
            } catch (Exception ex) {
                Toast.makeText(context, "Could not open Discipline Lock: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
