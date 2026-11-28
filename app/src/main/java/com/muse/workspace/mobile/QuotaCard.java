package com.muse.workspace.mobile;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;
import static com.muse.workspace.mobile.MobileUi.*;

/** 账号卡上的额度区：周额度刻度条、重置日期与邀请额度。 */
final class QuotaCard {
    private final TextView week, reset, extraLabel, inviteLabel;
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
        view.setOnClickListener(ignored -> toggleDetails.run());
        view.setFocusable(false);
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
        MobileUi.tintTicks(ticks, skin.foil, AccountAppearance.alpha(skin.line, 84), skin.alert);
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
        LinearLayout inviteColumn = statColumn(ui, "邀请码 · 已邀请 / 总数", inviteLabel);
        LinearLayout.LayoutParams invitePosition = new LinearLayout.LayoutParams(0, -2, 1.12f);
        invitePosition.setMarginStart(ui.dp(8));
        invitation.addView(inviteColumn, invitePosition);
        ui.block(details, invitation, 8);
    }

    String weeklyText() { return week.getText().toString(); }

    void update(AccountCardState card, String state) {
        long now = System.currentTimeMillis();
        AccountQuota data = card.quota(now);
        String plan = card.plan().isEmpty() ? "待同步" : card.plan();
        planChanged.accept(plan);
        boolean current = data != null && data.currentWeek(now) && "额度已同步".equals(state);
        String weekly = data == null ? ("额度已同步".equals(state) ? "待获取" : state) : data.weekly(now);
        if (data != null && weekly.startsWith("上次 · ")) weekly = weekly.substring("上次 · ".length());
        Double used = data == null ? null : data.used();
        boolean exhausted = used != null && used >= 100;
        week.setText(current && exhausted ? "已用完" : weekly);
        AccountCardTypography.weekly(week, data != null);
        week.setTextColor(used != null && used >= 80 ? skin.alert : skin.ink);
        MobileUi.setTicks(ticks, used, data != null);
        ticks.setAlpha(current ? 1f : 0.5f);
        String day = data == null ? "" : data.resetLabel(now);
        String status = state.contains("同步中") ? "同步中…" : state.contains("登录") ? "请先登录"
            : state.startsWith("未更新") ? "未更新" : state.startsWith("部分") ? "部分更新" : "";
        String caption = status.isEmpty() || data == null ? day : status;
        reset.setText(caption);
        reset.setTooltipText(state + (day.isEmpty() ? "" : "，" + day));
        reset.setVisibility(caption.isEmpty() ? View.GONE : View.VISIBLE);
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
            else copyInviteCode(context, code);
        });
        inviteLabel.setOnLongClickListener(ignored -> { showLabelDetails("邀请信息", inviteDetails); return true; });
        long at = data == null ? 0 : data.observed();
        String when = at <= 0 ? "成功时间：未知" : "成功时间：" + new SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(new Date(at));
        String weeklyDetails = "周额度：" + weekly + "\n" + when + "\n当前状态：" + state
            + (day.isEmpty() ? "" : "\n" + day) + (!current && data != null ? "\n显示上次成功数据" : "")
            + (AccountCardLabels.cached(card, now, usageAt) ? "\n额外额度或邀请信息含独立缓存；各字段成功时间可在详情中查看。" : "");
        week.setTooltipText(weeklyDetails);
        week.setOnLongClickListener(ignored -> { showLabelDetails("周额度", weeklyDetails); return true; });
        view.setOnLongClickListener(ignored -> { showLabelDetails("周额度", weeklyDetails); return true; });
        // 折叠摘要不可提前读出隐藏的邀请、额外额度和同步明细。
        view.setContentDescription("周额度 " + week.getText() + (day.isEmpty() ? "" : "，" + day)
            + (status.isEmpty() ? "" : "，" + state) + (!current && data != null ? "，显示上次成功数据" : ""));
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

    private void showLabelDetails(String title, String text) {
        MobileUi.dialog(context).setTitle(title).setMessage(text).setPositiveButton("关闭", null).show();
    }

    static void copyInviteCode(Context context, String code) {
        if (code == null || code.isEmpty()) {
            MobileUi.notice(context, "邀请码待获取");
            return;
        }
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                MobileUi.notice(context, "无法使用剪贴板");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Muse 邀请码", code));
        } catch (RuntimeException unavailable) {
            MobileUi.notice(context, "无法复制邀请码");
            return;
        }
        MobileUi.notice(context, "已复制邀请码");
    }
}
