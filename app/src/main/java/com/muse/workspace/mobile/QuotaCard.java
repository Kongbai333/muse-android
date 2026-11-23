package com.muse.workspace.mobile;

import android.content.Context;
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
    private final TextView week, reset, invite, code, uses, note;
    private final View ticks;
    private final Consumer<String> planChanged;
    final LinearLayout view;

    QuotaCard(Context context, MobileUi ui, Consumer<String> planChanged) {
        this.planChanged = planChanged;
        view = ui.column();

        LinearLayout top = ui.row();
        top.addView(ui.text("周额度", 12, TEXT2, false), new LinearLayout.LayoutParams(0, -2, 1));
        week = ui.single(ui.figure("待同步", 12, INK));
        week.setGravity(Gravity.END);
        top.addView(week, new LinearLayout.LayoutParams(-2, -2));
        ui.block(view, top, 0);

        ticks = ui.tickBar();
        LinearLayout.LayoutParams track = new LinearLayout.LayoutParams(-1, ui.dp(10));
        track.topMargin = ui.dp(7);
        view.addView(ticks, track);

        LinearLayout bottom = ui.row();
        reset = ui.single(ui.text("", 12, TEXT2, false));
        bottom.addView(reset, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(ui.text("额外额度 ", 12, TEXT2, false), new LinearLayout.LayoutParams(-2, -2));
        invite = ui.single(ui.figure("待同步", 12, INK));
        invite.setMaxWidth(ui.dp(140));
        bottom.addView(invite, new LinearLayout.LayoutParams(-2, -2));
        ui.block(view, bottom, 7);
        code = ui.text("邀请码 · 待页面提供", 12, TEXT2, false);
        uses = ui.text("次数 · 待页面提供", 12, TEXT2, false);
        note = ui.text("待同步", 11, MUTED, false);
        ui.block(view, code, 7);
        ui.block(view, uses, 3);
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
        week.setTextColor(used != null && used >= 80 ? ACCENT : INK);
        MobileUi.setTicks(ticks, used, data != null);
        ticks.setAlpha(current ? 1f : 0.5f);
        String day = data == null ? "" : data.resetLabel(now);
        reset.setText(!day.isEmpty() ? day : current && used == null ? "无需重置" : "");
        long extraAt = Math.max(card.observed("extra_remaining"), card.observed("extra_display"));
        invite.setText(card.extraText().isEmpty() ? "待同步" : (old(extraAt, now) ? "上次 " : "") + card.extraText());
        code.setText(card.inviteCode().isEmpty() ? "邀请码 · 待页面提供" : (old(card.observed("invite_code"), now) ? "上次邀请码 · " : "邀请码 · ") + card.inviteCode());
        String count = card.inviteRemaining() == null ? "次数 · 待页面提供" : (old(card.observed("invite_remaining"), now) ? "上次 " : "") + "还可使用 " + card.inviteRemaining() + " 次";
        if (card.inviteRedeemed() != null) count += " · " + card.inviteRedeemed() + " 位好友已兑现";
        if (!card.inviteEarned().isEmpty()) count += " · 已赚取 " + card.inviteEarned();
        uses.setText(count);
        long at = data == null ? card.lastSuccess() : data.observed();
        String when = at <= 0 ? "" : " · 上次 " + new SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(new Date(at));
        note.setText(current ? "额度已同步" + when : (state.startsWith("未更新") || state.contains("同步中") || state.contains("登录") || state.startsWith("部分") ? state : "未更新") + when);
        view.setContentDescription("周额度 " + week.getText() + (day.isEmpty() ? "" : "，" + day)
            + "，额外额度 " + invite.getText() + "，" + code.getText() + "，" + uses.getText() + "，" + note.getText());
    }

    private static boolean old(long observed, long now) { return observed <= 0 || now - observed >= 300_000; }
}
