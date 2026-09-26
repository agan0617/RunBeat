package io.github.agan0617.runbeat;

import java.util.Random;

/** 節拍聲音：全部用程式合成，不帶音檔。每種回傳一段 float PCM（-1～1）。 */
final class Sounds {
    static final int RATE = 44100;

    /** 編號存在設定裡，新增只能往後加，不要插隊或改順序。 */
    static final String[] NAMES = {
            "木魚", "電子嗶聲", "滴答", "牛鈴", "大鼓", "腳踏鈸", "響棒", "小鼓", "拍手", "鈴鐺",
            "水滴", "指甲輕敲", "輕敲木頭", "泡泡破掉", "沙鈴", "翻紙", "彈手指"};
    /** 這個編號以後是 ASMR 類（畫面上分組顯示）。 */
    static final int ASMR_START = 10;

    private Sounds() {}

    static float[] make(int type) {
        float[] s;
        switch (type) {
            case 1: s = beep(); break;
            case 2: s = tick(); break;
            case 3: s = cowbell(); break;
            case 4: s = kick(); break;
            case 5: s = hihat(); break;
            case 6: s = clave(); break;
            case 7: s = snare(); break;
            case 8: s = clap(); break;
            case 9: s = bell(); break;
            case 10: s = waterDrop(); break;
            case 11: s = nailTap(); break;
            case 12: s = softKnock(); break;
            case 13: s = bubblePop(); break;
            case 14: s = shaker(); break;
            case 15: s = paper(); break;
            case 16: s = fingerSnap(); break;
            default: s = woodblock(); break;
        }
        fadeOut(s, 0.005);
        return normalize(s, 0.9f);
    }

    // ---- 工具 ----

    /** RBJ 帶通濾波（constant 0 dB peak）。 */
    private static float[] bandpass(float[] x, double f0, double q) {
        double w = 2 * Math.PI * f0 / RATE, alpha = Math.sin(w) / (2 * q), cos = Math.cos(w);
        double a0 = 1 + alpha;
        double b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            double out = b0 * x[i] + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x[i]; y2 = y1; y1 = out;
            y[i] = (float) out;
        }
        return y;
    }

    private static float[] noise(int n, long seed) {
        Random r = new Random(seed);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = (float) (r.nextDouble() * 2 - 1);
        return s;
    }

    /** 結尾淡出，免得截斷處爆音。 */
    private static void fadeOut(float[] s, double seconds) {
        int n = Math.min(s.length, (int) (RATE * seconds));
        for (int i = 0; i < n; i++) s[s.length - 1 - i] *= (float) i / n;
    }

    /** 攻擊（淡入）＋指數衰減的包絡。 */
    private static double env(double t, double attack, double tau) {
        return t < attack ? t / attack : Math.exp(-(t - attack) / tau);
    }

    // ---- 一般 ----

    /** 響棒：清脆的高音木頭。 */
    private static float[] clave() {
        float[] s = alloc(0.1);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            s[i] = (float) (Math.exp(-t / 0.018) * Math.sin(2 * Math.PI * 2500 * t));
        }
        return s;
    }

    /** 小鼓：鼓皮低音＋響線的雜訊。 */
    private static float[] snare() {
        float[] s = alloc(0.2);
        float[] n = bandpass(noise(s.length, 3), 4000, 0.6);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            s[i] = (float) (0.6 * Math.exp(-t / 0.04) * Math.sin(2 * Math.PI * 190 * t)
                    + 1.4 * Math.exp(-t / 0.06) * n[i]);
        }
        return s;
    }

    /** 拍手：三下極短的雜訊疊出「啪」，後面一點殘響。 */
    private static float[] clap() {
        float[] s = alloc(0.15);
        float[] n = bandpass(noise(s.length, 4), 1200, 0.9);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            double e = 0;
            for (double start : new double[]{0, 0.009, 0.018}) {
                if (t >= start) e += Math.exp(-(t - start) / 0.003);
            }
            if (t >= 0.018) e += 0.4 * Math.exp(-(t - 0.018) / 0.04);
            s[i] = (float) (e * n[i]);
        }
        return s;
    }

    /** 鈴鐺：三個不成諧波的泛音，高的先消失。 */
    private static float[] bell() {
        float[] s = alloc(0.3);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            s[i] = (float) (Math.exp(-t / 0.2) * Math.sin(2 * Math.PI * 1320 * t)
                    + 0.5 * Math.exp(-t / 0.1) * Math.sin(2 * Math.PI * 3100 * t)
                    + 0.25 * Math.exp(-t / 0.05) * Math.sin(2 * Math.PI * 4650 * t));
        }
        return s;
    }

    // ---- ASMR：柔、近、脆 ----

    /** 水滴：音高快速往上滑的「咚」。 */
    private static float[] waterDrop() {
        float[] s = alloc(0.12);
        double phase = 0;
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            double f = Math.min(2600, 700 * Math.exp(t / 0.025));
            phase += 2 * Math.PI * f / RATE;
            s[i] = (float) (env(t, 0.001, 0.028) * Math.sin(phase));
        }
        return s;
    }

    /** 指甲輕敲：很短很細的高頻「嗒」。 */
    private static float[] nailTap() {
        float[] s = alloc(0.04);
        float[] n = bandpass(noise(s.length, 5), 4200, 3);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            s[i] = (float) (env(t, 0.0003, 0.004) * (2.5 * n[i] + 0.4 * Math.sin(2 * Math.PI * 2200 * t)));
        }
        return s;
    }

    /** 輕敲木頭：低沉、柔軟的「叩」。 */
    private static float[] softKnock() {
        float[] s = alloc(0.1);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            s[i] = (float) (env(t, 0.0015, 0.02)
                    * (Math.sin(2 * Math.PI * 340 * t) + 0.3 * Math.sin(2 * Math.PI * 720 * t)));
        }
        return s;
    }

    /** 泡泡破掉：極短往下掉的音高加一點破裂雜訊。 */
    private static float[] bubblePop() {
        float[] s = alloc(0.05);
        float[] n = bandpass(noise(s.length, 6), 2500, 2);
        double phase = 0;
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            double f = 600 + 1200 * Math.exp(-t / 0.006);
            phase += 2 * Math.PI * f / RATE;
            s[i] = (float) (Math.exp(-t / 0.008) * Math.sin(phase) + 1.5 * Math.exp(-t / 0.002) * n[i]);
        }
        return s;
    }

    /** 沙鈴：高頻雜訊，淡入再散掉的「沙」。 */
    private static float[] shaker() {
        float[] s = alloc(0.12);
        float[] n = bandpass(noise(s.length, 7), 6500, 1.2);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            s[i] = (float) (env(t, 0.015, 0.03) * n[i]);
        }
        return s;
    }

    /** 翻紙：中頻雜訊加上不規則的皺摺顫動。 */
    private static float[] paper() {
        float[] s = alloc(0.15);
        float[] n = bandpass(noise(s.length, 8), 3000, 0.7);
        Random r = new Random(9);
        double mod = 1, target = 1;
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            if (i % 180 == 0) target = 0.3 + 0.7 * r.nextDouble();   // 約 4 ms 換一次，模擬紙的皺摺
            mod += 0.02 * (target - mod);
            s[i] = (float) (env(t, 0.02, 0.05) * mod * n[i]);
        }
        return s;
    }

    /** 彈手指：清脆的「啪」。 */
    private static float[] fingerSnap() {
        float[] s = alloc(0.08);
        float[] n = bandpass(noise(s.length, 10), 2600, 2);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            s[i] = (float) (env(t, 0.0005, 0.007) * (2 * n[i] + 0.5 * Math.sin(2 * Math.PI * 1800 * t)));
        }
        return s;
    }

    private static float[] alloc(double seconds) {
        return new float[(int) (RATE * seconds)];
    }

    /** 木魚／木塊：兩個不成諧波的正弦，衰減很快。 */
    private static float[] woodblock() {
        float[] s = alloc(0.08);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            double env = Math.exp(-t / 0.012);
            s[i] = (float) (env * (Math.sin(2 * Math.PI * 880 * t) + 0.5 * Math.sin(2 * Math.PI * 2430 * t)));
        }
        return s;
    }

    /** 電子嗶聲：1 kHz，前後各 3 ms 淡入淡出免得爆音。 */
    private static float[] beep() {
        float[] s = alloc(0.06);
        int fade = RATE * 3 / 1000;
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1.0, Math.min((double) i / fade, (double) (s.length - i) / fade));
            s[i] = (float) (env * Math.sin(2 * Math.PI * 1000 * t));
        }
        return s;
    }

    /** 滴答：極短的高頻脈衝加一點雜訊。 */
    private static float[] tick() {
        float[] s = alloc(0.03);
        Random r = new Random(1);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            double env = Math.exp(-t / 0.003);
            s[i] = (float) (env * (0.7 * Math.sin(2 * Math.PI * 3200 * t) + 0.3 * (r.nextDouble() * 2 - 1)));
        }
        return s;
    }

    /** 牛鈴：兩個方波（540／800 Hz）疊加，軟削波讓它有金屬感。 */
    private static float[] cowbell() {
        float[] s = alloc(0.25);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            double env = t < 0.01 ? Math.exp(-t / 0.02) : Math.exp(-0.01 / 0.02) * Math.exp(-(t - 0.01) / 0.07);
            double sq = Math.signum(Math.sin(2 * Math.PI * 540 * t)) + Math.signum(Math.sin(2 * Math.PI * 800 * t));
            s[i] = (float) (env * Math.tanh(sq * 0.8));
        }
        // 簡單低通把方波的刺耳高頻磨掉一點
        float prev = 0;
        for (int i = 0; i < s.length; i++) {
            prev += 0.35f * (s[i] - prev);
            s[i] = prev;
        }
        return s;
    }

    /** 大鼓：正弦音高由 150 Hz 掃到 50 Hz，開頭加一點敲擊感。 */
    private static float[] kick() {
        float[] s = alloc(0.3);
        double phase = 0;
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            double f = 50 + 100 * Math.exp(-t / 0.03);
            phase += 2 * Math.PI * f / RATE;
            double env = Math.exp(-t / 0.09);
            double click = Math.exp(-t / 0.002) * 0.4;
            s[i] = (float) (env * Math.sin(phase) + click * Math.sin(2 * Math.PI * 1500 * t));
        }
        return s;
    }

    /** 腳踏鈸：高通過的白雜訊，短促。 */
    private static float[] hihat() {
        float[] s = alloc(0.06);
        Random r = new Random(2);
        float prevIn = 0, prevOut = 0;
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE;
            float in = (float) (r.nextDouble() * 2 - 1);
            float out = 0.9f * (prevOut + in - prevIn); // 一階高通
            prevIn = in;
            prevOut = out;
            s[i] = (float) (Math.exp(-t / 0.015) * out);
        }
        return s;
    }

    private static float[] normalize(float[] s, float peak) {
        float max = 0;
        for (float v : s) max = Math.max(max, Math.abs(v));
        if (max > 0) {
            float k = peak / max;
            for (int i = 0; i < s.length; i++) s[i] *= k;
        }
        return s;
    }

    static short[] toPcm16(float[] s) {
        short[] out = new short[s.length];
        for (int i = 0; i < s.length; i++) out[i] = (short) Math.round(s[i] * 32767);
        return out;
    }
}
