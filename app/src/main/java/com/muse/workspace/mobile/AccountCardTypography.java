package com.muse.workspace.mobile;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.widget.TextView;

/** 实卡与编辑预览共用的两个字级角色，使用 sp 跟随系统字体大小。 */
final class AccountCardTypography {
    private static final float NAME_SP = 16, WEEK_SP = 17;

    static TextView name(MobileUi ui, AccountAppearance.Skin skin, String value) {
        TextView text = ui.text(value, NAME_SP, skin.ink, true);
        skin.press(text);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        return text;
    }

    static void weekly(TextView text, boolean known) {
        text.setTextSize(known ? WEEK_SP : 12);
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.setFontFeatureSettings("tnum");
    }
}
