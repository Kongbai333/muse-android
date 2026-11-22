package com.muse.workspace.mobile;

public final class StudioDraftCheck {
    public static void main(String[] args) {
        require("请直接生成一张全新图片，画幅比例 16:9。内容：雨天的街道"
            .equals(StudioDraft.format(false, "雨天的街道", "16:9", 5)));
        require("请直接生成一个全新视频，不指定画幅比例，时长约 8 秒。内容：雨天的街道"
            .equals(StudioDraft.format(true, "雨天的街道", "Auto", 8)));
        require(StudioDraft.format(false, "  ", "Auto", 5).isEmpty());
        require(StudioDraft.trustedUrl("https://muse.ai/thread/new"));
        require(StudioDraft.trustedUrl("https://www.muse.ai/"));
        require(!StudioDraft.trustedUrl("https://muse.ai.evil.example/"));
        require(!StudioDraft.trustedUrl("https://muse.ai@other.example/"));
        require(!StudioDraft.trustedUrl("https://muse.ai:444/"));
        require(!StudioDraft.trustedUrl("http://muse.ai/"));
        require(StudioDraft.newThreadUrl("https://muse.ai/thread/new"));
        require(!StudioDraft.newThreadUrl("https://muse.ai/login?return=/thread/new"));
        System.out.println("Muse prompt and draft origin checks passed.");
    }

    private static void require(boolean correct) {
        if (!correct) throw new AssertionError("Studio draft contract did not hold");
    }
}
