package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import static com.muse.workspace.mobile.MobileUi.*;

/** Read-only account presentations shared by generation sheets and header switching. */
final class AccountChoices {
    static boolean exhausted(AccountQuota quota) {
        return quota != null && quota.currentWeek(System.currentTimeMillis()) && quota.used() != null && quota.used() >= 100;
    }
    static int tone(AccountQuota quota) { return quota == null ? MUTED : quotaColor(quota.used() == null ? 0d : quota.used()); }
    static String percent(AccountQuota quota) {
        return quota == null ? "待同步" : quota.used() == null ? "不限" : exhausted(quota) ? "用完" : Math.round(quota.used()) + "%";
    }
    /** Visible companion to cached readings; identical freshness semantics to weekly(). */
    static String cachedLabel(AccountQuota quota, long now) {
        if (quota == null || quota.currentWeek(now)) return "";
        return quota.resets() > quota.observed() && quota.resets() <= now ? "旧周期" : "上次";
    }
    static List<AccountStore.Account> sorted(List<AccountStore.Account> accounts, AccountStore.Account selected,
            QuotaSync quotas, Predicate<AccountStore.Account> usable, boolean pinCurrent) {
        ArrayList<AccountStore.Account> result = new ArrayList<>(accounts);
        result.sort((a, b) -> {
            if (a == b) return 0;
            if (pinCurrent && (a == selected || b == selected)) return a == selected ? -1 : 1;
            AccountQuota qa = quotas.value(a.id), qb = quotas.value(b.id);
            boolean outA = !usable.test(a) || exhausted(qa), outB = !usable.test(b) || exhausted(qb);
            if (outA != outB) return outA ? 1 : -1;
            if (outA) return 0;
            if ((qa != null) != (qb != null)) return qa != null ? -1 : 1;
            return Double.compare(qa == null || qa.used() == null ? 0 : qa.used(), qb == null || qb.used() == null ? 0 : qb.used());
        });
        return result;
    }

    static View row(Context context, MobileUi ui, AccountStore.Account account, QuotaSync quotas, ProxyStore proxies,
            boolean selected, boolean usable, boolean parked, boolean grid, boolean generation, Runnable action) {
        AccountQuota quota = quotas.value(account.id);
        boolean enabled = usable && !exhausted(quota);
        int faceSize = generation ? 26 : grid ? 20 : 24;
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(generation ? 10 : 8), 0, ui.dp(generation ? 10 : 8), 0);
        row.setMinimumHeight(ui.rowHeight(generation ? 46 : grid ? 42 : 40));
        row.addView(new AccountFace(context, ui, account, faceSize, selected, usable, quota, quotas.avatar(account.id)),
            new LinearLayout.LayoutParams(ui.dp(faceSize), ui.dp(faceSize)));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(grid ? 6 : generation ? 10 : 8), 0, ui.dp(4), 0);
        LinearLayout nameLine = ui.nameRow();
        nameLine.addView(ui.single(ui.text(account.name, grid ? 11.5f : generation ? 13 : 12.5f, INK, true)),
            new LinearLayout.LayoutParams(-2, -2));
        if (generation && !quotas.card(account.id).plan().isEmpty()) {
            TextView tag = ui.single(ui.meta(quotas.card(account.id).plan().toUpperCase(java.util.Locale.ROOT), 8.5f, MUTED));
            tag.setMaxWidth(ui.dp(48)); tag.setPadding(ui.dp(4), 0, ui.dp(4), 0); tag.setBackground(ui.shape(Color.TRANSPARENT, 4, LINE_STRONG));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMarginStart(ui.dp(6)); nameLine.addView(tag, p);
        }
        if (parked) {
            View dot = new View(context); dot.setBackground(ui.shape(quotaColor(0d), 99, 0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(grid ? 4 : 6), ui.dp(grid ? 4 : 6));
            p.setMarginStart(ui.dp(4)); nameLine.addView(dot, p);
        }
        words.addView(nameLine, new LinearLayout.LayoutParams(-1, -2));
        ProxyProfile proxy = proxies.find(account.proxyId());
        String route = proxy == null ? account.networkLabel(proxies) : proxy.scheme.toUpperCase(java.util.Locale.ROOT) + " · " + proxy.name;
        long now = System.currentTimeMillis();
        String state = quota == null ? "待同步" : quota.weekly(now);
        String cached = cachedLabel(quota, now);
        String detail = !usable ? "需更新 WebView" : exhausted(quota) ? "本周额度已用完" : grid ? percent(quota)
            : generation ? state + " · " + route : (cached.isEmpty() ? "" : cached + " · ") + route + (parked ? " · 后台保留中" : "");
        ui.block(words, ui.single(ui.meta(detail, grid ? 9.5f : 9, grid ? tone(quota) : MUTED)), 2);
        // A separate short line keeps both value and cache state visible in narrow grid cells.
        // Fresh rows have precisely the previous two-line layout.
        if (grid && usable && !cached.isEmpty()) ui.block(words, ui.meta(cached, 8, MUTED), 0);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (!grid) row.addView(ui.single(ui.metaStrong(usable ? percent(quota) : "不可用", generation ? 11.5f : 11, usable ? tone(quota) : MUTED)));
        if (selected) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)); p.setMarginStart(ui.dp(6));
            row.addView(ui.iconView(R.drawable.ic_check, INK, 14), p);
        }
        row.setSelected(selected); row.setAlpha(enabled ? 1 : .4f); row.setEnabled(enabled);
        int fill = selected ? grid ? ACTIVE_CARD : RAISED : grid ? RAISED : Color.TRANSPARENT;
        int border = generation || grid ? selected ? LINE_STRONG : LINE : 0;
        if (enabled) ui.touch(row, fill, 12, border, action); else row.setBackground(ui.shape(fill, 12, border));
        row.setContentDescription(account.name + "，" + state + "，" + route + (parked ? "，后台保留中" : "")
            + (selected ? "，当前选择" : "") + (enabled ? "" : "，不可选"));
        row.setTooltipText(row.getContentDescription());
        return row;
    }
}
