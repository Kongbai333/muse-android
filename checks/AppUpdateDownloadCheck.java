package com.muse.workspace.mobile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real stream/hash/rename/cleanup with synthetic transport and an isolated temporary directory. */
public final class AppUpdateDownloadCheck {
    private static int checks;
    private static final byte[] APK={80,75,3,4};
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Path.of(args[0]),"SYNTHETIC-update-");
        String hash=hex(MessageDigest.getInstance("SHA-256").digest(APK));
        Map<String,Object> fixture=AppUpdateReleaseCheck.release();
        String filename=(String)AppUpdateReleaseCheck.asset(fixture,0).get("name");
        byte[] sha=(hash+"  "+filename+"\n").getBytes(StandardCharsets.US_ASCII);
        AppUpdateReleaseCheck.asset(fixture,1).put("size",(long)sha.length);
        AppUpdateRelease release=AppUpdateRelease.choose(AppUpdateReleaseCheck.repo(),fixture,"0.1.27");
        File directory=root.resolve("owned").toFile();require(directory.mkdir(),"isolated update folder");
        Path keep=directory.toPath().resolve("SYNTHETIC-neighbor.txt");Files.writeString(keep,"SYNTHETIC user data stays");
        for(String mode:new String[]{"success","hash","short","interrupt","cancel","pre-cancel"}){
            AtomicBoolean cancelled=new AtomicBoolean(mode.equals("pre-cancel"));
            AppUpdateDownload.Transport transport=asset->{
                if(asset.name().endsWith(".sha256"))return new AppUpdateDownload.Response(new ByteArrayInputStream(sha),sha.length,()->{});
                byte[] data=mode.equals("hash")?new byte[]{1,2,3,4}:mode.equals("short")?new byte[]{80,75}:APK;
                InputStream stream=mode.equals("interrupt")?new InputStream(){public int read()throws IOException{throw new IOException("SYNTHETIC disconnected");}}:new ByteArrayInputStream(data);
                return new AppUpdateDownload.Response(stream,asset.size(),()->{});
            };
            try{
                AppUpdateDownload.Verified verified=AppUpdateDownload.run(release,directory,transport,cancelled,(done,total)->{if(mode.equals("cancel"))cancelled.set(true);});
                File result=verified.file();require(verified.sha256().equals(hash),"authenticated checksum follows the file to the native installer");
                require(mode.equals("success"),"failure mode cannot yield installable result: "+mode);
                require(result.getName().equals("verified.apk")&&AppUpdateDownload.hash(result).equals(hash),"complete bytes/hash then fixed-name rename");
            }catch(AppUpdateRelease.Failure expected){require(!mode.equals("success"),"success unexpectedly failed");}
            if(!mode.equals("success"))require(!new File(directory,"verified.apk").exists(),"no installable partial after "+mode);
            require(!new File(directory,"download.part").exists(),"no partial remains after "+mode);
            require(Files.readString(keep).equals("SYNTHETIC user data stays"),"cleanup only owns its fixed files");
        }
        AppUpdateDownload.clean(directory);require(Files.exists(keep),"cancel cleanup never clears parent/user data");
        Files.delete(keep);Files.delete(directory.toPath());Files.delete(root);
        System.out.println("App update download: "+checks+" real file/stream checks passed; synthetic transport, not device installation.");
    }
    private static String hex(byte[] data){StringBuilder out=new StringBuilder();for(byte b:data)out.append(String.format("%02x",b&255));return out.toString();}
    private static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
}
