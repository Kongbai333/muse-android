package com.muse.workspace.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

final class QuotaSync {
    private final Context context;
    private final SharedPreferences cache;
    private final MuseWeb browser;
    private final MuseAvatarCache avatars;
    private final Consumer<String> changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HashMap<String, String> states = new HashMap<>();
    private final QuotaRefreshPolicy policy = new QuotaRefreshPolicy();
    private Probe active;
    private String script;

    private static final class Probe {
        final String account;
        final long started = SystemClock.elapsedRealtime();
        final long wallStarted = System.currentTimeMillis();
        boolean first = true;
        boolean evaluating, updated;
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
        return card(account).quota(System.currentTimeMillis());
    }

    AccountCardState card(String account) {
        return AccountCardState.read(jsonMap(MuseAdapters.reply(cache.getString(account, null))), System.currentTimeMillis());
    }

    android.graphics.Bitmap avatar(String account) { return avatars.image(account); }

    String state(String account) { return states.getOrDefault(account, "待同步"); }

    void refresh(AccountStore.Account account, boolean profiles, boolean manual) {
        if (active != null && active.account.equals(account.id)) return;
        long elapsed = SystemClock.elapsedRealtime();
        if (!policy.allow(account.id, manual, elapsed)) return;
        if (!manual) {
            AccountQuota old = value(account.id);
            if (active != null || (old != null && old.currentWeek(System.currentTimeMillis()) && avatars.has(account.id))) return;
        }
        cancel();
        Probe probe = new Probe(account.id);
        active = probe;
        policy.started(account.id, elapsed);
        state(probe, "同步中…");
        try {
            if (script == null) script = MuseAdapters.source(context, "muse-observer.js");
            probe.page = browser.openWorker(account, profiles, StudioDraft.HOME, script,
                reason -> end(probe, "未更新 · 网页未就绪"));
            main.postDelayed(() -> poll(probe), 350);
        } catch (IOException error) {
            end(probe, "未更新 · 读取失败");
        }
    }

    private void poll(Probe probe) {
        if (active != probe) return;
        if (probe.page.needsLogin()) { loggedOut(probe); return; }
        if (SystemClock.elapsedRealtime() - probe.started >= 27_000) { end(probe, probe.updated ? "部分已更新 · 额度待重试" : "未更新 · 可稍后重试"); return; }
        if (probe.page != null && probe.page.ready() && !probe.evaluating) {
            boolean force = probe.first;
            probe.first = false;
            String expression = "JSON.stringify((()=>{const o=window.__museDesktopObserver?.snapshot(" + force
                + ",true);return o&&{url:o.url,quota:o.quota,plan:o.plan,extra:o.extra,invite:o.invite,invite_status:o.invite_status,retry_after_ms:o.retry_after_ms,status:o.quota_status,avatar:o.avatar_png};})())";
            probe.evaluating = true;
            probe.page.evaluate(expression, raw -> receive(probe, raw));
        }
        main.postDelayed(() -> poll(probe), 900);
    }

    private void receive(Probe probe, String raw) {
        if (active != probe) return;
        probe.evaluating = false;
        JSONObject result = MuseAdapters.reply(raw);
        if (result == null || !StudioDraft.trustedUrl(result.optString("url"))) return;
        if ("logged_out".equals(result.optString("status"))) {
            loggedOut(probe);
            return;
        }
        String avatar = result.optString("avatar", null);
        avatars.save(probe.account, avatar);
        if (avatar != null && !avatar.isEmpty()) changed.accept(probe.account);
        long now = System.currentTimeMillis();
        AccountCardState previous = card(probe.account);
        AccountCardState merged = previous.merge(jsonMap(result), now);
        if (!merged.rows().equals(previous.rows())) {
            cache.edit().putString(probe.account, new JSONObject(merged.rows()).toString()).apply();
            probe.updated = true;
            changed.accept(probe.account);
        }
        String status = result.optString("status");
        if ("rate_limited".equals(status)) {
            long delay = Math.min(3_600_000, Math.max(90_000, result.optLong("retry_after_ms", 90_000)));
            policy.finished(probe.account, "rate", delay, SystemClock.elapsedRealtime());
            end(probe, "未更新 · 网站限流，请稍后重试");
            return;
        }
        if ("forbidden".equals(status)) { end(probe, "未更新 · 网站暂未允许读取"); return; }
        if (("not_found".equals(status) || "request_failed".equals(status) || "unrecognized".equals(status) || "partial".equals(status))
                && result.has("retry_after_ms") && result.isNull("retry_after_ms")) {
            end(probe, probe.updated ? "部分已更新 · 额度待重试" : "未更新 · 未取得有效数据"); return;
        }
        AccountQuota data = merged.quota(now);
        if (data != null && data.fresh(now) && data.observed() >= probe.wallStarted - 1000) {
            if (probe.quotaReadyAt == 0) probe.quotaReadyAt = SystemClock.elapsedRealtime();
            long held = SystemClock.elapsedRealtime() - probe.quotaReadyAt;
            if (held < 1500 || !avatars.has(probe.account) && held < 5_000) return;
            end(probe, "额度已同步");
        }
    }

    private void loggedOut(Probe probe) {
        cache.edit().remove(probe.account).apply();
        avatars.remove(probe.account);
        policy.finished(probe.account, "auth", 0, SystemClock.elapsedRealtime());
        end(probe, "请先登录");
    }

    void loginReady(String account) {
        policy.loginReady(account);
    }

    private static Map<String,Object> jsonMap(JSONObject object) { return jsonMap(object, 0); }
    private static Map<String,Object> jsonMap(JSONObject object, int depth) {
        Map<String,Object> result = new LinkedHashMap<>();
        if (object == null || depth > 5) return result;
        java.util.Iterator<String> keys = object.keys(); int count = 0;
        while (keys.hasNext() && count++ < 64) {
            String key = keys.next(); Object value = object.opt(key);
            if (value instanceof JSONObject nested) value = jsonMap(nested, depth + 1);
            else if (value == JSONObject.NULL) value = null;
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Map) result.put(key, value);
        }
        return result;
    }

    private void state(Probe probe, String message) {
        states.put(probe.account, message);
        changed.accept(probe.account);
    }

    private void end(Probe probe, String message) {
        if (active != probe) return;
        active = null;
        if (probe.page != null) probe.page.close();
        String outcome = "额度已同步".equals(message) ? "ok" : message.contains("限流") ? "cancel"
            : message.contains("暂未允许") ? "forbidden" : message.contains("暂停") || message.contains("登录") ? "cancel" : "failure";
        policy.finished(probe.account, outcome, 0, SystemClock.elapsedRealtime());
        if (policy.paused(probe.account)) message += " · 点刷新重试";
        state(probe, message);
    }

    void cancel() { if (active != null) end(active, "未更新 · 已暂停"); }

    void remove(String id) {
        if (active != null && active.account.equals(id)) cancel();
        cache.edit().remove(id).apply();
        states.remove(id);
        policy.remove(id);
    }
}
