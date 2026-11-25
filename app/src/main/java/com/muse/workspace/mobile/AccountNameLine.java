package com.muse.workspace.mobile;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** 名称与真实套餐紧邻；大字号或长名称时整枚徽标换行，不挤出可见区域。 */
final class AccountNameLine extends ViewGroup {
    private final TextView name, plan;
    private final int gap, rowGap;
    private boolean wrap;

    AccountNameLine(Context context, TextView name, TextView plan, MobileUi ui) {
        super(context);
        this.name=name; this.plan=plan; gap=ui.dp(6); rowGap=ui.dp(3);
        addView(name,new LayoutParams(-2,-2));addView(plan,new LayoutParams(-2,-2));
    }

    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
        int width=MeasureSpec.getSize(widthMeasureSpec), free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED);
        int limit=MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST);
        name.measure(limit,free);plan.measure(limit,free);
        wrap=plan.getVisibility()!=GONE && name.getMeasuredWidth()+gap+plan.getMeasuredWidth()>width;
        int height=plan.getVisibility()==GONE?name.getMeasuredHeight():wrap?name.getMeasuredHeight()+rowGap+plan.getMeasuredHeight():Math.max(name.getMeasuredHeight(),plan.getMeasuredHeight());
        setMeasuredDimension(resolveSize(width,widthMeasureSpec),resolveSize(height,heightMeasureSpec));
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        place(name,0,0);
        if(plan.getVisibility()!=GONE)place(plan,wrap?0:name.getMeasuredWidth()+gap,wrap?name.getMeasuredHeight()+rowGap:Math.max(0,name.getBaseline()-plan.getBaseline()));
    }

    private void place(View view,int start,int top){int x=getLayoutDirection()==LAYOUT_DIRECTION_RTL?getWidth()-start-view.getMeasuredWidth():start;view.layout(x,top,x+view.getMeasuredWidth(),top+view.getMeasuredHeight());}
}
