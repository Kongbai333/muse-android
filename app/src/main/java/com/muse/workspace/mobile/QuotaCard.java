package com.muse.workspace.mobile;

import android.content.Context;
import android.text.TextUtils;
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
    private final TextView week, reset, invite, code, uses, details, note;
    private final View ticks;
    private final Consumer<String> planChanged;
    final LinearLayout view;

    QuotaCard(Context context, MobileUi ui, Consumer<String> planChanged) {
        this.planChanged = planChanged;
        view = ui.column();

        LinearLayout top = ui.row();
        LinearLayout heading = ui.row();
        heading.setBaselineAligned(true);
        heading.addView(ui.text("周额度", 12, TEXT2, false));
        reset = ui.single(ui.text("", 11, TEXT2, false));
        reset.setMinWidth(0);
        reset.setVisibility(View.GONE);
        LinearLayout.LayoutParams resetPosition = new LinearLayout.LayoutParams(0, -2, 1);
        resetPosition.setMarginStart(ui.dp(6));
        heading.addView(reset, resetPosition);
        LinearLayout.LayoutParams headingPosition = new LinearLayout.LayoutParams(0, -2, 1);
        headingPosition.setMarginEnd(ui.dp(8));
        top.addView(heading, headingPosition);
        week = ui.single(ui.figure("待同步", 12, INK));
        week.setGravity(Gravity.END);
        week.setMaxWidth(ui.dp(88));
        top.addView(week, new LinearLayout.LayoutParams(-2, -2));
        ui.block(view, top, 0);

        ticks = ui.tickBar();
        LinearLayout.LayoutParams track = new LinearLayout.LayoutParams(-1, ui.dp(10));
        track.topMargin = ui.dp(7);
        view.addView(ticks, track);

        LinearLayout invitation = ui.row();
        invitation.setBaselineAligned(true);
        invite = ui.single(ui.text("额外 未知", 12, TEXT2, false));
        invite.setMinWidth(0);
        LinearLayout.LayoutParams extraPosition = new LinearLayout.LayoutParams(0, -2, 0.34f);
        extraPosition.setMarginEnd(ui.dp(6));
        invitation.addView(invite, extraPosition);
        code = ui.single(ui.text("邀请码 未知", 12, TEXT2, false));
        code.setMinWidth(0);
        LinearLayout.LayoutParams codePosition = new LinearLayout.LayoutParams(0, -2, 0.46f);
        codePosition.setMarginEnd(ui.dp(6));
        invitation.addView(code, codePosition);
        uses = ui.single(ui.text("余 —", 12, TEXT2, false));
        uses.setMinWidth(0);
        uses.setGravity(Gravity.END);
        invitation.addView(uses, new LinearLayout.LayoutParams(0, -2, 0.20f));
        details = ui.text("", 11, TEXT2, false);
        details.setMaxLines(2);
        details.setEllipsize(TextUtils.TruncateAt.END);
        details.setVisibility(View.GONE);
        note = ui.text("待同步", 11, MUTED, false);
        ui.block(view, invitation, 7);
        ui.block(view, details, 3);
        ui.block(view, note, 5);
    }

    void update(AccountCardState card, String state) {
        long now = System.currentTimeMillis();
        AccountQuota data = card.quota(now);
        String plan = card.plan().isEmpty() ? "待同步" : card.plan();
        planChanged.accept(plan);
        boolean current = data != null && data.currentWeek(now) && "额度已同步".equals(state);
        String weekly = data == null ? state : !current && data.currentWeek(now) ? "上次 · " + data.weekly(now) : data.weekly(now);
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
        long extraAt = Math.max(card.observed("extra_remaining"), card.observed("extra_display"));
        long usageAt = data == null ? 0 : data.observed();
        boolean oldExtra = old(extraAt, now) || extraAt < usageAt;
        String fullExtra = card.extraText().isEmpty() ? "额外额度，未知" : "额外额度，" + (oldExtra ? "上次，" : "") + card.extraText();
        String extraValue = card.extraText().startsWith("剩余 ") ? card.extraText().substring(3) : card.extraText();
        invite.setText(extraValue.isEmpty() ? "额外 未知" : (oldExtra ? "旧额外 " : "额外 ") + extraValue.replace(" / ", "/"));
        invite.setContentDescription(fullExtra);
        invite.setTooltipText(fullExtra);
        boolean oldCode = old(card.observed("invite_code"), now) || card.observed("invite_code") < usageAt;
        boolean oldUses = old(card.observed("invite_remaining"), now) || card.observed("invite_remaining") < usageAt;
        String fullCode = card.inviteCode().isEmpty() ? "邀请码，未知" : (oldCode ? "上次邀请码，" : "邀请码，") + card.inviteCode();
        code.setText(card.inviteCode().isEmpty() ? "邀请码 未知" : (oldCode ? "旧码 " : "邀请码 ") + card.inviteCode());
        code.setContentDescription(fullCode);
        code.setTooltipText(fullCode);
        String count = card.inviteRemaining() == null ? "余 —" : (oldUses ? "旧" : "余") + card.inviteRemaining() + "次";
        String fullUses = card.inviteRemaining() == null ? "剩余次数，未知" : (oldUses ? "上次，" : "") + "还可使用 " + card.inviteRemaining() + " 次";
        uses.setText(count);
        uses.setContentDescription(fullUses);
        uses.setTooltipText(fullUses);
        String other = card.inviteRedeemed() == null ? "" : (old(card.observed("invite_redeemed"), now) || card.observed("invite_redeemed") < usageAt ? "上次 " : "") + card.inviteRedeemed() + " 位好友已兑现";
        if (!card.inviteEarned().isEmpty()) other += (other.isEmpty() ? "" : " · ")
            + (old(card.observed("invite_earned"), now) || card.observed("invite_earned") < usageAt ? "上次 " : "") + "已赚取 " + card.inviteEarned();
        details.setText(other);
        details.setContentDescription(other);
        details.setTooltipText(other);
        details.setVisibility(other.isEmpty() ? View.GONE : View.VISIBLE);
        long at = data == null ? card.lastSuccess() : data.observed();
        String when = at <= 0 ? "" : " · 上次 " + new SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(new Date(at));
        note.setText(current ? "额度已同步" + when : (state.startsWith("未更新") || state.contains("同步中") || state.contains("登录") || state.startsWith("部分") ? state : "未更新") + when);
        view.setContentDescription("周额度 " + week.getText() + (day.isEmpty() ? "" : "，" + day)
            + "，" + fullExtra + "，" + fullCode + "，" + fullUses + (other.isEmpty() ? "" : "，" + other) + "，" + note.getText());
    }

    private static boolean old(long observed, long now) { return observed <= 0 || now - observed >= 300_000; }
}
