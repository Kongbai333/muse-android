package com.muse.workspace.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;
import java.util.function.IntConsumer;

/**
 * 「暗房」界面工具。颜色来自 muse_* 资源（values 为白昼，values-night 为暗房），
 * 通过 {@link #load(Context)} 读取有效资源；外观变化只重建原生界面。
 */
final class MobileUi {
    static int BG, SURFACE, RAISED, LINE, LINE_STRONG, INK, TEXT2, MUTED, ACCENT, ON_ACCENT, ACCENT_SOFT, SCRIM, PROGRESS;
    /** 额度色阶：已用 0 / 45 / 65 / 82 / 95% 五个色标。 */
    static int Q0, Q1, Q2, Q3, Q4;
    /** 元数据等宽字：JetBrains Mono（res/font，SIL OFL 1.1）；读取失败时退回系统等宽字。 */
    static Typeface MONO, MONO_MEDIUM;
    /** 圆角三档：标签与小控件 / 卡片、图片、按钮 / 托盘、侧栏、弹窗。 */
    static final int R_SM = 6, R_MD = 12, R_LG = 20;
    private static final float[] QUOTA_STOPS = {0f, 45f, 65f, 82f, 95f};
    private static final android.animation.ArgbEvaluator BLEND = new android.animation.ArgbEvaluator();
    static boolean NIGHT = true;
    static final String PREFS = "muse-ui";
    private static final String THEME_KEY = "theme-darkroom";
    private static final String THEME_MODE_KEY = "theme-mode";

    final int chrome;
    private final Context context;

    MobileUi(Context context) {
        this.context = context;
        load(context);
        chrome = BG;
    }

    static void load(Context context) {
        BG = context.getColor(R.color.muse_bg);
        SURFACE = context.getColor(R.color.muse_surface);
        RAISED = context.getColor(R.color.muse_raised);
        LINE = context.getColor(R.color.muse_line);
        LINE_STRONG = context.getColor(R.color.muse_line_strong);
        INK = context.getColor(R.color.muse_text);
        TEXT2 = context.getColor(R.color.muse_text2);
        MUTED = context.getColor(R.color.muse_muted);
        ACCENT = context.getColor(R.color.muse_accent);
        PROGRESS = context.getColor(R.color.muse_progress);
        ON_ACCENT = context.getColor(R.color.muse_on_accent);
        ACCENT_SOFT = context.getColor(R.color.muse_accent_soft);
        SCRIM = context.getColor(R.color.muse_scrim);
        Q0 = context.getColor(R.color.muse_quota_0);
        Q1 = context.getColor(R.color.muse_quota_1);
        Q2 = context.getColor(R.color.muse_quota_2);
        Q3 = context.getColor(R.color.muse_quota_3);
        Q4 = context.getColor(R.color.muse_quota_4);
        if (MONO == null) {
            try {
                MONO = context.getResources().getFont(R.font.jetbrains_mono_regular);
                MONO_MEDIUM = context.getResources().getFont(R.font.jetbrains_mono_medium);
            } catch (RuntimeException unavailable) {
                MONO = Typeface.MONOSPACE;
                MONO_MEDIUM = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            }
        }
        NIGHT = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    static ThemeMode themeMode(Context context) {
        SharedPreferences saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = saved.getString(THEME_MODE_KEY, null);
        Boolean legacy = saved.contains(THEME_KEY) ? saved.getBoolean(THEME_KEY, false) : null;
        ThemeMode mode = ThemeMode.read(stored, legacy);
        if (!mode.stored.equals(stored) || saved.contains(THEME_KEY))
            saved.edit().putString(THEME_MODE_KEY, mode.stored).remove(THEME_KEY).apply();
        return mode;
    }

    static void applyNightMode(Context context) {
        int mode = switch (themeMode(context)) {
            case LIGHT -> AppCompatDelegate.MODE_NIGHT_NO;
            case DARK -> AppCompatDelegate.MODE_NIGHT_YES;
            case SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        };
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode);
    }

    static void setThemeMode(Context context, ThemeMode mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(THEME_MODE_KEY, mode.stored).remove(THEME_KEY).apply();
        applyNightMode(context);
        syncSplashNightMode(context);
    }

    private static final String SPLASH_NIGHT_KEY = "splash-night";

    /**
     * Android 12+ 的系统启动画面在应用代码运行前就画出来，只认系统记住的应用夜间模式。
     * 把暗房 / 白昼同步给系统，下次冷启动的启动画面就和应用主题一致；只在变化时调用一次。
     */
    static void syncSplashNightMode(Context context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int mode = switch (themeMode(context)) {
            case LIGHT -> android.app.UiModeManager.MODE_NIGHT_NO;
            case DARK -> android.app.UiModeManager.MODE_NIGHT_YES;
            case SYSTEM -> android.app.UiModeManager.MODE_NIGHT_AUTO;
        };
        if (preferences.getInt(SPLASH_NIGHT_KEY, -1) == mode) return;
        android.app.UiModeManager modes = context.getSystemService(android.app.UiModeManager.class);
        if (modes == null) return;
        try {
            modes.setApplicationNightMode(mode);
            preferences.edit().putInt(SPLASH_NIGHT_KEY, mode).apply();
        } catch (RuntimeException ignored) {
            // 个别定制系统不支持：启动画面退回跟随系统深浅色，不影响应用本身。
        }
    }

    /** 已用额度对应的颜色：在五个色标之间连续插值；未知或已用完为灰色。 */
    static int quotaColor(Double used) {
        if (used == null || used.isNaN() || used >= 100) return MUTED;
        float value = (float) Math.max(0, Math.min(99.9, used));
        int[] tones = {Q0, Q1, Q2, Q3, Q4};
        if (value >= QUOTA_STOPS[QUOTA_STOPS.length - 1]) return tones[tones.length - 1];
        for (int index = 1; index < QUOTA_STOPS.length; index++) {
            if (value < QUOTA_STOPS[index]) {
                float fraction = (value - QUOTA_STOPS[index - 1]) / (QUOTA_STOPS[index] - QUOTA_STOPS[index - 1]);
                return (int) BLEND.evaluate(fraction, tones[index - 1], tones[index]);
            }
        }
        return tones[0];
    }

    /** 额度状态的文字说明，与色标一一对应。 */
    static String quotaLevel(double used) {
        return used >= 100 ? "已用完" : used >= 95 ? "将尽" : used >= 82 ? "告急" : used >= 65 ? "偏紧" : used >= 45 ? "正常" : "充足";
    }

    static int themeIcon(Context context) {
        return switch (themeMode(context)) {
            case LIGHT -> R.drawable.ic_sun;
            case DARK -> R.drawable.ic_theme;
            case SYSTEM -> R.drawable.ic_theme_system;
        };
    }

    static String themeDescription(Context context) {
        ThemeMode mode = themeMode(context);
        return mode.label + (mode == ThemeMode.SYSTEM ? NIGHT ? "，当前黑夜" : "，当前白天" : "");
    }

    static MaterialAlertDialogBuilder dialog(Context context) {
        MobileUi ui = new MobileUi(context);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.MuseDialog);
        builder.setBackground(ui.shape(SURFACE, R_LG, LINE));
        return builder;
    }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    int ripple() { return NIGHT ? 0x21D1E8D5 : 0x18275A46; }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setBaselineAligned(false);
        return layout;
    }

    void pad(View view, int horizontal, int vertical) {
        view.setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical));
    }

    void block(LinearLayout column, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        column.addView(view, params);
    }

    View spacer() {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return view;
    }

    TextView text(String text, float size, int color, boolean bold) {
        TextView view = new MaterialTextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1f);
        view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        return view;
    }

    /** 数字、套餐与比例：等宽数字，紧凑字重。 */
    TextView figure(String text, float size, int color) {
        TextView view = text(text, size, color, true);
        view.setFontFeatureSettings("tnum");
        view.setLetterSpacing(-0.01f);
        return view;
    }

    /** 元数据：等宽字、略加字距，像底片边缘的字码（画幅、时间、额度、网络）。 */
    TextView meta(String text, float size, int color) {
        TextView view = text(text, size, color, false);
        view.setTypeface(MONO);
        view.setFontFeatureSettings("tnum");
        view.setLetterSpacing(0.04f);
        return view;
    }

    /** 加重的元数据：百分比、协议、角标。 */
    TextView metaStrong(String text, float size, int color) {
        TextView view = meta(text, size, color);
        view.setTypeface(MONO_MEDIUM);
        return view;
    }

    TextView single(TextView view) {
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    GradientDrawable topShape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float r = dp(radius);
        drawable.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return drawable;
    }

    GradientDrawable endShape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float r = dp(radius);
        drawable.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        return drawable;
    }

    LinearLayout toolbar() {
        LinearLayout bar = row();
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setMinimumHeight(dp(56));
        bar.setBackground(shape(SURFACE, 0, LINE));
        return bar;
    }

    /** 会话与网页共用：44dp 紧凑顶栏，与状态栏安全区同色，无外框。 */
    LinearLayout compactToolbar() {
        LinearLayout bar = row();
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setMinimumHeight(dp(44));
        bar.setBackgroundColor(BG);
        return bar;
    }

    Drawable touchable(int color, int radius, int border) {
        return new RippleDrawable(ColorStateList.valueOf(ripple()), shape(color, radius, border), shape(Color.WHITE, radius, 0));
    }

    void touch(View view, int color, int radius, int border, Runnable action) {
        view.setBackground(touchable(color, radius, border));
        view.setOnClickListener(v -> action.run());
        view.setFocusable(true);
        view.setClickable(true);
    }

    /** 通用按钮。color 为底色，border 为描边（0 表示无）。 */
    TextView button(String label, int color, int foreground, int border, Runnable action) {
        TextView button = text(label, 14, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        pad(button, 14, 6);
        touch(button, color, R_MD, border, action);
        return button;
    }

    /** 主按钮：墨色实底。强调色只留给“生成”。 */
    TextView primary(String label, Runnable action) { return button(label, INK, BG, 0, action); }

    TextView outline(String label, Runnable action) { return button(label, Color.TRANSPARENT, INK, LINE_STRONG, action); }

    /** 结果卡上的紧凑按钮：34dp，带图标。 */
    TextView small(String label, int icon, boolean ghost, Runnable action) {
        TextView button = text(label, 13, ghost ? TEXT2 : INK, false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(34));
        button.setMinimumHeight(dp(34));
        button.setPadding(dp(icon == 0 ? 12 : 10), 0, dp(12), 0);
        if (icon != 0) {
            Drawable drawable = context.getDrawable(icon);
            if (drawable != null) {
                drawable = drawable.mutate();
                drawable.setTint(ghost ? TEXT2 : INK);
                drawable.setBounds(0, 0, dp(15), dp(15));
                button.setCompoundDrawablesRelative(drawable, null, null, null);
                button.setCompoundDrawablePadding(dp(6));
            }
        }
        touch(button, ghost ? Color.TRANSPARENT : RAISED, 7, ghost ? 0 : LINE, action);
        return button;
    }

    /** 单行文字输入。 */
    EditText input(String hint, int type, int max) {
        EditText field = new androidx.appcompat.widget.AppCompatEditText(context);
        field.setSingleLine(true);
        field.setInputType(type);
        field.setHint(hint);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setTextSize(15);
        field.setBackground(shape(RAISED, R_MD, 0));
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setMinHeight(dp(48));
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        return field;
    }

    /** 带标签的输入框。 */
    LinearLayout field(EditText input, String label) {
        LinearLayout box = column();
        box.addView(text(label, 12.5f, TEXT2, false));
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, dp(48));
        position.topMargin = dp(7);
        box.addView(input, position);
        return box;
    }

    /** 密码框：右侧可切换显示。 */
    LinearLayout secretField(EditText input, String label) {
        LinearLayout box = column();
        box.addView(text(label, 12.5f, TEXT2, false));
        FrameLayout frame = new FrameLayout(context);
        frame.addView(input, new FrameLayout.LayoutParams(-1, dp(48)));
        input.setPadding(dp(14), 0, dp(48), 0);
        ImageButton eye = iconButton(R.drawable.ic_eye, "显示密码", TEXT2, () -> { });
        eye.setOnClickListener(view -> {
            boolean hidden = (input.getInputType() & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD;
            int selection = input.getSelectionEnd();
            input.setInputType(InputType.TYPE_CLASS_TEXT | (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            input.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            if (selection >= 0) input.setSelection(Math.min(selection, input.length()));
            eye.setImageTintList(ColorStateList.valueOf(hidden ? INK : TEXT2));
            eye.setContentDescription(hidden ? "隐藏密码" : "显示密码");
        });
        frame.addView(eye, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, dp(48));
        position.topMargin = dp(7);
        box.addView(frame, position);
        return box;
    }

    ImageButton iconButton(int icon, String label, int color, Runnable action) {
        return iconButton(icon, label, color, 40, action);
    }

    ImageButton compactIconButton(int icon, String label, int color, Runnable action) {
        return iconButton(icon, label, color, 34, action);
    }

    private ImageButton iconButton(int icon, String label, int color, int size, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageTintList(ColorStateList.valueOf(color));
        button.setImageResource(icon);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple()), null, shape(Color.WHITE, 8, 0)));
        pad(button, 0, 0);
        int pixels = dp(size);
        button.setLayoutParams(new LinearLayout.LayoutParams(pixels, pixels));
        button.setOnClickListener(v -> action.run());
        button.setFocusable(true);
        return button;
    }

    /** 生成键：46dp 强调色方圆。 */
    ImageButton sendButton(Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        int size = dp(46);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        button.setOnClickListener(v -> action.run());
        button.setFocusable(true);
        setSendState(button, false, true);
        return button;
    }

    void setSendState(ImageButton button, boolean busy, boolean ready) {
        String state = busy ? "busy" : ready ? "ready" : "idle";
        if (state.equals(button.getTag())) return;
        button.setTag(state);
        button.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_send);
        button.setImageTintList(ColorStateList.valueOf(busy ? INK : ON_ACCENT));
        int color = busy ? RAISED : ACCENT;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(busy ? ripple() : 0x33FFFFFF), shape(color, 23, 0), null));
        button.setAlpha(busy || ready ? 1f : 0.45f);
    }

    View iconView(int icon, int color, int size) {
        ImageView view = new ImageView(context);
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(color));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return view;
    }

    private static final java.util.ArrayList<String> ACCOUNT_ORDER = new java.util.ArrayList<>();

    /** 头像样式按账号在列表里的顺序轮换（强调色、深色、浅灰、描边），各处保持一致。 */
    static void accountOrder(java.util.List<String> ids) {
        ACCOUNT_ORDER.clear();
        if (ids != null) ACCOUNT_ORDER.addAll(ids);
    }

    /** 头像占位：首字 + 四种样式之一。 */
    TextView avatar(String name, String key, int sizeDp) {
        String initial = name == null || name.isEmpty() ? "M" : name.substring(0, name.offsetByCodePoints(0, 1));
        int position = key == null ? -1 : ACCOUNT_ORDER.indexOf(key);
        int tone = position >= 0 ? position % 4 : Math.abs(key == null ? 0 : key.hashCode()) % 4;
        // 强调色只留给“生成”：头像在墨色、浅灰、灰字、描边四种之间轮换。
        int background = tone == 0 ? INK : tone == 1 ? RAISED : tone == 2 ? TEXT2 : Color.TRANSPARENT;
        int foreground = tone == 0 || tone == 2 ? BG : INK;
        TextView view = figure(initial.toUpperCase(java.util.Locale.ROOT), sizeDp * 0.38f, foreground);
        view.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(background);
        if (tone == 3) circle.setStroke(dp(1.5f), TEXT2);
        view.setBackground(circle);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return view;
    }

    /** 套餐等短标签：描边小框。 */
    TextView tag(String value) {
        TextView tag = figure(value, 10.5f, TEXT2);
        single(tag);
        tag.setBackground(shape(Color.TRANSPARENT, 4, LINE_STRONG));
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        return tag;
    }

    /** 分段控件：图片/视频、会话/账号、代理协议。 */
    final class Segment {
        final LinearLayout view;
        private final TextView[] items;
        private int selected = -1;

        Segment(String[] labels, int[] icons, boolean fill, IntConsumer change) {
            view = row();
            view.setBackground(shape(RAISED, 8, 0));
            pad(view, 3, 3);
            items = new TextView[labels.length];
            for (int index = 0; index < labels.length; index++) {
                int position = index;
                TextView item = text(labels[index], 13, TEXT2, false);
                item.setGravity(Gravity.CENTER);
                boolean iconOnly = labels[index].isEmpty();
                item.setPadding(dp(iconOnly ? 7 : 13), 0, dp(iconOnly ? 7 : 13), 0);
                if (icons != null && icons[index] != 0) {
                    Drawable drawable = context.getDrawable(icons[index]);
                    if (drawable != null) {
                        drawable = drawable.mutate();
                        drawable.setBounds(0, 0, dp(15), dp(15));
                        item.setCompoundDrawablesRelative(drawable, null, null, null);
                        item.setCompoundDrawablePadding(iconOnly ? 0 : dp(6));
                    }
                }
                item.setFocusable(true);
                item.setClickable(true);
                item.setOnClickListener(v -> { if (position != selected) change.accept(position); });
                LinearLayout.LayoutParams params = fill ? new LinearLayout.LayoutParams(0, dp(34), 1f)
                    : new LinearLayout.LayoutParams(-2, dp(30));
                view.addView(item, params);
                items[index] = item;
            }
        }

        void select(int index) {
            selected = index;
            for (int i = 0; i < items.length; i++) {
                boolean on = i == index;
                TextView item = items[i];
                item.setTextColor(on ? INK : TEXT2);
                item.setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
                item.setBackground(on ? shape(BG, 6, 0) : null);
                item.setSelected(on);
                Drawable[] drawables = item.getCompoundDrawablesRelative();
                if (drawables[0] != null) drawables[0].setTint(on ? INK : TEXT2);
            }
        }

        void setLabel(int index, String label) { items[index].setText(label); }

        /** 只有图标的分段：给读屏和长按提示用的说明。 */
        void describe(int index, String label) {
            items[index].setContentDescription(label);
            items[index].setTooltipText(label);
        }
    }

    Segment segment(String[] labels, int[] icons, boolean fill, int selected, IntConsumer change) {
        Segment segment = new Segment(labels, icons, fill, change);
        segment.select(selected);
        return segment;
    }

    /** 输入框下方的参数按钮：账号、画幅、时长。 */
    LinearLayout chip(View lead, String value, String label, Runnable action) {
        return chip(lead, value, label, true, action);
    }

    /** 参数按钮；chevron 为 false 时不画下拉箭头，省出底栏宽度。 */
    LinearLayout chip(View lead, String value, String label, boolean chevron, Runnable action) {
        LinearLayout chip = row();
        chip.setPadding(dp(9), 0, dp(8), 0);
        chip.setContentDescription(label);
        chip.setTooltipText(label);
        if (lead != null) {
            LinearLayout.LayoutParams leadPosition = lead.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) lead.getLayoutParams() : new LinearLayout.LayoutParams(-2, -2);
            leadPosition.setMarginEnd(dp(6));
            chip.addView(lead, leadPosition);
        }
        TextView text = single(text(value, 13, INK, false));
        text.setMaxWidth(dp(86));
        chip.addView(text, new LinearLayout.LayoutParams(-2, -2));
        if (chevron) {
            View arrow = iconView(R.drawable.ic_chevron_down, TEXT2, 13);
            LinearLayout.LayoutParams chevronPosition = new LinearLayout.LayoutParams(dp(13), dp(13));
            chevronPosition.setMarginStart(dp(5));
            chip.addView(arrow, chevronPosition);
        } else chip.setPadding(dp(9), 0, dp(9), 0);
        touch(chip, RAISED, R_SM, 0, action);
        chip.setTag(text);
        return chip;
    }

    /** 画幅示意框：按比例画出的描边矩形，“自动”为虚线。 */
    View ratioBox(String ratio, int boxDp, int color) {
        float[] value = ratioValue(ratio);
        float scale = boxDp / Math.max(value[0], value[1]);
        View box = new View(context);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(dp(boxDp > 20 ? 3 : 2));
        if ("Auto".equals(ratio)) outline.setStroke(dp(1.5f), color, dp(3), dp(2));
        else outline.setStroke(dp(1.5f), color);
        box.setBackground(outline);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(Math.max(4, value[0] * scale)), dp(Math.max(4, value[1] * scale))));
        return box;
    }

    static float[] ratioValue(String ratio) {
        if (ratio == null || "Auto".equals(ratio)) return new float[]{4, 5};
        String[] parts = ratio.split(":");
        try { return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1])}; }
        catch (RuntimeException ignored) { return new float[]{4, 5}; }
    }

    /** 单选项：左侧图标或标签，中间标题和说明，右侧圆形选中标记。 */
    LinearLayout option(View lead, String title, String subtitle, boolean selected, boolean enabled, Runnable action) {
        LinearLayout row = row();
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setMinimumHeight(dp(56));
        if (lead != null) {
            LinearLayout.LayoutParams leadPosition = lead.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) lead.getLayoutParams() : new LinearLayout.LayoutParams(-2, -2);
            leadPosition.setMarginEnd(dp(12));
            row.addView(lead, leadPosition);
        }
        LinearLayout words = column();
        words.addView(single(text(title, 14.5f, INK, true)));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = single(text(subtitle, 12, TEXT2, false));
            LinearLayout.LayoutParams subPosition = new LinearLayout.LayoutParams(-1, -2);
            subPosition.topMargin = dp(4);
            words.addView(sub, subPosition);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        View mark = new View(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(selected ? 6 : 1.5f), selected ? INK : LINE_STRONG);
        mark.setBackground(ring);
        LinearLayout.LayoutParams markPosition = new LinearLayout.LayoutParams(dp(20), dp(20));
        markPosition.setMarginStart(dp(10));
        row.addView(mark, markPosition);
        row.setSelected(selected);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.42f);
        if (enabled) touch(row, selected ? RAISED : Color.TRANSPARENT, R_MD, selected ? LINE_STRONG : LINE, action);
        else row.setBackground(shape(Color.TRANSPARENT, R_MD, LINE));
        return row;
    }

    /** 提示块：普通 / 通过 / 警告。 */
    TextView note(String message, int kind) {
        TextView note = text(message, 13, kind == 0 ? TEXT2 : INK, false);
        note.setBackground(shape(kind == 2 ? ACCENT_SOFT : RAISED, R_MD, 0));
        note.setPadding(dp(12), dp(11), dp(12), dp(11));
        return note;
    }

    /** 底部弹窗；未保存退出保护仅由需要它的表单显式启用。 */
    final class Sheet {
        final BottomSheetDialog dialog;
        final LinearLayout body, actions;
        private final Activity owner;
        private java.util.function.BooleanSupplier dirty;
        private boolean leaving;
        private androidx.appcompat.app.AlertDialog discard;

        Sheet(Activity activity, String title, String subtitle) {
            owner = activity;
            dialog = new BottomSheetDialog(activity, R.style.MuseSheet) {
                @Override public void cancel() {
                    if (!leaving && dirty != null && dirty.getAsBoolean()) {
                        getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
                        requestLeave(null, true);
                    } else super.cancel();
                }
            };
            LinearLayout panel = column();
            panel.setBackground(topShape(SURFACE, R_LG));
            panel.setPadding(dp(18), 0, dp(18), dp(22));
            FrameLayout grab = new FrameLayout(activity);
            View handle = new View(activity);
            handle.setBackground(shape(LINE_STRONG, 2, 0));
            grab.addView(handle, new FrameLayout.LayoutParams(dp(38), dp(4), Gravity.CENTER));
            panel.addView(grab, new LinearLayout.LayoutParams(-1, dp(22)));
            TextView heading = text(title, 18, INK, true);
            heading.setPadding(dp(2), dp(4), dp(2), 0);
            panel.addView(heading);
            if (subtitle != null && !subtitle.isEmpty()) {
                TextView sub = text(subtitle, 13, TEXT2, false);
                sub.setPadding(dp(2), dp(8), dp(2), 0);
                panel.addView(sub);
            }
            ScrollView scroll = new ScrollView(activity) {
                @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int available = getResources().getDisplayMetrics().heightPixels;
                    View parent = (View) getParent();
                    if (parent != null && parent.getRootView().getHeight() > 0) available = Math.min(available, parent.getRootView().getHeight());
                    int limit = Math.round(available * .62f);
                    if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) limit = Math.min(limit, MeasureSpec.getSize(heightMeasureSpec));
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
                }
            };
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            body = column();
            body.setPadding(0, dp(16), 0, dp(2));
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            actions = row();
            actions.setVisibility(View.GONE);
            LinearLayout.LayoutParams actionPosition = new LinearLayout.LayoutParams(-1, -2);
            actionPosition.topMargin = dp(18);
            panel.addView(actions, actionPosition);
            dialog.setContentView(panel);
            Window window = dialog.getWindow();
            if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.setOnShowListener(shown -> {
                View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
                dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
                dialog.getBehavior().setSkipCollapsed(true);
            });
        }

        void add(View view, int top) { block(body, view, top); }

        void action(View view, boolean weight) {
            actions.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams params = weight ? new LinearLayout.LayoutParams(0, dp(48), 1f)
                : new LinearLayout.LayoutParams(-2, dp(48));
            if (actions.getChildCount() > 0) params.setMarginStart(dp(8));
            actions.addView(view, params);
        }

        void show() { dialog.show(); }
        void dismiss() { requestLeave(null, false); }
        void leave(Runnable after) { requestLeave(after, false); }
        void guardDismiss(java.util.function.BooleanSupplier dirty) { this.dirty = dirty; }
        void dismissSaved() { closeNow(null, false); }

        private void requestLeave(Runnable after, boolean cancel) {
            if (discard != null) return;
            if (owner.isFinishing() || owner.isDestroyed()) { closeNow(null, cancel); return; }
            if (dirty == null || !dirty.getAsBoolean()) { closeNow(after, cancel); return; }
            discard = MobileUi.dialog(owner)
                .setTitle("放弃本次修改？")
                .setMessage("账号设置还没有保存。你可以继续编辑，或放弃这次修改。")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃修改", (choice, which) -> closeNow(after, cancel)).show();
            discard.setOnDismissListener(ignored -> discard = null);
        }

        private void closeNow(Runnable after, boolean cancel) {
            leaving = true;
            if (discard != null) discard.dismiss();
            if (cancel) dialog.cancel(); else dialog.dismiss();
            if (after != null) after.run();
        }
    }

    Sheet sheet(Activity activity, String title, String subtitle) { return new Sheet(activity, title, subtitle); }

    /** 周额度刻度条：20 格，已用格按额度色阶着色；不限量时整条为浅色。 */
    View tickBar() { return new TickBar(context); }

    static void setTicks(View bar, Double used, boolean known) {
        if (bar instanceof TickBar ticks) ticks.set(used, known);
    }

    static void tintTicks(View bar, int progress, int empty, int hot) {
        if (bar instanceof TickBar ticks) { ticks.tinted=true; ticks.progressColor=progress; ticks.emptyColor=empty; ticks.unknownColor=empty; ticks.hotColor=hot; ticks.invalidate(); }
    }

    private final class TickBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Double used;
        private boolean known, tinted;
        private int progressColor = PROGRESS, emptyColor = LINE_STRONG, hotColor = ACCENT, unknownColor = LINE;

        TickBar(Context context) { super(context); }

        void set(Double value, boolean measured) {
            used = value;
            known = measured;
            if (!tinted) progressColor = hotColor = quotaColor(value);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int count = 20;
            float gap = dp(2), width = (getWidth() - gap * (count - 1)) / count, radius = dp(1.5f);
            int filled = used == null ? 0 : (int) Math.round(Math.max(0, Math.min(100, used)) / 5d);
            boolean hot = used != null && used >= 80;
            for (int index = 0; index < count; index++) {
                float left = index * (width + gap);
                if (!known) paint.setColor(unknownColor);
                else if (used == null) paint.setColor(emptyColor);
                else paint.setColor(index < filled ? (hot ? hotColor : progressColor) : emptyColor);
                canvas.drawRoundRect(left, 0, left + width, getHeight(), radius, radius, paint);
            }
        }
    }
}
