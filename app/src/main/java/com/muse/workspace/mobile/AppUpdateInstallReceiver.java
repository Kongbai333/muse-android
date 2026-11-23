package com.muse.workspace.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit private PendingIntent target. Never starts activities from the background. */
public final class AppUpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){AppUpdateInstaller.receive(context,intent);}
}
