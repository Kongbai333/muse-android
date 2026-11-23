package com.muse.workspace.mobile;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Own-session native boundary; byte staging is separate from foreground consent/commit. */
final class AppUpdateInstaller {
    static final String ACTION="com.muse.workspace.mobile.APP_UPDATE_STATUS",PREFS="muse-app-update-install";
    interface Listener { void event(String code); }
    private record Confirmation(int session,Intent intent) { }
    private static volatile WeakReference<Listener> listener=new WeakReference<>(null);
    private static volatile Confirmation pending;
    private final Context context;
    private final PackageInstaller installer;
    private final SharedPreferences prefs;
    AppUpdateInstaller(Context context){this.context=context.getApplicationContext();installer=this.context.getPackageManager().getPackageInstaller();prefs=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static File directory(Context context){return new File(context.getFilesDir(),"app-updates");}
    boolean permitted(){try{return context.getPackageManager().canRequestPackageInstalls();}catch(RuntimeException error){return false;}}
    void attach(Listener value){listener=new WeakReference<>(value);}
    void detach(Listener value){if(listener.get()==value)listener=new WeakReference<>(null);}
    Intent confirmation(){Confirmation value=pending;return value!=null&&value.session==prefs.getInt("session",-1)?new Intent(value.intent):null;}
    void confirmationLaunched(){pending=null;}
    boolean busy(){
        int id=prefs.getInt("session",-1);if(id<0)return false;
        try{if(installer.getSessionInfo(id)!=null)return true;prefs.edit().remove("session").remove("phase").apply();if(pending!=null&&pending.session==id)pending=null;return false;}
        catch(RuntimeException error){return true;}
    }
    AppUpdateRelease.Apk current()throws AppUpdateRelease.Failure{
        try{return identity(context.getPackageManager().getPackageInfo(context.getPackageName(),flags()));}
        catch(Exception error){throw new AppUpdateRelease.Failure("package");}
    }
    int stage(AppUpdateDownload.Verified verified,AppUpdateRelease release,AtomicBoolean cancelled)throws AppUpdateRelease.Failure{
        int id=-1;
        try{
            if(cancelled.get())throw new AppUpdateRelease.Failure("cancelled");
            if(!permitted())throw new AppUpdateRelease.Failure("permission");
            if(busy())throw new AppUpdateRelease.Failure("install_busy");
            File file=verified.file();
            if(!file.isFile()||!directory(context).getCanonicalFile().equals(file.getCanonicalFile().getParentFile())||file.length()!=release.apk.size()||!AppUpdateDownload.hash(file).equals(verified.sha256()))throw new AppUpdateRelease.Failure("checksum");
            PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),flags());
            if(archive==null||archive.applicationInfo==null||archive.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new AppUpdateRelease.Failure("system");
            AppUpdateRelease.validateIdentity(current(),identity(archive),release);
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());params.setSize(file.length());params.setInstallReason(PackageManager.INSTALL_REASON_USER);
            if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            id=installer.createSession(params);
            if(!prefs.edit().putInt("session",id).putString("phase","staging").commit())throw new AppUpdateRelease.Failure("storage");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long copied=0;
            try(PackageInstaller.Session session=installer.openSession(id);InputStream input=new FileInputStream(file);OutputStream output=session.openWrite("base.apk",0,file.length())){
                byte[] buffer=new byte[32768];int count;
                while((count=input.read(buffer))!=-1){if(cancelled.get())throw new AppUpdateRelease.Failure("cancelled");copied+=count;if(copied>release.apk.size())throw new AppUpdateRelease.Failure("size");output.write(buffer,0,count);hash.update(buffer,0,count);}
                if(copied!=release.apk.size()||!AppUpdateDownload.hex(hash.digest()).equals(verified.sha256()))throw new AppUpdateRelease.Failure("checksum");session.fsync(output);
            }
            if(cancelled.get())throw new AppUpdateRelease.Failure("cancelled");return id;
        }catch(Exception error){
            if(id>=0)try{installer.abandonSession(id);}catch(RuntimeException ignored){}
            if(id>=0&&prefs.getInt("session",-1)==id)prefs.edit().remove("session").remove("phase").apply();
            if(error instanceof AppUpdateRelease.Failure failure)throw failure;
            throw new AppUpdateRelease.Failure("install");
        }
    }
    void commit(int id)throws AppUpdateRelease.Failure{
        try{
            if(id<0||id!=prefs.getInt("session",-1)||!permitted())throw new AppUpdateRelease.Failure("permission");
            Intent callback=new Intent(context,AppUpdateInstallReceiver.class).setAction(ACTION).setPackage(context.getPackageName()).putExtra(PackageInstaller.EXTRA_SESSION_ID,id);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
            PendingIntent receiver=PendingIntent.getBroadcast(context,id,callback,flags);
            try(PackageInstaller.Session session=installer.openSession(id)){
                if(!prefs.edit().putString("phase","committed").commit())throw new AppUpdateRelease.Failure("storage");
                session.commit(receiver.getIntentSender());
            }
            try{AppUpdateDownload.clean(directory(context));}catch(AppUpdateRelease.Failure ignored){}
        }catch(Exception error){if(error instanceof AppUpdateRelease.Failure failure)throw failure;throw new AppUpdateRelease.Failure("install");}
    }
    void cancelPending()throws AppUpdateRelease.Failure{
        int id=prefs.getInt("session",-1);
        try{
            if(id>=0){PackageInstaller.SessionInfo info=installer.getSessionInfo(id);if(info!=null){if(!context.getPackageName().equals(info.getInstallerPackageName()))throw new AppUpdateRelease.Failure("install");installer.abandonSession(id);}}
            prefs.edit().remove("session").remove("phase").apply();pending=null;AppUpdateDownload.clean(directory(context));
        }catch(RuntimeException error){throw new AppUpdateRelease.Failure("install");}
    }
    static void receive(Context context,Intent intent){
        if(intent==null||!ACTION.equals(intent.getAction()))return;
        SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        int id=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1);if(id<0||id!=prefs.getInt("session",-1))return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        String code;
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent action;
            try{if(Build.VERSION.SDK_INT>=33)action=intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class);
            else action=intent.getParcelableExtra(Intent.EXTRA_INTENT);}catch(RuntimeException error){notifyListener("install");return;}
            if(action==null){notifyListener("install");return;}
            pending=new Confirmation(id,new Intent(action));code="confirm";
        }else{
            prefs.edit().remove("session").remove("phase").putString("last_status",status==PackageInstaller.STATUS_SUCCESS?"success":"failed").apply();pending=null;
            try{AppUpdateDownload.clean(directory(context));}catch(AppUpdateRelease.Failure ignored){}
            code=status==PackageInstaller.STATUS_SUCCESS?"success":status==PackageInstaller.STATUS_FAILURE_ABORTED?"cancelled":"install";
        }
        notifyListener(code);
    }
    private static void notifyListener(String code){Listener value=listener.get();if(value!=null)value.event(code);}
    private static int flags(){return Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;}
    @SuppressWarnings("deprecation")
    private static AppUpdateRelease.Apk identity(PackageInfo info)throws AppUpdateRelease.Failure{
        if(info==null)throw new AppUpdateRelease.Failure("package");
        Signature[] signatures=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        List<String> signers=new ArrayList<>();
        try{if(signatures!=null)for(Signature signature:signatures)signers.add(AppUpdateDownload.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));}
        catch(Exception error){throw new AppUpdateRelease.Failure("signature");}
        return new AppUpdateRelease.Apk(info.packageName,info.versionName,Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode,signers);
    }
}
