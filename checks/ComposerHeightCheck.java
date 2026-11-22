package com.muse.workspace.mobile;

public final class ComposerHeightCheck {
    public static void main(String[] args) {
        require(ComposerHeight.clamp(10, 800) == ComposerHeight.MIN_DP, "Input height can shrink to a single line, not below");
        require(ComposerHeight.clamp(48, 800) == 48, "Small saved heights must be kept");
        require(ComposerHeight.clamp(900, 800) == 480, "Input height may grow to 60% of the viewport");
        require(ComposerHeight.clamp(500, 400) == 240, "Input height must fit the available phone viewport");
        require(ComposerHeight.clamp(120, 800) == 120, "Saved in-range input height must be preserved");
        System.out.println("Resizable composer height bounds passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
