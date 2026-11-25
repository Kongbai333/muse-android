package com.muse.workspace.mobile;

import android.content.Context;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** 名称与真实套餐紧邻；大字号或长名称时整枚徽标换行，不挤出可见区域。 */
final class AccountNameLine extends ViewGroup {
    private final TextView name, plan;
    private final int gap, rowGap;
    private boolean wrap;
    private int nameTop, planTop;

    AccountNameLine(Context context, TextView name, TextView plan, MobileUi ui) {
        super(context);
        this.name=name; this.plan=plan; gap=ui.dp(6); rowGap=ui.dp(3);
        plan.setMaxLines(Integer.MAX_VALUE);plan.setEllipsize(null);
        addView(name,new LayoutParams(-2,-2));addView(plan,new LayoutParams(-2,-2));
    }

    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
        int width=MeasureSpec.getSize(widthMeasureSpec), free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED);
        int limit=MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST);
        name.measure(limit,free);plan.measure(limit,free);
        boolean visible=plan.getVisibility()!=GONE;
        wrap=visible && (name.getLineCount()>1 || plan.getLineCount()>1 || name.getMeasuredWidth()+gap+plan.getMeasuredWidth()>width);
        nameTop=0;planTop=0;
        int height=name.getMeasuredHeight();
        if(visible){
            if(wrap){planTop=height+rowGap;height=planTop+plan.getMeasuredHeight();}
            else{
                float nameCenter=textCenter(name),planCenter=textCenter(plan),center=Math.max(nameCenter,planCenter);
                nameTop=Math.round(center-nameCenter);planTop=Math.round(center-planCenter);
                height=Math.max(nameTop+name.getMeasuredHeight(),planTop+plan.getMeasuredHeight());
            }
        }
        setMeasuredDimension(resolveSize(width,widthMeasureSpec),resolveSize(height,heightMeasureSpec));
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        place(name,0,nameTop);
        if(plan.getVisibility()!=GONE)place(plan,wrap?0:name.getMeasuredWidth()+gap,planTop);
    }

    private static float textCenter(TextView view){
        Layout text=view.getLayout();
        if(text==null||text.getLineCount()==0||view.getBaseline()<0)return view.getMeasuredHeight()/2f;
        int last=text.getLineCount()-1;
        int lines=text.getLineBaseline(last)-text.getLineBaseline(0);
        return view.getBaseline()+(lines+text.getLineAscent(0)+text.getLineDescent(last))/2f;
    }

    private void place(View view,int start,int top){int x=getLayoutDirection()==LAYOUT_DIRECTION_RTL?getWidth()-start-view.getMeasuredWidth():start;view.layout(x,top,x+view.getMeasuredWidth(),top+view.getMeasuredHeight());}
}
