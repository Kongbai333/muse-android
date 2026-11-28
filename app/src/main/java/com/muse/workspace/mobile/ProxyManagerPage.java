package com.muse.workspace.mobile;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import static com.muse.workspace.mobile.MobileUi.*;

/** 代理设置页：每条配置显示协议、地址，以及被几个账号引用。点一行进入编辑。 */
final class ProxyManagerPage extends LinearLayout {
    interface Host {
        void edit(ProxyProfile profile);
        void remove(ProxyProfile profile);
        void check();
        /** 当前网络方式短状态；实际风险和不支持原因必须保留。 */
        String routing();
    }

    ProxyManagerPage(Context context, MobileUi ui, ProxyStore store, AccountStore accounts, Host host) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(20));

        List<ProxyProfile> profiles = store.all();
        LinearLayout routing = ui.column();
        routing.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        routing.setBackground(ui.shape(SURFACE, 16, LINE));
        LinearLayout heading = ui.row();
        String routeState = host.routing();
        boolean hub = "本机分流".equals(routeState);
        View dot = new View(context); dot.setBackground(ui.shape(hub ? quotaColor(0d) : MUTED, 99, 0));
        heading.addView(dot, new LayoutParams(ui.dp(8), ui.dp(8)));
        TextView title = ui.text(hub ? "本机代理分流已启用" : "代理分流状态", 13.5f, INK, true);
        LayoutParams titlePosition = new LayoutParams(0, -2, 1); titlePosition.setMarginStart(ui.dp(8)); heading.addView(title, titlePosition);
        TextView tag = ui.meta("HUB", 10, TEXT2); tag.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)); tag.setBackground(ui.shape(RAISED, 4, 0)); heading.addView(tag);
        routing.addView(heading);
        ui.block(routing, ui.text(hub ? "不同代理的账号可同时在线，切换不重新加载网页。建议定期检测出口，确认每个账号只走自己的代理。" : routeState, 12, TEXT2, false), 10);
        TextView check = ui.primary("检测各账号代理出口", host::check);
        check.setTextSize(13); check.setMinHeight(ui.dp(40)); check.setMinimumHeight(ui.dp(40));
        ui.block(routing, check, 10); ui.block(this, routing, 0);
        if (profiles.isEmpty()) {
            LinearLayout empty = ui.column();
            empty.setPadding(ui.dp(4), ui.dp(56), ui.dp(4), 0);
            empty.addView(ui.text("还没有代理配置", 22, INK, true));
            TextView add = ui.primary("新增代理", () -> host.edit(null));
            LinearLayout.LayoutParams addPosition = new LinearLayout.LayoutParams(-2, ui.dp(46));
            addPosition.topMargin = ui.dp(20);
            empty.addView(add, addPosition);
            addView(empty, new LayoutParams(-1, -2));
            return;
        }

        ui.block(this, ui.meta("代理线路 " + profiles.size(), 10, MUTED), 14);
        for (ProxyProfile profile : profiles) ui.block(this, row(ui, profile, accounts, host), 8);
    }

    static TextView protocolTag(MobileUi ui, String scheme) {
        TextView protocol = ui.text(scheme.toUpperCase(java.util.Locale.ROOT), 10.5f, INK, true);
        protocol.setTypeface(MobileUi.MONO_MEDIUM);
        protocol.setGravity(Gravity.CENTER);
        protocol.setBackground(ui.shape(android.graphics.Color.TRANSPARENT, 4, LINE_STRONG));
        protocol.setPadding(0, ui.dp(5), 0, ui.dp(5));
        protocol.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(62), -2));
        return protocol;
    }

    private View row(MobileUi ui, ProxyProfile profile, AccountStore accounts, Host host) {
        LinearLayout row = ui.column();
        row.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12));
        LinearLayout heading = ui.row();
        TextView protocol = ui.metaStrong(profile.scheme.toUpperCase(java.util.Locale.ROOT), 10, TEXT2);
        protocol.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)); protocol.setBackground(ui.shape(android.graphics.Color.TRANSPARENT, 4, LINE_STRONG));
        heading.addView(protocol);
        TextView name = ui.single(ui.text(profile.name, 14, INK, true));
        LayoutParams namePosition = new LayoutParams(0, -2, 1); namePosition.setMarginStart(ui.dp(8)); heading.addView(name, namePosition);
        if (profile.credential != null && !profile.credential.isEmpty()) heading.addView(ui.iconView(R.drawable.ic_lock, MUTED, 12));
        heading.addView(action(ui, "编辑", TEXT2, () -> host.edit(profile)));
        heading.addView(action(ui, "删除", MUTED, () -> host.remove(profile)));
        row.addView(heading, new LayoutParams(-1, -2));
        String address = (profile.host.indexOf(':') >= 0 ? "[" + profile.host + "]" : profile.host) + ":" + profile.port;
        TextView endpoint = ui.single(ui.meta(address, 11, MUTED));
        ui.block(row, endpoint, 6);
        View divider = new View(getContext()); divider.setBackgroundColor(LINE);
        LayoutParams dividerPosition = new LayoutParams(-1, ui.dp(1)); dividerPosition.topMargin = ui.dp(8); row.addView(divider, dividerPosition);

        int count = accounts.referenceCount(profile.id);
        TextView refs = ui.meta(count + " 个账号使用", 10, TEXT2);
        ui.block(row, refs, 6);
        row.setContentDescription(profile.name + "，" + profile.scheme.toUpperCase(java.util.Locale.ROOT) + " " + address
            + "，" + referenceLabel(profile, accounts) + "，点按编辑");
        ui.touch(row, SURFACE, 16, LINE, () -> host.edit(profile));
        return row;
    }

    private TextView action(MobileUi ui, String label, int color, Runnable action) {
        TextView button = ui.text(label, 11.5f, color, false);
        button.setGravity(Gravity.CENTER); button.setPadding(ui.dp(8), 0, ui.dp(8), 0); button.setMinHeight(ui.dp(28));
        ui.touch(button, android.graphics.Color.TRANSPARENT, 8, 0, action);
        return button;
    }

    static String referenceLabel(ProxyProfile profile, AccountStore accounts) {
        int count = accounts.referenceCount(profile.id);
        if (count == 0) return "暂无账号引用";
        return count + " 个账号引用 · " + referenceNames(profile, accounts);
    }

    static String referenceNames(ProxyProfile profile, AccountStore accounts) {
        StringBuilder names = new StringBuilder();
        for (AccountStore.Account account : accounts.all()) {
            if (!profile.id.equals(account.proxyId())) continue;
            if (names.length() > 0) names.append("、");
            names.append(account.name);
            if (names.length() > 54) break;
        }
        if (names.length() > 54) names.setLength(54);
        return names.toString();
    }
}
