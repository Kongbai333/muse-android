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
    interface Save { void account(String name, String proxyId, boolean routeEdited) throws Exception; }

    private final MobileUi.Sheet sheet;
    private final QuotaSync quotas;
    private final String accountId;
    private TextView refreshStatus;
    private EditText draftName;
    private String[] draftProxy;
    private boolean[] draftRouteEdited;
    private Runnable renderOptions;
    private boolean refreshRequested, refreshReported, refreshDenied;

    private AccountEditor(MobileUi.Sheet sheet, QuotaSync quotas, AccountStore.Account account) {
        this.sheet=sheet;this.quotas=quotas;accountId=account==null?"":account.id;
    }

    static AccountEditor show(Activity activity, MobileUi ui, AccountStore.Account account, Runnable remove,
            ProxyStore proxies, AccountStore accounts, QuotaSync quotas, Runnable manage, Runnable refresh, Save save) {
        MobileUi.Sheet sheet = ui.sheet(activity, account == null ? "添加账号" : "编辑账号",
            account == null ? "保存后打开网页登录 Muse；之后可在编辑账号中刷新额度、头像和套餐。"
                : "改动的代理会在下次打开这个账号的网页时生效。");
        AccountEditor editor = new AccountEditor(sheet, quotas, account);
        if (refresh != null) {
            sheet.add(ui.outline("刷新额度", () -> editor.refresh(refresh)), 12);
            editor.refreshStatus = ui.text("使用已保存的账号配置，保留本次编辑。", 12, TEXT2, false);
            editor.refreshStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            sheet.add(editor.refreshStatus, 6);
        }
        EditText name = ui.input("例如：工作号", InputType.TYPE_CLASS_TEXT, account == null ? 36 : Math.max(36, account.name.length()));
        if (account != null) name.setText(account.name);
        sheet.add(ui.field(name, "账号名称"), 14);

        List<ProxyProfile> available = proxies.all();
        String[] selected = {account == null ? "" : account.proxyId()};
        boolean[] routeEdited = {false};
        String originalName = name.getText().toString().trim();
        String originalProxy = selected[0];
        boolean legacyProxy = account != null && account.hasProxy() && account.proxyId().isEmpty();
        sheet.guardDismiss(() -> !name.getText().toString().trim().equals(originalName)
            || !selected[0].equals(originalProxy)
            || legacyProxy && routeEdited[0]);
        sheet.add(ui.text("网络代理", 12.5f, TEXT2, false), 18);
        LinearLayout options = ui.column();
        sheet.add(options, 7);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            options.removeAllViews();
            View globe = ui.iconView(R.drawable.ic_globe, TEXT2, 20);
            boolean direct = selected[0].isEmpty() && (routeEdited[0] || account == null || !account.hasProxy());
            ui.block(options, ui.option(globe, "系统网络", "不使用代理", direct, true, () -> {
                selected[0] = ""; routeEdited[0] = true; render[0].run();
            }), 0);
            for (ProxyProfile profile : available) {
                TextView tag = ProxyManagerPage.protocolTag(ui, profile.scheme);
                int count = accounts.referenceCount(profile.id);
                ui.block(options, ui.option(tag, profile.name, "已关联 " + count + " 个账号",
                    profile.id.equals(selected[0]), true, () -> { selected[0] = profile.id; routeEdited[0] = true; render[0].run(); }), 8);
            }
        };
        render[0].run();
        editor.draftName = name;
        editor.draftProxy = selected;
        editor.draftRouteEdited = routeEdited;
        editor.renderOptions = render[0];

        TextView manageButton = ui.text("管理代理配置", 13.5f, INK, false);
        manageButton.setPaintFlags(manageButton.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        manageButton.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(4));
        manageButton.setMinHeight(ui.dp(48));
        manageButton.setGravity(android.view.Gravity.CENTER_VERTICAL);
        manageButton.setFocusable(true);
        manageButton.setClickable(true);
        manageButton.setOnClickListener(view -> sheet.leave(manage));
        LinearLayout links = ui.row();
        links.addView(manageButton);
        if (remove != null) {
            links.addView(ui.spacer());
            TextView delete = ui.text("移除账号", 13.5f, ACCENT, true);
            delete.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(4));
            delete.setMinHeight(ui.dp(48));
            delete.setGravity(android.view.Gravity.CENTER_VERTICAL);
            delete.setFocusable(true);
            delete.setClickable(true);
            delete.setOnClickListener(view -> sheet.leave(remove));
            links.addView(delete);
        }
        sheet.add(links, 12);

        sheet.action(ui.outline("取消", sheet::dismiss), true);
        sheet.action(ui.primary("保存", () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError("请输入账号名称"); return; }
            try {
                save.account(value, selected[0], routeEdited[0]);
                sheet.dismissSaved();
            } catch (Exception error) {
                name.setError(error.getMessage() == null ? "无法保存账号，请重试。" : error.getMessage());
            }
        }), true);
        sheet.show();
        return editor;
    }

    private void refresh(Runnable action) {
        refreshRequested=true;refreshReported=false;refreshDenied=false;
        refreshStatus.setText("正在请求刷新…");
        action.run();
        if (!refreshDenied && !refreshReported) {
            boolean running=quotas.state(accountId).contains("同步中");
            refreshRequested=running;
            refreshStatus.setText(running ? "此账号正在同步，请稍候。" : "本次未启动刷新，请稍后再试。");
        }
    }

    boolean refreshBlocked(String id, String reason) {
        if (!showing(id) || refreshStatus==null) return false;
        refreshDenied=true;refreshRequested=false;
        refreshStatus.setText(reason);
        return true;
    }

    void onDismiss(Runnable closed) { sheet.dialog.setOnDismissListener(ignored -> closed.run()); }

    void retheme(MainActivity activity, AccountStore accounts) {
        if (!sheet.dialog.isShowing()) return;
        AccountStore.Account target = null;
        for (AccountStore.Account item : accounts.all()) if (item.id.equals(accountId)) target = item;
        sheet.dismissSaved();
        if (target == null && !accountId.isEmpty()) return;
        AccountEditor next = activity.openEditor(target);
        if (next == null) return;
        next.draftName.setText(draftName.getText().toString());
        next.draftProxy[0] = draftProxy[0];
        next.draftRouteEdited[0] = draftRouteEdited[0];
        next.renderOptions.run();
        if (refreshStatus != null && next.refreshStatus != null) {
            next.refreshRequested = refreshRequested;
            next.refreshDenied = refreshDenied;
            next.refreshReported = refreshReported;
            next.refreshStatus.setText(refreshStatus.getText());
        }
    }

    void updateQuota(String id) {
        if (!showing(id)) return;
        if (refreshRequested && refreshStatus!=null) {
            refreshReported=true;
            refreshStatus.setText(quotas.state(accountId));
        }
    }

    private boolean showing(String id) {
        return !accountId.isEmpty() && accountId.equals(id) && sheet.dialog.isShowing();
    }
}
