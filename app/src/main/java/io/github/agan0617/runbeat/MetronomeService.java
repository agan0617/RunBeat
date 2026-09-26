package io.github.agan0617.runbeat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;

/**
 * 前景服務：用 AudioTrack 串流自己合成的 PCM，節拍落在精確的取樣點上，
 * 不靠 Handler 計時，所以背景、鎖螢幕都不會飄。
 *
 * 刻意不要求音訊焦點：跑步時通常同時在放音樂，要求焦點會讓音樂 App 暫停。
 */
public class MetronomeService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_STOP = "stop";
    static final String ACTION_TOGGLE = "toggle";
    static final String ACTION_BPM_DELTA = "bpm_delta";
    static final String ACTION_UPDATE = "update";   // 設定已寫進 prefs，重新讀
    static final String EXTRA_DELTA = "delta";

    static final String PREFS = "runbeat";
    static final String KEY_BPM = "bpm";
    static final String KEY_SOUND = "sound";
    static final String KEY_VOLUME = "volume";
    static final int MIN_BPM = 60, MAX_BPM = 240, DEFAULT_BPM = 170, DEFAULT_VOLUME = 80;

    private static final String CHANNEL = "metronome";
    private static final int NOTIF_ID = 1;

    /** 給畫面更新用（同一個 process，不必 bind）。 */
    interface Listener { void onStateChanged(); }
    static volatile Listener listener;
    static volatile boolean running;

    private volatile int bpm = DEFAULT_BPM;
    private volatile int soundType;
    private volatile float volume = DEFAULT_VOLUME / 100f;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static int clampBpm(int v) {
        return Math.max(MIN_BPM, Math.min(MAX_BPM, v));
    }

    static void send(Context c, String action) {
        Intent i = new Intent(c, MetronomeService.class).setAction(action);
        if (ACTION_START.equals(action) || ACTION_TOGGLE.equals(action)) {
            c.startForegroundService(i);
        } else if (running) {
            c.startService(i);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadPrefs();
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (action == null) action = ACTION_START;
        switch (action) {
            case ACTION_TOGGLE:
                if (running) stopBeat(); else startBeat();
                break;
            case ACTION_STOP:
                stopBeat();
                break;
            case ACTION_BPM_DELTA:
                int v = clampBpm(bpm + intent.getIntExtra(EXTRA_DELTA, 0));
                prefs(this).edit().putInt(KEY_BPM, v).apply();
                bpm = v;
                break;
            case ACTION_UPDATE:
                break;
            default:
                startBeat();
        }
        if (running) {
            notifyState();
        } else {
            // 從 startForegroundService 進來卻不播，仍要先 startForeground 一次才不會被系統判錯
            startForegroundCompat(buildNotification());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        Listener l = listener;
        if (l != null) l.onStateChanged();
        return START_NOT_STICKY;
    }

    private void loadPrefs() {
        SharedPreferences p = prefs(this);
        bpm = clampBpm(p.getInt(KEY_BPM, DEFAULT_BPM));
        soundType = p.getInt(KEY_SOUND, 0);
        volume = p.getInt(KEY_VOLUME, DEFAULT_VOLUME) / 100f;
    }

    private void startBeat() {
        if (running) return;
        running = true;
        startForegroundCompat(buildNotification());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunBeat:beat");
        wakeLock.acquire(6 * 60 * 60 * 1000L); // 最長 6 小時，保險用
        worker = new Thread(this::audioLoop, "RunBeat-audio");
        worker.start();
    }

    private void stopBeat() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            try { w.join(500); } catch (InterruptedException ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int rate = Sounds.RATE;
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();

        final int chunk = 256; // 約 6 ms，換 BPM／音量反應快
        short[] buf = new short[chunk];
        int curType = soundType;
        float[] click = Sounds.make(curType);
        float[] playing = null;   // 正在響的那一拍（換聲音時不切斷前一拍）
        int clickPos = 0;
        long n = 0;
        double nextBeat = 0;      // 以取樣為單位；用 double 累加，BPM 不會因取整數而漂
        double lastBeat = 0;
        int lastBpm = bpm;

        while (running) {
            int b = bpm;
            if (b != lastBpm) {           // 改 BPM：從上一拍重新算下一拍
                nextBeat = Math.max(n, lastBeat + 60.0 * rate / b);
                lastBpm = b;
            }
            if (soundType != curType) {
                curType = soundType;
                click = Sounds.make(curType);
            }
            float vol = volume;
            for (int i = 0; i < chunk; i++, n++) {
                if (n >= nextBeat) {
                    playing = click;
                    clickPos = 0;
                    lastBeat = nextBeat;
                    nextBeat += 60.0 * rate / b;
                }
                float v = 0;
                if (playing != null) {
                    v = playing[clickPos++];
                    if (clickPos >= playing.length) playing = null;
                }
                buf[i] = (short) Math.round(v * vol * 32767);
            }
            track.write(buf, 0, chunk);
        }
        track.pause();
        track.flush();
        track.release();
    }

    private void startForegroundCompat(Notification n) {
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private void notifyState() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private PendingIntent action(String action, int delta, int req) {
        Intent i = new Intent(this, MetronomeService.class).setAction(action).putExtra(EXTRA_DELTA, delta);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "節拍器", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_beat)
                .setContentTitle(bpm + " BPM")
                .setContentText(Sounds.NAMES[Math.min(soundType, Sounds.NAMES.length - 1)])
                .setContentIntent(open)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "−5", action(ACTION_BPM_DELTA, -5, 1)).build())
                .addAction(new Notification.Action.Builder(null, "停止", action(ACTION_STOP, 0, 2)).build())
                .addAction(new Notification.Action.Builder(null, "+5", action(ACTION_BPM_DELTA, 5, 3)).build())
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    @Override
    public void onDestroy() {
        stopBeat();
        Listener l = listener;
        if (l != null) l.onStateChanged();
        super.onDestroy();
    }
}
