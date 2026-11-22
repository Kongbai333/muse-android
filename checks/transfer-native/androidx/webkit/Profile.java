package androidx.webkit;
import android.webkit.CookieManager;
public final class Profile {
    public final CookieManager manager=new CookieManager();
    public CookieManager getCookieManager(){return manager;}
}
