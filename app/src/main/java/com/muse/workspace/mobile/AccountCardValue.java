package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import com.google.android.material.textview.MaterialTextView;

/** 只调整百分号或比值的字级，原始显示文本和复制数据不变。 */
final class AccountCardValue extends MaterialTextView {
    private boolean percent;
    AccountCardValue(Context context,boolean percent){super(context);this.percent=percent;setTextSize(percent?20:15);setTypeface(Typeface.create("sans-serif",Typeface.BOLD));setIncludeFontPadding(false);setFontFeatureSettings("tnum");}
    @Override public void setText(CharSequence text,BufferType type){
        if(text!=null&&text.length()>0){String value=text.toString();int start=percent&&value.endsWith("%")?value.length()-1:!percent?value.indexOf(' '):-1;
            if(!percent&&start<0)start=value.indexOf('/');
            if(start>=0){SpannableString styled=new SpannableString(text);styled.setSpan(new RelativeSizeSpan(percent?.6f:.8f),start,value.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);super.setText(styled,type);return;}}
        super.setText(text,type);
    }
}
