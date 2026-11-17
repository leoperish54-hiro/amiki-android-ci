package com.amiki.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Native, resolution-independent Amiki launch artwork. */
final class AmikiSplashView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int background = Color.rgb(4, 7, 25);
    private final int violet = Color.rgb(183, 104, 255);
    private final int paleViolet = Color.rgb(224, 190, 255);

    AmikiSplashView(Context context) {
        super(context);
        setFocusable(false);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawColor(background);
        drawNebula(canvas, w, h);
        drawStars(canvas, w, h);

        final float centerX = w * 0.5f;
        final float emblemY = h * 0.285f;
        drawOrbit(canvas, centerX, emblemY, w);
        drawEmblem(canvas, centerX, emblemY, Math.min(w, h) * 0.105f);

        final float horizonY = h * 0.455f;
        drawPlanet(canvas, w, h, horizonY);

        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        textPaint.setTextSize(Math.min(w * 0.105f, h * 0.047f));
        drawTrackedText(canvas, "AMIKI", centerX, h * 0.535f, textPaint,
                textPaint.getTextSize() * 0.34f);

        textPaint.setColor(0xFFE0A0FF);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextSize(Math.min(w * 0.047f, h * 0.022f));
        canvas.drawText("Learn. Adapt. Adjust", centerX, h * 0.615f, textPaint);
        canvas.drawText("and Conquer", centerX, h * 0.651f, textPaint);

        drawFlower(canvas, centerX, h * 0.765f, Math.min(w, h) * 0.022f);

        textPaint.setColor(0xFFD9C2F2);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textPaint.setTextSize(Math.min(w * 0.043f, h * 0.020f));
        canvas.drawText("Your AI companion.", centerX, h * 0.855f, textPaint);
        canvas.drawText("Your memory. Your edge.", centerX, h * 0.895f, textPaint);
    }

    private void drawNebula(Canvas canvas, float w, float h) {
        paint.setShader(new RadialGradient(w * 0.5f, h * 0.34f, w * 0.62f,
                new int[]{0x442E087D, 0x22100655, 0x00040719},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setShader(new RadialGradient(w * 0.5f, h * 0.48f, w * 0.52f,
                new int[]{0x332E1BFF, 0x11130B70, 0x00040719},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private void drawStars(Canvas canvas, float w, float h) {
        paint.setShader(null);
        for (int i = 0; i < 92; i++) {
            float x = ((i * 73) % 997) / 997f * w;
            float y = ((i * 137 + 29) % 991) / 991f * h * 0.76f;
            float radius = (i % 9 == 0) ? 1.45f : 0.65f;
            paint.setColor((i % 5 == 0) ? 0xA8B9A4FF : 0x668D8BCB);
            canvas.drawCircle(x, y, radius * getResources().getDisplayMetrics().density, paint);
        }
    }

    private void drawOrbit(Canvas canvas, float cx, float cy, float w) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.1f);
        for (int i = 0; i < 5; i++) {
            float radius = w * (0.205f + i * 0.027f);
            paint.setColor(i == 1 ? 0x887E35FF : 0x334F37C9);
            canvas.drawCircle(cx, cy, radius, paint);
        }
        paint.setStrokeWidth(2.0f);
        paint.setColor(0xCCB65BFF);
        canvas.drawCircle(cx, cy, w * 0.205f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x887C36FF);
        canvas.drawCircle(cx, cy, w * 0.215f, paint);
        paint.setColor(background);
        canvas.drawCircle(cx, cy, w * 0.207f, paint);
    }

    private void drawEmblem(Canvas canvas, float cx, float cy, float size) {
        float s = size;
        paint.setShader(new RadialGradient(cx, cy, s * 2.2f,
                new int[]{0x88B85CFF, 0x228A36FF, 0x00040719},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, s * 2.2f, paint);
        paint.setShader(null);

        Path a = new Path();
        a.moveTo(cx - s * 0.88f, cy + s * 0.88f);
        a.lineTo(cx - s * 0.08f, cy - s);
        a.quadTo(cx, cy - s * 1.12f, cx + s * 0.12f, cy - s * 0.92f);
        a.lineTo(cx + s * 0.88f, cy + s * 0.88f);
        a.lineTo(cx + s * 0.49f, cy + s * 0.88f);
        a.lineTo(cx, cy - s * 0.18f);
        a.lineTo(cx - s * 0.49f, cy + s * 0.88f);
        a.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(s * 0.105f);
        paint.setColor(0xFFE6C7FF);
        canvas.drawPath(a, paint);

        Path spark = new Path();
        spark.moveTo(cx - s * 0.10f, cy + s * 0.50f);
        spark.lineTo(cx + s * 0.48f, cy + s * 0.05f);
        spark.lineTo(cx + s * 0.73f, cy + s * 0.16f);
        spark.lineTo(cx + s * 0.42f, cy + s * 0.28f);
        spark.lineTo(cx + s * 0.63f, cy + s * 0.62f);
        paint.setStrokeWidth(s * 0.09f);
        canvas.drawPath(spark, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawPlanet(Canvas canvas, float w, float h, float horizonY) {
        Path planet = new Path();
        planet.moveTo(0, horizonY + h * 0.045f);
        planet.quadTo(w * 0.5f, horizonY - h * 0.105f, w, horizonY + h * 0.045f);
        planet.lineTo(w, h);
        planet.lineTo(0, h);
        planet.close();

        paint.setShader(new android.graphics.LinearGradient(0, horizonY, 0, h,
                new int[]{0xFF182B78, 0xFF09133E, 0xFF05091D},
                null, Shader.TileMode.CLAMP));
        canvas.drawPath(planet, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2.2f, w * 0.006f));
        paint.setShader(new RadialGradient(w * 0.5f, horizonY, w * 0.58f,
                new int[]{0xFFF5D9FF, 0xFF9D6BFF, 0x008A45FF},
                null, Shader.TileMode.CLAMP));
        Path rim = new Path();
        rim.moveTo(0, horizonY + h * 0.045f);
        rim.quadTo(w * 0.5f, horizonY - h * 0.105f, w, horizonY + h * 0.045f);
        canvas.drawPath(rim, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);

        paint.setShader(new RadialGradient(w * 0.5f, horizonY, w * 0.23f,
                new int[]{0xFFF8E6FF, 0xAA9C55FF, 0x008A45FF},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(w * 0.5f, horizonY, w * 0.23f, paint);
        paint.setShader(null);
    }

    private void drawFlower(Canvas canvas, float cx, float cy, float radius) {
        paint.setShader(new RadialGradient(cx, cy, radius * 4.2f,
                new int[]{0x77B35AFF, 0x228D35FF, 0x00040719},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius * 4.2f, paint);
        paint.setShader(null);
        paint.setColor(violet);
        for (int i = 0; i < 6; i++) {
            canvas.save();
            canvas.rotate(i * 60f, cx, cy);
            canvas.drawOval(cx - radius * 0.43f, cy - radius * 1.38f,
                    cx + radius * 0.43f, cy - radius * 0.16f, paint);
            canvas.restore();
        }
        paint.setColor(paleViolet);
        canvas.drawCircle(cx, cy, radius * 0.28f, paint);
    }

    private void drawTrackedText(Canvas canvas, String value, float cx, float baseline,
                                 Paint p, float tracking) {
        float width = 0;
        for (int i = 0; i < value.length(); i++) width += p.measureText(value, i, i + 1);
        width += tracking * (value.length() - 1);
        float x = cx - width * 0.5f;
        for (int i = 0; i < value.length(); i++) {
            String character = value.substring(i, i + 1);
            canvas.drawText(character, x, baseline, p);
            x += p.measureText(character) + tracking;
        }
    }
}
