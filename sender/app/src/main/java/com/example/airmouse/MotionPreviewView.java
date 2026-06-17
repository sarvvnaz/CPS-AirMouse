package com.example.airmouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class MotionPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float cursorX = 0f;
    private float cursorY = 0f;
    private long clickFlashUntil = 0L;
    private long scrollFlashUntil = 0L;
    private String eventText = "Idle";

    public MotionPreviewView(Context context) {
        super(context);
    }

    public MotionPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void addMovement(double dx, double dy) {
        cursorX += (float) dx;
        cursorY += (float) dy;
        limitCursor();
        eventText = "Move dx=" + round(dx) + " dy=" + round(dy);
        postInvalidate();
    }

    public void showClick() {
        clickFlashUntil = System.currentTimeMillis() + 260;
        eventText = "Click";
        postInvalidate();
    }

    public void showScroll(int amount) {
        scrollFlashUntil = System.currentTimeMillis() + 260;
        eventText = amount < 0 ? "Scroll down" : "Scroll up";
        postInvalidate();
    }

    public void resetCursor() {
        cursorX = 0f;
        cursorY = 0f;
        eventText = "Centered";
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        long now = System.currentTimeMillis();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF6F8FB);
        canvas.drawRect(0, 0, w, h, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(0xFFE1E6EF);
        canvas.drawLine(w / 2f, 0, w / 2f, h, paint);
        canvas.drawLine(0, h / 2f, w, h / 2f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(34f);
        paint.setColor(0xFF2B2F36);
        canvas.drawText(eventText, 24f, 46f, paint);

        float cx = w / 2f + cursorX;
        float cy = h / 2f + cursorY;
        float size = Math.min(w, h) * 0.14f;
        RectF square = new RectF(cx - size, cy - size, cx + size, cy + size);

        if (now < clickFlashUntil) {
            paint.setColor(0xFFFFC107);
        } else if (now < scrollFlashUntil) {
            paint.setColor(0xFF7E57C2);
        } else {
            paint.setColor(0xFF43A047);
        }

        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(square, 18f, 18f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f);
        paint.setColor(0xFF1B5E20);
        canvas.drawRoundRect(square, 18f, 18f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(26f);
        paint.setColor(0xFF596273);
        canvas.drawText("Z gyro -> horizontal, X gyro -> vertical, Y motion -> click/scroll", 24f, h - 24f, paint);

        if (now < clickFlashUntil || now < scrollFlashUntil) {
            postInvalidateDelayed(40);
        }
    }

    private void limitCursor() {
        float maxX = Math.max(120f, getWidth() / 2f - 80f);
        float maxY = Math.max(120f, getHeight() / 2f - 80f);
        cursorX = Math.max(-maxX, Math.min(maxX, cursorX));
        cursorY = Math.max(-maxY, Math.min(maxY, cursorY));
    }

    private static String round(double value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }
}