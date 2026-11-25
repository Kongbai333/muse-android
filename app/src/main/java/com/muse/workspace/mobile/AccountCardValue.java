package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import com.google.android.material.textview.MaterialTextView;

/** 周额度百分号保持完整字级，比值副文保留原样式，原始文本和复制数据不变。 */
final class AccountCardValue extends MaterialTextView {
    private boolean percent;
    AccountCardValue(Context context,boolean percent){super(context);this.percent=percent;if(percent)AccountCardTypography.weekly(this,true);else setTextSize(15);setTypeface(Typeface.create("sans-serif",Typeface.BOLD));setIncludeFontPadding(false);setFontFeatureSettings("tnum");}
    @Override public void setText(CharSequence text,BufferType type){
        if(!percent&&text!=null&&text.length()>0){String value=text.toString();int start=value.indexOf(' ');
            if(start<0)start=value.indexOf('/');
            if(start>=0){SpannableString styled=new SpannableString(text);styled.setSpan(new RelativeSizeSpan(.8f),start,value.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);super.setText(styled,type);return;}}
        super.setText(text,type);
    }
}
