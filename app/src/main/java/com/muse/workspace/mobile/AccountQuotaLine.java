package com.muse.workspace.mobile;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** 标题、日期与末端数字按基线排布；空间不足时整组自然换行。 */
final class AccountQuotaLine extends ViewGroup {
    private final TextView title,date,value;
    private final int gap,rowGap;
    private boolean wrap,dateWrap;
    private int headingBaseline,headingHeight,baseline;

    AccountQuotaLine(Context context,MobileUi ui,TextView title,TextView date,TextView value){
        super(context);this.title=title;this.date=date;this.value=value;gap=ui.dp(5);rowGap=ui.dp(3);
        addView(title);addView(date);addView(value);
    }

    @Override protected LayoutParams generateDefaultLayoutParams(){return new LayoutParams(-2,-2);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),limit=MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST);
        title.measure(limit,free);value.measure(limit,free);
        date.measure(limit,free);
        boolean visible=date.getVisibility()!=GONE;
        dateWrap=visible&&title.getMeasuredWidth()+gap+date.getMeasuredWidth()>width;
        int headingWidth=dateWrap?Math.max(title.getMeasuredWidth(),date.getMeasuredWidth()):title.getMeasuredWidth()+(visible?gap+date.getMeasuredWidth():0);
        headingBaseline=Math.max(title.getBaseline(),visible?date.getBaseline():0);
        headingHeight=dateWrap?title.getMeasuredHeight()+rowGap+date.getMeasuredHeight():Math.max(headingBaseline-title.getBaseline()+title.getMeasuredHeight(),visible?headingBaseline-date.getBaseline()+date.getMeasuredHeight():0);
        wrap=dateWrap||headingWidth+gap+value.getMeasuredWidth()>width;
        baseline=Math.max(headingBaseline,value.getBaseline());
        int height=wrap?headingHeight+rowGap+value.getMeasuredHeight():Math.max(baseline-headingBaseline+headingHeight,baseline-value.getBaseline()+value.getMeasuredHeight());
        setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(height,heightSpec));
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        int base=wrap?headingBaseline:baseline;
        place(title,0,dateWrap?0:base-title.getBaseline());
        if(date.getVisibility()!=GONE)place(date,dateWrap?0:title.getMeasuredWidth()+gap,dateWrap?title.getMeasuredHeight()+rowGap:base-date.getBaseline());
        place(value,getWidth()-value.getMeasuredWidth(),wrap?headingHeight+rowGap:baseline-value.getBaseline());
    }

    private void place(View v,int start,int y){int x=getLayoutDirection()==LAYOUT_DIRECTION_RTL?getWidth()-start-v.getMeasuredWidth():start;v.layout(x,y,x+v.getMeasuredWidth(),y+v.getMeasuredHeight());}
}
