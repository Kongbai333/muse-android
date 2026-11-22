package com.muse.workspace.mobile;

final class MicrophonePermissionFlow {
    enum Decision { DENY, GRANT, REQUEST_ANDROID }

    private MicrophonePermissionFlow() { }

    static Decision decide(boolean audioAllowed, boolean permissionGranted, boolean androidPrompt) {
        if (!audioAllowed) return Decision.DENY;
        if (permissionGranted) return Decision.GRANT;
        return androidPrompt ? Decision.DENY : Decision.REQUEST_ANDROID;
    }
}
