package com.muse.workspace.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class AccountStore {
    static final class Account {
        final String id;
        String name;
        private String proxyId = "";
        private JSONObject legacyProxy;
        Boolean transferSourceForceProxy;

        Account(String id, String name) {
            this.id = id;
            this.name = name;
        }

        String profileName() {
            return "muse-" + id;
        }

        boolean hasProxy() { return !proxyId.isEmpty() || legacyProxy != null; }
        String proxyId() { return proxyId; }

        ProxySettings proxySettings(ProxyStore store) throws GeneralSecurityException, IOException, JSONException {
            if (!proxyId.isEmpty()) return store.settings(proxyId);
            if (legacyProxy == null) return null;
            return ProxySettings.create(legacyProxy.optString("scheme", "http"), legacyProxy.getString("host"),
                legacyProxy.getString("port"), legacyProxy.getString("username"), ProxySecrets.open(id, legacyProxy.getString("credential")));
        }

        String networkLabel(ProxyStore store) {
            ProxyProfile profile = store.find(proxyId);
            if (profile != null) return profile.scheme.toUpperCase(java.util.Locale.ROOT) + " 代理";
            return hasProxy() ? "HTTP 代理" : "系统网络";
        }
    }

    private final SharedPreferences preferences;
    private final ProxyStore proxies;
    private final ArrayList<Account> accounts = new ArrayList<>();
    private String selected;
    private String fallbackId;

    AccountStore(Context context, ProxyStore proxies) {
        this.proxies = proxies;
        preferences = context.getSharedPreferences("muse-accounts", Context.MODE_PRIVATE);
        selected = preferences.getString("selected", "");
        fallbackId = preferences.getString("fallback", "");
        String stored = preferences.getString("accounts", null);
        if (stored == null) {
            Account first = new Account(UUID.randomUUID().toString(), "主账号");
            accounts.add(first);
            selected = fallbackId = first.id;
            save();
            return;
        }
        boolean migrated = false;
        try {
            JSONArray data = new JSONArray(stored);
            for (int index = 0; index < data.length(); index++) {
                JSONObject item = data.getJSONObject(index);
                String id = UUID.fromString(item.getString("id")).toString();
                String name = item.getString("name").trim();
                if (!name.isEmpty()) {
                    Account account = new Account(id, name);
                    account.proxyId = item.optString("proxyId", "");
                    if (item.opt("transferSourceForceProxy") instanceof Boolean value) account.transferSourceForceProxy = value;
                    if (account.proxyId.isEmpty() && item.has("proxy")) {
                        account.legacyProxy = item.optJSONObject("proxy");
                        if (account.legacyProxy != null) {
                            try {
                                ProxySettings legacy = account.proxySettings(proxies);
                                account.proxyId = proxies.importLegacy(account.id, account.name, legacy);
                                account.legacyProxy = null;
                                migrated = true;
                            } catch (IOException | GeneralSecurityException | JSONException ignored) { }
                        }
                    }
                    accounts.add(account);
                }
            }
        } catch (JSONException | IllegalArgumentException ignored) {
            accounts.clear();
        }
        if (migrated) save();
    }

    List<Account> all() {
        return Collections.unmodifiableList(accounts);
    }

    Account current(boolean multiProfile) {
        Account firstAvailable = null;
        for (Account account : accounts) {
            if (!usable(account, multiProfile)) continue;
            if (account.id.equals(selected)) return account;
            if (firstAvailable == null) firstAvailable = account;
        }
        return firstAvailable;
    }

    boolean usable(Account account, boolean multiProfile) {
        return multiProfile || account.id.equals(fallbackId);
    }

    void add(String name, String proxyId) throws IOException {
        Account account = new Account(UUID.randomUUID().toString(), name);
        account.proxyId = proxyId == null ? "" : proxyId;
        String previous = selected;
        accounts.add(account);
        selected = account.id;
        if (!saveAccounts().commit()) {
            accounts.remove(account);
            selected = previous;
            save();
            throw new IOException("无法保存账号");
        }
    }

    void select(Account account) {
        selected = account.id;
        save();
    }

    /** An import candidate has a fresh UUID but is deliberately absent from all()/current(). */
    static Account candidate(AccountTransferFlow.Candidate candidate) {
        Account account = new Account(UUID.fromString(candidate.id).toString(), candidate.source.name);
        account.proxyId = candidate.proxyId == null ? "" : candidate.proxyId;
        account.transferSourceForceProxy = candidate.source.sourceForceProxy;
        return account;
    }

    /** One persistence boundary after route + isolated Cookie callbacks + flush have completed. */
    void activateImported(List<Account> candidates) throws IOException {
        if (candidates.isEmpty()) throw new IOException("没有可激活的账号。");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (Account existing : accounts) ids.add(existing.id);
        for (Account candidate : candidates) if (!ids.add(candidate.id)) throw new IOException("导入账号标识重复。");
        String previous = selected;
        accounts.addAll(candidates);
        selected = candidates.get(0).id;
        if (saveAccounts().commit()) return;
        accounts.removeAll(candidates);
        selected = previous;
        // Restore the in-process preferences cache as well as the native list on failed commit.
        saveAccounts().commit();
        throw new IOException("无法保存导入账号，账号未激活。");
    }

    void update(Account account, String name, String proxyId, boolean routeEdited) throws IOException {
        String previous = account.proxyId;
        JSONObject oldLegacy = account.legacyProxy;
        String previousName = account.name;
        if (routeEdited) {
            account.proxyId = proxyId == null ? "" : proxyId;
            account.legacyProxy = null;
        }
        account.name = name;
        if (!saveAccounts().commit()) {
            account.proxyId = previous;
            account.legacyProxy = oldLegacy;
            account.name = previousName;
            save();
            throw new IOException("无法保存账号设置");
        }
    }

    int referenceCount(String proxyId) {
        int count = 0;
        for (Account account : accounts) if (proxyId.equals(account.proxyId)) count++;
        return count;
    }

    void remove(Account account) {
        accounts.remove(account);
        // 删除列表项不复用旧 WebView 配置，避免新账号继承旧登录。
        if (selected.equals(account.id)) selected = "";
        save();
    }

    private void save() {
        saveAccounts().apply();
    }

    private SharedPreferences.Editor saveAccounts() {
        JSONArray list = new JSONArray();
        try {
            for (Account account : accounts) {
                JSONObject item = new JSONObject().put("id", account.id).put("name", account.name);
                if (!account.proxyId.isEmpty()) item.put("proxyId", account.proxyId);
                else if (account.legacyProxy != null) item.put("proxy", account.legacyProxy);
                if (account.transferSourceForceProxy != null) item.put("transferSourceForceProxy", account.transferSourceForceProxy);
                list.put(item);
            }
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
        return preferences.edit().putString("accounts", list.toString())
            .putString("selected", selected).putString("fallback", fallbackId);
    }
}
