package com.muse.workspace.mobile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Closed official-source/asset/identity policy. Contains no Android, credentials or requests. */
final class AppUpdateRelease {
    static final String REPO="Kongbai333/muse-android", PACKAGE="com.muse.workspace.mobile";
    static final long REPO_ID=1467211251L,MAX_APK_BYTES=128L*1024*1024;
    static final int MAX_METADATA_BYTES=1024*1024,MAX_CHECKSUM_BYTES=4096;
    private static final Pattern VERSION=Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private static final Set<String> HOSTS=new HashSet<>(Arrays.asList("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com"));
    static final class Failure extends IOException { final String code;final long retryAfterMs;Failure(String code){this(code,0);}Failure(String code,long retryAfterMs){super(code);this.code=code;this.retryAfterMs=retryAfterMs;} }
    record Asset(String name,String url,long size,String digest) { }
    record Apk(String packageName,String version,long code,List<String> signers) {
        Apk { signers=signers==null?Collections.emptyList():Collections.unmodifiableList(new ArrayList<>(signers)); }
    }
    final String tag,version,notes;
    final Asset apk,checksum;
    private AppUpdateRelease(String tag,String version,String notes,Asset apk,Asset checksum){this.tag=tag;this.version=version;this.notes=notes;this.apk=apk;this.checksum=checksum;}

    static AppUpdateRelease choose(Map<String,Object> repository,Map<String,Object> release,String installed)throws Failure{
        if(repository==null||integer(repository.get("id"))!=REPO_ID||!REPO.equalsIgnoreCase(string(repository.get("full_name"))))throw new Failure("source");
        if(release==null||!(release.get("draft") instanceof Boolean)||!(release.get("prerelease") instanceof Boolean))throw new Failure("metadata");
        if(Boolean.TRUE.equals(release.get("draft"))||Boolean.TRUE.equals(release.get("prerelease")))return null;
        String tag=string(release.get("tag_name"));if(!tag.startsWith("v"))throw new Failure("version");
        String version=tag.substring(1);parts(version);
        if(!newer(version,installed))return null;
        if(!("https://github.com/"+REPO+"/releases/tag/"+tag).equals(string(release.get("html_url"))))throw new Failure("source");
        String name="Muse-Android-"+version+"-preview.apk";
        if(!(release.get("assets") instanceof List<?> assets)||assets.size()>1000)throw new Failure("assets");
        Asset apk=null,sha=null;
        for(Object raw:assets){
            if(!(raw instanceof Map<?,?> asset))continue;
            Object filename=asset.get("name");
            if(!name.equals(filename)&&!(name+".sha256").equals(filename))continue;
            String file=(String)filename,url=string(asset.get("browser_download_url"));URI uri=allowedUrl(url);
            if(!uri.getHost().equals("github.com")||uri.getRawQuery()!=null||!("/"+REPO+"/releases/download/"+tag+"/"+file).equals(uri.getRawPath()))throw new Failure("source");
            if(!"uploaded".equals(asset.get("state")))throw new Failure("assets");
            long size=integer(asset.get("size"));if(size<=0||size>(name.equals(file)?MAX_APK_BYTES:MAX_CHECKSUM_BYTES))throw new Failure("size");
            String digest=null;
            if(asset.get("digest")!=null){String input=string(asset.get("digest"));if(!input.matches("sha256:[0-9a-fA-F]{64}"))throw new Failure("checksum");digest=input.substring(7).toLowerCase(Locale.ROOT);}
            Asset found=new Asset(file,url,size,digest);
            if(name.equals(file)){if(apk!=null)throw new Failure("assets");apk=found;}
            else {if(sha!=null)throw new Failure("assets");sha=found;}
        }
        if(apk==null||sha==null)throw new Failure("assets");
        Object body=release.get("body");String notes=body instanceof String?((String)body).replace("\u0000",""):"";
        if(notes.length()>16000)notes=notes.substring(0,16000);
        return new AppUpdateRelease(tag,version,notes,apk,sha);
    }

    static boolean newer(String candidate,String installed)throws Failure{
        int[] a=parts(candidate),b=parts(installed);
        for(int i=0;i<3;i++)if(a[i]!=b[i])return a[i]>b[i];return false;
    }
    private static int[] parts(String value)throws Failure{
        if(value==null)throw new Failure("version");Matcher m=VERSION.matcher(value);if(!m.matches())throw new Failure("version");
        return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))};
    }
    static URI allowedUrl(String value)throws Failure{
        try{
            if(value==null||value.length()>8192)throw new IllegalArgumentException();URI uri=URI.create(value);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||!HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IllegalArgumentException();
            return uri;
        }catch(IllegalArgumentException error){throw new Failure("source");}
    }
    static String checksum(byte[] bytes,String filename)throws Failure{
        if(bytes==null||bytes.length==0||bytes.length>MAX_CHECKSUM_BYTES||!filename.matches("Muse-Android-[0-9]+\\.[0-9]+\\.[0-9]+-preview\\.apk"))throw new Failure("checksum");
        Matcher m=Pattern.compile("([0-9a-fA-F]{64})[ \\t]+\\*?"+Pattern.quote(filename)+"(?:\\r?\\n)?").matcher(new String(bytes,StandardCharsets.US_ASCII));
        if(!m.matches())throw new Failure("checksum");return m.group(1).toLowerCase(Locale.ROOT);
    }
    static void validateIdentity(Apk installed,Apk candidate,AppUpdateRelease release)throws Failure{
        if(installed==null||candidate==null||!PACKAGE.equals(installed.packageName)||!PACKAGE.equals(candidate.packageName))throw new Failure("package");
        if(candidate.code<=installed.code||candidate.code<=0||!release.version.equals(candidate.version))throw new Failure("version");
        if(!signers(installed.signers).equals(signers(candidate.signers)))throw new Failure("signature");
    }
    private static Set<String> signers(List<String> values)throws Failure{
        if(values==null||values.isEmpty()||values.size()>8)throw new Failure("signature");Set<String> result=new HashSet<>();
        for(String value:values){if(value==null||!value.matches("[0-9a-fA-F]{64}"))throw new Failure("signature");result.add(value.toLowerCase(Locale.ROOT));}return result;
    }

    Map<String,Object> cacheRows(){
        Map<String,Object> root=new LinkedHashMap<>();root.put("draft",false);root.put("prerelease",false);root.put("tag_name",tag);root.put("body",notes);root.put("html_url","https://github.com/"+REPO+"/releases/tag/"+tag);
        List<Object> assets=new ArrayList<>();for(Asset asset:Arrays.asList(apk,checksum)){Map<String,Object> row=new LinkedHashMap<>();row.put("name",asset.name);row.put("browser_download_url",asset.url);row.put("size",asset.size);row.put("digest",asset.digest==null?null:"sha256:"+asset.digest);row.put("state","uploaded");assets.add(row);}root.put("assets",assets);return root;
    }
    private static String string(Object value)throws Failure{if(!(value instanceof String))throw new Failure("metadata");return (String)value;}
    private static long integer(Object value)throws Failure{if(!(value instanceof Number n))throw new Failure("metadata");double d=n.doubleValue();if(!Double.isFinite(d)||d<0||d>9007199254740991L||d!=Math.floor(d))throw new Failure("metadata");return n.longValue();}
}
