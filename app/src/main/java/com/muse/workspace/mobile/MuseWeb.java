package com.muse.workspace.mobile;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.webkit.WebViewCompat;
import java.io.IOException;
import java.util.HashMap;
import java.util.function.Consumer;

final class MuseWeb {
    interface Host {
        void browserChanged();
        boolean chooseFile(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params);
        void showVideo(View view, WebChromeClient.CustomViewCallback callback);
        void hideVideo();
    }

    private final Activity activity;
    private final Host host;
    private final WebMicrophone microphone;
    private final ProxyStore proxies;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HashMap<String, String> lastUrls = new HashMap<>();
    // 同一网络路由（同一代理或都用系统网络）下，切走的账号网页先保留在这里，切回来不重新加载。
    // WebView 的代理是进程全局的：路由一变就全部销毁，避免旧页面借用别的账号的网络出口。
    // 上限按手机内存自动决定；内存紧张时先释放最久没用的。被释放的网页会记下页面和返回记录，切回时恢复（需重新加载）。
    private final java.util.LinkedHashMap<String, WebView> parked = new java.util.LinkedHashMap<>(16, 0.75f, true);
    private final java.util.LinkedHashMap<String, android.os.Bundle> savedStates = new java.util.LinkedHashMap<>(16, 0.75f, true);
    private static final int SAVED_STATE_LIMIT = 30;
    private final int parkCapacity;
    private String appliedRoute;
    // 本机代理分流器：开启后全局代理固定指向它，不同代理的账号也能同时在线。
    static final String HUB_DISABLED = "proxy-hub-disabled";
    private final android.content.SharedPreferences settings;
    private ProxyHub hub;
    private final java.util.WeakHashMap<WebView, AccountStore.Account> owners = new java.util.WeakHashMap<>();
    private FrameLayout workerHost;
    private WebView view;
    private WorkPage worker;
    private AccountStore.Account account;
    private boolean multiProfile;
    private LoopbackProxy bridge;
    private boolean usingProxy;
    private boolean preparing;
    private boolean routeReady;
    private boolean wantsBrowser;
    private int routeGeneration;
    private String initialUrl = StudioDraft.HOME;
    private String notice = "";
    private boolean loading;
    private boolean transferActive;
    private Consumer<String> transferRoute;

    MuseWeb(Activity activity, Host host, WebMicrophone microphone, ProxyStore proxies) {
        this.activity = activity;
        this.host = host;
        this.microphone = microphone;
        this.proxies = proxies;
        parkCapacity = capacityFor(activity);
        settings = activity.getSharedPreferences(MobileUi.PREFS, android.content.Context.MODE_PRIVATE);
    }

    /** 是否允许使用分流器（检测发现串号时会自动关闭）。 */
    boolean hubAllowed() { return !settings.getBoolean(HUB_DISABLED, false) && WebProxyRoute.supported(); }
    boolean hubActive() { return hub != null && "hub".equals(appliedRoute); }

    /** 后台保留网页数：低内存设备 1 个，之后按总内存递增，最多 10 个。 */
    private static int capacityFor(Activity activity) {
        try {
            android.app.ActivityManager manager = activity.getSystemService(android.app.ActivityManager.class);
            if (manager == null) return 3;
            if (manager.isLowRamDevice()) return 1;
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            double gb = info.totalMem / (1024d * 1024d * 1024d);
            if (gb < 2.8) return 2;
            if (gb < 3.6) return 3;
            if (gb < 5.5) return 4;
            if (gb < 7.5) return 6;
            if (gb < 11) return 8;
            return 10;
        } catch (RuntimeException ignored) {
            return 3;
        }
    }

    /** 系统可用内存是否已经偏紧（低于系统阈值的 2 倍）。 */
    private boolean memoryTight() {
        try {
            android.app.ActivityManager manager = activity.getSystemService(android.app.ActivityManager.class);
            if (manager == null) return false;
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return info.lowMemory || info.availMem < info.threshold * 2;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    int parkCapacity() { return parkCapacity; }
    int parkedCount() { return parked.size(); }
    boolean isParked(String accountId) { return accountId != null && parked.containsKey(accountId); }
    boolean isOpen(String accountId) { return view != null && account != null && account.id.equals(accountId); }

    WebView view() { return view; }
    void setWorkerHost(FrameLayout underlay) { workerHost = underlay; }
    String url() { return view == null ? null : view.getUrl(); }
    String notice() { return notice; }
    boolean loading() { return loading || preparing(); }
    boolean preparing() { return wantsBrowser && view == null && (preparing || routeReady); }
    boolean usingProxy() { return usingProxy; }
    boolean canGoBack() { return view != null && view.canGoBack(); }

    void open(AccountStore.Account selected, boolean profiles) {
        if (transferActive) return;
        bind(selected, profiles);
        wantsBrowser = true;
        pump();
    }

    WorkPage openWorker(AccountStore.Account selected, boolean profiles, String url, String script, Consumer<String> failure) {
        if (transferActive) {
            WorkPage blocked = new WorkPage(url, script, failure);
            main.post(() -> blocked.fail("账号迁移期间暂停后台网页。"));
            return blocked;
        }
        bind(selected, profiles);
        if (worker != null) worker.close();
        WorkPage page = new WorkPage(url, script, failure);
        worker = page;
        // 调用方先保存任务引用，再让网络错误或创建回调生效。
        main.post(this::pump);
        return page;
    }

    /** 切换到另一个账号：同一路由时保留当前网页，否则完整关闭。 */
    void switchTo(AccountStore.Account selected, boolean profiles) {
        if (transferActive) return;
        if (selected != null) bind(selected, profiles);
    }

    private static String routeKey(AccountStore.Account account) {
        if (!account.hasProxy()) return "direct";
        return account.proxyId().isEmpty() ? "legacy:" + account.id : "proxy:" + account.proxyId();
    }

    private void bind(AccountStore.Account selected, boolean profiles) {
        if (account != null && account.id.equals(selected.id)) return;
        boolean keep = account != null && routeReady && profiles && multiProfile && appliedRoute != null;
        boolean upgrade = false;
        if (keep) {
            if ("hub".equals(appliedRoute)) keep = hub != null;
            else if ("direct".equals(appliedRoute) && !selected.hasProxy()) keep = true;
            else if ("direct".equals(appliedRoute) && hubAllowed()) upgrade = true; // 直连 → 分流器：已打开的网页保留
            else keep = appliedRoute.equals(routeKey(selected));
        }
        if (keep) suspendCurrent(); else close();
        if (upgrade) routeReady = false;
        account = selected;
        multiProfile = profiles;
        usingProxy = selected.hasProxy();
        initialUrl = lastUrls.getOrDefault(selected.id, StudioDraft.HOME);
    }

    private void pump() {
        if (transferActive) return;
        if (account == null || (!wantsBrowser && worker == null)) return;
        if (routeReady) {
            if (hub != null && "hub".equals(appliedRoute) && !ensureRegistered()) return;
            if (wantsBrowser && view == null) createBrowser();
            if (worker != null && worker.web == null) worker.start();
        } else if (!preparing) startRoute();
    }

    boolean beginTransfer() {
        if (transferActive || checking) return false;
        transferActive = true;
        try { close(); return true; }
        catch (RuntimeException error) { transferActive = false; throw error; }
    }

    void prepareTransferRoute(AccountStore.Account candidate, boolean profiles, Consumer<String> ready) {
        if (!transferActive) { ready.accept("cancelled"); return; }
        close();
        account = candidate;
        multiProfile = profiles;
        usingProxy = candidate.hasProxy();
        transferRoute = ready;
        startRoute();
    }

    void endTransfer() {
        if (!transferActive) return;
        transferRoute = null;
        try { close(); }
        finally { transferActive = false; host.browserChanged(); }
    }

    private void finishTransferRoute(String error) {
        Consumer<String> ready = transferRoute;
        transferRoute = null;
        if (ready != null) ready.accept(error);
    }

    /** 当前账号在分流器里登记（或更新）自己的上游。 */
    private boolean ensureRegistered() {
        try {
            hub.register(account.id, account.proxySettings(proxies));
            return true;
        } catch (Exception error) {
            failedToOpen("无法读取代理凭据，请在侧边栏编辑账号并重新填写。");
            return false;
        }
    }

    private void startRoute() {
        ProxySettings proxy;
        try {
            proxy = account.proxySettings(proxies);
        } catch (Exception error) {
            failedToOpen("无法读取代理凭据，请在侧边栏编辑账号并重新填写。");
            return;
        }
        if (proxy != null && !WebProxyRoute.supported()) {
            failedToOpen("当前 WebView 不支持代理设置，请更新 Android System WebView 或 Chrome。");
            return;
        }
        String rule = null;
        boolean useHub = hub != null || (proxy != null && hubAllowed());
        if (useHub) {
            try {
                if (hub == null) hub = new ProxyHub();
                hub.register(account.id, proxy);
                rule = hub.rule();
            } catch (IOException | RuntimeException error) {
                failedToOpen("无法启动本机代理分流，请重新打开 Muse 后重试。");
                return;
            }
        } else if (proxy != null) {
            try {
                if (proxy.hasAuthentication()) bridge = new LoopbackProxy(proxy);
                rule = bridge == null ? proxy.rule() : bridge.rule();
            } catch (IOException | RuntimeException error) {
                failedToOpen("无法启动账号代理，请检查设置后重试。");
                return;
            }
        }
        preparing = true;
        notice = usingProxy ? "正在应用账号代理…" : "正在打开网页…";
        int generation = routeGeneration;
        String key = useHub ? "hub" : routeKey(account);
        WebProxyRoute.apply(rule, error -> {
            if (generation != routeGeneration) return;
            preparing = false;
            if (error != null) failedToOpen(error);
            else {
                routeReady = true; appliedRoute = key;
                if (transferActive) finishTransferRoute(null); else pump();
            }
        });
    }

    private void createBrowser() {
        WebView kept = account == null ? null : parked.remove(account.id);
        android.os.Bundle saved = account == null || kept != null ? null : savedStates.remove(account.id);
        if (kept != null) {
            // 切回保留的网页：不重新加载。
            view = kept;
            kept.onResume();
            loading = false;
            notice = "";
            host.browserChanged();
            return;
        }
        try {
            view = createView(new Client(), true);
            boolean restored = false;
            if (saved != null) {
                // 恢复之前被释放的网页：回到原来的页面，保留返回记录。
                try { restored = view.restoreState(saved) != null; } catch (RuntimeException ignored) { restored = false; }
            }
            if (restored) notice = "";
            else navigate(initialUrl);
        } catch (RuntimeException error) {
            wantsBrowser = false;
            notice = "无法打开浏览器，请更新 Android System WebView 后重试。";
        }
        host.browserChanged();
    }

    private WebView createView(WebViewClient client, boolean foreground) {
        return createView(client, foreground, account);
    }

    private WebView createView(WebViewClient client, boolean foreground, AccountStore.Account owner) {
        WebView next = new WebView(activity);
        try {
            // 在触发任何导航或读取会话前绑定配置；绑定失败时不退回共享会话。
            if (multiProfile) WebViewCompat.setProfile(next, owner.profileName());
            owners.put(next, owner);
            WebSettings settings = next.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false);
            next.setWebViewClient(client);
            if (foreground) next.setWebChromeClient(new Chrome(next));
            return next;
        } catch (RuntimeException error) {
            next.destroy();
            throw error;
        }
    }

    private void failedToOpen(String message) {
        preparing = false;
        wantsBrowser = false;
        loading = false;
        closeBridge();
        notice = message;
        if (worker != null) worker.fail(message);
        if (transferRoute != null) finishTransferRoute("route");
        host.browserChanged();
    }

    void navigate(String url) {
        if (transferActive) return;
        if (!StudioDraft.webUrl(url)) return;
        initialUrl = url;
        if (view == null) return;
        microphone.cancelPending();
        notice = "";
        view.loadUrl(url);
    }

    void reload() {
        if (transferActive) return;
        if (view == null) return;
        microphone.cancelPending();
        notice = "";
        view.reload();
    }

    void back() {
        if (transferActive) return;
        if (canGoBack()) {
            microphone.cancelPending();
            notice = "";
            view.goBack();
        }
    }

    void detach() {
        microphone.cancelPending();
        if (view != null && view.getParent() instanceof ViewGroup parent) parent.removeView(view);
    }

    /** 同一路由下切走：保留当前网页（暂停），网络路由和代理桥接继续可用。 */
    private void suspendCurrent() {
        host.hideVideo();
        if (worker != null) worker.fail("账号环境已关闭，请重试。");
        wantsBrowser = false;
        notice = "";
        loading = false;
        if (view != null && account != null) {
            WebView old = view;
            String url = old.getUrl();
            if (StudioDraft.trustedUrl(url) && !StudioDraft.loginPage(url)) lastUrls.put(account.id, url);
            detach();
            view = null;
            quiet(old);
            WebView previous = parked.put(account.id, old);
            if (previous != null && previous != old) destroyQuietly(previous);
            trimParked(memoryTight() ? Math.max(1, parkCapacity / 3) : parkCapacity);
        }
    }

    /** 放到后台的网页：暂停音视频和页面渲染，减少内存和电量消耗。 */
    private static void quiet(WebView page) {
        try {
            if (StudioDraft.trustedUrl(page.getUrl())) {
                page.evaluateJavascript("(()=>{try{document.querySelectorAll('video,audio').forEach(m=>{try{m.pause()}catch(e){}})}catch(e){}})()", null);
            }
            page.onPause();
        } catch (RuntimeException ignored) { }
    }

    /** 只保留最近使用的 keep 个后台网页；释放的网页记下页面和返回记录。 */
    void trimParked(int keep) {
        while (parked.size() > Math.max(0, keep)) {
            java.util.Iterator<java.util.Map.Entry<String, WebView>> eldest = parked.entrySet().iterator();
            java.util.Map.Entry<String, WebView> entry = eldest.next();
            eldest.remove();
            remember(entry.getKey(), entry.getValue());
            destroyQuietly(entry.getValue());
        }
    }

    private void remember(String accountId, WebView page) {
        if (accountId == null || page == null) return;
        try {
            String url = page.getUrl();
            if (!StudioDraft.trustedUrl(url) || StudioDraft.loginPage(url)) return;
            lastUrls.put(accountId, url);
            android.os.Bundle state = new android.os.Bundle();
            if (page.saveState(state) == null) return;
            savedStates.put(accountId, state);
            while (savedStates.size() > SAVED_STATE_LIMIT) {
                java.util.Iterator<String> eldest = savedStates.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
        } catch (RuntimeException ignored) { }
    }

    /** 某个账号的设置变了或被移除：丢弃它保留的网页和记录。 */
    void forget(String accountId) {
        if (accountId == null) return;
        WebView old = parked.remove(accountId);
        if (old != null) destroyQuietly(old);
        savedStates.remove(accountId);
        if (hub != null) hub.unregister(accountId);
    }

    /** 关闭当前账号的网页：分流模式下只关当前这个，其它账号的后台网页保留；否则完整关闭。 */
    void resetCurrent() {
        if (!hubActive() || account == null) { close(); return; }
        host.hideVideo();
        if (worker != null) worker.fail("账号环境已关闭，请重试。");
        wantsBrowser = false;
        loading = false;
        notice = "";
        if (view != null) {
            WebView old = view;
            detach();
            view = null;
            destroyQuietly(old);
        }
        WebView kept = parked.remove(account.id);
        if (kept != null) destroyQuietly(kept);
        savedStates.remove(account.id);
        hub.unregister(account.id);
    }

    /** 代理配置被修改。返回 true 表示当前账号的网页已关闭，需要重新打开。 */
    boolean routeEdited(String proxyId, java.util.List<AccountStore.Account> all) {
        if (proxyId == null) return false;
        if (!hubActive()) {
            if (("proxy:" + proxyId).equals(appliedRoute)) { close(); return true; }
            return false;
        }
        boolean current = false;
        for (AccountStore.Account item : all) {
            if (!proxyId.equals(item.proxyId())) continue;
            if (account != null && account.id.equals(item.id)) current = true;
            else forget(item.id);
        }
        if (current) resetCurrent();
        return current;
    }

    /** 释放全部后台网页（记下页面和返回记录）。 */
    void dropParked() { trimParked(0); }

    private static void destroyQuietly(WebView old) {
        try {
            if (old.getParent() instanceof ViewGroup parent) parent.removeView(old);
            old.stopLoading();
            old.destroy();
        } catch (RuntimeException ignored) { }
    }

    void close() {
        routeGeneration++;
        Consumer<String> pendingTransfer = transferRoute;
        transferRoute = null;
        dropParked();
        appliedRoute = null;
        host.hideVideo();
        if (worker != null) worker.fail("账号环境已关闭，请重试。");
        wantsBrowser = false;
        routeReady = false;
        preparing = false;
        usingProxy = false;
        notice = "";
        loading = false;
        if (view != null) {
            WebView old = view;
            String url = old.getUrl();
            if (account != null && StudioDraft.trustedUrl(url) && !StudioDraft.loginPage(url)) lastUrls.put(account.id, url);
            if (account != null) remember(account.id, old);
            detach();
            view = null;
            old.stopLoading();
            old.destroy();
        }
        closeBridge();
        if (hub != null) { hub.close(); hub = null; }
        account = null;
        if (pendingTransfer != null) pendingTransfer.accept("cancelled");
    }


    // ---------- 代理隔离检测 ----------

    static final class CheckRow {
        final AccountStore.Account account;
        final String route;
        String ip, ipError;
        int probes, matched, wrong, missing;

        CheckRow(AccountStore.Account account, String route) { this.account = account; this.route = route; }
        boolean isolated() { return probes > 0 && matched == probes; }
    }

    interface CheckListener {
        void progress(String text);
        /** verdict：1 通过，-1 发现串号（已自动关闭分流），0 未能完成检测。 */
        void done(java.util.List<CheckRow> rows, int verdict, String reason);
    }

    private boolean checking;

    boolean checking() { return checking; }

    /**
     * 逐个账号检测：每个账号的网页环境请求一个只在本机应答的探测地址，分流器记录它出示的是哪个账号的凭证；
     * 正序、倒序各一轮。同时经各账号的路由查询出口 IP。
     */
    void checkIsolation(java.util.List<AccountStore.Account> targets, java.util.function.Function<AccountStore.Account, String> routeName,
                        CheckListener listener) {
        if (checking) { listener.done(null, 0, "检测正在进行。"); return; }
        if (preparing || worker != null) { listener.done(null, 0, "网页正在打开或有后台任务，请稍后再检测。"); return; }
        if (!WebProxyRoute.supported()) { listener.done(null, 0, "当前 WebView 不支持代理设置，请更新 Android System WebView 或 Chrome。"); return; }
        java.util.ArrayList<CheckRow> rows = new java.util.ArrayList<>();
        java.util.ArrayList<ProxySettings> routes = new java.util.ArrayList<>();
        for (AccountStore.Account item : targets) {
            ProxySettings upstream;
            try { upstream = item.proxySettings(proxies); }
            catch (Exception error) { listener.done(null, 0, "无法读取「" + item.name + "」的代理凭据，请重新填写后再检测。"); return; }
            rows.add(new CheckRow(item, routeName.apply(item)));
            routes.add(upstream);
        }
        if (rows.isEmpty()) { listener.done(rows, 0, "没有可检测的账号。"); return; }
        checking = true;
        try {
            if (hub == null) hub = new ProxyHub();
        } catch (IOException | RuntimeException error) {
            checking = false;
            listener.done(null, 0, "无法启动本机代理分流。");
            return;
        }
        for (int index = 0; index < rows.size(); index++) hub.register(rows.get(index).account.id, routes.get(index));
        int[] pending = {2};
        Runnable finish = () -> {
            if (--pending[0] > 0) return;
            checking = false;
            int verdict;
            String reason = null;
            boolean anyWrong = false, anyMissing = false;
            for (CheckRow row : rows) { anyWrong |= row.wrong > 0; anyMissing |= row.missing > 0 || row.probes == 0; }
            if (anyWrong) {
                verdict = -1;
                settings.edit().putBoolean(HUB_DISABLED, true).apply();
                close();
            } else if (anyMissing) {
                verdict = 0;
                reason = "有账号的检测请求没有到达本机分流器。";
            } else {
                verdict = 1;
                settings.edit().putBoolean(HUB_DISABLED, false).apply();
            }
            listener.done(rows, verdict, reason);
        };

        // 出口 IP：同一路由只查一次。
        new Thread(() -> {
            java.util.HashMap<String, String[]> cache = new java.util.HashMap<>();
            for (int index = 0; index < rows.size(); index++) {
                CheckRow row = rows.get(index);
                ProxySettings upstream = routes.get(index);
                String key = upstream == null ? "direct" : upstream.rule() + "|" + upstream.username;
                String[] result = cache.get(key);
                if (result == null) {
                    try { result = new String[]{ProxyHub.exitAddress(upstream), null}; }
                    catch (IOException | RuntimeException error) {
                        result = new String[]{null, error.getMessage() == null ? "无法获取" : error.getMessage()};
                    }
                    cache.put(key, result);
                }
                row.ip = result[0];
                row.ipError = result[1];
            }
            main.post(finish);
        }, "MuseExitCheck").start();

        // 隔离：先把全局代理指向分流器（已打开的网页会按各自账号重新认证），再逐个探测。
        java.util.ArrayList<CheckRow> order = new java.util.ArrayList<>(rows);
        if (multiProfile && rows.size() > 1) {
            java.util.ArrayList<CheckRow> reverse = new java.util.ArrayList<>(rows);
            java.util.Collections.reverse(reverse);
            order.addAll(reverse);
        }
        Runnable probes = () -> runProbe(order, 0, listener, finish);
        if ("hub".equals(appliedRoute)) probes.run();
        else {
            ProxyHub router = hub;
            listener.progress("正在切换到本机代理分流…");
            WebProxyRoute.apply(router.rule(), error -> {
                if (hub != router) { checking = false; return; }
                if (error != null) {
                    for (CheckRow row : rows) row.missing++;
                    finish.run();
                    return;
                }
                appliedRoute = "hub";
                probes.run();
            });
        }
    }

    private void runProbe(java.util.List<CheckRow> order, int index, CheckListener listener, Runnable finish) {
        if (index >= order.size() || hub == null) { finish.run(); return; }
        CheckRow row = order.get(index);
        listener.progress("正在检测「" + row.account.name + "」（" + (index + 1) + " / " + order.size() + "）…");
        String token = "t" + java.util.UUID.randomUUID().toString().replace("-", "");
        WebView probe;
        try {
            probe = createView(new RouteClient(), false, row.account);
            probe.setFocusable(false);
            workerHost.addView(probe, new FrameLayout.LayoutParams(-1, -1));
            workerHost.setVisibility(View.VISIBLE);
        } catch (RuntimeException error) {
            row.probes++;
            row.missing++;
            main.post(() -> runProbe(order, index + 1, listener, finish));
            return;
        }
        boolean[] settled = {false};
        ProxyHub router = hub;
        Runnable next = () -> {
            router.cancelProbe(token);
            workerHost.removeView(probe);
            if (worker == null || worker.web == null) workerHost.setVisibility(View.GONE);
            destroyQuietly(probe);
            runProbe(order, index + 1, listener, finish);
        };
        Runnable timeout = () -> {
            if (settled[0]) return;
            settled[0] = true;
            row.probes++;
            row.missing++;
            next.run();
        };
        router.expectProbe(token, (seen, accountId) -> main.post(() -> {
            if (settled[0]) return;
            settled[0] = true;
            main.removeCallbacks(timeout);
            row.probes++;
            if (row.account.id.equals(accountId)) row.matched++; else row.wrong++;
            next.run();
        }));
        main.postDelayed(timeout, 12_000);
        probe.loadUrl("https://" + token + ProxyHub.PROBE_SUFFIX + "/");
    }

    private void closeBridge() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    private class RouteClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
            return request.isForMainFrame() && !StudioDraft.webUrl(request.getUrl().toString());
        }

        @Override public void onReceivedHttpAuthRequest(WebView web, HttpAuthHandler handler, String host, String realm) {
            ProxyHub router = hub;
            if (router != null && router.matchesChallenge(host, realm)) {
                // 分流器：每个网页只用它所属账号的本机凭证。
                AccountStore.Account owner = owners.get(web);
                if (owner == null) { handler.cancel(); return; }
                try {
                    ProxyHub.Credential credential = router.register(owner.id, owner.proxySettings(proxies));
                    handler.proceed(credential.username, credential.secret);
                } catch (Exception error) {
                    handler.cancel();
                }
                return;
            }
            LoopbackProxy route = bridge;
            if ((web == view || parked.containsValue(web) || (worker != null && web == worker.web))
                    && route != null && route.matchesChallenge(host, realm)) {
                handler.proceed(route.username, route.secret);
            } else {
                handler.cancel();
            }
        }
    }

    private final class Client extends RouteClient {
        @Override public void onPageStarted(WebView web, String url, android.graphics.Bitmap favicon) {
            if (web != view) return;
            microphone.cancelPending();
            loading = true;
            host.browserChanged();
        }

        @Override public void onPageFinished(WebView web, String url) {
            if (web != view || !url.equals(web.getUrl())) return;
            loading = false;
            host.browserChanged();
        }

        @Override public void onReceivedError(WebView web, WebResourceRequest request, WebResourceError error) {
            if (web != view || !request.isForMainFrame()) return;
            loading = false;
            notice = usingProxy ? "网页加载失败，请检查账号代理后重试。" : "网页加载失败，请检查网络后刷新。";
            host.browserChanged();
        }

        @Override public boolean onRenderProcessGone(WebView web, RenderProcessGoneDetail detail) {
            if (web == view) {
                detach();
                view = null;
                web.destroy();
                wantsBrowser = false;
                loading = false;
                notice = "网页进程已关闭，点「刷新」重新打开。";
                host.browserChanged();
            } else if (parked.values().remove(web)) {
                // 后台网页的渲染进程被系统回收：只丢弃这个网页，切回时重新加载。
                destroyQuietly(web);
            }
            return true;
        }
    }

    final class WorkPage {
        private final String initial, script;
        private final Consumer<String> failure;
        private WebView web;
        private boolean loaded, installed, loginRequired;
        private int navigation;

        WorkPage(String initial, String script, Consumer<String> failure) {
            this.initial = initial;
            this.script = script;
            this.failure = failure;
        }

        private void start() {
            try {
                web = createView(new RouteClient() {
                    @Override public void onPageStarted(WebView page, String url, android.graphics.Bitmap icon) {
                        if (web != page) return;
                        navigation++;
                        loaded = installed = false;
                        loginRequired = false;
                    }

                    @Override public void onPageFinished(WebView page, String url) {
                        if (web != page || !url.equals(page.getUrl())) return;
                        loaded = true;
                        if (needsLogin() || !StudioDraft.trustedUrl(url)) return;
                        int revision = navigation;
                        page.evaluateJavascript(script + "\n;true", value -> {
                            if (web == page && revision == navigation && StudioDraft.trustedUrl(page.getUrl())) installed = "true".equals(value);
                        });
                    }

                    @Override public void onReceivedError(WebView page, WebResourceRequest request, WebResourceError error) {
                        if (page == web && request.isForMainFrame()) fail(usingProxy ? "后台网页未能打开，请检查账号代理。" : "后台网页未能打开，请检查网络。");
                    }

                    @Override public void onReceivedHttpError(WebView page, WebResourceRequest request, WebResourceResponse error) {
                        if (page == web && request.isForMainFrame() && error.getStatusCode() == 401) loginRequired = true;
                    }

                    @Override public boolean onRenderProcessGone(WebView page, RenderProcessGoneDetail detail) {
                        if (page == web) fail("后台网页已关闭，请重试。");
                        return true;
                    }
                }, false);
                web.setFocusable(false);
                workerHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
                workerHost.setVisibility(View.VISIBLE);
                web.loadUrl(initial);
            } catch (RuntimeException error) {
                fail("无法打开后台网页，请更新 Android System WebView 后重试。");
            }
        }

        boolean needsLogin() {
            return web != null && (loginRequired || (loaded && (!StudioDraft.trustedUrl(web.getUrl()) || StudioDraft.loginPage(web.getUrl()))));
        }

        boolean ready() {
            return installed && web != null && StudioDraft.trustedUrl(web.getUrl()) && !StudioDraft.loginPage(web.getUrl());
        }

        String url() { return web == null ? null : web.getUrl(); }

        void evaluate(String expression, Consumer<String> answer) {
            if (!ready()) { answer.accept(null); return; }
            WebView page = web;
            int revision = navigation;
            page.evaluateJavascript("(()=>{if(window!==window.top||!['https://muse.ai','https://www.muse.ai'].includes(location.origin))return null;try{return "
                + expression + ";}catch{return null;}})()", result -> {
                if (web == page && revision == navigation && ready()) answer.accept(result);
                else answer.accept(null);
            });
        }

        void close() {
            if (worker != this) return;
            worker = null;
            if (web == null) return;
            WebView old = web;
            web = null;
            workerHost.removeView(old);
            workerHost.setVisibility(View.GONE);
            old.stopLoading();
            old.destroy();
        }

        private void fail(String message) {
            close();
            failure.accept(message);
        }
    }

    private final class Chrome extends WebChromeClient {
        private final WebView page;

        Chrome(WebView page) { this.page = page; }

        @Override public void onPermissionRequest(PermissionRequest request) {
            if (page == view) microphone.request(page, request);
            else request.deny();
        }

        @Override public void onPermissionRequestCanceled(PermissionRequest request) {
            microphone.webCanceled(request);
        }

        @Override public boolean onShowFileChooser(WebView web, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (web != view || !StudioDraft.trustedUrl(web.getUrl())) return false;
            return host.chooseFile(callback, params);
        }

        @Override public void onShowCustomView(View video, CustomViewCallback callback) {
            if (page != view) { callback.onCustomViewHidden(); return; }
            host.showVideo(video, callback);
        }

        @Override public void onHideCustomView() {
            host.hideVideo();
        }
    }
}
