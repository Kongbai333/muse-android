package com.muse.workspace.mobile;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import androidx.webkit.WebViewFeature;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static com.muse.workspace.mobile.MobileUi.*;

public final class MainActivity extends AppCompatActivity implements MuseWeb.Host, AccountSidebar.Host {
    private static final int STUDIO = 0, WEB = 1, PROXIES = 2;
    private static final int FILE_PICKER = 610, SAVE_MEDIA = 611, REFERENCE_PICKER = 612;
    private static final String[] IMAGE_RATIOS = {"Auto", "1:1", "16:9", "9:16", "4:3", "3:4"};
    private static final String[] VIDEO_RATIOS = {"Auto", "1:1", "16:9", "9:16"};
    private static final int[] LENGTHS = {5, 6, 8, 10};

    private MobileUi ui;
    private AccountStore accounts;
    private ProxyStore proxies;
    private MuseAvatarCache avatars;
    private StudioReferenceStore references;
    private MuseWeb browser;
    private WebMicrophone microphone;
    private QuotaSync quotas;
    private final Handler quotaHandler = new Handler(Looper.getMainLooper());
    private boolean foreground;
    private final java.util.HashSet<String> loginPageOwners = new java.util.HashSet<>();
    private final Runnable quotaPulse = new Runnable() {
        @Override public void run() {
            if (!foreground || !ready || closing) return;
            syncCurrentQuota();
            quotaHandler.postDelayed(this, 60_000);
        }
    };
    private MobileStudio studio;
    private StudioResults studioResults;
    private boolean multiProfile;
    private AccountTransferUi transferUi;
    private AppUpdateUi updater;
    private FrameLayout root, body, webFrame;
    private LinearLayout main;
    private AccountSidebar sidebar;
    private AccountEditor accountEditor;
    private FrameLayout quickSwitch;
    private ImageView quickSwitchPhoto;
    private TextView quickSwitchInitial, quickSwitchCount;
    private android.widget.PopupWindow accountMenu;
    private boolean switchTucked, switchPeeking, switchRight = true, switchLongPressed;
    private float switchY = 0.3f;
    private TextView webTitle, webSubtitle, webNotice, studioTitle, studioSubtitle;
    private LinearLayout studioAccount;
    private ImageButton sidebarToggle, webBack, submitButton;
    private MobileUi.Segment studioMode;
    private TextView studioAccountValue, studioAccountAvatar;
    private LinearLayout studioComposer;
    private ImageButton studioReferenceButton;
    private LinearLayout studioReferenceStrip;
    private ScrollView studioScroll;
    private EditText studioInput;
    private LinearProgressIndicator webProgress;
    private ValueCallback<Uri[]> fileCallback;
    private boolean fileChooserMultiple;
    private View videoView;
    private WebChromeClient.CustomViewCallback videoCallback;
    private int topInset, bottomInset;
    private int screen = STUDIO;
    private boolean video;
    private String prompt = "";
    private String ratio = "Auto";
    private int seconds = 5;
    private final Handler draftHandler = new Handler(Looper.getMainLooper());
    private final Runnable persistDraft = this::syncStudioDraft;
    private MobileStudio.Entry exporting;
    private boolean closing;
    private boolean ready;
    private boolean studioPinned = true;
    private boolean themeReopenSidebar, themeShowSessions = true;
    private boolean themeNight;
    private int themeGeneration, themeApplied;
    private boolean referenceLoading;
    private String referencePickerSession = "", studioReferenceSignature = "";
    private StudioSession pendingComposerSession;
    private int pendingComposerHeight;
    private float switchTouchX, switchTouchY, switchStartTranslationX, switchStartTranslationY;
    private boolean switchDragging;
    private final Runnable persistComposerHeight = () -> {
        if (pendingComposerSession != null && pendingComposerHeight > 0) {
            studio.updateComposerHeight(pendingComposerSession, pendingComposerHeight);
            pendingComposerSession = null;
        }
    };

    @Override protected void onCreate(Bundle state) {
        CrashReport.install(this);
        if (CrashReport.pending(this)) {
            // 上次闪退：先显示错误信息，不加载主界面。
            super.onCreate(state);
            startActivity(new Intent(this, CrashActivity.class));
            finish();
            return;
        }
        MobileUi.applyNightMode(this);
        super.onCreate(state);
        try {
            buildInterface(state);
        } catch (Throwable error) {
            // 启动时出错：不闪退，直接显示错误信息。
            ready = false;
            CrashReport.save(this, Thread.currentThread(), error);
            startActivity(new Intent(this, CrashActivity.class));
            finish();
        }
    }

    private void buildInterface(Bundle state) {
        ui = new MobileUi(this);
        themeNight = MobileUi.NIGHT;
        proxies = new ProxyStore(this);
        accounts = new AccountStore(this, proxies);
        syncAccountOrder();
        avatars = new MuseAvatarCache(this);
        references = new StudioReferenceStore(this);
        microphone = new WebMicrophone(this);
        browser = new MuseWeb(this, this, microphone, proxies);
        quotas = new QuotaSync(this, browser, avatars, this::updateQuota);
        studio = new MobileStudio(this, browser, this::updateStudio);
        try {
            multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        } catch (RuntimeException ignored) {
            multiProfile = false;
        }
        transferUi = new AccountTransferUi(this, accounts, proxies, browser, multiProfile, new AccountTransferUi.Host() {
            @Override public boolean allowed() {
                if (!ready || closing) return false;
                if (studio.running() != null) { message("请先停止或等待当前生成结束，再迁移账号。"); return false; }
                if (browser.checking()) { message("请等当前代理检查结束后再迁移账号。"); return false; }
                return true;
            }
            @Override public void beforeTransfer() { syncStudioDraft(); quotas.cancel(); clearFile(); }
            @Override public void imported() { importedAccounts(); }
            @Override public void notice(String text) { message(text); }
        });
        updater = new AppUpdateUi(this, new AppUpdateUi.Host() {
            @Override public boolean canOffer() {
                return foreground && ready && !closing && !isFinishing() && !isDestroyed()
                    && studio.running() == null && !browser.checking() && !browser.transferring() && !transferUi.busy();
            }
            @Override public boolean beforeInstall() {
                if (!canOffer()) { message("请先完成或停止当前任务，再准备安装更新。"); return false; }
                draftHandler.removeCallbacks(persistComposerHeight);
                persistComposerHeight.run();
                syncStudioDraft();
                studio.flushSessions();
                quotas.cancel();
                return true;
            }
            @Override public void notice(String text) { message(text); }
        });
        if (state != null) {
            screen = Math.max(STUDIO, Math.min(PROXIES, state.getInt("screen", STUDIO)));
            video = state.getBoolean("video");
            prompt = state.getString("prompt", "");
            ratio = state.getString("ratio", "Auto");
            seconds = state.getInt("seconds", 5);
            String exportingId = state.getString("exporting", "");
            for (MobileStudio.Entry entry : studio.entries()) if (entry.id.equals(exportingId)) exporting = entry;
            studio.selectSession(state.getString("studioSession", ""));
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(ui.chrome);
        FrameLayout workerHost = new FrameLayout(this);
        workerHost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        workerHost.setVisibility(View.GONE);
        root.addView(workerHost, new FrameLayout.LayoutParams(-1, -1));
        browser.setWorkerHost(workerHost);
        main = ui.column();
        // 后台网页有完整的渲染尺寸，但不会显示或接收页面触摸。
        main.setBackgroundColor(ui.chrome);
        main.setFocusableInTouchMode(true);
        main.setClickable(true);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));
        body = new FrameLayout(this);
        main.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        quickSwitch = buildQuickSwitch();
        sidebar = new AccountSidebar(this, ui, this, proxies);
        root.addView(quickSwitch, new FrameLayout.LayoutParams(ui.dp(SWITCH_SIZE), ui.dp(SWITCH_SIZE), Gravity.TOP | Gravity.START));
        root.addView(sidebar, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) placeQuickSwitch(false, null);
        });
        configureInsets(workerHost);
        setContentView(root);
        applyBarAppearance();
        ViewCompat.requestApplyInsets(root);
        restoreQuickSwitchPosition();
        showScreen(screen);
        ready = true;
    }

    // ---------- 浮动账号头像：显示当前账号头像，可拖动；拖到屏幕边缘后收起，只露出一小部分 ----------
    // 容器 60dp，里面是 54dp 的圆形头像框（四周 3dp 留给右上角的数量角标）。
    private static final int SWITCH_SIZE = 60, SWITCH_RING = 54, SWITCH_PEEK = 23, SWITCH_EDGE = 7;

    private FrameLayout buildQuickSwitch() {
        FrameLayout bubble = new FrameLayout(this);
        bubble.setClipChildren(false);
        bubble.setClipToPadding(false);
        bubble.setFocusable(true);
        bubble.setClickable(true);

        FrameLayout ring = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
        disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        disc.setColor(SURFACE);
        disc.setStroke(ui.dp(1), LINE_STRONG);
        ring.setBackground(disc);
        ring.setElevation(ui.dp(4));
        ring.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ring.setOutlineAmbientShadowColor(0xFF000000);
            ring.setOutlineSpotShadowColor(0xFF000000);
        }
        int inner = ui.dp(44);
        quickSwitchInitial = ui.avatar("M", "", 44);
        ring.addView(quickSwitchInitial, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        quickSwitchPhoto = new ImageView(this);
        quickSwitchPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable photoShape = new android.graphics.drawable.GradientDrawable();
        photoShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        photoShape.setColor(RAISED);
        quickSwitchPhoto.setBackground(photoShape);
        quickSwitchPhoto.setClipToOutline(true);
        quickSwitchPhoto.setVisibility(View.GONE);
        ring.addView(quickSwitchPhoto, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        bubble.addView(ring, new FrameLayout.LayoutParams(ui.dp(SWITCH_RING), ui.dp(SWITCH_RING), Gravity.CENTER));

        // 可用账号数：压在头像框右上角外侧，和设计稿一致。
        quickSwitchCount = ui.figure("", 11, ON_ACCENT);
        quickSwitchCount.setGravity(Gravity.CENTER);
        quickSwitchCount.setMinWidth(ui.dp(19));
        quickSwitchCount.setPadding(ui.dp(5), 0, ui.dp(5), 0);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(ACCENT);
        pill.setCornerRadius(ui.dp(10));
        quickSwitchCount.setBackground(pill);
        quickSwitchCount.setElevation(ui.dp(11));
        quickSwitchCount.setVisibility(View.GONE);
        bubble.addView(quickSwitchCount, new FrameLayout.LayoutParams(-2, ui.dp(19), Gravity.TOP | Gravity.END));
        bubble.setOnClickListener(view -> tapQuickSwitch());
        bubble.setLongClickable(true);
        bubble.setOnLongClickListener(view -> {
            dismissAccountMenu();
            switchPeeking = false;
            openSidebar();
            return true;
        });
        bubble.setOnTouchListener(this::dragQuickSwitch);
        return bubble;
    }

    /** 当前账号头像（已同步的 Muse 头像优先，否则用首字）与可用账号数。 */
    private void updateQuickSwitchFace() {
        if (quickSwitch == null || accounts == null) return;
        AccountStore.Account account = current();
        String name = account == null ? "Muse" : account.name;
        TextView fresh = ui.avatar(name, account == null ? "" : account.id, 44);
        quickSwitchInitial.setText(fresh.getText());
        quickSwitchInitial.setTextColor(fresh.getCurrentTextColor());
        quickSwitchInitial.setBackground(fresh.getBackground());
        android.graphics.Bitmap photo = account == null || quotas == null ? null : quotas.avatar(account.id);
        quickSwitchPhoto.setImageBitmap(photo);
        quickSwitchPhoto.setVisibility(photo == null ? View.GONE : View.VISIBLE);
        int usable = 0;
        for (AccountStore.Account item : accounts.all()) if (accounts.usable(item, multiProfile) && !exhausted(item)) usable++;
        quickSwitchCount.setText(String.valueOf(usable));
        quickSwitchCount.setVisibility(usable > 1 && (!switchTucked || switchPeeking) ? View.VISIBLE : View.GONE);
        String label = "快速切换 Muse 账号 · 当前：" + name + (switchTucked && !switchPeeking ? "（已收起，点按展开）" : "")
            + "，长按打开侧边栏";
        quickSwitch.setContentDescription(label);
        quickSwitch.setTooltipText(label);
    }

    /** 本周额度已用完（只看本周有效的同步结果）。 */
    private boolean exhausted(AccountStore.Account account) {
        AccountQuota quota = quotas == null ? null : quotas.value(account.id);
        long now = System.currentTimeMillis();
        Double used = quota != null && quota.currentWeek(now) ? quota.used() : null;
        return used != null && used >= 100;
    }

    private float switchWidth() { return root.getWidth() - root.getPaddingLeft() - root.getPaddingRight(); }
    private float switchMinY() { return topInset + ui.dp(8); }
    private float switchMaxY() {
        return Math.max(switchMinY(), root.getHeight() - bottomInset - ui.dp(SWITCH_SIZE) - ui.dp(8));
    }

    private float switchRestX(boolean right) {
        return right ? switchWidth() - ui.dp(SWITCH_SIZE) - ui.dp(SWITCH_EDGE) : ui.dp(SWITCH_EDGE);
    }

    private float switchTuckX(boolean right) {
        return right ? switchWidth() - ui.dp(SWITCH_PEEK) : -(ui.dp(SWITCH_SIZE) - ui.dp(SWITCH_PEEK));
    }

    /** 按记住的边、高度和收起状态摆放；animate 为 true 时平滑移动。 */
    private void placeQuickSwitch(boolean animate, Runnable done) {
        if (quickSwitch == null || root == null) return;
        if (root.getWidth() == 0) { quickSwitch.post(() -> placeQuickSwitch(animate, done)); return; }
        float x = switchTucked && !switchPeeking ? switchTuckX(switchRight) : switchRestX(switchRight);
        float y = switchMinY() + Math.max(0f, Math.min(1f, switchY)) * (switchMaxY() - switchMinY());
        quickSwitch.animate().cancel();
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            quickSwitch.animate().translationX(x).translationY(y).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                .withEndAction(() -> { if (done != null) done.run(); }).start();
        } else {
            quickSwitch.setTranslationX(x);
            quickSwitch.setTranslationY(y);
            quickSwitch.setScaleX(1f);
            quickSwitch.setScaleY(1f);
            if (done != null) done.run();
        }
        updateQuickSwitchFace();
    }

    /** 长按浮动头像：打开侧边栏。 */
    private final Runnable switchLongPress = () -> {
        if (switchDragging || quickSwitch == null) return;
        switchLongPressed = true;
        quickSwitch.performLongClick();
    };

    private boolean dragQuickSwitch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                view.animate().cancel();
                switchTouchX = event.getRawX(); switchTouchY = event.getRawY();
                switchStartTranslationX = view.getTranslationX(); switchStartTranslationY = view.getTranslationY();
                switchDragging = false;
                switchLongPressed = false;
                view.removeCallbacks(switchLongPress);
                view.postDelayed(switchLongPress, android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (switchLongPressed) return true;
                float dx = event.getRawX() - switchTouchX, dy = event.getRawY() - switchTouchY;
                if (!switchDragging && dx * dx + dy * dy > ui.dp(8) * ui.dp(8)) {
                    view.removeCallbacks(switchLongPress);
                    switchDragging = true;
                    dismissAccountMenu();
                    quickSwitchCount.setVisibility(View.GONE);
                    view.setScaleX(1.08f);
                    view.setScaleY(1.08f);
                }
                if (switchDragging) {
                    // 允许拖出屏幕边缘，但至少留出一小部分可以再拖回来。
                    float x = Math.max(switchTuckX(false), Math.min(switchTuckX(true), switchStartTranslationX + dx));
                    float y = Math.max(switchMinY(), Math.min(switchMaxY(), switchStartTranslationY + dy));
                    view.setTranslationX(x);
                    view.setTranslationY(y);
                }
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                view.removeCallbacks(switchLongPress);
                if (switchLongPressed) { /* 已打开侧边栏 */ }
                else if (switchDragging) settleQuickSwitch(view);
                else view.performClick();
                switchDragging = false; switchLongPressed = false; return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(switchLongPress);
                if (switchDragging) settleQuickSwitch(view);
                switchDragging = false; switchLongPressed = false; return true;
            }
            default -> { return false; }
        }
    }

    /** 松手：吸附到最近的左/右边；超出边缘四分之一以上就收起，只露出一小部分。 */
    private void settleQuickSwitch(View view) {
        float size = ui.dp(SWITCH_SIZE), x = view.getTranslationX(), width = switchWidth();
        switchRight = x + size / 2f >= width / 2f;
        switchTucked = switchRight ? x > width - size * 0.75f : x < -size * 0.25f;
        switchPeeking = false;
        float range = switchMaxY() - switchMinY();
        switchY = range <= 0 ? 0f : (view.getTranslationY() - switchMinY()) / range;
        storeQuickSwitchPosition();
        placeQuickSwitch(true, null);
    }

    /** 点按：收起状态先滑出再打开；菜单或侧栏关闭后再收回。 */
    private void tapQuickSwitch() {
        if (switchTucked && !switchPeeking) {
            switchPeeking = true;
            placeQuickSwitch(true, this::showQuickSwitch);
        } else showQuickSwitch();
    }

    private void retuckQuickSwitch() {
        if (!switchPeeking) return;
        switchPeeking = false;
        if (quickSwitch != null && quickSwitch.getVisibility() == View.VISIBLE) placeQuickSwitch(true, null);
        else placeQuickSwitch(false, null);
    }

    private void restoreQuickSwitchPosition() {
        android.content.SharedPreferences saved = getSharedPreferences("muse-ui", MODE_PRIVATE);
        switchRight = saved.getBoolean("switch-right", true);
        switchY = saved.getFloat("switch-y", 0.3f);
        switchTucked = saved.getBoolean("switch-tucked", false);
        switchPeeking = false;
        placeQuickSwitch(false, null);
    }

    private void storeQuickSwitchPosition() {
        getSharedPreferences("muse-ui", MODE_PRIVATE).edit()
            .putBoolean("switch-right", switchRight)
            .putFloat("switch-y", switchY)
            .putBoolean("switch-tucked", switchTucked).apply();
    }

    private void dismissAccountMenu() {
        android.widget.PopupWindow menu = accountMenu;
        accountMenu = null;
        if (menu != null && menu.isShowing()) {
            try { menu.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    private static int all(List<AccountStore.Account> list) { return list == null ? 0 : list.size(); }

    private View menuAction(int icon, String label, Runnable action) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        row.addView(ui.iconView(icon, TEXT2, 18));
        TextView text = ui.text(label, 13.5f, INK, false);
        LinearLayout.LayoutParams textPosition = new LinearLayout.LayoutParams(0, -2, 1);
        textPosition.setMarginStart(ui.dp(12));
        row.addView(text, textPosition);
        row.setContentDescription(label);
        ui.touch(row, Color.TRANSPARENT, 12, 0, action);
        return row;
    }

    private View quickAccountCell(AccountStore.Account account, boolean on, android.widget.PopupWindow popup, long now) {
        boolean usable = accounts.usable(account, multiProfile);
        boolean exhausted = exhausted(account);
        boolean started = browser.isOpen(account.id) || browser.isParked(account.id);
        boolean selectable = on || usable && !exhausted;
        String lifecycle = browser.isParked(account.id) ? "后台保留" : started ? "已启动" : "未启动";
        AccountQuota quota = quotas.value(account.id);
        boolean oldPeriod = quota != null && quota.resets() > quota.observed() && quota.resets() <= now;
        String detail = quota == null || oldPeriod ? "周额度待获取" : quota.used() == null ? "周额度不限量" : "周额度" + Math.round(quota.used()) + "%";
        String unavailable = !usable ? "需更新 WebView" : exhausted && !on ? "额度已用完" : "";
        LinearLayout cell = ui.column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setMinimumHeight(ui.dp(86));
        cell.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
        cell.setSelected(on);
        FrameLayout face = new FrameLayout(this);
        face.addView(ui.avatar(account.name, account.id, 30), new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        android.graphics.Bitmap photo = quotas.avatar(account.id);
        if (photo != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(photo);
            image.setBackground(ui.shape(RAISED, 15, 0));
            image.setClipToOutline(true);
            face.addView(image, new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        }
        if (on) face.addView(ui.iconView(R.drawable.ic_check, ACCENT, 14),
            new FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.END | Gravity.BOTTOM));
        face.setAlpha(!selectable ? 0.4f : started ? 1f : 0.5f);
        cell.addView(face, new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        TextView name = ui.single(ui.text(account.name, 12.5f, INK, true));
        name.setMinWidth(0);
        name.setGravity(Gravity.CENTER);
        name.setAlpha(!selectable ? 0.4f : started ? 1f : 0.5f);
        ui.block(cell, name, 5);
        TextView status = ui.single(ui.text(detail, 12, TEXT2, false));
        status.setMinWidth(0);
        status.setGravity(Gravity.CENTER);
        ui.block(cell, status, 2);
        String description = account.name + "，" + lifecycle + (on ? "，当前账号" : "")
            + "，" + detail + (quota == null ? "" : "，" + quota.weekly(now)) + (selectable ? "" : "，" + unavailable + "，不可选");
        cell.setContentDescription(description);
        cell.setTooltipText(description);
        cell.setEnabled(selectable);
        if (selectable) ui.touch(cell, on ? ACCENT_SOFT : SURFACE, 9, LINE, () -> {
            popup.dismiss();
            switchQuickAccount(account);
        });
        return cell;
    }

    /** 三列快速切号菜单，启动状态只读；只在用户点选后打开账号。 */
    private void showAccountMenu(List<AccountStore.Account> available) {
        dismissAccountMenu();
        if (quickSwitch == null || root == null || root.getWindowToken() == null || root.getWidth() <= ui.dp(16)) return;
        int width = Math.min(ui.dp(276), root.getWidth() - ui.dp(16));
        LinearLayout menu = ui.column();
        menu.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        TextView title = ui.text("快速切换账号", 12, TEXT2, false);
        title.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8));
        menu.addView(title);
        LinearLayout grid = ui.column();
        menu.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(menu, new ScrollView.LayoutParams(-1, -2));
        android.widget.PopupWindow popup = new android.widget.PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        AccountStore.Account selected = current();
        long now = System.currentTimeMillis();
        for (int start = 0; start < available.size(); start += 3) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.TOP);
            for (int column = 0; column < 3; column++) {
                int index = start + column;
                View cell = index < available.size() ? quickAccountCell(available.get(index), available.get(index) == selected, popup, now) : new View(this);
                LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(0, -2, 1);
                if (column > 0) position.setMarginStart(ui.dp(6));
                row.addView(cell, position);
            }
            ui.block(grid, row, start == 0 ? 0 : 6);
        }
        // 只保留原添加入口；账号管理仍在侧栏。
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerPosition = new LinearLayout.LayoutParams(-1, ui.dp(1));
        dividerPosition.setMargins(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(4));
        menu.addView(divider, dividerPosition);
        if (multiProfile) menu.addView(menuAction(R.drawable.ic_user_plus, "添加账号", () -> {
            popup.dismiss();
            editAccount(null);
        }));
        if (!multiProfile) {
            TextView note = ui.text("WebView 不支持多账号", 11.5f, MUTED, false);
            note.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(6));
            menu.addView(note);
        }
        popup.setBackgroundDrawable(ui.shape(SURFACE, 20, LINE_STRONG));
        popup.setElevation(ui.dp(14));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            if (accountMenu == popup) accountMenu = null;
            retuckQuickSwitch();
        });
        int maxHeight = Math.max(1, root.getHeight() - topInset - root.getPaddingBottom() - ui.dp(20));
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        int height = scroll.getMeasuredHeight();
        popup.setHeight(height);
        int[] origin = new int[2];
        root.getLocationInWindow(origin);
        int size = ui.dp(SWITCH_SIZE);
        int bubbleX = origin[0] + root.getPaddingLeft() + Math.round(quickSwitch.getTranslationX());
        int bubbleY = origin[1] + Math.round(quickSwitch.getTranslationY());
        int x = switchRight ? bubbleX - width - ui.dp(10) : bubbleX + size + ui.dp(10);
        x = Math.max(origin[0] + ui.dp(8), Math.min(origin[0] + root.getWidth() - width - ui.dp(8), x));
        int y = bubbleY + size / 2 - ui.dp(56);
        int maxY = origin[1] + root.getHeight() - root.getPaddingBottom() - height - ui.dp(12);
        y = Math.max(origin[1] + topInset + ui.dp(8), Math.min(maxY, y));
        accountMenu = popup;
        try { popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y); }
        catch (RuntimeException error) { accountMenu = null; retuckQuickSwitch(); }
    }

    private void updateQuickSwitchVisibility() {
        if (quickSwitch == null || sidebar == null || accounts == null) return;
        boolean visible = screen != PROXIES && videoView == null && !sidebar.isOpen();
        quickSwitch.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showQuickSwitch() {
        List<AccountStore.Account> all = accounts.all();
        if (all.isEmpty()) {
            openSidebar();
            sidebar.showSessions(false);
            return;
        }
        // 只读启动状态；未启动灰显但仍可点选，真正不可用的账号保留保护。
        showAccountMenu(new java.util.ArrayList<>(all));
    }

    /** 快速切号：不管当前在哪个页面，都打开所选账号的网页。 */
    private void switchQuickAccount(AccountStore.Account account) {
        if (account == current() && screen == WEB && browser.view() != null) return;
        syncStudioDraft();
        if (account != current() && !select(account)) return;
        showScreen(WEB);
    }

    private void configureInsets(FrameLayout workerHost) {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setStatusBarContrastEnforced(false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets input = insets.getInsets(WindowInsetsCompat.Type.ime());
            topInset = safe.top;
            bottomInset = safe.bottom;
            root.setPadding(safe.left, 0, safe.right, Math.max(safe.bottom, input.bottom));
            quickSwitch.post(() -> { if (!switchDragging) placeQuickSwitch(false, null); });
            main.setPadding(0, topInset, 0, 0);
            workerHost.setPadding(0, topInset, 0, 0);
            sidebar.setTopInset(topInset);
            if (videoView != null) {
                FrameLayout.LayoutParams videoLayout = (FrameLayout.LayoutParams) videoView.getLayoutParams();
                if (videoLayout.topMargin != topInset) {
                    videoLayout.topMargin = topInset;
                    videoView.setLayoutParams(videoLayout);
                }
            }
            return insets;
        });
    }

    /** 状态栏/导航栏图标深浅：必须在 setContentView 之后调用（Android 11 上提前取控制器会空指针）。 */
    private void applyBarAppearance() {
        try {
            androidx.core.view.WindowInsetsControllerCompat bars =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            bars.setAppearanceLightStatusBars(!MobileUi.NIGHT);
            bars.setAppearanceLightNavigationBars(!MobileUi.NIGHT);
        } catch (RuntimeException ignored) {
            // 主题里已设置 windowLightStatusBar，这里只是补充。
        }
    }

    private ImageButton sidebarButton() {
        sidebarToggle = ui.iconButton(R.drawable.ic_sidebar, "打开侧边栏", INK, this::openSidebar);
        return sidebarToggle;
    }

    private void openSidebar() {
        if (sidebar.isOpen() || videoView != null) return;
        hideKeyboard();
        dismissAccountMenu();
        if (quickSwitch != null) quickSwitch.setVisibility(View.GONE);
        renderSidebar();
        sidebar.showSessions(screen == STUDIO);
        main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        main.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        main.setFocusable(false);
        sidebar.open();
        syncCurrentQuota();
    }

    @Override public void sidebarClosed() {
        main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        main.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        main.setFocusableInTouchMode(true);
        updateQuickSwitchVisibility();
        retuckQuickSwitch();
        if (!main.isInTouchMode() && sidebarToggle != null) sidebarToggle.requestFocus();
    }

    private void renderSidebar() {
        List<AccountStore.Account> list = accounts.all();
        sidebar.render(list, current(), screen == STUDIO, screen == PROXIES, multiProfile,
            account -> accounts.usable(account, multiProfile), quotas, studio.sessions(), studio.currentSession(),
            studio.running() == null ? "" : studio.running().sessionId);
        for (AccountStore.Account account : list) updateQuota(account.id);
    }

    private void syncCurrentQuota() {
        AccountStore.Account account = current();
        if (foreground && ready && account != null && studio.running() == null && !browser.checking() && !browser.transferring())
            quotas.refresh(account, multiProfile, false);
    }

    @Override public void openStudio() {
        if (screen == STUDIO) sidebar.close(true);
        else showScreen(STUDIO);
    }

    @Override public void openProxyManager() { showScreen(PROXIES); }

    @Override public void createStudioSession() { newStudioSession(); }

    @Override public void selectStudioSession(StudioSession session) { switchStudioSession(session); }

    @Override public void deleteStudioSessions(List<StudioSession> chosen) {
        String busy = studio.running() == null ? "" : studio.running().sessionId;
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        int records = 0;
        boolean skippedBusy = false;
        for (StudioSession session : chosen) {
            if (session.id.equals(busy)) { skippedBusy = true; continue; }
            ids.add(session.id);
            records += studio.entryCount(session.id);
        }
        if (ids.isEmpty()) { message("正在生成的会话不能删除，请等它结束或先停止生成。"); return; }
        String title = ids.size() == 1 ? "删除会话「" + shorten(chosen.get(0).name, 14) + "」？" : "删除 " + ids.size() + " 个会话？";
        String detail = (records > 0 ? "会同时删除其中的 " + records + " 条生成记录，以及保存在 App 里的图片和视频。" : "这些会话里没有生成记录。")
            + "已保存到相册的文件不受影响，Muse 网站上的对话也不会被删除。删除后无法恢复。"
            + (skippedBusy ? "\n正在生成的会话会保留。" : "");
        MobileUi.Sheet sheet = ui.sheet(this, title, detail);
        sheet.action(ui.outline("取消", sheet::dismiss), true);
        sheet.action(ui.primary("删除", () -> {
            sheet.dismiss();
            removeStudioSessions(ids);
        }), true);
        sheet.show();
    }

    private void removeStudioSessions(java.util.List<String> ids) {
        StudioSession before = studio.currentSession();
        boolean currentGone = before != null && ids.contains(before.id);
        if (!currentGone) syncStudioDraft();
        else draftHandler.removeCallbacks(persistDraft); // 别把被删会话的草稿写进新选中的会话
        int removed = studio.deleteSessions(ids);
        if (currentGone) {
            StudioSession next = studio.currentSession();
            if (next == null) next = studio.ensureSession(current());
            if (next != null) {
                prompt = next.draft;
                video = next.video;
                ratio = next.ratio;
                seconds = next.seconds;
            } else prompt = "";
            studioReferenceSignature = "";
        }
        sidebar.finishPicking();
        renderSidebar();
        if (screen == STUDIO) renderScreen(STUDIO);
        message(removed == 0 ? "没有删除任何会话。" : "已删除 " + removed + " 个会话。");
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    @Override public void openAccount(AccountStore.Account account) {
        if (screen == WEB && account == current() && browser.view() != null) sidebar.close(true);
        else if (select(account)) showScreen(WEB);
    }

    @Override public void refreshAccount(AccountStore.Account account) {
        String blocked = null;
        if (studio.running() != null) blocked = "请等当前生成结束后刷新额度。";
        // WebView 的代理是全局的，刷新其他账号会改变当前网页的网络环境。
        else if (screen == WEB && account != current()) blocked = "请先回到工作室，再刷新其他账号的额度。";
        if (blocked != null) {
            if (accountEditor == null || !accountEditor.refreshBlocked(account.id, blocked)) message(blocked);
            return;
        }
        quotas.refresh(account, multiProfile, true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (!ready) { super.onSaveInstanceState(state); return; }
        draftHandler.removeCallbacks(persistComposerHeight);
        persistComposerHeight.run();
        syncStudioDraft();
        super.onSaveInstanceState(state);
        state.putInt("screen", screen);
        state.putBoolean("video", video);
        state.putString("prompt", prompt);
        state.putString("ratio", ratio);
        state.putInt("seconds", seconds);
        state.putString("studioSession", studio.currentSessionId());
        if (exporting != null) state.putString("exporting", exporting.id);
    }

    private AccountStore.Account current() { return accounts.current(multiProfile); }

    private void showScreen(int next) {
        syncStudioDraft();
        sidebar.close(true);
        renderScreen(next);
    }

    private void renderScreen(int next) {
        dismissAccountMenu();
        hideVideo();
        hideKeyboard();
        if (next == WEB) quotas.cancel();
        browser.detach();
        body.removeAllViews();
        webFrame = null;
        studioResults = null;
        studioScroll = null;
        studioTitle = null;
        studioSubtitle = null;
        studioMode = null;
        studioComposer = null;
        studioAccountValue = null;
        studioAccountAvatar = null;
        studioReferenceButton = null;
        studioReferenceStrip = null;
        studioReferenceSignature = "";
        studioInput = null;
        studioAccount = null;
        submitButton = null;
        screen = next;
        if (videoView == null) root.setBackgroundColor(rootColor());
        body.addView(next == WEB ? renderWeb() : next == PROXIES ? renderProxyManager() : renderStudio(),
            new FrameLayout.LayoutParams(-1, -1));
        if (!sidebar.isOpen()) main.requestFocus();
        updateQuickSwitchVisibility();
        updateQuickSwitchFace();
    }

    private LinearLayout.LayoutParams webIconSize() { return new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)); }

    private int rootColor() { return screen == STUDIO ? SURFACE : BG; }

    private LinearLayout studioHeader() {
        LinearLayout toolbar = ui.toolbar();
        toolbar.addView(sidebarButton());
        LinearLayout heading = ui.column();
        heading.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        studioTitle = ui.single(ui.text("Muse 工作室", 17, INK, true));
        heading.addView(studioTitle);
        studioSubtitle = ui.single(ui.text("", 12, TEXT2, false));
        LinearLayout.LayoutParams subtitlePosition = new LinearLayout.LayoutParams(-1, -2);
        subtitlePosition.topMargin = ui.dp(4);
        heading.addView(studioSubtitle, subtitlePosition);
        heading.setOnClickListener(view -> openSidebar());
        heading.setContentDescription("打开会话列表");
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(ui.iconButton(R.drawable.ic_add, "新建会话", INK, this::newStudioSession));
        updateStudioSessionTitle();
        return toolbar;
    }

    private View renderStudio() {
        StudioSession session = studio.ensureSession(current());
        if (session != null) {
            prompt = session.draft;
            video = session.video;
            ratio = session.ratio;
            seconds = session.seconds;
        }
        LinearLayout screenView = ui.column();
        screenView.setBackgroundColor(BG);
        ui.block(screenView, studioHeader(), 0);
        studioScroll = new ScrollView(this);
        studioScroll.setFillViewport(true);
        studioScroll.setVerticalScrollBarEnabled(false);
        LinearLayout transcript = ui.column();
        // 像聊天一样：内容少时贴在输入框上方，最新的一条在最下面。
        transcript.setGravity(Gravity.BOTTOM);
        transcript.setPadding(ui.dp(15), ui.dp(16), ui.dp(15), ui.dp(16));
        studioResults = new StudioResults(this, ui, studio, this::reusePrompt, this::saveMedia, this::appendStarter);
        ui.block(transcript, studioResults.view, 0);
        studioScroll.addView(transcript, new ScrollView.LayoutParams(-1, -2));
        screenView.addView(studioScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        screenView.addView(renderStudioComposer(), new LinearLayout.LayoutParams(-1, -2));
        // 停在底部时，新卡片、图片加载和输入框变高都保持显示最新一条；用户往上翻时不打扰。
        studioPinned = true;
        ScrollView scroll = studioScroll;
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            View child = scroll.getChildAt(0);
            studioPinned = child == null || y + scroll.getHeight() >= child.getHeight() - ui.dp(56);
        });
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (studioPinned && bottom - top != oldBottom - oldTop) scroll.post(() -> scrollStudioToEnd(false));
        });
        transcript.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (studioPinned && bottom - top != oldBottom - oldTop) scroll.post(() -> scrollStudioToEnd(false));
        });
        updateStudio();
        updateStudioSessionTitle();
        scroll.post(() -> scrollStudioToEnd(false));
        return screenView;
    }

    private void scrollStudioToEnd(boolean smooth) {
        ScrollView scroll = studioScroll;
        if (scroll == null) return;
        View child = scroll.getChildAt(0);
        if (child == null) return;
        int end = Math.max(0, child.getHeight() - scroll.getHeight());
        if (smooth) scroll.smoothScrollTo(0, end); else scroll.scrollTo(0, end);
        studioPinned = true;
    }

    private View renderStudioComposer() {
        LinearLayout shell = ui.column();
        studioComposer = shell;
        shell.setBackground(ui.topShape(SURFACE, 20));
        shell.setPadding(ui.dp(13), 0, ui.dp(13), ui.dp(10));
        shell.setElevation(ui.dp(1));
        StudioSession composerSession = studio.currentSession();
        int initialHeight = composerSession == null ? ComposerHeight.DEFAULT_DP : ComposerHeight.clamp(composerSession.composerHeightDp, viewportDp());

        // 顶部把手：向上拖动拉高输入框，高度按会话记住。
        FrameLayout resizeTarget = new FrameLayout(this);
        resizeTarget.setContentDescription("拖动调整输入框高度");
        resizeTarget.setTooltipText("拖动调整输入框高度");
        resizeTarget.setFocusable(true);
        View resizeHandle = new View(this);
        resizeHandle.setBackground(ui.shape(LINE_STRONG, 2, 0));
        resizeTarget.addView(resizeHandle, new FrameLayout.LayoutParams(ui.dp(38), ui.dp(4), Gravity.CENTER));
        shell.addView(resizeTarget, new LinearLayout.LayoutParams(-1, ui.dp(20)));

        LinearLayout modes = ui.row();
        studioMode = ui.segment(new String[]{"图片", "视频"}, new int[]{R.drawable.ic_mode_image, R.drawable.ic_mode_video},
            false, video ? 1 : 0, index -> setStudioMode(index == 1));
        studioMode.view.setContentDescription(video ? "生成视频" : "生成图片");
        modes.addView(studioMode.view, new LinearLayout.LayoutParams(-2, -2));
        modes.addView(ui.spacer());
        studioReferenceButton = ui.iconButton(R.drawable.ic_reference, "添加参考图片", INK, this::pickReference);
        modes.addView(studioReferenceButton);
        ui.block(shell, modes, 0);

        studioReferenceStrip = ui.column();
        ui.block(shell, studioReferenceStrip, 10);
        updateReferencePreview();

        EditText input = new AppCompatEditText(this);
        studioInput = input;
        input.setHint(video ? "描述镜头、动作和氛围…" : "描述你想生成的画面、风格和细节…");
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(15);
        input.setLineSpacing(ui.dp(4), 1f);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinHeight(ui.dp(initialHeight));
        input.setMaxHeight(ui.dp(initialHeight));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6000)});
        input.setBackgroundColor(Color.TRANSPARENT);
        ui.pad(input, 2, 4);
        input.setText(prompt);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                prompt = s.toString();
                draftHandler.removeCallbacks(persistDraft);
                draftHandler.postDelayed(persistDraft, 350);
                updateStudio();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        LinearLayout.LayoutParams inputPosition = new LinearLayout.LayoutParams(-1, ui.dp(initialHeight));
        inputPosition.topMargin = ui.dp(8);
        shell.addView(input, inputPosition);

        resizeTarget.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            int startHeight;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        startY = event.getRawY();
                        startHeight = input.getHeight() > 0 ? Math.round(input.getHeight() / ui.dp(1)) : initialHeight;
                        resizeHandle.setBackground(ui.shape(TEXT2, 2, 0));
                        view.setPressed(true);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        int requested = startHeight + Math.round((startY - event.getRawY()) / ui.dp(1));
                        int height = ComposerHeight.clamp(requested, viewportDp());
                        setComposerHeight(input, height);
                        if (composerSession != null) {
                            pendingComposerHeight = height;
                            pendingComposerSession = composerSession;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.setPressed(false);
                        resizeHandle.setBackground(ui.shape(LINE_STRONG, 2, 0));
                        if (composerSession != null) {
                            pendingComposerHeight = ComposerHeight.clamp(Math.round(input.getHeight() / ui.dp(1)), viewportDp());
                            pendingComposerSession = composerSession;
                            draftHandler.removeCallbacks(persistComposerHeight);
                            draftHandler.post(persistComposerHeight);
                        }
                        return true;
                    }
                    default -> { return true; }
                }
            }
        });

        LinearLayout controls = ui.row();
        AccountStore.Account owner = current();
        studioAccountAvatar = ui.avatar(owner == null ? "账号" : owner.name, owner == null ? "" : owner.id, 20);
        studioAccount = ui.chip(studioAccountAvatar, owner == null ? "选择账号" : owner.name, "选择 Muse 账号", this::pickAccount);
        studioAccountValue = (TextView) studioAccount.getTag();
        controls.addView(studioAccount, new LinearLayout.LayoutParams(-2, ui.dp(34)));
        updateStudioAccount();
        String[] ratios = video ? VIDEO_RATIOS : IMAGE_RATIOS;
        LinearLayout ratioChip = ui.chip(ui.ratioBox(ratio, 13, TEXT2), prettyRatio(ratio), "画幅比例 · " + prettyRatio(ratio),
            () -> chooseRatio(ratios));
        LinearLayout.LayoutParams ratioPosition = new LinearLayout.LayoutParams(-2, ui.dp(34));
        ratioPosition.setMarginStart(ui.dp(6));
        controls.addView(ratioChip, ratioPosition);
        if (video) {
            LinearLayout durationChip = ui.chip(ui.iconView(R.drawable.ic_duration, TEXT2, 15), seconds + " 秒",
                "视频时长 · " + seconds + " 秒", this::chooseDuration);
            LinearLayout.LayoutParams durationPosition = new LinearLayout.LayoutParams(-2, ui.dp(34));
            durationPosition.setMarginStart(ui.dp(6));
            controls.addView(durationChip, durationPosition);
        }
        controls.addView(ui.spacer());
        ImageButton send = ui.sendButton(this::submit);
        send.setContentDescription("发送生成请求");
        send.setTooltipText("发送生成请求");
        submitButton = send;
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46));
        sendSize.setMarginStart(ui.dp(8));
        controls.addView(send, sendSize);
        ui.block(shell, controls, 10);
        return shell;
    }

    /** 生成键：空闲时生成，生成中变为停止。 */
    private void submit() {
        if (studio.running() != null) { studio.stop(); return; }
        generate();
    }

    /** 空会话里的示例句：追加到输入框。 */
    private void appendStarter(String text) {
        if (studioInput == null) return;
        String current = studioInput.getText() == null ? "" : studioInput.getText().toString();
        String next = current.trim().isEmpty() ? text : current.replaceAll("[，,\\s]+$", "") + "，" + text;
        studioInput.setText(next);
        studioInput.setSelection(studioInput.length());
        studioInput.requestFocus();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.showSoftInput(studioInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void setStudioMode(boolean videoMode) {
        if (video == videoMode) return;
        video = videoMode;
        ratio = "Auto";
        syncStudioDraft();
        showScreen(STUDIO);
    }




    private int viewportDp() {
        float density = getResources().getDisplayMetrics().density;
        int pixels = root == null || root.getHeight() <= 0 ? getResources().getDisplayMetrics().heightPixels : root.getHeight();
        return Math.max(320, Math.round(pixels / density));
    }

    private void setComposerHeight(EditText input, int heightDp) {
        int height = ui.dp(ComposerHeight.clamp(heightDp, viewportDp()));
        input.setMinHeight(height);
        input.setMaxHeight(height);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) input.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            input.setLayoutParams(params);
        }
    }

    private int referenceCount(StudioSession session) {
        if (session == null || references == null) return 0;
        return Math.min(session.referenceCount, references.count(session.id));
    }

    private void updateReferencePreview() {
        if (studioReferenceStrip == null || studio == null || references == null) return;
        StudioSession session = studio.currentSession();
        String id = session == null ? "" : session.id;
        int count = referenceCount(session);
        if (session != null && count != session.referenceCount) {
            // 文件缺失时以实际存在的为准。
            session.setReferenceCount(count, System.currentTimeMillis());
            studio.flushSessions();
        }
        int max = StudioReferenceStore.MAX_REFERENCES;
        if (studioReferenceButton != null) {
            String label = count == 0 ? "添加参考图片（最多 " + max + " 张）" : count >= max ? "已添加 " + count + " 张参考图（已达上限）"
                : "已添加 " + count + " 张参考图，点击继续添加";
            studioReferenceButton.setImageTintList(android.content.res.ColorStateList.valueOf(count > 0 ? ACCENT : INK));
            studioReferenceButton.setContentDescription(label);
            studioReferenceButton.setTooltipText(label);
        }
        StringBuilder stamp = new StringBuilder(id).append(':').append(count);
        for (int index = 0; index < count; index++) {
            File source = references.file(id, index);
            stamp.append(':').append(source == null ? 0 : source.lastModified());
        }
        String signature = stamp.toString();
        if (signature.equals(studioReferenceSignature)) return;
        studioReferenceSignature = signature;
        studioReferenceStrip.removeAllViews();
        studioReferenceStrip.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        if (count == 0) return;

        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout tiles = ui.row();
        scroller.addView(tiles, new FrameLayout.LayoutParams(-2, -2));
        for (int index = 0; index < count; index++) {
            int position = index;
            FrameLayout tile = new FrameLayout(this);
            tile.setBackground(ui.shape(RAISED, 11, 0));
            tile.setClipToOutline(true);
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setContentDescription("参考图片 " + (index + 1));
            tile.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
            TextView order = ui.figure(String.valueOf(index + 1), 10, 0xFFFFFFFF);
            order.setGravity(Gravity.CENTER);
            order.setBackground(ui.shape(0x9E000000, 6, 0));
            order.setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1));
            FrameLayout.LayoutParams orderPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            orderPosition.setMargins(ui.dp(4), 0, 0, ui.dp(4));
            tile.addView(order, orderPosition);
            ImageButton remove = ui.compactIconButton(R.drawable.ic_close, "移除第 " + (index + 1) + " 张参考图片", 0xFFFFFFFF,
                () -> removeReference(id, position));
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(0x9E000000);
            remove.setBackground(dot);
            remove.setScaleType(ImageView.ScaleType.FIT_CENTER);
            remove.setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5));
            FrameLayout.LayoutParams removePosition = new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP | Gravity.END);
            removePosition.setMargins(0, ui.dp(3), ui.dp(3), 0);
            tile.addView(remove, removePosition);
            LinearLayout.LayoutParams tilePosition = new LinearLayout.LayoutParams(ui.dp(54), ui.dp(54));
            if (index > 0) tilePosition.setMarginStart(ui.dp(8));
            tiles.addView(tile, tilePosition);
            String expected = signature;
            new Thread(() -> {
                android.graphics.Bitmap bitmap = references.thumbnail(id, position, 160);
                runOnUiThread(() -> {
                    if (bitmap != null && thumbnail.isAttachedToWindow() && expected.equals(studioReferenceSignature))
                        thumbnail.setImageBitmap(bitmap);
                    else if (bitmap != null) bitmap.recycle();
                });
            }, "MuseReferencePreview").start();
        }
        if (count < max) {
            FrameLayout add = new FrameLayout(this);
            add.addView(ui.iconView(R.drawable.ic_add, TEXT2, 20), new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
            add.setContentDescription("继续添加参考图片");
            ui.touch(add, Color.TRANSPARENT, 11, LINE_STRONG, this::pickReference);
            LinearLayout.LayoutParams addPosition = new LinearLayout.LayoutParams(ui.dp(54), ui.dp(54));
            addPosition.setMarginStart(ui.dp(8));
            tiles.addView(add, addPosition);
        }
        studioReferenceStrip.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = ui.text(count + " / " + max + " 张参考图，会随下一次生成按顺序发送（第 1 张作为视频首帧）。",
            12, TEXT2, false);
        LinearLayout.LayoutParams hintPosition = new LinearLayout.LayoutParams(-1, -2);
        hintPosition.topMargin = ui.dp(6);
        studioReferenceStrip.addView(hint, hintPosition);
    }

    private void pickReference() {
        if (referenceLoading) return;
        if (studio.running() != null) { message("请等当前生成结束后再更换参考图。"); return; }
        StudioSession session = studio.currentSession();
        if (session == null) { message("请先选择工作室会话。"); return; }
        int remaining = StudioReferenceStore.MAX_REFERENCES - referenceCount(session);
        if (remaining <= 0) { message("最多添加 " + StudioReferenceStore.MAX_REFERENCES + " 张参考图，可先移除一些。"); return; }
        referencePickerSession = session.id;
        Intent picker = null;
        // Android 13 起优先用系统照片选择器（无需存储权限，可多选）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            picker = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            int limit = Math.min(remaining, android.provider.MediaStore.getPickImagesMaxLimit());
            if (limit > 1) picker.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, limit);
        }
        if (picker == null) picker = documentPicker(remaining > 1);
        try { startActivityForResult(picker, REFERENCE_PICKER); }
        catch (RuntimeException error) {
            try { startActivityForResult(documentPicker(remaining > 1), REFERENCE_PICKER); }
            catch (RuntimeException fallback) { referencePickerSession = ""; message("无法打开图片选择器。"); }
        }
    }

    private static Intent documentPicker(boolean multiple) {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*").putExtra(Intent.EXTRA_LOCAL_ONLY, true).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** 选择结果里的全部 content:// 地址（多选在 ClipData 里，单选在 getData 里）。 */
    private static java.util.List<Uri> pickedUris(Intent data) {
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        if (data == null) return uris;
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null && "content".equals(uri.getScheme()) && !uris.contains(uri)) uris.add(uri);
            }
        }
        Uri single = data.getData();
        if (single != null && "content".equals(single.getScheme()) && !uris.contains(single)) uris.add(single);
        return uris;
    }

    private void removeReference(String sessionId, int index) {
        if (studio.running() != null) { message("请等当前生成结束后再移除参考图。"); return; }
        StudioSession session = studio.session(sessionId);
        int count = referenceCount(session);
        if (session == null || index < 0 || index >= count) return;
        references.removeAt(sessionId, index);
        session.setReferenceCount(references.count(sessionId), System.currentTimeMillis());
        studio.flushSessions();
        studioReferenceSignature = "";
        updateReferencePreview();
    }

    private void importReferences(java.util.List<Uri> uris, String sessionId) {
        StudioSession owner = studio.session(sessionId);
        if (owner == null || uris.isEmpty()) return;
        int start = referenceCount(owner);
        int room = StudioReferenceStore.MAX_REFERENCES - start;
        java.util.List<Uri> chosen = uris.size() > room ? uris.subList(0, Math.max(0, room)) : uris;
        int skipped = uris.size() - chosen.size();
        if (chosen.isEmpty()) { message("最多添加 " + StudioReferenceStore.MAX_REFERENCES + " 张参考图。"); return; }
        referenceLoading = true;
        if (studioReferenceButton != null) studioReferenceButton.setEnabled(false);
        java.util.List<Uri> batch = new java.util.ArrayList<>(chosen);
        new Thread(() -> {
            int saved = 0;
            String failure = null;
            for (Uri uri : batch) {
                try {
                    references.save(getContentResolver(), sessionId, start + saved, uri);
                    saved++;
                } catch (IOException | RuntimeException | OutOfMemoryError error) {
                    if (failure == null) failure = error.getMessage() == null ? "无法保存参考图片。" : error.getMessage();
                }
            }
            int added = saved;
            String problem = failure;
            runOnUiThread(() -> {
                referenceLoading = false;
                if (isDestroyed()) return;
                StudioSession session = studio.session(sessionId);
                if (session != null) {
                    session.setReferenceCount(references.count(sessionId), System.currentTimeMillis());
                    studio.flushSessions();
                    studioReferenceSignature = "";
                    updateReferencePreview();
                    int failed = batch.size() - added;
                    if (failed > 0 || skipped > 0) {
                        StringBuilder note = new StringBuilder("已添加 ").append(added).append(" 张");
                        if (failed > 0) note.append("，").append(failed).append(" 张未能添加：").append(problem);
                        if (skipped > 0) note.append("，超出上限未添加 ").append(skipped).append(" 张");
                        message(note.toString());
                    }
                } else message("对应的工作室会话已不存在。");
                if (studioReferenceButton != null) studioReferenceButton.setEnabled(true);
            });
        }, "MuseReferenceImport").start();
    }

    private void syncStudioDraft() {
        draftHandler.removeCallbacks(persistDraft);
        StudioSession session = studio == null ? null : studio.currentSession();
        if (session != null) studio.updateDraft(session, prompt, video, ratio, seconds);
    }

    private void updateStudioSessionTitle() {
        StudioSession session = studio == null ? null : studio.currentSession();
        if (studioTitle != null) studioTitle.setText(session == null ? "Muse 工作室" : session.name);
        if (studioSubtitle != null) {
            int count = studio == null ? 0 : studio.currentEntries().size();
            AccountStore.Account account = accounts == null ? null : current();
            String owner = account == null ? "未选择账号" : account.name;
            MobileStudio.Entry running = studio == null ? null : studio.running();
            boolean here = running != null && session != null && session.id.equals(running.sessionId);
            studioSubtitle.setText((here ? "生成中" : count == 0 ? "还没有记录" : count + " 条记录") + " · " + owner);
            studioSubtitle.setTextColor(here ? ACCENT : TEXT2);
        }
    }

    private void newStudioSession() {
        syncStudioDraft();
        StudioSession session = studio.createSession(current());
        if (session == null) {
            openSidebar();
            sidebar.showSessions(false);
            return;
        }
        prompt = session.draft;
        video = session.video;
        ratio = session.ratio;
        seconds = session.seconds;
        sidebar.close(true);
        renderScreen(STUDIO);
    }

    private AccountStore.Account accountForSession(StudioSession session) {
        for (AccountStore.Account account : accounts.all()) if (account.id.equals(session.owner)) return account;
        return null;
    }

    private void switchStudioSession(StudioSession session) {
        if (session == studio.currentSession() && screen == STUDIO) { sidebar.close(true); return; }
        syncStudioDraft();
        AccountStore.Account account = accountForSession(session);
        if (account == null) {
            message("这个会话对应的账号已移除。");
            return;
        }
        if (!select(account) || !studio.selectSession(session.id)) return;
        prompt = session.draft;
        video = session.video;
        ratio = session.ratio;
        seconds = session.seconds;
        sidebar.close(true);
        renderScreen(STUDIO);
    }

    private void weighted(LinearLayout row, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMarginStart(ui.dp(margin));
        row.addView(child, params);
    }

    private static String prettyRatio(String raw) { return "Auto".equals(raw) ? "自动" : raw; }

    private void chooseRatio(String[] ratios) {
        hideKeyboard();
        MobileUi.Sheet sheet = ui.sheet(this, "画幅比例", "选“自动”时由 Muse 按提示词决定，不指定画幅比例。");
        LinearLayout grid = ui.column();
        LinearLayout line = null;
        for (int index = 0; index < ratios.length; index++) {
            if (index % 3 == 0) {
                line = ui.row();
                ui.block(grid, line, index == 0 ? 0 : 8);
            }
            String value = ratios[index];
            boolean selected = value.equals(ratio);
            LinearLayout tile = ui.column();
            tile.setGravity(Gravity.CENTER);
            View box = ui.ratioBox(value, 34, selected ? ACCENT : TEXT2);
            tile.addView(box);
            TextView label = ui.figure(prettyRatio(value), 13, INK);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelPosition = new LinearLayout.LayoutParams(-2, -2);
            labelPosition.topMargin = ui.dp(10);
            tile.addView(label, labelPosition);
            tile.setContentDescription("画幅 " + prettyRatio(value) + (selected ? "，已选择" : ""));
            ui.touch(tile, selected ? ACCENT_SOFT : Color.TRANSPARENT, 14, selected ? ACCENT : LINE, () -> {
                sheet.dismiss();
                ratio = value; syncStudioDraft(); showScreen(STUDIO);
            });
            LinearLayout.LayoutParams tilePosition = new LinearLayout.LayoutParams(0, ui.dp(96), 1);
            if (index % 3 != 0) tilePosition.setMarginStart(ui.dp(8));
            line.addView(tile, tilePosition);
        }
        if (line != null) while (line.getChildCount() < 3) {
            LinearLayout.LayoutParams filler = new LinearLayout.LayoutParams(0, ui.dp(96), 1);
            filler.setMarginStart(ui.dp(8));
            line.addView(new View(this), filler);
        }
        sheet.add(grid, 0);
        sheet.show();
    }

    private void chooseDuration() {
        hideKeyboard();
        MobileUi.Sheet sheet = ui.sheet(this, "视频时长", "时长越长，消耗的周额度越多。");
        for (int index = 0; index < LENGTHS.length; index++) {
            int value = LENGTHS[index];
            String detail = value <= 6 ? "适合单个镜头" : "适合推拉、转场";
            sheet.add(ui.option(ui.iconView(R.drawable.ic_duration, TEXT2, 20), value + " 秒", detail, value == seconds, true, () -> {
                sheet.dismiss();
                seconds = value; syncStudioDraft(); showScreen(STUDIO);
            }), index == 0 ? 0 : 8);
        }
        sheet.show();
    }

    private void pickAccount() {
        List<AccountStore.Account> available = new java.util.ArrayList<>();
        for (AccountStore.Account account : accounts.all()) if (accounts.usable(account, multiProfile)) available.add(account);
        if (available.isEmpty()) { openSidebar(); return; }
        hideKeyboard();
        MobileUi.Sheet sheet = ui.sheet(this, "选择 Muse 账号", "这个会话的生成会使用所选账号的额度和代理。");
        AccountStore.Account selected = current();
        for (int index = 0; index < available.size(); index++) {
            AccountStore.Account account = available.get(index);
            AccountQuota quota = quotas.value(account.id);
            long now = System.currentTimeMillis();
            String detail = quota == null ? quotas.state(account.id)
                : quota.weekly(now) + (quota.resetLabel(now).isEmpty() ? "" : " · " + quota.resetLabel(now));
            View avatar = ui.avatar(account.name, account.id, 36);
            sheet.add(ui.option(avatar, account.name, detail, account == selected, true, () -> {
                sheet.dismiss();
                chooseAccount(account);
            }), index == 0 ? 0 : 8);
        }
        sheet.show();
    }

    private void chooseAccount(AccountStore.Account account) {
        StudioSession session = studio.currentSession();
        if (session != null && session.belongsTo(account.id)) {
            if (select(account)) updateStudioAccount();
            return;
        }
        syncStudioDraft();
        if (!select(account)) return;
        session = studio.createSession(account);
        session.draft = prompt;
        session.video = video;
        session.ratio = ratio;
        session.seconds = seconds;
        studio.flushSessions();
        renderScreen(STUDIO);
    }

    private void updateStudioAccount() {
        if (studioAccount == null) return;
        AccountStore.Account account = current();
        String name = account == null ? "选择账号" : account.name;
        if (studioAccountValue != null) studioAccountValue.setText(name);
        if (studioAccountAvatar != null) {
            TextView fresh = ui.avatar(account == null ? "账号" : account.name, account == null ? "" : account.id, 20);
            studioAccountAvatar.setText(fresh.getText());
            studioAccountAvatar.setTextColor(fresh.getCurrentTextColor());
            studioAccountAvatar.setBackground(fresh.getBackground());
        }
        String label = account == null ? "选择 Muse 账号" : "账号 · " + name;
        studioAccount.setContentDescription(label);
        studioAccount.setTooltipText(label);
    }

    private boolean select(AccountStore.Account account) {
        if (browser.checking()) {
            message("正在检测代理出口，请稍候再切换账号。");
            return false;
        }
        if (studio.running() != null && !studio.accountInUse(account.id)) {
            message("请等当前任务结束，或先停止生成再切换账号。");
            return false;
        }
        quotas.cancel();
        if (account != current()) {
            clearFile();
            // 同一网络路由的账号互相切换时保留网页，切回来不重新加载；路由不同才完整关闭。
            browser.switchTo(account, multiProfile);
        }
        accounts.select(account);
        return true;
    }

    private void generate() {
        AccountStore.Account account = current();
        if (account == null) { openSidebar(); return; }
        if (studio.running() != null) return;
        if (prompt.trim().isEmpty()) { studioInput.setError("请输入提示词"); return; }
        hideKeyboard();
        quotas.cancel();
        StudioSession session = studio.currentSession();
        if (session == null || !session.belongsTo(account.id)) session = studio.ensureSession(account);
        studio.start(account, multiProfile, prompt, video, ratio, seconds, session);
        if (session != null) studio.updateDraft(session, "", video, ratio, seconds);
        studioInput.setText("");
        studioPinned = true;
        if (studioScroll != null) studioScroll.post(() -> scrollStudioToEnd(true));
    }

    private void updateStudio() {
        if (closing || screen != STUDIO || submitButton == null) return;
        boolean busy = studio.running() != null;
        boolean ready = !busy && !prompt.trim().isEmpty();
        String action = busy ? "停止生成" : video ? "生成视频" : "生成图片";
        submitButton.setContentDescription(action);
        submitButton.setTooltipText(action);
        submitButton.setEnabled(true);
        ui.setSendState(submitButton, busy, ready);
        if (studioReferenceButton != null) {
            boolean enabled = !busy && !referenceLoading;
            studioReferenceButton.setEnabled(enabled);
            studioReferenceButton.setAlpha(enabled ? 1f : 0.4f);
        }
        updateReferencePreview();
        if (studioResults != null) studioResults.update();
        updateStudioSessionTitle();
    }

    private void reusePrompt(MobileStudio.Entry entry) {
        if (studio.running() == null) {
            for (AccountStore.Account account : accounts.all()) {
                if (account.id.equals(entry.owner) && accounts.usable(account, multiProfile)) { select(account); break; }
            }
            studio.selectSession(entry.sessionId);
        }
        video = entry.video;
        prompt = entry.prompt;
        ratio = entry.ratio;
        seconds = entry.seconds;
        studio.updateDraft(studio.currentSession(), prompt, video, ratio, seconds);
        renderScreen(STUDIO);
    }

    private void message(String text) {
        if (root == null || isFinishing()) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); return; }
        Snackbar bar = Snackbar.make(root, text, Snackbar.LENGTH_SHORT);
        View view = bar.getView();
        view.setBackground(ui.shape(INK, 12, 0));
        TextView label = view.findViewById(com.google.android.material.R.id.snackbar_text);
        if (label != null) { label.setTextColor(BG); label.setMaxLines(3); }
        if (screen == STUDIO && studioComposer != null && studioComposer.isAttachedToWindow() && !sidebar.isOpen())
            bar.setAnchorView(studioComposer);
        bar.show();
    }

    /**
     * 三态外观选择，只重建原生界面；不重新创建Activity或网页。
     */
    @Override public void toggleTheme() {
        if (!ready || closing) return;
        ThemeMode[] modes = ThemeMode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label;
        MobileUi.dialog(this).setTitle("外观")
            .setSingleChoiceItems(labels, MobileUi.themeMode(this).ordinal(), (dialog, index) -> {
                dialog.dismiss();
                if (index >= 0 && index < modes.length) selectThemeMode(modes[index]);
            }).setNegativeButton("取消", null).show();
    }

    private void selectThemeMode(ThemeMode mode) {
        if (!ready || closing || mode == MobileUi.themeMode(this)) return;
        draftHandler.removeCallbacks(persistComposerHeight);
        persistComposerHeight.run();
        syncStudioDraft();
        studio.flushSessions();
        themeReopenSidebar = sidebar.isOpen();
        themeShowSessions = sidebar.showingSessions();
        int generation = ++themeGeneration;
        MobileUi.setThemeMode(this, mode);
        root.post(() -> {
            if (closing || isFinishing() || isDestroyed() || generation != themeGeneration) return;
            getDelegate().applyDayNight();
            if (themeApplied != generation) applyTheme(); // 同色模式变化也更新入口图标。
        });
    }

    @Override public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (!ready) return;
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (night != themeNight || themeApplied != themeGeneration) applyTheme();
    }

    /** 用新配色重建原生界面；WebView 只是从旧容器挪到新容器，不会重新加载。 */
    private void applyTheme() {
        themeApplied = themeGeneration;
        dismissAccountMenu();
        hideKeyboard();
        syncStudioDraft();
        MobileUi.load(this);
        ui = new MobileUi(this);
        themeNight = MobileUi.NIGHT;
        Window window = getWindow();
        window.setNavigationBarColor(BG);
        applyBarAppearance();
        main.setBackgroundColor(BG);

        // 悬浮头像：换新配色，位置和收起状态不变。
        int switchIndex = root.indexOfChild(quickSwitch);
        ViewGroup.LayoutParams switchLayout = quickSwitch.getLayoutParams();
        int switchVisibility = quickSwitch.getVisibility();
        root.removeView(quickSwitch);
        quickSwitch = buildQuickSwitch();
        quickSwitch.setVisibility(switchVisibility);
        root.addView(quickSwitch, switchIndex < 0 ? root.getChildCount() : switchIndex, switchLayout);

        // 侧栏：重建后按原来的分页恢复；原来开着就直接显示，不再滑一次。
        boolean reopen = themeReopenSidebar || sidebar.isOpen();
        boolean sessions = sidebar.isOpen() ? sidebar.showingSessions() : themeShowSessions;
        themeReopenSidebar = false;
        int sidebarIndex = root.indexOfChild(sidebar);
        ViewGroup.LayoutParams sidebarLayout = sidebar.getLayoutParams();
        root.removeView(sidebar);
        sidebar = new AccountSidebar(this, ui, this, proxies);
        sidebar.setTopInset(topInset);
        root.addView(sidebar, sidebarIndex < 0 ? root.getChildCount() : sidebarIndex, sidebarLayout);

        renderScreen(screen);
        placeQuickSwitch(false, null);
        if (reopen) {
            renderSidebar();
            sidebar.showSessions(sessions);
            quickSwitch.setVisibility(View.GONE);
            main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            main.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            main.setFocusable(false);
            sidebar.openImmediately();
        } else {
            main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            main.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            main.setFocusableInTouchMode(true);
            updateQuickSwitchVisibility();
        }
    }

    private void updateQuota(String id) {
        if (!closing && sidebar != null) {
            sidebar.updateQuota(id, quotas);
            sidebar.updateAvatar(id, quotas.avatar(id));
        }
        if (!closing) updateQuickSwitchFace();
        if (!closing && accountEditor != null) accountEditor.updateQuota(id);
    }

    @Override public void editAccount(AccountStore.Account account) {
        if (account == null && !multiProfile) return;
        if (studio.running() != null && (account == null || studio.accountInUse(account.id))) {
            message("请等当前任务结束，或先停止生成再修改账号。");
            return;
        }
        Runnable remove = account == null || !multiProfile ? null : () -> MobileUi.dialog(this)
                .setTitle("移除「" + account.name + "」？").setMessage("账号将从列表中移除。此操作不会清除旧浏览器配置。")
                .setNegativeButton("取消", null).setPositiveButton("移除", (confirm, button) -> {
                    boolean resetBrowser = account == current();
                    loginPageOwners.remove(account.id);
                    accounts.remove(account);
                    browser.forget(account.id);
                    quotas.remove(account.id);
                    avatars.remove(account.id);
                    accountChanged(resetBrowser);
                }).show();
        Runnable refresh = account != null && accounts.all().contains(account) && accounts.usable(account, multiProfile)
            ? () -> refreshAccount(account) : null;
        AccountEditor opened = AccountEditor.show(this, ui, account, remove, proxies, accounts, quotas, this::openProxyManager, refresh, (name, proxyId, appearance, routeEdited) -> {
            // 普通卡面是本地外观偏好，不能因此取消任务、刷新额度或重建网页登录。
            boolean sameRoute = account != null && (!routeEdited || account.proxyId().equals(proxyId == null ? "" : proxyId)
                && !(account.hasProxy() && account.proxyId().isEmpty()));
            if (account != null && account.name.equals(name) && sameRoute) {
                AccountAppearance.save(this, account.id, appearance);
                renderSidebar();
                return;
            }
            quotas.cancel();
            if (account == null) {
                accounts.add(name, proxyId);
                AccountStore.Account added = current();
                if (added != null) AccountAppearance.save(this, added.id, appearance);
                // 新账号会成为当前账号：同一路由时保留原账号的网页。
                clearFile();
                browser.switchTo(current(), multiProfile);
                if (screen == WEB) renderScreen(current() == null ? STUDIO : WEB);
                accountChanged(false);
                return;
            }
            String newProxy = proxyId == null ? "" : proxyId;
            boolean routeChanged = !account.proxyId().equals(newProxy) || (account.hasProxy() && account.proxyId().isEmpty());
            boolean resetBrowser = routeChanged && account == current();
            accounts.update(account, name, proxyId);
            AccountAppearance.save(this, account.id, appearance);
            if (routeChanged) browser.forget(account.id);
            accountChanged(resetBrowser);
        });
        accountEditor = opened;
        opened.onDismiss(() -> { if (accountEditor == opened) accountEditor = null; });
    }

    @Override public void exportAccounts() { if (transferUi != null) transferUi.exportAccounts(); }
    @Override public void importAccounts() { if (transferUi != null) transferUi.importAccounts(); }
    @Override public void importCookieText() { if (transferUi != null) transferUi.importCookieText(); }
    @Override public void checkForUpdates() { if (updater != null) updater.check(true); }

    private void importedAccounts() {
        // Imported candidates are already committed; no automatic quota worker runs before this point.
        syncAccountOrder();
        updateStudioAccount();
        renderSidebar();
        updateQuickSwitchFace();
        showScreen(WEB);
    }

    private void syncAccountOrder() {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (AccountStore.Account account : accounts.all()) ids.add(account.id);
        MobileUi.accountOrder(ids);
    }

    private void accountChanged(boolean resetBrowser) {
        syncAccountOrder();
        if (resetBrowser) {
            clearFile();
            browser.resetCurrent();
            if (screen == WEB) renderScreen(current() == null ? STUDIO : WEB);
        }
        updateStudioAccount();
        if (!resetBrowser && screen == WEB) browserChanged();
        if (screen == PROXIES) renderScreen(PROXIES);
        else renderSidebar();
        syncCurrentQuota();
        updateQuickSwitchVisibility();
        updateQuickSwitchFace();
    }

    private View renderProxyManager() {
        LinearLayout page = ui.column();
        page.setBackgroundColor(BG);
        LinearLayout toolbar = ui.toolbar();
        toolbar.addView(sidebarButton());
        LinearLayout heading = ui.column();
        heading.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        heading.addView(ui.single(ui.text("代理设置", 17, INK, true)));
        int inUse = 0;
        for (AccountStore.Account account : accounts.all()) if (account.hasProxy()) inUse++;
        TextView summary = ui.single(ui.text(proxies.all().size() + " 条配置 · " + inUse + " 个账号在用", 12, TEXT2, false));
        LinearLayout.LayoutParams summaryPosition = new LinearLayout.LayoutParams(-1, -2);
        summaryPosition.topMargin = ui.dp(4);
        heading.addView(summary, summaryPosition);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(ui.iconButton(R.drawable.ic_add, "新增代理", INK, () -> editProxy(null)));
        ui.block(page, toolbar, 0);
        ScrollView list = new ScrollView(this);
        list.setFillViewport(true);
        list.setVerticalScrollBarEnabled(false);
        list.addView(new ProxyManagerPage(this, ui, proxies, accounts, new ProxyManagerPage.Host() {
            @Override public void edit(ProxyProfile profile) { editProxy(profile); }
            @Override public void remove(ProxyProfile profile) { removeProxy(profile); }
            @Override public void check() { runProxyCheck(); }
            @Override public String routing() {
                if (!multiProfile) return "WebView 不支持多账号";
                if (!browser.hubAllowed()) return "串号风险：跨代理切换将重新加载，请重新检测";
                return "本机分流";
            }
        }), new ScrollView.LayoutParams(-1, -2));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private void editProxy(ProxyProfile profile) {
        MobileStudio.Entry running = studio.running();
        if (profile != null && running != null && profile.id.equals(runningProxyId(running.owner))) {
            message("请等当前生成结束后再修改此代理。");
            return;
        }
        ProxyEditor.show(this, ui, profile, proxies, accounts, id -> proxyChanged(profile),
            profile == null ? null : () -> removeProxy(profile));
    }

    private String runningProxyId(String accountId) {
        for (AccountStore.Account account : accounts.all()) if (account.id.equals(accountId)) return account.proxyId();
        return "";
    }

    private void proxyChanged(ProxyProfile profile) {
        quotas.cancel(); // A response from the previous route must not update the new route's card.
        AccountStore.Account selected = current();
        boolean affectsCurrent = profile != null && selected != null && profile.id.equals(selected.proxyId());
        if (profile != null) {
            // 分流模式下只关闭用这条代理的账号网页，其它账号不受影响。
            boolean handled = browser.routeEdited(profile.id, accounts.all());
            if (affectsCurrent) {
                clearFile();
                if (!handled) browser.close();
            }
        }
        if (screen == PROXIES) renderScreen(PROXIES);
        else renderSidebar();
        if (screen == WEB && affectsCurrent) renderScreen(WEB);
    }

    /** 检测各账号的代理出口：确认每个账号的网页只走自己的代理，并显示出口 IP。 */
    private void runProxyCheck() {
        if (studio.running() != null) { message("请等当前生成结束后再检测。"); return; }
        if (browser.checking()) { message("检测正在进行。"); return; }
        List<AccountStore.Account> targets = new java.util.ArrayList<>();
        for (AccountStore.Account account : accounts.all()) if (accounts.usable(account, multiProfile)) targets.add(account);
        if (targets.isEmpty()) { message("还没有可检测的账号。"); return; }
        quotas.cancel();
        MobileUi.Sheet sheet = ui.sheet(this, "检测代理出口",
            "逐个账号检查：网页是否只用自己的代理，以及实际出口 IP。检测请求只在本机应答，不会访问 Muse。");
        TextView status = ui.note("正在准备…", 0);
        sheet.add(status, 0);
        LinearLayout list = ui.column();
        sheet.add(list, 12);
        TextView close = ui.outline("关闭", sheet::dismiss);
        sheet.action(close, true);
        sheet.show();
        browser.checkIsolation(targets, this::routeLabel, new MuseWeb.CheckListener() {
            @Override public void progress(String text) {
                if (!isDestroyed()) status.setText(text);
            }

            @Override public void done(List<MuseWeb.CheckRow> rows, int verdict, String reason) {
                if (isDestroyed()) return;
                if (rows == null) {
                    status.setText(reason == null ? "没有完成检测。" : reason);
                    status.setBackground(ui.shape(ACCENT_SOFT, 12, 0));
                    status.setTextColor(INK);
                    return;
                }
                String summary;
                if (verdict > 0) summary = "通过：每个账号的网页都只用了自己的代理。不同代理的账号可以同时在线，切换不重新加载。";
                else if (verdict < 0) summary = "发现问题：有账号的网页用了别的账号的代理凭证。为避免串号，已自动改回“切换到不同代理的账号时重新加载”，已打开的网页已关闭。";
                else summary = "没有完成检测" + (reason == null ? "" : "：" + reason) + " 设置没有改变，可以稍后再试。";
                java.util.HashMap<String, String> ipOwner = new java.util.HashMap<>();
                boolean sharedExit = false;
                for (MuseWeb.CheckRow row : rows) {
                    if (row.ip == null) continue;
                    String owner = ipOwner.putIfAbsent(row.ip, row.route);
                    if (owner != null && !owner.equals(row.route)) sharedExit = true;
                }
                if (sharedExit) summary += "\n注意：有不同的代理出口 IP 相同，可能是同一个上游，换号时请留意。";
                status.setText(summary);
                status.setTextColor(INK);
                status.setBackground(ui.shape(verdict < 0 ? ACCENT_SOFT : RAISED, 12, 0));
                list.removeAllViews();
                for (MuseWeb.CheckRow row : rows) ui.block(list, checkRow(row), list.getChildCount() == 0 ? 0 : 8);
                if (verdict < 0 && screen == WEB) renderScreen(WEB);
                if (screen == PROXIES) {
                    // 刷新页面上的分流状态说明，弹窗保持打开。
                    sheet.dialog.setOnDismissListener(gone -> { if (screen == PROXIES) renderScreen(PROXIES); });
                }
            }
        });
    }

    private String routeLabel(AccountStore.Account account) {
        ProxyProfile profile = proxies.find(account.proxyId());
        if (profile != null) return profile.name + " · " + profile.scheme.toUpperCase(java.util.Locale.ROOT);
        return account.hasProxy() ? account.networkLabel(proxies) : "系统网络";
    }

    private View checkRow(MuseWeb.CheckRow row) {
        LinearLayout line = ui.row();
        line.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11));
        line.setBackground(ui.shape(Color.TRANSPARENT, 14, LINE));
        line.addView(ui.avatar(row.account.name, row.account.id, 32));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(11), 0, ui.dp(8), 0);
        words.addView(ui.single(ui.text(row.account.name, 14, INK, true)));
        TextView route = ui.single(ui.text(row.route, 12, TEXT2, false));
        LinearLayout.LayoutParams routePosition = new LinearLayout.LayoutParams(-1, -2);
        routePosition.topMargin = ui.dp(4);
        words.addView(route, routePosition);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout right = ui.column();
        right.setGravity(Gravity.END);
        String isolation = row.wrong > 0 ? "用了别的账号的代理" : row.isolated() ? "只走自己的代理" : "未完成";
        TextView badge = ui.text(isolation, 12, row.wrong > 0 ? ACCENT : row.isolated() ? INK : MUTED, true);
        badge.setGravity(Gravity.END);
        right.addView(badge);
        TextView ip = ui.single(ui.text(row.ip != null ? row.ip : "出口 IP：" + (row.ipError == null ? "未知" : row.ipError), 12,
            row.ip != null ? TEXT2 : MUTED, false));
        if (row.ip != null) ip.setTypeface(android.graphics.Typeface.MONOSPACE);
        ip.setGravity(Gravity.END);
        ip.setMaxWidth(ui.dp(170));
        LinearLayout.LayoutParams ipPosition = new LinearLayout.LayoutParams(-2, -2);
        ipPosition.topMargin = ui.dp(4);
        ipPosition.gravity = Gravity.END;
        right.addView(ip, ipPosition);
        line.addView(right, new LinearLayout.LayoutParams(-2, -2));
        line.setContentDescription(row.account.name + "，" + row.route + "，" + isolation + "，出口 " + (row.ip == null ? "未知" : row.ip));
        return line;
    }

    private void removeProxy(ProxyProfile profile) {
        try {
            proxies.remove(profile, accounts);
            if (screen == PROXIES) renderScreen(PROXIES);
        } catch (IOException error) { message(error.getMessage()); }
    }

    private View renderWeb() {
        LinearLayout page = ui.column();
        page.setBackgroundColor(BG);
        // 紧凑顶栏：44dp 高，按钮 36dp，地址栏单行（账号名 · 域名 · 代理）。
        LinearLayout bar = ui.row();
        // 与 main 顶部安全区共用 BG；外层不描边，地址栏保留自己的轮廓。
        bar.setBackgroundColor(BG);
        bar.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        bar.setMinimumHeight(ui.dp(44));
        bar.addView(sidebarButton(), webIconSize());
        webBack = ui.compactIconButton(R.drawable.ic_web_back, "返回上一页", INK, browser::back);
        bar.addView(webBack, webIconSize());
        LinearLayout url = ui.row();
        url.setBackground(ui.shape(RAISED, 8, LINE));
        url.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        url.addView(ui.iconView(R.drawable.ic_lock, TEXT2, 12));
        webTitle = ui.single(ui.text("Muse", 13, INK, true));
        webTitle.setMaxWidth(ui.dp(120));
        LinearLayout.LayoutParams titlePosition = new LinearLayout.LayoutParams(-2, -2);
        titlePosition.setMarginStart(ui.dp(6));
        url.addView(webTitle, titlePosition);
        webSubtitle = ui.single(ui.text("muse.ai", 12, TEXT2, false));
        LinearLayout.LayoutParams subtitlePosition = new LinearLayout.LayoutParams(0, -2, 1);
        subtitlePosition.setMarginStart(ui.dp(6));
        url.addView(webSubtitle, subtitlePosition);
        LinearLayout.LayoutParams urlPosition = new LinearLayout.LayoutParams(0, ui.dp(34), 1);
        urlPosition.setMargins(ui.dp(2), 0, ui.dp(2), 0);
        bar.addView(url, urlPosition);
        bar.addView(ui.compactIconButton(R.drawable.ic_home, "Muse 首页", INK, () -> browser.navigate(StudioDraft.HOME)), webIconSize());
        bar.addView(ui.compactIconButton(R.drawable.ic_web_refresh, "刷新网页", INK, () -> {
            if (browser.view() == null) showScreen(WEB); else browser.reload();
        }), webIconSize());
        ui.block(page, bar, 0);

        webProgress = new LinearProgressIndicator(this);
        webProgress.setIndeterminate(true);
        webProgress.setIndicatorColor(ACCENT);
        webProgress.setTrackColor(Color.TRANSPARENT);
        webProgress.setTrackThickness(ui.dp(2));
        page.addView(webProgress, new LinearLayout.LayoutParams(-1, -2));
        webNotice = ui.note("", 0);
        LinearLayout.LayoutParams noticePosition = new LinearLayout.LayoutParams(-1, -2);
        noticePosition.setMargins(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        page.addView(webNotice, noticePosition);

        webFrame = new FrameLayout(this);
        webFrame.setBackgroundColor(BG);
        page.addView(webFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        AccountStore.Account account = current();
        if (account != null) browser.open(account, multiProfile);
        browserChanged();
        return page;
    }

    @Override public void browserChanged() {
        if (closing) return;
        AccountStore.Account account = current();
        String url = browser.url();
        if (account != null && url != null && browser.isOpen(account.id) && StudioDraft.trustedUrl(url)) {
            if (browser.consumeLoginRequired()) { loginPageOwners.add(account.id); quotas.loginRequired(account.id); }
            else if (loginPageOwners.contains(account.id) && !StudioDraft.loginPage(url) && browser.pageReady()) {
                loginPageOwners.remove(account.id);
                quotas.loginReady(account.id);
                syncCurrentQuota();
            }
        }
        if (screen != WEB || webFrame == null) return;
        String host = url == null ? null : Uri.parse(url).getHost();
        ProxyProfile routeProfile = account == null ? null : proxies.find(account.proxyId());
        String route = browser.usingProxy() ? " · " + (routeProfile == null ? "代理" : routeProfile.name) : "";
        webTitle.setText(account == null ? "Muse" : account.name);
        webSubtitle.setText("· " + (host == null ? "muse.ai" : host) + route);
        webBack.setEnabled(browser.canGoBack());
        webBack.setAlpha(browser.canGoBack() ? 1f : 0.45f);
        webProgress.setVisibility(browser.loading() ? View.VISIBLE : View.GONE);
        webNotice.setText(browser.notice());
        webNotice.setVisibility(browser.notice().isEmpty() ? View.GONE : View.VISIBLE);
        WebView active = browser.view();
        if (active != null) {
            if (active.getParent() != webFrame) {
                browser.detach();
                webFrame.removeAllViews();
                webFrame.addView(active, new FrameLayout.LayoutParams(-1, -1));
            }
        } else if (browser.preparing()) {
            webFrame.removeAllViews();
        } else if (webFrame.getChildCount() == 0) {
            String button = account == null ? "选择账号后打开网页" : "重新打开 Muse";
            TextView empty = ui.button(button, RAISED, INK, 0, account == null ? this::openSidebar : () -> showScreen(WEB));
            webFrame.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
    }

    private void saveMedia(MobileStudio.Entry entry) {
        if (studio.media(entry) == null) { message("生成文件不存在。"); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(entry.video ? "video/*" : "image/*").putExtra(Intent.EXTRA_TITLE, "Muse-" + entry.id + "." + entry.extension);
        try { exporting = entry; startActivityForResult(intent, SAVE_MEDIA); }
        catch (RuntimeException error) { exporting = null; message("无法打开文件保存位置。"); }
    }

    @Override public boolean chooseFile(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        clearFile();
        try {
            fileCallback = callback;
            fileChooserMultiple = params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
            startActivityForResult(webFilePicker(params), FILE_PICKER);
            return true;
        } catch (RuntimeException error) {
            clearFile();
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!ready) return;
        microphone.onAndroidResult(requestCode, grantResults);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (!ready) return;
        if (updater != null && updater.onActivityResult(request, result, data)) return;
        if (transferUi != null && transferUi.onActivityResult(request, result, data)) return;
        if (request == SAVE_MEDIA) {
            File source = exporting == null ? null : studio.media(exporting);
            exporting = null;
            if (result == RESULT_OK && data != null && data.getData() != null && source != null) {
                Uri destination = data.getData();
                new Thread(() -> {
                    boolean saved = false;
                    try (FileInputStream input = new FileInputStream(source);
                         OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                        if (output == null) throw new IOException("无法写入");
                        byte[] buffer = new byte[65536];
                        int size;
                        while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                        output.flush();
                        saved = true;
                    } catch (IOException | RuntimeException ignored) { saved = false; }
                    String message = saved ? "已保存生成文件" : "未能保存生成文件";
                    runOnUiThread(() -> { if (!isDestroyed()) message(message); });
                }, "MuseSave").start();
            }
            return;
        }
        if (request == REFERENCE_PICKER) {
            String sessionId = referencePickerSession;
            referencePickerSession = "";
            if (result == RESULT_OK && !sessionId.isEmpty()) importReferences(pickedUris(data), sessionId);
            return;
        }
        if (request == FILE_PICKER && fileCallback != null) {
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null;
            // 系统的 parseResult 只认单个文件；多选结果在 ClipData 里，这里一起读出。
            java.util.List<Uri> picked = result == RESULT_OK ? pickedUris(data) : java.util.Collections.emptyList();
            if (!fileChooserMultiple && picked.size() > 1) picked = picked.subList(0, 1);
            callback.onReceiveValue(picked.isEmpty() ? null : picked.toArray(new Uri[0]));
        }
    }

    /**
     * 网页“上传”用的选择器。系统自带的 createIntent 只取第一个 accept 值当 MIME（像 ".jpg" 这样的扩展名会让
     * 选择器里什么都选不了），也不会打开多选；这里把扩展名转成 MIME，并在网页允许时打开多选。
     */
    private Intent webFilePicker(WebChromeClient.FileChooserParams params) {
        java.util.LinkedHashSet<String> mimes = new java.util.LinkedHashSet<>();
        String[] accepts = params.getAcceptTypes();
        if (accepts != null) for (String raw : accepts) {
            if (raw == null) continue;
            for (String part : raw.split(",")) {
                String type = part.trim().toLowerCase(java.util.Locale.ROOT);
                if (type.isEmpty()) continue;
                if (type.startsWith(".")) {
                    String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
                    if (mime != null) mimes.add(mime);
                } else if (type.contains("/")) mimes.add(type);
            }
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
        if (mimes.size() == 1 && !mimes.contains("*/*")) intent.setType(mimes.iterator().next());
        else {
            intent.setType("*/*");
            if (!mimes.isEmpty() && !mimes.contains("*/*")) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    private void clearFile() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
    }

    @Override public void showVideo(View view, WebChromeClient.CustomViewCallback callback) {
        if (videoView != null || screen != WEB) { callback.onCustomViewHidden(); return; }
        sidebar.close(false);
        dismissAccountMenu();
        if (quickSwitch != null) quickSwitch.setVisibility(View.GONE);
        videoView = view; videoCallback = callback;
        videoView.setBackgroundColor(Color.BLACK);
        main.setVisibility(View.GONE);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-1, -1);
        layout.topMargin = topInset;
        root.addView(videoView, layout);
        root.setBackgroundColor(Color.BLACK);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
    }

    @Override public void hideVideo() {
        if (videoView == null) return;
        root.removeView(videoView);
        videoView = null;
        main.setVisibility(View.VISIBLE);
        root.setBackgroundColor(rootColor());
        applyBarAppearance();
        WebChromeClient.CustomViewCallback callback = videoCallback;
        videoCallback = null;
        if (callback != null) callback.onCustomViewHidden();
        updateQuickSwitchVisibility();
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    @Override public void onBackPressed() {
        if (!ready) { super.onBackPressed(); return; }
        if (videoView != null) { hideVideo(); return; }
        if (sidebar.isOpen()) { if (!sidebar.finishPickingOnBack()) sidebar.close(true); return; }
        if (screen == WEB && browser.canGoBack()) { browser.back(); return; }
        if (screen != STUDIO) { showScreen(STUDIO); return; }
        super.onBackPressed();
    }

    /** 系统提示内存紧张时分级释放后台网页（释放的网页会记下页面，切回时恢复）。 */
    @SuppressWarnings("deprecation")
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!ready || browser == null) return;
        if (level >= TRIM_MEMORY_COMPLETE || level == TRIM_MEMORY_RUNNING_CRITICAL) browser.trimParked(0);
        else if (level >= TRIM_MEMORY_MODERATE) browser.trimParked(1);
        else if (level == TRIM_MEMORY_RUNNING_LOW) browser.trimParked(Math.max(1, browser.parkCapacity() / 2));
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (ready && browser != null) browser.trimParked(0);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        quotaHandler.removeCallbacks(quotaPulse);
        quotaPulse.run();
        if (updater != null) updater.resume();
    }

    @Override protected void onPause() {
        foreground = false;
        if (updater != null) updater.pause();
        quotaHandler.removeCallbacks(quotaPulse);
        if (quotas != null) quotas.cancel();
        super.onPause();
    }

    @Override protected void onStop() {
        if (!ready) { super.onStop(); return; }
        dismissAccountMenu();
        draftHandler.removeCallbacks(persistComposerHeight);
        persistComposerHeight.run();
        syncStudioDraft();
        if (studio != null) studio.flushSessions();
        microphone.cancelPending();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (!ready) { super.onDestroy(); return; }
        closing = true;
        if (updater != null) updater.close();
        if (transferUi != null) transferUi.close();
        dismissAccountMenu();
        clearFile();
        quotas.cancel();
        studio.close();
        browser.close();
        super.onDestroy();
    }
}
