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
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

/**
 * 前景服務：用 AudioTrack 串流自己合成的 PCM，節拍落在精確的取樣點上，
 * 不靠 Handler 計時，所以背景、鎖螢幕都不會飄。
 *
 * 刻意不要求音訊焦點：跑步時通常同時在放音樂，要求焦點會讓音樂 App 暫停。
 *
 * 1.4 起掛一個 MediaSession，手錶（小米運動健康的音樂控制）、耳機、鎖定畫面的媒體控制都能遙控：
 * ⏯ 開始／暫停、⏭ BPM +1、⏮ BPM −1，歌名顯示目前 BPM。
 * 從這些地方暫停時服務不收掉（保留前景與 MediaSession），不然錶上再按 ⏯ 就找不到它；
 * 暫停超過 30 分鐘才自動收掉。App 裡按「停止」或通知上的 ✕ 則直接收掉。
 */
public class MetronomeService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_STOP = "stop";       // 收掉服務
    static final String ACTION_TOGGLE = "toggle";   // App 的開始／停止鈕：沒在打就開始，在打就收掉
    static final String ACTION_PLAY_PAUSE = "play_pause"; // 通知上的 ⏯：暫停不收掉服務
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
    private static final String CUSTOM_MINUS5 = "minus5", CUSTOM_PLUS5 = "plus5", CUSTOM_CLOSE = "close";
    private static final long PAUSE_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 給畫面更新用（同一個 process，不必 bind）。 */
    interface Listener { void onStateChanged(); }
    static volatile Listener listener;
    static volatile boolean running;   // 正在打拍子
    static volatile boolean alive;     // 服務還在（打拍子中或暫停中）

    private volatile int bpm = DEFAULT_BPM;
    private volatile int soundType;
    private volatile float volume = DEFAULT_VOLUME / 100f;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private MediaSession session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pauseTimeout = this::close;

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
        } else if (alive) {
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
            case ACTION_PLAY_PAUSE:
                if (running) pauseBeat(); else startBeat();
                break;
            case ACTION_BPM_DELTA:
                changeBpm(intent.getIntExtra(EXTRA_DELTA, 0));
                break;
            case ACTION_UPDATE:
                break;
            default:
                startBeat();
        }
        if (!running && !alive) {
            // 從 startForegroundService 進來卻不播，仍要先 startForeground 一次才不會被系統判錯
            startForegroundCompat(buildNotification());
        }
        stateChanged();
        return START_NOT_STICKY;
    }

    private void loadPrefs() {
        SharedPreferences p = prefs(this);
        bpm = clampBpm(p.getInt(KEY_BPM, DEFAULT_BPM));
        soundType = p.getInt(KEY_SOUND, 0);
        volume = p.getInt(KEY_VOLUME, DEFAULT_VOLUME) / 100f;
    }

    private void changeBpm(int delta) {
        int v = clampBpm(bpm + delta);
        prefs(this).edit().putInt(KEY_BPM, v).apply();
        bpm = v;
    }

    private void startBeat() {
        main.removeCallbacks(pauseTimeout);
        if (running) return;
        running = true;
        alive = true;
        startForegroundCompat(buildNotification());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunBeat:beat");
        wakeLock.acquire(6 * 60 * 60 * 1000L); // 最長 6 小時，保險用
        worker = new Thread(this::audioLoop, "RunBeat-audio");
        worker.start();
    }

    /** 停止打拍子，服務收掉。 */
    private void stopBeat() {
        main.removeCallbacks(pauseTimeout);
        haltAudio();
        alive = false;
    }

    /** 停止打拍子但服務留著（前景＋MediaSession），錶上按 ⏯ 才叫得回來。 */
    private void pauseBeat() {
        if (!running) return;
        haltAudio();
        main.removeCallbacks(pauseTimeout);
        main.postDelayed(pauseTimeout, PAUSE_TIMEOUT_MS);
    }

    private void close() {
        stopBeat();
        stateChanged();
    }

    private void haltAudio() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            try { w.join(500); } catch (InterruptedException ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    /** 狀態變了：更新 MediaSession、通知與畫面；服務不用留就收掉。 */
    private void stateChanged() {
        if (alive) {
            updateSession();
            notifyState();
        } else {
            releaseSession();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        Listener l = listener;
        if (l != null) l.onStateChanged();
    }

    // ─────────────── MediaSession：手錶、耳機、鎖定畫面的媒體控制 ───────────────

    private void ensureSession() {
        if (session != null) return;
        session = new MediaSession(this, "RunBeat");
        session.setSessionActivity(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { loadPrefs(); startBeat(); stateChanged(); }
            @Override public void onPause() { pauseBeat(); stateChanged(); }
            @Override public void onStop() { close(); }
            @Override public void onSkipToNext() { loadPrefs(); changeBpm(1); stateChanged(); }
            @Override public void onSkipToPrevious() { loadPrefs(); changeBpm(-1); stateChanged(); }
            @Override public void onCustomAction(String action, Bundle extras) {
                loadPrefs();
                if (CUSTOM_MINUS5.equals(action)) changeBpm(-5);
                else if (CUSTOM_PLUS5.equals(action)) changeBpm(5);
                else if (CUSTOM_CLOSE.equals(action)) { close(); return; }
                stateChanged();
            }
        }, main);
        session.setActive(true);
    }

    private void updateSession() {
        ensureSession();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, bpm + " BPM")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, soundName())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "RunBeat")
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(running ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, running ? 1f : 0f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                // Android 13 起媒體通知的按鈕由這裡決定：⏮／⏭ 是 ∓1，另外補 −5、+5、關閉
                .addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_MINUS5, "BPM −5", R.drawable.ic_act_minus5).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_PLUS5, "BPM +5", R.drawable.ic_act_plus5).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_CLOSE, "關閉", R.drawable.ic_act_close).build())
                .build());
    }

    private void releaseSession() {
        if (session == null) return;
        session.setActive(false);
        session.release();
        session = null;
    }

    // ─────────────── 音訊 ───────────────

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
        // 同時響著的拍子：拍子快、聲音長時前一拍還沒結束，讓它自然衰減，不硬切（硬切會「啪」）
        final int voices = 4;
        float[][] playing = new float[voices][];
        int[] pos = new int[voices];
        int nextVoice = 0;
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
                    playing[nextVoice] = click;
                    pos[nextVoice] = 0;
                    nextVoice = (nextVoice + 1) % voices;
                    lastBeat = nextBeat;
                    nextBeat += 60.0 * rate / b;
                }
                float v = 0;
                for (int k = 0; k < voices; k++) {
                    float[] p = playing[k];
                    if (p == null) continue;
                    v += p[pos[k]++];
                    if (pos[k] >= p.length) playing[k] = null;
                }
                v = Math.max(-1f, Math.min(1f, v * vol));
                buf[i] = (short) Math.round(v * 32767);
            }
            track.write(buf, 0, chunk);
        }
        track.pause();
        track.flush();
        track.release();
    }

    // ─────────────── 通知 ───────────────

    private String soundName() {
        return Sounds.NAMES[Math.min(soundType, Sounds.NAMES.length - 1)];
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

    private Notification.Action act(int icon, String title, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build();
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
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        // 下面這組按鈕是 Android 12 以前用的；13 起有 MediaSession 時改用 updateSession() 的那組
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_beat)
                .setContentTitle(bpm + " BPM" + (running ? "" : "・暫停"))
                .setContentText(soundName())
                .setContentIntent(open)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(act(R.drawable.ic_act_minus5, "−5", action(ACTION_BPM_DELTA, -5, 1)))
                .addAction(running ? act(R.drawable.ic_act_pause, "暫停", action(ACTION_PLAY_PAUSE, 0, 2))
                                   : act(R.drawable.ic_act_play, "開始", action(ACTION_PLAY_PAUSE, 0, 2)))
                .addAction(act(R.drawable.ic_act_plus5, "+5", action(ACTION_BPM_DELTA, 5, 3)))
                .addAction(act(R.drawable.ic_act_close, "關閉", action(ACTION_STOP, 0, 4)))
                .setStyle(style)
                .build();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        haltAudio();
        alive = false;
        releaseSession();
        Listener l = listener;
        if (l != null) l.onStateChanged();
        super.onDestroy();
    }
}
