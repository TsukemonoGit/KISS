package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import fr.neamar.kiss.utils.Log;

public class WidgetGridLayout extends ViewGroup {

    private static final String TAG = WidgetGridLayout.class.getSimpleName();

    public static final int COLUMNS = 4;
    private int mCellWidth;
    private int mCellHeight;

    private boolean[][] mOccupied;
    private int mRows = 5; // Default, will be recalculated

    // Track max rows needed by content
    private int mCalculatedRows = 5;

    // Drag / Drop preview fields
    private View mDragView;
    private int mDropTargetCellX = -1;
    private int mDropTargetCellY = -1;
    private int mDropTargetSpanX = -1;
    private int mDropTargetSpanY = -1;
    private boolean mDropTargetValid = true;

    private final Paint mPreviewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WidgetGridLayout(@NonNull Context context) {
        super(context);
        init();
    }

    public WidgetGridLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WidgetGridLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        mCellHeight = (int) (75 * getResources().getDisplayMetrics().density); // Default 75dp cell height
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            float x = ev.getX();
            float y = ev.getY();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() != GONE && child instanceof WidgetView) {
                    WidgetView wv = (WidgetView) child;
                    if (wv.isInEditMode()) {
                        if (x < child.getLeft() || x > child.getRight() || y < child.getTop() || y > child.getBottom()) {
                            wv.exitEditMode();
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        mCellWidth = widthSize / COLUMNS;
        if (mCellWidth == 0) mCellWidth = 1;

        if (heightSize > 0) {
            mCalculatedRows = Math.max(1, heightSize / mCellHeight);
        }
        
        mRows = mCalculatedRows;

        // First pass: adjust mRows to fit all children if somehow they span beyond normal height
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp.cellY + lp.spanY > mRows) {
                    mRows = lp.cellY + lp.spanY;
                }
            }
        }
        
        mOccupied = new boolean[COLUMNS][mRows];

        // Measure children
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                
                int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(lp.spanX * mCellWidth, MeasureSpec.EXACTLY);
                int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(lp.spanY * mCellHeight, MeasureSpec.EXACTLY);
                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
                
                // Mark occupied (skip the view currently being dragged)
                if (child != mDragView) {
                    markCells(lp.cellX, lp.cellY, lp.spanX, lp.spanY, true);
                }
            }
        }

        setMeasuredDimension(widthSize, heightSize);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int childLeft = lp.cellX * mCellWidth;
                int childTop = lp.cellY * mCellHeight;
                child.layout(childLeft, childTop, childLeft + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (mDragView != null && mDropTargetCellX >= 0 && mDropTargetCellY >= 0) {
            if (mDropTargetValid) {
                mPreviewPaint.setColor(Color.argb(100, 100, 255, 100)); // Greenish
            } else {
                mPreviewPaint.setColor(Color.argb(100, 255, 100, 100)); // Reddish
            }
            
            int left = mDropTargetCellX * mCellWidth;
            int top = mDropTargetCellY * mCellHeight;
            int right = left + (mDropTargetSpanX * mCellWidth);
            int bottom = top + (mDropTargetSpanY * mCellHeight);
            
            int padding = (int)(2 * getResources().getDisplayMetrics().density);
            canvas.drawRect(left + padding, top + padding, right - padding, bottom - padding, mPreviewPaint);
        }
    }

    // --- Grid Logic ---

    public int getCellWidth() { return mCellWidth; }
    public int getCellHeight() { return mCellHeight; }

    public int getRows() { return mRows; }

    private void markCells(int x, int y, int spanX, int spanY, boolean occupied) {
        if (mOccupied == null) return;
        for (int ix = x; ix < x + spanX; ix++) {
            for (int iy = y; iy < y + spanY; iy++) {
                if (ix >= 0 && ix < COLUMNS && iy >= 0 && iy < mRows) {
                    mOccupied[ix][iy] = occupied;
                }
            }
        }
    }

    public boolean isAreaEmpty(int x, int y, int spanX, int spanY, View ignoreView) {
        if (x < 0 || y < 0 || x + spanX > COLUMNS) {
            return false; // Horizontal out of bounds
        }
        if (y < 0 || y + spanY > Math.max(mRows, mCalculatedRows)) {
            return false; // Vertical out of bounds (allow grid to momentarily stretch if needed)
        }
        
        // Build an occupancy mask excluding the ignored view safely
        int rowsSafe = Math.max(mRows, y + spanY);
        boolean[][] tempOccupied = new boolean[COLUMNS][rowsSafe];
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE && child != ignoreView) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                for (int ix = lp.cellX; ix < lp.cellX + lp.spanX; ix++) {
                    for (int iy = lp.cellY; iy < lp.cellY + lp.spanY; iy++) {
                        if (ix >= 0 && ix < COLUMNS && iy >= 0 && iy < rowsSafe) {
                            tempOccupied[ix][iy] = true;
                        }
                    }
                }
            }
        }

        for (int ix = x; ix < x + spanX; ix++) {
            for (int iy = y; iy < y + spanY; iy++) {
                if (tempOccupied[ix][iy]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Finds the first empty space that can fit the given spans.
     * Returns a 2-element array [cellX, cellY] or null if no space.
     */
    public int[] findFirstEmptySpace(int spanX, int spanY) {
        if (mOccupied == null) return null; // Not measured yet
        // allow searching below current mRows if the widget is tall or grid is full
        int searchRows = Math.max(mRows, mRows + spanY); 
        for (int y = 0; y <= searchRows; y++) {
            for (int x = 0; x <= COLUMNS - spanX; x++) {
                if (isAreaEmpty(x, y, spanX, spanY, null)) {
                    return new int[]{x, y};
                }
            }
        }
        return null; // Grid might be totally full (unlikely if it grows infinitely downwards) or widget too large horizontally
    }

    // --- Drag and Resize ---

    public void startDragOrResize(View view) {
        mDragView = view;
        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        mDropTargetCellX = lp.cellX;
        mDropTargetCellY = lp.cellY;
        mDropTargetSpanX = lp.spanX;
        mDropTargetSpanY = lp.spanY;
        mDropTargetValid = true;
        invalidate();
    }

    public void previewMove(View view, float pixelX, float pixelY) {
        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        
        mDropTargetSpanX = lp.spanX;
        mDropTargetSpanY = lp.spanY;

        // Determine destination cell by adding half a cell size for snapping center
        mDropTargetCellX = Math.round(pixelX / mCellWidth);
        mDropTargetCellY = Math.round(pixelY / mCellHeight);

        // Clamp to borders
        mDropTargetCellX = Math.max(0, Math.min(mDropTargetCellX, COLUMNS - mDropTargetSpanX));
        mDropTargetCellY = Math.max(0, Math.min(mDropTargetCellY, mRows - mDropTargetSpanY));

        mDropTargetValid = isAreaEmpty(mDropTargetCellX, mDropTargetCellY, mDropTargetSpanX, mDropTargetSpanY, view);
        invalidate();
    }

    public void previewResize(View view, int rawWidthPx, int rawHeightPx, int minWidthDp, int minHeightDp) {
        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        float density = getResources().getDisplayMetrics().density;
        
        mDropTargetCellX = lp.cellX;
        mDropTargetCellY = lp.cellY;

        // Calculate needed span based on requested pixel size
        int newSpanX = Math.max(1, Math.round((float) rawWidthPx / mCellWidth));
        int newSpanY = Math.max(1, Math.round((float) rawHeightPx / mCellHeight));

        // Enforce min spans based on Android AppWidgetProviderInfo
        int minSpanX = Math.max(1, (int) Math.ceil((minWidthDp * density) / mCellWidth));
        int minSpanY = Math.max(1, (int) Math.ceil((minHeightDp * density) / mCellHeight));
        
        newSpanX = Math.max(newSpanX, minSpanX);
        newSpanY = Math.max(newSpanY, minSpanY);

        mDropTargetSpanX = Math.min(newSpanX, COLUMNS - lp.cellX);
        // Allow widget to resize downwards dynamically if we want
        mDropTargetSpanY = newSpanY;

        mDropTargetValid = isAreaEmpty(mDropTargetCellX, mDropTargetCellY, mDropTargetSpanX, mDropTargetSpanY, view);
        invalidate();
    }

    /**
     * Applies the drop locally and returns whether it was valid.
     */
    public boolean commitDragOrResize(View view) {
        boolean valid = mDropTargetValid;
        if (valid && mDragView == view) {
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.cellX = mDropTargetCellX;
            lp.cellY = mDropTargetCellY;
            lp.spanX = mDropTargetSpanX;
            lp.spanY = mDropTargetSpanY;
            requestLayout(); // Cause a relayout
        }
        
        // Reset state
        mDragView = null;
        mDropTargetCellX = -1;
        mDropTargetCellY = -1;
        invalidate();
        return valid;
    }

    public void abortInteraction() {
        mDragView = null;
        mDropTargetCellX = -1;
        mDropTargetCellY = -1;
        invalidate();
    }


    // --- LayoutParams ---

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(0, 0, 1, 1);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    public static class LayoutParams extends ViewGroup.LayoutParams {
        public int cellX;
        public int cellY;
        public int spanX;
        public int spanY;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
            this.cellX = 0;
            this.cellY = 0;
            this.spanX = 1;
            this.spanY = 1;
        }

        public LayoutParams(int cx, int cy, int sx, int sy) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.cellX = cx;
            this.cellY = cy;
            this.spanX = sx;
            this.spanY = sy;
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
            if (source instanceof LayoutParams) {
                LayoutParams lp = (LayoutParams) source;
                this.cellX = lp.cellX;
                this.cellY = lp.cellY;
                this.spanX = lp.spanX;
                this.spanY = lp.spanY;
            } else {
                this.cellX = 0;
                this.cellY = 0;
                this.spanX = 1;
                this.spanY = 1;
            }
        }
    }
}
