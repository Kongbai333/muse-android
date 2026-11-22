package com.muse.workspace.mobile;

/** 工作室输入框高度（dp）：最低一行，最高占屏幕高度的 60%。 */
final class ComposerHeight {
    static final int MIN_DP = 36;
    static final int DEFAULT_DP = 76;
    private static final float MAX_SHARE = 0.6f;

    static int clamp(int requestedDp, int viewportDp) {
        int max = Math.max(MIN_DP, Math.round(viewportDp * MAX_SHARE));
        return Math.max(MIN_DP, Math.min(max, requestedDp));
    }
}
