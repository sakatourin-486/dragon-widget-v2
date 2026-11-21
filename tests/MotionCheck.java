package dev.sakatourin.dragon;

public class MotionCheck {
    static void check(float density, int originalHeight) {
        int height = Math.round(HtmlMotion.CHARACTER_HEIGHT_DP * density);
        if (Math.abs(height - originalHeight * .8f) > 1f) throw new AssertionError("Resize");
        for (float aspect : new float[]{916f / 1196, 1137f / 1206}) {
            int w = Math.round(height * aspect);
            int oldWidth = Math.round(originalHeight * aspect);
            int surface = (int) Math.ceil(w * HtmlMotion.max(HtmlMotion.X)) + 2;
            int oldSurface = (int) Math.ceil(oldWidth * HtmlMotion.max(HtmlMotion.X)) + 2;
            if (Math.abs(surface - oldSurface * .8f) > 3f) throw new AssertionError("Hit area");
            float pad = (surface - w) * .5f;
            for (int i = 0; i <= 1000; i++) {
                float q = HtmlMotion.bounceProgress(i / 1000f);
                float sx = HtmlMotion.sample(q, HtmlMotion.OFFSETS, HtmlMotion.X);
                float sy = HtmlMotion.sample(q, HtmlMotion.OFFSETS, HtmlMotion.Y);
                float dy = HtmlMotion.sample(q, HtmlMotion.OFFSETS, HtmlMotion.LIFT);
                float left = pad + w * (1 - sx) * .5f;
                float top = height * (1 - sy) + dy;
                if (left < 0 || left + w * sx > surface || top < 0 || Math.abs(top + height * sy - height) > .001f)
                    throw new AssertionError("Bounce left the fixed viewport");
            }
        }
    }

    public static void main(String[] args) {
        for (float density : new float[]{1, 1.25f, 1.5f, 2, 2.625f, 3, 4}) {
            check(density, Math.round(145 * density));
        }
        if (HtmlMotion.panelDuration(0, 1) != 260 || HtmlMotion.panelDuration(1, 0) != 215
                || HtmlMotion.panelDuration(.5f, 0) >= 215) throw new AssertionError("Panel timing");
        float last = -1;
        for (int i = 0; i <= 1000; i++) {
            float progress = HtmlMotion.panelProgress(i / 1000f);
            if (progress < last || progress < 0 || progress > 1) throw new AssertionError("Panel discontinuity");
            last = progress;
        }
        if (last != 1) throw new AssertionError("Panel never settles");
        System.out.println("Dragon dimensions, touch bounds, stationary feet and reversible menu curves verified.");
    }
}
