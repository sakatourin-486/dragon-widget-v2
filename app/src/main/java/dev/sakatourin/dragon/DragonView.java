package dev.sakatourin.dragon;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.View;
import java.io.*;

/** Four discrete hand poses, drawn from the original artwork; no idle motion. */
final class DragonView extends View {
    static final int GRID = 64;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Bitmap idle, work;
    private final float[][] poses = new float[4][];
    private final Rect workCrop;
    private int pose;
    private int next = 1;
    private long poseStart;
    private boolean poseFrozen;
    private final float[] blendedPose = new float[(GRID + 1) * (GRID + 1) * 2];
    private float blend;
    private int contentWidth, contentHeight;
    private float originX,originY,motionX=1,motionY=1,motionLift;
    private boolean typing;
    final int idleWidth, workWidth, imageHeight;

    DragonView(Context context) throws IOException {
        super(context);
        Bitmap a = load("dragon.png"), b = load("dragon_work.png");
        Rect bounds = visibleBounds(a);
        idle = Bitmap.createBitmap(a, bounds.left, bounds.top, bounds.width(), bounds.height());
        workCrop = visibleBounds(b);
        work = Bitmap.createBitmap(b, workCrop.left, workCrop.top, workCrop.width(), workCrop.height());
        float density = getResources().getDisplayMetrics().density;
        imageHeight = Math.round(HtmlMotion.CHARACTER_HEIGHT_DP * density);
        idleWidth = Math.round(imageHeight * idle.getWidth() / (float) idle.getHeight());
        workWidth = Math.round(imageHeight * work.getWidth() / (float) work.getHeight());
        contentWidth=idleWidth;contentHeight=imageHeight;
        // Left down, release, right down, release. The desk and head remain fixed.
        float[] left = {14, -16, 0, -8}, right = {-12, -4, 14, -16};
        for (int f = 0; f < 4; f++) {
            float[] mesh = poses[f] = new float[(GRID + 1) * (GRID + 1) * 2];
            int index = 0;
            for (int y = 0; y <= GRID; y++) for (int x = 0; x <= GRID; x++) {
                float px = x * work.getWidth() / (float) GRID;
                float py = y * work.getHeight() / (float) GRID;
                float sx = px + workCrop.left, sy = py + workCrop.top;
                float l = weight(sx, sy, 532, 758, 75, 65);
                float r = weight(sx, sy, 664, 772, 78, 65);
                mesh[index++] = px + l * (f == 0 ? 2 : -1) - r * (f == 2 ? 2 : -1);
                mesh[index++] = py + l * left[f] + r * right[f];
            }
        }
        setContentDescription("龙娘，轻点开关菜单，长按切换打字，拖动移动");
    }

    private Bitmap load(String name) throws IOException {
        try (InputStream in = getContext().getAssets().open(name)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b == null) throw new IOException("Invalid image: " + name);
            return b;
        }
    }
    static Rect visibleBounds(Bitmap bitmap) {
        int left = bitmap.getWidth(), top = bitmap.getHeight(), right = -1, bottom = -1;
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) if (Color.alpha(row[x]) >= 32) {
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        return right < left ? new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())
                : new Rect(left, top, right + 1, bottom + 1);
    }
    private static float weight(float x, float y, float cx, float cy, float rx, float ry) {
        float dx = (x - cx) / rx, dy = (y - cy) / ry;
        float d = dx * dx + dy * dy;
        if (d >= 1) return 0;
        float w = 1 - d;
        return w * w;
    }
    void setViewport(int width,int height,float x,float y){
        contentWidth=width;contentHeight=height;originX=x;originY=y;invalidate();
    }
    void setMotion(float sx,float sy,float lift){motionX=sx;motionY=sy;motionLift=lift;invalidate();}
    void setBlend(float value) { blend = value; invalidate(); }
    int widthAt(float value) { return Math.round(idleWidth + (workWidth - idleWidth) * value); }

    private final Runnable nextPose = new Runnable() {
        @Override public void run() {
            if (!typing) return;
            pose = next;
            next = (next + 1) % poses.length;
            poseStart = SystemClock.uptimeMillis();
            invalidate();
            clock.postDelayed(this, 110);
        }
    };

    void pauseTyping() {
        if (typing) {
            updateMesh();
            poseFrozen = true;
        }
        typing = false;
        clock.removeCallbacks(nextPose);
    }

    void setTyping(boolean enabled) {
        clock.removeCallbacks(nextPose);
        typing = enabled;
        poseFrozen = false;
        pose = 0;
        next = 1;
        poseStart = SystemClock.uptimeMillis();
        if (enabled) clock.postDelayed(nextPose, 110);
        invalidate();
    }

    private float[] updateMesh() {
        if (poseFrozen) return blendedPose;
        if (!typing) return poses[pose];
        float t = Math.min(1f, (SystemClock.uptimeMillis() - poseStart) / 88f);
        float weight = t * t * (3 - 2 * t);
        float[] a = poses[pose], b = poses[next];
        for (int i = 0; i < blendedPose.length; i++) {
            blendedPose[i] = a[i] + (b[i] - a[i]) * weight;
        }
        if (t < 1f) postInvalidateOnAnimation();
        return blendedPose;
    }

    void release() { setTyping(false); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(originX, originY + motionLift);
        canvas.scale(motionX, motionY, contentWidth * .5f, contentHeight);
        if (blend < 1f) {
            paint.setAlpha(blend > .5f ? Math.round(510 * (1 - blend)) : 255);
            draw(canvas, idle, idleWidth, imageHeight, false);
        }
        if (blend > 0f) {
            paint.setAlpha(Math.round(255 * blend));
            draw(canvas, work, workWidth, imageHeight, true);
        }
        paint.setAlpha(255);
        canvas.restore();
    }
    private void draw(Canvas canvas, Bitmap bitmap, float width, float height, boolean mesh) {
        canvas.save();
        canvas.translate((contentWidth - width) * .5f, contentHeight - height);
        canvas.scale(width / bitmap.getWidth(), height / bitmap.getHeight());
        if (mesh) canvas.drawBitmapMesh(bitmap, GRID, GRID, updateMesh(), 0, null, 0, paint);
        else canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }
    @Override protected void onDetachedFromWindow() { release(); super.onDetachedFromWindow(); }
}
