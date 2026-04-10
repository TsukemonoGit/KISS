package fr.neamar.kiss.ui;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    private static final float HORIZONTAL_SWIPE_CANCEL_MULTIPLIER = 1.0f;

    private boolean mHasPerformedLongPress;
    private CheckForLongPress mPendingCheckForLongPress;
    private float mDownX, mDownY;
    private final int mTouchSlop;

    private boolean mEditMode = false;
    private boolean mResizeMode = false;
    private boolean mDeletePressed = false;

    private float mGestureStartRawX, mGestureStartRawY;
    private int mStartLeft, mStartTop;

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
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!mEditMode)
            return;

        int w = getWidth();
        int h = getHeight();
        float density = getResources().getDisplayMetrics().density;

        mHandlePaint.setStyle(Paint.Style.FILL);
        mHandlePaint.setColor(Color.argb(50, 255, 255, 255));
        canvas.drawRoundRect(0, 0, w, h, 12 * density, 12 * density, mHandlePaint);

        float r = 6 * density;

        mHandlePaint.setStyle(Paint.Style.STROKE);
        mHandlePaint.setColor(Color.WHITE);
        mHandlePaint.setStrokeWidth(2 * density);
        canvas.drawRoundRect(r, r, w - r, h - r, 12 * density, 12 * density, mHandlePaint);

        mHandlePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(r, h / 2f, r, mHandlePaint); // Left
        canvas.drawCircle(w - r, h / 2f, r, mHandlePaint); // Right
        canvas.drawCircle(w / 2f, r, r, mHandlePaint); // Top
        canvas.drawCircle(w / 2f, h - r, r, mHandlePaint); // Bottom

        float dr = deleteButtonRadius(density);
        float cx = deleteButtonCx(w, density);
        float cy = deleteButtonCy(density);

        mHandlePaint.setColor(Color.argb(200, 255, 50, 50));
        mHandlePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, dr, mHandlePaint);

        mHandlePaint.setColor(Color.WHITE);
        mHandlePaint.setStyle(Paint.Style.STROKE);
        mHandlePaint.setStrokeWidth(2 * density);
        float p = 5 * density;
        canvas.drawLine(cx - p, cy - p, cx + p, cy + p, mHandlePaint);
        canvas.drawLine(cx + p, cy - p, cx - p, cy + p, mHandlePaint);
    }

    // -------------------------------------------------------------------------
    // Touch — long-press cancellation (always fires regardless of child intercept)
    // -------------------------------------------------------------------------

    /**
     * dispatchTouchEvent は子Viewが requestDisallowInterceptTouchEvent(true) を
     * 呼んだ後も必ず呼ばれる。ここでスワイプを検出してキャンセルすることで、
     * onInterceptTouchEvent の MOVE が届かない場合でも長押し判定を取り消せる。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!mEditMode) {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_MOVE: {
                    float deltaX = Math.abs(ev.getRawX() - mDownX);
                    float deltaY = Math.abs(ev.getRawY() - mDownY);
                    if (deltaX > mTouchSlop * HORIZONTAL_SWIPE_CANCEL_MULTIPLIER
                            || deltaY > mTouchSlop) {
                        mHasPerformedLongPress = false;
                        cancelPendingLongPress();
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mHasPerformedLongPress = false;
                    cancelPendingLongPress();
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // -------------------------------------------------------------------------
    // Touch — intercept / delegate
    // -------------------------------------------------------------------------

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

                    if (isDeleteButtonHit(ev.getX(), ev.getY())) {
                        mDeletePressed = true;
                        mResizeFlags = 0;
                        mResizeMode = false;
                        return true;
                    }
                    mDeletePressed = false;

                    mResizeFlags = calculateResizeFlags(ev.getX(), ev.getY());
                    mResizeMode = (mResizeFlags != 0);
                    captureLayoutStart();

                    if (getParent() instanceof WidgetGridLayout) {
                        ((WidgetGridLayout) getParent()).startDragOrResize(this);
                    }
                    requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (ev.getX() >= 0 && ev.getX() <= getWidth()
                        && ev.getY() >= 0 && ev.getY() <= getHeight()) {
                    postCheckForLongClick();
                }
                break;

            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mEditMode)
                    return true;
                // キャンセルは dispatchTouchEvent で処理済み。
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mEditMode)
            return super.onTouchEvent(ev);

        WidgetGridLayout grid = (getParent() instanceof WidgetGridLayout)
                ? (WidgetGridLayout) getParent()
                : null;

        float dx = ev.getRawX() - mGestureStartRawX;
        float dy = ev.getRawY() - mGestureStartRawY;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                if (mDeletePressed)
                    return true;

                if (grid != null) {
                    if (mResizeMode) {
                        int dLeft = (mResizeFlags & RESIZE_LEFT) != 0 ? (int) dx : 0;
                        int dRight = (mResizeFlags & RESIZE_RIGHT) != 0 ? (int) dx : 0;
                        int dTop = (mResizeFlags & RESIZE_TOP) != 0 ? (int) dy : 0;
                        int dBottom = (mResizeFlags & RESIZE_BOTTOM) != 0 ? (int) dy : 0;
                        grid.previewResize(this, dLeft, dTop, dRight, dBottom,
                                getMinResizeWidth(), getMinResizeHeight());
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
                    if (isDeleteButtonHit(ev.getX(), ev.getY()) && mListener != null) {
                        mListener.onWidgetDeleted(this);
                    }
                    mDeletePressed = false;
                    return true;
                }

                boolean dropped = (grid != null) && grid.commitDragOrResize(this);
                setTranslationX(0f);
                setTranslationY(0f);

                if (dropped && mListener != null) {
                    if (mResizeMode)
                        mListener.onWidgetResized(this);
                    else
                        mListener.onWidgetMoved(this);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                mDeletePressed = false;
                setTranslationX(0f);
                setTranslationY(0f);
                if (grid != null)
                    grid.abortInteraction();
                return true;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Delete button geometry helpers
    // -------------------------------------------------------------------------

    private float deleteButtonRadius(float density) {
        return 14 * density;
    }

    private float deleteButtonCx(int viewWidth, float density) {
        return viewWidth - deleteButtonRadius(density);
    }

    private float deleteButtonCy(float density) {
        return deleteButtonRadius(density);
    }

    /** ローカル座標 (localX, localY) が削除ボタンのヒット領域内かどうかを返す。 */
    private boolean isDeleteButtonHit(float localX, float localY) {
        float density = getResources().getDisplayMetrics().density;
        float cx = deleteButtonCx(getWidth(), density);
        float cy = deleteButtonCy(density);
        float hitRadius = 24 * density;
        return Math.hypot(localX - cx, localY - cy) <= hitRadius;
    }

    // -------------------------------------------------------------------------
    // Resize helpers
    // -------------------------------------------------------------------------

    private int calculateResizeFlags(float localX, float localY) {
        int w = getWidth();
        int h = getHeight();
        float margin = dpToPx(getContext(), -8);
        float r = dpToPx(getContext(), 24);

        int flags = 0;
        if (Math.hypot(localX - margin, localY - h / 2f) <= r)
            flags |= RESIZE_LEFT;
        if (Math.hypot(localX - (w - margin), localY - h / 2f) <= r)
            flags |= RESIZE_RIGHT;
        if (Math.hypot(localX - w / 2f, localY - margin) <= r)
            flags |= RESIZE_TOP;
        if (Math.hypot(localX - w / 2f, localY - (h - margin)) <= r)
            flags |= RESIZE_BOTTOM;
        return flags;
    }

    private int getMinResizeWidth() {
        AppWidgetProviderInfo info = getAppWidgetInfo();
        if (info == null)
            return 40;
        return info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth;
    }

    private int getMinResizeHeight() {
        AppWidgetProviderInfo info = getAppWidgetInfo();
        if (info == null)
            return 40;
        return info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
    }

    // -------------------------------------------------------------------------
    // Layout helpers
    // -------------------------------------------------------------------------

    private void captureLayoutStart() {
        mStartLeft = getLeft();
        mStartTop = getTop();
    }

    private static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------------------
    // Long-press machinery
    // -------------------------------------------------------------------------

    protected class CheckForLongPress implements Runnable {
        private int mOriginalWindowAttachCount;

        @Override
        public void run() {
            if (getParent() != null && hasWindowFocus()
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

    // -------------------------------------------------------------------------
    // Misc overrides
    // -------------------------------------------------------------------------

    @Override
    public int getDescendantFocusability() {
        return ViewGroup.FOCUS_BLOCK_DESCENDANTS;
    }

    @Override
    @SuppressWarnings("deprecation")
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