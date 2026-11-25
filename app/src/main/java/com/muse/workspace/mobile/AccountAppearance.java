package com.muse.workspace.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;

/** 仅设备本地的纯色偏好；保留已有 ID，不进入账号、Cookie、代理或跨端传输数据。 */
final class AccountAppearance {
    private static final String PREFS = "muse-account-appearance";
    static final Skin[] ALL = {
        new Skin("ice", "银灰", "d9e4e8 233b44 45606b 547f8d 648995", false),
        new Skin("wave", "深蓝", "245a80 f2f8f3 dae8e6 a1dedc 83d2d3", true),
        new Skin("fan", "酒红", "722f3e fff4e7 f0ddd3 ecc18c e4ae75", true),
        new Skin("porcelain", "灰白", "ebece7 233954 46576a 406995 315b91", false),
        new Skin("weave", "蔷薇粉", "e5c1c5 452c40 624651 92536b 9b5c74", false),
        new Skin("pine", "松绿", "305646 f3f5df d3e3d4 d7d99e c3d4a0", true),
        new Skin("carbon2", "炭灰", "292c30 f1f2ed d0d4d1 c4d8d3 889da4", true),
        new Skin("terrace", "杏橙", "edc7a8 4a312b 68473b 9c553e b45e42", false),
        new Skin("orbit", "暮紫", "483d64 f9f5ff e0dbe9 decbf0 c3b4e4", true),
        new Skin("record", "琥珀黄", "dab565 3f321e 594527 705321 8a652e", false),
        new Skin("sage", "鼠尾草绿", "c9ddcf 213c33 425c4b 426b53 466d55", false),
        new Skin("clay", "陶粉", "edc9b5 4c3027 705044 93604d 98634f", false),
        new Skin("carbon", "深墨", "27343e faf4e7 d3d8d4 edc98a d4bf90", true)
    };

    static Skin find(String id) {
        for (Skin skin : ALL) if (skin.id.equals(id)) return skin;
        return ALL[10];
    }

    static Skin read(Context context, String accountId) {
        try { return find(accountId == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(accountId, null)); }
        catch (ClassCastException invalidPreference) { return find(null); }
    }

    static void save(Context context, String accountId, String skinId) {
        if (accountId != null && !accountId.isEmpty())
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(accountId, find(skinId).id).apply();
    }

    static int alpha(int color, int alpha) { return ColorUtils.setAlphaComponent(color, alpha); }

    static final class Skin {
        final String id, name;
        final int paper, ink, foil, line, copy, hair, glaze, ripple, alert;

        Skin(String id, String name, String palette, boolean dark) {
            this.id = id; this.name = name;
            String[] colors = palette.split(" ");
            paper = Color.parseColor("#" + colors[0]); ink = Color.parseColor("#" + colors[1]);
            int secondary = Color.parseColor("#" + colors[2]);
            foil = Color.parseColor("#" + colors[3]); line = Color.parseColor("#" + colors[4]);
            copy = ColorUtils.blendARGB(ink, secondary, .18f);
            hair = alpha(line, dark ? 104 : 88);
            glaze = dark ? 0x13ffffff : 0x38fff9ea;
            ripple = alpha(ink, dark ? 40 : 24);
            int warning = dark ? 0xffffc79b : 0xff8a3418;
            alert = ColorUtils.calculateContrast(warning, paper) >= 4.5 ? warning : ink;
        }

        Drawable surface(Context context, boolean selected) { return surface(context, selected, 23); }

        /** 卡面、预览和色块共用一种不透明填充，最多一道外框。 */
        Drawable surface(Context context, boolean selected, float radius) {
            float density = context.getResources().getDisplayMetrics().density;
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(paper);
            fill.setCornerRadius(radius * density);
            fill.setStroke(Math.max(1, Math.round(density)), selected ? foil : hair);
            return fill;
        }

        Drawable background(Context context, boolean selected) {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(23 * context.getResources().getDisplayMetrics().density);
            return new RippleDrawable(ColorStateList.valueOf(ripple), surface(context, selected), mask);
        }

        void press(TextView view) {
            view.setTextColor(ink);
            view.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        }

        void badge(TextView view, MobileUi ui) {
            view.setTextSize(11);
            view.setTextColor(ink);
            view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
            view.setBackground(ui.shape(glaze, 4, hair));
            view.setPadding(ui.dp(5), ui.dp(2), ui.dp(5), ui.dp(2));
        }
    }
}
