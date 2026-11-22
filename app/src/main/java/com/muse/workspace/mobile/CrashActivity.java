package com.muse.workspace.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 显示上次闪退的错误信息。只用系统控件，不依赖 Muse 主题与界面代码。 */
public final class CrashActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String trace = CrashReport.take(this);
        String report = trace == null ? "没有读到错误记录。" : trace;
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0F0F11);
        page.setFitsSystemWindows(true);
        page.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Muse 遇到错误");
        title.setTextColor(0xFFF1EFEA);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        TextView hint = new TextView(this);
        hint.setText("请截图这一页（最上面“根因”那几行最重要），或点“复制错误信息”，发给 AWS Quick。点“继续打开”会再试一次。");
        hint.setTextColor(0xFFA4A29C);
        hint.setTextSize(13);
        hint.setPadding(0, Math.round(8 * density), 0, Math.round(12 * density));
        page.addView(hint);

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(report);
        body.setTextColor(0xFFE8E6E1);
        body.setTextSize(11);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setBackgroundColor(0xFF17171A);
        body.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, Math.round(12 * density), 0, 0);
        Button copy = new Button(this);
        copy.setText("复制错误信息");
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Muse 闪退", report));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(Math.round(10 * density), 1));
        Button again = new Button(this);
        again.setText("继续打开");
        again.setOnClickListener(view -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        actions.addView(again, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        setContentView(page);
    }
}
