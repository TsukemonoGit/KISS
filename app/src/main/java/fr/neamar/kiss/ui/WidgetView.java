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

    private float mGestureStartRawX, mGestureStartRawY;
    private int mStartLeft, mStartTop, mStartWidth, mStartHeight;

    private static final int HANDLE_DP = 56;
    private int mHandlePx;

    private final Paint mHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  mHandlePath  = new Path();

    private OnWidgetInteractionListener mListener;

    public interface OnWidgetInteractionListener {
        void onWidgetMoved(WidgetView view);
        void onWidgetResized(WidgetView view);
    }

    public WidgetView(Context context) {
        super(context);
        mHandlePaint.setColor(Color.WHITE);
        mHandlePaint.setAlpha(200);
        mHandlePaint.setStyle(Paint.Style.FILL);
        mHandlePx = dpToPx(context, HANDLE_DP);
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
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mEditMode) return;

        int w = getWidth();
        int h = getHeight();
        int size = dpToPx(getContext(), HANDLE_DP);

        mHandlePath.reset();
        mHandlePath.moveTo(w,        h);
        mHandlePath.lineTo(w - size, h);
        mHandlePath.lineTo(w,        h - size);
        mHandlePath.close();

        canvas.drawPath(mHandlePath, mHandlePaint);
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
                    mResizeMode = isInResizeHandle(ev.getX(), ev.getY());
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
                if (grid != null) {
                    if (mResizeMode) {
                        int rawW = Math.max(dpToPx(getContext(), 40), mStartWidth + (int) dx);
                        int rawH = Math.max(dpToPx(getContext(), 40), mStartHeight + (int) dy);
                        // Using fixed minimums (e.g. 40dp) here. 
                        grid.previewResize(this, rawW, rawH, 40, 40);
                    } else {
                        setTranslationX(dx);
                        setTranslationY(dy);
                        grid.previewMove(this, mStartLeft + dx, mStartTop + dy);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
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
                setTranslationX(0f);
                setTranslationY(0f);
                if (grid != null) grid.abortInteraction();
                // Do NOT exit edit mode automatically
                return true;
        }
        return true;
    }

    private boolean isInResizeHandle(float localX, float localY) {
        int handle = dpToPx(getContext(), HANDLE_DP);
        return localX >= getWidth() - handle && localY >= getHeight() - handle;
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