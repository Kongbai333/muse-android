package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 与卡面预览共用的头像 / 身份 / 右上操作三栏。 */
final class AccountCardTop extends ViewGroup {
    private final MobileUi ui;
    private final View portrait,identity;
    private final AccountCardAction edit,switchAccount;
    private final boolean preview;
    private int avatarColumn,avatarSize,reserved;
    private final Rect editHit=new Rect(),switchHit=new Rect();
    private AccountCardAction touched;

    AccountCardTop(Context context,MobileUi ui,View portrait,View identity,AccountCardAction edit,AccountCardAction switchAccount,boolean preview){
        super(context);this.ui=ui;this.portrait=portrait;this.identity=identity;this.edit=edit;this.switchAccount=switchAccount;this.preview=preview;
        setClipChildren(false);addView(portrait);addView(identity);
        if(edit!=null)addView(edit);if(switchAccount!=null)addView(switchAccount);
    }

    void showEditor(boolean full){if(edit!=null)edit.setVisibility(full?VISIBLE:GONE);}

    @Override protected LayoutParams generateDefaultLayoutParams(){return new LayoutParams(-2,-2);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED);
        boolean compact=width<ui.dp(260);avatarSize=ui.dp(preview||compact?30:35);avatarColumn=ui.dp(preview?32:compact?30:36);
        portrait.measure(MeasureSpec.makeMeasureSpec(avatarSize,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(avatarSize,MeasureSpec.EXACTLY));
        reserved=0;
        if(switchAccount!=null){
            switchAccount.configure(compact,false);switchAccount.measure(free,free);reserved=switchAccount.visualWidth();
            if(edit!=null&&edit.getVisibility()!=GONE){edit.configure(compact,false);edit.measure(free,free);reserved+=edit.visualWidth()+ui.dp(1);}
        }
        int identityWidth=Math.max(0,width-avatarColumn-ui.dp(7)-(reserved==0?0:reserved+ui.dp(compact?13:11)));
        identity.measure(MeasureSpec.makeMeasureSpec(identityWidth,MeasureSpec.EXACTLY),free);
        int height=Math.max(avatarSize,identity.getMeasuredHeight());if(switchAccount!=null)height=Math.max(height,ui.dp(48));
        setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(height,heightSpec));
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        place(portrait,0,(getHeight()-avatarSize)/2,avatarSize,avatarSize);
        place(identity,avatarColumn+ui.dp(7),(getHeight()-identity.getMeasuredHeight())/2,identity.getMeasuredWidth(),identity.getMeasuredHeight());
        int start=getWidth()-reserved;
        if(edit!=null&&edit.getVisibility()!=GONE){place(edit,start,0,edit.getMeasuredWidth(),edit.getMeasuredHeight());start+=edit.visualWidth()+ui.dp(1);}
        if(switchAccount!=null)place(switchAccount,start,0,switchAccount.getMeasuredWidth(),switchAccount.getMeasuredHeight());
        editHit.setEmpty();switchHit.setEmpty();
        if(switchAccount!=null){
            hit(switchAccount,switchHit);
            if(edit!=null&&edit.getVisibility()!=GONE){hit(edit,editHit);int middle=(edit.getLeft()+edit.getWidth()/2+switchAccount.getLeft()+switchAccount.getWidth()/2)/2;
                if(edit.getLeft()<switchAccount.getLeft()){editHit.right=middle;switchHit.left=middle;}else{editHit.left=middle;switchHit.right=middle;}
                edit.touchBounds(editHit);
            }
            switchAccount.touchBounds(switchHit);
        }
    }

    private void hit(AccountCardAction action,Rect bounds){int center=action.getLeft()+action.getWidth()/2,half=action.targetWidth()/2;bounds.set(center-half,0,center+half,ui.dp(48));}

    boolean actionTouch(ViewGroup card,MotionEvent event){
        if(switchAccount==null)return false;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){touched=null;Rect bounds=new Rect(editHit);if(edit!=null&&edit.getVisibility()==VISIBLE){card.offsetDescendantRectToMyCoords(this,bounds);if(bounds.contains((int)event.getX(),(int)event.getY()))touched=edit;}
            if(touched==null){bounds.set(switchHit);card.offsetDescendantRectToMyCoords(this,bounds);if(bounds.contains((int)event.getX(),(int)event.getY()))touched=switchAccount;}}
        if(touched==null)return false;
        MotionEvent local=MotionEvent.obtain(event);local.setLocation(touched.getWidth()/2f,touched.getHeight()/2f);
        if(touched.getVisibility()!=VISIBLE)local.setAction(MotionEvent.ACTION_CANCEL);
        touched.dispatchTouchEvent(local);local.recycle();
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)touched=null;
        return true;
    }

    private void place(View v,int start,int y,int width,int height){int x=getLayoutDirection()==LAYOUT_DIRECTION_RTL?getWidth()-start-width:start;v.layout(x,y,x+width,y+height);}

    static LinearLayout proxyLine(Context context,MobileUi ui,AccountAppearance.Skin skin,String route){
        LinearLayout line=ui.row();line.addView(ui.iconView(R.drawable.ic_route,skin.copy,13));
        TextView text=ui.text("代理 · "+route,13,skin.copy,false);text.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMarginStart(ui.dp(3));line.addView(text,p);return line;
    }
}
