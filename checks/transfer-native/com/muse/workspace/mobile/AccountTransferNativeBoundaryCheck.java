package com.muse.workspace.mobile;

import android.os.Handler;
import android.webkit.CookieManager;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs the actual Cookie adapter against fake OS boundaries, NOT an Android device smoke. */
public final class AccountTransferNativeBoundaryCheck {
    private static int checks;
    public static void main(String[] args)throws Exception{
        WebViewFeature.supported.add(WebViewFeature.MULTI_PROFILE);WebViewFeature.supported.add(WebViewFeature.GET_COOKIE_INFO);
        AccountStore accounts=new AccountStore();AccountStore.Account owner=new AccountStore.Account("synthetic-owner");
        reject(()->AccountTransferCookies.source(owner,accounts,true),"missing profile must not fall back");
        require(CookieManager.defaultReads==0&&ProfileStore.getInstance().creates==0,"export creates no wrong/default profile");
        Profile profile=ProfileStore.getInstance().getOrCreateProfile(owner.profileName());
        reject(()->AccountTransferCookies.candidate(owner),"candidate must not reuse an existing profile");
        AccountStore.Account fresh=new AccountStore.Account("synthetic-fresh");
        require(AccountTransferCookies.candidate(fresh)==ProfileStore.getInstance().getProfile(fresh.profileName()).manager,"new candidate owns a fresh isolated manager");
        AccountTransferCookies.Source source=AccountTransferCookies.source(owner,accounts,true);
        require(source.manager==profile.manager&&source.attributes,"correct profile and metadata capability");
        String line="hatch_sess=SYNTHETIC==%2F%3D%25+; domain=.muse.ai; path=/; secure; httponly";
        profile.manager.info.put("https://muse.ai/",Arrays.asList(line));profile.manager.info.put("https://muse.ai/api/session",Arrays.asList(line));
        AccountTransferCookies.Snapshot data=AccountTransferCookies.read(source);
        require(data.cookies.size()==1&&Boolean.TRUE.equals(data.cookies.get(0).httpOnly),"native attribute snapshot keeps HttpOnly and scope dedup");
        profile.manager.info.put("https://muse.ai/",Arrays.asList("tracking=SYNTHETIC; path=/"));profile.manager.info.put("https://muse.ai/api/session",Arrays.asList("tracking=SYNTHETIC; path=/"));
        AccountTransferCookies.Source attributesSource=source;reject(()->AccountTransferCookies.read(attributesSource),"non-login cookies are not a transferable login snapshot");
        WebViewFeature.supported.remove(WebViewFeature.GET_COOKIE_INFO);
        source=AccountTransferCookies.source(owner,accounts,true);require(!source.attributes,"independent GET_COOKIE_INFO feature");
        profile.manager.headers.put("https://muse.ai/api/session","hatch_sess=SYNTHETIC==%25+");
        data=AccountTransferCookies.read(source);require(data.fidelity.equals("header")&&data.cookies.get(0).expires==null,"explicit header downgrade without expiry invention");
        WebViewFeature.supported.remove(WebViewFeature.MULTI_PROFILE);
        reject(()->AccountTransferCookies.source(owner,accounts,true),"lost named profile support");
        reject(()->AccountTransferCookies.source(owner,accounts,false),"not the actual fallback account");
        AccountTransferCookies.source(new AccountStore.Account("fallback"),accounts,false);
        require(CookieManager.defaultReads==1,"only real fallback may read Default");
        List<AccountTransferBundle.Cookie> cookies=AccountTransferCookieText.header("hatch_sess=SYNTHETIC==%25+; hatch_vml=SYNTHETIC_OTHER","https://muse.ai/");
        Handler main=new Handler();CookieManager manager=new CookieManager();List<String> done=new ArrayList<>();
        AccountTransferCookies.Write write=new AccountTransferCookies.Write(main,Runnable::run,manager,cookies,done::add);write.start();
        require(manager.callbacks.size()==1&&done.isEmpty(),"serial Cookie writes wait for callback");
        manager.callbacks.get(0).onReceiveValue(true);require(manager.callbacks.size()==2&&manager.flushes==0,"second Cookie before flush");
        manager.callbacks.get(0).onReceiveValue(true);main.drain();
        require(done.isEmpty()&&manager.flushes==0,"duplicate stale native callback cannot flush or finish early");
        manager.callbacks.get(1).onReceiveValue(true);require(done.isEmpty()&&manager.flushes==1,"flush before main-thread completion");main.drain();
        require(done.size()==1&&done.get(0)==null,"all callbacks and flush completed");
        require(manager.written.get(0).contains("SYNTHETIC==%25+"),"native argument raw value preserved");
        for(String mode:new String[]{"cancel","false","timeout","flush"}){
            Handler h=new Handler();CookieManager cm=new CookieManager();List<String> answers=new ArrayList<>();
            AccountTransferCookies.Write action=new AccountTransferCookies.Write(h,Runnable::run,cm,cookies,answers::add);action.start();
            if(mode.equals("cancel")){action.cancel();cm.callbacks.get(0).onReceiveValue(true);h.timeout();h.drain();require(answers.isEmpty()&&cm.callbacks.size()==1,"cancel stops native continuation");}
            else if(mode.equals("false")){cm.callbacks.get(0).onReceiveValue(false);require(answers.size()==1&&answers.get(0)!=null&&cm.flushes==0,"native false prevents publication");}
            else if(mode.equals("timeout")){h.timeout();cm.callbacks.get(0).onReceiveValue(true);require(answers.size()==1&&answers.get(0)!=null&&cm.callbacks.size()==1,"late timeout callback ignored");}
            else{cm.failFlush=true;cm.callbacks.get(0).onReceiveValue(true);cm.callbacks.get(1).onReceiveValue(true);h.drain();require(answers.size()==1&&answers.get(0)!=null,"flush failure never succeeds");}
        }
        System.out.println("Cookie native-boundary doubles: "+checks+" checks passed; not an Android device or network test.");
    }
    private interface Throwing{void call()throws Exception;}
    private static void reject(Throwing call,String reason)throws Exception{try{call.call();}catch(AccountTransferCrypto.TransferException expected){checks++;return;}throw new AssertionError(reason);}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
}
