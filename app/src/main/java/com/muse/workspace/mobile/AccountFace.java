package com.muse.workspace.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import static com.muse.workspace.mobile.MobileUi.*;

/** Shared native quota ring: all account surfaces use the same real observation. */
final class AccountFace extends FrameLayout {
    private final MobileUi ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final TextView initial;
    private final ImageView photo;
    private final float stroke;
    private float progress;
    private int tone;

    AccountFace(Context context, MobileUi ui, AccountStore.Account account, int size, boolean selected,
                boolean usable, AccountQuota quota, Bitmap bitmap) {
        super(context);
        this.ui = ui;
        setWillNotDraw(false);
        stroke = ui.dp(size >= 30 ? 2.4f : 1.9f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        initial = ui.text(account == null || account.name.isEmpty() ? "M"
            : account.name.substring(0, account.name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT),
            Math.max(8, Math.round(size * .34f)), selected ? BG : INK, true);
        initial.setGravity(Gravity.CENTER);
        initial.setBackground(ui.shape(selected ? INK : RAISED, 99, 0));
        int inset = Math.round(stroke + ui.dp(size >= 24 ? 2 : 1.5f));
        LayoutParams position = new LayoutParams(-1, -1, Gravity.CENTER);
        position.setMargins(inset, inset, inset, inset);
        addView(initial, position);
        photo = new ImageView(context);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setBackground(ui.shape(RAISED, 99, 0));
        photo.setClipToOutline(true);
        addView(photo, new LayoutParams(position));
        setPhoto(bitmap);
        setQuota(quota, usable);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void setPhoto(Bitmap bitmap) {
        photo.setImageBitmap(bitmap);
        photo.setVisibility(bitmap == null ? GONE : VISIBLE);
        initial.setVisibility(bitmap == null ? VISIBLE : GONE);
    }

    void setQuota(AccountQuota quota, boolean usable) {
        tone = quota == null || !usable ? MUTED : quotaColor(quota.used() == null ? 0d : quota.used());
        progress = quota == null || !usable ? 0f : quota.used() == null ? 1f
            : (float) Math.max(0, Math.min(1, quota.used() / 100));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        bounds.set(stroke / 2, stroke / 2, getWidth() - stroke / 2, getHeight() - stroke / 2);
        paint.setStrokeWidth(stroke);
        paint.setColor(LINE_STRONG);
        canvas.drawOval(bounds, paint);
        paint.setColor(tone);
        if (progress > 0) canvas.drawArc(bounds, -90, progress * 360, false, paint);
    }
}
