package com.muse.workspace.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;
import static com.muse.workspace.mobile.MobileUi.*;

/** 新增 / 编辑代理：底部弹窗。测试结果直接显示在表单里；有账号引用时删除会说明原因。 */
final class ProxyEditor {
    static void show(Activity activity, MobileUi ui, ProxyProfile existing, ProxyStore proxies, AccountStore accounts,
            Consumer<String> saved, Runnable remove) {
        MobileUi.Sheet sheet = ui.sheet(activity, existing == null ? "新增代理" : "编辑代理",
            existing == null ? "一条配置可以被多个账号共用。" : "修改后，使用它的账号会在下次打开网页时生效。");

        String[] schemes = {"http", "https", "socks5"};
        String[] schemeLabels = {"HTTP", "HTTPS", "SOCKS5"};
        String[] defaultPorts = {"8080", "443", "1080"};
        int initial = 0;
        if (existing != null) for (int i = 0; i < schemes.length; i++) if (schemes[i].equals(existing.scheme)) initial = i;
        int[] selectedScheme = {initial};

        EditText title = ui.input("例如：家用代理", InputType.TYPE_CLASS_TEXT, existing == null ? 48 : Math.max(48, existing.name.length()));
        if (existing != null) title.setText(existing.name);
        EditText host = ui.input("127.0.0.1", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 280);
        host.setTypeface(Typeface.MONOSPACE);
        EditText port = ui.input("端口", InputType.TYPE_CLASS_NUMBER, 5);
        port.setTypeface(Typeface.MONOSPACE);
        EditText user = ui.input("可选", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 1024);
        EditText pass = ui.input("可选", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 4096);
        port.setText(defaultPorts[initial]);

        TextView status = ui.note("用户名与密码保存在设备加密存储中。", 0);
        Consumer<String[]> say = message -> {
            int kind = "ok".equals(message[0]) ? 1 : "warn".equals(message[0]) ? 2 : 0;
            status.setText(message[1]);
            status.setTextColor(kind == 0 ? TEXT2 : INK);
            status.setBackground(ui.shape(kind == 2 ? ACCENT_SOFT : RAISED, 9, LINE));
        };

        TextView paste = ui.small("粘贴完整代理地址", 0, false, () -> { });
        LinearLayout pasteRow = ui.row();
        pasteRow.addView(paste, new LinearLayout.LayoutParams(-2, ui.dp(34)));
        sheet.add(pasteRow, 0);
        sheet.add(ui.field(title, "代理名称"), 16);

        LinearLayout protocolBox = ui.column();
        protocolBox.addView(ui.text("代理协议", 12.5f, TEXT2, false));
        MobileUi.Segment[] holder = new MobileUi.Segment[1];
        holder[0] = ui.segment(schemeLabels, null, true, initial, which -> {
            if (defaultPorts[selectedScheme[0]].equals(port.getText().toString())) port.setText(defaultPorts[which]);
            selectedScheme[0] = which;
            holder[0].select(which);
        });
        LinearLayout.LayoutParams segmentPosition = new LinearLayout.LayoutParams(-1, -2);
        segmentPosition.topMargin = ui.dp(7);
        protocolBox.addView(holder[0].view, segmentPosition);
        sheet.add(protocolBox, 14);

        LinearLayout address = ui.row();
        address.setGravity(android.view.Gravity.TOP);
        address.addView(ui.field(host, "服务器地址"), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams portPosition = new LinearLayout.LayoutParams(ui.dp(104), -2);
        portPosition.setMarginStart(ui.dp(10));
        address.addView(ui.field(port, "端口"), portPosition);
        sheet.add(address, 14);

        LinearLayout credentials = ui.row();
        credentials.setGravity(android.view.Gravity.TOP);
        credentials.addView(ui.field(user, "用户名（可选）"), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams passPosition = new LinearLayout.LayoutParams(0, -2, 1);
        passPosition.setMarginStart(ui.dp(10));
        credentials.addView(ui.secretField(pass, "密码（可选）"), passPosition);
        sheet.add(credentials, 14);
        sheet.add(status, 14);

        if (existing != null) {
            ProxySettings preset = null;
            try { preset = existing.settings(); }
            catch (Exception ignored) { say.accept(new String[]{"warn", "无法解密现有凭据，请重新填写。"}); }
            if (preset != null) fill(preset, host, port, user, pass);
            if (remove != null) {
                TextView delete = ui.text("删除代理配置", 13.5f, ACCENT, true);
                delete.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(4));
                delete.setFocusable(true);
                delete.setClickable(true);
                delete.setOnClickListener(view -> {
                    int count = accounts.referenceCount(existing.id);
                    if (count > 0) {
                        say.accept(new String[]{"warn", "仍有 " + count + " 个账号在用这条代理（"
                            + ProxyManagerPage.referenceNames(existing, accounts) + "）。先在这些账号的编辑页改用其他代理，再回来删除。"});
                        return;
                    }
                    MobileUi.dialog(activity).setTitle("删除代理配置？")
                        .setMessage("「" + existing.name + "」将从设备的代理列表中移除。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (dialog, which) -> { sheet.dismiss(); remove.run(); }).show();
                });
                LinearLayout deleteRow = ui.row();
                deleteRow.addView(delete);
                sheet.add(deleteRow, 6);
            }
        }

        paste.setOnClickListener(view -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
            if (text == null || text.toString().trim().isEmpty()) { say.accept(new String[]{"warn", "剪贴板没有代理地址。"}); return; }
            try {
                ProxySettings settings = ProxySettings.parse(text.toString());
                int protocol = java.util.Arrays.asList(schemes).indexOf(settings.scheme);
                if (protocol >= 0) { selectedScheme[0] = protocol; holder[0].select(protocol); }
                fill(settings, host, port, user, pass);
                say.accept(new String[]{"", "已填写代理地址，可以测试连接或保存。"});
            } catch (IllegalArgumentException error) { say.accept(new String[]{"warn", error.getMessage()}); }
        });

        TextView test = ui.outline("测试连接", () -> { });
        test.setOnClickListener(view -> {
            ProxySettings settings;
            try { settings = read(schemes[selectedScheme[0]], host, port, user, pass); }
            catch (IllegalArgumentException error) { say.accept(new String[]{"warn", error.getMessage()}); return; }
            test.setEnabled(false);
            test.setAlpha(0.5f);
            say.accept(new String[]{"", "正在测试连接与认证…"});
            long started = System.currentTimeMillis();
            new Thread(() -> {
                String result;
                boolean passed = false;
                try {
                    LoopbackProxy.probe(settings);
                    passed = true;
                    result = "连接与认证通过 · " + (System.currentTimeMillis() - started) + " ms，可以保存。";
                }
                catch (LoopbackProxy.Rejected error) { result = error.getMessage(); }
                catch (java.io.IOException | RuntimeException error) { result = "连接失败，请检查协议、服务器、端口和认证信息。"; }
                String message = result;
                boolean ok = passed;
                activity.runOnUiThread(() -> {
                    if (!status.isAttachedToWindow()) return;
                    say.accept(new String[]{ok ? "ok" : "warn", message});
                    test.setEnabled(true);
                    test.setAlpha(1f);
                });
            }, "MuseProxyCheck").start();
        });
        TextView save = ui.primary("保存", () -> { });
        save.setOnClickListener(view -> {
            String name = title.getText().toString().trim();
            if (name.isEmpty()) { title.setError("请填写代理名称"); say.accept(new String[]{"warn", "请填写代理名称。"}); return; }
            try {
                ProxySettings settings = read(schemes[selectedScheme[0]], host, port, user, pass);
                if (existing == null) saved.accept(proxies.add(name, settings));
                else { proxies.update(existing, name, settings); saved.accept(existing.id); }
                sheet.dismiss();
            } catch (Exception error) {
                say.accept(new String[]{"warn", error.getMessage() == null ? "无法保存代理配置，请重试。" : error.getMessage()});
            }
        });
        sheet.action(test, true);
        sheet.action(save, true);
        sheet.show();
    }

    private static void fill(ProxySettings settings, EditText host, EditText port, EditText user, EditText pass) {
        host.setText(settings.host); port.setText(Integer.toString(settings.port));
        user.setText(settings.username); pass.setText(settings.secret);
    }

    private static ProxySettings read(String scheme, EditText host, EditText port, EditText user, EditText pass) {
        return ProxySettings.create(scheme, host.getText().toString(), port.getText().toString(),
            user.getText().toString(), pass.getText().toString());
    }
}
