package com.example.myapplication;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.myapplication.shared.MediaSyncContracts;

import java.util.ArrayDeque;
import java.util.Iterator;

public class MainActivity extends AppCompatActivity {

    private static final int MAX_DIAGNOSTIC_LINES = 10;
    private static final long IDLE_DIM_DELAY_MS = 10_000L;
    private static final float DIMMED_BRIGHTNESS = 0.02f;

    private MediaControllerCompat remoteCtrl;
    private BroadcastReceiver tokenReceiver;
    private BroadcastReceiver statusReceiver;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Handler keepAwakeHandler = new Handler(Looper.getMainLooper());
    private Runnable progressTicker;
    private int lastPlaybackState = PlaybackStateCompat.STATE_NONE;
    private long lastBasePositionMs = 0L;
    private long lastStateUpdateElapsed = 0L;
    private float lastSpeed = 0f;

    private String currentSourcePackage = MediaSyncContracts.DEFAULT_TARGET_PACKAGE;
    private String latestStatus = "等待捕获酷狗 MediaSession";
    private String latestAccessibilityStatus = "";
    private String latestCandidateStatus = "";
    private boolean hasLyrics = false;
    private boolean lyricsEnabled = true;
    private boolean accessibilityEnabled = false;
    private boolean keepAwakeModeEnabled = false;
    private boolean screenDimmed = false;
    private Button carKeepAwakeButton;
    private final ArrayDeque<String> diagnosticLines = new ArrayDeque<>();
    private final Runnable dimScreenRunnable = new Runnable() {
        @Override
        public void run() {
            dimScreenForCarMode();
        }
    };

    private final MediaControllerCompat.Callback controllerCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            applyMetadata(metadata);
            PlaybackStateCompat state = remoteCtrl != null ? remoteCtrl.getPlaybackState() : null;
            if (state != null) {
                onPlaybackStateChanged(state);
            }
        }

        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            lastPlaybackState = state.getState();
            lastBasePositionMs = state.getPosition();
            lastStateUpdateElapsed = state.getLastPositionUpdateTime();
            lastSpeed = state.getPlaybackSpeed();

            PlaybackControlsFragment controls = findPlaybackFragment();
            if (controls != null) {
                controls.updatePlayPauseButton(state.getState());
                controls.updateProgressTime(resolvePosition());
            }
            updateProgressTicker();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ensureDefaultPrefs();
        if (!isNlEnabled()) {
            promptForNlPermission();
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        registerReceivers();
        setupAccessibilityButton();
        setupCarKeepAwakeButton();
        setupOpenPlayerButton();
        refreshCarKeepAwakeState(false);
        pushDiagnosticLine(latestStatus);
        updateSongInfo();
        requestRemoteController();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessibilityState();
        refreshCarKeepAwakeState(true);
        requestRemoteController();
    }

    @Override
    protected void onPause() {
        cancelCarDimmer();
        restoreNormalBrightness(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelCarDimmer();
        restoreNormalBrightness(false);
        progressHandler.removeCallbacksAndMessages(null);
        keepAwakeHandler.removeCallbacksAndMessages(null);
        if (remoteCtrl != null) {
            remoteCtrl.unregisterCallback(controllerCallback);
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(tokenReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
        super.onDestroy();
    }

    private void ensureDefaultPrefs() {
        if (!getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                .contains(MediaSyncContracts.PREF_TARGET_PACKAGE)) {
            getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(
                            MediaSyncContracts.PREF_TARGET_PACKAGE,
                            MediaSyncContracts.DEFAULT_TARGET_PACKAGE
                    )
                    .putBoolean(MediaSyncContracts.PREF_AUTO_LYRICS, true)
                    .apply();
        }
        if (!getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                .contains(MediaSyncContracts.PREF_KEEP_SCREEN_AWAKE)) {
            getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MediaSyncContracts.PREF_KEEP_SCREEN_AWAKE, false)
                    .apply();
        }
    }

    private void registerReceivers() {
        tokenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                MediaSessionCompat.Token token =
                        intent.getParcelableExtra(MediaSyncContracts.EXTRA_CONTROLLER_TOKEN);
                if (token == null) {
                    return;
                }

                String sourcePackage = intent.getStringExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE);
                if (sourcePackage != null && !sourcePackage.isEmpty()) {
                    if (!sourcePackage.equals(currentSourcePackage)) {
                        hasLyrics = false;
                    }
                    currentSourcePackage = sourcePackage;
                }

                if (remoteCtrl != null) {
                    remoteCtrl.unregisterCallback(controllerCallback);
                }

                remoteCtrl = new MediaControllerCompat(MainActivity.this, token);
                remoteCtrl.registerCallback(controllerCallback);
                MediaControllerCompat.setMediaController(MainActivity.this, remoteCtrl);

                latestStatus = "已连接 " + MediaSyncContracts.friendlyName(currentSourcePackage);
                pushDiagnosticLine(latestStatus);
                updateSongInfo();
                applyMetadata(remoteCtrl.getMetadata());
                PlaybackStateCompat state = remoteCtrl.getPlaybackState();
                if (state != null) {
                    controllerCallback.onPlaybackStateChanged(state);
                }
            }
        };

        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String sourcePackage = intent.getStringExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE);
                String status = intent.getStringExtra(MediaSyncContracts.EXTRA_STATUS);
                if (sourcePackage != null && !sourcePackage.isEmpty()) {
                    if (!sourcePackage.equals(currentSourcePackage)) {
                        hasLyrics = false;
                    }
                    currentSourcePackage = sourcePackage;
                }
                hasLyrics = hasLyrics
                        || intent.getBooleanExtra(MediaSyncContracts.EXTRA_HAS_LYRICS, false);
                lyricsEnabled = intent.getBooleanExtra(
                        MediaSyncContracts.EXTRA_LYRICS_ENABLED,
                        lyricsEnabled
                );
                if (status != null && !status.isEmpty()) {
                    latestStatus = status;
                    if (status.startsWith("无障碍命中")) {
                        latestAccessibilityStatus = status;
                    } else if (status.startsWith("候选 ")) {
                        latestCandidateStatus = status;
                    } else if (status.startsWith("无障碍")
                            || status.contains("未命中歌词")) {
                        latestAccessibilityStatus = status;
                    }
                    pushDiagnosticLine(status);
                    updateSongInfo();
                }
            }
        };

        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.registerReceiver(
                tokenReceiver,
                new IntentFilter(MediaSyncContracts.ACTION_REMOTE_CONTROLLER)
        );
        manager.registerReceiver(
                statusReceiver,
                new IntentFilter(MediaSyncContracts.ACTION_STATUS)
        );
    }

    private void setupOpenPlayerButton() {
        Button openPlayerButton = findViewById(R.id.btn_open_player);
        openPlayerButton.setOnClickListener(view -> openPreferredPlayer());
    }

    private void setupCarKeepAwakeButton() {
        carKeepAwakeButton = findViewById(R.id.btn_car_keep_awake);
        carKeepAwakeButton.setOnClickListener(view -> toggleCarKeepAwakeMode());
    }

    private void setupAccessibilityButton() {
        Button accessibilityButton = findViewById(R.id.btn_accessibility_settings);
        accessibilityButton.setOnClickListener(view -> openAccessibilitySettings());
    }

    private void toggleCarKeepAwakeMode() {
        keepAwakeModeEnabled = !keepAwakeModeEnabled;
        getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(MediaSyncContracts.PREF_KEEP_SCREEN_AWAKE, keepAwakeModeEnabled)
                .apply();
        if (keepAwakeModeEnabled) {
            pushDiagnosticLine("车载常亮已开启，10秒无操作后自动暗屏");
            restoreNormalBrightness(false);
            scheduleCarDimmer();
        } else {
            pushDiagnosticLine("车载常亮已关闭");
            cancelCarDimmer();
            restoreNormalBrightness(true);
        }
        refreshCarKeepAwakeButton();
        updateSongInfo();
    }

    private void openPreferredPlayer() {
        String packageName = getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                .getString(
                        MediaSyncContracts.PREF_TARGET_PACKAGE,
                        MediaSyncContracts.DEFAULT_TARGET_PACKAGE
                );
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            Toast.makeText(
                    this,
                    "未检测到 " + MediaSyncContracts.friendlyName(packageName) + "，请先安装。",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launchIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "启动播放器失败。", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestRemoteController() {
        Intent request = new Intent(MediaSyncContracts.ACTION_REQUEST_REMOTE_CONTROLLER);
        LocalBroadcastManager.getInstance(this).sendBroadcast(request);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (!keepAwakeModeEnabled) {
            return;
        }
        if (screenDimmed) {
            restoreNormalBrightness(true);
        }
        scheduleCarDimmer();
    }

    private void refreshAccessibilityState() {
        accessibilityEnabled = isAccessibilityEnabled();
        pushDiagnosticLine(
                accessibilityEnabled
                        ? "无障碍歌词抓取已启用"
                        : "请启用“酷狗歌词抓取”无障碍服务"
        );
        updateSongInfo();
    }

    private void refreshCarKeepAwakeState(boolean scheduleTimer) {
        keepAwakeModeEnabled = getSharedPreferences(MediaSyncContracts.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(MediaSyncContracts.PREF_KEEP_SCREEN_AWAKE, false);
        refreshCarKeepAwakeButton();
        if (keepAwakeModeEnabled) {
            if (scheduleTimer) {
                scheduleCarDimmer();
            }
        } else {
            cancelCarDimmer();
            restoreNormalBrightness(false);
        }
        updateSongInfo();
    }

    private void refreshCarKeepAwakeButton() {
        if (carKeepAwakeButton == null) {
            return;
        }
        carKeepAwakeButton.setText(
                keepAwakeModeEnabled
                        ? R.string.disable_car_keep_awake_button
                        : R.string.enable_car_keep_awake_button
        );
    }

    private void scheduleCarDimmer() {
        if (!keepAwakeModeEnabled) {
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        keepAwakeHandler.removeCallbacks(dimScreenRunnable);
        keepAwakeHandler.postDelayed(dimScreenRunnable, IDLE_DIM_DELAY_MS);
    }

    private void cancelCarDimmer() {
        keepAwakeHandler.removeCallbacks(dimScreenRunnable);
    }

    private void dimScreenForCarMode() {
        if (!keepAwakeModeEnabled || isFinishing()) {
            return;
        }
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = DIMMED_BRIGHTNESS;
        getWindow().setAttributes(params);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!screenDimmed) {
            screenDimmed = true;
            pushDiagnosticLine("车载常亮已暗屏，触摸屏幕恢复亮度");
            updateSongInfo();
        }
    }

    private void restoreNormalBrightness(boolean reportStatus) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (screenDimmed && reportStatus) {
            pushDiagnosticLine("车载常亮已恢复亮度");
        }
        screenDimmed = false;
        if (reportStatus) {
            updateSongInfo();
        }
    }

    private boolean isNlEnabled() {
        String pkg = getPackageName();
        String flat = new ComponentName(pkg, QqSessionSniffer.class.getName()).flattenToString();
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                "enabled_notification_listeners"
        );
        return enabled != null && enabled.contains(flat);
    }

    private void promptForNlPermission() {
        new AlertDialog.Builder(this)
                .setTitle("启用通知读取权限")
                .setMessage("请在接下来的页面勾选本应用，否则无法读取播放器的媒体会话和歌词线索。")
                .setPositiveButton("去授权", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        String flat = new ComponentName(
                this,
                KugouLyricsAccessibilityService.class
        ).flattenToString();
        return enabled.contains(flat);
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    private void applyMetadata(MediaMetadataCompat metadata) {
        if (metadata == null) {
            return;
        }

        PlaybackControlsFragment controls = findPlaybackFragment();
        if (controls != null) {
            controls.updateTitle(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
            controls.updateArtist(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
            controls.updateTotalTime(
                    metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            );
        }

        Bitmap cover = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
        if (cover == null) {
            cover = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
        }
        if (cover == null) {
            cover = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
        }

        AlbumCoverFragment albumCoverFragment = findAlbumCoverFragment();
        if (albumCoverFragment != null && cover != null) {
            albumCoverFragment.updateCover(cover);
        }
    }

    private void updateProgressTicker() {
        progressHandler.removeCallbacksAndMessages(null);
        if (lastPlaybackState != PlaybackStateCompat.STATE_PLAYING) {
            return;
        }
        progressTicker = new Runnable() {
            @Override
            public void run() {
                PlaybackControlsFragment controls = findPlaybackFragment();
                if (controls != null) {
                    controls.updateProgressTime(resolvePosition());
                }
                if (lastPlaybackState == PlaybackStateCompat.STATE_PLAYING) {
                    progressHandler.postDelayed(this, 1000L);
                }
            }
        };
        progressHandler.post(progressTicker);
    }

    private long resolvePosition() {
        long position = lastBasePositionMs;
        if (lastPlaybackState == PlaybackStateCompat.STATE_PLAYING) {
            long updateBase = lastStateUpdateElapsed > 0L
                    ? lastStateUpdateElapsed
                    : SystemClock.elapsedRealtime();
            long delta = SystemClock.elapsedRealtime() - updateBase;
            position += (long) (delta * (lastSpeed == 0f ? 1f : lastSpeed));
        }
        return Math.max(position, 0L);
    }

    private void updateSongInfo() {
        PlaybackControlsFragment controls = findPlaybackFragment();
        if (controls == null) {
            return;
        }
        StringBuilder info = new StringBuilder();
        info.append("来源：")
                .append(MediaSyncContracts.friendlyName(currentSourcePackage))
                .append('\n');
        info.append("无障碍：")
                .append(accessibilityEnabled ? "已开启" : "未开启")
                .append('\n');
        info.append("常亮：");
        if (!keepAwakeModeEnabled) {
            info.append("未开启");
        } else {
            info.append(screenDimmed ? "已暗屏" : "监控中");
        }
        info.append('\n');
        info.append("歌词：")
                .append(hasLyrics ? "已捕获" : "未捕获");
        if (hasLyrics) {
            info.append(lyricsEnabled ? "（已开启）" : "（已关闭）");
        }
        if (!latestAccessibilityStatus.isEmpty()) {
            info.append('\n').append(latestAccessibilityStatus);
        }
        if (!latestCandidateStatus.isEmpty()) {
            info.append('\n').append(latestCandidateStatus);
        }

        if (!diagnosticLines.isEmpty()) {
            Iterator<String> iterator = diagnosticLines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.equals(latestAccessibilityStatus) || line.equals(latestCandidateStatus)) {
                    continue;
                }
                info.append('\n').append(line);
            }
        } else if (latestStatus != null && !latestStatus.isEmpty()) {
            info.append('\n').append(latestStatus);
        }
        controls.updateSongInfo(info.toString());
    }

    private void pushDiagnosticLine(String line) {
        if (line == null) {
            return;
        }
        String normalized = line.trim();
        if (normalized.isEmpty()) {
            return;
        }
        if (!diagnosticLines.isEmpty() && normalized.equals(diagnosticLines.peekFirst())) {
            return;
        }
        diagnosticLines.addFirst(normalized);
        while (diagnosticLines.size() > MAX_DIAGNOSTIC_LINES) {
            diagnosticLines.removeLast();
        }
    }

    private PlaybackControlsFragment findPlaybackFragment() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.playbackControlsFragment);
        return fragment instanceof PlaybackControlsFragment
                ? (PlaybackControlsFragment) fragment
                : null;
    }

    private AlbumCoverFragment findAlbumCoverFragment() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.playerAlbumCoverFragment);
        return fragment instanceof AlbumCoverFragment
                ? (AlbumCoverFragment) fragment
                : null;
    }
}
