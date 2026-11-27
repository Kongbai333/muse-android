package com.muse.workspace.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import static com.muse.workspace.mobile.MobileUi.*;

/**
 * 账号叠放：当前账号完整显示在顶部；其余按剩余额度叠成 38dp 的条，额度用完或不可用的压成 30dp 沉底。
 * 点条切换（被点的卡抬起、飞到顶部展开，旧的当前卡收成条插回队列）；点当前卡打开网页；长按刷新额度或编辑。
 * 卡片视图按账号复用，重新绑定时只移动位置，所以侧栏任何一次刷新都会走同一套动画。
 */
final class AccountDeck extends FrameLayout {
    interface Host {
        /** 切到该账号但停留在侧栏；返回 false 表示没切成（例如正在生成）。 */
        boolean switchAccount(AccountStore.Account account);
        void openAccount(AccountStore.Account account);
        void refreshAccount(AccountStore.Account account);
        void editAccount(AccountStore.Account account);
        void notice(String text);
    }

    private static final String SPREAD_KEY = "account-deck-spread";
    private static final int STRIP = 38, OUT = 30, TAIL = 8, GAP = 10, LABEL = 26, OUT_GAP = 12, SPREAD_GAP = 6;
    private static final int MOTION = 440, COUNT = 640;
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);

    private final MobileUi ui;
    private final Host host;
    private final ProxyStore proxies;
    private final HashMap<String, Card> cards = new HashMap<>();
    private final ArrayList<Card> order = new ArrayList<>();
    private final HashMap<View, ObjectAnimator> motions = new HashMap<>();
    private final HashMap<View, Float> targets = new HashMap<>();
    private final LinearLayout liveLabel;
    private final TextView liveText, outLabel, spreadToggle;
    private QuotaSync quotas;
    private Predicate<AccountStore.Account> usable = account -> true;
    private String currentId = "", flying = "";
    private boolean spread, animateNext, staggerNext, laidOut;
    private float liveY, outY;

    AccountDeck(Context context, MobileUi ui, Host host, ProxyStore proxies) {
        super(context);
        this.ui = ui;
        this.host = host;
        this.proxies = proxies;
        setClipChildren(false);
        setClipToPadding(false);
        spread = context.getSharedPreferences(MobileUi.PREFS, Context.MODE_PRIVATE).getBoolean(SPREAD_KEY, false);

        liveLabel = ui.row();
        liveText = ui.meta("", 10, MUTED);
        liveLabel.setPadding(ui.dp(2), 0, 0, ui.dp(4));
        liveLabel.addView(liveText, new LinearLayout.LayoutParams(0, -2, 1));
        spreadToggle = ui.text("", 12, TEXT2, true);
        spreadToggle.setGravity(Gravity.CENTER);
        spreadToggle.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        ui.touch(spreadToggle, Color.TRANSPARENT, R_SM, 0, this::toggleSpread);
        liveLabel.addView(spreadToggle, new LinearLayout.LayoutParams(-2, ui.dp(LABEL - 4)));
        addView(liveLabel, new FrameLayout.LayoutParams(-1, ui.dp(LABEL)));

        outLabel = ui.single(ui.meta("", 10, MUTED));
        outLabel.setPadding(ui.dp(2), 0, 0, 0);
        outLabel.setGravity(Gravity.CENTER_VERTICAL);
        addView(outLabel, new FrameLayout.LayoutParams(-1, ui.dp(LABEL - 4)));
    }

    /** 重新绑定账号列表；返回当前账号是否变了（侧栏据此滚回顶部）。 */
    boolean bind(List<AccountStore.Account> accounts, AccountStore.Account current,
                 Predicate<AccountStore.Account> usable, QuotaSync quotas) {
        this.usable = usable;
        this.quotas = quotas;
        String previous = currentId;
        currentId = current == null ? "" : current.id;
        HashSet<String> alive = new HashSet<>();
        int index = 0;
        for (AccountStore.Account account : accounts) {
            alive.add(account.id);
            Card card = cards.get(account.id);
            if (card == null) {
                card = new Card(getContext(), account.id);
                cards.put(account.id, card);
                addView(card, new FrameLayout.LayoutParams(-1, -2));
            }
            card.account = account;
            card.listIndex = index++;
        }
        for (Iterator<Map.Entry<String, Card>> it = cards.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Card> entry = it.next();
            if (alive.contains(entry.getKey())) continue;
            Card gone = entry.getValue();
            ObjectAnimator motion = motions.remove(gone);
            if (motion != null) motion.cancel();
            targets.remove(gone);
            gone.stopCounting();
            removeView(gone);
            it.remove();
        }
        for (Card card : cards.values()) refresh(card);
        boolean changed = laidOut && !previous.equals(currentId);
        Card active = cards.get(currentId);
        if (changed && active != null) active.countFrom(0f, 140);
        resort();
        animateNext = laidOut;
        requestLayout();
        return changed;
    }

    void updateQuota(String id) {
        Card card = cards.get(id);
        if (card == null || card.account == null) return;
        refresh(card);
        resort();
        animateNext = laidOut;
        requestLayout();
    }

    void updateAvatar(String id, Bitmap image) {
        Card card = cards.get(id);
        if (card != null) card.ring.setPhoto(image);
    }

    /** 侧栏打开时，当前账号的额度从 0 数到实际值。 */
    void replay() {
        Card active = cards.get(currentId);
        if (active != null) active.countFrom(0f, 220);
    }

    boolean isEmpty() { return cards.isEmpty(); }

    // ---------------------------------------------------------------- 数据

    private void refresh(Card card) {
        AccountStore.Account account = card.account;
        long now = System.currentTimeMillis();
        AccountQuota data = quotas == null ? null : quotas.value(account.id);
        String state = quotas == null ? "待同步" : quotas.state(account.id);
        card.bind(account, account.id.equals(currentId), usable.test(account), data, state, now);
    }

    /** 当前账号在最前；可切换的按已用额度从低到高（待同步排在后面）；用完与不可用的沉底。 */
    private void resort() {
        Card active = cards.get(currentId);
        ArrayList<Card> live = new ArrayList<>(), out = new ArrayList<>();
        for (Card card : cards.values()) {
            if (card == active) continue;
            if (card.sunk()) out.add(card); else live.add(card);
        }
        live.sort((a, b) -> {
            if (a.known != b.known) return a.known ? -1 : 1;
            int byUse = Double.compare(a.rankValue(), b.rankValue());
            return byUse != 0 ? byUse : Integer.compare(a.listIndex, b.listIndex);
        });
        out.sort((a, b) -> Integer.compare(a.listIndex, b.listIndex));
        order.clear();
        if (active != null) order.add(active);
        order.addAll(live);
        order.addAll(out);
        for (int slot = 0; slot < order.size(); slot++) {
            Card card = order.get(slot);
            card.lastSlot = card.slot < 0 ? slot : card.slot;
            card.slot = slot;
        }
        applyDepth();

        boolean labels = order.size() > 1;
        liveLabel.setVisibility(labels && !live.isEmpty() ? VISIBLE : GONE);
        liveText.setText("可切换 " + live.size());
        spreadToggle.setText(spread ? "叠放" : "展开");
        spreadToggle.setContentDescription(spread ? "叠放账号列表" : "展开账号列表，显示每个账号的额度刻度");
        boolean allSpent = true;
        for (Card card : out) if (!card.exhausted) allSpent = false;
        outLabel.setVisibility(labels && !out.isEmpty() ? VISIBLE : GONE);
        outLabel.setText((allSpent ? "已用完 " : "暂不可用 ") + out.size());
    }

    /** 后面的卡压住前面卡的下沿；正在飞的卡临时抬到最上层。 */
    private void applyDepth() {
        float step = ui.dp(1) * 0.02f;
        for (Card card : order) card.setTranslationZ(card.id.equals(flying) ? ui.dp(8) : card.slot * step);
    }

    // ---------------------------------------------------------------- 布局与动画

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int exact = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child == liveLabel || child == outLabel) {
                child.measure(exact, MeasureSpec.makeMeasureSpec(child.getLayoutParams().height, MeasureSpec.EXACTLY));
            } else if (child.getVisibility() != GONE) child.measure(exact, free);
        }
        setMeasuredDimension(width, plan());
    }

    /** 算出每张卡和两行标签的目标位置，返回整叠的高度。 */
    private int plan() {
        int y = 0;
        int index = 0;
        Card active = order.isEmpty() || !order.get(0).id.equals(currentId) ? null : order.get(0);
        if (active != null) {
            active.targetY = 0;
            y = active.getMeasuredHeight();
            index = 1;
        }
        ArrayList<Card> live = new ArrayList<>(), out = new ArrayList<>();
        for (; index < order.size(); index++) (order.get(index).sunk() ? out : live).add(order.get(index));
        if (!live.isEmpty()) {
            if (active != null) y += ui.dp(GAP);
            liveY = y;
            y += ui.dp(LABEL);
            for (int i = 0; i < live.size(); i++) {
                Card card = live.get(i);
                card.targetY = y;
                boolean last = i == live.size() - 1;
                y += last ? card.getMeasuredHeight() : spread ? card.getMeasuredHeight() + ui.dp(SPREAD_GAP) : ui.dp(STRIP);
            }
        }
        if (!out.isEmpty()) {
            if (y > 0) y += ui.dp(OUT_GAP);
            outY = y;
            y += ui.dp(LABEL - 4);
            for (int i = 0; i < out.size(); i++) {
                Card card = out.get(i);
                card.targetY = y;
                boolean last = i == out.size() - 1;
                y += last ? card.getMeasuredHeight() : spread ? card.getMeasuredHeight() + ui.dp(SPREAD_GAP) : ui.dp(OUT);
            }
        }
        return y;
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() != GONE) child.layout(0, 0, width, child.getMeasuredHeight());
        }
        boolean animate = animateNext && laidOut && ValueAnimator.areAnimatorsEnabled() && isShown();
        boolean stagger = staggerNext;
        animateNext = false;
        staggerNext = false;
        place(liveLabel, liveY, animate, 0);
        place(outLabel, outY, animate, 0);
        for (Card card : order) {
            int delay = stagger ? card.slot * 18 : card.id.equals(flying) ? 0
                : Math.min(Math.abs(card.slot - card.lastSlot), 6) * 14 + 20;
            place(card, card.targetY, animate, delay);
            card.lastSlot = card.slot;
        }
        laidOut = true;
    }

    private void place(View view, float target, boolean animate, int delay) {
        Float last = targets.get(view);
        ObjectAnimator running = motions.get(view);
        // 文字变化等引起的普通重排：正在去同一个位置的动画继续走完。
        if (last != null && last == target && running != null && running.isStarted()) return;
        if (running != null) running.cancel();
        motions.remove(view);
        targets.put(view, target);
        if (!animate || last == null || Math.abs(view.getTranslationY() - target) < 1f) {
            view.setTranslationY(target);
            return;
        }
        ObjectAnimator motion = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, target);
        motion.setDuration(MOTION);
        motion.setStartDelay(delay);
        motion.setInterpolator(EASE);
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (motions.get(view) == animation) motions.remove(view);
            }
        });
        motions.put(view, motion);
        motion.start();
    }

    @Override protected void onDetachedFromWindow() {
        for (ObjectAnimator motion : motions.values()) motion.cancel();
        motions.clear();
        for (Card card : cards.values()) card.stopCounting();
        super.onDetachedFromWindow();
    }

    private void toggleSpread() {
        spread = !spread;
        getContext().getSharedPreferences(MobileUi.PREFS, Context.MODE_PRIVATE).edit().putBoolean(SPREAD_KEY, spread).apply();
        for (Card card : cards.values()) refresh(card);
        resort();
        animateNext = laidOut;
        staggerNext = true;
        requestLayout();
    }

    // ---------------------------------------------------------------- 交互

    private void tap(Card card) {
        AccountStore.Account account = card.account;
        if (account == null) return;
        if (card.id.equals(currentId)) { host.openAccount(account); return; }
        if (!card.usable) {
            shake(card);
            host.notice("「" + account.name + "」需要更新 Android System WebView 后才能使用。");
            return;
        }
        if (card.exhausted) {
            shake(card);
            host.notice("「" + account.name + "」本周额度已用完" + (card.resetText.isEmpty() ? "" : " · " + card.resetText));
            return;
        }
        flying = card.id;
        applyDepth();
        boolean motion = ValueAnimator.areAnimatorsEnabled();
        if (motion) card.animate().scaleX(1.035f).scaleY(1.035f).setDuration(130).setInterpolator(EASE)
            .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(EASE).start()).start();
        if (!host.switchAccount(account)) {
            flying = "";
            card.animate().cancel();
            card.setScaleX(1f);
            card.setScaleY(1f);
            applyDepth();
            return;
        }
        performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
        postDelayed(() -> {
            flying = "";
            applyDepth();
        }, MOTION + 160);
    }

    private void menu(Card card) {
        AccountStore.Account account = card.account;
        if (account == null || !card.usable) return;
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        MobileUi.dialog(getContext()).setTitle(account.name)
            .setItems(new String[]{"刷新额度", "编辑账号", "额度明细"}, (dialog, which) -> {
                if (which == 0) host.refreshAccount(account);
                else if (which == 1) host.editAccount(account);
                else if (which == 2) showQuota(card);
            }).show();
    }

    private void showQuota(Card card) {
        if (quotas == null) return;
        AccountAppearance.Skin skin = AccountAppearance.read(getContext(), card.id);
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        content.setBackground(skin.background(getContext(), false));
        QuotaCard detail = new QuotaCard(getContext(), ui, ignored -> {}, () -> {}, skin);
        detail.update(quotas.card(card.id), quotas.state(card.id));
        content.addView(detail.view);
        content.addView(detail.details);
        MobileUi.dialog(getContext()).setTitle(card.account.name).setView(content).setPositiveButton("关闭", null).show();
    }

    private void shake(Card card) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        float d = ui.dp(1);
        ObjectAnimator shake = ObjectAnimator.ofFloat(card, View.TRANSLATION_X, 0, -5 * d, 4 * d, -3 * d, 2 * d, 0);
        shake.setDuration(360);
        shake.start();
    }

    // ---------------------------------------------------------------- 卡片

    private final class Card extends LinearLayout {
        final String id;
        final LinearLayout head, body, words, nameLine;
        final Ring ring;
        final TextView name, tag, network, percent, reset, invite;
        final View ticks;
        AccountStore.Account account;
        int listIndex, slot = -1, lastSlot = -1;
        float targetY;
        boolean active, usable, known, unlimited, exhausted;
        Double used;
        String resetText = "", mode = "";
        float shown = -1f;
        ValueAnimator counter;

        Card(Context context, String id) {
            super(context);
            this.id = id;
            setOrientation(VERTICAL);
            setElevation(ui.dp(1.5f));
            setClickable(true);
            setFocusable(true);
            setOnClickListener(view -> tap(this));
            setOnLongClickListener(view -> { menu(this); return true; });

            head = ui.row();
            ring = new Ring(getContext());
            head.addView(ring, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
            words = ui.column();
            words.setPadding(ui.dp(9), 0, ui.dp(6), 0);
            nameLine = ui.row();
            name = ui.single(ui.text("", 13, INK, true));
            nameLine.addView(name, new LinearLayout.LayoutParams(-2, -2, 0f));
            tag = ui.tag("");
            LinearLayout.LayoutParams tagPosition = new LinearLayout.LayoutParams(-2, -2);
            tagPosition.setMarginStart(ui.dp(6));
            nameLine.addView(tag, tagPosition);
            words.addView(nameLine, new LinearLayout.LayoutParams(-1, -2));
            network = ui.single(ui.meta("", 9.5f, MUTED));
            LinearLayout.LayoutParams networkPosition = new LinearLayout.LayoutParams(-1, -2);
            networkPosition.topMargin = ui.dp(5);
            words.addView(network, networkPosition);
            head.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            percent = ui.single(ui.metaStrong("", 12.5f, INK));
            percent.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            percent.setMaxWidth(ui.dp(84));
            head.addView(percent, new LinearLayout.LayoutParams(-2, -2));
            addView(head, new LinearLayout.LayoutParams(-1, ui.dp(STRIP)));

            body = ui.column();
            ticks = ui.tickBar();
            body.addView(ticks, new LinearLayout.LayoutParams(-1, ui.dp(7)));
            LinearLayout meta = ui.row();
            reset = ui.single(ui.meta("", 9.5f, MUTED));
            meta.addView(reset, new LinearLayout.LayoutParams(0, -2, 1));
            invite = ui.single(ui.meta("", 9.5f, MUTED));
            invite.setOnClickListener(ignored -> showQuota(this));
            invite.setMaxWidth(ui.dp(140));
            meta.addView(invite, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams metaPosition = new LinearLayout.LayoutParams(-1, -2);
            metaPosition.topMargin = ui.dp(8);
            body.addView(meta, metaPosition);
            LinearLayout.LayoutParams bodyPosition = new LinearLayout.LayoutParams(-1, -2);
            bodyPosition.topMargin = ui.dp(2);
            addView(body, bodyPosition);
        }

        boolean sunk() { return exhausted || !usable; }

        /** 排序用：不限量按 0 算。 */
        double rankValue() { return !known || used == null ? 0 : used; }

        void bind(AccountStore.Account account, boolean active, boolean usable, AccountQuota data, String state, long now) {
            boolean wasKnown = known;
            Double before = used;
            this.active = active;
            this.usable = usable;
            known = data != null && data.currentWeek(now);
            used = known ? data.used() : null;
            unlimited = known && used == null;
            exhausted = known && used != null && used >= 100;

            name.setText(account.name);
            AccountCardState saved = quotas == null ? null : quotas.card(id);
            String plan = saved == null ? "" : saved.plan();
            tag.setText(plan.toUpperCase(Locale.ROOT));
            ProxyProfile profile = proxies.find(account.proxyId());
            network.setText(!usable ? "需更新 WebView" : profile != null
                ? profile.scheme.toUpperCase(Locale.ROOT) + " · " + profile.name : account.networkLabel(proxies));
            resetText = data == null ? "" : data.resetLabel(now);
            invite.setText(saved == null ? "额度明细" : AccountCardLabels.invitation(saved));
            invite.setContentDescription(invite.getText() + "，查看额度和邀请明细");
            ring.setFace(account, active, sunk());

            String next = active ? "active" : sunk() ? "out" : "strip";
            boolean detailed = !sunk() && (active || spread);
            next += detailed ? "+" : "";
            if (!next.equals(mode)) applyMode(active, detailed);
            if (!next.equals(mode) && detailed && !mode.isEmpty() && ValueAnimator.areAnimatorsEnabled()) {
                body.setAlpha(0f);
                body.animate().alpha(1f).setStartDelay(160).setDuration(260).start();
            }
            mode = next;
            tag.setVisibility(sunk() && !active || plan.isEmpty() ? GONE : VISIBLE);

            if (!usable) showFixed("不可用");
            else if (!known) showFixed(data == null ? state : "待刷新");
            else if (unlimited) showFixed("不限");
            else if (exhausted) showFixed("用完");
            else if (counter != null && counter.isStarted()) {
                if (before == null || Math.abs(before - used) >= 0.5) countFrom(shown, 0);
            } else if (wasKnown && before != null && laidOut && Math.abs(before - used) >= 0.5) countFrom((float) (double) before, 60);
            else show(used.floatValue());

            String describe = account.name + (plan.isEmpty() ? "" : "，" + plan) + "，"
                + (!usable ? "需更新 WebView 后可用" : !known ? "额度" + percent.getText()
                : unlimited ? "不限量" : exhausted ? "本周额度已用完" : "已用 " + Math.round(used) + "%，" + quotaLevel(used))
                + (active ? "，当前账号，点按打开网页" : sunk() ? "" : "，点按切换") + (usable ? "，长按刷新或编辑" : "");
            setContentDescription(describe);
        }

        private void applyMode(boolean active, boolean detailed) {
            boolean out = sunk() && !active;
            int fill = active ? (NIGHT ? ColorUtils.blendARGB(RAISED, INK, 0.05f) : SURFACE)
                : out ? ColorUtils.blendARGB(SURFACE, NIGHT ? RAISED : BG, 0.55f) : (NIGHT ? RAISED : BG);
            setBackground(ui.touchable(fill, R_MD, active ? LINE_STRONG : LINE));
            setPadding(ui.dp(11), 0, ui.dp(11), ui.dp(detailed ? 12 : TAIL));
            int headHeight = active || (detailed && spread) ? 54 : out ? OUT : STRIP;
            head.getLayoutParams().height = ui.dp(headHeight);
            int ringSize = headHeight >= 54 ? 32 : out ? 18 : 24;
            ring.getLayoutParams().width = ui.dp(ringSize);
            ring.getLayoutParams().height = ui.dp(ringSize);
            ring.setSize(ringSize);
            name.setTextSize(out ? 12 : active ? 14 : 13);
            name.setTextColor(out ? MUTED : INK);
            name.setTypeface(android.graphics.Typeface.create(out ? "sans-serif" : "sans-serif-medium", android.graphics.Typeface.NORMAL));
            name.setMaxWidth(ui.dp(active ? 150 : 130));
            network.setVisibility(headHeight >= 54 ? VISIBLE : GONE);
            percent.setTextSize(active ? 15 : out ? 10.5f : 12.5f);
            body.setVisibility(detailed ? VISIBLE : GONE);
            body.animate().cancel();
            body.setAlpha(1f);
            head.requestLayout();
        }

        void countFrom(float from, int delay) {
            if (!usable || !known || used == null || exhausted) return;
            stopCounting();
            float to = used.floatValue();
            if (!ValueAnimator.areAnimatorsEnabled()) { show(to); return; }
            show(Math.max(0f, from));
            ValueAnimator count = ValueAnimator.ofFloat(Math.max(0f, from), to);
            count.setDuration(COUNT);
            count.setStartDelay(delay);
            count.setInterpolator(new DecelerateInterpolator(2f));
            count.addUpdateListener(value -> show((float) value.getAnimatedValue()));
            counter = count;
            count.start();
        }

        void stopCounting() {
            if (counter != null) counter.cancel();
            counter = null;
        }

        /** 环、刻度条和百分比数字用同一个颜色，随数值连续变化。 */
        private void show(float value) {
            shown = value;
            int tone = quotaColor((double) value);
            percent.setText(Math.round(value) + "%");
            percent.setTextColor(tone);
            percent.setTextSize(active ? 15 : 12.5f);
            ring.setProgress(value / 100f, tone);
            MobileUi.setTicks(ticks, (double) value, true);
            reset.setText(resetText.isEmpty() ? quotaLevel(value) : resetText + " · " + quotaLevel(value));
        }

        private void showFixed(String text) {
            stopCounting();
            shown = -1f;
            percent.setText(text);
            percent.setTextColor(unlimited ? Q0 : MUTED);
            percent.setTextSize(active && unlimited ? 15 : 10.5f);
            ring.setProgress(unlimited ? 1f : exhausted ? 1f : 0f, unlimited ? Q0 : MUTED);
            MobileUi.setTicks(ticks, exhausted ? 100d : null, known);
            reset.setText(unlimited ? "不限量" : resetText.isEmpty() ? text : resetText);
        }
    }

    /** 头像 + 外圈额度环：先画灰色轨道，再按已用比例画色弧，头像缩在环内。 */
    private final class Ring extends FrameLayout {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final TextView initial;
        private final ImageView photo;
        private float progress, stroke;
        private Bitmap image;

        Ring(Context context) {
            super(context);
            setWillNotDraw(false);
            track.setStyle(Paint.Style.STROKE);
            arc.setStyle(Paint.Style.STROKE);
            initial = ui.figure("", 10, INK);
            initial.setGravity(Gravity.CENTER);
            addView(initial, new FrameLayout.LayoutParams(-1, -1));
            photo = new ImageView(context);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(RAISED);
            photo.setBackground(oval);
            photo.setClipToOutline(true);
            photo.setVisibility(GONE);
            addView(photo, new FrameLayout.LayoutParams(-1, -1));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            setSize(24);
        }

        void setSize(int sizeDp) {
            stroke = ui.dp(sizeDp >= 30 ? 2.5f : 2f);
            int inset = Math.round(stroke + ui.dp(sizeDp >= 24 ? 2f : 1.5f));
            for (View child : new View[]{initial, photo}) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
                params.setMargins(inset, inset, inset, inset);
                child.setLayoutParams(params);
            }
            initial.setTextSize(Math.max(8f, sizeDp * 0.36f));
            invalidate();
        }

        void setFace(AccountStore.Account account, boolean active, boolean out) {
            String label = account.name == null || account.name.isEmpty() ? "M"
                : account.name.substring(0, account.name.offsetByCodePoints(0, 1));
            initial.setText(label.toUpperCase(Locale.ROOT));
            GradientDrawable face = new GradientDrawable();
            face.setShape(GradientDrawable.OVAL);
            face.setColor(active ? INK : NIGHT ? SURFACE : RAISED);
            initial.setBackground(face);
            initial.setTextColor(active ? BG : out ? MUTED : INK);
            if (image == null && quotas != null) setPhoto(quotas.avatar(account.id));
        }

        void setPhoto(Bitmap bitmap) {
            image = bitmap;
            photo.setImageBitmap(bitmap);
            photo.setVisibility(bitmap == null ? GONE : VISIBLE);
            initial.setVisibility(bitmap == null ? VISIBLE : GONE);
        }

        void setProgress(float value, int color) {
            progress = Math.max(0f, Math.min(1f, value));
            arc.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float half = stroke / 2f;
            box.set(half, half, getWidth() - half, getHeight() - half);
            track.setStrokeWidth(stroke);
            track.setColor(LINE_STRONG);
            canvas.drawOval(box, track);
            if (progress <= 0f) return;
            arc.setStrokeWidth(stroke);
            canvas.drawArc(box, -90f, 360f * progress, false, arc);
        }
    }
}
