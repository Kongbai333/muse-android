package com.muse.workspace.mobile;

import java.util.UUID;

final class StudioSession {
    static final int TITLE_LIMIT = 28;
    final String id;
    final String owner;
    final String ownerName;
    String name;
    String url;
    String draft = "";
    String ratio = "Auto";
    boolean video;
    boolean hasReference;
    /** 待发送参考图数量（0–10）；hasReference 与之同步，供旧数据兼容。 */
    int referenceCount;
    int composerHeightDp = ComposerHeight.DEFAULT_DP;
    int seconds = 5;
    long created;
    long updated;

    private StudioSession(String id, String owner, String ownerName, String name, String url, long created) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.name = name;
        this.url = url;
        this.created = created;
        this.updated = created;
    }

    static StudioSession create(String owner, String ownerName, long now) {
        return new StudioSession(UUID.randomUUID().toString(), owner, ownerName, "新会话", StudioDraft.NEW_THREAD, now);
    }

    static StudioSession restore(String id, String owner, String ownerName, String name, String url, long created) {
        StudioSession session = new StudioSession(id, owner, ownerName,
            clean(name, "新会话"), StudioDraft.trustedUrl(url) ? url : StudioDraft.NEW_THREAD, created);
        return session;
    }

    static String defaultId(String owner) { return "default-" + owner; }

    static StudioSession defaultSession(String owner, String ownerName, long now) {
        return new StudioSession(defaultId(owner), owner, ownerName, "默认会话", StudioDraft.NEW_THREAD, now);
    }

    boolean belongsTo(String account) { return owner.equals(account); }

    void setReference(boolean attached, long now) {
        setReferenceCount(attached ? Math.max(1, referenceCount) : 0, now);
    }

    void setReferenceCount(int count, long now) {
        referenceCount = Math.max(0, Math.min(StudioReferenceStore.MAX_REFERENCES, count));
        hasReference = referenceCount > 0;
        touch(now);
    }

    void setComposerHeight(int heightDp, long now) {
        composerHeightDp = ComposerHeight.clamp(heightDp, 720);
        touch(now);
    }

    void adoptPrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.isEmpty() || !"新会话".equals(name)) return;
        name = value.length() <= TITLE_LIMIT ? value : value.substring(0, TITLE_LIMIT - 1) + "…";
    }

    void touch(long now) { updated = Math.max(updated, now); }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String clean = value.trim();
        return clean.isEmpty() || clean.length() > TITLE_LIMIT ? fallback : clean;
    }
}
