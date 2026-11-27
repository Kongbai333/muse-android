package com.muse.workspace.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;

import java.util.List;
import java.util.function.Predicate;

import static com.muse.workspace.mobile.MobileUi.*;

/** 左侧滑入的会话 / 账号侧栏：顶部下划线分页 + 「+」，账号叠放显示，底部一行图标导航。 */
final class AccountSidebar extends FrameLayout {
    interface Host extends AccountDeck.Host {
        void sidebarClosed();
        void openStudio();
        void openProxyManager();
        void createStudioSession();
        void selectStudioSession(StudioSession session);
        void deleteStudioSessions(List<StudioSession> sessions);
        void toggleTheme();
        void checkForUpdates();
        void importAccounts();
        void importCookieText();
        void exportAccounts();
    }

    private final MobileUi ui;
    private final Host host;
    private final ProxyStore proxies;
    private final View scrim;
    private final LinearLayout panel, rows, sessionRows, footer, navigation;
    private final ScrollView accountScroll, sessionScroll;
    private final TextView sessionTab, accountTab;
    private final View sessionLine, accountLine, accountEmpty;
    private final ImageButton addButton;
    private final AccountDeck deck;
    private final SharedPreferences positions;
    private AccountDeck.Anchor anchor;
    private int sessionPosition;
    private boolean restoring;
    private final Runnable persistScroll = this::savePosition;
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
        positions = context.getSharedPreferences(MobileUi.PREFS, Context.MODE_PRIVATE);
        sessionsShown = positions.getBoolean("sidebar-sessions", true);
        sessionPosition = positions.getInt("sidebar-session-y", 0);
        anchor = new AccountDeck.Anchor(positions.getString("sidebar-anchor", ""), positions.getInt("sidebar-slot", 0), positions.getInt("sidebar-offset", -ui.dp(12)));

        scrim = new View(context);
        scrim.setBackgroundColor(SCRIM);
        scrim.setAlpha(0f);
        scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(view -> close(true));
        addView(scrim, new LayoutParams(-1, -1));

        panel = ui.column();
        panel.setBackground(ui.endShape(SURFACE, 18));
        panel.setElevation(ui.dp(10));
        panel.setClickable(true);
        panel.setFocusableInTouchMode(true);
        ViewCompat.setAccessibilityPaneTitle(panel, "侧边栏");
        addView(panel, new LayoutParams(ui.dp(340), -1, Gravity.START));

        LinearLayout heading = ui.row();
        heading.setPadding(ui.dp(20), ui.dp(4), ui.dp(8), 0);
        TextView name = ui.text("Muse", 20, INK, true);
        name.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        heading.addView(name, new LinearLayout.LayoutParams(-2, -2));
        View mark = new View(context);
        mark.setBackground(ui.shape(ACCENT, 2, 0));
        LinearLayout.LayoutParams markPosition = new LinearLayout.LayoutParams(ui.dp(6), ui.dp(6));
        markPosition.setMarginStart(ui.dp(3));
        markPosition.gravity = Gravity.BOTTOM;
        markPosition.bottomMargin = ui.dp(3);
        heading.addView(mark, markPosition);
        heading.addView(ui.spacer());
        heading.addView(ui.compactIconButton(R.drawable.ic_close, "关闭侧边栏", INK, () -> close(true)));
        ui.block(panel, heading, 0);

        // 分页：文字 + 下划线，右侧「+」按当前分页新建会话或添加账号。
        LinearLayout tabBar = ui.row();
        tabBar.setPadding(ui.dp(20), ui.dp(2), ui.dp(8), 0);
        LinearLayout sessionColumn = ui.column();
        sessionTab = ui.text("会话", 14, MUTED, false);
        sessionLine = new View(context);
        tab(sessionColumn, sessionTab, sessionLine, () -> selectTab(true));
        tabBar.addView(sessionColumn, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout accountColumn = ui.column();
        accountTab = ui.text("账号", 14, MUTED, false);
        accountLine = new View(context);
        tab(accountColumn, accountTab, accountLine, () -> selectTab(false));
        LinearLayout.LayoutParams accountPosition = new LinearLayout.LayoutParams(-2, -2);
        accountPosition.setMarginStart(ui.dp(18));
        tabBar.addView(accountColumn, accountPosition);
        tabBar.addView(ui.spacer());
        addButton = ui.compactIconButton(R.drawable.ic_add, "新建会话", INK, this::addToSection);
        tabBar.addView(addButton);
        panel.addView(tabBar, new LinearLayout.LayoutParams(-1, -2));
        View tabRule = new View(context);
        tabRule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rulePosition = new LinearLayout.LayoutParams(-1, ui.dp(1));
        rulePosition.setMargins(ui.dp(14), 0, ui.dp(14), 0);
        panel.addView(tabRule, rulePosition);

        FrameLayout lists = new FrameLayout(context);
        sessionRows = ui.column();
        sessionRows.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        sessionScroll = new ScrollView(context);
        sessionScroll.setVerticalScrollBarEnabled(false);
        sessionScroll.addView(sessionRows, new ScrollView.LayoutParams(-1, -2));
        lists.addView(sessionScroll, new FrameLayout.LayoutParams(-1, -1));
        rows = ui.column();
        rows.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(16));
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        deck = new AccountDeck(context, ui, host, proxies);
        rows.addView(deck, new LinearLayout.LayoutParams(-1, -2));
        accountEmpty = empty("还没有 Muse 账号", "点右上角的 + 添加一个账号，在网页里登录后就能开始创作。");
        rows.addView(accountEmpty, new LinearLayout.LayoutParams(-1, -2));
        accountScroll = new ScrollView(context);
        accountScroll.setVerticalScrollBarEnabled(false);
        accountScroll.setClipChildren(false);
        accountScroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        lists.addView(accountScroll, new FrameLayout.LayoutParams(-1, -1));
        accountScroll.setOnScrollChangeListener((view, left, top, oldLeft, oldTop) -> {
            if (!restoring && isOpen() && !sessionsShown && !deck.isLayoutRequested()) {
                deck.settlePositions();
                anchor = deck.anchor(top - deck.getTop());
                scheduleSave();
            }
        });
        sessionScroll.setOnScrollChangeListener((view, left, top, oldLeft, oldTop) -> {
            if (!restoring && isOpen() && sessionsShown) {
                sessionPosition = top;
                scheduleSave();
            }
        });
        panel.addView(lists, new LinearLayout.LayoutParams(-1, 0, 1));

        // 只在多选删除或无法添加账号时出现的操作区。
        footer = ui.column();
        footer.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(10));
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(context);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams linePosition = new LinearLayout.LayoutParams(-1, ui.dp(1));
        linePosition.setMargins(ui.dp(14), 0, ui.dp(14), 0);
        panel.addView(line, linePosition);

        // 底部导航：一行三个入口，图标在上、文字在下。
        navigation = ui.row();
        navigation.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(12));
        panel.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        updateSection();
        setVisibility(GONE);
    }

    private void tab(LinearLayout column, TextView label, View underline, Runnable action) {
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelPosition = new LinearLayout.LayoutParams(-2, ui.dp(40));
        column.addView(label, labelPosition);
        LinearLayout.LayoutParams linePosition = new LinearLayout.LayoutParams(-1, ui.dp(2));
        column.addView(underline, linePosition);
        column.setFocusable(true);
        column.setClickable(true);
        column.setOnClickListener(view -> action.run());
    }

    private void selectTab(boolean sessions) {
        if (sessions == sessionsShown) return;
        sessionsShown = sessions;
        savePosition();
        if (!sessionsShown && picking) { picking = false; picked.clear(); renderSessions(); }
        updateSection();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec);
        panel.getLayoutParams().width = Math.min(ui.dp(340), Math.max(0, Math.round(available * 0.86f)));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        restoring = true;
        super.onLayout(changed, left, top, right, bottom);
        if (isOpen()) {
            if (sessionsShown) sessionScroll.scrollTo(0, sessionPosition);
            else if (accountCount > 0) {
                accountScroll.scrollTo(0, deck.getTop() + deck.position(anchor));
                if (accountScroll.getScrollY() > 0) deck.settlePositions();
                AccountDeck.Anchor resolved = deck.anchor(accountScroll.getScrollY() - deck.getTop());
                if (!resolved.equals(anchor)) { anchor = resolved; scheduleSave(); }
            }
        }
        restoring = false;
        applyReveal(reveal);
    }

    private void scheduleSave() {
        removeCallbacks(persistScroll);
        postDelayed(persistScroll, 160);
    }

    void savePosition() {
        removeCallbacks(persistScroll);
        positions.edit().putBoolean("sidebar-sessions", sessionsShown).putInt("sidebar-session-y", sessionPosition)
            .putString("sidebar-anchor", anchor.id()).putInt("sidebar-slot", anchor.slot()).putInt("sidebar-offset", anchor.offset()).apply();
    }

    void afterQuickSwitch(String id) { positions.edit().putString("sidebar-quick-target", id).apply(); }

    void restoreDetailsFrom(AccountSidebar previous) { deck.restoreDetailsFrom(previous.deck); }

    void forgetAccount(String id) {
        if (id.equals(positions.getString("sidebar-quick-target", ""))) positions.edit().remove("sidebar-quick-target").apply();
        if (id.equals(anchor.id())) {
            anchor = new AccountDeck.Anchor("", anchor.slot(), anchor.offset());
            savePosition();
        }
    }

    void setTopInset(int top) { panel.setPadding(0, top, 0, 0); }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        String target = positions.getString("sidebar-quick-target", "");
        if (!target.isEmpty()) {
            positions.edit().remove("sidebar-quick-target").apply();
            if (target.equals(deck.currentId())) {
                anchor = new AccountDeck.Anchor(target, 0, -rows.getPaddingTop());
                showSessions(false);
                savePosition();
            }
        }
        setVisibility(VISIBLE);
        applyReveal(0f);
        panel.requestFocus();
        slideTo(1f);
    }

    void close(boolean animate) {
        if (!isOpen() || (hiding && animate)) return;
        savePosition();
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
        savePosition();
        cancelTransition();
        super.onDetachedFromWindow();
    }

    void render(List<AccountStore.Account> accounts, AccountStore.Account current, boolean studioActive,
            boolean proxyActive, boolean canAdd, Predicate<AccountStore.Account> usable, QuotaSync quotas,
            List<StudioSession> sessions, StudioSession selectedSession, String runningSessionId) {
        accountCount = accounts.size();
        canAddAccount = canAdd;
        this.studioActive = studioActive;
        this.proxyActive = proxyActive;

        deck.bind(accounts, current, usable, quotas);
        deck.setVisibility(accounts.isEmpty() ? GONE : VISIBLE);
        accountEmpty.setVisibility(accounts.isEmpty() ? VISIBLE : GONE);

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
            ui.block(sessionRows, sessionRow(session, session == shownSelected, session.id.equals(shownRunning)), 2);
        }
        if (shownSessions.isEmpty()) sessionRows.addView(empty("还没有会话", "点右上角的 + 新建一个会话开始创作。"));
    }

    /** 列表顶部一行：平时显示“管理”，管理中显示已选数量、全选与完成。 */
    private View sessionHeader() {
        LinearLayout header = ui.row();
        header.setMinimumHeight(ui.dp(36));
        header.setPadding(ui.dp(6), 0, 0, 0);
        int selectable = 0;
        for (StudioSession session : shownSessions) if (!session.id.equals(shownRunning)) selectable++;
        TextView label = picking ? ui.text(picked.isEmpty() ? "选择要删除的会话" : "已选 " + picked.size() + " 个", 12.5f, INK, true)
            : ui.meta("最近的会话", 10, MUTED);
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (picking) {
            boolean all = selectable > 0 && picked.size() >= selectable;
            header.addView(headerAction(all ? "取消全选" : "全选", TEXT2, () -> {
                if (all) picked.clear();
                else for (StudioSession session : shownSessions) if (!session.id.equals(shownRunning)) picked.add(session.id);
                refreshPicking();
            }));
            header.addView(headerAction("完成", INK, () -> setPicking(false, null)));
        } else header.addView(headerAction("管理", TEXT2, () -> setPicking(true, null)));
        return header;
    }

    private View headerAction(String label, int color, Runnable action) {
        TextView view = ui.text(label, 13, color, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(ui.dp(34));
        view.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        ui.touch(view, Color.TRANSPARENT, R_SM, 0, action);
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

    boolean finishPickingOnBack() {
        if (!picking) return false;
        int scroll = sessionScroll.getScrollY();
        finishPicking();
        sessionScroll.post(() -> sessionScroll.scrollTo(0, scroll));
        return true;
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
        // 纯文字的紧凑行：标题 + 账号 · 类型。
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(48));
        row.setPadding(ui.dp(14), ui.dp(7), ui.dp(12), ui.dp(7));
        LinearLayout words = ui.column();
        words.setPadding(0, 0, ui.dp(6), 0);
        words.addView(ui.single(ui.text(session.name, 14, INK, selected)));
        TextView sub = ui.single(ui.meta((session.video ? "VID · " : "IMG · ") + session.ownerName, 10, MUTED));
        LinearLayout.LayoutParams subPosition = new LinearLayout.LayoutParams(-1, -2);
        subPosition.topMargin = ui.dp(4);
        words.addView(sub, subPosition);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (running) row.addView(ui.text("显影中", 11, ACCENT, true));
        boolean checked = picking && picked.contains(session.id);
        if (picking && !running) row.addView(checkMark(checked), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        row.setSelected(picking ? checked : selected);
        if (picking) {
            ui.touch(row, checked ? RAISED : Color.TRANSPARENT, R_MD, 0, () -> togglePicked(session));
            if (running) row.setAlpha(0.5f);
        } else {
            ui.touch(row, selected ? RAISED : Color.TRANSPARENT, R_MD, 0, () -> host.selectStudioSession(session));
            row.setOnLongClickListener(view -> {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                setPicking(true, session);
                return true;
            });
        }
        row.setContentDescription(session.name + "，" + session.ownerName + (selected ? "，当前会话" : "")
            + (running ? "，生成中" : "") + (picking ? (running ? "，生成中不能删除" : checked ? "，已选中" : "，未选中")
                : "，长按可多选删除"));
        frame.addView(row, new FrameLayout.LayoutParams(-1, -2));
        if (selected && !picking) {
            View bar = new View(getContext());
            bar.setBackground(ui.shape(INK, 2, 0));
            FrameLayout.LayoutParams barPosition = new FrameLayout.LayoutParams(ui.dp(3), ui.dp(18), Gravity.START | Gravity.CENTER_VERTICAL);
            barPosition.setMarginStart(ui.dp(4));
            frame.addView(bar, barPosition);
        }
        return frame;
    }

    private View checkMark(boolean checked) {
        FrameLayout box = new FrameLayout(getContext());
        box.setBackground(ui.shape(checked ? INK : Color.TRANSPARENT, 11, checked ? 0 : LINE_STRONG));
        if (checked) box.addView(ui.iconView(R.drawable.ic_check, BG, 14),
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
        selectTab(sessions);
    }

    private void updateSection() {
        paintTab(sessionTab, sessionLine, sessionsShown, "会话", sessionCount);
        paintTab(accountTab, accountLine, !sessionsShown, "账号", accountCount);
        sessionScroll.setVisibility(sessionsShown ? VISIBLE : GONE);
        accountScroll.setVisibility(sessionsShown ? GONE : VISIBLE);

        boolean adding = sessionsShown ? !picking : canAddAccount;
        addButton.setVisibility(adding ? VISIBLE : GONE);
        addButton.setImageResource(sessionsShown ? R.drawable.ic_add : R.drawable.ic_user_plus);
        String addLabel = sessionsShown ? "新建会话" : "添加账号";
        addButton.setContentDescription(addLabel);
        addButton.setTooltipText(addLabel);

        footer.removeAllViews();
        View action = sessionsShown && picking ? deleteAction()
            : !sessionsShown && !canAddAccount ? ui.note("更新 Android System WebView 或 Chrome 后可添加多个账号。", 0) : null;
        footer.setVisibility(action == null ? GONE : VISIBLE);
        if (action != null) footer.addView(action, new LinearLayout.LayoutParams(-1, -2));

        navigation.removeAllViews();
        int proxyCount = proxies.all().size();
        dock(R.drawable.ic_studio, "工作室", "", studioActive, host::openStudio);
        dock(R.drawable.ic_route, "代理设置", proxyCount == 0 ? "" : String.valueOf(proxyCount), proxyActive, host::openProxyManager);
        dock(MobileUi.themeIcon(getContext()), MobileUi.themeMode(getContext()).label, "", false, host::toggleTheme)
            .setContentDescription("外观，" + MobileUi.themeDescription(getContext()));
        dock(R.drawable.ic_web_refresh, "检查更新", "", false, host::checkForUpdates);
        dock(R.drawable.ic_transfer, "导入导出", "", false, this::showTransferChoices);
    }

    private void addToSection() {
        if (sessionsShown) host.createStudioSession();
        else if (canAddAccount) host.editAccount(null);
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

    private void paintTab(TextView label, View underline, boolean on, String name, int count) {
        label.setText(name + "  " + count);
        label.setTextColor(on ? INK : MUTED);
        label.setTypeface(android.graphics.Typeface.create(on ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
        underline.setBackgroundColor(on ? INK : Color.TRANSPARENT);
        View column = (View) label.getParent();
        column.setSelected(on);
        column.setContentDescription(name + "，" + count + " 个" + (on ? "，已选中" : ""));
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

    /** 底部导航的一格：上面图标（当前页衬底色），下面文字；代理数量做成角标。 */
    private View dock(int icon, String label, String badge, boolean active, Runnable action) {
        LinearLayout item = ui.column();
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, ui.dp(4), 0, ui.dp(4));
        FrameLayout pill = new FrameLayout(getContext());
        pill.setBackground(ui.shape(active ? RAISED : Color.TRANSPARENT, R_MD, 0));
        pill.addView(ui.iconView(icon, active ? INK : TEXT2, 20), new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        if (!badge.isEmpty()) {
            TextView count = ui.metaStrong(badge, 8.5f, BG);
            count.setGravity(Gravity.CENTER);
            count.setMinWidth(ui.dp(14));
            count.setPadding(ui.dp(3), 0, ui.dp(3), 0);
            count.setBackground(ui.shape(INK, 7, 0));
            FrameLayout.LayoutParams badgePosition = new FrameLayout.LayoutParams(-2, ui.dp(14), Gravity.TOP | Gravity.END);
            badgePosition.setMarginEnd(ui.dp(5));
            pill.addView(count, badgePosition);
        }
        item.addView(pill, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(30)));
        TextView text = ui.single(ui.text(label, 11, active ? INK : TEXT2, active));
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textPosition = new LinearLayout.LayoutParams(-2, -2);
        textPosition.topMargin = ui.dp(5);
        item.addView(text, textPosition);
        item.setSelected(active);
        item.setContentDescription(label + (badge.isEmpty() ? "" : "，" + badge + " 条") + (active ? "，当前页面" : ""));
        item.setTooltipText(label);
        ui.touch(item, Color.TRANSPARENT, R_MD, 0, action);
        navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        return item;
    }

    void updateQuota(String id, QuotaSync quotas) {
        deck.updateQuota(id);
    }

    void updateAvatar(String id, android.graphics.Bitmap image) {
        deck.updateAvatar(id, image);
    }
}
