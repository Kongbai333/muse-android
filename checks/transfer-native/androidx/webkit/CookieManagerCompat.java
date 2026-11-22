package androidx.webkit;
import android.webkit.CookieManager;
import java.util.ArrayList;
import java.util.List;
public final class CookieManagerCompat {
    public static List<String> getCookieInfo(CookieManager manager,String url){
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO))throw new UnsupportedOperationException();
        return manager.info.getOrDefault(url,new ArrayList<>());
    }
}
