package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import androidx.core.view.ViewCompat;

/** 卡片始终按原内容自然高度测量，只改变列表中的位置及可见裁剪区域。 */
final class AccountCardStack extends ViewGroup {
    private final int gap;

    AccountCardStack(Context context, int gap) {
        super(context);
        this.gap = gap;
        setClipChildren(true);
    }

    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(-1, -2); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int childWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int height = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            // 不把露出高度作为卡片的测量约束；完整卡和详情从未缩为零或 GONE。
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (i > 0) height += gap;
            height += child instanceof Card ? ((Card) child).exposedHeight() : child.getMeasuredHeight();
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height + getPaddingBottom(), heightMeasureSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int y = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (i > 0) y += gap;
            child.layout(getPaddingLeft(), y, getPaddingLeft() + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            y += child instanceof Card ? ((Card) child).exposedHeight() : child.getMeasuredHeight();
        }
    }

    /** 完整卡自然排版不变；摘要是叠放露出区域的独立表面，不参与压缩完整卡。 */
    static final class Card extends FrameLayout {
        private final ViewGroup full, summary;
        private final String description;
        private String weekly = "待同步";
        private float expansion;
        private boolean expanded, compactGesture, summaryGesture, clippedGesture;

        Card(Context context, ViewGroup full, ViewGroup summary, String description, Runnable toggle) {
            super(context);
            this.full = full;
            this.summary = summary;
            this.description = description;
            addView(full, new FrameLayout.LayoutParams(-1, -2));
            addView(summary, new FrameLayout.LayoutParams(-1, -2));
            setClipToOutline(true);
            setFocusable(true);
            setOnClickListener(ignored -> toggle.run());
            setExpanded(false);
            reveal(0f);
        }

        float expansion() { return expansion; }
        boolean expanded() { return expanded; }

        int exposedHeight() {
            int natural = getMeasuredHeight();
            int compact = Math.min(natural, summary.getMeasuredHeight());
            return compact + Math.round((natural - compact) * expansion);
        }

        void setWeekly(String value) { weekly = value; describe(); }

        void setExpanded(boolean value) {
            expanded = value;
            updateInteraction();
            describe();
            ViewCompat.setStateDescription(this, value ? "已展开" : "已叠放");
        }

        private void describe() {
            setContentDescription(description + "，周额度 " + weekly + (expanded ? "，点按叠回" : "，点按展开"));
        }

        void reveal(float value) {
            expansion = Math.max(0f, Math.min(1f, value));
            full.setAlpha(expansion);
            summary.setAlpha(1f - expansion);
            // INVISIBLE 只用于重复的摘要表面；完整卡/详情始终 VISIBLE 且保持原高。
            summary.setVisibility(fullyOpen() ? INVISIBLE : VISIBLE);
            updateInteraction();
            setClipBounds(new Rect(0, 0, getMeasuredWidth(), exposedHeight()));
            if (getParent() != null) getParent().requestLayout();
        }

        private boolean fullyOpen() { return expanded && expansion == 1f; }

        private void updateInteraction() {
            boolean open = fullyOpen();
            full.setImportantForAccessibility(open ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            full.setDescendantFocusability(open ? FOCUS_BEFORE_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
            summary.setImportantForAccessibility(open ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            summary.setDescendantFocusability(open ? FOCUS_BLOCK_DESCENDANTS : FOCUS_BEFORE_DESCENDANTS);
            if (!open) full.clearFocus();
            else summary.clearFocus();
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            setClipBounds(new Rect(0, 0, getWidth(), exposedHeight()));
        }

        private boolean insideExposure(MotionEvent event) {
            return event.getX() >= 0 && event.getX() < getWidth() && event.getY() >= 0 && event.getY() < exposedHeight();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!insideExposure(event)) return false;
                compactGesture = !fullyOpen();
                summaryGesture = clippedGesture = false;
            }
            if (!compactGesture) return super.dispatchTouchEvent(event);
            if (clippedGesture) return true;
            if (action != MotionEvent.ACTION_CANCEL && !insideExposure(event)) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                if (summaryGesture) summary.dispatchTouchEvent(cancel);
                else super.onTouchEvent(cancel);
                cancel.recycle();
                clippedGesture = true;
                return true;
            }
            // 叠放/过渡只分发到可见摘要；后方完整卡即使同坐标有按钮也收不到触摸。
            if (action == MotionEvent.ACTION_DOWN) summaryGesture = summary.dispatchTouchEvent(event);
            else if (summaryGesture) summary.dispatchTouchEvent(event);
            return summaryGesture || super.onTouchEvent(event);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            Rect bounds = new Rect();
            info.getBoundsInParent(bounds);
            bounds.bottom = bounds.top + exposedHeight();
            info.setBoundsInParent(bounds);
            info.getBoundsInScreen(bounds);
            bounds.bottom = Math.min(bounds.bottom, bounds.top + exposedHeight());
            info.setBoundsInScreen(bounds);
        }
    }
}
