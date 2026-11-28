package com.muse.workspace.mobile;

import android.content.Context;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.google.android.material.textview.MaterialTextView;
import static com.muse.workspace.mobile.MobileUi.*;

/** 只为确实超过三行的真实提示词提供开合，随宽度和字体重新测量。 */
final class StudioPromptView extends MaterialTextView {
    private final String full;
    private final TextView hint;
    private boolean expanded,overflow,dirty=true;

    StudioPromptView(Context context,MobileUi ui,String text,Runnable inspect){
        super(context);full=text==null?"":text;setText(full);setTextSize(12.5f);setTextColor(INK);setIncludeFontPadding(false);setLineSpacing(ui.dp(3),1f);
        hint=ui.text("",11.5f,TEXT2,true);hint.setMinHeight(ui.dp(28));hint.setGravity(android.view.Gravity.CENTER_VERTICAL);
        hint.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);hint.setFocusable(false);hint.setOnClickListener(ignored->toggle());
        setOnLongClickListener(ignored->{inspect.run();return true;});
    }

    View hint(){return hint;}
    private void toggle(){if(!overflow)return;expanded=!expanded;dirty=true;requestLayout();}

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=Math.max(1,MeasureSpec.getSize(widthSpec)-getCompoundPaddingLeft()-getCompoundPaddingRight());
        Layout complete=StaticLayout.Builder.obtain(full,0,full.length(),getPaint(),width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(getIncludeFontPadding())
            .setLineSpacing(getLineSpacingExtra(),getLineSpacingMultiplier()).setBreakStrategy(getBreakStrategy()).setHyphenationFrequency(getHyphenationFrequency()).build();
        boolean longer=complete.getLineCount()>3;
        if(longer!=overflow){overflow=longer;if(!overflow)expanded=false;dirty=true;}
        if(dirty){
            dirty=false;setMaxLines(overflow&&!expanded?3:Integer.MAX_VALUE);setEllipsize(overflow&&!expanded?TextUtils.TruncateAt.END:null);
            setOnClickListener(overflow?ignored->toggle():null);setClickable(overflow);setFocusable(true);
            setContentDescription("提示词："+full+(overflow?expanded?"，已展开，点按收起":"，已收起，点按展开":"")+"，长按查看或复制");
            ViewCompat.setStateDescription(this,overflow?expanded?"已展开":"已收起":null);
            hint.setVisibility(overflow?VISIBLE:GONE);hint.setText(expanded?"收起提示词":"展开提示词");
        }
        super.onMeasure(widthSpec,heightSpec);
    }
}
