package com.enricoros.nreal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.media3.common.C;

public class AbLoopTimelineView extends View {
  private static final int COLOR_A = Color.rgb(31, 111, 107);

  private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float radius;
  private final float trackInset;

  private long durationMs = C.TIME_UNSET;
  private long loopStartMs = C.TIME_UNSET;
  private long loopEndMs = C.TIME_UNSET;

  public AbLoopTimelineView(Context context) {
    this(context, null);
  }

  public AbLoopTimelineView(Context context, AttributeSet attrs) {
    super(context, attrs);
    float density = getResources().getDisplayMetrics().density;
    radius = 5.0f * density;
    trackInset = 16.0f * density;
    setWillNotDraw(false);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  public void setLoopPoints(long durationMs, long loopStartMs, long loopEndMs) {
    this.durationMs = durationMs;
    this.loopStartMs = loopStartMs;
    this.loopEndMs = loopEndMs;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (durationMs <= 0L) {
      return;
    }
    drawPoint(canvas, loopStartMs);
    drawPoint(canvas, loopEndMs);
  }

  private void drawPoint(Canvas canvas, long positionMs) {
    if (positionMs == C.TIME_UNSET || positionMs < 0L) {
      return;
    }
    float usableWidth = Math.max(1.0f, getWidth() - trackInset * 2.0f);
    float progress = Math.min(1.0f, Math.max(0.0f, (float) positionMs / (float) durationMs));
    float x = trackInset + usableWidth * progress;
    float y = getHeight() * 0.5f;

    pointPaint.setColor(COLOR_A);
    canvas.drawCircle(x, y, radius, pointPaint);
  }
}
