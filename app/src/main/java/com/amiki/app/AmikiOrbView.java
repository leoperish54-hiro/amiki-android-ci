package com.amiki.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Lightweight, non-blocking status orb. Its appearance reflects the shell's real runtime state;
 * it performs no network requests and never infers state from decorative animation.
 */
final class AmikiOrbView extends View {
    static final String STATE_IDLE = "idle";
    static final String STATE_THINKING = "thinking";
    static final String STATE_LISTENING = "listening";
    static final String STATE_SPEAKING = "speaking";
    static final String STATE_OFFLINE = "offline";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String state = STATE_IDLE;
    private int primary = 0xFF8B7CFF;
    private int accent = 0xFF4DE1C1;
    private int text = 0xFFE8EAF6;
    private int muted = 0xFFADB4D0;

    AmikiOrbView(Context context) {
        super(context);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateDescription();
    }

    void setPalette(int primary, int accent, int text, int muted) {
        this.primary = primary;
        this.accent = accent;
        this.text = text;
        this.muted = muted;
        invalidate();
    }

    void setState(String nextState) {
        if (nextState == null) nextState = STATE_IDLE;
        if (!STATE_IDLE.equals(nextState)
                && !STATE_THINKING.equals(nextState)
                && !STATE_LISTENING.equals(nextState)
                && !STATE_SPEAKING.equals(nextState)
                && !STATE_OFFLINE.equals(nextState)) {
            nextState = STATE_IDLE;
        }
        if (!state.equals(nextState)) {
            state = nextState;
            updateDescription();
            invalidate();
        }
    }

    String getState() {
        return state;
    }

    private int stateColor() {
        if (STATE_LISTENING.equals(state) || STATE_SPEAKING.equals(state)) return accent;
        if (STATE_OFFLINE.equals(state)) return muted;
        return primary;
    }

    private void updateDescription() {
        String label;
        if (STATE_LISTENING.equals(state)) label = "Listening";
        else if (STATE_THINKING.equals(state)) label = "Thinking";
        else if (STATE_SPEAKING.equals(state)) label = "Speaking";
        else if (STATE_OFFLINE.equals(state)) label = "Backend offline";
        else label = "Ready";
        setContentDescription("Amiki status orb: " + label);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) * 0.43f;
        int activeColor = stateColor();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, radius * 0.035f));
        paint.setColor(withAlpha(activeColor, 105));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(withAlpha(activeColor, 70));
        canvas.drawCircle(cx, cy, radius * 0.78f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(activeColor, STATE_OFFLINE.equals(state) ? 28 : 38));
        canvas.drawCircle(cx, cy, radius * 0.68f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, radius * 0.055f));
        paint.setColor(activeColor);
        canvas.drawCircle(cx, cy, radius * 0.57f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(text);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        paint.setTextSize(radius * 0.82f);
        canvas.drawText("✦", cx, cy + radius * 0.29f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(activeColor, 210));
        float dotRadius = Math.max(2f, radius * 0.075f);
        float dotX = cx + radius * 0.70f;
        float dotY = cy - radius * 0.70f;
        canvas.drawCircle(dotX, dotY, dotRadius, paint);

        if (STATE_THINKING.equals(state)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, radius * 0.055f));
            paint.setColor(withAlpha(activeColor, 180));
            canvas.drawArc(new RectF(cx - radius * 0.90f, cy - radius * 0.90f,
                    cx + radius * 0.90f, cy + radius * 0.90f),
                    -70f, 105f, false, paint);
        } else if (STATE_OFFLINE.equals(state)) {
            paint.setColor(muted);
            paint.setStrokeWidth(Math.max(1.5f, radius * 0.06f));
            canvas.drawLine(cx - radius * 0.17f, cy - radius * 0.17f,
                    cx + radius * 0.17f, cy + radius * 0.17f, paint);
        }
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
