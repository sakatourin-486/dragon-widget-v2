package dev.sakatourin.dragon;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

public class DragonService extends Service {
    private static final int OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rect display = new Rect();
    private WindowManager wm;
    private WindowManager.LayoutParams lp, panelLp;
    private FrameLayout root;
    private DragonView pet;
    private DragonPanel panel;
    private ValueAnimator modeAnimator, panelAnimator;
    private boolean working, panelAdded, panelOpen, panelAbove, frameScheduled;
    private int panelSide;
    private float modeBlend, panelAmount;
    private float bounceFromX = 1, bounceFromY = 1, currentX = 1, currentY = 1;
    private long bounceStart = -1;
    private int failures;
    private boolean requestRunning;
    private volatile boolean alive = true;
    private volatile HttpURLConnection connection;
    private JSONObject data = new JSONObject();
    private String status = "未连接账号数据";

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private SharedPreferences preferences() { return getSharedPreferences("config", 0); }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                "dragon", "龙娘悬浮窗", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, DragonService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "dragon")
                .setContentTitle("SaKaTouRin 运行中")
                .setContentText("轻点开关菜单，拖动移动，长按切换打字")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .addAction(new Notification.Action.Builder(null, "关闭", stop).build())
                .build();
        startForeground(1, notification);

        SharedPreferences prefs = preferences();
        try { data = new JSONObject(prefs.getString("lastData", "{}")); }
        catch (JSONException ignored) {}
        if (data.length() > 0) status = "正在重连 · 上次数据";

        wm = getSystemService(WindowManager.class);
        readDisplay();
        try { pet = new DragonView(this); }
        catch (IOException e) { stopSelf(); return; }

        int width = pet.idleWidth, height = pet.imageHeight, surface = surfaceFor(width);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.addView(pet, new FrameLayout.LayoutParams(surface, height));
        pet.setViewport(width, height, (surface - width) * .5f, 0);

        lp = overlayParams(surface, height);
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        int center = prefs.getInt("petCenterX", prefs.getInt("petX", dp(32)) + width / 2);
        lp.x = center - surface / 2;
        lp.y = prefs.getInt("petY", dp(280));
        clampPosition();
        try { wm.addView(root, lp); }
        catch (RuntimeException e) { stopSelf(); return; }

        panel = new DragonPanel(this);
        panelLp = overlayParams(dp(240), panel.preferredHeight());
        pet.setOnClickListener(v -> togglePanel());
        pet.setOnLongClickListener(v -> {
            toggleWorking();
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        bindGestures();
        handler.post(poll);
    }

    private WindowManager.LayoutParams overlayParams(int width, int height) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OVERLAY_FLAGS, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.windowAnimations = 0;
        return p;
    }

    private void bindGestures() {
        pet.setOnTouchListener(new View.OnTouchListener() {
            float x, y;
            int originX, originY;
            boolean dragged, held;
            final Runnable hold = () -> {
                if (alive) held = pet.performLongClick();
            };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x = e.getRawX(); y = e.getRawY();
                        originX = lp.x; originY = lp.y;
                        dragged = held = false;
                        handler.postDelayed(hold, 550);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (held) return true;
                        float dx = e.getRawX() - x, dy = e.getRawY() - y;
                        if (Math.abs(dx) + Math.abs(dy) > dp(8)) {
                            dragged = true;
                            handler.removeCallbacks(hold);
                        }
                        if (dragged) {
                            lp.x = originX + Math.round(dx);
                            lp.y = originY + Math.round(dy);
                            clampPosition();
                            wm.updateViewLayout(root, lp);
                            if (panelAdded) positionPanel(false);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(hold);
                        if (dragged) preferences().edit().putInt("petCenterX", lp.x + lp.width / 2)
                                .putInt("petY", lp.y).apply();
                        else if (!held && alive) v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(hold);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void toggleWorking() {
        working = !working;
        if (modeAnimator != null) modeAnimator.cancel();
        pet.pauseTyping();
        setPetViewport(Math.max(pet.idleWidth, pet.workWidth));
        ValueAnimator transition = ValueAnimator.ofFloat(modeBlend, working ? 1f : 0f);
        modeAnimator = transition;
        transition.setDuration(Math.max(100, Math.round(330 * Math.abs((working ? 1f : 0f) - modeBlend))));
        transition.setInterpolator(new AccelerateDecelerateInterpolator());
        transition.addUpdateListener(a -> {
            modeBlend = (float) a.getAnimatedValue();
            pet.setBlend(modeBlend);
        });
        transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                if (!alive || modeAnimator != transition) return;
                setPetViewport(pet.widthAt(modeBlend));
                pet.setTyping(working);
                if (panelAdded) positionPanel(false);
            }
            @Override public void onAnimationCancel(Animator animator) {
                if (modeAnimator == transition) modeAnimator = null;
            }
        });
        transition.start();
    }

    private int surfaceFor(int width) { return (int) Math.ceil(width * HtmlMotion.max(HtmlMotion.X)) + 2; }

    private void setPetViewport(int width) {
        int center = lp.x + lp.width / 2, surface = surfaceFor(width);
        lp.x = center - surface / 2;
        lp.width = surface;
        pet.setLayoutParams(new FrameLayout.LayoutParams(surface, pet.imageHeight));
        pet.setViewport(width, pet.imageHeight, (surface - width) * .5f, 0);
        clampPosition();
        wm.updateViewLayout(root, lp);
    }

    private void readDisplay() {
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        display.set(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private void clampPosition() {
        lp.x = Math.max(0, Math.min(Math.max(0, display.width() - lp.width), lp.x));
        lp.y = Math.max(0, Math.min(Math.max(0, display.height() - lp.height), lp.y));
    }

    private void togglePanel() {
        if (!alive || panel == null) return;
        if (modeAnimator == null || !modeAnimator.isRunning()) beginPetBounce();
        if (panelOpen) {
            panelOpen = false;
            animatePanel(false);
            return;
        }

        panelOpen = true;
        if (!panelAdded) {
            panel.setSelection(null, false);
            positionPanel(true);
            applyPanelMotion(0f);
            try {
                wm.addView(panel, panelLp);
                panelAdded = true;
            } catch (RuntimeException e) {
                panelOpen = false;
                return;
            }
        }
        render();
        animatePanel(true);
        handler.removeCallbacks(poll);
        if (!requestRunning) handler.post(poll);
    }

    private void positionPanel(boolean initial) {
        int edge = dp(7), gap = dp(7), w = display.width(), h = display.height();
        int px = lp.x, py = lp.y, right = px + lp.width, bottom = py + lp.height;
        int width = Math.max(1, Math.min(dp(242), w - edge * 2));
        int height = Math.max(1, Math.min(panel.preferredHeight(), h - edge * 2));
        int roomRight = w - edge - right - gap, roomLeft = px - edge - gap;
        int roomAbove = py - edge - gap, roomBelow = h - edge - bottom - gap;
        boolean sideRight = roomRight >= width && (panelSide > 0
                || (panelSide == 0 && roomRight >= roomLeft) || roomLeft < width);
        boolean sideLeft = roomLeft >= width && !sideRight;
        int x, y;
        if (sideRight || sideLeft) {
            x = sideRight ? right + gap : px - gap - width;
            y = Math.max(edge, Math.min(h - edge - height, py + lp.height / 2 - height / 2));
            panelAbove = false;
            panelSide = sideRight ? 1 : -1;
        } else {
            panelAbove = roomAbove >= height && (panelAbove || roomBelow < height)
                    || roomBelow < height && roomAbove >= roomBelow;
            int room = panelAbove ? roomAbove : roomBelow;
            if (room < height && Math.max(roomLeft, roomRight) >= dp(178) && room < dp(195)) {
                panelSide = roomRight >= roomLeft ? 1 : -1;
                width = Math.min(width, panelSide > 0 ? roomRight : roomLeft);
                x = panelSide > 0 ? right + gap : px - gap - width;
                y = Math.max(edge, Math.min(h - edge - height, py + lp.height / 2 - height / 2));
                panelAbove = false;
            } else {
                height = Math.max(1, Math.min(height, room));
                x = Math.max(edge, Math.min(w - edge - width, px + lp.width / 2 - width / 2));
                y = panelAbove ? py - gap - height : bottom + gap;
                panelSide = 0;
            }
        }
        boolean changed = panelLp.x != x || panelLp.y != y || panelLp.width != width || panelLp.height != height;
        panelLp.x = x; panelLp.y = y; panelLp.width = width; panelLp.height = height;
        panel.setPivotX(Math.max(0, Math.min(width, px + lp.width * .5f - x)));
        panel.setPivotY(Math.max(0, Math.min(height, py + lp.height * .5f - y)));
        if (changed && panelAdded && !initial) wm.updateViewLayout(panel, panelLp);
        if (panelAnimator != null && panelAnimator.isRunning()) applyPanelMotion(panelAmount);
    }

    private void animatePanel(boolean opening) {
        if (panelAnimator != null) panelAnimator.cancel();
        panel.setInteractionEnabled(opening);
        // Disable menu interaction as soon as closing begins.
        setPanelTouchable(opening);
        ValueAnimator transition = ValueAnimator.ofFloat(panelAmount, opening ? 1f : 0f);
        panelAnimator = transition;
        transition.setDuration(HtmlMotion.panelDuration(panelAmount, opening ? 1f : 0f));
        transition.setInterpolator(HtmlMotion::panelProgress);
        transition.addUpdateListener(a -> applyPanelMotion((float) a.getAnimatedValue()));
        transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator animator) {
                if (panelAnimator == transition) panelAnimator = null;
            }
            @Override public void onAnimationEnd(Animator animator) {
                if (panelAnimator != transition) return;
                panelAnimator = null;
                if (!panelOpen) detachPanel();
            }
        });
        transition.start();
    }

    private void setPanelTouchable(boolean touchable) {
        int flags = touchable ? OVERLAY_FLAGS : OVERLAY_FLAGS | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (panelLp.flags == flags) return;
        panelLp.flags = flags;
        if (panelAdded) wm.updateViewLayout(panel, panelLp);
    }

    private void applyPanelMotion(float amount) {
        panelAmount = amount;
        panel.setAlpha(amount);
        panel.setScaleX(.94f + .06f * amount);
        panel.setScaleY(.94f + .06f * amount);
        float travel = dp(5) * (1 - amount);
        panel.setTranslationX(-panelSide * travel);
        panel.setTranslationY(panelSide == 0 ? (panelAbove ? travel : -travel) : 0f);
    }

    private void detachPanel() {
        if (!panelAdded) return;
        panelAdded = false;
        try { wm.removeViewImmediate(panel); }
        catch (IllegalArgumentException ignored) {}
    }

    private void beginPetBounce() {
        bounceFromX = currentX;
        bounceFromY = currentY;
        bounceStart = System.nanoTime();
        if (!frameScheduled) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(motionFrame);
        }
    }

    private final Choreographer.FrameCallback motionFrame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long now) {
            frameScheduled = false;
            if (!alive || pet == null || bounceStart < 0) return;
            float elapsed = Math.max(0, (now - bounceStart) / 450000000f);
            float q = HtmlMotion.bounceProgress(elapsed);
            float remaining = 1 - Math.min(1, elapsed * 5);
            currentX = HtmlMotion.sample(q, HtmlMotion.OFFSETS, HtmlMotion.X) + (bounceFromX - 1) * remaining;
            currentY = HtmlMotion.sample(q, HtmlMotion.OFFSETS, HtmlMotion.Y) + (bounceFromY - 1) * remaining;
            currentX = Math.min(HtmlMotion.max(HtmlMotion.X), currentX);
            currentY = Math.max(.8f, Math.min(1f, currentY));
            pet.setMotion(currentX, currentY, 0);
            if (elapsed < 1f) {
                frameScheduled = true;
                Choreographer.getInstance().postFrameCallback(this);
            } else bounceStart = -1;
        }
    };

    private void render() {
        if (panel == null || !panelOpen) return;
        panel.update(data, status, failures > 0 || data.optBoolean("stale", false));
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!alive || requestRunning) return;
            SharedPreferences prefs = preferences();
            String endpoint = prefs.getString("url", "http://127.0.0.1:8765/quota");
            String key = prefs.getString("key", "").trim();
            if (!DataSource.isAllowed(endpoint)) {
                status = "数据源地址无效";
                failures++;
                render();
                return;
            }
            requestRunning = true;
            new Thread(() -> fetch(endpoint, key), "dragon-quota").start();
        }
    };

    private void fetch(String address, String key) {
        JSONObject result = null;
        String error = null;
        HttpURLConnection http = null;
        try {
            URL endpoint = new URL(address);
            boolean local = "127.0.0.1".equals(endpoint.getHost()) || "localhost".equalsIgnoreCase(endpoint.getHost());
            http = (HttpURLConnection) (local ? endpoint.openConnection(Proxy.NO_PROXY) : endpoint.openConnection());
            connection = http;
            http.setConnectTimeout(4000);
            http.setReadTimeout(6000);
            http.setUseCaches(false);
            http.setRequestProperty("Authorization", "Bearer " + key);
            http.setInstanceFollowRedirects(false);
            int code = http.getResponseCode();
            if (code != 200) throw new IOException(code == 401 ? "配对密钥不正确" : "数据服务返回 " + code);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 100000) throw new IOException("数据响应异常");
                }
            }
            result = new JSONObject(body.toString());
        } catch (Exception e) {
            String detail = e.getMessage();
            error = detail == null || detail.trim().isEmpty() ? e.getClass().getSimpleName() : detail;
            error = error.replace('\n', ' ').replace('\r', ' ');
            if (error.length() > 90) error = error.substring(0, 90) + "…";
        } finally {
            if (http != null) http.disconnect();
            connection = null;
        }
        final JSONObject fresh = result;
        final String why = error;
        handler.post(() -> {
            requestRunning = false;
            if (!alive) return;
            if (fresh != null) {
                failures = 0;
                data = fresh;
                status = fresh.optString("status", "已连接数据源");
                JSONObject cached = new JSONObject();
                for (String field : new String[]{"fiveHour", "weekly", "lifetimeTokens", "lastUpdated", "stale"}) {
                    Object value = fresh.opt(field);
                    if (value != null) try { cached.put(field, value); } catch (JSONException ignored) {}
                }
                String value = cached.toString();
                SharedPreferences prefs = preferences();
                if (!value.equals(prefs.getString("lastData", ""))) {
                    prefs.edit().putString("lastData", value).apply();
                }
            } else {
                failures++;
                status = "配对密钥不正确".equals(why) ? why : "连接失败 · " + why;
            }
            render();
            handler.removeCallbacks(poll);
            long delay = fresh != null ? (panelOpen ? 2500L : 15000L)
                    : Math.min(15000L, 2000L * (1L << Math.min(failures - 1, 3)));
            handler.postDelayed(poll, delay);
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(poll);
        if (!requestRunning) handler.post(poll);
        return START_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (root == null || lp == null || wm == null) return;
        readDisplay();
        clampPosition();
        wm.updateViewLayout(root, lp);
        if (panelAdded) positionPanel(false);
    }

    @Override public void onDestroy() {
        alive = false;
        if (modeAnimator != null) modeAnimator.cancel();
        if (panelAnimator != null) panelAnimator.cancel();
        if (pet != null) pet.release();
        if (panel != null) panel.release();
        handler.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(motionFrame);
        HttpURLConnection http = connection;
        if (http != null) http.disconnect();
        detachPanel();
        if (root != null && root.isAttachedToWindow()) wm.removeViewImmediate(root);
        stopForeground(true);
        super.onDestroy();
    }
}
