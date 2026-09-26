package io.github.agan0617.runbeat;

import java.util.Random;

/** 節拍聲音：全部用程式合成，不帶音檔。每種回傳一段 float PCM（-1～1）。 */
final class Sounds {
    static final int RATE = 44100;

    static final String[] NAMES = {"木魚", "電子嗶聲", "滴答", "牛鈴", "大鼓", "腳踏鈸"};

    private Sounds() {}

    static float[] make(int type) {
        float[] s;
        switch (type) {
            case 1: s = beep(); break;
            case 2: s = tick(); break;
            case 3: s = cowbell(); break;
            case 4: s = kick(); break;
            case 5: s = hihat(); break;
            default: s = woodblock(); break;
        }
        return normalize(s, 0.9f);
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
