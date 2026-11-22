package com.muse.workspace.mobile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Authenticated logical data only; local UUID/vault/profile allocation belongs to the flow. */
final class AccountTransferBundle {
    final List<Account> accounts;
    final List<Proxy> proxies;

    static final class Proxy {
        final String ref, name;
        final ProxySettings settings;
        Proxy(String ref,String name,ProxySettings settings){this.ref=ref;this.name=name;this.settings=settings;}
    }
    static final class Account {
        final String ref,name,mode,proxyRef,bypassPolicy,fidelity;
        final Boolean sourceForceProxy;
        final List<String> coverage;
        final List<Cookie> cookies;
        Account(String ref,String name,String mode,String proxyRef,String bypassPolicy,Boolean sourceForceProxy,String fidelity,List<String> coverage,List<Cookie> cookies){
            this.ref=ref;this.name=name;this.mode=mode;this.proxyRef=proxyRef;this.bypassPolicy=bypassPolicy;this.sourceForceProxy=sourceForceProxy;this.fidelity=fidelity;
            this.coverage=Collections.unmodifiableList(new ArrayList<>(coverage));this.cookies=Collections.unmodifiableList(new ArrayList<>(cookies));
        }
        Account withNetwork(String proxyRef){return new Account(ref,name,proxyRef==null?"system":"proxy",proxyRef,proxyRef==null?"platform":"strict",null,fidelity,coverage,cookies);}
        boolean confirmationNeeded(){return mode.equals("system") || bypassPolicy.equals("platform") || fidelity.equals("header");}
    }
    static final class Cookie {
        final String sourceUrl,name,value,domain,path,sameSite;
        final Long expires;
        final Boolean secure,httpOnly,session,hostOnly;
        Cookie(String sourceUrl,String name,String value,String domain,String path,Long expires,Boolean secure,Boolean httpOnly,String sameSite,Boolean session,Boolean hostOnly){
            this.sourceUrl=sourceUrl;this.name=name;this.value=value;this.domain=domain;this.path=path;this.expires=expires;this.secure=secure;this.httpOnly=httpOnly;this.sameSite=sameSite;this.session=session;this.hostOnly=hostOnly;
        }
        String setCookie(long now) throws AccountTransferCrypto.TransferException {
            if(expires!=null&&expires<=now)throw new AccountTransferCrypto.TransferException("expired");
            StringBuilder text=new StringBuilder(name).append('=').append(value).append("; Path=").append(path==null?"/":path);
            if(domain!=null&&!Boolean.TRUE.equals(hostOnly)&&(Boolean.FALSE.equals(hostOnly)||domain.startsWith(".")))text.append("; Domain=").append(domain);
            if(!Boolean.FALSE.equals(secure))text.append("; Secure");
            if(Boolean.TRUE.equals(httpOnly))text.append("; HttpOnly");
            if(sameSite!=null&&!sameSite.equals("unspecified"))text.append("; SameSite=").append(sameSite);
            if(expires!=null)text.append("; Expires=").append(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US).withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(expires)));
            return text.toString();
        }
    }

    AccountTransferBundle(List<Account> accounts,List<Proxy> proxies){this.accounts=Collections.unmodifiableList(new ArrayList<>(accounts));this.proxies=Collections.unmodifiableList(new ArrayList<>(proxies));}

    static AccountTransferBundle parse(byte[] bytes) throws AccountTransferCrypto.TransferException {
        try {
            Map<String,Object> root=object(AccountTransferJson.parse(bytes));keys(root,"format","version","accounts","proxies");
            if(!string(root,"format").equals("muse-account-payload")||integer(root,"version")!=1)throw invalid();
            List<Object> rawProxies=list(root.get("proxies"),0,32),rawAccounts=list(root.get("accounts"),1,32);
            List<Proxy> proxies=new ArrayList<>();Set<String> proxyRefs=new HashSet<>();
            for(Object value:rawProxies){
                Map<String,Object> item=object(value);keys(item,"proxy_ref","name","protocol","host","port","auth");
                String ref=ref(string(item,"proxy_ref"));if(!proxyRefs.add(ref))throw invalid();
                String protocol=string(item,"protocol");if(!AccountTransferJson.set("http","https","socks5").contains(protocol))throw invalid();
                String username="",secret="";
                if(item.get("auth")!=null){Map<String,Object> auth=object(item.get("auth"));keys(auth,"username","password");username=bounded(string(auth,"username"),1,1024);secret=bounded(string(auth,"password"),0,4096);}
                if(protocol.equals("socks5")&&(utf8(username)>255||utf8(secret)>255))throw invalid();
                long port=integer(item,"port");if(port<1||port>65535)throw invalid();
                String host=bounded(string(item,"host"),1,280);
                if(!host.equals(host.trim())||host.startsWith("[")||host.endsWith("]"))throw invalid();
                proxies.add(new Proxy(ref,name(string(item,"name")),ProxySettings.create(protocol,host,Long.toString(port),username,secret)));
            }
            List<Account> accounts=new ArrayList<>();Set<String> accountRefs=new HashSet<>(),usedProxies=new HashSet<>();
            for(Object value:rawAccounts){
                Map<String,Object> item=object(value);keys(item,"account_ref","name","network","cookies");
                String ref=ref(string(item,"account_ref"));if(!accountRefs.add(ref))throw invalid();
                Map<String,Object> network=object(item.get("network"));keys(network,"mode","proxy_ref","bypass_policy","source_force_proxy");
                String mode=string(network,"mode"),proxy=nullableString(network,"proxy_ref"),policy=string(network,"bypass_policy");Boolean source=nullableBoolean(network,"source_force_proxy");
                if(mode.equals("proxy")){if(proxy==null||!proxyRefs.contains(proxy)||!AccountTransferJson.set("strict","platform").contains(policy))throw invalid();usedProxies.add(proxy);}
                else if(!mode.equals("system")||proxy!=null||!policy.equals("platform")||source!=null)throw invalid();
                Map<String,Object> cookieData=object(item.get("cookies"));keys(cookieData,"fidelity","coverage_urls","items");
                String fidelity=string(cookieData,"fidelity");if(!AccountTransferJson.set("attributes","header").contains(fidelity))throw invalid();
                List<String> coverage=new ArrayList<>();for(Object url:list(cookieData.get("coverage_urls"),1,16)){String checked=trusted(asString(url));if(coverage.contains(checked))throw invalid();coverage.add(checked);}
                List<Cookie> cookies=new ArrayList<>();boolean sessionFound=false;
                for(Object rawCookie:list(cookieData.get("items"),1,256)){
                    Map<String,Object> c=object(rawCookie);keys(c,"source_url","name","value","domain","path","expires_at_unix_seconds","secure","http_only","same_site","session","host_only");
                    String url=trusted(string(c,"source_url")),cookieName=string(c,"name"),cookieValue=bounded(string(c,"value"),0,16384);
                    if(!coverage.contains(url)||!cookieName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}")||cookieValue.indexOf(';')>=0)throw invalid();
                    String domain=nullableString(c,"domain"),path=nullableString(c,"path"),sameSite=nullableString(c,"same_site");
                    Boolean secure=nullableBoolean(c,"secure"),httpOnly=nullableBoolean(c,"http_only"),session=nullableBoolean(c,"session"),hostOnly=nullableBoolean(c,"host_only");
                    Long expires=c.get("expires_at_unix_seconds")==null?null:integer(c,"expires_at_unix_seconds");
                    if(expires!=null&&(expires<1||expires>253402300799L))throw invalid();
                    if(Boolean.TRUE.equals(session)&&expires!=null||Boolean.FALSE.equals(session)&&expires==null)throw invalid();
                    if(sameSite!=null&&!AccountTransferJson.set("unspecified","lax","strict","none").contains(sameSite))throw invalid();
                    if("none".equals(sameSite)&&Boolean.FALSE.equals(secure))throw invalid();
                    if(path!=null&&(!bounded(path,1,1024).startsWith("/")||path.indexOf(';')>=0))throw invalid();
                    if(domain!=null){
                        if(!AccountTransferJson.set("muse.ai",".muse.ai","www.muse.ai",".www.muse.ai").contains(domain))throw invalid();
                        String plain=domain.startsWith(".")?domain.substring(1):domain,host=URI.create(url).getHost();
                        if(!host.equals(plain)&&!host.endsWith("."+plain))throw invalid();
                        if(Boolean.TRUE.equals(hostOnly)&&!host.equals(plain))throw invalid();
                    } else if(Boolean.FALSE.equals(hostOnly))throw invalid();
                    if(cookieName.equals("hatch_sess")&&!cookieValue.isEmpty())sessionFound=true;
                    cookies.add(new Cookie(url,cookieName,cookieValue,domain,path,expires,secure,httpOnly,sameSite,session,hostOnly));
                }
                if(!sessionFound)throw invalid();
                accounts.add(new Account(ref,name(string(item,"name")),mode,proxy,policy,source,fidelity,coverage,cookies));
            }
            if(!usedProxies.equals(proxyRefs))throw invalid();
            return new AccountTransferBundle(accounts,proxies);
        } catch(IllegalArgumentException error){throw invalid();}
    }

    byte[] toBytes() throws AccountTransferCrypto.TransferException {
        List<Object> accountRows=new ArrayList<>(),proxyRows=new ArrayList<>();
        for(Proxy proxy:proxies){ProxySettings s=proxy.settings;proxyRows.add(map("proxy_ref",proxy.ref,"name",proxy.name,"protocol",s.scheme,"host",s.host,"port",s.port,"auth",s.hasAuthentication()?map("username",s.username,"password",s.secret):null));}
        for(Account a:accounts){
            List<Object> cookieRows=new ArrayList<>();for(Cookie c:a.cookies)cookieRows.add(map("source_url",c.sourceUrl,"name",c.name,"value",c.value,"domain",c.domain,"path",c.path,"expires_at_unix_seconds",c.expires,"secure",c.secure,"http_only",c.httpOnly,"same_site",c.sameSite,"session",c.session,"host_only",c.hostOnly));
            accountRows.add(map("account_ref",a.ref,"name",a.name,"network",map("mode",a.mode,"proxy_ref",a.proxyRef,"bypass_policy",a.bypassPolicy,"source_force_proxy",a.sourceForceProxy),"cookies",map("fidelity",a.fidelity,"coverage_urls",a.coverage,"items",cookieRows)));
        }
        return AccountTransferJson.bytes(map("format","muse-account-payload","version",1,"accounts",accountRows,"proxies",proxyRows));
    }

    static Account fromCookieHeader(String text,String name) throws AccountTransferCrypto.TransferException {
        List<Cookie> cookies=AccountTransferCookieText.header(text,"https://muse.ai/");
        Account account=new Account("account-1",name,"system",null,"platform",null,"header",java.util.Arrays.asList("https://muse.ai/"),cookies);
        return parse(new AccountTransferBundle(java.util.Arrays.asList(account),java.util.Arrays.asList()).toBytes()).accounts.get(0);
    }
    static String trusted(String value)throws AccountTransferCrypto.TransferException{
        try{URI uri=URI.create(value);if(value.length()>2048||!"https".equals(uri.getScheme())||!AccountTransferJson.set("muse.ai","www.muse.ai").contains(uri.getHost())||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw invalid();return value;}catch(IllegalArgumentException error){throw invalid();}
    }
    static boolean deviceRelated(String host){
        // Classification only: the original address is never rewritten or resolved here.
        String value=host.toLowerCase(Locale.ROOT);if(value.endsWith("."))value=value.substring(0,value.length()-1);
        return value.equals("localhost")||value.endsWith(".localhost")||value.endsWith(".local")||value.endsWith(".lan")||value.endsWith(".home.arpa")
            ||!value.contains(".")&&!value.contains(":")||value.equals("::")||value.equals("::1")||value.matches("(?:0{1,4}:){7}0*1?")
            ||value.startsWith("::ffff:")||value.equals("0.0.0.0")||value.startsWith("127.")||value.startsWith("10.")
            ||value.startsWith("192.168.")||value.startsWith("169.254.")||value.startsWith("fc")&&value.contains(":")
            ||value.startsWith("fd")&&value.contains(":")||value.startsWith("fe80:")||value.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    }
    private static String name(String value)throws AccountTransferCrypto.TransferException{bounded(value,1,512);if(value.codePointCount(0,value.length())>128||value.trim().isEmpty())throw invalid();return value;}
    private static String ref(String value)throws AccountTransferCrypto.TransferException{if(!value.matches("[A-Za-z0-9_-]{1,64}"))throw invalid();return value;}
    private static String bounded(String value,int min,int max)throws AccountTransferCrypto.TransferException{AccountTransferJson.validUnicode(value);int length=utf8(value);if(length<min||length>max||value.chars().anyMatch(Character::isISOControl))throw invalid();return value;}
    private static int utf8(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    private static String asString(Object value)throws AccountTransferCrypto.TransferException{if(!(value instanceof String))throw invalid();return (String)value;}
    private static String string(Map<String,Object> map,String key)throws AccountTransferCrypto.TransferException{return asString(map.get(key));}
    private static String nullableString(Map<String,Object> map,String key)throws AccountTransferCrypto.TransferException{return map.get(key)==null?null:asString(map.get(key));}
    private static Boolean nullableBoolean(Map<String,Object> map,String key)throws AccountTransferCrypto.TransferException{Object value=map.get(key);if(value!=null&&!(value instanceof Boolean))throw invalid();return (Boolean)value;}
    private static long integer(Map<String,Object> map,String key)throws AccountTransferCrypto.TransferException{Object value=map.get(key);if(!(value instanceof Long)&&!(value instanceof Integer))throw invalid();return ((Number)value).longValue();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value)throws AccountTransferCrypto.TransferException{if(!(value instanceof Map))throw invalid();return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value,int min,int max)throws AccountTransferCrypto.TransferException{if(!(value instanceof List<?> list)||list.size()<min||list.size()>max)throw invalid();return (List<Object>)value;}
    private static void keys(Map<String,Object> value,String...names)throws AccountTransferCrypto.TransferException{if(!value.keySet().equals(AccountTransferJson.set(names)))throw invalid();}
    private static Map<String,Object> map(Object...values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private static AccountTransferCrypto.TransferException invalid(){return AccountTransferJson.invalid();}
}
