package fr.neamar.kiss.ui;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.Collections;

/**
 * Widget host view with drag-to-move and drag-to-resize support.
 * Relies on WidgetGridLayout for cell management.
 */
public class WidgetView extends AppWidgetHostView {

    protected boolean mHasPerformedLongPress;
    private CheckForLongPress mPendingCheckForLongPress;
    private float mDownX, mDownY;

    private boolean mEditMode = false;
    private boolean mResizeMode = false;
    private boolean mDeletePressed = false;

    private float mGestureStartRawX, mGestureStartRawY;
    private int mStartLeft, mStartTop, mStartWidth, mStartHeight;

    private static final int RESIZE_LEFT = 1;
    private static final int RESIZE_TOP = 2;
    private static final int RESIZE_RIGHT = 4;
    private static final int RESIZE_BOTTOM = 8;
    private int mResizeFlags = 0;

    private final Paint mHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnWidgetInteractionListener mListener;

    public interface OnWidgetInteractionListener {
        void onWidgetMoved(WidgetView view);
        void onWidgetResized(WidgetView view);
        void onWidgetDeleted(WidgetView view);
    }

    public WidgetView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public void setOnWidgetInteractionListener(OnWidgetInteractionListener l) {
        mListener = l;
    }

    public void enterEditMode() {
        mEditMode = true;
        invalidate();
    }

    public void exitEditMode() {
        mEditMode = false;
        mResizeMode = false;
        setTranslationX(0f);
        setTranslationY(0f);
        invalidate();
    }

    public boolean isInEditMode() {
        return mEditMode;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!mEditMode) return;

        int w = getWidth();
        int h = getHeight();
        float density = getResources().getDisplayMetrics().density;
        
        // Semi-transparent background overlay
        mHandlePaint.setStyle(Paint.Style.FILL);
        mHandlePaint.setColor(Color.argb(50, 255, 255, 255));
        canvas.drawRoundRect(0, 0, w, h, 12 * density, 12 * density, mHandlePaint);

        float margin = 8 * density;
        float r = 6 * density; // handle radius

        // Outline
        mHandlePaint.setStyle(Paint.Style.STROKE);
        mHandlePaint.setColor(Color.WHITE);
        mHandlePaint.setStrokeWidth(2 * density);
        // Draw slightly inset so it matches the handles visually
        canvas.drawRoundRect(r, r, w - r, h - r, 12 * density, 12 * density, mHandlePaint);

        // Handles (dots)
        mHandlePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(r,         h / 2f,     r, mHandlePaint); // Left
        canvas.drawCircle(w - r,     h / 2f,     r, mHandlePaint); // Right
        canvas.drawCircle(w / 2f,    r,          r, mHandlePaint); // Top
        canvas.drawCircle(w / 2f,    h - r,      r, mHandlePaint); // Bottom

        // Delete 'X' Button at Top-Right
        float dr = 14 * density; // radius for delete button
        float cx = w - dr; // flush with edge
        float cy = dr;

        // Draw red circle
        mHandlePaint.setColor(Color.argb(200, 255, 50, 50));
        mHandlePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, dr, mHandlePaint);

        // Draw X lines
        mHandlePaint.setColor(Color.WHITE);
        mHandlePaint.setStyle(Paint.Style.STROKE);
        mHandlePaint.setStrokeWidth(2 * density);
        float p = 5 * density; // padding for the X
        canvas.drawLine(cx - p, cy - p, cx + p, cy + p, mHandlePaint);
        canvas.drawLine(cx + p, cy - p, cx - p, cy + p, mHandlePaint);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mHasPerformedLongPress) {
            mHasPerformedLongPress = false;
            return true;
        }

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                if (mEditMode) {
                    mGestureStartRawX = ev.getRawX();
                    mGestureStartRawY = ev.getRawY();
                    
                    float density = getResources().getDisplayMetrics().density;
                    float dr = 14 * density;
                    float cx = getWidth() - dr;
                    float cy = dr;
                    float hitRadius = 24 * density; // generous hit radius
                    
                    if (Math.hypot(ev.getX() - cx, ev.getY() - cy) <= hitRadius) {
                        mDeletePressed = true;
                        mResizeFlags = 0;
                        mResizeMode = false;
                        return true; // Consume touch for delete button
                    }
                    mDeletePressed = false;
                    
                    mResizeFlags = calculateResizeFlags(ev.getX(), ev.getY());
                    mResizeMode = (mResizeFlags != 0);
                    captureLayoutStart();
                    
                    if (getParent() instanceof WidgetGridLayout) {
                        ((WidgetGridLayout) getParent()).startDragOrResize(this);
                    }
                    requestDisallowInterceptTouchEvent(true);
                    return true; // Steal touch
                }
                postCheckForLongClick();
                break;

            case MotionEvent.ACTION_MOVE:
                if (mEditMode) return true;
                if (Math.abs(ev.getRawX() - mDownX) > 10 || Math.abs(ev.getRawY() - mDownY) > 10) {
                    mHasPerformedLongPress = false;
                    cancelPendingLongPress();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mEditMode) return true;
                mHasPerformedLongPress = false;
                cancelPendingLongPress();
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mEditMode) return super.onTouchEvent(ev);

        WidgetGridLayout grid = null;
        if (getParent() instanceof WidgetGridLayout) {
             grid = (WidgetGridLayout) getParent();
        }

        float dx = ev.getRawX() - mGestureStartRawX;
        float dy = ev.getRawY() - mGestureStartRawY;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                if (mDeletePressed) return true; // Ignore moves while pressing delete
                
                if (grid != null) {
                    if (mResizeMode) {
                        int dLeft = (mResizeFlags & RESIZE_LEFT) != 0 ? (int) dx : 0;
                        int dRight = (mResizeFlags & RESIZE_RIGHT) != 0 ? (int) dx : 0;
                        int dTop = (mResizeFlags & RESIZE_TOP) != 0 ? (int) dy : 0;
                        int dBottom = (mResizeFlags & RESIZE_BOTTOM) != 0 ? (int) dy : 0;
                        
                        android.appwidget.AppWidgetProviderInfo info = getAppWidgetInfo();
                        int minW = 40;
                        int minH = 40;
                        if (info != null) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                                minW = info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth;
                                minH = info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
                            } else {
                                minW = info.minWidth;
                                minH = info.minHeight;
                            }
                        }
                        
                        grid.previewResize(this, dLeft, dTop, dRight, dBottom, minW, minH);
                    } else {
                        setTranslationX(dx);
                        setTranslationY(dy);
                        grid.previewMove(this, mStartLeft + dx, mStartTop + dy);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mDeletePressed) {
                    float density = getResources().getDisplayMetrics().density;
                    float dr = 14 * density;
                    float cx = getWidth() - dr;
                    float cy = dr;
                    float hitRadius = 24 * density;
                    if (Math.hypot(ev.getX() - cx, ev.getY() - cy) <= hitRadius) {
                        if (mListener != null) mListener.onWidgetDeleted(this);
                    }
                    mDeletePressed = false;
                    return true;
                }
                
                boolean dropped = false;
                if (grid != null) {
                    dropped = grid.commitDragOrResize(this);
                }
                // Clear translations
                setTranslationX(0f);
                setTranslationY(0f);
                
                if (dropped && mListener != null) {
                    if (mResizeMode) mListener.onWidgetResized(this);
                    else             mListener.onWidgetMoved(this);
                }
                // Do NOT exit edit mode automatically, wait for outside click
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                mDeletePressed = false;
                setTranslationX(0f);
                setTranslationY(0f);
                if (grid != null) grid.abortInteraction();
                // Do NOT exit edit mode automatically
                return true;
        }
        return true;
    }

    private int calculateResizeFlags(float localX, float localY) {
        int w = getWidth();
        int h = getHeight();
        // Negative margin pushes the center of the hit target OUTSIDE the widget bounds.
        // This is possible because WidgetGridLayout forwards touches up to 32dp outside the bounds!
        float margin = dpToPx(getContext(), -8); 
        float r = dpToPx(getContext(), 24); // Reduced from 32; inward reach goes from 40dp to just 16dp
        
        int flags = 0;
        // Left handle is at (margin, h/2)
        if (Math.hypot(localX - margin, localY - h/2f) <= r) flags |= RESIZE_LEFT;
        // Right handle is at (w - margin, h/2)
        if (Math.hypot(localX - (w - margin), localY - h/2f) <= r) flags |= RESIZE_RIGHT;
        // Top handle is at (w/2, margin)
        if (Math.hypot(localX - w/2f, localY - margin) <= r) flags |= RESIZE_TOP;
        // Bottom handle is at (w/2, h - margin)
        if (Math.hypot(localX - w/2f, localY - (h - margin)) <= r) flags |= RESIZE_BOTTOM;
        
        return flags;
    }

    private void captureLayoutStart() {
        mStartLeft = getLeft();
        mStartTop  = getTop();
        mStartWidth = getWidth();
        mStartHeight = getHeight();
    }

    private static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    protected class CheckForLongPress implements Runnable {
        private int mOriginalWindowAttachCount;
        @Override
        public void run() {
            if ((getParent() != null) && hasWindowFocus()
                    && mOriginalWindowAttachCount == getWindowAttachCount()
                    && !WidgetView.this.mHasPerformedLongPress) {
                if (performLongClick()) {
                    WidgetView.this.mHasPerformedLongPress = true;
                }
            }
        }
        void rememberWindowAttachCount() {
            mOriginalWindowAttachCount = getWindowAttachCount();
        }
    }

    private void postCheckForLongClick() {
        mHasPerformedLongPress = false;
        if (mPendingCheckForLongPress == null) {
            mPendingCheckForLongPress = new CheckForLongPress();
        }
        mPendingCheckForLongPress.rememberWindowAttachCount();
        postDelayed(mPendingCheckForLongPress, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelPendingLongPress() {
        if (mPendingCheckForLongPress != null) {
            removeCallbacks(mPendingCheckForLongPress);
        }
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mHasPerformedLongPress = false;
        cancelPendingLongPress();
    }

    @Override
    public int getDescendantFocusability() {
        return ViewGroup.FOCUS_BLOCK_DESCENDANTS;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float density = getResources().getDisplayMetrics().density;
        int wDp = (int) (w / density);
        int hDp = (int) (h / density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateAppWidgetSize(Bundle.EMPTY, Collections.singletonList(new SizeF(wDp, hDp)));
        } else {
            updateAppWidgetSize(null, wDp, hDp, wDp, hDp);
        }
    }
}