package androidx.webkit;
import java.util.HashSet;
import java.util.Set;
public final class WebViewFeature {
    public static final String MULTI_PROFILE="MULTI_PROFILE",GET_COOKIE_INFO="GET_COOKIE_INFO";
    public static final Set<String> supported=new HashSet<>();
    public static boolean isFeatureSupported(String feature){return supported.contains(feature);}
}
