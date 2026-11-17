package com.amiki.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Subtle, self-contained atmosphere overlay for the Dashboard portrait.
 * Animation is local-only and stops when this view is hidden or detached.
 */
final class AmikiAtmosphereView extends View {
    private static final long FRAME_DELAY_MS = 50L;
    private static final float[] PARTICLE_X = {0.08f, 0.19f, 0.88f, 0.94f, 0.07f, 0.91f, 0.22f, 0.79f};
    private static final float[] PARTICLE_Y = {0.16f, 0.07f, 0.20f, 0.51f, 0.76f, 0.83f, 0.94f, 0.96f};
    private static final float[] PARTICLE_SIZE = {1.4f, 1.0f, 1.2f, 1.0f, 1.1f, 1.5f, 0.9f, 1.2f};
    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mistPath = new Path();
    private final Path secondMistPath = new Path();
    private final RectF borderRect = new RectF();
    private final Runnable frameTick = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || !isShown()) return;
            invalidate();
            postDelayed(this, FRAME_DELAY_MS);
        }
    };

    private RadialGradient[] cornerGlows = new RadialGradient[0];
    private float density;
    private long animationStart;
    private boolean running;

    AmikiAtmosphereView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentDescription(null);
    }

    private String state = AmikiOrbView.STATE_IDLE;

    void setState(String nextState) {
        if (!AmikiOrbView.STATE_IDLE.equals(nextState)
                && !AmikiOrbView.STATE_THINKING.equals(nextState)
                && !AmikiOrbView.STATE_LISTENING.equals(nextState)
                && !AmikiOrbView.STATE_SPEAKING.equals(nextState)
                && !AmikiOrbView.STATE_OFFLINE.equals(nextState)) {
            nextState = AmikiOrbView.STATE_IDLE;
        }
        if (!state.equals(nextState)) {
            state = nextState;
            invalidate();
        }
    }

    private int stateTint() {
        if (AmikiOrbView.STATE_LISTENING.equals(state)
                || AmikiOrbView.STATE_SPEAKING.equals(state)) return 0xFF4DE1C1;
        if (AmikiOrbView.STATE_OFFLINE.equals(state)) return 0xFFADB4D0;
        return 0xFFB6A6FF;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            cornerGlows = new RadialGradient[0];
            return;
        }
        float radius = Math.max(w, h) * 0.58f;
        int[] colors = {0x408B68FF, 0x188B68FF, 0x008B68FF};
        float[] stops = {0f, 0.52f, 1f};
        cornerGlows = new RadialGradient[] {
                new RadialGradient(0f, 0f, radius, colors, stops, Shader.TileMode.CLAMP),
                new RadialGradient(w, 0f, radius, colors, stops, Shader.TileMode.CLAMP),
                new RadialGradient(0f, h, radius, colors, stops, Shader.TileMode.CLAMP),
                new RadialGradient(w, h, radius, colors, stops, Shader.TileMode.CLAMP)
        };
        borderRect.set(0.75f * density, 0.75f * density,
                w - 0.75f * density, h - 0.75f * density);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animationStart = SystemClock.uptimeMillis();
        updateAnimationState();
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimationState();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimationState();
    }

    private void updateAnimationState() {
        boolean shouldRun = isAttachedToWindow() && isShown();
        if (shouldRun && !running) {
            running = true;
            if (animationStart == 0L) animationStart = SystemClock.uptimeMillis();
            removeCallbacks(frameTick);
            postDelayed(frameTick, FRAME_DELAY_MS);
        } else if (!shouldRun && running) {
            stopAnimation();
        }
    }

    private void stopAnimation() {
        running = false;
        removeCallbacks(frameTick);
    }

    private float phase(long now, float offset, long periodMs) {
        return ((now - animationStart) % periodMs) / (float) periodMs * TWO_PI + offset;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        long now = SystemClock.uptimeMillis();
        float breathe = 0.78f + 0.22f * (float) Math.sin(phase(now, 0f, 6200L));
        int save = canvas.save();
        canvas.clipRect(0f, 0f, width, height);

        paint.setStyle(Paint.Style.FILL);
        for (RadialGradient glow : cornerGlows) {
            paint.setShader(glow);
            paint.setAlpha((int) (155f * breathe));
            canvas.drawRect(0f, 0f, width, height, paint);
        }
        paint.setShader(null);
        paint.setAlpha(255);

        drawMist(canvas, width, height, now, breathe);
        drawParticles(canvas, width, height, now);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(0.7f * density, 1f));
        paint.setColor(0x558B7CFF);
        paint.setAlpha((int) (75f + 35f * breathe));
        canvas.drawRoundRect(borderRect, 15f * density, 15f * density, paint);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(save);
    }

    private void drawMist(Canvas canvas, int width, int height, long now, float breathe) {
        long driftPeriod = AmikiOrbView.STATE_THINKING.equals(state) ? 6500L : 9200L;
        float drift = (float) Math.sin(phase(now, 0.6f, driftPeriod));
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(3.2f * density);
        paint.setColor(stateTint());
        paint.setAlpha((int) (30f + 16f * breathe));

        mistPath.reset();
        mistPath.moveTo(-width * 0.08f, height * (0.88f + drift * 0.012f));
        mistPath.cubicTo(width * 0.22f, height * 0.78f,
                width * 0.32f, height * 1.01f,
                width * 0.63f, height * (0.89f - drift * 0.01f));
        mistPath.quadTo(width * 0.83f, height * 0.83f,
                width * 1.08f, height * 0.92f);
        canvas.drawPath(mistPath, paint);

        paint.setStrokeWidth(1.8f * density);
        paint.setColor(stateTint());
        paint.setAlpha((int) (22f + 13f * breathe));
        secondMistPath.reset();
        secondMistPath.moveTo(-width * 0.04f, height * (0.95f - drift * 0.008f));
        secondMistPath.quadTo(width * 0.40f, height * 0.86f,
                width * 0.70f, height * (0.96f + drift * 0.008f));
        secondMistPath.quadTo(width * 0.90f, height * 1.01f,
                width * 1.04f, height * 0.91f);
        canvas.drawPath(secondMistPath, paint);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawParticles(Canvas canvas, int width, int height, long now) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < PARTICLE_X.length; i++) {
            float phase = phase(now, i * 0.83f, 3600L + i * 270L);
            float x = PARTICLE_X[i] * width + (float) Math.sin(phase) * width * 0.012f;
            float y = PARTICLE_Y[i] * height + (float) Math.cos(phase * 0.73f) * height * 0.018f;
            float radius = Math.max(0.7f * density, PARTICLE_SIZE[i] * density);
            int alpha = (int) (65f + 65f * (0.5f + 0.5f * (float) Math.sin(phase)));
            paint.setColor(stateTint());
            paint.setAlpha(alpha / 2);
            canvas.drawCircle(x, y, radius * 2.1f, paint);
            paint.setAlpha(alpha);
            canvas.drawCircle(x, y, radius * 0.72f, paint);
        }
        paint.setAlpha(255);
    }
}
