package com.example.myapplication;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.myapplication.shared.LyricsHeuristics;
import com.example.myapplication.shared.MediaSyncContracts;

import java.nio.charset.StandardCharsets;
import java.util.List;

@SuppressLint("OverrideAbstract")
public class QqSessionSniffer extends NotificationListenerService {

    private static final String TAG = "SessionSniffer";

    private MediaController activeController;
    private String activePackage;
    private String lastLyricsPayload = "";
    private String lastStatus = "";

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            inspectMetadata(metadata, "session-metadata");
            inspectControllerExtras(activeController, "controller-extras");
            sendToken();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            inspectPlaybackState(state, "playback-state");
            broadcastStatus(
                    MediaSyncContracts.friendlyName(activePackage)
                            + " 状态 "
                            + playbackLabel(state)
            );
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            inspectBundle("controller", extras, "controller-extras");
        }

        @Override
        public void onSessionEvent(String event, Bundle extras) {
            inspectTextCandidate("event", event, "session-event-name");
            inspectBundle("sessionEvent", extras, "session-event");
            if (event != null && !event.isEmpty()) {
                broadcastStatus("收到 " + MediaSyncContracts.friendlyName(activePackage) + " session event: " + event);
            }
        }
    };

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshCtrl();
            sendToken();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(MediaSyncContracts.ACTION_REQUEST_REMOTE_CONTROLLER);
        LocalBroadcastManager.getInstance(this).registerReceiver(requestReceiver, filter);
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(requestReceiver);
        if (activeController != null) {
            activeController.unregisterCallback(controllerCallback);
        }
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        refreshCtrl();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        if (shouldInspectPackage(sbn.getPackageName())) {
            inspectNotification(sbn);
            refreshCtrl();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && sbn.getPackageName() != null && sbn.getPackageName().equals(activePackage)) {
            refreshCtrl();
        }
    }

    private boolean shouldInspectPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        return packageName.equals(preferredPackage())
                || packageName.equals(activePackage)
                || packageName.equals(MediaSyncContracts.FALLBACK_TARGET_PACKAGE);
    }

    private String preferredPackage() {
        return getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                .getString(
                        MediaSyncContracts.PREF_TARGET_PACKAGE,
                        MediaSyncContracts.DEFAULT_TARGET_PACKAGE
                );
    }

    private void refreshCtrl() {
        MediaSessionManager manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (manager == null) {
            broadcastStatus("系统未提供 MediaSessionManager");
            return;
        }

        ComponentName me = new ComponentName(this, QqSessionSniffer.class);
        List<MediaController> controllers = manager.getActiveSessions(me);
        if (controllers == null || controllers.isEmpty()) {
            if (activeController != null) {
                activeController.unregisterCallback(controllerCallback);
                activeController = null;
            }
            activePackage = null;
            lastLyricsPayload = "";
            broadcastStatus("未捕获到活动播放器，请先在酷狗里播放一首歌");
            return;
        }

        MediaController chosen = chooseController(controllers);
        if (chosen == null) {
            chosen = controllers.get(0);
        }

        if (activeController == null
                || !activeController.getSessionToken().equals(chosen.getSessionToken())) {
            attach(chosen);
            return;
        }

        activePackage = chosen.getPackageName();
        inspectMetadata(chosen.getMetadata(), "session-metadata");
        sendToken();
    }

    private MediaController chooseController(List<MediaController> controllers) {
        String preferred = preferredPackage();
        MediaController preferredPlaying = null;
        MediaController preferredAny = null;
        MediaController anyPlaying = null;

        for (MediaController controller : controllers) {
            if (controller == null) {
                continue;
            }
            if (preferred.equals(controller.getPackageName())) {
                preferredAny = controller;
                if (isPlaying(controller.getPlaybackState())) {
                    preferredPlaying = controller;
                }
            } else if (anyPlaying == null && isPlaying(controller.getPlaybackState())) {
                anyPlaying = controller;
            }
        }

        if (preferredPlaying != null) {
            return preferredPlaying;
        }
        if (preferredAny != null) {
            return preferredAny;
        }
        if (anyPlaying != null) {
            return anyPlaying;
        }
        return controllers.isEmpty() ? null : controllers.get(0);
    }

    private boolean isPlaying(PlaybackState state) {
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private void attach(MediaController controller) {
        if (activeController != null) {
            activeController.unregisterCallback(controllerCallback);
        }
        activeController = controller;
        activePackage = controller.getPackageName();
        activeController.registerCallback(controllerCallback);

        broadcastStatus("已连接 " + MediaSyncContracts.friendlyName(activePackage) + " 的 MediaSession");
        dumpCapabilities(controller);
        inspectMetadata(controller.getMetadata(), "session-metadata");
        inspectControllerExtras(controller, "controller-extras");
        inspectPlaybackState(controller.getPlaybackState(), "playback-state");
        sendToken();
    }

    private void sendToken() {
        if (activeController == null) {
            return;
        }
        MediaSessionCompat.Token compatToken =
                MediaSessionCompat.Token.fromToken(activeController.getSessionToken());
        Intent intent = new Intent(MediaSyncContracts.ACTION_REMOTE_CONTROLLER);
        intent.putExtra(MediaSyncContracts.EXTRA_CONTROLLER_TOKEN, compatToken);
        intent.putExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE, activePackage);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void inspectNotification(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification() != null ? sbn.getNotification().extras : null;
        if (extras == null || extras.isEmpty()) {
            return;
        }
        inspectBundle("notification", extras, "notification");
    }

    private void inspectMetadata(MediaMetadata metadata, String source) {
        if (metadata == null) {
            return;
        }
        Log.i(
                TAG,
                "Meta from "
                        + activePackage
                        + " title="
                        + metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        + " artist="
                        + metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        );
        for (String key : metadata.keySet()) {
            inspectValue(key, metadata.getText(key), source);
        }
    }

    private void inspectControllerExtras(MediaController controller, String source) {
        if (controller == null) {
            return;
        }
        inspectBundle("controller", controller.getExtras(), source);
    }

    private void inspectPlaybackState(PlaybackState state, String source) {
        if (state == null) {
            return;
        }
        inspectBundle("playbackState", state.getExtras(), source + "-extras");
        List<PlaybackState.CustomAction> actions = state.getCustomActions();
        if (actions == null) {
            return;
        }
        for (PlaybackState.CustomAction action : actions) {
            if (action == null) {
                continue;
            }
            inspectTextCandidate("action", action.getAction(), source + "-customAction");
            inspectTextCandidate(
                    "name",
                    action.getName() != null ? action.getName().toString() : null,
                    source + "-customAction"
            );
            inspectBundle(action.getAction(), action.getExtras(), source + "-customAction");
        }
    }

    private void inspectBundle(String key, Bundle extras, String source) {
        if (extras == null || extras.isEmpty()) {
            return;
        }
        if (shouldReportBundle(source)) {
            broadcastStatus("检查 " + source + " keys: " + summarizeKeys(extras));
        }
        for (String childKey : extras.keySet()) {
            inspectValue(key + "." + childKey, extras.get(childKey), source, 0);
        }
    }

    private void inspectValue(String key, Object value, String source) {
        inspectValue(key, value, source, 0);
    }

    private void inspectValue(String key, Object value, String source, int depth) {
        if (depth > 3 || value == null) {
            return;
        }
        if (value instanceof CharSequence) {
            inspectTextCandidate(key, value.toString(), source);
            return;
        }
        if (value instanceof CharSequence[]) {
            CharSequence[] values = (CharSequence[]) value;
            for (CharSequence candidate : values) {
                inspectTextCandidate(key, candidate != null ? candidate.toString() : null, source);
            }
            return;
        }
        if (value instanceof Bundle) {
            Bundle bundle = (Bundle) value;
            for (String childKey : bundle.keySet()) {
                inspectValue(key + "." + childKey, bundle.get(childKey), source, depth + 1);
            }
            return;
        }
        if (value instanceof String[]) {
            String[] values = (String[]) value;
            for (String candidate : values) {
                inspectTextCandidate(key, candidate, source);
            }
            return;
        }
        if (value instanceof List<?>) {
            List<?> values = (List<?>) value;
            int index = 0;
            for (Object candidate : values) {
                inspectValue(key + "[" + index + "]", candidate, source, depth + 1);
                index++;
                if (index >= 8) {
                    break;
                }
            }
            return;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length == 0 || bytes.length > 32_768) {
                return;
            }
            inspectTextCandidate(key, new String(bytes, StandardCharsets.UTF_8), source);
        }
    }

    private void inspectTextCandidate(String key, String candidate, String source) {
        String normalized = LyricsHeuristics.normalizePayload(candidate);
        if (normalized.isEmpty()) {
            return;
        }

        boolean likelyLyrics = LyricsHeuristics.keyLooksLikeLyrics(key)
                || LyricsHeuristics.looksLikeTimedLyrics(normalized)
                || LyricsHeuristics.looksLikeLyricsPayload(normalized);

        if (!likelyLyrics) {
            return;
        }

        publishLyricsPayload(normalized, source + ":" + key);
    }

    private void publishLyricsPayload(String payload, String source) {
        String normalized = LyricsHeuristics.normalizePayload(payload);
        if (normalized.isEmpty() || normalized.equals(lastLyricsPayload)) {
            return;
        }
        lastLyricsPayload = normalized;

        Intent intent = new Intent(MediaSyncContracts.ACTION_LYRICS_PAYLOAD);
        intent.putExtra(MediaSyncContracts.EXTRA_LYRICS, normalized);
        intent.putExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE, activePackage);
        intent.putExtra(MediaSyncContracts.EXTRA_LYRICS_SOURCE, source);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        String signal = LyricsHeuristics.looksLikeTimedLyrics(normalized)
                ? "发现可同步歌词"
                : "发现候选歌词";
        broadcastStatus(MediaSyncContracts.friendlyName(activePackage) + " " + signal + "，来源 " + source);
    }

    private void broadcastStatus(String status) {
        if (status == null || status.equals(lastStatus)) {
            return;
        }
        lastStatus = status;
        Log.i(TAG, status);

        Intent intent = new Intent(MediaSyncContracts.ACTION_STATUS);
        intent.putExtra(MediaSyncContracts.EXTRA_STATUS, status);
        intent.putExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE, activePackage);
        intent.putExtra(MediaSyncContracts.EXTRA_HAS_LYRICS, !lastLyricsPayload.isEmpty());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private boolean shouldReportBundle(String source) {
        return source != null
                && (source.contains("controller-extras")
                || source.contains("playback-state")
                || source.contains("session-event"));
    }

    private String summarizeKeys(Bundle bundle) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String key : bundle.keySet()) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(key);
            index++;
            if (index >= 5) {
                break;
            }
        }
        if (bundle.keySet().size() > 5) {
            builder.append("...");
        }
        return builder.toString();
    }

    private String playbackLabel(PlaybackState state) {
        if (state == null) {
            return "UNKNOWN";
        }
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
                return "PLAYING";
            case PlaybackState.STATE_PAUSED:
                return "PAUSED";
            case PlaybackState.STATE_BUFFERING:
                return "BUFFERING";
            default:
                return String.valueOf(state.getState());
        }
    }

    private void dumpCapabilities(MediaController controller) {
        PlaybackState state = controller.getPlaybackState();
        long actions = state != null ? state.getActions() : 0L;

        Log.i(TAG, "=== Playback Actions ===");
        if ((actions & PlaybackState.ACTION_PLAY) != 0) Log.i(TAG, "  PLAY");
        if ((actions & PlaybackState.ACTION_PAUSE) != 0) Log.i(TAG, "  PAUSE");
        if ((actions & PlaybackState.ACTION_PLAY_PAUSE) != 0) Log.i(TAG, "  PLAY_PAUSE");
        if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) Log.i(TAG, "  NEXT");
        if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) Log.i(TAG, "  PREVIOUS");
        if ((actions & PlaybackState.ACTION_SEEK_TO) != 0) Log.i(TAG, "  SEEK_TO");

        MediaMetadata metadata = controller.getMetadata();
        if (metadata != null) {
            Log.i(TAG, "=== Metadata Keys ===");
            for (String key : metadata.keySet()) {
                Log.i(TAG, "  " + key);
            }
        }

        List<MediaSession.QueueItem> queue = controller.getQueue();
        Log.i(TAG, "Queue size=" + (queue == null ? 0 : queue.size()));
    }
}
