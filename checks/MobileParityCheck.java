package com.muse.workspace.mobile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class MobileParityCheck {
    public static void main(String[] args) throws Exception {
        long now = 1_800_000_000_000L;
        AccountQuota limited = AccountQuota.read("Plus", "known", 0., now - 10_000, now + 60_000,
            0., 200., null, now);
        require(limited != null && limited.weekly(now).equals("已用 0%"), "实际零额度不能显示成未知");
        require(limited.invitation(now).equals("剩余 0 / 200"), "邀请额度必须单独使用站点的数值");
        require(limited.weekly(now + 60_001).equals("旧周期 · 已用 0%"), "周额度重置后保留旧值但必须明确旧周期");
        require(limited.invitation(now + 60_001).equals("剩余 0 / 200"), "周重置不能清空邀请额度");
        require(AccountQuota.read("Plus", "known", 101., now, 0, null, null, null, now) == null,
            "无效用量不能缓存");

        Path folder = Files.createTempDirectory(Path.of(args[0]), "media-");
        Path finished = folder.resolve("sample.png");
        try (StudioMediaTransfer transfer = new StudioMediaTransfer(finished.toFile(), 4)) {
            transfer.write(0, new byte[] {1, 2}, false);
            try {
                transfer.write(2, new byte[] {3}, true);
                throw new AssertionError("被截断的媒体不能提前提交");
            } catch (IOException expected) { }
            require(!Files.exists(finished), "未完成媒体不能显示在历史中");
            transfer.write(2, new byte[] {3, 4}, true);
            transfer.commit();
        }
        require(Arrays.equals(Files.readAllBytes(finished), new byte[] {1, 2, 3, 4}), "完成的文件必须与收到的字节一致");
        Files.delete(finished);
        Files.delete(folder);
        System.out.println("Account quota and studio media checks passed.");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
