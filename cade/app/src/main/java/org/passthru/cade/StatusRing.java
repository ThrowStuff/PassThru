// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.content.Context;
import android.graphics.*;
import android.provider.Settings;
import android.view.View;

/** A restrained circle, with a moving highlight only when animations are enabled. */
public final class StatusRing extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private UiState.Ring state=UiState.Ring.DIM;
    public StatusRing(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    public void setState(UiState.Ring value) { state=value; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float stroke=8*getResources().getDisplayMetrics().density;
        float radius=Math.min(getWidth(),getHeight())*.36f;
        float x=getWidth()/2f,y=getHeight()/2f;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
        int color=getContext().getColor(state==UiState.Ring.DIM?R.color.border:
            state==UiState.Ring.ATTENTION?R.color.attention:R.color.accent);
        paint.setColor(color); paint.setAlpha(state==UiState.Ring.ROTATING?70:255);
        canvas.drawCircle(x,y,radius,paint);
        if (state==UiState.Ring.ROTATING) {
            boolean animate=Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)!=0f;
            float angle=animate?(android.os.SystemClock.uptimeMillis()%1800)*.2f:-90;
            paint.setAlpha(255); canvas.drawArc(x-radius,y-radius,x+radius,y+radius,angle,90,false,paint);
            if (animate && isShown()) postInvalidateOnAnimation();
        }
    }
}
