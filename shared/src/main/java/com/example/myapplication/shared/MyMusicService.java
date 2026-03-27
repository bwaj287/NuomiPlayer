package com.example.myapplication.shared;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyMusicService extends MediaBrowserServiceCompat {

    private static final Pattern LRC_TAG_PATTERN =
            Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern KRC_LINE_PATTERN =
            Pattern.compile("^\\[(\\d{1,7}),(\\d{1,7})](.*)$");

    private MediaSessionCompat session;
    private MediaControllerCompat remoteCtrl;
    private MediaMetadataCompat lastRemoteMetadata;
    private PlaybackStateCompat lastRemoteState;
    private String currentSourcePackage = MediaSyncContracts.DEFAULT_TARGET_PACKAGE;
    private String lastLyricsRaw = "";
    private String lyricsSource = "";
    private String lastStatus = "";
    private boolean lyricsEnabled;
    private final List<LyricLine> parsedLyrics = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable lyricsUpdater = new Runnable() {
        @Override
        public void run() {
            if (!shouldRunLyricsTicker()) {
                return;
            }
            mirror(null, null);
            handler.postDelayed(this, 1000L);
        }
    };

    private final MediaControllerCompat.Callback remoteCb = new RemoteCallback();

    private final BroadcastReceiver tokenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MediaSessionCompat.Token token =
                    intent.getParcelableExtra(MediaSyncContracts.EXTRA_CONTROLLER_TOKEN);
            if (token == null) {
                return;
            }

            currentSourcePackage = intent.getStringExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE);
            if (currentSourcePackage == null || currentSourcePackage.isEmpty()) {
                currentSourcePackage = MediaSyncContracts.DEFAULT_TARGET_PACKAGE;
            }

            if (remoteCtrl != null) {
                remoteCtrl.unregisterCallback(remoteCb);
            }

            remoteCtrl = new MediaControllerCompat(MyMusicService.this, token);
            remoteCtrl.registerCallback(remoteCb);

            publishStatus("Android Auto 已连接 " + MediaSyncContracts.friendlyName(currentSourcePackage));
            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
        }
    };

    private final BroadcastReceiver lyricsRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String payload = intent.getStringExtra(MediaSyncContracts.EXTRA_LYRICS);
            String sourcePackage = intent.getStringExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE);
            String source = intent.getStringExtra(MediaSyncContracts.EXTRA_LYRICS_SOURCE);

            if (sourcePackage != null && !sourcePackage.isEmpty()) {
                currentSourcePackage = sourcePackage;
            }
            acceptLyricsPayload(payload, source);
        }
    };

    private class RemoteCallback extends MediaControllerCompat.Callback {
        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            inspectLyricsFromMetadata(metadata);
            mirror(metadata, null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            mirror(null, state);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        lyricsEnabled = getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(MediaSyncContracts.PREF_AUTO_LYRICS, true);

        session = new MediaSessionCompat(this, "MirrorSession");
        session.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        session.setActive(true);
        setSessionToken(session.getSessionToken());

        session.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().play();
                }
            }

            @Override
            public void onPause() {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().pause();
                }
            }

            @Override
            public void onStop() {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().stop();
                }
            }

            @Override
            public void onSkipToNext() {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().skipToNext();
                }
            }

            @Override
            public void onSkipToPrevious() {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().skipToPrevious();
                }
            }

            @Override
            public void onSeekTo(long positionMs) {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().seekTo(positionMs);
                }
            }

            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().playFromSearch(query, extras);
                } else {
                    onPlay();
                }
            }

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().playFromMediaId(mediaId, extras);
                } else {
                    onPlay();
                }
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                if (!hasLyrics()) {
                    return;
                }
                if (MediaSyncContracts.CUSTOM_ACTION_SHOW_LYRICS.equals(action)
                        || MediaSyncContracts.CUSTOM_ACTION_HIDE_LYRICS.equals(action)) {
                    lyricsEnabled = !lyricsEnabled;
                    getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(MediaSyncContracts.PREF_AUTO_LYRICS, lyricsEnabled)
                            .apply();
                    publishStatus(lyricsEnabled ? "歌词模式已开启" : "歌词模式已关闭");
                    mirror(null, null);
                }
            }
        });

        IntentFilter controllerFilter = new IntentFilter(MediaSyncContracts.ACTION_REMOTE_CONTROLLER);
        IntentFilter lyricsFilter = new IntentFilter(MediaSyncContracts.ACTION_LYRICS_PAYLOAD);
        LocalBroadcastManager.getInstance(this).registerReceiver(tokenRx, controllerFilter);
        LocalBroadcastManager.getInstance(this).registerReceiver(lyricsRx, lyricsFilter);

        publishStatus("等待连接播放器");
        requestRemoteController();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(tokenRx);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(lyricsRx);
        if (remoteCtrl != null) {
            remoteCtrl.unregisterCallback(remoteCb);
        }
        if (session != null) {
            session.release();
        }
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(
            @NonNull String parentId,
            @NonNull Result<List<MediaBrowserCompat.MediaItem>> result
    ) {
        result.sendResult(Collections.<MediaBrowserCompat.MediaItem>emptyList());
    }

    private void requestRemoteController() {
        Intent request = new Intent(MediaSyncContracts.ACTION_REQUEST_REMOTE_CONTROLLER);
        LocalBroadcastManager.getInstance(this).sendBroadcast(request);
    }

    private void inspectLyricsFromMetadata(MediaMetadataCompat metadata) {
        if (metadata == null) {
            return;
        }
        for (String key : metadata.keySet()) {
            CharSequence text = metadata.getText(key);
            if (text == null) {
                continue;
            }
            String candidate = text.toString();
            if (LyricsHeuristics.keyLooksLikeLyrics(key)
                    || LyricsHeuristics.looksLikeTimedLyrics(candidate)
                    || LyricsHeuristics.looksLikeLyricsPayload(candidate)) {
                acceptLyricsPayload(candidate, "metadata:" + key);
                return;
            }
        }
    }

    private void acceptLyricsPayload(String payload, String source) {
        String normalized = LyricsHeuristics.normalizePayload(payload);
        if (normalized.isEmpty() || normalized.equals(lastLyricsRaw)) {
            return;
        }

        lastLyricsRaw = normalized;
        lyricsSource = source == null ? "" : source;

        parsedLyrics.clear();
        parsedLyrics.addAll(parseLyrics(normalized));

        if (!hasLyrics()) {
            return;
        }

        if (getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(MediaSyncContracts.PREF_AUTO_LYRICS, true)) {
            lyricsEnabled = true;
        }

        String signal = parsedLyrics.isEmpty() ? "已接收静态歌词" : "已接收可同步歌词";
        publishStatus(signal + "，来源 " + (lyricsSource.isEmpty() ? "unknown" : lyricsSource));
        mirror(null, null);
    }

    private List<LyricLine> parseLyrics(String raw) {
        List<LyricLine> result = new ArrayList<>();
        String normalized = LyricsHeuristics.normalizePayload(raw);
        if (normalized.isEmpty()) {
            return result;
        }

        String[] lines = normalized.split("\n");
        for (String line : lines) {
            Matcher krcMatcher = KRC_LINE_PATTERN.matcher(line);
            if (krcMatcher.find()) {
                long startMs = safeParseLong(krcMatcher.group(1));
                String text = LyricsHeuristics.stripTimingMarkup(krcMatcher.group(3));
                if (!text.isEmpty()) {
                    result.add(new LyricLine(startMs, text));
                }
                continue;
            }

            Matcher lrcMatcher = LRC_TAG_PATTERN.matcher(line);
            List<Long> timestamps = new ArrayList<>();
            int lastEnd = 0;
            while (lrcMatcher.find()) {
                timestamps.add(parseLrcTimestamp(lrcMatcher.group(1), lrcMatcher.group(2), lrcMatcher.group(3)));
                lastEnd = lrcMatcher.end();
            }
            if (!timestamps.isEmpty()) {
                String text = line.substring(lastEnd).trim();
                text = LyricsHeuristics.stripTimingMarkup(text);
                if (!text.isEmpty()) {
                    for (Long timestamp : timestamps) {
                        result.add(new LyricLine(timestamp, text));
                    }
                }
            }
        }

        Collections.sort(result, new Comparator<LyricLine>() {
            @Override
            public int compare(LyricLine left, LyricLine right) {
                return Long.compare(left.timeMs, right.timeMs);
            }
        });
        return result;
    }

    private long parseLrcTimestamp(String minutePart, String secondPart, String fractionPart) {
        long minutes = safeParseLong(minutePart);
        long seconds = safeParseLong(secondPart);
        long fraction = safeParseLong(fractionPart);
        if (fractionPart != null) {
            if (fractionPart.length() == 1) {
                fraction *= 100L;
            } else if (fractionPart.length() == 2) {
                fraction *= 10L;
            }
        }
        return (minutes * 60_000L) + (seconds * 1_000L) + fraction;
    }

    private long safeParseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void mirror(MediaMetadataCompat metadata, PlaybackStateCompat state) {
        if (metadata != null) {
            lastRemoteMetadata = metadata;
        }
        if (state != null) {
            lastRemoteState = state;
        }

        session.setMetadata(buildSessionMetadata());
        session.setPlaybackState(buildSessionState());
        updateLyricsTicker();
    }

    private MediaMetadataCompat buildSessionMetadata() {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();
        String originalTitle = "";
        String originalArtist = "";
        if (lastRemoteMetadata != null) {
            originalTitle = safeText(lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_TITLE);
            originalArtist = safeText(lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_ARTIST);
            copyText(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_TITLE);
            copyText(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_ARTIST);
            copyText(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_ALBUM);
            copyText(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST);
            copyText(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE);
            copyText(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE);
            copyText(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION);
            copyText(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
            copyString(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_MEDIA_URI);
            copyString(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_ART_URI);
            copyString(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI);
            copyString(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI);
            copyLong(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_DURATION);
            copyBitmap(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_ART);
            copyBitmap(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
            copyBitmap(builder, lastRemoteMetadata, MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
        } else {
            builder.putText(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    MediaSyncContracts.friendlyName(currentSourcePackage)
            );
        }

        if (lyricsEnabled && hasLyrics()) {
            String primaryLyric = resolveCurrentLyricLine();
            if (!primaryLyric.isEmpty()) {
                String secondaryLyric = resolveSecondaryLyricLine(primaryLyric);
                String subtitle = !originalTitle.isEmpty()
                        ? originalTitle
                        : (!originalArtist.isEmpty() ? originalArtist : secondaryLyric);
                String description = !secondaryLyric.isEmpty()
                        ? secondaryLyric
                        : buildSongContext(originalTitle, originalArtist);

                builder.putText(MediaMetadataCompat.METADATA_KEY_TITLE, primaryLyric);
                builder.putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, primaryLyric);

                if (!subtitle.isEmpty()) {
                    builder.putText(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle);
                    builder.putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle);
                }
                if (!description.isEmpty() && !description.equals(subtitle)) {
                    builder.putText(
                            MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION,
                            description
                    );
                }
            }
        }

        return builder.build();
    }

    private PlaybackStateCompat buildSessionState() {
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder();
        PlaybackStateCompat remoteState = lastRemoteState;
        long position = clockPosition();
        long actions = defaultActions();

        if (remoteState != null) {
            int state = remoteState.getState();
            float speed = remoteState.getPlaybackSpeed();
            if (state == PlaybackStateCompat.STATE_PLAYING && speed == 0f) {
                speed = 1f;
            }
            builder.setState(state, position, speed, SystemClock.elapsedRealtime());
            actions = remoteState.getActions();
            builder.setBufferedPosition(remoteState.getBufferedPosition());
            builder.setActiveQueueItemId(remoteState.getActiveQueueItemId());
            if (remoteState.getErrorMessage() != null) {
                builder.setErrorMessage(remoteState.getErrorMessage());
            }
        } else {
            builder.setState(
                    PlaybackStateCompat.STATE_PAUSED,
                    0L,
                    0f,
                    SystemClock.elapsedRealtime()
            );
        }

        builder.setActions(actions | defaultActions());

        if (hasLyrics()) {
            PlaybackStateCompat.CustomAction customAction =
                    new PlaybackStateCompat.CustomAction.Builder(
                            lyricsEnabled
                                    ? MediaSyncContracts.CUSTOM_ACTION_HIDE_LYRICS
                                    : MediaSyncContracts.CUSTOM_ACTION_SHOW_LYRICS,
                            lyricsEnabled ? "关闭歌词" : "歌词",
                            android.R.drawable.ic_menu_info_details
                    ).build();
            builder.addCustomAction(customAction);
        }

        return builder.build();
    }

    private long defaultActions() {
        return PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_PLAY_PAUSE;
    }

    private void updateLyricsTicker() {
        handler.removeCallbacks(lyricsUpdater);
        if (shouldRunLyricsTicker()) {
            handler.postDelayed(lyricsUpdater, 1000L);
        }
    }

    private boolean shouldRunLyricsTicker() {
        return lyricsEnabled
                && !parsedLyrics.isEmpty()
                && lastRemoteState != null
                && lastRemoteState.getState() == PlaybackStateCompat.STATE_PLAYING;
    }

    private boolean hasLyrics() {
        return !parsedLyrics.isEmpty() || !LyricsHeuristics.firstDisplayLine(lastLyricsRaw).isEmpty();
    }

    private String resolveCurrentLyricLine() {
        if (!parsedLyrics.isEmpty()) {
            long position = clockPosition();
            LyricLine current = parsedLyrics.get(0);
            for (LyricLine line : parsedLyrics) {
                if (line.timeMs <= position) {
                    current = line;
                } else {
                    break;
                }
            }
            return current.text;
        }
        return LyricsHeuristics.firstDisplayLine(lastLyricsRaw);
    }

    private String resolveSecondaryLyricLine(String primaryLyric) {
        if (lastLyricsRaw == null || lastLyricsRaw.isEmpty()) {
            return "";
        }
        String normalizedPrimary = LyricsHeuristics.normalizePayload(primaryLyric);
        if (normalizedPrimary.isEmpty()) {
            return "";
        }
        for (String line : LyricsHeuristics.normalizePayload(lastLyricsRaw).split("\n")) {
            String cleaned = LyricsHeuristics.stripTimingMarkup(line).trim();
            if (cleaned.isEmpty() || cleaned.equals(normalizedPrimary)) {
                continue;
            }
            return cleaned;
        }
        return "";
    }

    private String buildSongContext(String originalTitle, String originalArtist) {
        if (!originalTitle.isEmpty() && !originalArtist.isEmpty()) {
            return originalTitle + " - " + originalArtist;
        }
        return !originalTitle.isEmpty() ? originalTitle : originalArtist;
    }

    private long clockPosition() {
        PlaybackStateCompat state = lastRemoteState;
        if (state == null) {
            return 0L;
        }
        long position = state.getPosition();
        if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
            long delta = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            position += (long) (delta * state.getPlaybackSpeed());
        }
        return Math.max(position, 0L);
    }

    private void publishStatus(String status) {
        if (status == null || status.equals(lastStatus)) {
            return;
        }
        lastStatus = status;

        Intent intent = new Intent(MediaSyncContracts.ACTION_STATUS);
        intent.putExtra(MediaSyncContracts.EXTRA_STATUS, status);
        intent.putExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE, currentSourcePackage);
        intent.putExtra(MediaSyncContracts.EXTRA_HAS_LYRICS, hasLyrics());
        intent.putExtra(MediaSyncContracts.EXTRA_LYRICS_ENABLED, lyricsEnabled);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void copyText(MediaMetadataCompat.Builder builder, MediaMetadataCompat source, String key) {
        CharSequence text = source.getText(key);
        if (text != null) {
            builder.putText(key, text);
        }
    }

    private void copyString(MediaMetadataCompat.Builder builder, MediaMetadataCompat source, String key) {
        String value = source.getString(key);
        if (value != null) {
            builder.putString(key, value);
        }
    }

    private void copyLong(MediaMetadataCompat.Builder builder, MediaMetadataCompat source, String key) {
        if (source.containsKey(key)) {
            builder.putLong(key, source.getLong(key));
        }
    }

    private void copyBitmap(MediaMetadataCompat.Builder builder, MediaMetadataCompat source, String key) {
        Bitmap bitmap = source.getBitmap(key);
        if (bitmap != null) {
            builder.putBitmap(key, bitmap);
        }
    }

    private String safeText(MediaMetadataCompat source, String key) {
        CharSequence text = source.getText(key);
        return text == null ? "" : text.toString();
    }

    private static final class LyricLine {
        final long timeMs;
        final String text;

        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }
}
