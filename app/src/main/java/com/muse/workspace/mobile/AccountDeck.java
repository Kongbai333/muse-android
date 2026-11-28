package com.muse.workspace.mobile;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import static com.muse.workspace.mobile.MobileUi.*;

/** Current account details, compact account rows and an initially collapsed spent group. */
final class AccountDeck extends FrameLayout {
    interface Host {
        boolean switchAccount(AccountStore.Account account);
        void openAccount(AccountStore.Account account);
        void refreshAccount(AccountStore.Account account);
        void editAccount(AccountStore.Account account);
        void deleteAccount(AccountStore.Account account);
        void notice(String text);
    }
    private static final String GRID_KEY = "account-deck-grid";
    private final MobileUi ui;
    private final Host host;
    private final ProxyStore proxies;
    private final HashMap<String, Card> cards = new HashMap<>();
    private final ArrayList<Card> order = new ArrayList<>();
    private final LinearLayout liveLabel;
    private final TextView liveText, spreadToggle, outLabel;
    private QuotaSync quotas;
    private Predicate<AccountStore.Account> usable = account -> true;
    private String currentId = "", menuId = "";
    private boolean grid, outExpanded, menuWasOpen, detailsWereOpen;
    private int liveY, outY;
    private androidx.appcompat.app.AlertDialog actionMenu, quotaDialog;

    AccountDeck(Context context, MobileUi ui, Host host, ProxyStore proxies) {
        super(context);
        this.ui = ui; this.host = host; this.proxies = proxies;
        grid = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(GRID_KEY, false);
        liveLabel = ui.row();
        liveLabel.setPadding(ui.dp(4), 0, 0, 0);
        liveText = ui.meta("", 10, MUTED);
        liveLabel.addView(liveText, new LinearLayout.LayoutParams(0, -2, 1));
        spreadToggle = ui.meta("", 10, TEXT2);
        spreadToggle.setGravity(Gravity.CENTER);
        spreadToggle.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        ui.touch(spreadToggle, Color.TRANSPARENT, R_SM, 0, this::toggleGrid);
        liveLabel.addView(spreadToggle, new LinearLayout.LayoutParams(-2, -1));
        addView(liveLabel);
        outLabel = ui.single(ui.meta("", 10, MUTED));
        outLabel.setGravity(Gravity.CENTER_VERTICAL);
        outLabel.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        ui.touch(outLabel, Color.TRANSPARENT, 8, 0, () -> { outExpanded = !outExpanded; resort(); requestLayout(); });
        addView(outLabel);
    }

    void bind(List<AccountStore.Account> accounts, AccountStore.Account current,
              Predicate<AccountStore.Account> usable, QuotaSync quotas) {
        this.usable = usable; this.quotas = quotas;
        currentId = current == null ? "" : current.id;
        HashSet<String> alive = new HashSet<>();
        int index = 0;
        for (AccountStore.Account account : accounts) {
            alive.add(account.id);
            Card card = cards.get(account.id);
            if (card == null) { card = new Card(account.id); cards.put(account.id, card); addView(card); }
            card.account = account; card.listIndex = index++; card.bind();
        }
        for (String id : new ArrayList<>(cards.keySet())) if (!alive.contains(id)) removeView(cards.remove(id));
        resort(); requestLayout();
    }

    void updateQuota(String id) {
        Card card = cards.get(id);
        if (card == null || quotas == null) return;
        card.bind(); resort(); requestLayout();
    }
    void updateAvatar(String id, Bitmap image) {
        Card card = cards.get(id);
        if (card != null && card.ring != null) card.ring.setPhoto(image);
    }
    record Anchor(String id, int slot, int offset) { }
    Anchor anchor(int position) {
        Card first = null;
        for (Card card : order) {
            if (card.getVisibility() == GONE) continue;
            if (first == null) first = card;
            if (card.targetY > position) break;
            if (card.targetY != first.targetY) first = card;
        }
        return first == null ? new Anchor("", 0, 0) : new Anchor(first.id, order.indexOf(first), position - first.targetY);
    }
    int position(Anchor anchor) {
        if (order.isEmpty()) return 0;
        Card card = cards.get(anchor.id());
        if (card == null) card = order.get(Math.max(0, Math.min(order.size() - 1, anchor.slot())));
        if (card.getVisibility() == GONE) return outY;
        return card.targetY + Math.min(anchor.offset(), Math.max(0, card.getMeasuredHeight() - 1));
    }
    String currentId() { return currentId; }
    void settlePositions() { }
    void restoreDetailsFrom(AccountDeck previous) {
        outExpanded = previous.outExpanded; resort(); requestLayout();
        Card card = cards.get(previous.menuId);
        if (card == null) return;
        if (previous.detailsWereOpen || previous.quotaDialog != null && previous.quotaDialog.isShowing()) showQuota(card);
        else if (previous.menuWasOpen || previous.actionMenu != null && previous.actionMenu.isShowing()) menu(card);
    }
    private void toggleGrid() {
        grid = !grid;
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(GRID_KEY, grid).apply();
        for (Card card : cards.values()) card.bind();
        resort(); requestLayout(); // Sidebar restores an account anchor, never the old pixel position.
    }
    private void resort() {
        Card active = cards.get(currentId);
        ArrayList<Card> live = new ArrayList<>(), spent = new ArrayList<>();
        for (Card card : cards.values()) if (card != active) (card.sunk() ? spent : live).add(card);
        live.sort((a, b) -> {
            if ((a.quota != null) != (b.quota != null)) return a.quota != null ? -1 : 1;
            int byUse = Double.compare(a.quota == null || a.quota.used() == null ? 0 : a.quota.used(),
                b.quota == null || b.quota.used() == null ? 0 : b.quota.used());
            return byUse == 0 ? Integer.compare(a.listIndex, b.listIndex) : byUse;
        });
        spent.sort((a, b) -> Integer.compare(a.listIndex, b.listIndex));
        order.clear();
        if (active != null) order.add(active);
        order.addAll(live); order.addAll(spent);
        for (Card card : order) card.setVisibility(card != active && card.sunk() && !outExpanded ? GONE : VISIBLE);
        liveLabel.setVisibility(live.isEmpty() ? GONE : VISIBLE);
        liveText.setText("可切换 " + live.size());
        spreadToggle.setText(grid ? "切单列" : "切双列");
        spreadToggle.setContentDescription(spreadToggle.getText());
        outLabel.setVisibility(spent.isEmpty() ? GONE : VISIBLE);
        outLabel.setText("已用完 / 不可用 " + spent.size() + (outExpanded ? "    收起 ⌃" : "    展开 ⌄"));
        outLabel.setContentDescription(outLabel.getText());
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), gap = ui.dp(6), y = 0, column = 0, rowHeight = 0;
        int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        liveLabel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ui.rowHeight(24), MeasureSpec.EXACTLY));
        outLabel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ui.rowHeight(30), MeasureSpec.EXACTLY));
        boolean liveStarted = false, outStarted = false;
        for (Card card : order) {
            if (card.active) {
                card.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), free);
                card.targetX = card.targetY = 0; y = card.getMeasuredHeight(); continue;
            }
            if (card.sunk() && !outStarted) {
                if (column != 0) { y += rowHeight + gap; column = 0; }
                outY = y + ui.dp(10); y = outY + outLabel.getMeasuredHeight(); outStarted = true;
            } else if (!card.sunk() && !liveStarted) {
                liveY = y + ui.dp(10); y = liveY + liveLabel.getMeasuredHeight(); liveStarted = true;
            }
            if (card.getVisibility() == GONE) { card.targetY = outY; continue; }
            boolean tiled = grid && !card.sunk();
            int cellWidth = tiled ? (width - gap) / 2 : width;
            int height = ui.rowHeight(card.sunk() ? 34 : tiled ? 46 : 42);
            card.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            card.targetX = tiled ? column * (cellWidth + gap) : 0; card.targetY = y; rowHeight = height;
            if (tiled && column == 0) column = 1;
            else { y += height + (tiled ? gap : ui.dp(2)); column = 0; }
        }
        if (column != 0) y += rowHeight;
        setMeasuredDimension(width, y);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        liveLabel.layout(0, liveY, r - l, liveY + liveLabel.getMeasuredHeight());
        outLabel.layout(0, outY, r - l, outY + outLabel.getMeasuredHeight());
        for (Card card : order) if (card.getVisibility() != GONE)
            card.layout(card.targetX, card.targetY, card.targetX + card.getMeasuredWidth(), card.targetY + card.getMeasuredHeight());
    }
    private void tap(Card card) {
        if (card.active) { host.openAccount(card.account); return; }
        if (card.sunk()) {
            card.shake();
            host.notice("「" + card.account.name + "」" + (!card.usable ? "需要更新 Android System WebView 后才能使用。" : "本周额度已用完"));
        } else host.switchAccount(card.account);
    }
    private void menu(Card card) {
        if (actionMenu != null) actionMenu.dismiss();
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        menuId = card.id;
        CharSequence[] actions = card.usable ? new CharSequence[]{"刷新额度", "编辑账号", "额度明细", "删除账号"} : new CharSequence[]{"额度明细", "删除账号"};
        actionMenu = MobileUi.dialog(getContext()).setTitle(card.account.name).setItems(actions, (dialog, which) -> {
            if (cards.get(card.id) != card) return;
            switch (actions[which].toString()) {
                case "刷新额度" -> host.refreshAccount(card.account);
                case "编辑账号" -> host.editAccount(card.account);
                case "额度明细" -> showQuota(card);
                case "删除账号" -> host.deleteAccount(card.account);
            }
        }).show();
        actionMenu.setOnDismissListener(ignored -> actionMenu = null);
    }
    private void showQuota(Card card) {
        menuId = card.id;
        AccountCardState saved = quotas.card(card.id);
        long now = System.currentTimeMillis();
        AccountQuota data = saved.quota(now);
        String reset = data == null ? "" : data.resetLabel(now);
        String detail = "周额度：" + (data == null ? "待获取" : data.weekly(now)) + AccountCardLabels.time(saved.observed("usage"))
            + (reset.isEmpty() ? "" : "\n" + reset) + "\n同步状态：" + quotas.state(card.id)
            + "\n\n" + AccountCardLabels.extraDetails(saved) + "\n\n" + AccountCardLabels.inviteDetails(saved);
        quotaDialog = MobileUi.dialog(getContext()).setTitle(card.account.name + " · 额度明细").setMessage(detail).setPositiveButton("关闭", null).show();
        quotaDialog.setOnDismissListener(ignored -> quotaDialog = null);
    }
    @Override protected void onDetachedFromWindow() {
        menuWasOpen = actionMenu != null && actionMenu.isShowing(); detailsWereOpen = quotaDialog != null && quotaDialog.isShowing();
        if (actionMenu != null) actionMenu.dismiss();
        if (quotaDialog != null) quotaDialog.dismiss();
        super.onDetachedFromWindow();
    }

    private final class Card extends LinearLayout {
        final String id;
        AccountStore.Account account;
        AccountQuota quota;
        AccountFace ring;
        int listIndex, targetX, targetY;
        boolean active, usable, exhausted;
        ObjectAnimator motion;
        Card(String id) {
            super(AccountDeck.this.getContext()); this.id = id;
            setOrientation(VERTICAL); setGravity(Gravity.CENTER_VERTICAL);
            setOnClickListener(view -> tap(this)); setOnLongClickListener(view -> { menu(this); return true; });
        }
        boolean sunk() { return !usable || exhausted; }
        void bind() {
            removeAllViews(); active = id.equals(currentId); usable = AccountDeck.this.usable.test(account);
            AccountCardState saved = quotas.card(id);
            long now = System.currentTimeMillis(); quota = saved.quota(now);
            exhausted = quota != null && quota.currentWeek(now) && quota.used() != null && quota.used() >= 100;
            boolean tiled = grid && !active && !sunk(), out = sunk() && !active;
            setAlpha(out ? .65f : 1f); setMinimumHeight(active ? ui.rowHeight(124) : 0);
            setPadding(ui.dp(active ? 10 : 8), ui.dp(active ? 10 : 0), ui.dp(active ? 10 : 8), ui.dp(active ? 10 : 0));
            setBackground(ui.touchable(active ? ACTIVE_CARD : tiled ? RAISED : Color.TRANSPARENT, out ? 8 : 12, active ? LINE_STRONG : tiled ? LINE : 0));
            setElevation(active ? ui.dp(1) : 0);
            LinearLayout head = ui.row();
            int faceSize = active ? 32 : out ? 18 : tiled ? 20 : 24;
            ring = new AccountFace(getContext(), ui, account, faceSize, active, usable, quota, quotas.avatar(id));
            head.addView(ring, new LayoutParams(ui.dp(faceSize), ui.dp(faceSize)));
            LinearLayout words = ui.column();
            words.setPadding(ui.dp(active ? 10 : tiled ? 6 : 8), 0, ui.dp(4), 0);
            LinearLayout names = ui.nameRow();
            TextView name = ui.single(ui.text(account.name, active ? 13.5f : tiled || out ? 12 : 12.5f, out ? MUTED : INK, !out));
            names.addView(name, new LayoutParams(-2, -2));
            if (!tiled && !out && !saved.plan().isEmpty()) {
                TextView tag = ui.single(ui.meta(saved.plan().toUpperCase(Locale.ROOT), active ? 9.5f : 8.5f, active ? TEXT2 : MUTED));
                tag.setPadding(ui.dp(active ? 6 : 4), ui.dp(1), ui.dp(active ? 6 : 4), ui.dp(1)); tag.setMaxWidth(ui.dp(52));
                tag.setBackground(ui.shape(Color.TRANSPARENT, 4, LINE_STRONG));
                LayoutParams p = new LayoutParams(-2, -2); p.setMarginStart(ui.dp(6)); names.addView(tag, p);
            }
            words.addView(names, new LayoutParams(-1, -2));
            if (!tiled && !out) {
                ProxyProfile route = proxies.find(account.proxyId());
                String label = !usable ? "需更新 WebView" : route == null ? account.networkLabel(proxies) : route.scheme.toUpperCase(Locale.ROOT) + " · " + route.name;
                String cached = AccountChoices.cachedLabel(quota, now);
                if (!active && !cached.isEmpty()) label = cached + " · " + label;
                ui.block(words, ui.single(ui.meta(label, active ? 9.5f : 9, MUTED)), 2);
            }
            head.addView(words, new LayoutParams(0, -2, 1));
            int tone = !usable || quota == null ? MUTED : quotaColor(quota.used() == null ? 0d : quota.used());
            String percent = !usable ? "不可用" : quota == null ? "待同步" : quota.used() == null ? "不限" : exhausted ? "用完" : Math.round(quota.used()) + "%";
            LinearLayout numbers = ui.column(); numbers.setGravity(Gravity.END);
            numbers.addView(ui.single(ui.metaStrong(percent, active ? 15 : tiled ? 10.5f : out ? 10 : 11.5f, tone)));
            String cached = AccountChoices.cachedLabel(quota, now);
            if (tiled && !cached.isEmpty()) ui.block(numbers, ui.meta(cached, 8, MUTED), 1);
            if (!active && !tiled && !out) {
                LayoutParams p = new LayoutParams(ui.dp(36), ui.dp(2.5f)); p.topMargin = ui.dp(4);
                numbers.addView(ui.miniProgress(quota == null ? null : quota.used(), quota != null), p);
            }
            head.addView(numbers, new LayoutParams(!active && !tiled && !out ? ui.dp(44) : -2, -2));
            if (active || !tiled && !out) {
                android.widget.ImageButton more = ui.compactIconButton(active ? R.drawable.ic_more : R.drawable.ic_more_vertical, "账号操作 · " + account.name, MUTED, () -> menu(this));
                more.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); more.setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5));
                LayoutParams p = new LayoutParams(ui.dp(24), ui.dp(28)); p.setMarginStart(ui.dp(4)); head.addView(more, p);
            }
            addView(head, new LayoutParams(-1, -2));
            if (tiled) {
                LayoutParams p = new LayoutParams(-1, ui.dp(2.5f)); p.topMargin = ui.dp(4);
                addView(ui.miniProgress(quota == null ? null : quota.used(), quota != null), p);
            }
            if (active) details(saved, now);
            setContentDescription(account.name + "，" + percent + "，" + (quota == null ? quotas.state(id) : quota.weekly(now))
                + (active ? "，当前账号，点按打开网页" : sunk() ? "，不可切换" : "，点按切换") + "，长按查看账号操作");
        }
        private void details(AccountCardState saved, long now) {
            View ticks = ui.tickBar(); MobileUi.setTicks(ticks, quota == null ? null : quota.used(), quota != null);
            LayoutParams tickPos = new LayoutParams(-1, ui.dp(6)); tickPos.topMargin = ui.dp(8); addView(ticks, tickPos);
            LinearLayout info = ui.row();
            String reset = quota == null ? "待同步" : quota.used() == null ? "不限量" : quota.resetLabel(now)
                + (quota.resetLabel(now).isEmpty() ? "" : " · ") + quotaLevel(quota.used());
            if (quota != null && !quota.currentWeek(now)) reset = "上次 · " + reset;
            info.addView(ui.single(ui.meta(reset, 9.5f, MUTED)), new LayoutParams(0, -2, 1));
            boolean remaining = saved.extraRemaining() != null && saved.extraRemaining() > 0;
            TextView extra = ui.single(ui.metaStrong(AccountCardLabels.extra(saved), 9.5f, remaining ? INK : MUTED)); extra.setMaxWidth(ui.dp(134));
            LayoutParams extraPos = new LayoutParams(-2, -2); extraPos.setMarginStart(ui.dp(8)); info.addView(extra, extraPos); ui.block(this, info, 6);
            View rule = new View(getContext()); rule.setBackgroundColor(LINE);
            LayoutParams rulePos = new LayoutParams(-1, ui.dp(1)); rulePos.topMargin = ui.dp(6); addView(rule, rulePos);
            LinearLayout invitation = ui.row(); String code = saved.inviteCode();
            TextView invite = ui.single(ui.meta("邀请码 " + (code.isEmpty() ? saved.inviteExhausted() ? "已满" : "待获取" : code), 9.5f, code.isEmpty() ? MUTED : TEXT2));
            invite.setMinHeight(ui.dp(22)); invite.setGravity(Gravity.CENTER_VERTICAL);
            if (!code.isEmpty()) {
                ui.touch(invite, Color.TRANSPARENT, 4, 0, () -> {
                    String latest = quotas.card(id).inviteCode(); if (latest.isEmpty()) return;
                    ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class); if (clipboard == null) return;
                    try { clipboard.setPrimaryClip(ClipData.newPlainText("Muse 邀请码", latest)); host.notice("已复制邀请码：" + latest); }
                    catch (RuntimeException unavailable) { host.notice("无法复制邀请码"); }
                });
                invite.setContentDescription("邀请码 " + code + "，点按复制");
            }
            LinearLayout codeRow = ui.nameRow(); codeRow.addView(invite, new LayoutParams(-2, -2));
            if (!code.isEmpty()) {
                View copy = ui.iconView(R.drawable.ic_copy, MUTED, 10);
                LayoutParams copyPosition = new LayoutParams(ui.dp(10), ui.dp(10)); copyPosition.setMarginStart(ui.dp(4));
                codeRow.addView(copy, copyPosition); copy.setOnClickListener(view -> invite.performClick());
                copy.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            invitation.addView(codeRow, new LayoutParams(0, -2, 1));
            TextView count = ui.single(ui.meta("邀请 " + AccountCardLabels.inviteCount(saved), 9.5f, TEXT2)); count.setMaxWidth(ui.dp(100));
            LayoutParams countPos = new LayoutParams(-2, -2); countPos.setMarginStart(ui.dp(8)); invitation.addView(count, countPos); ui.block(this, invitation, 6);
            setTooltipText((quota == null ? "待同步" : quota.weekly(now)) + AccountCardLabels.time(saved.observed("usage"))
                + "\n" + AccountCardLabels.extraSummary(saved) + "\n" + AccountCardLabels.inviteDetails(saved));
        }
        void shake() {
            if (!ValueAnimator.areAnimatorsEnabled()) return;
            if (motion != null) motion.cancel();
            motion = ObjectAnimator.ofFloat(this, TRANSLATION_X, 0, -ui.dp(5), ui.dp(4), -ui.dp(3), ui.dp(2), 0); motion.setDuration(360); motion.start();
        }
        @Override protected void onDetachedFromWindow() {
            if (motion != null) motion.cancel(); setTranslationX(0); super.onDetachedFromWindow();
        }
    }
}
