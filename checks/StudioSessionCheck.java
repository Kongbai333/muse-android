package com.muse.workspace.mobile;

public final class StudioSessionCheck {
    public static void main(String[] args) {
        StudioSession session = StudioSession.create("account-1", "主账号", 1_700_000_000_000L);
        require("新会话".equals(session.name), "新会话必须有明确的默认标题");
        session.adoptPrompt("  夜晚的城市，霓虹灯和雨  ");
        require("夜晚的城市，霓虹灯和雨".equals(session.name), "会话标题应取首条提示词");
        require(session.name.length() <= StudioSession.TITLE_LIMIT, "会话标题不能撑破手机顶部");
        require(session.url.equals(StudioDraft.NEW_THREAD), "新会话必须从新的 Muse 对话开始");
        session.setReference(true, 1_700_000_000_100L);
        require(session.hasReference, "会话必须保留待发送参考图状态");
        require(StudioSession.defaultId("account-1").equals(StudioSession.defaultId("account-1")),
            "旧历史的默认会话 ID 必须稳定");
        System.out.println("Muse studio session model passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
