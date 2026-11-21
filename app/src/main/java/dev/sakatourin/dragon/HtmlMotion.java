package dev.sakatourin.dragon;

/** The preview's CSS timing, in logical pixels and milliseconds. */
final class HtmlMotion {
    static final float CHARACTER_HEIGHT_DP = 145f * .8f;
    static final float[] OFFSETS = {0f, .16f, .40f, .65f, .82f, 1f};
    // Keep a small horizontal squash so the tap still feels elastic. The
    // native surface reserves this 6% once and does not move during the tap.
    static final float[] X = {1f, 1.06f, .98f, 1.025f, .997f, 1f};
    static final float[] LIFT = {0f, 0f, 0f, 0f, 0f, 0f};
    // Never exceed the resting height. The character squashes and settles back
    // onto the same feet instead of stretching above its resting position.
    static final float[] Y = {1f, .84f, .985f, .925f, .995f, 1f};
    static float sample(float p, float[] offsets, float[] values) {
        p = Math.max(0f, Math.min(1f, p));
        for (int i = 1; i < offsets.length; i++) {
            if (p <= offsets[i]) {
                float t = (p - offsets[i-1]) / (offsets[i] - offsets[i-1]);
                return values[i-1] + (values[i] - values[i-1]) * t;
            }
        }
        return values[values.length-1];
    }
    static float max(float[] values) {
        float result=values[0];
        for(float value:values) result=Math.max(result,value);
        return result;
    }
    static double cubic(double t, double a, double b) {
        double u = 1 - t;
        return 3*u*u*t*a + 3*u*t*t*b + t*t*t;
    }
    static float ease(float progress, double x1, double y1, double x2, double y2) {
        if (progress <= 0) return 0;
        if (progress >= 1) return 1;
        double lo = 0, hi = 1;
        for (int i = 0; i < 30; i++) {
            double t = (lo + hi) / 2;
            if (cubic(t, x1, x2) < progress) lo = t; else hi = t;
        }
        return (float)cubic((lo+hi)/2, y1, y2);
    }
    static float bounceProgress(float progress) { return ease(progress, .2, .65, .3, 1); }
    static float panelProgress(float progress) { return ease(progress, .18, .72, .25, 1); }
    static int panelDuration(float from, float to) {
        return Math.round(Math.max(90f, (to > from ? 260f : 215f) * Math.abs(to - from)));
    }
}
