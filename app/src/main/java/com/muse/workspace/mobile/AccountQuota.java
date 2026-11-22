package com.muse.workspace.mobile;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

record AccountQuota(String plan, Double used, long observed, long resets, String extra) {
    private static final long MAX_AGE = 5 * 60_000L;

    static AccountQuota read(String plan, String state, Double used, long observed, long resets,
                             Double balance, Double total, String display, long now) {
        if (observed <= 0 || observed > now + 30_000 || (!"known".equals(state) && !"unlimited".equals(state))) return null;
        if ("known".equals(state) && (!valid(used) || used > 100.)) return null;
        if (!valid(balance)) balance = null;
        if (!valid(total)) total = null;
        if (balance != null && total != null && balance > total) { balance = null; display = null; }
        String invitation = label(display, 96, "");
        if (invitation.isEmpty() && balance != null) {
            invitation = "剩余 " + number(balance) + (total == null ? "" : " / " + number(total));
        }
        return new AccountQuota(label(plan, 32, "套餐"), "unlimited".equals(state) ? null : used,
            observed, resets, invitation);
    }

    boolean fresh(long now) { return observed <= now + 30_000 && now - observed < MAX_AGE; }
    boolean currentWeek(long now) { return fresh(now) && (resets <= 0 || resets > now); }

    String weekly(long now) {
        if (!currentWeek(now)) return "待刷新";
        return used == null ? "不限量" : "已用 " + Math.round(used) + "%";
    }

    String resetLabel(long now) {
        if (resets <= 0 || !currentWeek(now)) return "";
        return new SimpleDateFormat("M月d日重置", Locale.SIMPLIFIED_CHINESE).format(new Date(resets));
    }

    String invitation(long now) {
        if (!fresh(now)) return "待刷新";
        return extra.isEmpty() ? "待同步" : extra;
    }

    private static boolean valid(Double value) { return value != null && Double.isFinite(value) && value >= 0.; }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    private static String label(String value, int max, String fallback) {
        if (value == null) return fallback;
        String clean = value.trim();
        return clean.isEmpty() || clean.length() > max || clean.chars().anyMatch(Character::isISOControl) ? fallback : clean;
    }
}
