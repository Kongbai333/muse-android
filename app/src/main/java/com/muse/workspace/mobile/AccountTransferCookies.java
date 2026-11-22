package com.muse.workspace.mobile;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.webkit.CookieManager;
import androidx.webkit.CookieManagerCompat;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Native boundary. Lookup/creation on UI thread; local reads/flush may run on the IO executor. */
final class AccountTransferCookies {
    static final class Source {
        final CookieManager manager;
        final boolean attributes;
        Source(CookieManager manager,boolean attributes){this.manager=manager;this.attributes=attributes;}
    }
    static final class Snapshot {
        final String fidelity;
        final List<String> coverage;
        final List<AccountTransferBundle.Cookie> cookies;
        Snapshot(String fidelity,List<String> coverage,List<AccountTransferBundle.Cookie> cookies){this.fidelity=fidelity;this.coverage=coverage;this.cookies=cookies;}
    }
    private static final List<String> URLS=Arrays.asList("https://muse.ai/","https://muse.ai/api/session","https://www.muse.ai/","https://www.muse.ai/api/session");

    // Explicit negative feature guards below are verified by the native-boundary checks.
    @SuppressLint("RequiresFeature")
    static Source source(AccountStore.Account account,AccountStore accounts,boolean profiles)throws AccountTransferCrypto.TransferException{
        try{
            CookieManager manager;
            if(profiles){
                if(!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))throw new AccountTransferCrypto.TransferException("capability");
                Profile profile=ProfileStore.getInstance().getProfile(account.profileName());
                if(profile==null)throw new AccountTransferCrypto.TransferException("empty-cookie");
                manager=profile.getCookieManager();
            }else{
                if(!accounts.usable(account,false))throw new AccountTransferCrypto.TransferException("capability");
                manager=CookieManager.getInstance();
            }
            return new Source(manager,WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO));
        }catch(RuntimeException error){throw new AccountTransferCrypto.TransferException("capability");}
    }

    /** No WebView navigation or fetch: reads only the manager already associated with an account. */
    @SuppressLint("RequiresFeature") // Source.attributes is captured by a separate GET_COOKIE_INFO check.
    static Snapshot read(Source source)throws AccountTransferCrypto.TransferException{
        try{
            source.manager.flush();
            if(!source.attributes){
                for(String url:Arrays.asList("https://muse.ai/api/session","https://www.muse.ai/api/session")){
                    String header=source.manager.getCookie(url);if(header==null||header.isEmpty())continue;
                    List<AccountTransferBundle.Cookie> rows=AccountTransferCookieText.header(header,url);
                    boolean essential=false;for(AccountTransferBundle.Cookie c:rows)if(c.name.equals("hatch_sess")&&!c.value.isEmpty())essential=true;
                    if(essential)return new Snapshot("header",Arrays.asList(url),rows);
                }
                throw new AccountTransferCrypto.TransferException("empty-cookie");
            }
            Map<String,AccountTransferBundle.Cookie> found=new LinkedHashMap<>();
            for(String url:URLS){
                for(String line:CookieManagerCompat.getCookieInfo(source.manager,url)){
                    AccountTransferBundle.Cookie cookie=AccountTransferCookieText.fromSetCookie(line,url);
                    String scope=cookie.domain==null?java.net.URI.create(url).getHost():cookie.domain;
                    String key=scope+"\n"+cookie.path+"\n"+cookie.name;
                    AccountTransferBundle.Cookie old=found.get(key);
                    if(old!=null&&!old.value.equals(cookie.value))throw new AccountTransferCrypto.TransferException("snapshot-changed");
                    if(old==null)found.put(key,cookie);
                    if(found.size()>256)throw AccountTransferJson.invalid();
                }
            }
            boolean essential=false;
            for(AccountTransferBundle.Cookie cookie:found.values())if(cookie.name.equals("hatch_sess")&&!cookie.value.isEmpty())essential=true;
            if(!essential)throw new AccountTransferCrypto.TransferException("empty-cookie");
            return new Snapshot("attributes",new ArrayList<>(URLS),new ArrayList<>(found.values()));
        }catch(RuntimeException error){throw new AccountTransferCrypto.TransferException("cookies");}
    }

    @SuppressLint("RequiresFeature") // Negative MULTI_PROFILE guard rejects before any profile lookup.
    static CookieManager candidate(AccountStore.Account candidate)throws AccountTransferCrypto.TransferException{
        try{
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))throw new AccountTransferCrypto.TransferException("capability");
            if(ProfileStore.getInstance().getProfile(candidate.profileName())!=null)throw new AccountTransferCrypto.TransferException("prepare");
            return ProfileStore.getInstance().getOrCreateProfile(candidate.profileName()).getCookieManager();
        }catch(RuntimeException error){throw new AccountTransferCrypto.TransferException("capability");}
    }

    static final class Write {
        private final Handler main;
        private final Executor io;
        private final CookieManager manager;
        private final List<AccountTransferBundle.Cookie> cookies;
        private final Consumer<String> done;
        private final AtomicBoolean cancelled=new AtomicBoolean();
        private boolean settled;
        private int index;
        private Runnable timeout;
        Write(Handler main,Executor io,CookieManager manager,List<AccountTransferBundle.Cookie> cookies,Consumer<String> done){this.main=main;this.io=io;this.manager=manager;this.cookies=cookies;this.done=done;}
        void start(){next();}
        void cancel(){cancelled.set(true);if(timeout!=null)main.removeCallbacks(timeout);}
        private void finish(String error){if(settled||cancelled.get())return;settled=true;if(timeout!=null)main.removeCallbacks(timeout);done.accept(error);}
        private void next(){
            if(settled||cancelled.get())return;
            if(index>=cookies.size()){
                try{io.execute(()->{String error=null;try{manager.flush();}catch(RuntimeException failure){error="cookies";}String answer=error;main.post(()->finish(answer));});}
                catch(RuntimeException error){finish("cookies");}return;
            }
            AccountTransferBundle.Cookie cookie=cookies.get(index++);
            final String value;try{value=cookie.setCookie(System.currentTimeMillis()/1000);}catch(AccountTransferCrypto.TransferException error){finish(error.code);return;}
            AtomicBoolean answered=new AtomicBoolean();
            Runnable requestTimeout=()->{if(answered.compareAndSet(false,true))finish("cookie-timeout");};
            timeout=requestTimeout;main.postDelayed(requestTimeout,30000);
            try{manager.setCookie(cookie.sourceUrl,value,success->{
                if(settled||cancelled.get()||!answered.compareAndSet(false,true))return;main.removeCallbacks(requestTimeout);
                if(!Boolean.TRUE.equals(success)){finish("cookies");return;}
                // The callback confirms local setting only. Do not report server identity here.
                next();
            });}catch(RuntimeException error){answered.set(true);main.removeCallbacks(requestTimeout);finish("cookies");}
        }
    }
}
