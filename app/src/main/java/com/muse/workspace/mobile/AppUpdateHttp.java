package com.muse.workspace.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Public app-level HTTPS only; never uses Muse WebViews, cookies, vaults or account routes. */
final class AppUpdateHttp implements AppUpdateDownload.Transport {
    static final String API="https://api.github.com/repos/"+AppUpdateRelease.REPO;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile HttpsURLConnection active;
    @Override public AppUpdateDownload.Response open(AppUpdateRelease.Asset asset)throws IOException{return connect(asset.url());}
    byte[] metadata(String url,int limit)throws AppUpdateRelease.Failure{
        if(!url.equals(API)&&!url.equals(API+"/releases/latest"))throw new AppUpdateRelease.Failure("source");
        try(AppUpdateDownload.Response response=connect(url);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            if(response.length>limit)throw new AppUpdateRelease.Failure("size");
            byte[] buffer=new byte[8192];int count;
            while((count=response.stream.read(buffer))!=-1){check();if(output.size()+count>limit)throw new AppUpdateRelease.Failure("size");output.write(buffer,0,count);}check();return output.toByteArray();
        }catch(AppUpdateRelease.Failure error){throw error;}catch(IOException error){throw new AppUpdateRelease.Failure("network");}
    }
    private AppUpdateDownload.Response connect(String input)throws AppUpdateRelease.Failure{
        try{
            String next=input;
            for(int redirects=0;redirects<=5;redirects++){
                check();URI uri=AppUpdateRelease.allowedUrl(next);
                HttpsURLConnection connection=(HttpsURLConnection)uri.toURL().openConnection();active=connection;
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
                connection.setRequestMethod("GET");connection.setRequestProperty("User-Agent","Muse-Android-Update");
                connection.setRequestProperty("Accept","application/vnd.github+json,application/octet-stream");
                connection.setRequestProperty("Accept-Encoding","identity");
                if(uri.getHost().equals("api.github.com"))connection.setRequestProperty("X-GitHub-Api-Version","2026-03-10");
                check();int status=connection.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){
                    String location=connection.getHeaderField("Location");connection.disconnect();active=null;
                    if(location==null||redirects==5)throw new AppUpdateRelease.Failure("source");next=new URL(uri.toURL(),location).toString();continue;
                }
                if(status!=200){
                    long retry=retryAfter(connection.getHeaderField("Retry-After"));
                    boolean limited=status==429||status==403&&("0".equals(connection.getHeaderField("X-RateLimit-Remaining"))||connection.getHeaderField("Retry-After")!=null);
                    connection.disconnect();active=null;
                    if(limited)throw new AppUpdateRelease.Failure("rate",retry);
                    throw new AppUpdateRelease.Failure(status==404?"no_release":"network");
                }
                check();return new AppUpdateDownload.Response(connection.getInputStream(),connection.getContentLengthLong(),()->{connection.disconnect();if(active==connection)active=null;});
            }
            throw new AppUpdateRelease.Failure("source");
        }catch(AppUpdateRelease.Failure error){disconnect();throw error;}
        catch(IOException|RuntimeException error){disconnect();throw new AppUpdateRelease.Failure(cancelled.get()?"cancelled":"network");}
    }
    void cancel(){cancelled.set(true);disconnect();}
    private void disconnect(){HttpsURLConnection connection=active;if(connection!=null){active=null;connection.disconnect();}}
    private void check()throws AppUpdateRelease.Failure{if(cancelled.get()||Thread.currentThread().isInterrupted())throw new AppUpdateRelease.Failure("cancelled");}
    private static long retryAfter(String value){
        if(value==null)return 1800000;
        try{long seconds=Long.parseLong(value.trim());return seconds<0?1800000:Math.max(60000,Math.min(21600,seconds)*1000);}
        catch(NumberFormatException error){try{long time=java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();return Math.min(21600000,Math.max(60000,time-System.currentTimeMillis()));}catch(RuntimeException ignored){return 1800000;}}
    }
}
