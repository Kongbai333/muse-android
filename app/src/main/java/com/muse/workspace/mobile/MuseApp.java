package com.muse.workspace.mobile;

import android.app.Application;

/** 进程入口：最先安装闪退记录，并在界面创建前应用持久化的三态外观。 */
public final class MuseApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CrashReport.install(this);
        MobileUi.applyNightMode(this);
    }
}
