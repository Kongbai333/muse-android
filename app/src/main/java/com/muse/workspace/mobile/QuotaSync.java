package com.muse.workspace.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.function.Consumer;

final class QuotaSync {
    private final Context context;
    private final SharedPreferences cache;
    private final MuseWeb browser;
    private final MuseAvatarCache avatars;
    private final Consumer<String> changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HashMap<String, String> states = new HashMap<>();
    private Probe active;
    private String script;

    private static final class Probe {
        final String account;
        final long started = SystemClock.elapsedRealtime();
        boolean first = true;
        long quotaReadyAt;
        MuseWeb.WorkPage page;

        Probe(String account) { this.account = account; }
    }

    QuotaSync(Context context, MuseWeb browser, MuseAvatarCache avatars, Consumer<String> changed) {
        this.context = context;
        this.browser = browser;
        this.avatars = avatars;
        this.changed = changed;
        cache = context.getSharedPreferences("muse-account-quotas", Context.MODE_PRIVATE);
    }

    AccountQuota value(String account) {
        return decode(MuseAdapters.reply(cache.getString(account, null)), System.currentTimeMillis());
    }

    android.graphics.Bitmap avatar(String account) { return avatars.image(account); }

    String state(String account) { return states.getOrDefault(account, "待同步"); }

    void refresh(AccountStore.Account account, boolean profiles, boolean manual) {
        if (active != null && active.account.equals(account.id)) return;
        if (!manual) {
            AccountQuota old = value(account.id);
            if (active != null || (old != null && old.currentWeek(System.currentTimeMillis()) && avatars.has(account.id))) return;
        }
        cancel();
        Probe probe = new Probe(account.id);
        active = probe;
        state(probe, "同步中…");
        try {
            if (script == null) script = MuseAdapters.source(context, "muse-observer.js");
            probe.page = browser.openWorker(account, profiles, StudioDraft.HOME, script,
                reason -> end(probe, "同步失败"));
            main.postDelayed(() -> poll(probe), 350);
        } catch (IOException error) {
            end(probe, "同步失败");
        }
    }

    private void poll(Probe probe) {
        if (active != probe) return;
        if (probe.page.needsLogin()) { loggedOut(probe); return; }
        if (SystemClock.elapsedRealtime() - probe.started >= 27_000) { end(probe, "同步失败 · 点刷新重试"); return; }
        if (probe.page.ready()) {
            boolean force = probe.first;
            probe.first = false;
            String expression = "JSON.stringify((()=>{const o=window.__museDesktopObserver?.snapshot(" + force
                + ",true);return o&&{url:o.url,quota:o.quota,status:o.quota_status,avatar:o.avatar_png};})())";
            probe.page.evaluate(expression, raw -> receive(probe, raw));
        }
        main.postDelayed(() -> poll(probe), 900);
    }

    private void receive(Probe probe, String raw) {
        if (active != probe) return;
        JSONObject result = MuseAdapters.reply(raw);
        if (result == null || !StudioDraft.trustedUrl(result.optString("url"))) return;
        if ("logged_out".equals(result.optString("status"))) {
            loggedOut(probe);
            return;
        }
        String avatar = result.optString("avatar", null);
        avatars.save(probe.account, avatar);
        if (avatar != null && !avatar.isEmpty()) changed.accept(probe.account);
        JSONObject reading = result.optJSONObject("quota");
        long now = System.currentTimeMillis();
        AccountQuota data = decode(reading, now);
        if (data != null && data.fresh(now)) {
            cache.edit().putString(probe.account, reading.toString()).apply();
            if (probe.quotaReadyAt == 0) probe.quotaReadyAt = SystemClock.elapsedRealtime();
            if (!avatars.has(probe.account)
                    && SystemClock.elapsedRealtime() - probe.quotaReadyAt < 5_000) return;
            end(probe, "已同步");
        }
    }

    private void loggedOut(Probe probe) {
        cache.edit().remove(probe.account).apply();
        avatars.remove(probe.account);
        end(probe, "请先登录");
    }

    private static AccountQuota decode(JSONObject reading, long now) {
        if (reading == null) return null;
        JSONObject extra = reading.optJSONObject("extra");
        return AccountQuota.read(reading.optString("plan_label", ""), reading.optString("state"), number(reading, "used_percent"),
            reading.optLong("observed_at"), reading.optLong("resets_at"), number(extra, "remaining"), number(extra, "total"),
            extra == null || extra.isNull("display_value") ? null : extra.optString("display_value"), now);
    }

    private static Double number(JSONObject object, String key) {
        return object != null && object.opt(key) instanceof Number number ? number.doubleValue() : null;
    }

    private void state(Probe probe, String message) {
        states.put(probe.account, message);
        changed.accept(probe.account);
    }

    private void end(Probe probe, String message) {
        if (active != probe) return;
        active = null;
        if (probe.page != null) probe.page.close();
        state(probe, message);
    }

    void cancel() { if (active != null) end(active, "待同步"); }

    void remove(String id) {
        if (active != null && active.account.equals(id)) cancel();
        cache.edit().remove(id).apply();
        states.remove(id);
    }
}
