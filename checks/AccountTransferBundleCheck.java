package com.muse.workspace.mobile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exercises the real strict wire/data code, never an Android profile or a network. */
public final class AccountTransferBundleCheck {
    private static int checks;
    private static byte[] fixture;
    public static void main(String[] args) throws Exception {
        fixture = Files.readAllBytes(Path.of("checks/fixtures/account-transfer-v1/payload.json"));
        AccountTransferBundle bundle = AccountTransferBundle.parse(fixture);
        require(bundle.accounts.size()==1 && bundle.proxies.size()==1,"fixture collections");
        require(bundle.proxies.get(0).settings.username.equals("SYNTHETIC_USER_raw+%3D="),"opaque proxy auth");
        require(bundle.accounts.get(0).cookies.get(0).value.equals("SYNTHETIC_SESSION==%2F%3D%25+ONLY"),"raw Cookie value");
        Map<String,Object> opaque=raw();String rawUser=" SYNTHETIC_user+%3D= ",rawSecret=" SYNTHETIC:secret==%2B+ ";auth(opaque).put("username",rawUser);auth(opaque).put("password",rawSecret);
        ProxySettings rawSettings=AccountTransferBundle.parse(AccountTransferJson.bytes(opaque)).proxies.get(0).settings;
        require(rawSettings.username.equals(rawUser)&&rawSettings.secret.equals(rawSecret),"opaque auth spaces/colon/percent/equal/plus preserved");
        require(AccountTransferBundle.parse(bundle.toBytes()).accounts.get(0).name.equals("合成账号 A"),"Unicode model roundtrip");
        reject(raw -> raw.put("unknown",true),"unknown root");
        reject(raw -> account(raw).put("unknown",true),"unknown account");
        reject(raw -> network(raw).put("proxy_ref","missing"),"dangling proxy");
        reject(raw -> network(raw).put("mode","direct-on-error"),"unknown network mode");
        reject(raw -> proxy(raw).put("protocol","ftp"),"unsupported proxy");
        reject(raw -> proxy(raw).put("port",70000L),"bad port");
        reject(raw -> auth(raw).put("username","bad:username"),"ambiguous auth separator");
        reject(raw -> auth(raw).put("password","SYNTHETIC\nONLY"),"auth control");
        reject(raw -> cookie(raw).put("domain",".example.invalid"),"unrelated cookie domain");
        reject(raw -> cookie(raw).put("source_url","https://muse.ai.evil.invalid/"),"lookalike origin");
        reject(raw -> cookie(raw).put("value","SYNTHETIC; HttpOnly"),"Cookie attribute injection");
        reject(raw -> cookie(raw).put("path","/\r\nInjected"),"path injection");
        reject(raw -> cookie(raw).put("expires_at_unix_seconds",1893456000000L),"millisecond timestamp");
        reject(raw -> cookie(raw).put("session",true),"session with expiry");
        reject(raw -> cookie(raw).put("name","hatch_vml"),"essential login credential missing");
        Map<String,Object> shared=raw();
        Map<String,Object> second=copy(account(shared)); second.put("account_ref","account-b");
        list(shared.get("accounts")).add(second);
        AccountTransferBundle sharing=AccountTransferBundle.parse(AccountTransferJson.bytes(shared));
        require(sharing.accounts.size()==2 && sharing.proxies.size()==1,"shared package node retained");
        second.put("account_ref","account-a");
        rejectBytes(AccountTransferJson.bytes(shared),"duplicate account ref");
        for(String invalid:new String[]{"{\"format\":1,\"format\":2}","{} trailing","{\"x\":01}","{\"x\":NaN}","{\"x\":'single'}","{\"x\":\"\\ud800\"}","{\"x\":\"\\u１２３４\"}","[true,]"})
            rejectJson(invalid,"strict JSON syntax/duplicates/Unicode");
        String deep="0";for(int i=0;i<18;i++)deep="["+deep+"]";rejectJson(deep,"depth bound");
        AccountTransferBundle.Account header=AccountTransferBundle.fromCookieHeader("Cookie: hatch_sess=SYNTHETIC==%2F%3D%25+; hatch_vml=SYNTHETIC_VML","合成文本");
        require(header.fidelity.equals("header") && header.cookies.size()==2,"legacy header prefix");
        require(header.cookies.get(0).value.equals("SYNTHETIC==%2F%3D%25+"),"legacy first equals only/no decode");
        require(header.cookies.get(0).expires==null && header.cookies.get(0).httpOnly==null,"unknown metadata not invented");
        String line=header.cookies.get(0).setCookie(1700000000L);
        require(!line.contains("Expires") && !line.contains("Domain="),"legacy narrow session target");
        AccountTransferBundle.Cookie info=AccountTransferCookieText.fromSetCookie("hatch_sess=SYNTHETIC==%25; domain=.muse.ai; path=/; expires=Tue, 01 Jan 2030 00:00:00 GMT; secure; httponly; samesite=lax","https://muse.ai/api/session");
        require(Boolean.TRUE.equals(info.httpOnly) && info.expires==1893456000L && info.hostOnly==null,"native attributes/seconds/unknown host-only");
        require(info.setCookie(1700000000L).contains("HttpOnly"),"known HttpOnly retained");
        try{info.setCookie(1900000000L);throw new AssertionError("extended expired Cookie");}catch(AccountTransferCrypto.TransferException good){checks++;}
        try{AccountTransferCookieText.fromSetCookie("hatch_sess=SYNTHETIC; Partitioned","https://muse.ai/");throw new AssertionError("partition silently dropped");}catch(AccountTransferCrypto.TransferException good){checks++;}
        for(String host:new String[]{"localhost","localhost.","::1","0:0:0:0:0:0:0:1","::ffff:127.0.0.1","0.0.0.0","printer","proxy.local","172.16.1.2","fc00::1"})
            require(AccountTransferBundle.deviceRelated(host),"device-address confirmation: "+host);
        for(String host:new String[]{"proxy.example.invalid","203.0.113.10","2001:db8::1"})require(!AccountTransferBundle.deviceRelated(host),"ordinary address classification");
        System.out.println("Account transfer schema/Cookie: "+checks+" checks passed; synthetic only.");
    }
    private interface Change{void apply(Map<String,Object> root)throws Exception;}
    private static void reject(Change change,String name)throws Exception{Map<String,Object> root=raw();change.apply(root);rejectBytes(AccountTransferJson.bytes(root),name);}
    private static void rejectBytes(byte[] bytes,String name)throws Exception{try{AccountTransferBundle.parse(bytes);}catch(AccountTransferCrypto.TransferException expected){checks++;return;}throw new AssertionError("Accepted "+name);}
    private static void rejectJson(String value,String name)throws Exception{try{AccountTransferJson.parse(value.getBytes(StandardCharsets.UTF_8));}catch(AccountTransferCrypto.TransferException expected){checks++;return;}throw new AssertionError("Accepted "+name);}
    private static void require(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    @SuppressWarnings("unchecked") private static Map<String,Object> raw()throws Exception{return (Map<String,Object>)AccountTransferJson.parse(fixture);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return (List<Object>)value;}
    private static Map<String,Object> account(Map<String,Object> root){return object(list(root.get("accounts")).get(0));}
    private static Map<String,Object> network(Map<String,Object> root){return object(account(root).get("network"));}
    private static Map<String,Object> proxy(Map<String,Object> root){return object(list(root.get("proxies")).get(0));}
    private static Map<String,Object> auth(Map<String,Object> root){return object(proxy(root).get("auth"));}
    private static Map<String,Object> cookie(Map<String,Object> root){return object(list(object(account(root).get("cookies")).get("items")).get(0));}
    @SuppressWarnings("unchecked") private static Map<String,Object> copy(Object value)throws Exception{return (Map<String,Object>)AccountTransferJson.parse(AccountTransferJson.bytes(value));}
}
