package com.muse.workspace.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class ProxyStore {
    private final SharedPreferences preferences;
    private final ArrayList<ProxyProfile> profiles = new ArrayList<>();

    ProxyStore(Context context) {
        preferences = context.getSharedPreferences("muse-proxy-profiles", Context.MODE_PRIVATE);
        String raw = preferences.getString("profiles", "[]");
        try {
            JSONArray stored = new JSONArray(raw);
            for (int index = 0; index < stored.length(); index++) {
                JSONObject item = stored.optJSONObject(index);
                if (item == null) continue;
                try {
                    String id = UUID.fromString(item.getString("id")).toString();
                    String name = item.getString("name").trim();
                    ProxySettings settings = ProxySettings.create(item.optString("scheme", "http"), item.getString("host"),
                        Integer.toString(item.getInt("port")), item.optString("username"), "");
                    if (name.isEmpty()) continue;
                    profiles.add(new ProxyProfile(id, name, settings, item.optString("credential", "")));
                } catch (JSONException | IllegalArgumentException ignored) { }
            }
        } catch (JSONException ignored) { }
    }

    List<ProxyProfile> all() { return Collections.unmodifiableList(profiles); }

    ProxyProfile find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (ProxyProfile profile : profiles) if (id.equals(profile.id)) return profile;
        return null;
    }

    ProxySettings settings(String id) throws GeneralSecurityException, IOException {
        ProxyProfile profile = find(id);
        if (profile == null) throw new IOException("找不到已保存的代理配置。");
        return profile.settings();
    }

    String add(String name, ProxySettings settings) throws GeneralSecurityException, IOException {
        return add(UUID.randomUUID().toString(), name, settings);
    }

    String importLegacy(String accountId, String accountName, ProxySettings settings)
            throws GeneralSecurityException, IOException {
        String id = UUID.nameUUIDFromBytes(("muse-legacy-proxy:" + accountId).getBytes(StandardCharsets.UTF_8)).toString();
        if (find(id) != null) return id;
        return add(id, "旧账号代理 · " + accountName, settings);
    }

    private String add(String id, String name, ProxySettings settings) throws GeneralSecurityException, IOException {
        ProxyProfile profile = new ProxyProfile(id, cleanName(name), settings, sealCredentials(id, settings));
        profiles.add(profile);
        if (!persist()) {
            profiles.remove(profile);
            throw new IOException("无法保存代理配置。");
        }
        return id;
    }

    void update(ProxyProfile profile, String name, ProxySettings settings) throws GeneralSecurityException, IOException {
        ProxyProfile old = find(profile.id);
        if (old == null) throw new IOException("代理配置已移除，请刷新页面。");
        String previousName = old.name, previousScheme = old.scheme, previousHost = old.host,
            previousCredential = old.credential;
        int previousPort = old.port;
        String nextCredential = sealCredentials(old.id, settings);
        old.name = cleanName(name);
        old.scheme = settings.scheme;
        old.host = settings.host;
        old.port = settings.port;
        old.credential = nextCredential;
        if (persist()) return;
        old.name = previousName; old.scheme = previousScheme; old.host = previousHost; old.port = previousPort;
        old.credential = previousCredential;
        throw new IOException("无法保存代理配置。");
    }

    void remove(ProxyProfile profile, AccountStore accounts) throws IOException {
        if (accounts.referenceCount(profile.id) != 0) throw new IOException("仍有账号引用此代理，无法删除。");
        if (!profiles.remove(profile)) return;
        if (!persist()) {
            profiles.add(profile);
            throw new IOException("无法删除代理配置。");
        }
    }

    private boolean persist() {
        JSONArray saved = new JSONArray();
        try {
            for (ProxyProfile profile : profiles) saved.put(new JSONObject().put("id", profile.id)
                .put("name", profile.name).put("scheme", profile.scheme).put("host", profile.host)
                .put("port", profile.port).put("credential", profile.credential));
        } catch (JSONException error) { return false; }
        return preferences.edit().putString("profiles", saved.toString()).commit();
    }

    private static String sealCredentials(String id, ProxySettings settings)
            throws GeneralSecurityException, IOException {
        return settings.hasAuthentication()
            ? ProxySecrets.seal(id, settings.username + ":" + settings.secret) : "";
    }

    private static String cleanName(String name) throws IOException {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 48 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("代理名称必须为 1–48 个字符。");
        }
        return value;
    }
}
