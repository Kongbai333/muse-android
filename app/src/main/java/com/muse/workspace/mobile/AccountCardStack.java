package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.core.view.ViewCompat;

/** 同宽原高的实体卡依次绘制，后卡覆盖前卡；只改变相邻顶部的距离。 */
final class AccountCardStack extends ViewGroup {
    AccountCardStack(Context context) {
        super(context);
        setClipChildren(true);
    }

    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(-1, -2); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int childWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int height = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            height += i == getChildCount() - 1 || !(child instanceof Card)
                ? child.getMeasuredHeight() : ((Card) child).followingOffset();
        }
        // 末卡完整收尾；不是每张露出高度的总和，也不额外追加一张卡的空白。
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height + getPaddingBottom(), heightMeasureSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int y = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(getPaddingLeft(), y, getPaddingLeft() + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            int offset = i == getChildCount() - 1 || !(child instanceof Card)
                ? child.getMeasuredHeight() : ((Card) child).followingOffset();
            if (child instanceof Card) ((Card) child).setUncoveredHeight(offset);
            y += offset;
        }
    }

    /** 一张始终不透明、原高的卡面；没有重复摘要、逐卡裁剪或淡入淡出。 */
    static final class Card extends FrameLayout {
        private final LinearLayout content;
        private final View header;
        private final ViewGroup details;
        private final AccountCardTop top;
        private final String description;
        private final boolean followed;
        private String weekly = "待同步";
        private float expansion;
        private int moveFrom = -1;
        private float moveProgress;
        private boolean expanded, cancelledGesture;
        private int uncoveredHeight = Integer.MAX_VALUE;
        private final int touchSlop;
        private float downX, downY;

        Card(Context context, LinearLayout content, View header, ViewGroup details, AccountCardTop top,
                String description, boolean followed, Runnable toggle) {
            super(context);
            this.content = content;
            this.header = header;
            this.details = details;
            this.top = top;
            this.description = description;
            this.followed = followed;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            int border = Math.round(getResources().getDisplayMetrics().density);
            setPadding(border, border, border, border);
            addView(content, new FrameLayout.LayoutParams(-1, -2));
            setClipToOutline(true);
            setFocusable(true);
            if (followed) setOnClickListener(ignored -> toggle.run());
            setExpanded(false);
        }

        boolean hasFollowing() { return followed; }
        float expansion() { return expansion; }
        boolean expanded() { return expanded; }

        private int detailsTop() {
            int margin = details.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? ((LinearLayout.LayoutParams) details.getLayoutParams()).topMargin : 0;
            return getPaddingTop() + content.getPaddingTop() + header.getMeasuredHeight() + margin;
        }

        int followingOffset() {
            int natural = getMeasuredHeight();
            // 与 HTML upper 一致：包含进度条后 11dp 留白，下一张从这里覆盖。
            int border = Math.round(getResources().getDisplayMetrics().density);
            int stacked = Math.min(natural, content.getPaddingTop() + header.getMeasuredHeight() + border);
            if (moveFrom >= 0) return Math.round(moveFrom + ((expanded ? natural : stacked) - moveFrom) * moveProgress);
            return expanded ? natural : stacked;
        }

        void setWeekly(String value) { weekly = value; describe(); }

        void setExpanded(boolean value) {
            expanded = followed && value;
            top.showEditor(!followed || expanded);
            describe();
            ViewCompat.setStateDescription(this, !followed ? "完整显示" : expanded ? "已展开" : "已叠放");
        }

        private void describe() {
            setContentDescription(description + "，周额度 " + weekly
                + (!followed ? "，完整账号卡" : expanded ? "，点按叠回" : "，点按展开"));
        }

        void reveal(float value) {
            expansion = Math.max(0f, Math.min(1f, value));
            moveFrom = -1;
            if (getParent() != null) getParent().requestLayout();
        }

        void beginMove(int currentOffset) { moveFrom = currentOffset; moveProgress = 0f; }

        void move(float value) {
            moveProgress = Math.max(0f, Math.min(1f, value));
            if (getParent() != null) getParent().requestLayout();
        }

        private int visibleHeight() { return Math.min(getMeasuredHeight(), uncoveredHeight); }

        void setUncoveredHeight(int value) {
            uncoveredHeight = value;
            boolean allVisible = value >= getMeasuredHeight();
            details.setImportantForAccessibility(allVisible ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            details.setDescendantFocusability(allVisible ? FOCUS_BEFORE_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
            if (!allVisible) details.clearFocus();
        }

        private boolean insideVisibleCard(MotionEvent event) {
            return event.getX() >= 0 && event.getX() < getWidth() && event.getY() >= 0 && event.getY() < visibleHeight();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!insideVisibleCard(event)) return false;
                cancelledGesture = false;
                downX = event.getX(); downY = event.getY();
            }
            if (cancelledGesture) return true;
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (action != MotionEvent.ACTION_CANCEL && (!insideVisibleCard(event)
                    || event.getPointerCount() > 1
                    || (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) && dx * dx + dy * dy > touchSlop * touchSlop)) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                top.actionTouch(this, cancel);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                cancelledGesture = true;
                return true;
            }
            return top.actionTouch(this, event) || super.dispatchTouchEvent(event);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            // 过渡中只有部分露出的详情控件不抢点击；头部切换按钮始终独立。
            return visibleHeight() < getMeasuredHeight() && event.getY() >= detailsTop()
                || super.onInterceptTouchEvent(event);
        }

        @Override public void getFocusedRect(Rect bounds) { bounds.set(0, 0, getWidth(), visibleHeight()); }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            Rect bounds = new Rect();
            info.getBoundsInParent(bounds);
            bounds.bottom = bounds.top + visibleHeight();
            info.setBoundsInParent(bounds);
            info.getBoundsInScreen(bounds);
            bounds.bottom = Math.min(bounds.bottom, bounds.top + visibleHeight());
            info.setBoundsInScreen(bounds);
        }
    }
}
