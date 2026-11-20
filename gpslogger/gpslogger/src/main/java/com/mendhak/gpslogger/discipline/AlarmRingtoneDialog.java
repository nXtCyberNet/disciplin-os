package com.mendhak.gpslogger.discipline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mendhak.gpslogger.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive dialog allowing the user to preview and customize their alarm ringtone.
 */
public class AlarmRingtoneDialog {

    public interface OnToneSelectedListener {
        void onToneSelected(String toneId, String toneName);
    }

    public static class ToneOption {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String uri;

        public ToneOption(String id, String title, String subtitle, String uri) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.uri = uri;
        }
    }

    public static void show(Context context, OnToneSelectedListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("🎵 Select Morning Alarm Ringtone");

        List<ToneOption> options = new ArrayList<>();
        options.add(new ToneOption(
                AlarmTonePlayer.TONE_SIREN,
                "🚨 Emergency Siren",
                "High-intensity frequency-modulated sweep (ultra loud)",
                null
        ));
        options.add(new ToneOption(
                AlarmTonePlayer.TONE_DIGITAL_BEEP,
                "⏰ Digital Alarm Clock",
                "Classic piercing 4-burst digital clock beeps (1200 Hz)",
                null
        ));
        options.add(new ToneOption(
                AlarmTonePlayer.TONE_MILITARY_BUGLE,
                "🎺 Military Reveille Bugle",
                "Traditional military wake-up call fanfare notes",
                null
        ));
        options.add(new ToneOption(
                AlarmTonePlayer.TONE_SYSTEM_ALARM,
                "🔔 System Default Alarm",
                "Standard Android device alarm sound",
                null
        ));

        String currentToneId = AlarmTonePlayer.getSelectedToneId(context);
        final int[] selectedIndex = {0};
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id.equals(currentToneId)) {
                selectedIndex[0] = i;
                break;
            }
        }

        ToneAdapter adapter = new ToneAdapter(context, options, selectedIndex);

        builder.setSingleChoiceItems(adapter, selectedIndex[0], (dialog, which) -> {
            selectedIndex[0] = which;
            ToneOption opt = options.get(which);
            // Play 2.5s preview so user hears the sound
            AlarmTonePlayer.getInstance().playPreview(context, opt.id, opt.uri, 2500);
            adapter.notifyDataSetChanged();
        });

        builder.setPositiveButton("Save Ringtone", (dialog, which) -> {
            AlarmTonePlayer.getInstance().stop();
            if (selectedIndex[0] >= 0 && selectedIndex[0] < options.size()) {
                ToneOption opt = options.get(selectedIndex[0]);
                AlarmTonePlayer.saveSelectedTone(context, opt.id, opt.title, opt.uri);
                Toast.makeText(context, "Saved alarm ringtone: " + opt.title, Toast.LENGTH_SHORT).show();
                if (listener != null) {
                    listener.onToneSelected(opt.id, opt.title);
                }
            }
        });

        builder.setNeutralButton("📱 System Picker...", (dialog, which) -> {
            AlarmTonePlayer.getInstance().stop();
            if (context instanceof Activity) {
                Intent pickerIntent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
                pickerIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                        RingtoneManager.TYPE_ALARM | RingtoneManager.TYPE_RINGTONE);
                pickerIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Custom Alarm Ringtone");
                pickerIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
                pickerIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                ((Activity) context).startActivityForResult(pickerIntent, 9922);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            AlarmTonePlayer.getInstance().stop();
            dialog.dismiss();
        });

        builder.setOnDismissListener(dialog -> AlarmTonePlayer.getInstance().stop());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private static class ToneAdapter extends ArrayAdapter<ToneOption> {
        private final int[] selectedIndex;

        public ToneAdapter(Context context, List<ToneOption> items, int[] selectedIndex) {
            super(context, 0, items);
            this.selectedIndex = selectedIndex;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            ToneOption item = getItem(position);
            if (item != null) {
                TextView text1 = convertView.findViewById(android.R.id.text1);
                TextView text2 = convertView.findViewById(android.R.id.text2);

                boolean isSelected = (position == selectedIndex[0]);
                text1.setText(item.title + (isSelected ? "  🔊 (Selected)" : ""));
                text1.setTextColor(isSelected ? 0xFF059669 : 0xFF0F172A);
                text1.setTextSize(15f);

                text2.setText(item.subtitle);
                text2.setTextColor(isSelected ? 0xFF047857 : 0xFF475569);
                text2.setTextSize(12f);
            }
            return convertView;
        }
    }
}
