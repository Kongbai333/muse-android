package com.muse.workspace.mobile;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import static com.muse.workspace.mobile.MobileUi.*;

/** 工作室生成记录：按时间从上到下排列，最新的在最下面（与聊天一致）。生成中的卡片显示步骤与进度，取回文件后画面从模糊、发暗“显影”成清晰。 */
final class StudioResults {
    private static final int THUMBNAIL_LIMIT = 10;
    final LinearLayout view;
    private final LinearLayout cards;
    private final Activity activity;
    private final MobileUi ui;
    private final MobileStudio studio;
    private final Consumer<MobileStudio.Entry> reuse, save;
    private final Consumer<String> starter;
    private Result latest;
    private String firstId;
    private String sessionId;

    StudioResults(Activity activity, MobileUi ui, MobileStudio studio,
                  Consumer<MobileStudio.Entry> reuse, Consumer<MobileStudio.Entry> save, Consumer<String> starter) {
        this.activity = activity; this.ui = ui; this.studio = studio; this.reuse = reuse; this.save = save;
        this.starter = starter;
        view = ui.column();
        cards = ui.column();
        ui.block(view, cards, 0);
        update();
    }

    void update() {
        StudioSession session = studio.currentSession();
        String currentSession = session == null ? "" : session.id;
        List<MobileStudio.Entry> entries = studio.currentEntries();
        if (!Objects.equals(sessionId, currentSession)) {
            sessionId = currentSession;
            firstId = null;
            latest = null;
            cards.removeAllViews();
        }
        if (entries.isEmpty()) {
            if (cards.getChildCount() == 0 || firstId != null) {
                cards.removeAllViews();
                firstId = null;
                latest = null;
                ui.block(cards, welcome(), 0);
            }
            return;
        }
        String first = entries.get(0).id;
        if (first.equals(firstId) && latest != null) { latest.refresh(); return; }
        firstId = first;
        cards.removeAllViews();
        // 记录按“最新在前”保存；显示时倒过来，最新的一条在最下面。
        for (int index = entries.size() - 1; index >= 0; index--) {
            Result result = new Result(entries.get(index), index < THUMBNAIL_LIMIT);
            if (index == 0) latest = result;
            ui.block(cards, result.card, cards.getChildCount() == 0 ? 0 : 12);
        }
    }

    private View welcome() {
        LinearLayout welcome = ui.column();
        welcome.setPadding(ui.dp(6), ui.dp(48), ui.dp(6), ui.dp(12));
        welcome.addView(ui.text("从一个想法开始", 24, INK, true));
        ui.block(welcome, ui.text("描述画面、加一张参考图，或者先从下面任选一句改起。", 14, TEXT2, false), 12);
        String[][] starters = {
            {"风格和细节", "胶片颗粒，低饱和，柔和侧光，画面留白"},
            {"动作和氛围", "人物回头的一瞬，风吹起衣角，黄昏的逆光"},
            {"描述镜头", "低角度广角，镜头缓慢推进，跟随主体"}
        };
        for (int index = 0; index < starters.length; index++) {
            String[] item = starters[index];
            LinearLayout row = ui.row();
            row.setGravity(Gravity.TOP);
            row.setPadding(ui.dp(14), ui.dp(13), ui.dp(14), ui.dp(13));
            TextView kind = ui.text(item[0], 12, TEXT2, false);
            row.addView(kind, new LinearLayout.LayoutParams(ui.dp(74), -2));
            row.addView(ui.text(item[1], 13.5f, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            ui.touch(row, SURFACE, 10, LINE, () -> starter.accept(item[1]));
            row.setContentDescription(item[0] + "：" + item[1]);
            ui.block(welcome, row, index == 0 ? 24 : 8);
        }
        return welcome;
    }

    /** 按比例测量的画面框；竖图收窄，横图占满。 */
    static final class AspectFrame extends FrameLayout {
        float ratioWidth = 4, ratioHeight = 5;
        int previewHeight = 160;

        AspectFrame(Activity context) { super(context); }

        void setRatio(float width, float height) {
            if (width <= 0 || height <= 0 || (width == ratioWidth && height == ratioHeight)) return;
            ratioWidth = width; ratioHeight = height;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = Math.round(previewHeight * getResources().getDisplayMetrics().density);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    private final class Result {
        final MobileStudio.Entry entry;
        final boolean thumbnail;
        final LinearLayout card, actions;
        final TextView reply;
        final AspectFrame media;
        final ImageView image;
        final FrameLayout overlay;
        final LinearLayout stopped;
        final View glow;
        final TextView step, stoppedText;
        File currentFile;
        boolean wasRunning, initialized, developed, ticking;
        String stepText = "";
        ObjectAnimator pulse;
        ValueAnimator development;
        // 显影计时：每秒刷新一次，离开屏幕即停。
        final Runnable tick = new Runnable() {
            @Override public void run() {
                updateClock();
                if (ticking && media.isAttachedToWindow()) media.postDelayed(this, 1000);
            }
        };

        Result(MobileStudio.Entry entry, boolean thumbnail) {
            this.entry = entry;
            this.thumbnail = thumbnail;
            card = ui.column();
            card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            card.setBackground(ui.shape(SURFACE, 16, LINE));
            LinearLayout promptRow = ui.row(); promptRow.setGravity(Gravity.TOP);
            StudioPromptView prompt = new StudioPromptView(activity, ui, entry.prompt, () -> showPrompt(entry));
            promptRow.addView(prompt, new LinearLayout.LayoutParams(0, -2, 1));
            TextView stamp = ui.meta(new SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE).format(new Date(entry.created)), 9.5f, MUTED);
            LinearLayout.LayoutParams stampPosition = new LinearLayout.LayoutParams(-2, -2); stampPosition.setMarginStart(ui.dp(8));
            promptRow.addView(stamp, stampPosition); card.addView(promptRow);
            ui.block(card, prompt.hint(), 0);

            media = new AspectFrame(activity);
            float[] ratio = MobileUi.ratioValue(entry.ratio);
            media.setRatio(ratio[0], ratio[1]);
            media.setBackground(ui.shape(RAISED, R_MD, LINE_STRONG));
            media.setClipToOutline(true);
            image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setVisibility(View.GONE);
            media.addView(image, new FrameLayout.LayoutParams(-1, -1));

            // 显影中：整块暗房底色，底部一团安全灯红光缓慢呼吸；中间等宽字「显影中」、步骤与计时；底边一条进度线。
            // 白昼主题也保持暗房，和结果出来后的“显影”动画接得上。
            overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(RAISED);
            glow = new View(activity);
            android.graphics.drawable.GradientDrawable light = new android.graphics.drawable.GradientDrawable();
            light.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
            light.setColors(new int[]{androidx.core.graphics.ColorUtils.setAlphaComponent(ACCENT, 0x5C),
                androidx.core.graphics.ColorUtils.setAlphaComponent(ACCENT, 0x1A), 0x00000000});
            light.setGradientCenter(0.5f, 1.12f);
            light.setGradientRadius(ui.dp(300));
            glow.setBackground(light);
            overlay.addView(glow, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout label = ui.column();
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView title = ui.metaStrong("暗房显影中…", 12.5f, ACCENT);
            label.addView(title);
            step = ui.single(ui.meta("", 10, MUTED));
            step.setGravity(Gravity.CENTER);
            step.setMaxWidth(ui.dp(260));
            LinearLayout.LayoutParams stepPosition = new LinearLayout.LayoutParams(-2, -2);
            stepPosition.topMargin = ui.dp(10);
            label.addView(step, stepPosition);
            overlay.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            LinearProgressIndicator progress = new LinearProgressIndicator(activity);
            progress.setIndeterminate(true);
            progress.setIndicatorColor(ACCENT);
            progress.setTrackColor(LINE);
            progress.setTrackThickness(ui.dp(2));
            progress.setTrackCornerRadius(0);
            overlay.addView(progress, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            overlay.setVisibility(View.GONE);
            media.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            media.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    if (!ticking) return;
                    media.removeCallbacks(tick);
                    media.post(tick);
                    startPulse();
                }
                @Override public void onViewDetachedFromWindow(View view) {
                    media.removeCallbacks(tick);
                    stopPulse();
                    if (development != null) development.cancel();
                }
            });

            stopped = ui.column();
            stopped.setGravity(Gravity.CENTER);
            stopped.setPadding(ui.dp(18), 0, ui.dp(18), 0);
            stoppedText = ui.text("", 13, TEXT2, false);
            stoppedText.setGravity(Gravity.CENTER);
            stopped.addView(stoppedText);
            stopped.setVisibility(View.GONE);
            media.addView(stopped, new FrameLayout.LayoutParams(-1, -1));

            if (thumbnail) ui.block(card, media, 10);
            reply = ui.text("", 13, TEXT2, false);
            reply.setTextIsSelectable(true);
            ui.block(card, reply, 6);

            LinearLayout meta = ui.row();
            String timestamp = new SimpleDateFormat("M/d HH:mm", Locale.SIMPLIFIED_CHINESE).format(new Date(entry.created));
            String aspect = "Auto".equals(entry.ratio) ? "自动画幅" : entry.ratio;
            // 底片边码：IMG / VID · 画幅 · 时长 · 账号；读屏仍读中文。
            String code = (entry.video ? "VID" : "IMG") + " · " + ("Auto".equals(entry.ratio) ? "AUTO" : entry.ratio)
                + (entry.video ? " · " + entry.seconds + "S" : "") + " · " + entry.name;
            meta.setContentDescription((entry.video ? "视频" : "图片") + "，" + aspect + (entry.video ? "，" + entry.seconds + " 秒" : "")
                + "，" + entry.name + "，" + timestamp);
            meta.addView(ui.single(ui.meta(code, 9.5f, MUTED)), new LinearLayout.LayoutParams(0, -2, 1));
            ui.block(card, meta, 8);

            actions = ui.row();
            meta.addView(actions, new LinearLayout.LayoutParams(-2, -2));
            refresh();
        }

        void refresh() {
            if (!entry.text.contentEquals(reply.getText())) reply.setText(entry.text);
            reply.setVisibility(entry.text.isEmpty() ? View.GONE : View.VISIBLE);
            boolean running = studio.running() == entry;
            int previewHeight = running ? 144 : 160;
            if (media.previewHeight != previewHeight) { media.previewHeight = previewHeight; media.requestLayout(); }
            File file = studio.media(entry);
            String status = entry.status == null ? "" : entry.status;
            if (!status.equals(stepText)) { stepText = status; updateClock(); }
            if (!status.contentEquals(stoppedText.getText())) stoppedText.setText(status.isEmpty() ? "未取得结果" : status);
            overlay.setVisibility(running && file == null ? View.VISIBLE : View.GONE);
            setDeveloping(running && file == null);
            stopped.setVisibility(!running && file == null ? View.VISIBLE : View.GONE);
            media.setContentDescription(running ? status : file == null ? status : entry.video ? "播放视频" : "查看生成图片");
            if (initialized && running == wasRunning && Objects.equals(currentFile, file)) return;
            boolean justFinished = initialized && wasRunning && !running && file != null;
            initialized = true;
            wasRunning = running;
            currentFile = file;
            actions.removeAllViews();
            if (running) {
                addAction(ui.small("停止", 0, false, studio::stop));
                return;
            }
            // 动作只留两个常用的，其余收进「⋯」；点图片本身就是查看 / 播放。
            addAction(ui.small("复用", 0, true, () -> reuse.accept(entry)));
            if (file != null) addAction(ui.small("保存", 0, false, () -> save.accept(entry)));
            if (file == null) addAction(ui.small("提示词", 0, true, () -> showPrompt(entry)));
            else {
                View spacer = ui.spacer();
                actions.addView(spacer);
                TextView more = ui.small("", R.drawable.ic_more, true, () -> {
                    String look = entry.video ? "播放视频" : "查看大图";
                    MobileUi.dialog(activity).setItems(new String[]{look, "查看提示词"}, (dialog, which) -> {
                        if (which == 0) preview(entry, file);
                        else showPrompt(entry);
                    }).show();
                });
                more.setCompoundDrawablePadding(0);
                more.setPadding(ui.dp(9), 0, ui.dp(9), 0);
                more.setContentDescription("更多：" + (entry.video ? "播放视频" : "查看大图") + "、查看提示词");
                more.setMinHeight(ui.dp(28)); more.setMinimumHeight(ui.dp(28));
                actions.addView(more, new LinearLayout.LayoutParams(-2, ui.dp(28)));
            }
            if (file == null || !thumbnail) return;
            media.setOnClickListener(tap -> preview(entry, file));
            if (entry.video) {
                readVideoFrame(file, 720, bitmap -> {
                    if (!image.isAttachedToWindow()) { if (bitmap != null) bitmap.recycle(); return; }
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap);
                        image.setVisibility(View.VISIBLE);
                        if ("Auto".equals(entry.ratio)) media.setRatio(bitmap.getWidth(), bitmap.getHeight());
                    }
                    addPlayBadge();
                    if (justFinished) develop();
                });
            } else {
                readImage(file, 900, bitmap -> {
                    if (bitmap == null || !image.isAttachedToWindow()) return;
                    image.setImageBitmap(bitmap);
                    image.setVisibility(View.VISIBLE);
                    if ("Auto".equals(entry.ratio)) media.setRatio(bitmap.getWidth(), bitmap.getHeight());
                    if (justFinished) develop();
                });
            }
        }

        private void setDeveloping(boolean on) {
            if (on == ticking) return;
            ticking = on;
            media.removeCallbacks(tick);
            if (on) {
                updateClock();
                if (media.isAttachedToWindow()) media.postDelayed(tick, 1000);
                startPulse();
            } else stopPulse();
        }

        /** 步骤 · 已用时间，例如「正在生成 · 00:42」。 */
        private void updateClock() {
            long seconds = Math.max(0, (System.currentTimeMillis() - entry.created) / 1000);
            String clock = seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
            String text = stepText.isEmpty() ? clock : stepText + " · " + clock;
            if (!text.contentEquals(step.getText())) step.setText(text);
            overlay.setContentDescription("显影中，" + (stepText.isEmpty() ? "" : stepText + "，") + "已用 " + clock);
        }

        /** 安全灯：红光在 55%–100% 之间缓慢呼吸；系统关闭动画时保持常亮。 */
        private void startPulse() {
            if (pulse != null || !ticking || !media.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) return;
            pulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 1f, 0.55f);
            pulse.setDuration(1800);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            pulse.start();
        }

        private void stopPulse() {
            if (pulse != null) pulse.cancel();
            pulse = null;
            glow.setAlpha(1f);
        }

        private void addAction(View button) {
            if (button instanceof TextView label) { label.setTextSize(11.5f); label.setMinHeight(ui.dp(28)); label.setMinimumHeight(ui.dp(28)); label.setPadding(ui.dp(10), 0, ui.dp(10), 0); }
            LinearLayout.LayoutParams geometry = new LinearLayout.LayoutParams(-2, ui.rowHeight(28));
            if (actions.getChildCount() != 0) geometry.setMarginStart(ui.dp(6));
            actions.addView(button, geometry);
        }

        private void addPlayBadge() {
            FrameLayout badge = new FrameLayout(activity);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(0x8C0C0C0E);
            badge.setBackground(circle);
            View play = ui.iconView(R.drawable.ic_play, 0xFFFFFFFF, 24);
            FrameLayout.LayoutParams playPosition = new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER);
            playPosition.leftMargin = ui.dp(2);
            badge.addView(play, playPosition);
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            media.addView(badge, new FrameLayout.LayoutParams(ui.dp(56), ui.dp(56), Gravity.CENTER));
            TextView duration = ui.figure(String.format(Locale.ROOT, "00:%02d", entry.seconds), 11.5f, 0xFFFFFFFF);
            duration.setBackground(ui.shape(0x990C0C0E, 6, 0));
            duration.setPadding(ui.dp(7), ui.dp(3), ui.dp(7), ui.dp(3));
            FrameLayout.LayoutParams durationPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            durationPosition.setMargins(0, ui.dp(10), ui.dp(10), 0);
            media.addView(duration, durationPosition);
        }

        /** 显影：模糊 18→0、亮度与饱和度从低到正常；Android 12 以下只做淡入。 */
        private void develop() {
            if (developed || !ValueAnimator.areAnimatorsEnabled()) return;
            developed = true;
            ValueAnimator motion = ValueAnimator.ofFloat(0f, 1f);
            development = motion;
            motion.setDuration(900);
            motion.setInterpolator(new DecelerateInterpolator(1.6f));
            motion.addUpdateListener(value -> {
                float p = (float) value.getAnimatedValue();
                image.setScaleX(1.06f - 0.06f * p);
                image.setScaleY(1.06f - 0.06f * p);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    float blur = Math.max(0.01f, ui.dp(18) * (1f - p));
                    ColorMatrix color = new ColorMatrix();
                    color.setSaturation(p);
                    float light = 0.12f + 0.88f * p;
                    ColorMatrix scale = new ColorMatrix();
                    scale.setScale(light, light, light, 1f);
                    color.postConcat(scale);
                    image.setRenderEffect(RenderEffect.createChainEffect(
                        RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(color)),
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)));
                    if (p >= 1f) image.setRenderEffect(null);
                } else {
                    image.setAlpha(p);
                }
            });
            motion.start();
        }
    }

    /** 查看完整提示词：可选中、复制，或放回输入框重新编辑。 */
    private void showPrompt(MobileStudio.Entry entry) {
        String aspect = "Auto".equals(entry.ratio) ? "自动画幅" : entry.ratio;
        String stamp = new SimpleDateFormat("M/d HH:mm", Locale.SIMPLIFIED_CHINESE).format(new Date(entry.created));
        MobileUi.Sheet sheet = ui.sheet(activity, "提示词", (entry.video ? "视频" : "图片") + " · " + aspect
            + (entry.video ? " · " + entry.seconds + " 秒" : "") + " · " + entry.name + " · " + stamp);
        TextView text = ui.text(entry.prompt, 15, INK, false);
        text.setLineSpacing(ui.dp(5), 1f);
        text.setTextIsSelectable(true);
        text.setBackground(ui.shape(RAISED, 13, 0));
        text.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        sheet.add(text, 0);
        if (!entry.text.isEmpty()) {
            TextView reply = ui.text(entry.text, 13, TEXT2, false);
            reply.setTextIsSelectable(true);
            sheet.add(reply, 12);
        }
        sheet.action(ui.outline("复制", () -> {
            android.content.ClipboardManager clipboard = activity.getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Muse 提示词", entry.prompt));
            MobileUi.notice(activity, "已复制提示词");
        }), true);
        sheet.action(ui.primary("重新编辑", () -> { sheet.dismiss(); reuse.accept(entry); }), true);
        sheet.show();
    }

    private void preview(MobileStudio.Entry entry, File file) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = ui.column();
        content.setBackgroundColor(0xFF050505);
        content.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(18));

        LinearLayout toolbar = ui.row();
        toolbar.addView(ui.iconButton(R.drawable.ic_close, "关闭", 0xFFF1EFEA, dialog::dismiss));
        String aspect = "Auto".equals(entry.ratio) ? "自动画幅" : entry.ratio;
        TextView title = ui.text((entry.video ? "生成视频" : "生成原图") + " · " + aspect, 12.5f, 0xFFA4A29C, false);
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(ui.iconButton(R.drawable.ic_download, "保存文件", 0xFFF1EFEA, () -> save.accept(entry)));
        ui.block(content, toolbar, 0);

        FrameLayout preview = new FrameLayout(activity);
        LinearLayout.LayoutParams previewPosition = new LinearLayout.LayoutParams(-1, 0, 1);
        previewPosition.topMargin = ui.dp(8);
        content.addView(preview, previewPosition);

        LinearLayout foot = ui.column();
        foot.setPadding(ui.dp(10), ui.dp(14), ui.dp(10), 0);
        TextView prompt = ui.text(entry.prompt, 13.5f, 0xFFC9C7C1, false);
        prompt.setMaxLines(4);
        prompt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        prompt.setOnClickListener(view -> {
            boolean collapsed = prompt.getMaxLines() == 4;
            prompt.setMaxLines(collapsed ? 14 : 4);
            prompt.setEllipsize(collapsed ? null : android.text.TextUtils.TruncateAt.END);
        });
        foot.addView(prompt);
        LinearLayout buttons = ui.row();
        buttons.addView(darkButton("保存文件", R.drawable.ic_download, () -> save.accept(entry)));
        TextView copy = darkButton("复制提示词", 0, () -> {
            android.content.ClipboardManager clipboard = activity.getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Muse 提示词", entry.prompt));
            MobileUi.notice(activity, "已复制提示词");
        });
        LinearLayout.LayoutParams copyPosition = new LinearLayout.LayoutParams(-2, ui.dp(38));
        copyPosition.setMarginStart(ui.dp(8));
        buttons.addView(copy, copyPosition);
        TextView edit = darkButton("重新编辑", 0, () -> { dialog.dismiss(); reuse.accept(entry); });
        LinearLayout.LayoutParams editPosition = new LinearLayout.LayoutParams(-2, ui.dp(38));
        editPosition.setMarginStart(ui.dp(8));
        buttons.addView(edit, editPosition);
        ui.block(foot, buttons, 14);
        content.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        if (entry.video) {
            VideoView video = new VideoView(activity);
            preview.addView(video, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            MediaController controls = new MediaController(activity);
            video.setMediaController(controls);
            video.setOnErrorListener((player, what, extra) -> {
                preview.removeAllViews();
                TextView message = ui.text("系统播放器无法播放此格式，可保存文件后打开。", 14, 0xFFA4A29C, false);
                preview.addView(message, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                return true;
            });
            dialog.setOnDismissListener(closed -> video.stopPlayback());
            dialog.setContentView(content);
            dialog.show();
            video.setVideoPath(file.getPath());
            video.start();
        } else {
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
            dialog.setContentView(content);
            dialog.show();
            readImage(file, 3072, bitmap -> {
                if (!dialog.isShowing()) return;
                if (bitmap != null) image.setImageBitmap(bitmap);
                else preview.addView(ui.text("无法显示图片，可保存原文件。", 14, 0xFFA4A29C, false),
                    new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            });
        }
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF050505));
            window.setLayout(-1, -1);
            window.setStatusBarColor(0xFF050505);
            window.setNavigationBarColor(0xFF050505);
        }
    }

    private TextView darkButton(String label, int icon, Runnable action) {
        TextView button = ui.text(label, 13, 0xFFF1EFEA, false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(ui.dp(icon == 0 ? 14 : 12), 0, ui.dp(14), 0);
        if (icon != 0) {
            android.graphics.drawable.Drawable drawable = activity.getDrawable(icon);
            if (drawable != null) {
                drawable = drawable.mutate();
                drawable.setTint(0xFFF1EFEA);
                drawable.setBounds(0, 0, ui.dp(15), ui.dp(15));
                button.setCompoundDrawablesRelative(drawable, null, null, null);
                button.setCompoundDrawablePadding(ui.dp(6));
            }
        }
        button.setMinHeight(ui.dp(38));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22FFFFFF), ui.shape(Color.TRANSPARENT, 11, 0x33FFFFFF),
            ui.shape(Color.WHITE, 11, 0)));
        button.setOnClickListener(view -> action.run());
        button.setFocusable(true);
        return button;
    }

    private void readImage(File file, int cap, Consumer<Bitmap> ready) {
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), options);
                options.inJustDecodeBounds = false;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > cap) options.inSampleSize *= 2;
                bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            } catch (RuntimeException | OutOfMemoryError ignored) { }
            Bitmap loaded = bitmap;
            activity.runOnUiThread(() -> { if (!activity.isDestroyed()) ready.accept(loaded); });
        }, "MuseImagePreview").start();
    }

    private void readVideoFrame(File file, int cap, Consumer<Bitmap> ready) {
        new Thread(() -> {
            Bitmap bitmap = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getPath());
                Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    int longest = Math.max(frame.getWidth(), frame.getHeight());
                    if (longest > cap) {
                        float scale = cap / (float) longest;
                        bitmap = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * scale)),
                            Math.max(1, Math.round(frame.getHeight() * scale)), true);
                        if (bitmap != frame) frame.recycle();
                    } else bitmap = frame;
                }
            } catch (RuntimeException | OutOfMemoryError ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
            }
            Bitmap loaded = bitmap;
            activity.runOnUiThread(() -> {
                if (!activity.isDestroyed()) ready.accept(loaded);
                else if (loaded != null) loaded.recycle();
            });
        }, "MuseVideoFrame").start();
    }
}
