package com.muse.workspace.mobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.webkit.CookieManager;
import androidx.appcompat.app.AlertDialog;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-invoked local-file flow. No password state, plaintext files, telemetry or remote checks. */
final class AccountTransferUi {
    interface Host {
        boolean allowed();
        void beforeTransfer();
        void imported();
        void notice(String text);
    }
    private static final int SAVE_PACKAGE=620, OPEN_PACKAGE=621;
    private final Activity activity;
    private final AccountStore accounts;
    private final ProxyStore proxies;
    private final MuseWeb browser;
    private final boolean profiles;
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"MuseAccountTransfer");thread.setDaemon(true);return thread;});
    private final List<AlertDialog> dialogs=new ArrayList<>();
    private Op operation;
    private boolean closed;

    private static final class Op {
        final AtomicBoolean cancelled=new AtomicBoolean();
        char[] passphrase;
        byte[] encrypted;
        AccountTransferBundle bundle;
        AccountTransferFlow flow;
        AccountTransferCookies.Write write;
        final List<String> createdProfiles=new ArrayList<>();
        AlertDialog progress;
        TextView status;
        boolean locked;
    }
    private static final class ExportRow {
        final String ref,name,proxyRef;
        final Boolean sourceForce;
        final AccountTransferCookies.Source source;
        ExportRow(String ref,String name,String proxyRef,Boolean sourceForce,AccountTransferCookies.Source source){this.ref=ref;this.name=name;this.proxyRef=proxyRef;this.sourceForce=sourceForce;this.source=source;}
    }

    AccountTransferUi(Activity activity,AccountStore accounts,ProxyStore proxies,MuseWeb browser,boolean profiles,Host host){this.activity=activity;this.accounts=accounts;this.proxies=proxies;this.browser=browser;this.profiles=profiles;this.host=host;}

    private boolean available(){
        if(closed||activity.isFinishing()||activity.isDestroyed())return false;
        if(operation!=null){host.notice("请先完成或取消当前账号迁移。");return false;}
        return host.allowed();
    }

    void exportAccounts(){
        if(!available())return;
        List<AccountStore.Account> choices=new ArrayList<>();for(AccountStore.Account a:accounts.all())if(accounts.usable(a,profiles))choices.add(a);
        if(choices.isEmpty()){host.notice("没有可导出的账号。");return;}
        String[] labels=new String[choices.size()];boolean[] picked=new boolean[choices.size()];
        for(int i=0;i<labels.length;i++){labels[i]=choices.get(i).name;picked[i]=choices.get(i)==accounts.current(profiles);}
        AlertDialog select=MobileUi.dialog(activity).setTitle("导出账号与代理")
            .setMultiChoiceItems(labels,picked,(dialog,index,checked)->picked[index]=checked)
            .setNegativeButton("取消",null).setPositiveButton("继续",null).create();
        track(select);select.setOnShowListener(ignored->select.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            List<AccountStore.Account> selected=new ArrayList<>();for(int i=0;i<picked.length;i++)if(picked[i])selected.add(choices.get(i));
            if(selected.isEmpty()||selected.size()>32){host.notice("请选择 1–32 个账号。");return;}
            select.dismiss();passphrase(true,phrase->exportSelected(selected,phrase));
        }));select.show();
    }

    void importAccounts(){
        if(!available())return;
        if(!profileImportSupported())return;
        try{activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_LOCAL_ONLY,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),OPEN_PACKAGE);}
        catch(RuntimeException error){host.notice("无法打开账号包选择器。");}
    }

    private boolean profileImportSupported(){
        try{if(profiles&&WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))return true;}
        catch(RuntimeException ignored){}
        host.notice("当前 WebView 不支持独立账号导入，请更新 Android System WebView 后重试。");return false;
    }

    boolean onActivityResult(int request,int result,Intent data){
        if(request!=SAVE_PACKAGE&&request!=OPEN_PACKAGE)return false;
        if(closed)return true;
        if(request==OPEN_PACKAGE){
            if(result==Activity.RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();passphrase(false,phrase->readPackage(uri,phrase));}
            return true;
        }
        Op op=operation;
        if(op==null||op.encrypted==null){host.notice("迁移操作已结束，请重新导出。");return true;}
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null){cancel(op);return true;}
        Uri destination=data.getData();progress(op,"正在保存加密账号包…");
        io.execute(()->{
            String error=null;byte[] bytes=op.encrypted;
            try(OutputStream output=activity.getContentResolver().openOutputStream(destination,"w")){
                if(output==null||bytes==null)throw new java.io.IOException();
                for(int offset=0;offset<bytes.length;offset+=32768){if(op.cancelled.get())throw new java.io.InterruptedIOException();output.write(bytes,offset,Math.min(32768,bytes.length-offset));}
                output.flush();
            }catch(Exception failure){error="io";}
            String answer=error;main.post(()->{if(current(op))finish(op,answer,false,answer==null?"加密账号包已保存。":null);});
        });return true;
    }

    private void passphrase(boolean exporting,java.util.function.Consumer<char[]> accept){
        if(!available())return;
        MobileUi ui=new MobileUi(activity);LinearLayout content=content(ui);
        content.addView(ui.text(exporting?"账号 Cookie 与代理认证将一起加密。请自行保管迁移密码。仅导出声明的网址范围，部分原始 Cookie 属性可能未知，不含完整浏览器配置。":"请输入导出时设置的迁移密码。密码仅用于此次操作。",13,MobileUi.TEXT2,false));
        EditText first=secret(ui,exporting?"设置迁移密码":"迁移密码");content.addView(ui.field(first,"迁移密码"));
        EditText second=exporting?secret(ui,"再次输入迁移密码"):null;if(second!=null)content.addView(ui.field(second,"确认密码"));
        AlertDialog dialog=MobileUi.dialog(activity).setTitle(exporting?"设置账号包密码":"解密账号包").setView(content)
            .setNegativeButton("取消",null).setPositiveButton(exporting?"加密":"解密",null).create();
        track(dialog);dialog.setOnDismissListener(ignored->{first.getText().clear();if(second!=null)second.getText().clear();dialogs.remove(dialog);});
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            char[] phrase=characters(first),confirm=second==null?null:characters(second);
            try{
                AccountTransferCrypto.validatePassphrase(phrase);
                if(confirm!=null&&!Arrays.equals(phrase,confirm)){first.setError("两次密码不一致。");Arrays.fill(phrase,'\0');return;}
                dialog.dismiss();accept.accept(phrase);
            }catch(AccountTransferCrypto.TransferException failure){Arrays.fill(phrase,'\0');first.setError("密码过短、过长或包含无效字符，请换一个更长的密码。");}
            finally{if(confirm!=null)Arrays.fill(confirm,'\0');}
        }));dialog.show();
    }

    private void exportSelected(List<AccountStore.Account> selected,char[] phrase){
        Op op=begin(phrase);if(op==null)return;
        progress(op,"正在读取本地账号快照…");
        List<ExportRow> rows=new ArrayList<>();List<AccountTransferBundle.Proxy> nodes=new ArrayList<>();Map<String,String> refs=new LinkedHashMap<>();
        try{
            host.beforeTransfer();if(!browser.beginTransfer())throw new AccountTransferCrypto.TransferException("busy");op.locked=true;
            for(AccountStore.Account account:selected){
                String proxyRef=null;
                if(account.hasProxy()){
                    String identity=account.proxyId().isEmpty()?"legacy:"+account.id:account.proxyId();proxyRef=refs.get(identity);
                    if(proxyRef==null){
                        proxyRef="proxy-"+(refs.size()+1);refs.put(identity,proxyRef);
                        ProxySettings settings=account.proxySettings(proxies);if(settings==null)throw new AccountTransferCrypto.TransferException("route");
                        ProxyProfile profile=proxies.find(account.proxyId());nodes.add(new AccountTransferBundle.Proxy(proxyRef,profile==null?"账号代理":profile.name,settings));
                    }
                }
                rows.add(new ExportRow("account-"+(rows.size()+1),account.name,proxyRef,account.transferSourceForceProxy,AccountTransferCookies.source(account,accounts,profiles)));
            }
        }catch(AccountTransferCrypto.TransferException error){finish(op,error.code,false,null);return;}
        catch(Exception error){finish(op,"snapshot",false,null);return;}
        boolean reduced=false;for(ExportRow row:rows)if(!row.source.attributes)reduced=true;
        if(reduced){
            hideProgress(op);
            AlertDialog warning=MobileUi.dialog(activity).setTitle("仅能按名值导出")
                .setMessage("当前 WebView 不支持带属性的 Cookie 读取，部分账号只能按名值降级导出。原有效期、作用域和标志可能未知；文件仍会整体加密，导入端需要确认映射。是否继续？")
                .setNegativeButton("取消",(d,w)->cancel(op)).setPositiveButton("继续导出",(d,w)->{if(current(op)){progress(op,"正在加密本地账号快照…");encryptSnapshot(op,rows,nodes);}}).create();
            track(warning);warning.setOnCancelListener(ignored->cancel(op));warning.show();
        }else encryptSnapshot(op,rows,nodes);
    }

    private void encryptSnapshot(Op op,List<ExportRow> rows,List<AccountTransferBundle.Proxy> nodes){
        if(!current(op))return;
        io.execute(()->{
            byte[] plain=null,encrypted=null;String error=null;
            try{
                List<AccountTransferBundle.Account> accountsOut=new ArrayList<>();
                for(ExportRow row:rows){if(op.cancelled.get())throw new AccountTransferCrypto.TransferException("cancelled");
                    AccountTransferCookies.Snapshot snapshot=AccountTransferCookies.read(row.source);
                    accountsOut.add(new AccountTransferBundle.Account(row.ref,row.name,row.proxyRef==null?"system":"proxy",row.proxyRef,row.proxyRef==null?"platform":"strict",row.proxyRef==null?null:row.sourceForce,snapshot.fidelity,snapshot.coverage,snapshot.cookies));
                }
                plain=new AccountTransferBundle(accountsOut,nodes).toBytes();AccountTransferBundle.parse(plain);
                encrypted=AccountTransferCrypto.encrypt(plain,op.passphrase);
            }catch(AccountTransferCrypto.TransferException failure){error=failure.code;}
            catch(Exception failure){error="snapshot";}
            finally{if(plain!=null)Arrays.fill(plain,(byte)0);wipe(op.passphrase);}
            byte[] result=encrypted;String answer=error;
            main.post(()->{
                if(!current(op)){if(result!=null)Arrays.fill(result,(byte)0);return;}
                if(answer!=null){finish(op,answer,false,null);return;}
                op.encrypted=result;release(op);hideProgress(op);
                try{activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_LOCAL_ONLY,true).putExtra(Intent.EXTRA_TITLE,"Muse-Accounts.muse-account"),SAVE_PACKAGE);}
                catch(RuntimeException failure){finish(op,"io",false,null);}
            });
        });
    }

    private void readPackage(Uri uri,char[] phrase){
        Op op=begin(phrase);if(op==null)return;progress(op,"正在认证并解密账号包…");
        io.execute(()->{
            byte[] encrypted=null,plain=null;AccountTransferBundle result=null;String error=null;
            try(InputStream input=activity.getContentResolver().openInputStream(uri)){
                if(input==null)throw new java.io.IOException();
                encrypted=readBounded(input,op);plain=AccountTransferCrypto.decrypt(encrypted,op.passphrase);result=AccountTransferBundle.parse(plain);
            }catch(AccountTransferCrypto.TransferException failure){error=failure.code;}
            catch(Exception failure){error="io";}
            finally{if(encrypted!=null)Arrays.fill(encrypted,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);wipe(op.passphrase);}
            AccountTransferBundle bundle=result;String answer=error;main.post(()->{if(!current(op))return;if(answer!=null){finish(op,answer,false,null);return;}op.bundle=bundle;hideProgress(op);preview(op);});
        });
    }

    private static byte[] readBounded(InputStream input,Op op)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[32768];
        try{int size,total=0;while((size=input.read(buffer))!=-1){if(op.cancelled.get())throw new AccountTransferCrypto.TransferException("cancelled");total+=size;if(total>AccountTransferCrypto.MAX_FILE_BYTES)throw new AccountTransferCrypto.TransferException("format");output.write(buffer,0,size);}return output.toByteArray();}
        finally{Arrays.fill(buffer,(byte)0);}
    }

    private void preview(Op op){
        if(!current(op))return;MobileUi ui=new MobileUi(activity);LinearLayout content=content(ui);StringBuilder text=new StringBuilder("将新建 ").append(op.bundle.accounts.size()).append(" 个账号和 ").append(op.bundle.proxies.size()).append(" 个代理节点，不覆盖现有数据。\n\n");boolean warning=false;
        Map<String,AccountTransferBundle.Proxy> nodes=new LinkedHashMap<>();for(AccountTransferBundle.Proxy proxy:op.bundle.proxies)nodes.put(proxy.ref,proxy);
        for(AccountTransferBundle.Account account:op.bundle.accounts){
            text.append(account.name).append('\n');
            if(account.mode.equals("system")){text.append("系统网络：不会迁移源设备的网络环境，需要确认本机网络。\n");warning=true;}
            else{
                AccountTransferBundle.Proxy proxy=nodes.get(account.proxyRef);ProxySettings route=proxy.settings;
                text.append(proxy.name).append(" · ").append(route.label()).append(" · ").append(route.host).append(':').append(route.port).append(route.hasAuthentication()?" · 有认证\n":" · 无认证\n");
                if(AccountTransferBundle.deviceRelated(route.host)){text.append("设备相关地址将原样保留，需确认在本机可达，不会自动改写。\n");warning=true;}
                if(account.bypassPolicy.equals("platform")){text.append("来源使用平台旁路规则；本机按现有严格规则处理，本机地址规则可能更严格。\n");warning=true;}
            }
            if(account.fidelity.equals("header")){text.append("名值降级：未含原有效期或完整属性，按来源 HTTPS 主机、根路径、会话 Cookie 导入。\n");warning=true;}
            boolean unknown=false;for(AccountTransferBundle.Cookie cookie:account.cookies)if(cookie.hostOnly==null||cookie.domain==null||cookie.path==null||cookie.httpOnly==null||cookie.sameSite==null||cookie.session==null)unknown=true;
            if(unknown){text.append("有未知 Cookie 属性：不伪造有效期；缺失作用域将限制为来源主机/根路径，未知标志按目标 WebView 解释。\n");warning=true;}
            text.append('\n');
        }
        text.append("本地写入完成不等于网站认可登录；打开后请核对网站中的真实账号和工作区。");
        TextView summary=ui.text(text.toString(),13,MobileUi.TEXT2,false);content.addView(summary);
        CheckBox confirm=new CheckBox(activity);confirm.setText(R.string.account_transfer_confirm);if(warning)content.addView(confirm);
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);boolean needsConfirm=warning;
        AlertDialog dialog=MobileUi.dialog(activity).setTitle("确认新建导入账号").setView(scroll).setNegativeButton("取消",(d,w)->cancel(op)).setPositiveButton("准备并导入",null).create();
        track(dialog);dialog.setOnCancelListener(ignored->cancel(op));dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            if(needsConfirm&&!confirm.isChecked()){confirm.setError("请先确认说明。");return;}
            dialog.dismiss();activate(op);
        }));dialog.show();
    }

    private void activate(Op op){
        if(!current(op)||!profileImportSupported()){finish(op,"capability",false,null);return;}
        progress(op,"正在准备代理与账号隔离…");
        op.flow=new AccountTransferFlow(op.bundle,new AccountTransferFlow.Backend(){
            public void begin()throws Exception{host.beforeTransfer();if(!browser.beginTransfer())throw new AccountTransferCrypto.TransferException("busy");op.locked=true;}
            public String createProxy(AccountTransferBundle.Proxy proxy)throws Exception{return proxies.addImported(proxy.name,proxy.settings);}
            public void route(AccountTransferFlow.Candidate candidate,java.util.function.Consumer<String> done){status(op,"正在配置指定路由…");browser.prepareTransferRoute(AccountStore.candidate(candidate),profiles,done);}
            public void cookies(AccountTransferFlow.Candidate candidate,java.util.function.Consumer<String> done)throws Exception{
                status(op,"正在写入独立账号 Cookie…");
                AccountStore.Account account=AccountStore.candidate(candidate);
                CookieManager manager=AccountTransferCookies.candidate(account);
                op.createdProfiles.add(account.profileName());
                op.write=new AccountTransferCookies.Write(main,io,manager,candidate.source.cookies,done);op.write.start();
            }
            public void commit(List<AccountTransferFlow.Candidate> candidates)throws Exception{
                if(!current(op))throw new AccountTransferCrypto.TransferException("cancelled");List<AccountStore.Account> rows=new ArrayList<>();for(AccountTransferFlow.Candidate candidate:candidates)rows.add(AccountStore.candidate(candidate));accounts.activateImported(rows);
            }
            public void rollback(List<String> created,List<AccountTransferFlow.Candidate> candidates){
                if(op.write!=null)op.write.cancel();browser.close();
                for(String profile:op.createdProfiles){try{if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))ProfileStore.getInstance().deleteProfile(profile);}catch(RuntimeException ignored){}}
                op.createdProfiles.clear();
                for(String id:created){ProxyProfile proxy=proxies.find(id);if(proxy!=null)try{proxies.remove(proxy,accounts);}catch(Exception ignored){}}
            }
            public void end(){release(op);}
        },error->finish(op,error,error==null,error==null?"本地账号已导入，请在网页核对真实身份与工作区。":null));
        op.flow.start();
    }

    void importCookieText(){
        if(!available()||!profileImportSupported())return;
        MobileUi ui=new MobileUi(activity);LinearLayout content=content(ui);
        EditText name=ui.input("导入账号",InputType.TYPE_CLASS_TEXT,128);name.setText("导入账号");
        EditText cookie=ui.input("hatch_sess=…; hatch_vml=…",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE,1024*1024);cookie.setMinLines(3);cookie.setSaveEnabled(false);cookie.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        content.addView(ui.field(name,"账号名称"));content.addView(ui.field(cookie,"单账号 Cookie 请求头（不是完整账号包）"));
        List<ProxyProfile> options=new ArrayList<>(proxies.all());List<String> labels=new ArrayList<>();labels.add("系统网络（需确认）");for(ProxyProfile proxy:options)labels.add(proxy.name+" · "+proxy.endpoint());
        Spinner network=new Spinner(activity);network.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));content.addView(ui.text("请明确选择此账号使用的代理",13,MobileUi.TEXT2,false));content.addView(network);
        AlertDialog dialog=MobileUi.dialog(activity).setTitle("导入 Cookie 文本").setView(content).setNegativeButton("取消",null).setPositiveButton("预览",null).create();
        track(dialog);dialog.setOnDismissListener(ignored->{cookie.getText().clear();dialogs.remove(dialog);});
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            try{
                AccountTransferBundle.Account account=AccountTransferBundle.fromCookieHeader(cookie.getText().toString(),name.getText().toString());
                List<AccountTransferBundle.Proxy> nodes=new ArrayList<>();int chosen=network.getSelectedItemPosition();
                if(chosen>0){ProxyProfile proxy=options.get(chosen-1);ProxySettings settings=proxy.settings();nodes.add(new AccountTransferBundle.Proxy("proxy-1",proxy.name,settings));account=account.withNetwork("proxy-1");}
                AccountTransferBundle bundle=AccountTransferBundle.parse(new AccountTransferBundle(Arrays.asList(account),nodes).toBytes());
                Op op=begin(null);if(op==null)return;op.bundle=bundle;dialog.dismiss();preview(op);
            }catch(Exception error){cookie.setError("无法解析单账号 Cookie 或指定代理，请检查格式和代理配置。");}
        }));dialog.show();
    }

    private Op begin(char[] phrase){if(!available()){wipe(phrase);return null;}Op op=new Op();op.passphrase=phrase;operation=op;return op;}
    private boolean current(Op op){return !closed&&operation==op&&!op.cancelled.get();}
    private void release(Op op){if(op.locked){op.locked=false;browser.endTransfer();}}
    private void finish(Op op,String error,boolean imported,String message){
        if(operation!=op)return;
        op.cancelled.set(true);if(op.write!=null)op.write.cancel();release(op);hideProgress(op);wipe(op.passphrase);
        if(op.encrypted!=null)Arrays.fill(op.encrypted,(byte)0);op.encrypted=null;op.bundle=null;operation=null;
        if(!closed){if(message!=null)host.notice(message);else if(error!=null&&!error.equals("cancelled"))host.notice(message(error));if(imported)host.imported();}
    }
    private void cancel(Op op){
        if(operation!=op)return;op.cancelled.set(true);wipe(op.passphrase);
        if(op.write!=null)op.write.cancel();if(op.flow!=null)op.flow.cancel();
        if(operation==op)finish(op,"cancelled",false,null);
    }
    private void progress(Op op,String text){
        hideProgress(op);MobileUi ui=new MobileUi(activity);LinearLayout content=content(ui),row=ui.row();ProgressBar spinner=new ProgressBar(activity);row.addView(spinner,new LinearLayout.LayoutParams(ui.dp(30),ui.dp(30)));
        op.status=ui.text(text,14,MobileUi.TEXT2,false);op.status.setPadding(ui.dp(12),0,0,0);row.addView(op.status,new LinearLayout.LayoutParams(0,-2,1));content.addView(row);
        op.progress=MobileUi.dialog(activity).setTitle("账号迁移").setView(content).setNegativeButton("取消",(d,w)->cancel(op)).create();
        op.progress.setCanceledOnTouchOutside(false);op.progress.setOnCancelListener(ignored->cancel(op));track(op.progress);op.progress.show();
    }
    private void hideProgress(Op op){if(op.progress!=null){AlertDialog dialog=op.progress;op.progress=null;dialog.dismiss();dialogs.remove(dialog);}}
    private void status(Op op,String value){if(op.status!=null&&current(op))op.status.setText(value);}
    private void track(AlertDialog dialog){dialogs.add(dialog);dialog.setOnDismissListener(ignored->dialogs.remove(dialog));}
    private LinearLayout content(MobileUi ui){LinearLayout content=ui.column();int pad=ui.dp(22);content.setPadding(pad,ui.dp(10),pad,ui.dp(8));return content;}
    private EditText secret(MobileUi ui,String hint){EditText input=ui.input(hint,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,1024);input.setSaveEnabled(false);input.setFreezesText(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);return input;}
    private static char[] characters(EditText field){char[] result=new char[field.length()];for(int i=0;i<result.length;i++)result[i]=field.getText().charAt(i);return result;}
    private static void wipe(char[] chars){if(chars!=null)Arrays.fill(chars,'\0');}
    private static String message(String code){return switch(code){
        case "authentication"->"密码错误或账号包已损坏，未导入账号。";
        case "format","payload"->"账号包格式、版本、字段或大小不受支持，未导入账号。";
        case "capability"->"当前 WebView 不支持所需账号隔离或 Cookie API，请更新后重试。";
        case "password"->"迁移密码过短、过长或包含无效字符。";
        case "expired"->"账号包包含已过期 Cookie，请从已登录设备重新导出。";
        case "unsupported-cookie"->"遇到当前不能保真迁移的 Cookie 属性，未导出账号包。";
        case "empty-cookie"->"没有读取到该账号可迁移的登录 Cookie，请先正常网页登录。";
        case "route","prepare"->"无法恢复指定代理或账号隔离，账号未激活，未改为直连。";
        case "storage"->"无法保存导入账号，账号未激活。";
        case "cookies","cookie-timeout"->"Cookie 写入未完成，账号未激活。";
        case "snapshot","snapshot-changed"->"无法读取一致的本地账号/代理快照，请稍后重试。";
        case "busy"->"当前环境正在处理其他操作，请稍后重试。";
        case "crypto"->"本机加密服务不可用，请更新系统后重试。";
        default->"账号包读写失败，未确认完成；请检查所选文件和存储位置。";
    };}

    boolean busy(){return operation!=null||!dialogs.isEmpty();}
    void close(){closed=true;if(operation!=null)cancel(operation);for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();dialogs.clear();io.shutdownNow();}
}
