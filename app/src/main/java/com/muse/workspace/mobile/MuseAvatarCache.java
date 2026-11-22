package com.muse.workspace.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.Base64;
import java.util.HashMap;

final class MuseAvatarCache {
    private static final int MAX_DATA_URL = 48_000;
    private final SharedPreferences store;
    private final HashMap<String, Bitmap> decoded = new HashMap<>();

    MuseAvatarCache(Context context) {
        store = context.getSharedPreferences("muse-account-avatars", Context.MODE_PRIVATE);
    }

    void save(String account, String dataUrl) {
        if (account == null || account.isEmpty() || dataUrl == null || dataUrl.length() > MAX_DATA_URL
                || !dataUrl.startsWith("data:image/png;base64,")) return;
        String encoded = dataUrl.substring("data:image/png;base64,".length());
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (image == null || image.getWidth() != 72 || image.getHeight() != 72) return;
            decoded.put(account, image);
            store.edit().putString(account, encoded).apply();
        } catch (IllegalArgumentException ignored) { }
    }

    Bitmap image(String account) {
        Bitmap known = decoded.get(account);
        if (known != null) return known;
        String encoded = store.getString(account, "");
        if (encoded.isEmpty() || encoded.length() > MAX_DATA_URL) return null;
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (image == null || image.getWidth() != 72 || image.getHeight() != 72) return null;
            decoded.put(account, image);
            return image;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    boolean has(String account) { return store.contains(account); }

    void remove(String account) {
        decoded.remove(account);
        store.edit().remove(account).apply();
    }
}
