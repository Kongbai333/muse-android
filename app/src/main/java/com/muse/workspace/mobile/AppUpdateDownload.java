package com.muse.workspace.mobile;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads own fixed files only. A result is returned only after complete hash-checked rename. */
final class AppUpdateDownload {
    interface Transport { Response open(AppUpdateRelease.Asset asset)throws IOException; }
    interface Progress { void update(long done,long total); }
    record Verified(File file,String sha256) { }
    static final class Response implements Closeable {
        final InputStream stream;final long length;private final Runnable release;
        Response(InputStream stream,long length,Runnable release){this.stream=stream;this.length=length;this.release=release;}
        @Override public void close()throws IOException{try{stream.close();}finally{release.run();}}
    }
    static Verified run(AppUpdateRelease release,File directory,Transport transport,AtomicBoolean cancelled,Progress progress)throws AppUpdateRelease.Failure{
        boolean complete=false;
        try{
            check(cancelled);
            if(!directory.isDirectory()&&!directory.mkdirs())throw new AppUpdateRelease.Failure("storage");
            File part=child(directory,"download.part"),ready=child(directory,"verified.apk");clean(directory);
            byte[] checksum=small(release.checksum,transport,cancelled);
            verify(hex(digest().digest(checksum)),release.checksum.digest());
            String expected=AppUpdateRelease.checksum(checksum,release.apk.name());
            MessageDigest hash=digest();long size=0;byte[] buffer=new byte[32768];
            try(Response response=transport.open(release.apk);FileOutputStream output=new FileOutputStream(part)){
                if(response.length>=0&&response.length!=release.apk.size())throw new AppUpdateRelease.Failure("size");
                int count;while((count=response.stream.read(buffer))!=-1){
                    check(cancelled);size+=count;if(size>release.apk.size()||size>AppUpdateRelease.MAX_APK_BYTES)throw new AppUpdateRelease.Failure("size");
                    output.write(buffer,0,count);hash.update(buffer,0,count);progress.update(size,release.apk.size());
                }
                check(cancelled);if(size!=release.apk.size())throw new AppUpdateRelease.Failure("size");output.getFD().sync();
            }
            String actual=hex(hash.digest());verify(actual,expected);verify(actual,release.apk.digest());check(cancelled);
            if(!part.renameTo(ready))throw new AppUpdateRelease.Failure("storage");
            complete=true;return new Verified(ready,expected);
        }catch(AppUpdateRelease.Failure error){throw error;}
        catch(IOException error){throw new AppUpdateRelease.Failure("download");}
        finally{if(!complete)try{clean(directory);}catch(AppUpdateRelease.Failure ignored){}}
    }
    private static byte[] small(AppUpdateRelease.Asset asset,Transport transport,AtomicBoolean cancelled)throws IOException{
        if(asset.size()>AppUpdateRelease.MAX_CHECKSUM_BYTES)throw new AppUpdateRelease.Failure("size");
        try(Response response=transport.open(asset);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            if(response.length>=0&&response.length!=asset.size())throw new AppUpdateRelease.Failure("size");
            byte[] buffer=new byte[1024];int count;while((count=response.stream.read(buffer))!=-1){check(cancelled);if(output.size()+count>AppUpdateRelease.MAX_CHECKSUM_BYTES)throw new AppUpdateRelease.Failure("size");output.write(buffer,0,count);}
            check(cancelled);if(output.size()!=asset.size())throw new AppUpdateRelease.Failure("size");return output.toByteArray();
        }
    }
    static String hash(File file)throws AppUpdateRelease.Failure{
        try(InputStream input=new FileInputStream(file)){MessageDigest hash=digest();byte[] buffer=new byte[32768];long size=0;int count;
            while((count=input.read(buffer))!=-1){size+=count;if(size>AppUpdateRelease.MAX_APK_BYTES)throw new AppUpdateRelease.Failure("size");hash.update(buffer,0,count);}return hex(hash.digest());
        }catch(IOException error){if(error instanceof AppUpdateRelease.Failure failure)throw failure;throw new AppUpdateRelease.Failure("storage");}
    }
    static void clean(File directory)throws AppUpdateRelease.Failure{
        if(!directory.exists())return;
        for(String name:new String[]{"download.part","verified.apk"}){File file=child(directory,name);if(file.exists()&&!file.delete())throw new AppUpdateRelease.Failure("storage");}
    }
    private static File child(File directory,String name)throws AppUpdateRelease.Failure{
        try{File file=new File(directory,name);if(!directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()))throw new AppUpdateRelease.Failure("storage");return file;}
        catch(IOException error){throw new AppUpdateRelease.Failure("storage");}
    }
    private static void check(AtomicBoolean cancelled)throws AppUpdateRelease.Failure{if(cancelled.get()||Thread.currentThread().isInterrupted())throw new AppUpdateRelease.Failure("cancelled");}
    private static MessageDigest digest()throws AppUpdateRelease.Failure{try{return MessageDigest.getInstance("SHA-256");}catch(GeneralSecurityException error){throw new AppUpdateRelease.Failure("checksum");}}
    static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes){text.append(Character.forDigit((b>>>4)&15,16));text.append(Character.forDigit(b&15,16));}return text.toString();}
    private static void verify(String actual,String expected)throws AppUpdateRelease.Failure{if(expected!=null&&!actual.equals(expected))throw new AppUpdateRelease.Failure("checksum");}
}
