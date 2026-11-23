package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.Typeface;
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
        setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(28));

        List<ProxyProfile> profiles = store.all();
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

        // 分流状态 + 出口检测入口。
        LinearLayout routing = ui.row();
        routing.setPadding(ui.dp(14), ui.dp(12), ui.dp(10), ui.dp(12));
        routing.setBackground(ui.shape(RAISED, 16, 0));
        TextView state = ui.text(host.routing(), 12, TEXT2, false);
        state.setLineSpacing(ui.dp(3), 1f);
        state.setTooltipText(state.getText());
        routing.addView(state, new LayoutParams(0, -2, 1));
        TextView check = ui.small("检测出口", 0, false, host::check);
        LayoutParams checkPosition = new LayoutParams(-2, ui.dp(34));
        checkPosition.setMarginStart(ui.dp(10));
        routing.addView(check, checkPosition);
        ui.block(this, routing, 6);
        for (ProxyProfile profile : profiles) ui.block(this, row(ui, profile, accounts, host), 8);
    }

    static TextView protocolTag(MobileUi ui, String scheme) {
        TextView protocol = ui.text(scheme.toUpperCase(java.util.Locale.ROOT), 10.5f, INK, true);
        protocol.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        protocol.setGravity(Gravity.CENTER);
        protocol.setBackground(ui.shape(RAISED, 6, 0));
        protocol.setPadding(0, ui.dp(5), 0, ui.dp(5));
        protocol.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(62), -2));
        return protocol;
    }

    private View row(MobileUi ui, ProxyProfile profile, AccountStore accounts, Host host) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(12), ui.dp(14), ui.dp(14), ui.dp(14));
        row.addView(protocolTag(ui, profile.scheme));

        LinearLayout words = ui.column();
        words.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        words.addView(ui.single(ui.text(profile.name, 14.5f, INK, true)));
        String address = (profile.host.indexOf(':') >= 0 ? "[" + profile.host + "]" : profile.host) + ":" + profile.port;
        TextView endpoint = ui.single(ui.text(address, 12, TEXT2, false));
        endpoint.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams endpointPosition = new LinearLayout.LayoutParams(-1, -2);
        endpointPosition.topMargin = ui.dp(5);
        words.addView(endpoint, endpointPosition);
        row.addView(words, new LayoutParams(0, -2, 1));

        int count = accounts.referenceCount(profile.id);
        TextView refs = ui.text(count == 0 ? "暂无账号引用" : count + " 个账号", 12, count == 0 ? MUTED : INK, count != 0);
        refs.setGravity(Gravity.END);
        row.addView(refs, new LayoutParams(-2, -2));
        row.setContentDescription(profile.name + "，" + profile.scheme.toUpperCase(java.util.Locale.ROOT) + " " + address
            + "，" + referenceLabel(profile, accounts) + "，点按编辑");
        ui.touch(row, SURFACE, 16, LINE, () -> host.edit(profile));
        return row;
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
