package com.muse.workspace.mobile;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class MuseAdapters {
    static String source(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name); ByteArrayOutputStream text = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) text.write(buffer, 0, count);
            return text.toString("UTF-8");
        }
    }

    static JSONObject reply(String raw) {
        if (raw == null || raw.length() > 320_000) return null;
        try {
            Object decoded = new JSONTokener(raw).nextValue();
            if (decoded instanceof JSONObject object) return object;
            if (decoded instanceof String text) return new JSONObject(text);
        } catch (JSONException ignored) { }
        return null;
    }
}
