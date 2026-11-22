package androidx.webkit;
import java.util.HashMap;
import java.util.Map;
public final class ProfileStore {
    private static final ProfileStore INSTANCE=new ProfileStore();
    public final Map<String,Profile> profiles=new HashMap<>();
    public int creates;
    public static ProfileStore getInstance(){return INSTANCE;}
    public Profile getProfile(String name){return profiles.get(name);}
    public Profile getOrCreateProfile(String name){creates++;return profiles.computeIfAbsent(name,key->new Profile());}
    public boolean deleteProfile(String name){return profiles.remove(name)!=null;}
}
