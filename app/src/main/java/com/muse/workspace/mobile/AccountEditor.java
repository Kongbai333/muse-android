package com.muse.workspace.mobile;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import static com.muse.workspace.mobile.MobileUi.*;

/** 添加 / 编辑账号：底部弹窗，代理直接在列表里选择，显示每条代理已关联的账号数。 */
final class AccountEditor {
    interface Save { void account(String name, String proxyId) throws Exception; }

    static void show(Activity activity, MobileUi ui, AccountStore.Account account, Runnable remove,
            ProxyStore proxies, AccountStore accounts, Runnable manage, Save save) {
        MobileUi.Sheet sheet = ui.sheet(activity, account == null ? "添加账号" : "编辑账号",
            account == null ? "保存后打开网页登录 Muse，再点卡片上的刷新同步头像和套餐。"
                : "改动的代理会在下次打开这个账号的网页时生效。");
        EditText name = ui.input("例如：工作号", InputType.TYPE_CLASS_TEXT, account == null ? 36 : Math.max(36, account.name.length()));
        if (account != null) name.setText(account.name);
        sheet.add(ui.field(name, "账号名称"), 0);

        List<ProxyProfile> available = proxies.all();
        String[] selected = {account == null ? "" : account.proxyId()};
        if (!selected[0].isEmpty() && proxies.find(selected[0]) == null) selected[0] = "";
        sheet.add(ui.text("网络代理", 12.5f, TEXT2, false), 18);
        LinearLayout options = ui.column();
        sheet.add(options, 7);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            options.removeAllViews();
            View globe = ui.iconView(R.drawable.ic_globe, TEXT2, 20);
            ui.block(options, ui.option(globe, "系统网络", "不使用代理", selected[0].isEmpty(), true, () -> {
                selected[0] = ""; render[0].run();
            }), 0);
            for (ProxyProfile profile : available) {
                TextView tag = ProxyManagerPage.protocolTag(ui, profile.scheme);
                int count = accounts.referenceCount(profile.id);
                ui.block(options, ui.option(tag, profile.name, "已关联 " + count + " 个账号",
                    profile.id.equals(selected[0]), true, () -> { selected[0] = profile.id; render[0].run(); }), 8);
            }
        };
        render[0].run();

        TextView manageButton = ui.text("管理代理配置", 13.5f, INK, false);
        manageButton.setPaintFlags(manageButton.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        manageButton.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(4));
        manageButton.setFocusable(true);
        manageButton.setClickable(true);
        manageButton.setOnClickListener(view -> { sheet.dismiss(); manage.run(); });
        LinearLayout links = ui.row();
        links.addView(manageButton);
        if (remove != null) {
            links.addView(ui.spacer());
            TextView delete = ui.text("移除账号", 13.5f, ACCENT, true);
            delete.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(4));
            delete.setFocusable(true);
            delete.setClickable(true);
            delete.setOnClickListener(view -> { sheet.dismiss(); remove.run(); });
            links.addView(delete);
        }
        sheet.add(links, 12);

        sheet.action(ui.outline("取消", sheet::dismiss), true);
        sheet.action(ui.primary("保存", () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError("请输入账号名称"); return; }
            try {
                save.account(value, proxies.find(selected[0]) == null ? "" : selected[0]);
                sheet.dismiss();
            } catch (Exception error) {
                name.setError(error.getMessage() == null ? "无法保存账号，请重试。" : error.getMessage());
            }
        }), true);
        sheet.show();
    }
}
