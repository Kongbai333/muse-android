package com.muse.workspace.mobile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual policy, public-shaped synthetic metadata and signer digests only. */
public final class AppUpdateReleaseCheck {
    private static int count;
    public static void main(String[] args)throws Exception{
        require(AppUpdateRelease.newer("0.1.10","0.1.9"),"numeric version ordering");
        require(!AppUpdateRelease.newer("0.1.9","0.1.10"),"no lexicographic downgrade");
        AppUpdateRelease good=AppUpdateRelease.choose(repo(),release(),"0.1.27");
        require(good!=null&&good.version.equals("0.1.28")&&good.apk.name().endsWith("-preview.apk"),"formal preview variant selected");
        require(AppUpdateRelease.choose(repo(),release(),"0.1.28")==null,"same version ignored");
        for(String field:new String[]{"draft","prerelease"}){Map<String,Object> r=release();r.put(field,true);require(AppUpdateRelease.choose(repo(),r,"0.1.27")==null,"stable only "+field);}
        reject(()->AppUpdateRelease.choose(map("id",1L,"full_name","Kongbai333/muse-android"),release(),"0.1.27"),"wrong immutable repo id");
        for(String tag:new String[]{"v0.1.28-beta","v0.1.28/../evil","v99999999999.0.0"}){Map<String,Object> r=release();r.put("tag_name",tag);reject(()->AppUpdateRelease.choose(repo(),r,"0.1.27"),"bad stable tag");}
        for(String url:new String[]{"http://github.com/a","https://github.com.evil.invalid/a","https://x@github.com/a","https://github.com:444/a","https://github.com/a#fragment","https://muse.ai/a"})reject(()->AppUpdateRelease.allowedUrl(url),"wrong source/transport");
        require(AppUpdateRelease.allowedUrl("https://release-assets.githubusercontent.com/synthetic?token=PUBLIC_SYNTHETIC").getHost().equals("release-assets.githubusercontent.com"),"known asset CDN allowed without logging query");
        for(String value:new String[]{"https://github.com/Kongbai333/muse-desktop/releases/download/v0.1.28/a.apk","https://github.com/Kongbai333/muse-android/releases/download/v0.1.28/../a.apk"}){
            Map<String,Object> r=release();asset(r,0).put("browser_download_url",value);reject(()->AppUpdateRelease.choose(repo(),r,"0.1.27"),"asset path fixed to this release");
        }
        Map<String,Object> missing=release();list(missing.get("assets")).remove(1);reject(()->AppUpdateRelease.choose(repo(),missing,"0.1.27"),"checksum required");
        Map<String,Object> wrong=release();asset(wrong,0).put("name","Muse-Desktop-0.1.28-win-x64.zip");reject(()->AppUpdateRelease.choose(repo(),wrong,"0.1.27"),"wrong platform rejected");
        Map<String,Object> huge=release();asset(huge,0).put("size",200000000L);reject(()->AppUpdateRelease.choose(repo(),huge,"0.1.27"),"download size limit");
        Map<String,Object> duplicate=release();list(duplicate.get("assets")).add(new LinkedHashMap<>(asset(duplicate,0)));reject(()->AppUpdateRelease.choose(repo(),duplicate,"0.1.27"),"ambiguous duplicate asset");
        String hash="a".repeat(64);require(AppUpdateRelease.checksum((hash+"  "+good.apk.name()+"\n").getBytes(StandardCharsets.US_ASCII),good.apk.name()).equals(hash),"strict checksum filename/hash");
        reject(()->AppUpdateRelease.checksum((hash+"  ../other.apk\n").getBytes(StandardCharsets.US_ASCII),good.apk.name()),"checksum traversal/mismatch");
        AppUpdateRelease.Apk installed=new AppUpdateRelease.Apk("com.muse.workspace.mobile","0.1.27",29,Arrays.asList(hash));
        AppUpdateRelease.Apk candidate=new AppUpdateRelease.Apk("com.muse.workspace.mobile","0.1.28",30,Arrays.asList(hash));
        AppUpdateRelease.validateIdentity(installed,candidate,good);require(true,"higher code same signer");
        reject(()->AppUpdateRelease.validateIdentity(installed,new AppUpdateRelease.Apk("wrong.package","0.1.28",30,Arrays.asList(hash)),good),"package mismatch");
        reject(()->AppUpdateRelease.validateIdentity(installed,new AppUpdateRelease.Apk(candidate.packageName(),"0.1.28",29,Arrays.asList(hash)),good),"versionCode downgrade/equality");
        reject(()->AppUpdateRelease.validateIdentity(installed,new AppUpdateRelease.Apk(candidate.packageName(),"0.1.28",30,Arrays.asList("b".repeat(64))),good),"signer mismatch");
        reject(()->AppUpdateRelease.validateIdentity(installed,new AppUpdateRelease.Apk(candidate.packageName(),"0.1.29",31,Arrays.asList(hash)),good),"APK version differs from selected release");
        reject(()->AppUpdateRelease.validateIdentity(installed,new AppUpdateRelease.Apk(candidate.packageName(),"0.1.28",30,new ArrayList<>()),good),"missing signature fails closed");
        System.out.println("App update release/identity policy: "+count+" checks passed; synthetic metadata, no network/device.");
    }
    static Map<String,Object> repo(){return map("id",1467211251L,"full_name","Kongbai333/muse-android");}
    static Map<String,Object> release(){
        String prefix="https://github.com/Kongbai333/muse-android/releases/download/v0.1.28/",apk="Muse-Android-0.1.28-preview.apk";
        return map("tag_name","v0.1.28","draft",false,"prerelease",false,"body","SYNTHETIC release notes","html_url","https://github.com/Kongbai333/muse-android/releases/tag/v0.1.28","assets",new ArrayList<>(Arrays.asList(
            map("name",apk,"browser_download_url",prefix+apk,"size",4L,"digest",null,"state","uploaded"),
            map("name",apk+".sha256","browser_download_url",prefix+apk+".sha256","size",98L,"digest",null,"state","uploaded"))));
    }
    static Map<String,Object> map(Object... v){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
    @SuppressWarnings("unchecked") static List<Object> list(Object v){return (List<Object>)v;}
    @SuppressWarnings("unchecked") static Map<String,Object> asset(Map<String,Object> r,int index){return (Map<String,Object>)list(r.get("assets")).get(index);}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action,String reason)throws Exception{try{action.run();}catch(AppUpdateRelease.Failure expected){count++;return;}throw new AssertionError(reason);}
    private static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);count++;}
}
