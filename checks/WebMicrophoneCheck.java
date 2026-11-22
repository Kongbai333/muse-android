package com.muse.workspace.mobile;

import java.util.Arrays;

public final class WebMicrophoneCheck {
    public static void main(String[] args) {
        String muse = "https://muse.ai/thread/new";
        String origin = "https://muse.ai";
        String audio = "android.webkit.resource.AUDIO_CAPTURE";
        String camera = "android.webkit.resource.VIDEO_CAPTURE";

        require(Arrays.equals(new String[]{audio}, WebMicrophone.allowedResources(muse, origin,
            new String[]{camera, audio, "example.future.permission"})), "Only requested microphone access may be granted");
        require(WebMicrophone.allowedResources(muse, "https://www.muse.ai", new String[]{audio}).length == 1,
            "The official www origin can request microphone access");
        require(WebMicrophone.allowedResources(muse, origin, new String[]{camera}).length == 0,
            "A camera-only request must not be granted");
        require(WebMicrophone.allowedResources(muse, "https://muse.ai.other.example", new String[]{audio}).length == 0,
            "An embedded third-party origin must not inherit microphone access");
        require(WebMicrophone.allowedResources("https://other.example", origin, new String[]{audio}).length == 0,
            "A trusted frame in an unrelated page must not inherit microphone access");
        require(WebMicrophone.allowedResources(muse, "https://muse.ai:444", new String[]{audio}).length == 0,
            "A different port is a different origin");
        System.out.println("Muse microphone permission policy passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
