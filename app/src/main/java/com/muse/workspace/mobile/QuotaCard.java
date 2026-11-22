package com.muse.workspace.mobile;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;
import static com.muse.workspace.mobile.MobileUi.*;

/** 账号卡上的额度区：周额度刻度条、重置日期与邀请额度。 */
final class QuotaCard {
    private final TextView week, reset, invite;
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
        bottom.addView(ui.text("邀请额度 ", 12, TEXT2, false), new LinearLayout.LayoutParams(-2, -2));
        invite = ui.single(ui.figure("待同步", 12, INK));
        invite.setMaxWidth(ui.dp(140));
        bottom.addView(invite, new LinearLayout.LayoutParams(-2, -2));
        ui.block(view, bottom, 7);
    }

    void update(AccountQuota data, String state) {
        long now = System.currentTimeMillis();
        String plan = data == null ? "待同步" : data.plan();
        planChanged.accept(plan);
        String weekly = data == null ? state : data.currentWeek(now) ? data.weekly(now)
            : "已同步".equals(state) || "待同步".equals(state) ? data.weekly(now) : state;
        boolean current = data != null && data.currentWeek(now);
        Double used = current ? data.used() : null;
        boolean exhausted = used != null && used >= 100;
        week.setText(exhausted ? "已用完" : weekly);
        week.setTextColor(used != null && used >= 80 ? ACCENT : INK);
        MobileUi.setTicks(ticks, used, current);
        String day = data == null ? "" : data.resetLabel(now);
        reset.setText(!day.isEmpty() ? day : current && used == null ? "无需重置" : "");
        invite.setText(data == null ? "待同步" : data.invitation(now));
        view.setContentDescription("周额度 " + week.getText() + (day.isEmpty() ? "" : "，" + day)
            + "，邀请额度 " + invite.getText());
    }
}
