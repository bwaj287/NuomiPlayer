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

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.myapplication.shared.LyricsHeuristics;
import com.example.myapplication.shared.MediaSyncContracts;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@SuppressLint("OverrideAbstract")
public class QqSessionSniffer extends NotificationListenerService {

    private static final String TAG = "SessionSniffer";
    private static final boolean ENABLE_PRIVATE_BRIDGE = false;

    private MediaController activeController;
    private String activePackage;
    private String lastLyricsPayload = "";
    private String lastStatus = "";
    private boolean kugouReceiverRegistered;
    private KugouPrivateBridge kugouPrivateBridge;

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

    private final BroadcastReceiver kugouBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            broadcastStatus("收到广播 " + compactAction(action));
            inspectTextCandidate("action", action, "broadcast-action");
            inspectBundle("broadcast", intent.getExtras(), "broadcast:" + action);
            refreshCtrl();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(MediaSyncContracts.ACTION_REQUEST_REMOTE_CONTROLLER);
        LocalBroadcastManager.getInstance(this).registerReceiver(requestReceiver, filter);
        registerKugouBroadcasts();
        if (ENABLE_PRIVATE_BRIDGE) {
            kugouPrivateBridge = new KugouPrivateBridge(this, new KugouPrivateBridge.Listener() {
                @Override
                public void onStatus(String status) {
                    broadcastStatus(status);
                }

                @Override
                public void onLyricsPayload(String payload, String source) {
                    publishLyricsPayload(payload, source);
                }
            });
            kugouPrivateBridge.attach();
        }
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(requestReceiver);
        unregisterKugouBroadcasts();
        if (kugouPrivateBridge != null) {
            kugouPrivateBridge.shutdown();
            kugouPrivateBridge = null;
        }
        if (activeController != null) {
            activeController.unregisterCallback(controllerCallback);
        }
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        refreshCtrl();
        requestPrivateBridgeConnect("listener-connected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        if (shouldInspectPackage(sbn.getPackageName())) {
            if (MediaSyncContracts.DEFAULT_TARGET_PACKAGE.equals(sbn.getPackageName())) {
                requestPrivateBridgeConnect("notification");
            }
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

        scanControllers(controllers);
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
        if (MediaSyncContracts.DEFAULT_TARGET_PACKAGE.equals(activePackage)) {
            requestPrivateBridgeConnect("refreshCtrl");
        }
        sendToken();
    }

    private MediaController chooseController(List<MediaController> controllers) {
        String preferred = preferredPackage();
        MediaController best = null;
        int bestScore = Integer.MIN_VALUE;

        for (MediaController controller : controllers) {
            if (controller == null) {
                continue;
            }
            int score = scoreController(controller, preferred);
            if (score > bestScore) {
                bestScore = score;
                best = controller;
            }
        }
        return best;
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
        if (MediaSyncContracts.DEFAULT_TARGET_PACKAGE.equals(activePackage)) {
            requestPrivateBridgeConnect("attach");
        }
        dumpCapabilities(controller);
        inspectMetadata(controller.getMetadata(), "session-metadata");
        inspectControllerExtras(controller, "controller-extras");
        inspectPlaybackState(controller.getPlaybackState(), "playback-state");
        sendToken();
    }

    private void requestPrivateBridgeConnect(String trigger) {
        if (ENABLE_PRIVATE_BRIDGE && kugouPrivateBridge != null) {
            kugouPrivateBridge.requestConnect(trigger);
        }
    }

    private void scanControllers(List<MediaController> controllers) {
        for (MediaController controller : controllers) {
            if (controller == null || controller.getPackageName() == null) {
                continue;
            }
            inspectMetadata(controller.getMetadata(), "scan-metadata:" + controller.getPackageName());
            inspectBundle("controller", controller.getExtras(), "scan-extras:" + controller.getPackageName());
            inspectPlaybackState(controller.getPlaybackState(), "scan-playback:" + controller.getPackageName());
        }
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
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                inspectValue(
                        key + "." + String.valueOf(entry.getKey()),
                        entry.getValue(),
                        source,
                        depth + 1
                );
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
                || source.contains("session-event")
                || source.startsWith("broadcast:")
                || source.startsWith("scan-extras:")
                || source.startsWith("scan-playback:"));
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

    private String compactAction(String action) {
        if (action == null || action.isEmpty()) {
            return "(empty)";
        }
        int lastDot = action.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < action.length() - 1) {
            return action.substring(lastDot + 1);
        }
        return action;
    }

    private void registerKugouBroadcasts() {
        if (kugouReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.kugou.android.huawei.hicar");
        filter.addAction("com.kugou.android.music.metachanged");
        filter.addAction("com.kugou.android.music.playstatechanged");
        filter.addAction("com.kugou.android.music.queuechanged");
        filter.addAction("com.kugou.android.music.musicservicecommand.action_back_lyric_change");
        filter.addAction("com.kugou.android.music.musicservicecommand.action_back_lyric_reset");
        filter.addAction("com.kugou.android.music.musicservicecommand.auto_change_lyr");
        filter.addAction("com.kugou.android.update_remote_lyric_close");
        filter.addAction("com.kugou.android.action.hicar.state_change");
        filter.addAction("com.huawei.hicar.ACTION_HICAR_STARTED");
        filter.addAction("com.huawei.hicar.ACTION_HICAR_STOPPED");
        ContextCompat.registerReceiver(
                this,
                kugouBroadcastReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
        );
        kugouReceiverRegistered = true;
    }

    private void unregisterKugouBroadcasts() {
        if (!kugouReceiverRegistered) {
            return;
        }
        unregisterReceiver(kugouBroadcastReceiver);
        kugouReceiverRegistered = false;
    }

    private int scoreController(MediaController controller, String preferredPackage) {
        int score = 0;
        String packageName = controller.getPackageName();
        if (preferredPackage.equals(packageName)) {
            score += 4000;
        } else if (MediaSyncContracts.FALLBACK_TARGET_PACKAGE.equals(packageName)) {
            score += 2500;
        }
        if (isPlaying(controller.getPlaybackState())) {
            score += 200;
        }
        score += scoreMetadata(controller.getMetadata());
        score += scoreBundle(controller.getExtras(), 0);
        score += scorePlaybackState(controller.getPlaybackState());
        return score;
    }

    private int scoreMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            return 0;
        }
        int score = 0;
        for (String key : metadata.keySet()) {
            score += scoreKey(key);
            CharSequence text = metadata.getText(key);
            if (text != null) {
                score += scoreText(key, text.toString());
            }
        }
        return score;
    }

    private int scorePlaybackState(PlaybackState state) {
        if (state == null) {
            return 0;
        }
        int score = scoreBundle(state.getExtras(), 0);
        List<PlaybackState.CustomAction> actions = state.getCustomActions();
        if (actions == null) {
            return score;
        }
        for (PlaybackState.CustomAction action : actions) {
            if (action == null) {
                continue;
            }
            score += scoreText("action", action.getAction());
            if (action.getName() != null) {
                score += scoreText("name", action.getName().toString());
            }
            score += scoreBundle(action.getExtras(), 0);
        }
        return score;
    }

    private int scoreBundle(Bundle extras, int depth) {
        if (extras == null || extras.isEmpty() || depth > 3) {
            return 0;
        }
        int score = 0;
        for (String key : extras.keySet()) {
            score += scoreKey(key);
            score += scoreObject(key, extras.get(key), depth + 1);
        }
        return score;
    }

    private int scoreObject(String key, Object value, int depth) {
        if (value == null || depth > 3) {
            return 0;
        }
        if (value instanceof CharSequence) {
            return scoreText(key, value.toString());
        }
        if (value instanceof Bundle) {
            return scoreBundle((Bundle) value, depth + 1);
        }
        if (value instanceof String[]) {
            int score = 0;
            for (String candidate : (String[]) value) {
                score += scoreText(key, candidate);
            }
            return score;
        }
        if (value instanceof CharSequence[]) {
            int score = 0;
            for (CharSequence candidate : (CharSequence[]) value) {
                score += scoreText(key, candidate != null ? candidate.toString() : null);
            }
            return score;
        }
        if (value instanceof List<?>) {
            int score = 0;
            int index = 0;
            for (Object candidate : (List<?>) value) {
                score += scoreObject(key + "[" + index + "]", candidate, depth + 1);
                index++;
                if (index >= 8) {
                    break;
                }
            }
            return score;
        }
        if (value instanceof Map<?, ?>) {
            int score = 0;
            int index = 0;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                score += scoreObject(
                        key + "." + String.valueOf(entry.getKey()),
                        entry.getValue(),
                        depth + 1
                );
                index++;
                if (index >= 8) {
                    break;
                }
            }
            return score;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length == 0 || bytes.length > 32_768) {
                return 0;
            }
            return scoreText(key, new String(bytes, StandardCharsets.UTF_8));
        }
        return 0;
    }

    private int scoreKey(String key) {
        if (key == null) {
            return 0;
        }
        String normalized = key.toLowerCase();
        int score = 0;
        if (normalized.contains("hicar") || normalized.contains("ucar")) {
            score += 1200;
        }
        if (LyricsHeuristics.keyLooksLikeLyrics(key)) {
            score += 2400;
        }
        return score;
    }

    private int scoreText(String key, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int score = scoreKey(key);
        String normalized = text.toLowerCase();
        if (normalized.contains("hicar") || normalized.contains("ucar")) {
            score += 1000;
        }
        if (LyricsHeuristics.looksLikeTimedLyrics(text)) {
            score += 3200;
        } else if (LyricsHeuristics.looksLikeLyricsPayload(text)) {
            score += 1800;
        }
        return score;
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
