package com.muse.workspace.mobile;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;
import static com.muse.workspace.mobile.MobileUi.*;

/** 账号卡上的额度区：周额度刻度条、重置日期与邀请额度。 */
final class QuotaCard {
    private final TextView week, reset, extraLabel, inviteLabel, note;
    private final View ticks;
    private final Context context;
    private final Consumer<String> planChanged;
    private final AccountAppearance.Skin skin;
    final LinearLayout view, details;

    QuotaCard(Context context, MobileUi ui, Consumer<String> planChanged, Runnable toggleDetails, AccountAppearance.Skin skin) {
        this.planChanged = planChanged;
        this.context = context;
        this.skin = skin;
        view = ui.column();
        details = ui.column();

        TextView title = ui.text("周额度", 13, skin.ink, true);
        reset = ui.text("", 12, skin.copy, false);
        reset.setVisibility(View.GONE);
        reset.setOnClickListener(ignored -> toggleDetails.run());
        reset.setFocusable(false);
        reset.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        week = new AccountCardValue(context, true);
        week.setText("待同步");
        skin.press(week);
        week.setGravity(Gravity.END);
        week.setOnClickListener(ignored -> toggleDetails.run());
        week.setFocusable(false);
        week.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ui.block(view, new AccountQuotaLine(context, ui, title, reset, week), 0);

        ticks = ui.tickBar();
        MobileUi.tintTicks(ticks, skin.foil, AccountAppearance.alpha(skin.line, 84), skin.foil);
        LinearLayout.LayoutParams track = new LinearLayout.LayoutParams(-1, ui.dp(5));
        track.topMargin = ui.dp(4);
        view.addView(ticks, track);

        View rule = new View(context);
        rule.setBackgroundColor(skin.hair);
        details.addView(rule, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        LinearLayout invitation = ui.row();
        invitation.setGravity(Gravity.TOP);
        extraLabel = label(ui, "—/—");
        LinearLayout.LayoutParams extraPosition = new LinearLayout.LayoutParams(0, -2, 1);
        extraPosition.setMarginEnd(ui.dp(8));
        invitation.addView(statColumn(ui, "额外额度 · 已用 / 总量", extraLabel), extraPosition);
        View divider = new View(context);
        divider.setBackgroundColor(skin.hair);
        invitation.addView(divider, new LinearLayout.LayoutParams(ui.dp(1), -1));
        inviteLabel = label(ui, "待获取 —/30");
        inviteLabel.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        LinearLayout inviteColumn = statColumn(ui, "邀请码 · 已邀请 / 总数", inviteLabel);
        LinearLayout.LayoutParams invitePosition = new LinearLayout.LayoutParams(0, -2, 1.12f);
        invitePosition.setMarginStart(ui.dp(8));
        invitation.addView(inviteColumn, invitePosition);
        note = ui.text("待同步", 12, skin.copy, false);
        note.setMinHeight(ui.dp(48));
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(skin.ripple), null, ui.shape(android.graphics.Color.WHITE, 4, 0)));
        LinearLayout footer = ui.row();
        footer.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout mark = ui.row();
        mark.addView(ui.iconView(R.drawable.ic_muse_mark, skin.copy, 12), new LinearLayout.LayoutParams(ui.dp(12), ui.dp(8)));
        TextView wordmark = ui.text("MUSE", 11, skin.copy, true);
        LinearLayout.LayoutParams wordPosition = new LinearLayout.LayoutParams(-2, -2);
        wordPosition.setMarginStart(ui.dp(3));
        mark.addView(wordmark, wordPosition);
        LinearLayout.LayoutParams markPosition = new LinearLayout.LayoutParams(-2, -2);
        markPosition.setMarginStart(ui.dp(5));
        footer.addView(mark, markPosition);
        ui.block(details, invitation, 8);
        ui.block(details, footer, 9);
    }

    String weeklyText() { return week.getText().toString(); }

    void update(AccountCardState card, String state) {
        long now = System.currentTimeMillis();
        AccountQuota data = card.quota(now);
        String plan = card.plan().isEmpty() ? "待同步" : card.plan();
        planChanged.accept(plan);
        boolean current = data != null && data.currentWeek(now) && "额度已同步".equals(state);
        String weekly = data == null ? state : data.weekly(now);
        if (data != null && weekly.startsWith("上次 · ")) weekly = weekly.substring("上次 · ".length());
        Double used = data == null ? null : data.used();
        boolean exhausted = used != null && used >= 100;
        week.setText(current && exhausted ? "已用完" : weekly);
        week.setTextSize(data == null ? 12 : 20);
        week.setTooltipText(week.getText());
        week.setTextColor(used != null && used >= 80 ? skin.foil : skin.ink);
        MobileUi.setTicks(ticks, used, data != null);
        ticks.setAlpha(current ? 1f : 0.5f);
        String day = data == null ? "" : data.resetLabel(now);
        reset.setText(day);
        reset.setTooltipText(day);
        reset.setVisibility(day.isEmpty() ? View.GONE : View.VISIBLE);
        long usageAt = data == null ? 0 : data.observed();
        String extraText = AccountCardLabels.extra(card), inviteText = AccountCardLabels.invitation(card);
        String extraDetails = AccountCardLabels.extraDetails(card), inviteDetails = AccountCardLabels.inviteDetails(card);
        extraLabel.setText(extraText.startsWith("额外 ") ? extraText.substring("额外 ".length()) : extraText);
        extraLabel.setContentDescription(extraText + "，点按查看精确额度和成功时间");
        extraLabel.setTooltipText(extraDetails);
        extraLabel.setOnClickListener(ignored -> showLabelDetails("额外额度", extraDetails));
        inviteLabel.setText(inviteText);
        String code = card.inviteCode();
        inviteLabel.setContentDescription(inviteText + (code.isEmpty() ? card.inviteExhausted() ? "，暂无可复制的邀请码" : "，邀请码待获取" : "，点按复制完整邀请码") + "，长按查看剩余次数和成功时间");
        inviteLabel.setTooltipText(inviteDetails);
        inviteLabel.setOnClickListener(ignored -> {
            if (code.isEmpty() && card.inviteExhausted()) showLabelDetails("邀请已满", inviteDetails);
            else copyInviteCode(code);
        });
        inviteLabel.setOnLongClickListener(ignored -> { showLabelDetails("邀请信息", inviteDetails); return true; });
        long at = data == null ? card.lastSuccess() : data.observed();
        String when = at <= 0 ? "" : " · 上次 " + new SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(new Date(at));
        String sync = current ? "额度已同步" : state.startsWith("未更新") || state.contains("同步中") || state.contains("登录") || state.startsWith("部分") ? state : "未更新";
        note.setText(sync + when + (AccountCardLabels.cached(card, now, usageAt) ? " · 标签含缓存" : ""));
        // 折叠摘要不可提前读出隐藏的邀请、额外额度和同步明细。
        view.setContentDescription("周额度 " + week.getText() + (day.isEmpty() ? "" : "，" + day));
    }

    private TextView label(MobileUi ui, String text) {
        TextView label = new AccountCardValue(context, false);
        label.setText(text);
        skin.press(label);
        label.setMinWidth(0);
        label.setMinimumHeight(ui.dp(32));
        label.setPadding(0, 0, 0, 0);
        label.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(skin.ripple), null, ui.shape(android.graphics.Color.WHITE, 4, 0)));
        label.setOnClickListener(ignored -> { });
        label.setFocusable(true);
        return label;
    }

    private LinearLayout statColumn(MobileUi ui, String title, TextView value) {
        LinearLayout column = ui.column();
        column.addView(ui.text(title, 12, skin.copy, false), new LinearLayout.LayoutParams(-1, -2));
        ui.block(column, value, 3);
        column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.setOnClickListener(ignored -> value.performClick());
        column.setOnLongClickListener(ignored -> value.performLongClick());
        return column;
    }

    void setRefreshAction(String accountName, Runnable refresh) {
        note.setOnClickListener(ignored -> refresh.run());
        note.setFocusable(true);
        note.setPaintFlags(note.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        Consumer<CharSequence> describe = text -> {
            String explanation = text + "，点按刷新「" + accountName + "」的额度";
            note.setContentDescription(explanation);
            note.setTooltipText(explanation);
        };
        describe.accept(note.getText());
        note.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { describe.accept(text); }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
    }

    private void showLabelDetails(String title, String text) {
        MobileUi.dialog(context).setTitle(title).setMessage(text).setPositiveButton("关闭", null).show();
    }

    private void copyInviteCode(String code) {
        if (code == null || code.isEmpty()) {
            Toast.makeText(context, "邀请码待获取", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                Toast.makeText(context, "无法使用剪贴板", Toast.LENGTH_SHORT).show();
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Muse 邀请码", code));
        } catch (RuntimeException unavailable) {
            Toast.makeText(context, "无法复制邀请码", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, "已复制邀请码", Toast.LENGTH_SHORT).show();
    }
}
