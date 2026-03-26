package com.example.myapplication.shared;

public final class MediaSyncContracts {

    public static final String PREFS_NAME = "media_sync_prefs";
    public static final String PREF_TARGET_PACKAGE = "target_package";
    public static final String PREF_AUTO_LYRICS = "auto_lyrics";

    public static final String DEFAULT_TARGET_PACKAGE = "com.kugou.android";
    public static final String FALLBACK_TARGET_PACKAGE = "com.tencent.qqmusic";

    public static final String ACTION_REMOTE_CONTROLLER =
            "com.example.myapplication.action.REMOTE_CONTROLLER";
    public static final String ACTION_REQUEST_REMOTE_CONTROLLER =
            "com.example.myapplication.action.REQUEST_REMOTE_CONTROLLER";
    public static final String ACTION_LYRICS_PAYLOAD =
            "com.example.myapplication.action.LYRICS_PAYLOAD";
    public static final String ACTION_STATUS =
            "com.example.myapplication.action.STATUS";

    public static final String EXTRA_CONTROLLER_TOKEN = "controller_token";
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_LYRICS = "lyrics";
    public static final String EXTRA_LYRICS_SOURCE = "lyrics_source";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_HAS_LYRICS = "has_lyrics";
    public static final String EXTRA_LYRICS_ENABLED = "lyrics_enabled";

    public static final String CUSTOM_ACTION_SHOW_LYRICS =
            "com.example.myapplication.action.SHOW_LYRICS";
    public static final String CUSTOM_ACTION_HIDE_LYRICS =
            "com.example.myapplication.action.HIDE_LYRICS";

    private MediaSyncContracts() {
    }

    public static String friendlyName(String packageName) {
        if ("com.kugou.android".equals(packageName)) {
            return "酷狗音乐";
        }
        if ("com.tencent.qqmusic".equals(packageName)) {
            return "QQ 音乐";
        }
        if (packageName == null || packageName.isEmpty()) {
            return "播放器";
        }
        return packageName;
    }
}
