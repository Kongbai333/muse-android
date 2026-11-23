package com.muse.workspace.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** App-scoped public updater UI. Neither checks nor downloads run once per Muse account. */
final class AppUpdateUi implements AppUpdateInstaller.Listener {
    interface Host { boolean canOffer();boolean beforeInstall();void notice(String message); }
    private static final int UNKNOWN_SOURCE=630;
    private static final AtomicBoolean WORKING=new AtomicBoolean();
    private static final long AUTO_INTERVAL=6*60*60_000L,FAIL_INTERVAL=30*60_000L;
    private final Activity activity;
    private final Host host;
    private final SharedPreferences preferences;
    private final AppUpdateInstaller installer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"MuseAppUpdate");thread.setDaemon(true);return thread;});
    private final List<AlertDialog> dialogs=new ArrayList<>();
    private final Runnable startup=()->check(false);
    private boolean foreground,closed;
    private Op operation;
    private AlertDialog offer;
    private static final class Op {
        final AtomicBoolean cancelled=new AtomicBoolean(),cleanupQueued=new AtomicBoolean(),finished=new AtomicBoolean();
        final AppUpdateHttp http=new AppUpdateHttp();
        String phase="check";
        volatile int session=-1;
        AppUpdateRelease release;
        AppUpdateDownload.Verified verified;
        AlertDialog progress;
        TextView status;
        ProgressBar bar;
    }
    AppUpdateUi(Activity activity,Host host){this.activity=activity;this.host=host;preferences=activity.getSharedPreferences("muse-app-updates",Context.MODE_PRIVATE);installer=new AppUpdateInstaller(activity);}

    void resume(){if(closed)return;foreground=true;installer.attach(this);if(operation!=null&&operation.phase.equals("permission_ready"))permissionOrStage(operation);else launchConfirmation();main.removeCallbacks(startup);main.postDelayed(startup,1800);}
    void pause(){foreground=false;main.removeCallbacks(startup);Op op=operation;if(op!=null&&(op.phase.equals("check")||op.phase.equals("download")))cancel(op,null);}
    void close(){if(closed)return;closed=true;foreground=false;main.removeCallbacks(startup);installer.detach(this);if(operation!=null)cancel(operation,null);for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();dialogs.clear();io.shutdown();}
    void check(boolean manual){
        if(closed||!foreground||activity.isFinishing()||activity.isDestroyed())return;
        if(operation!=null||WORKING.get()){if(manual)host.notice("更新任务正在处理，请稍候。");return;}
        if(installer.busy()){if(manual)pendingDialog();return;}
        long now=System.currentTimeMillis();
        if(now<preferences.getLong("notBefore",0)){if(manual)host.notice("GitHub 暂时限流，请稍后重试。");return;}
        long sinceAttempt=now-preferences.getLong("attempt",0),sinceSuccess=now-preferences.getLong("success",0);
        if(sinceAttempt>=0&&sinceAttempt<(manual?60000:FAIL_INTERVAL)||!manual&&sinceSuccess>=0&&sinceSuccess<AUTO_INTERVAL){
            AppUpdateRelease cached=cached();if(cached!=null)showOffer(cached,manual,true);else if(manual)host.notice("刚检查过更新，请稍后再试。");return;
        }
        Op op=begin();if(op==null)return;preferences.edit().putLong("attempt",now).apply();if(manual)progress(op,"正在检查官方稳定版本…",false);
        io.execute(()->{
            AppUpdateRelease release=null;AppUpdateRelease.Failure failure=null;
            try{
                Map<String,Object> repo=map(new JSONObject(new String(op.http.metadata(AppUpdateHttp.API,AppUpdateRelease.MAX_METADATA_BYTES),StandardCharsets.UTF_8)),0);
                Map<String,Object> response=map(new JSONObject(new String(op.http.metadata(AppUpdateHttp.API+"/releases/latest",AppUpdateRelease.MAX_METADATA_BYTES),StandardCharsets.UTF_8)),0);
                release=AppUpdateRelease.choose(repo,response,installer.current().version());
            }catch(AppUpdateRelease.Failure error){failure=error;}catch(Exception error){failure=new AppUpdateRelease.Failure("metadata");}
            AppUpdateRelease result=release;AppUpdateRelease.Failure error=failure;
            main.post(()->{
                if(!current(op))return;
                if(error!=null){if(error.code.equals("rate"))preferences.edit().putLong("notBefore",System.currentTimeMillis()+Math.max(60000,Math.min(21600000,error.retryAfterMs))).apply();end(op);if(manual)host.notice(message(error.code));return;}
                preferences.edit().putLong("success",System.currentTimeMillis()).putString("release",result==null?"":new JSONObject(result.cacheRows()).toString()).apply();
                end(op);if(result!=null)showOffer(result,manual,false);else if(manual)host.notice("未发现比当前版本更新的官方稳定版。");
            });
        });
    }
    private AppUpdateRelease cached(){
        try{
            String raw=preferences.getString("release","");if(raw==null||raw.isEmpty()||raw.length()>AppUpdateRelease.MAX_METADATA_BYTES)return null;
            Map<String,Object> repo=new LinkedHashMap<>();repo.put("id",AppUpdateRelease.REPO_ID);repo.put("full_name",AppUpdateRelease.REPO);
            return AppUpdateRelease.choose(repo,map(new JSONObject(raw),0),installer.current().version());
        }catch(Exception error){return null;}
    }
    private void showOffer(AppUpdateRelease release,boolean manual,boolean cached){
        if(!foreground||closed||!host.canOffer()||offer!=null)return;
        long now=System.currentTimeMillis();
        if(!manual&&release.tag.equals(preferences.getString("laterTag",""))&&now<preferences.getLong("laterUntil",0))return;
        MobileUi ui=new MobileUi(activity);LinearLayout content=ui.column();int pad=ui.dp(22);content.setPadding(pad,ui.dp(10),pad,ui.dp(6));
        String text=(cached?"上次检查发现官方稳定版本 ":"发现官方稳定版本 ")+release.version+"\n"+String.format(Locale.US,"下载大小约 %.1f MiB",release.apk.size()/1048576d)
            +"\n下载后将核对校验和、包名、版本和签名，再由系统确认安装；保留本机账号与数据。离开应用会取消本次下载，可重新准备。\n\n"+(release.notes.isEmpty()?"该版本未附说明。":release.notes);
        content.addView(ui.text(text,13,MobileUi.TEXT2,false));ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        AlertDialog dialog=MobileUi.dialog(activity).setTitle("Muse Android "+release.version).setView(scroll)
            .setNegativeButton("稍后",(d,w)->preferences.edit().putString("laterTag",release.tag).putLong("laterUntil",System.currentTimeMillis()+AUTO_INTERVAL).apply())
            .setPositiveButton("更新",(d,w)->download(release)).create();
        offer=dialog;track(dialog);dialog.setOnDismissListener(ignored->{dialogs.remove(dialog);if(offer==dialog)offer=null;});dialog.show();
    }
    private void download(AppUpdateRelease release){
        if(closed||!foreground||!host.canOffer())return;
        if(installer.busy()){pendingDialog();return;}
        Op op=begin();if(op==null){host.notice("更新任务正在结束，请稍后重试。");return;}
        op.phase="download";op.release=release;progress(op,"正在下载，进度 0%…",true);
        io.execute(()->{
            try{
                long[] last={0};AppUpdateDownload.Verified verified=AppUpdateDownload.run(release,AppUpdateInstaller.directory(activity),op.http,op.cancelled,(done,total)->{
                    long now=SystemClock.elapsedRealtime();if(done!=total&&now-last[0]<150)return;last[0]=now;
                    main.post(()->{if(current(op)&&op.bar!=null){op.bar.setProgress((int)(done*1000/total));op.status.setText("下载进度 "+done*100/total+"% · "+done/1024+" / "+total/1024+" KiB");}});
                });
                main.post(()->{if(!current(op))return;op.verified=verified;permissionOrStage(op);});
            }catch(AppUpdateRelease.Failure error){main.post(()->{if(current(op))cancel(op,message(error.code));});}
            catch(Exception error){main.post(()->{if(current(op))cancel(op,message("download"));});}
        });
    }
    private void permissionOrStage(Op op){
        if(!current(op))return;
        if(!foreground||!host.canOffer()){cancel(op,"请完成当前任务后重新准备更新。");return;}
        if(!installer.permitted()){
            op.phase="permission";hideProgress(op);
            AlertDialog dialog=MobileUi.dialog(activity).setTitle("允许本应用安装更新")
                .setMessage("需要由系统允许此来源。你可以取消；授权后仍会由系统确认安装，不会卸载或清除原数据。")
                .setNegativeButton("取消",(d,w)->cancel(op,null)).setPositiveButton("打开系统设置",(d,w)->{
                    try{activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())),UNKNOWN_SOURCE);}
                    catch(RuntimeException error){cancel(op,message("permission"));}
                }).create();track(dialog);dialog.setOnCancelListener(ignored->cancel(op,null));dialog.show();return;
        }
        stage(op);
    }
    boolean onActivityResult(int request,int result,Intent data){
        if(request!=UNKNOWN_SOURCE)return false;
        Op op=operation;if(op==null||!op.phase.equals("permission"))return true;
        if(installer.permitted()){op.phase="permission_ready";if(foreground)permissionOrStage(op);}else cancel(op,"尚未允许安装更新，原应用和数据保持不变。");return true;
    }
    private void stage(Op op){
        try{if(!host.beforeInstall()){cancel(op,null);return;}}catch(RuntimeException error){cancel(op,message("storage"));return;}
        op.phase="stage";progress(op,"正在复核并准备系统安装…",false);
        io.execute(()->{
            try{
                int session=installer.stage(op.verified,op.release,op.cancelled);op.session=session;
                main.post(()->{
                    if(!current(op))return;
                    if(!foreground||!host.canOffer()){cancel(op,"请回到应用后重新准备安装。");return;}
                    try{op.phase="install";installer.commit(session);end(op);host.notice("已交给系统确认安装，可在系统界面取消。");launchConfirmation();}
                    catch(AppUpdateRelease.Failure error){cancel(op,message(error.code));}
                });
            }catch(AppUpdateRelease.Failure error){main.post(()->{if(current(op))cancel(op,message(error.code));});}
        });
    }
    @Override public void event(String code){
        if(closed)return;
        if(code.equals("confirm")){launchConfirmation();return;}
        if(foreground)host.notice(code.equals("success")?"更新已安装，请重新打开应用。":code.equals("cancelled")?"已取消系统安装，原应用和数据保持不变。":message("install"));
    }
    private void launchConfirmation(){
        if(closed||!foreground||!host.canOffer())return;Intent action=installer.confirmation();if(action==null)return;
        try{activity.startActivity(action);installer.confirmationLaunched();}
        catch(RuntimeException error){host.notice("无法打开系统安装确认，请在检查更新中取消后重试。");}
    }
    private void pendingDialog(){
        if(!foreground||closed)return;
        if(installer.confirmation()!=null){launchConfirmation();return;}
        AlertDialog dialog=MobileUi.dialog(activity).setTitle("发现未完成的更新准备")
            .setMessage("上次安装准备尚未结束。若系统确认未出现或准备已中断，可取消本次准备后重新检查；不会清除原应用数据。")
            .setNegativeButton("稍后",null).setPositiveButton("取消本次准备",(d,w)->{try{installer.cancelPending();host.notice("本次安装准备已取消，可重新检查更新。");}catch(AppUpdateRelease.Failure error){host.notice(message(error.code));}}).create();track(dialog);dialog.show();
    }
    private Op begin(){if(closed||operation!=null||!WORKING.compareAndSet(false,true))return null;Op op=new Op();operation=op;return op;}
    private boolean current(Op op){return !closed&&operation==op&&!op.cancelled.get();}
    private void end(Op op){
        if(!op.finished.compareAndSet(false,true))return;
        hideProgress(op);if(operation==op)operation=null;WORKING.set(false);
    }
    private void cancel(Op op,String notice){
        if(operation!=op||op.finished.get()||!op.cleanupQueued.compareAndSet(false,true))return;
        op.cancelled.set(true);op.http.cancel();hideProgress(op);if(notice!=null&&!closed)host.notice(notice);
        io.execute(()->{try{if(op.session>=0)installer.cancelPending();else AppUpdateDownload.clean(AppUpdateInstaller.directory(activity));}catch(AppUpdateRelease.Failure ignored){}main.post(()->end(op));});
    }
    private void progress(Op op,String text,boolean determinate){
        hideProgress(op);MobileUi ui=new MobileUi(activity);LinearLayout view=ui.column();int pad=ui.dp(22);view.setPadding(pad,ui.dp(12),pad,ui.dp(8));
        op.status=ui.text(text,13,MobileUi.TEXT2,false);view.addView(op.status);op.bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);op.bar.setIndeterminate(!determinate);op.bar.setMax(1000);ui.block(view,op.bar,12);
        op.progress=MobileUi.dialog(activity).setTitle("应用更新").setView(view).setNegativeButton("取消",(d,w)->cancel(op,null)).create();op.progress.setCanceledOnTouchOutside(false);op.progress.setOnCancelListener(ignored->cancel(op,null));track(op.progress);op.progress.show();
    }
    private void hideProgress(Op op){if(op.progress!=null){AlertDialog dialog=op.progress;op.progress=null;dialog.dismiss();dialogs.remove(dialog);}}
    private void track(AlertDialog dialog){dialogs.add(dialog);dialog.setOnDismissListener(ignored->dialogs.remove(dialog));}
    private static Map<String,Object> map(JSONObject object,int depth)throws AppUpdateRelease.Failure{
        if(depth>8)throw new AppUpdateRelease.Failure("metadata");Map<String,Object> result=new LinkedHashMap<>();Iterator<String> keys=object.keys();int count=0;
        while(keys.hasNext()){if(++count>1000)throw new AppUpdateRelease.Failure("metadata");String key=keys.next();result.put(key,value(object.opt(key),depth+1));}return result;
    }
    private static Object value(Object raw,int depth)throws AppUpdateRelease.Failure{
        if(depth>8)throw new AppUpdateRelease.Failure("metadata");if(raw==JSONObject.NULL)return null;if(raw instanceof JSONObject object)return map(object,depth);
        if(raw instanceof JSONArray array){if(array.length()>1000)throw new AppUpdateRelease.Failure("metadata");List<Object> result=new ArrayList<>();for(int i=0;i<array.length();i++)result.add(value(array.opt(i),depth+1));return result;}
        return raw;
    }
    private static String message(String code){return switch(code){
        case "signature"->"更新包签名与本机安装不一致，已停止；请勿卸载或清除数据来绕过。";
        case "package","version"->"更新包的应用或版本不匹配，已停止安装。";
        case "system"->"该更新包不适用于当前 Android 系统，已停止安装。";
        case "checksum","size"->"更新包不完整或校验失败，已丢弃，请重新下载。";
        case "source","assets","metadata"->"官方稳定版本信息或适配资产不完整，请稍后重试。";
        case "rate"->"GitHub 暂时限流，请稍后重试。";
        case "permission"->"系统未允许本应用安装更新，原应用和数据保持不变。";
        case "install_busy"->"系统安装尚未结束，请先处理系统确认。";
        case "install"->"系统未完成安装，原应用仍可使用；可检查更新后重试。";
        case "cancelled"->"更新已取消。";
        case "no_release"->"目前未找到可下载的官方稳定版本。";
        default->"更新未完成，请检查 GitHub 网络或存储空间后重试。";
    };}
}
