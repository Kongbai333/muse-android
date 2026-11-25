package com.muse.workspace.mobile;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.TextUtils;
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
    final LinearLayout view, details;

    QuotaCard(Context context, MobileUi ui, Consumer<String> planChanged, Runnable toggleDetails) {
        this.planChanged = planChanged;
        this.context = context;
        view = ui.column();
        details = ui.column();

        LinearLayout top = ui.row();
        LinearLayout heading = ui.row();
        heading.setBaselineAligned(true);
        heading.addView(ui.text("周额度", 12, TEXT2, false));
        reset = ui.text("", 11, TEXT2, false);
        reset.setVisibility(View.GONE);
        reset.setOnClickListener(ignored -> toggleDetails.run());
        reset.setFocusable(false);
        reset.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams resetPosition = new LinearLayout.LayoutParams(0, -2, 1);
        resetPosition.setMarginStart(ui.dp(6));
        heading.addView(reset, resetPosition);
        // 日期紧跟周额度标签，同组内允许换行；数值另占末端空间，不互相压叠。
        LinearLayout.LayoutParams headingPosition = new LinearLayout.LayoutParams(0, -2, 2);
        headingPosition.setMarginEnd(ui.dp(8));
        top.addView(heading, headingPosition);
        week = ui.figure("待同步", 12, INK);
        week.setGravity(Gravity.END);
        week.setOnClickListener(ignored -> toggleDetails.run());
        week.setFocusable(false);
        week.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(week, new LinearLayout.LayoutParams(0, -2, 1));
        ui.block(view, top, 0);

        ticks = ui.tickBar();
        LinearLayout.LayoutParams track = new LinearLayout.LayoutParams(-1, ui.dp(10));
        track.topMargin = ui.dp(7);
        view.addView(ticks, track);

        LinearLayout invitation = ui.row();
        invitation.setBaselineAligned(true);
        extraLabel = label(ui, "额外 —/—");
        LinearLayout.LayoutParams extraPosition = new LinearLayout.LayoutParams(0, -2, 1);
        extraPosition.setMarginEnd(ui.dp(8));
        invitation.addView(extraLabel, extraPosition);
        inviteLabel = label(ui, "待获取 —/30");
        inviteLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        invitation.addView(inviteLabel, new LinearLayout.LayoutParams(0, -2, 1.4f));
        note = ui.text("待同步", 11, MUTED, false);
        ui.block(details, invitation, 7);
        ui.block(details, note, 5);
    }

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
        week.setTooltipText(week.getText());
        week.setTextColor(used != null && used >= 80 ? ACCENT : INK);
        MobileUi.setTicks(ticks, used, data != null);
        ticks.setAlpha(current ? 1f : 0.5f);
        String day = data == null ? "" : data.resetLabel(now);
        reset.setText(day);
        reset.setTooltipText(day);
        reset.setVisibility(day.isEmpty() ? View.GONE : View.VISIBLE);
        long usageAt = data == null ? 0 : data.observed();
        String extraText = AccountCardLabels.extra(card), inviteText = AccountCardLabels.invitation(card);
        String extraDetails = AccountCardLabels.extraDetails(card), inviteDetails = AccountCardLabels.inviteDetails(card);
        extraLabel.setText(extraText);
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
        TextView label = ui.single(ui.text(text, 12, TEXT2, false));
        label.setMinWidth(0);
        label.setMinimumHeight(ui.dp(32));
        label.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        ui.touch(label, RAISED, 10, LINE, () -> { });
        return label;
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
