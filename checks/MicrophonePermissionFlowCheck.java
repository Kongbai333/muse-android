package com.muse.workspace.mobile;

import java.lang.reflect.Method;

public final class MicrophonePermissionFlowCheck {
    public static void main(String[] args) throws Exception {
        Class<?> flow;
        try {
            flow = Class.forName("com.muse.workspace.mobile.MicrophonePermissionFlow");
        } catch (ClassNotFoundException error) {
            throw new AssertionError("Microphone permission flow policy is missing.");
        }
        Method decide = flow.getDeclaredMethod("decide", boolean.class, boolean.class, boolean.class);
        require(decide, false, true, false, "DENY", "Untrusted resources must be denied");
        require(decide, true, true, true, "GRANT", "An OS grant must win over a pending permission callback");
        require(decide, true, false, true, "DENY", "Do not overlap Android permission prompts");
        require(decide, true, false, false, "REQUEST_ANDROID", "Request missing Android permission");
        System.out.println("Muse microphone permission flow passed.");
    }

    private static void require(Method decide, boolean audioAllowed, boolean permissionGranted,
            boolean androidPrompt, String expected, String message) throws Exception {
        Object actual = decide.invoke(null, audioAllowed, permissionGranted, androidPrompt);
        if (!expected.equals(actual.toString())) throw new AssertionError(message + ": " + actual);
    }
}
