package com.muse.workspace.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;

import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

import static com.muse.workspace.mobile.MobileUi.*;

/** 左侧滑入的会话 / 账号侧栏。 */
final class AccountSidebar extends FrameLayout {
    interface Host {
        void sidebarClosed();
        void openStudio();
        void openProxyManager();
        void openAccount(AccountStore.Account account);
        void refreshAccount(AccountStore.Account account);
        void editAccount(AccountStore.Account account);
        void exportAccounts();
        void importAccounts();
        void importCookieText();
        void createStudioSession();
        void selectStudioSession(StudioSession session);
        void deleteStudioSessions(List<StudioSession> sessions);
        void toggleTheme();
        void checkForUpdates();
    }

    private final MobileUi ui;
    private final Host host;
    private final ProxyStore proxies;
    private final View scrim;
    private final LinearLayout panel, rows, sessionRows, footer, navigation;
    private final ScrollView accountScroll, sessionScroll;
    private final MobileUi.Segment tabs;
    private final HashMap<String, QuotaCard> cards = new HashMap<>();
    private final HashMap<String, ImageView> avatarImages = new HashMap<>();
    private final HashMap<String, TextView> avatarFallbacks = new HashMap<>();
    private final HashMap<String, AccountCard> accountCards = new HashMap<>();
    private String expandedAccountId;
    private ValueAnimator accountMotion;
    private ValueAnimator transition;
    private float reveal;
    private boolean hiding;
    private boolean canAddAccount;
    private boolean studioActive, proxyActive;
    private boolean sessionsShown = true;
    private int accountCount, sessionCount;
    // 会话管理（多选删除）状态与最近一次渲染的数据。
    private boolean picking;
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
    private List<StudioSession> shownSessions = java.util.Collections.emptyList();
    private StudioSession shownSelected;
    private String shownRunning = "";

    AccountSidebar(Context context, MobileUi ui, Host host, ProxyStore proxies) {
        super(context);
        this.ui = ui;
        this.host = host;
        this.proxies = proxies;

        scrim = new View(context);
        scrim.setBackgroundColor(SCRIM);
        scrim.setAlpha(0f);
        scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(view -> close(true));
        addView(scrim, new LayoutParams(-1, -1));

        panel = ui.column();
        panel.setBackground(ui.endShape(SURFACE, 30));
        panel.setElevation(ui.dp(10));
        panel.setClickable(true);
        panel.setFocusableInTouchMode(true);
        ViewCompat.setAccessibilityPaneTitle(panel, "侧边栏");
        addView(panel, new LayoutParams(ui.dp(340), -1, Gravity.START));

        tabs = ui.segment(new String[]{"会话", "账号"}, null, true, 0, index -> {
            sessionsShown = index == 0;
            if (!sessionsShown && picking) { picking = false; picked.clear(); renderSessions(); }
            updateSection();
        });
        LinearLayout.LayoutParams tabPosition = new LinearLayout.LayoutParams(-1, -2);
        tabPosition.setMargins(ui.dp(14), ui.dp(6), ui.dp(14), 0);
        panel.addView(tabs.view, tabPosition);

        FrameLayout lists = new FrameLayout(context);
        sessionRows = ui.column();
        sessionRows.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        sessionScroll = new ScrollView(context);
        sessionScroll.setVerticalScrollBarEnabled(false);
        sessionScroll.addView(sessionRows, new ScrollView.LayoutParams(-1, -2));
        lists.addView(sessionScroll, new FrameLayout.LayoutParams(-1, -1));
        rows = ui.column();
        rows.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        accountScroll = new ScrollView(context);
        accountScroll.setVerticalScrollBarEnabled(false);
        accountScroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        lists.addView(accountScroll, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(lists, new LinearLayout.LayoutParams(-1, 0, 1));

        View line = new View(context);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams linePosition = new LinearLayout.LayoutParams(-1, ui.dp(1));
        linePosition.setMargins(ui.dp(14), 0, ui.dp(14), 0);
        panel.addView(line, linePosition);

        footer = ui.column();
        footer.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0);
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        navigation = ui.column();
        navigation.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(18));
        panel.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        updateSection();
        setVisibility(GONE);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec);
        panel.getLayoutParams().width = Math.min(ui.dp(340), Math.max(0, Math.round(available * 0.86f)));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        applyReveal(reveal);
    }

    void setTopInset(int top) { panel.setPadding(0, top, 0, 0); }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        setVisibility(VISIBLE);
        applyReveal(0f);
        panel.requestFocus();
        slideTo(1f);
    }

    void close(boolean animate) {
        if (!isOpen() || (hiding && animate)) return;
        hiding = true;
        if (animate) slideTo(0f);
        else {
            cancelTransition();
            applyReveal(0f);
            finishClose();
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return hiding || super.dispatchTouchEvent(event);
    }

    private void applyReveal(float fraction) {
        reveal = fraction;
        int direction = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : -1;
        panel.setTranslationX(direction * (1f - fraction) * (panel.getWidth() + panel.getElevation()));
        scrim.setAlpha(fraction);
    }

    private void slideTo(float target) {
        cancelTransition();
        if (reveal == target || !ValueAnimator.areAnimatorsEnabled()) {
            applyReveal(target);
            if (target == 0f) finishClose();
            return;
        }
        boolean entering = target > reveal;
        ValueAnimator motion = ValueAnimator.ofFloat(reveal, target);
        motion.setDuration(entering ? 300 : 200);
        motion.setInterpolator(entering ? new PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            : new PathInterpolator(0.4f, 0f, 1f, 1f));
        motion.addUpdateListener(value -> applyReveal((float) value.getAnimatedValue()));
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (transition != animation) return;
                transition = null;
                if (target == 0f) finishClose();
            }
        });
        transition = motion;
        motion.start();
    }

    private void cancelTransition() {
        ValueAnimator previous = transition;
        transition = null;
        if (previous != null) previous.cancel();
    }

    private void finishClose() {
        setVisibility(GONE);
        hiding = false;
        if (picking) { picking = false; picked.clear(); renderSessions(); updateSection(); }
        host.sidebarClosed();
    }

    @Override protected void onDetachedFromWindow() {
        cancelTransition();
        cancelAccountMotion();
        for (AccountCard card : accountCards.values()) card.reveal(card.expanded ? 1f : 0f);
        super.onDetachedFromWindow();
    }

    void render(List<AccountStore.Account> accounts, AccountStore.Account current, boolean studioActive,
            boolean proxyActive, boolean canAdd, Predicate<AccountStore.Account> usable, QuotaSync quotas,
            List<StudioSession> sessions, StudioSession selectedSession, String runningSessionId) {
        accountCount = accounts.size();
        canAddAccount = canAdd;
        this.studioActive = studioActive;
        this.proxyActive = proxyActive;

        cancelAccountMotion();
        accountCards.clear();
        cards.clear();
        avatarImages.clear();
        avatarFallbacks.clear();
        rows.removeAllViews();
        for (AccountStore.Account account : accounts) {
            ui.block(rows, accountCard(account, account == current, usable.test(account), quotas.value(account.id)),
                rows.getChildCount() == 0 ? 0 : -10);
        }
        if (!accountCards.containsKey(expandedAccountId)) expandedAccountId = null;
        if (accounts.isEmpty()) rows.addView(empty("还没有 Muse 账号", "添加一个账号，在网页里登录后就能开始创作。"));

        shownSessions = new java.util.ArrayList<>(sessions);
        shownSelected = selectedSession;
        shownRunning = runningSessionId == null ? "" : runningSessionId;
        java.util.HashSet<String> alive = new java.util.HashSet<>();
        for (StudioSession session : sessions) alive.add(session.id);
        picked.retainAll(alive);
        picked.remove(shownRunning);
        if (sessions.isEmpty()) picking = false;
        renderSessions();
        updateSection();
    }

    private void renderSessions() {
        sessionRows.removeAllViews();
        sessionCount = shownSessions.size();
        if (!shownSessions.isEmpty()) ui.block(sessionRows, sessionHeader(), 0);
        for (StudioSession session : shownSessions) {
            ui.block(sessionRows, sessionRow(session, session == shownSelected, session.id.equals(shownRunning)),
                sessionRows.getChildCount() <= 1 ? 2 : 2);
        }
        if (shownSessions.isEmpty()) sessionRows.addView(empty("还没有会话", "点击下方按钮开始创作。"));
    }

    /** 列表顶部一行：平时显示“管理”，管理中显示已选数量、全选与完成。 */
    private View sessionHeader() {
        LinearLayout header = ui.row();
        header.setMinimumHeight(ui.dp(36));
        header.setPadding(ui.dp(6), 0, 0, 0);
        int selectable = 0;
        for (StudioSession session : shownSessions) if (!session.id.equals(shownRunning)) selectable++;
        TextView label = ui.text(picking ? (picked.isEmpty() ? "选择要删除的会话" : "已选 " + picked.size() + " 个") : "最近的会话",
            12.5f, picking ? INK : MUTED, picking);
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (picking) {
            boolean all = selectable > 0 && picked.size() >= selectable;
            header.addView(headerAction(all ? "取消全选" : "全选", TEXT2, () -> {
                if (all) picked.clear();
                else for (StudioSession session : shownSessions) if (!session.id.equals(shownRunning)) picked.add(session.id);
                refreshPicking();
            }));
            header.addView(headerAction("完成", ACCENT, () -> setPicking(false, null)));
        } else header.addView(headerAction("管理", TEXT2, () -> setPicking(true, null)));
        return header;
    }

    private View headerAction(String label, int color, Runnable action) {
        TextView view = ui.text(label, 13, color, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(ui.dp(34));
        view.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        ui.touch(view, Color.TRANSPARENT, 10, 0, action);
        return view;
    }

    private void setPicking(boolean on, StudioSession first) {
        picking = on;
        picked.clear();
        if (on && first != null && !first.id.equals(shownRunning)) picked.add(first.id);
        refreshPicking();
    }

    private void refreshPicking() {
        renderSessions();
        updateSection();
    }

    /** 删除完成或取消时退出管理模式。 */
    void finishPicking() {
        if (!picking && picked.isEmpty()) return;
        picking = false;
        picked.clear();
        refreshPicking();
    }

    private void togglePicked(StudioSession session) {
        if (session.id.equals(shownRunning)) return;
        if (!picked.remove(session.id)) picked.add(session.id);
        refreshPicking();
    }

    private View empty(String title, String detail) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(8), ui.dp(18), ui.dp(8), ui.dp(12));
        box.addView(ui.text(title, 15, INK, true));
        ui.block(box, ui.text(detail, 13, TEXT2, false), 8);
        return box;
    }

    private View sessionRow(StudioSession session, boolean selected, boolean running) {
        FrameLayout frame = new FrameLayout(getContext());
        // 仅显示单行标题；账号/类型留在完整无障碍说明里。
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(48));
        row.setPadding(ui.dp(14), ui.dp(7), ui.dp(12), ui.dp(7));
        TextView title = ui.single(ui.text(session.name, 14, INK, selected));
        title.setMinWidth(0);
        title.setTooltipText(session.name);
        LinearLayout.LayoutParams titlePosition = new LinearLayout.LayoutParams(0, -2, 1);
        titlePosition.setMarginEnd(ui.dp(6));
        row.addView(title, titlePosition);
        if (running) row.addView(ui.text("生成中", 11, ACCENT, true));
        boolean checked = picking && picked.contains(session.id);
        if (picking && !running) row.addView(checkMark(checked), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        row.setSelected(picking ? checked : selected);
        if (picking) {
            ui.touch(row, checked ? ACCENT_SOFT : Color.TRANSPARENT, 12, 0, () -> togglePicked(session));
            if (running) row.setAlpha(0.5f);
        } else {
            ui.touch(row, selected ? RAISED : Color.TRANSPARENT, 12, 0, () -> host.selectStudioSession(session));
            row.setOnLongClickListener(view -> {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                setPicking(true, session);
                return true;
            });
        }
        row.setContentDescription(session.name + "，" + session.ownerName + (session.video ? "，视频" : "，图片") + (selected ? "，当前会话" : "")
            + (running ? "，生成中" : "") + (picking ? (running ? "，生成中不能删除" : checked ? "，已选中" : "，未选中")
                : "，长按可多选删除"));
        frame.addView(row, new FrameLayout.LayoutParams(-1, -2));
        if (selected && !picking) {
            View bar = new View(getContext());
            bar.setBackground(ui.shape(ACCENT, 2, 0));
            FrameLayout.LayoutParams barPosition = new FrameLayout.LayoutParams(ui.dp(3), ui.dp(18), Gravity.START | Gravity.CENTER_VERTICAL);
            barPosition.setMarginStart(ui.dp(4));
            frame.addView(bar, barPosition);
        }
        return frame;
    }

    private View checkMark(boolean checked) {
        FrameLayout box = new FrameLayout(getContext());
        box.setBackground(ui.shape(checked ? ACCENT : Color.TRANSPARENT, 11, checked ? 0 : LINE_STRONG));
        if (checked) box.addView(ui.iconView(R.drawable.ic_check, ON_ACCENT, 14),
            new FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER));
        return box;
    }

    boolean showingSessions() { return sessionsShown; }

    /** 直接显示（不做滑入动画），用于切换外观后恢复原来打开的侧栏。 */
    void openImmediately() {
        cancelTransition();
        hiding = false;
        setVisibility(VISIBLE);
        applyReveal(1f);
        panel.requestFocus();
    }

    void showSessions(boolean sessions) {
        sessionsShown = sessions;
        updateSection();
    }

    private void updateSection() {
        tabs.select(sessionsShown ? 0 : 1);
        tabs.setLabel(0, "会话  " + sessionCount);
        tabs.setLabel(1, "账号  " + accountCount);
        sessionScroll.setVisibility(sessionsShown ? VISIBLE : GONE);
        accountScroll.setVisibility(sessionsShown ? GONE : VISIBLE);

        footer.removeAllViews();
        View action = sessionsShown && picking ? deleteAction()
            : sessionsShown ? ui.primary("新建会话", host::createStudioSession)
            : canAddAccount ? ui.primary("添加账号", () -> host.editAccount(null))
            : ui.note("更新 Android System WebView 或 Chrome 后可添加多个账号。", 0);
        footer.addView(action, new LinearLayout.LayoutParams(-1, -2));
        navigation.removeAllViews();
        navigation.addView(settingsBar());
    }

    private View settingsBar() {
        int proxyCount = proxies.all().size();
        LinearLayout settings = ui.row();
        settings.addView(settingsIcon(R.drawable.ic_route, "代理设置", proxyCount == 0 ? "未配置代理" : proxyCount + " 条",
            proxyActive, host::openProxyManager));
        settings.addView(settingsIcon(MobileUi.themeIcon(getContext()), "外观", MobileUi.themeDescription(getContext()), false, host::toggleTheme));
        settings.addView(settingsIcon(R.drawable.ic_web_refresh, "检查更新", "", false, host::checkForUpdates));
        settings.addView(settingsIcon(R.drawable.ic_transfer, "导入导出", "", false, this::showTransferChoices));
        return settings;
    }

    private void showTransferChoices() {
        MobileUi.dialog(getContext()).setTitle("导入导出")
            .setItems(new String[]{"加密账号包", "Cookie文本", "账号及静态代理导出"}, (dialog, index) -> {
                dialog.dismiss();
                if (index == 0) host.importAccounts();
                else if (index == 1) host.importCookieText();
                else if (index == 2) host.exportAccounts();
            })
            .setNegativeButton("取消", null).show();
    }

    private View settingsIcon(int icon, String label, String value, boolean active, Runnable action) {
        String description = label + (value.isEmpty() ? "" : "，" + value) + (active ? "，已选中" : "");
        TextView button = ui.single(ui.text(label, 10.5f, active ? ACCENT : TEXT2, false));
        button.setGravity(Gravity.CENTER);
        Drawable image = getContext().getDrawable(icon);
        if (image != null) {
            image = image.mutate();
            image.setTint(active ? ACCENT : TEXT2);
            image.setBounds(0, 0, ui.dp(20), ui.dp(20));
            button.setCompoundDrawablesRelative(null, image, null, null);
            button.setCompoundDrawablePadding(ui.dp(4));
        }
        ui.pad(button, 4, 6);
        button.setSelected(active);
        button.setMinimumWidth(ui.dp(48));
        button.setMinimumHeight(ui.dp(56));
        button.setContentDescription(description);
        button.setTooltipText(description);
        ui.touch(button, active ? ACCENT_SOFT : Color.TRANSPARENT, 12, 0, action);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(56), 1));
        return button;
    }

    private View deleteAction() {
        if (picked.isEmpty()) {
            TextView idle = ui.outline("选择会话后删除", () -> { });
            idle.setEnabled(false);
            idle.setAlpha(0.5f);
            return idle;
        }
        return ui.primary("删除所选（" + picked.size() + "）", () -> {
            java.util.ArrayList<StudioSession> chosen = new java.util.ArrayList<>();
            for (StudioSession session : shownSessions) if (picked.contains(session.id)) chosen.add(session);
            if (!chosen.isEmpty()) host.deleteStudioSessions(chosen);
        });
    }

    private View navRow(int icon, String label, String value, boolean active, Runnable action) {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(46));
        row.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        row.addView(ui.iconView(icon, active ? ACCENT : TEXT2, 19));
        TextView text = ui.text(label, 14.5f, INK, active);
        LinearLayout.LayoutParams textPosition = new LinearLayout.LayoutParams(0, -2, 1);
        textPosition.setMarginStart(ui.dp(12));
        row.addView(text, textPosition);
        if (!value.isEmpty()) row.addView(ui.text(value, 12.5f, MUTED, false));
        row.setSelected(active);
        row.setContentDescription(label + (value.isEmpty() ? "" : "，" + value));
        ui.touch(row, Color.TRANSPARENT, 12, 0, action);
        return row;
    }

    private View accountCard(AccountStore.Account account, boolean active, boolean usable, AccountQuota quotaData) {
        LinearLayout card = ui.column();
        // 后一卡仅覆盖这张卡底部预留的 10dp 空白，不遮摘要、详情或操作。
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(8), ui.dp(22));
        card.setSelected(active);
        card.setElevation(ui.dp(1));
        Runnable toggleDetails = () -> toggleAccount(account.id);
        ui.touch(card, BG, 18, active ? ACCENT : LINE, toggleDetails);

        LinearLayout identityRow = ui.row();
        FrameLayout portrait = new FrameLayout(getContext());
        int avatarSize = ui.dp(38);
        TextView avatarFallback = ui.avatar(account.name, account.id, 38);
        portrait.addView(avatarFallback, new FrameLayout.LayoutParams(avatarSize, avatarSize));
        ImageView avatar = new ImageView(getContext());
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(ui.shape(RAISED, 19, 0));
        avatar.setClipToOutline(true);
        avatar.setVisibility(View.GONE);
        portrait.addView(avatar, new FrameLayout.LayoutParams(avatarSize, avatarSize));
        avatarImages.put(account.id, avatar);
        avatarFallbacks.put(account.id, avatarFallback);
        identityRow.addView(portrait, new LinearLayout.LayoutParams(avatarSize, avatarSize));

        LinearLayout identity = ui.column();
        identity.setPadding(ui.dp(11), 0, ui.dp(4), 0);
        TextView label = ui.text(account.name, 15, INK, true);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTooltipText(account.name);
        // Tooltip 文字本身会消费触摸，短按显式复用卡片动作，完整名称仍可长按查看。
        label.setOnClickListener(ignored -> toggleDetails.run());
        label.setFocusable(false);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        ui.block(identity, label, 0);
        FrameLayout planLine = new FrameLayout(getContext());
        TextView plan = ui.tag(quotaData == null ? "待同步" : quotaData.plan());
        FrameLayout.LayoutParams planPosition = new FrameLayout.LayoutParams(-2, -2);
        planPosition.setMarginEnd(ui.dp(19));
        planLine.addView(plan, planPosition);
        View chevron = ui.iconView(R.drawable.ic_chevron_down, TEXT2, 13);
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams chevronPosition = new FrameLayout.LayoutParams(ui.dp(13), ui.dp(13), Gravity.END | Gravity.CENTER_VERTICAL);
        planLine.addView(chevron, chevronPosition);
        ui.block(identity, planLine, 4);
        identityRow.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout actions = ui.row();
        if (usable) {
            actions.addView(ui.compactIconButton(R.drawable.ic_web_refresh, "刷新「" + account.name + "」的额度", INK,
                () -> host.refreshAccount(account)));
            actions.addView(ui.compactIconButton(R.drawable.ic_edit, "编辑「" + account.name + "」", INK,
                () -> host.editAccount(account)));
        }
        ImageButton switchAccount = ui.compactIconButton(R.drawable.ic_account_switch, "切换到账号「" + account.name + "」", INK,
            () -> { if (usable) host.openAccount(account); });
        switchAccount.setEnabled(usable);
        actions.addView(switchAccount);
        ui.block(card, new AccountHeader(identityRow, actions), 0);

        LinearLayout networkLine = ui.row();
        ProxyProfile profile = proxies.find(account.proxyId());
        boolean routed = account.hasProxy();
        networkLine.addView(ui.iconView(routed ? R.drawable.ic_route : R.drawable.ic_globe, TEXT2, 12));
        String network = !usable ? "需更新 WebView" : profile != null ? profile.name : account.networkLabel(proxies);
        TextView networkText = ui.text(network, 12, TEXT2, false);
        LinearLayout.LayoutParams networkPosition = new LinearLayout.LayoutParams(0, -2, 1);
        networkPosition.setMarginStart(ui.dp(4));
        networkLine.addView(networkText, networkPosition);
        QuotaCard quota = new QuotaCard(getContext(), ui, value -> {
            plan.setText(value);
            plan.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }, toggleDetails);
        cards.put(account.id, quota);
        LinearLayout.LayoutParams quotaPosition = new LinearLayout.LayoutParams(-1, -2);
        quotaPosition.topMargin = ui.dp(12);
        quotaPosition.setMarginEnd(ui.dp(4));
        card.addView(quota.view, quotaPosition);

        LinearLayout detailContent = ui.column();
        detailContent.setPadding(0, ui.dp(10), ui.dp(4), 0);
        detailContent.addView(networkLine, new LinearLayout.LayoutParams(-1, -2));
        detailContent.addView(quota.details, new LinearLayout.LayoutParams(-1, -2));
        AccountDetails details = new AccountDetails(getContext());
        details.addView(detailContent, new FrameLayout.LayoutParams(-1, -2));
        card.addView(details, new LinearLayout.LayoutParams(-1, -2));
        AccountCard holder = new AccountCard(card, details, chevron, account.name + (active ? "，当前账号" : ""));
        accountCards.put(account.id, holder);
        holder.setExpanded(account.id.equals(expandedAccountId));
        holder.reveal(holder.expanded ? 1f : 0f);
        if (!usable) card.setAlpha(0.55f);
        return card;
    }

    private void toggleAccount(String id) {
        if (!accountCards.containsKey(id)) return;
        cancelAccountMotion();
        expandedAccountId = id.equals(expandedAccountId) ? null : id;
        HashMap<AccountCard, Float> starts = new HashMap<>();
        for (java.util.Map.Entry<String, AccountCard> entry : accountCards.entrySet()) {
            AccountCard card = entry.getValue();
            card.setExpanded(entry.getKey().equals(expandedAccountId));
            if (card.details.fraction != (card.expanded ? 1f : 0f)) starts.put(card, card.details.fraction);
        }
        if (starts.isEmpty()) return;
        if (!ValueAnimator.areAnimatorsEnabled() || !rows.isLaidOut() || !isShown()) {
            for (AccountCard card : starts.keySet()) card.reveal(card.expanded ? 1f : 0f);
            return;
        }
        ValueAnimator motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(260);
        motion.setInterpolator(new PathInterpolator(0.2f, 0f, 0.2f, 1f));
        motion.addUpdateListener(value -> {
            float progress = (float) value.getAnimatedValue();
            for (java.util.Map.Entry<AccountCard, Float> entry : starts.entrySet()) {
                AccountCard card = entry.getKey();
                float start = entry.getValue(), target = card.expanded ? 1f : 0f;
                card.reveal(start + (target - start) * progress);
            }
        });
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (accountMotion != animation) return;
                accountMotion = null;
                for (AccountCard card : starts.keySet()) card.reveal(card.expanded ? 1f : 0f);
            }
        });
        accountMotion = motion;
        motion.start();
    }

    private void cancelAccountMotion() {
        ValueAnimator previous = accountMotion;
        accountMotion = null;
        if (previous != null) previous.cancel();
    }

    private static final class AccountCard {
        final LinearLayout view;
        final AccountDetails details;
        final View chevron;
        final String description;
        boolean expanded;

        AccountCard(LinearLayout view, AccountDetails details, View chevron, String description) {
            this.view = view;
            this.details = details;
            this.chevron = chevron;
            this.description = description;
        }

        void setExpanded(boolean value) {
            expanded = value;
            details.expanded = value;
            details.updateInteraction();
            view.setContentDescription(description + (value ? "，点按收起详情" : "，点按展开详情"));
            ViewCompat.setStateDescription(view, value ? "已展开" : "已收起");
        }

        void reveal(float fraction) {
            details.setFraction(fraction);
            chevron.setRotation(180f * fraction);
        }
    }

    /** 始终按详情的自然高度测量；仅改变容器高度，让列表真正推开后项。 */
    private static final class AccountDetails extends FrameLayout {
        float fraction;
        boolean expanded;

        AccountDetails(Context context) { super(context); }

        void setFraction(float value) {
            fraction = Math.max(0f, Math.min(1f, value));
            setVisibility(fraction == 0f ? GONE : VISIBLE);
            updateInteraction();
            requestLayout();
        }

        void updateInteraction() {
            boolean interactive = expanded && fraction == 1f;
            setImportantForAccessibility(interactive ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            setDescendantFocusability(interactive ? FOCUS_BEFORE_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
            if (!interactive) clearFocus();
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            setMeasuredDimension(getMeasuredWidth(), Math.round(getMeasuredHeight() * fraction));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            return !expanded || fraction < 1f || super.onInterceptTouchEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return !expanded || fraction < 1f || super.onTouchEvent(event);
        }
    }

    /** 空间不足时把完整操作组换到下一行，不挤掉头像、账号名或按钮。 */
    private final class AccountHeader extends LinearLayout {
        final LinearLayout identity, actions;
        boolean wrapped;

        AccountHeader(LinearLayout identity, LinearLayout actions) {
            super(AccountSidebar.this.getContext());
            this.identity = identity;
            this.actions = actions;
            setGravity(Gravity.CENTER_VERTICAL);
            addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            addView(actions, new LinearLayout.LayoutParams(-2, -2));
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            actions.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            float scale = getResources().getConfiguration().fontScale;
            boolean wrap = MeasureSpec.getSize(widthMeasureSpec) < actions.getMeasuredWidth() + ui.dp(53 + 96 * scale);
            if (wrapped != wrap) {
                wrapped = wrap;
                setOrientation(wrap ? VERTICAL : HORIZONTAL);
                identity.setLayoutParams(new LinearLayout.LayoutParams(wrap ? -1 : 0, -2, wrap ? 0 : 1));
                LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-2, -2);
                position.gravity = Gravity.END;
                position.topMargin = wrap ? ui.dp(4) : 0;
                actions.setLayoutParams(position);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    void updateQuota(String id, QuotaSync quotas) {
        QuotaCard card = cards.get(id);
        if (card != null) card.update(quotas.card(id), quotas.state(id));
    }

    void updateAvatar(String id, android.graphics.Bitmap image) {
        ImageView view = avatarImages.get(id);
        if (view == null) return;
        TextView fallback = avatarFallbacks.get(id);
        if (image == null) {
            view.setImageDrawable(null);
            view.setVisibility(View.GONE);
            if (fallback != null) fallback.setVisibility(View.VISIBLE);
        } else {
            view.setImageBitmap(image);
            view.setVisibility(View.VISIBLE);
            if (fallback != null) fallback.setVisibility(View.GONE);
        }
    }
}
