package com.muse.workspace.mobile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Executes real redirect/status/cancel code with synthetic URL connections, never live TLS. */
public final class AppUpdateHttpCheck {
    private static final ArrayDeque<Fake> queue=new ArrayDeque<>();private static final List<Fake> opened=new ArrayList<>();private static int checks;
    private static final class Fake extends HttpsURLConnection {
        int status=200;long length=2;boolean disconnected;byte[] bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);Runnable onRead;
        Map<String,String> headers=new HashMap<>();
        Fake()throws Exception{super(new URL("https://api.github.com/SYNTHETIC"));}
        public void connect(){}public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}
        public int getResponseCode(){return status;}public String getHeaderField(String name){return headers.get(name);}public long getContentLengthLong(){return length;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes){@Override public synchronized int read(byte[] b,int o,int l){if(onRead!=null){Runnable call=onRead;onRead=null;call.run();}return super.read(b,o,l);}};}
        public String getCipherSuite(){return "SYNTHETIC_ONLY";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return new Certificate[0];}
    }
    public static void main(String[] args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL url){Fake result=queue.removeFirst();opened.add(result);return result;}}:null);
        Fake ok=new Fake();queue.add(ok);require(new AppUpdateHttp().metadata(AppUpdateHttp.API,100).length==2,"public metadata body");
        require(!ok.getInstanceFollowRedirects()&&ok.getConnectTimeout()==15000&&ok.getReadTimeout()==15000,"manual bounded transport");
        require(ok.getRequestProperty("Authorization")==null&&ok.getRequestProperty("Cookie")==null,"no account or GitHub credentials supplied");
        Fake offsite=new Fake();offsite.status=302;offsite.headers.put("Location","https://evil.invalid/payload");queue.add(offsite);int before=opened.size();
        reject(()->new AppUpdateHttp().metadata(AppUpdateHttp.API,100),"source");require(opened.size()==before+1&&offsite.disconnected,"offsite redirect rejected before a request");
        before=opened.size();for(int i=0;i<6;i++){Fake redirect=new Fake();redirect.status=302;redirect.headers.put("Location","https://objects.githubusercontent.com/SYNTHETIC-"+i);queue.add(redirect);}
        reject(()->new AppUpdateHttp().metadata(AppUpdateHttp.API,100),"source");require(opened.size()==before+6,"at most five redirect hops");
        Fake huge=new Fake();huge.length=101;queue.add(huge);reject(()->new AppUpdateHttp().metadata(AppUpdateHttp.API,100),"size");require(huge.disconnected,"oversize body closes connection");
        Fake rate=new Fake();rate.status=429;rate.headers.put("Retry-After","120");queue.add(rate);try{new AppUpdateHttp().metadata(AppUpdateHttp.API,100);throw new AssertionError();}catch(AppUpdateRelease.Failure failure){require(failure.code.equals("rate")&&failure.retryAfterMs==120000,"rate limit preserved");}
        Fake excessive=new Fake();excessive.status=429;excessive.headers.put("Retry-After",Long.toString(Long.MAX_VALUE));queue.add(excessive);
        try{new AppUpdateHttp().metadata(AppUpdateHttp.API,100);throw new AssertionError();}catch(AppUpdateRelease.Failure failure){require(failure.retryAfterMs==21600000,"overflowing retry delay clamps conservatively");}
        AppUpdateHttp cancelled=new AppUpdateHttp();Fake stream=new Fake();stream.onRead=cancelled::cancel;queue.add(stream);reject(()->cancelled.metadata(AppUpdateHttp.API,100),"cancelled");require(stream.disconnected,"cancel disconnects active request");
        require(queue.isEmpty(),"all synthetic requests consumed");System.out.println("App update HTTPS boundary: "+checks+" checks passed; fake URL connections, no network/TLS verification.");
    }
    private interface Action{void run()throws Exception;}
    private static void reject(Action action,String code)throws Exception{try{action.run();}catch(AppUpdateRelease.Failure error){require(error.code.equals(code),"expected safe error code "+code);return;}throw new AssertionError("expected "+code);}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
}
