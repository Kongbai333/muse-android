package com.muse.workspace.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** 27/24dp 视觉圆钮；顶栏按 48dp 目标扩展触区，并在相邻按钮中线分界。 */
final class AccountCardAction extends FrameLayout {
    private final MobileUi ui;
    private final AccountAppearance.Skin skin;
    private final FrameLayout circle;
    private final ImageView glyph;
    private int diameter;
    private boolean atEnd;
    private final Rect touchBounds = new Rect();

    AccountCardAction(Context context, MobileUi ui, AccountAppearance.Skin skin, int icon, String name, Runnable action) {
        super(context);this.ui=ui;this.skin=skin;
        circle=new FrameLayout(context);circle.setDuplicateParentStateEnabled(true);
        glyph=new ImageView(context);glyph.setImageResource(icon);glyph.setImageTintList(ColorStateList.valueOf(skin.ink));
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        circle.addView(glyph,new FrameLayout.LayoutParams(ui.dp(14),ui.dp(14),Gravity.CENTER));
        circle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        addView(circle);setFocusable(true);setContentDescription(name);setTooltipText(name);
        setOnClickListener(ignored->action.run());configure(false,false);
    }

    void configure(boolean compact,boolean end){
        int size=compact?24:27;
        if(diameter==size&&atEnd==end)return;
        diameter=size;atEnd=end;
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(ui.dp(size),ui.dp(size),(end?Gravity.END:Gravity.START)|Gravity.TOP);
        circle.setLayoutParams(position);
        circle.setBackground(new RippleDrawable(ColorStateList.valueOf(skin.ripple),ui.shape(skin.glaze,(size+1)/2,skin.hair),ui.shape(Color.WHITE,(size+1)/2,0)));
    }

    int visualWidth(){return ui.dp(diameter);}
    int targetWidth(){return ui.dp(48);}
    void touchBounds(Rect bounds){touchBounds.set(bounds);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){super.onMeasure(MeasureSpec.makeMeasureSpec(visualWidth(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(visualWidth(),MeasureSpec.EXACTLY));}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Button");
        if(!touchBounds.isEmpty()){
            Rect screen=new Rect();info.getBoundsInScreen(screen);
            screen.offset(touchBounds.left-getLeft(),touchBounds.top-getTop());screen.right=screen.left+touchBounds.width();screen.bottom=screen.top+touchBounds.height();
            info.setBoundsInScreen(screen);info.setBoundsInParent(touchBounds);
        }
    }
}
