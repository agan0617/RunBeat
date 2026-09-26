package io.github.agan0617.runbeat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity implements MetronomeService.Listener {
    private SharedPreferences prefs;
    private TextView bpmText, volumeLabel, batteryHint;
    private SeekBar bpmBar, volumeBar;
    private Button toggle;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = MetronomeService.prefs(this);

        bpmText = findViewById(R.id.bpm);
        bpmBar = findViewById(R.id.bpmBar);
        volumeBar = findViewById(R.id.volumeBar);
        volumeLabel = findViewById(R.id.volumeLabel);
        toggle = findViewById(R.id.toggle);
        batteryHint = findViewById(R.id.batteryHint);

        bpmBar.setMax(MetronomeService.MAX_BPM - MetronomeService.MIN_BPM);
        bpmBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) setBpm(MetronomeService.MIN_BPM + p);
            }
        });
        findViewById(R.id.minus5).setOnClickListener(v -> setBpm(bpm() - 5));
        findViewById(R.id.minus1).setOnClickListener(v -> setBpm(bpm() - 1));
        findViewById(R.id.plus1).setOnClickListener(v -> setBpm(bpm() + 1));
        findViewById(R.id.plus5).setOnClickListener(v -> setBpm(bpm() + 5));

        RadioGroup group = findViewById(R.id.sounds);
        int cur = prefs.getInt(MetronomeService.KEY_SOUND, 0);
        for (int i = 0; i < Sounds.NAMES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(Sounds.NAMES[i]);
            rb.setTextSize(18);
            rb.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
            rb.setTag(i);
            group.addView(rb);
            if (i == cur) rb.setChecked(true);
        }
        group.setOnCheckedChangeListener((g, id) -> {
            int type = (int) g.findViewById(id).getTag();
            prefs.edit().putInt(MetronomeService.KEY_SOUND, type).apply();
            MetronomeService.send(this, MetronomeService.ACTION_UPDATE);
            if (!MetronomeService.running) preview(type);
        });

        volumeBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                prefs.edit().putInt(MetronomeService.KEY_VOLUME, p).apply();
                volumeLabel.setText("音量 " + p + "%");
                MetronomeService.send(MainActivity.this, MetronomeService.ACTION_UPDATE);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (!MetronomeService.running) preview(prefs.getInt(MetronomeService.KEY_SOUND, 0));
            }
        });

        toggle.setOnClickListener(v -> {
            askPermissionsOnce();
            MetronomeService.send(this, MetronomeService.ACTION_TOGGLE);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        MetronomeService.listener = this;
        refresh();
    }

    @Override
    protected void onPause() {
        MetronomeService.listener = null;
        super.onPause();
    }

    @Override
    public void onStateChanged() {
        runOnUiThread(this::refresh);
    }

    private int bpm() {
        return MetronomeService.clampBpm(prefs.getInt(MetronomeService.KEY_BPM, MetronomeService.DEFAULT_BPM));
    }

    private void setBpm(int v) {
        prefs.edit().putInt(MetronomeService.KEY_BPM, MetronomeService.clampBpm(v)).apply();
        MetronomeService.send(this, MetronomeService.ACTION_UPDATE);
        refresh();
    }

    private void refresh() {
        if (updating) return;
        updating = true;
        int b = bpm();
        bpmText.setText(String.valueOf(b));
        bpmBar.setProgress(b - MetronomeService.MIN_BPM);
        int vol = prefs.getInt(MetronomeService.KEY_VOLUME, MetronomeService.DEFAULT_VOLUME);
        volumeBar.setProgress(vol);
        volumeLabel.setText("音量 " + vol + "%");
        toggle.setText(MetronomeService.running ? "停止" : "開始");
        PowerManager pm = getSystemService(PowerManager.class);
        batteryHint.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);
        updating = false;
    }

    /** 沒在播時選聲音／放開音量條，試聽一下。 */
    private void preview(int type) {
        float vol = prefs.getInt(MetronomeService.KEY_VOLUME, MetronomeService.DEFAULT_VOLUME) / 100f;
        float[] s = Sounds.make(type);
        short[] pcm = new short[s.length];
        for (int i = 0; i < s.length; i++) pcm[i] = (short) Math.round(s[i] * vol * 32767);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(Sounds.RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(pcm, 0, pcm.length);
        t.setNotificationMarkerPosition(pcm.length);
        t.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override public void onMarkerReached(AudioTrack track) { track.release(); }
            @Override public void onPeriodicNotification(AudioTrack track) {}
        });
        t.play();
    }

    /** 第一次按開始時：要通知權限（前景服務的通知），再問要不要讓它不受省電限制。 */
    private void askPermissionsOnce() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean("asked_battery", false)) {
            prefs.edit().putBoolean("asked_battery", true).apply();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
