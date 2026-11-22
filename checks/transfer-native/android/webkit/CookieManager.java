package android.webkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public class CookieManager {
    public static final CookieManager DEFAULT=new CookieManager();
    public static int defaultReads;
    public final Map<String,List<String>> info=new HashMap<>();
    public final Map<String,String> headers=new HashMap<>();
    public final List<ValueCallback<Boolean>> callbacks=new ArrayList<>();
    public final List<String> written=new ArrayList<>();
    public int flushes;public boolean failFlush;
    public static CookieManager getInstance(){defaultReads++;return DEFAULT;}
    public void flush(){flushes++;if(failFlush)throw new IllegalStateException("synthetic");}
    public String getCookie(String url){return headers.get(url);}
    public void setCookie(String url,String value,ValueCallback<Boolean> callback){written.add(value);callbacks.add(callback);}
}
