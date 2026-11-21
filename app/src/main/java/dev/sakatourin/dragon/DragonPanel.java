package dev.sakatourin.dragon;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

final class DragonPanel extends FrameLayout {
    private static final int INK = Color.rgb(49, 42, 62);
    private static final int MUTED = Color.rgb(115, 103, 128);
    private static final int PURPLE = Color.rgb(105, 77, 158);
    private static final int LINE = Color.rgb(235, 229, 243);
    private static final int WHITE = Color.rgb(253, 251, 255);

    enum Item {
        FIVE_HOUR("五小时额度", "额度剩余", "fiveHour"),
        WEEKLY("周额度", "额度剩余", "weekly"),
        TOKENS("总计 Token", "账号累计使用量", "lifetimeTokens"),
        TASK("当前执行的任务", "", "task"),
        PROGRESS("任务进度与进程", "", "task");

        final String label, subtitle, key;
        Item(String label, String subtitle, String key) {
            this.label = label;
            this.subtitle = subtitle;
            this.key = key;
        }
    }

    private final ScrollView menu, detail;
    private final TextView detailTitle, metric, summary, planList, menuStatus;
    private final ProgressLine bar;
    private Item selection;
    private JSONObject snapshot = new JSONObject();
    private String status = "";
    private boolean stale, interactionEnabled = true;
    private ValueAnimator pageAnimator, metricAnimator;
    private float pageAmount, displayedPercent = Float.NaN;
    private float targetPercent = Float.NaN;
    private long displayedTokens = -1, targetTokens = -1;
    private String lastContent = "";

    DragonPanel(Context context) {
        super(context);
        setBackground(shape(WHITE, LINE, 11));
        setClipToOutline(true);
        setPadding(dp(9), dp(7), dp(9), dp(7));

        menu = scroll(context);
        LinearLayout menuContent = column(context);
        menu.addView(menuContent);
        TextView title = text("龙娘助手", 15, INK, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), 0);
        menuContent.addView(title, new LinearLayout.LayoutParams(-1, dp(36)));
        separator(menuContent);
        for (Item item : Item.values()) menuContent.addView(row(item),
                new LinearLayout.LayoutParams(-1, dp(43)));
        separator(menuContent);
        menuStatus = text("", 10, MUTED, false);
        menuStatus.setPadding(dp(8), dp(2), dp(8), 0);
        menuStatus.setGravity(Gravity.CENTER_VERTICAL);
        menuStatus.setMaxLines(1);
        menuStatus.setEllipsize(TextUtils.TruncateAt.END);
        menuContent.addView(menuStatus, new LinearLayout.LayoutParams(-1, dp(25)));

        detail = scroll(context);
        LinearLayout detailContent = column(context);
        detail.addView(detailContent);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 25, PURPLE, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回菜单");
        back.setBackground(ripple());
        back.setOnClickListener(v -> { if (interactionEnabled) setSelection(null, true); });
        header.addView(back, new LinearLayout.LayoutParams(dp(37), dp(39)));
        detailTitle = text("", 14, INK, true);
        detailTitle.setMaxLines(2);
        header.addView(detailTitle, new LinearLayout.LayoutParams(0, -2, 1));
        detailContent.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));
        separator(detailContent);

        LinearLayout body = column(context);
        body.setPadding(dp(11), dp(11), dp(11), dp(8));
        detailContent.addView(body, new LinearLayout.LayoutParams(-1, -2));
        metric = text("", 27, INK, true);
        metric.setMaxLines(4);
        metric.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(metric, new LinearLayout.LayoutParams(-1, -2));

        bar = new ProgressLine(context);
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(-1, dp(10));
        progressLayout.topMargin = dp(10);
        body.addView(bar, progressLayout);
        summary = text("", 12, MUTED, false);
        summary.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams summaryLayout = new LinearLayout.LayoutParams(-1, -2);
        summaryLayout.topMargin = dp(9);
        body.addView(summary, summaryLayout);
        planList = text("", 11, INK, false);
        planList.setLineSpacing(dp(4), 1);
        LinearLayout.LayoutParams listLayout = new LinearLayout.LayoutParams(-1, -2);
        listLayout.topMargin = dp(9);
        body.addView(planList, listLayout);
        FrameLayout pages = new FrameLayout(context) {
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                if (!interactionEnabled) return false;
                View current = selection == null ? menu : detail;
                if (pageAnimator != null) return true;
                return current.dispatchTouchEvent(event);
            }
        };
        pages.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(detail, new FrameLayout.LayoutParams(-1, -1));
        addView(pages, new FrameLayout.LayoutParams(-1, -1));
        detail.setVisibility(INVISIBLE);
        updateAccessibility();
    }

    int preferredHeight() { return dp(294); }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private LinearLayout column(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    private ScrollView scroll(Context context) {
        ScrollView view = new ScrollView(context);
        view.setFillViewport(true);
        view.setOverScrollMode(OVER_SCROLL_NEVER);
        view.setVerticalScrollBarEnabled(false);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setIncludeFontPadding(false);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) background.setStroke(dp(1), stroke);
        return background;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x224e397b), null,
                shape(Color.WHITE, Color.TRANSPARENT, 8));
    }

    private void separator(LinearLayout container) {
        View line = new View(getContext());
        line.setBackgroundColor(LINE);
        container.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private View row(Item item) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(7), 0, dp(5), 0);
        row.setBackground(ripple());
        row.setContentDescription(item.label);
        row.setFocusable(true);
        row.setOnClickListener(v -> { if (interactionEnabled) setSelection(item, true); });

        Icon icon = new Icon(getContext(), item);
        row.addView(icon, new LinearLayout.LayoutParams(dp(29), dp(29)));
        TextView label = text(item.label, 13, INK, true);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(0, -1, 1);
        labelLayout.leftMargin = dp(8);
        row.addView(label, labelLayout);
        TextView chevron = text("›", 20, MUTED, false);
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(20), -1));
        return row;
    }

    void setInteractionEnabled(boolean enabled) {
        interactionEnabled = enabled;
        updateAccessibility();
    }

    private void updateAccessibility() {
        menu.setImportantForAccessibility(interactionEnabled && selection == null
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        detail.setImportantForAccessibility(interactionEnabled && selection != null
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void setSelection(Item item, boolean animated) {
        if (pageAnimator != null) pageAnimator.cancel();
        if (item != null && item != selection) {
            displayedPercent = targetPercent = Float.NaN;
            displayedTokens = targetTokens = -1;
            lastContent = "";
            if (metricAnimator != null) metricAnimator.cancel();
            for (View view : new View[]{metric, summary, planList}) view.animate().cancel();
            summary.setAlpha(1f);
            planList.setAlpha(1f);
            metric.setAlpha(1f);
            detailTitle.setText(item.label);
            detail.scrollTo(0, 0);
        }
        selection = item;
        if (item != null) renderDetail(false);
        updateAccessibility();

        float destination = item == null ? 0 : 1;
        if (!animated || pageAmount == destination) {
            applyPage(destination);
            settlePage();
            return;
        }
        menu.setVisibility(VISIBLE);
        detail.setVisibility(VISIBLE);
        ValueAnimator transition = ValueAnimator.ofFloat(pageAmount, destination);
        pageAnimator = transition;
        transition.setDuration(Math.max(90L, Math.round(Math.abs(pageAmount - destination) * 220f)));
        transition.setInterpolator(HtmlMotion::panelProgress);
        transition.addUpdateListener(a -> applyPage((float) a.getAnimatedValue()));
        transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator animator) {
                if (pageAnimator == transition) pageAnimator = null;
            }
            @Override public void onAnimationEnd(Animator animator) {
                if (pageAnimator != transition) return;
                pageAnimator = null;
                settlePage();
            }
        });
        transition.start();
    }

    private void applyPage(float amount) {
        pageAmount = amount;
        menu.setAlpha(1 - amount);
        menu.setTranslationX(-dp(13) * amount);
        detail.setAlpha(amount);
        detail.setTranslationX(dp(15) * (1 - amount));
    }

    private void settlePage() {
        menu.setVisibility(selection == null ? VISIBLE : INVISIBLE);
        detail.setVisibility(selection == null ? INVISIBLE : VISIBLE);
    }

    void update(JSONObject data, String connectionStatus, boolean staleData) {
        snapshot = data;
        status = connectionStatus;
        stale = staleData || (data.optLong("lastUpdated", 0) > 0
                && System.currentTimeMillis() / 1000 - data.optLong("lastUpdated") > 150);
        if (!TextUtils.equals(menuStatus.getText(), connectionStatus)) {
            menuStatus.setText(connectionStatus);
            if (isAttachedToWindow() && selection == null) fadeCopy(menuStatus);
        }
        if (selection != null) renderDetail(true);
    }

    private void renderDetail(boolean animated) {
        Item item = selection;
        if (item == null) return;
        JSONObject task = snapshot.optJSONObject("task");
        switch (item) {
            case FIVE_HOUR:
            case WEEKLY:
                JSONObject quota = snapshot.optJSONObject(item.key);
                double remaining = quota == null ? Double.NaN : quota.optDouble("remainingPercent", Double.NaN);
                if (Double.isFinite(remaining)) {
                    percent((float) Math.max(0, Math.min(100, remaining)), animated);
                    copy(item.subtitle + "\n" + (stale ? status : quota.optString("resetText", status)), "", animated);
                } else {
                    plain("暂无数据", animated);
                    copy(status, "", animated);
                }
                break;
            case TOKENS:
                long count = snapshot.optLong(item.key, -1);
                if (count >= 0) tokens(count, animated);
                else plain("暂无数据", animated);
                copy(count >= 0 ? item.subtitle + "\n" + status : status, "", animated);
                break;
            case TASK:
            case PROGRESS:
                taskDetails(item, task, animated);
                break;
        }
    }

    private void taskDetails(Item item, JSONObject task, boolean animated) {
        long stamp = snapshot.optLong("taskUpdated", 0);
        boolean current = task != null && stamp > 0 && System.currentTimeMillis() / 1000 - stamp <= 24;
        if (!current) {
            String taskStatus = snapshot.optString("taskStatus", "");
            if ("idle".equals(taskStatus) && stamp > 0
                    && System.currentTimeMillis() / 1000 - stamp <= 24) {
                plain("暂无执行中的任务", animated);
                copy("本机 Codex 当前空闲", "", animated);
            } else {
                plain("暂无任务数据", animated);
                copy(snapshot.has("taskStatus") ? "等待本机 Codex 任务状态更新"
                        : "请更新并重启 Termux 的 quota_bridge.py", "", animated);
            }
            return;
        }

        String phase = task.optString("phase", "执行中");
        if (item == Item.TASK) {
            plain(task.optString("title", "Codex 任务"), animated);
            copy("当前阶段 · " + phase, "", animated);
            return;
        }
        double completion = task.optDouble("percent", Double.NaN);
        if (Double.isFinite(completion)) {
            percent((float) Math.max(0, Math.min(100, completion)), animated);
            int completed = task.optInt("completedSteps", 0), total = task.optInt("totalSteps", 0);
            String counts = total > 0 ? "已完成 " + completed + " / " + total + " 步\n" : "";
            copy(counts + "当前阶段 · " + phase, formatPlan(task.optJSONArray("steps")), animated);
        } else {
            plain(phase, animated);
            copy("本轮暂未提供可计数的计划步骤", "", animated);
        }
    }

    private String formatPlan(JSONArray steps) {
        if (steps == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(steps.length(), 24); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            if (result.length() > 0) result.append('\n');
            switch (step.optString("status")) {
                case "completed": result.append("✓  "); break;
                case "inProgress": result.append("●  "); break;
                default: result.append("○  ");
            }
            result.append(step.optString("step"));
        }
        return result.toString();
    }

    private void updateMetricSize(boolean numerical) {
        int size = numerical ? 29 : 20;
        if (Math.abs(metric.getTextSize() - size * getResources().getDisplayMetrics().scaledDensity) > .5f) {
            metric.setTextSize(size);
        }
    }

    private void percent(float amount, boolean animated) {
        bar.setVisibility(VISIBLE);
        if (Float.compare(amount, targetPercent) == 0) return;
        targetPercent = amount;
        updateMetricSize(true);
        if (metricAnimator != null) metricAnimator.cancel();
        metric.animate().cancel();
        metric.setAlpha(1);
        float start = displayedPercent;
        if (!animated || Float.isNaN(start)) {
            showPercent(amount);
            return;
        }
        animateMetric(weight -> showPercent(start + (amount - start) * weight));
    }

    private void showPercent(float amount) {
        displayedPercent = amount;
        String value = Math.round(amount) + "%";
        if (!TextUtils.equals(metric.getText(), value)) metric.setText(value);
        bar.setValue(amount / 100f);
    }

    private void tokens(long amount, boolean animated) {
        bar.setVisibility(GONE);
        if (amount == targetTokens) return;
        targetTokens = amount;
        updateMetricSize(true);
        if (metricAnimator != null) metricAnimator.cancel();
        metric.animate().cancel();
        metric.setAlpha(1);
        long start = displayedTokens;
        if (!animated || start < 0) {
            showTokens(amount);
            return;
        }
        animateMetric(weight -> showTokens(weight == 1f ? amount
                : Math.round(start + (amount - (double) start) * weight)));
    }

    private void showTokens(long amount) {
        displayedTokens = amount;
        String value = NumberFormat.getIntegerInstance(Locale.CHINA).format(amount);
        if (!TextUtils.equals(metric.getText(), value)) metric.setText(value);
    }

    private void plain(String value, boolean animated) {
        targetPercent = displayedPercent = Float.NaN;
        targetTokens = displayedTokens = -1;
        if (metricAnimator != null) metricAnimator.cancel();
        bar.setVisibility(GONE);
        updateMetricSize(false);
        if (!TextUtils.equals(metric.getText(), value)) {
            metric.setText(value);
            if (animated) fadeCopy(metric);
        }
    }

    private void copy(String note, String plan, boolean animated) {
        String key = note + '\0' + plan;
        if (key.equals(lastContent)) return;
        lastContent = key;
        if (!TextUtils.equals(summary.getText(), note)) {
            summary.setText(note);
            if (animated) fadeCopy(summary);
        }
        if (!TextUtils.equals(planList.getText(), plan)) {
            planList.setText(plan);
            if (animated) fadeCopy(planList);
        }
        planList.setVisibility(plan.isEmpty() ? GONE : VISIBLE);
    }

    private void fadeCopy(TextView view) {
        view.animate().cancel();
        view.setAlpha(.64f);
        view.animate().alpha(1f).setDuration(185).setInterpolator(HtmlMotion::panelProgress).start();
    }

    private interface MetricUpdate { void show(float weight); }

    private void animateMetric(MetricUpdate update) {
        metricAnimator = ValueAnimator.ofFloat(0f, 1f);
        metricAnimator.setDuration(330);
        metricAnimator.setInterpolator(HtmlMotion::panelProgress);
        metricAnimator.addUpdateListener(a -> update.show((float) a.getAnimatedValue()));
        metricAnimator.start();
    }

    void release() {
        if (pageAnimator != null) pageAnimator.cancel();
        if (metricAnimator != null) metricAnimator.cancel();
        for (View view : new View[]{metric, summary, planList, menuStatus}) view.animate().cancel();
    }

    private final class ProgressLine extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectangle = new RectF();
        private float value;
        ProgressLine(Context context) { super(context); }
        void setValue(float value) { this.value = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            rectangle.set(0, 0, getWidth(), getHeight());
            paint.setColor(LINE);
            canvas.drawRoundRect(rectangle, radius, radius, paint);
            if (value > 0) {
                rectangle.right = getWidth() * value;
                paint.setColor(PURPLE);
                canvas.drawRoundRect(rectangle, radius, radius, paint);
            }
        }
    }

    private final class Icon extends View {
        private final Item item;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectangle = new RectF();
        Icon(Context context, Item item) { super(context); this.item = item; }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(getWidth() / 29f, getHeight() / 29f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(243, 237, 251));
            rectangle.set(1, 1, 28, 28);
            canvas.drawRoundRect(rectangle, 7, 7, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.35f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(PURPLE);
            switch (item) {
                case FIVE_HOUR:
                    canvas.drawCircle(14.5f, 14.5f, 6, paint);
                    canvas.drawLine(14.5f, 10.6f, 14.5f, 14.5f, paint);
                    canvas.drawLine(14.5f, 14.5f, 17f, 16.4f, paint);
                    break;
                case WEEKLY:
                    rectangle.set(8, 10, 21, 20.5f);
                    canvas.drawRoundRect(rectangle, 2, 2, paint);
                    canvas.drawLine(8, 13, 21, 13, paint);
                    canvas.drawLine(11.5f, 8.5f, 11.5f, 11.5f, paint);
                    canvas.drawLine(17.5f, 8.5f, 17.5f, 11.5f, paint);
                    break;
                case TOKENS:
                    rectangle.set(8.5f, 9, 20.5f, 14);
                    canvas.drawOval(rectangle, paint);
                    canvas.drawLine(8.5f, 11.5f, 8.5f, 17.5f, paint);
                    canvas.drawLine(20.5f, 11.5f, 20.5f, 17.5f, paint);
                    rectangle.offset(0, 6);
                    canvas.drawArc(rectangle, 0, 180, false, paint);
                    break;
                case TASK:
                    rectangle.set(9.5f, 8.5f, 19.5f, 20.5f);
                    canvas.drawRoundRect(rectangle, 1.6f, 1.6f, paint);
                    canvas.drawLine(12, 13, 17, 13, paint);
                    canvas.drawLine(12, 16.5f, 17, 16.5f, paint);
                    break;
                case PROGRESS:
                    canvas.drawLine(9.5f, 20, 9.5f, 15.4f, paint);
                    canvas.drawLine(14.5f, 20, 14.5f, 12.4f, paint);
                    canvas.drawLine(19.5f, 20, 19.5f, 9.4f, paint);
                    break;
            }
            canvas.restore();
        }
    }
}
