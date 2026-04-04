package com.example.myapplication;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.myapplication.shared.LyricsHeuristics;
import com.example.myapplication.shared.MediaSyncContracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class KugouLyricsAccessibilityService extends AccessibilityService {

    private static final String TARGET_PACKAGE = MediaSyncContracts.DEFAULT_TARGET_PACKAGE;
    private static final long TRACK_REFRESH_WINDOW_MS = 1200L;
    private static final long SCAN_DEBOUNCE_MS = 60L;
    private static final long POLL_INTERVAL_MS = 350L;
    private static final long LYRIC_SIGNAL_WINDOW_MS = 5000L;
    private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}$");
    private static final Pattern LETTER_OR_HAN_PATTERN = Pattern.compile(".*[A-Za-z\\p{IsHan}].*");
    private static final String[] OVERLAY_PACKAGES = {
            "com.sec.android.app.launcher",
            "com.android.systemui"
    };

    private static final String[] HOME_MARKERS = {
            "最近播放", "歌单", "我喜欢", "本地/云盘", "关注", "音乐", "听书", "已购", "全部"
    };
    private static final String[] IGNORE_EXACT = {
            "播放", "暂停", "下一首", "上一首", "分享", "收藏", "评论", "更多", "下载", "词", "曲", "歌词"
    };
    private static final String[] IGNORE_CONTAINS = {
            "控制界面", "进入控制", "控制器", "controller", "controls", "播放页", "播放控制"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scanVisibleKugouWindows();
        }
    };
    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            scanVisibleKugouWindows();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (!TARGET_PACKAGE.equals(intent.getStringExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE))) {
                return;
            }
            String status = intent.getStringExtra(MediaSyncContracts.EXTRA_STATUS);
            if (!looksLikeLyricSignal(status)) {
                return;
            }
            lastLyricSignalAt = SystemClock.elapsedRealtime();
            scheduleScan();
        }
    };

    private String lastPublishedLyric = "";
    private String lastStatus = "";
    private String lastNoMatchSignature = "";
    private String currentTitle = "";
    private String currentArtist = "";
    private long lastTrackRefreshAt = 0L;
    private long lastLyricSignalAt = 0L;

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = new String[]{TARGET_PACKAGE};
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
                statusReceiver,
                new IntentFilter(MediaSyncContracts.ACTION_STATUS)
        );
        broadcastStatus("无障碍歌词抓取已连接");
        scheduleScan();
        schedulePolling();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (!matchesTargetPackage(packageName) && !hasVisibleKugouWindow()) {
            return;
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            scheduleScan();
            schedulePolling();
        }
    }

    @Override
    public void onInterrupt() {
        broadcastStatus("无障碍歌词抓取被系统中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
        broadcastStatus("无障碍歌词抓取已断开");
        return super.onUnbind(intent);
    }

    private void scheduleScan() {
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS);
    }

    private void schedulePolling() {
        handler.removeCallbacks(pollingRunnable);
        handler.post(pollingRunnable);
    }

    private boolean matchesTargetPackage(CharSequence packageName) {
        return packageName != null && TARGET_PACKAGE.contentEquals(packageName);
    }

    private boolean hasVisibleKugouWindow() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            return false;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && matchesTargetPackage(root.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void scanVisibleKugouWindows() {
        refreshTrackContextIfNeeded();

        List<AccessibilityNodeInfo> roots = collectTargetRoots();
        boolean scanningOverlayRoots = false;
        if (roots.isEmpty() && shouldInspectOverlayRoots()) {
            roots = collectOverlayRoots();
            scanningOverlayRoots = !roots.isEmpty();
        }
        if (roots.isEmpty()) {
            String windows = describeVisibleWindows();
            String status;
            if (shouldInspectOverlayRoots()) {
                status = windows.isEmpty()
                        ? "无障碍已收到歌词状态，但还没看到歌词浮层窗口"
                        : "无障碍已收到歌词状态，但未见歌词浮层窗口，当前窗口 " + windows;
            } else {
                status = windows.isEmpty()
                        ? "无障碍等待可见的酷狗歌词窗口"
                        : "无障碍未见酷狗窗口，当前窗口 " + windows;
            }
            reportNoMatch(status, "no-window:" + windows);
            return;
        }

        List<NodeText> nodes = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots) {
            Rect windowBounds = new Rect();
            root.getBoundsInScreen(windowBounds);
            collectNodeTexts(root, 0, windowBounds, nodes);
        }
        if (nodes.isEmpty()) {
            reportNoMatch("无障碍已连接，但还没读到酷狗界面文本", "no-text");
            return;
        }

        if (scanningOverlayRoots) {
            nodes = filterOverlayNodes(nodes);
            if (nodes.isEmpty()) {
                reportNoMatch(
                        "无障碍看到了歌词浮层窗口，但还没读到歌词文本",
                        "overlay-no-text:" + describeVisibleWindows()
                );
                return;
            }
        }

        if (looksLikeHomeScreen(nodes) && !hasLyricHint(nodes)) {
            reportNoMatch(
                    "无障碍当前在酷狗首页，请点底部播放器进入正在播放页",
                    signatureFor(nodes)
            );
            return;
        }

        NodeText candidate = chooseBestCandidate(nodes);
        if (candidate == null) {
            reportNoMatch(buildNoMatchStatus(nodes), signatureFor(nodes));
            return;
        }

        publishLyric(
                buildLyricPayload(candidate, nodes),
                candidate,
                summarizeTopCandidates(nodes)
        );
    }

    private List<AccessibilityNodeInfo> collectTargetRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        Set<Integer> identities = new HashSet<>();

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null || !matchesTargetPackage(root.getPackageName())) {
                    continue;
                }
                int identity = System.identityHashCode(root);
                if (identities.add(identity)) {
                    roots.add(root);
                }
            }
        }

        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null && matchesTargetPackage(activeRoot.getPackageName())) {
            int identity = System.identityHashCode(activeRoot);
            if (identities.add(identity)) {
                roots.add(activeRoot);
            }
        }
        return roots;
    }

    private List<AccessibilityNodeInfo> collectOverlayRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        Set<Integer> identities = new HashSet<>();

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            return roots;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !matchesOverlayPackage(root.getPackageName())) {
                continue;
            }
            int identity = System.identityHashCode(root);
            if (identities.add(identity)) {
                roots.add(root);
            }
        }
        return roots;
    }

    private String describeVisibleWindows() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                continue;
            }
            CharSequence packageName = root.getPackageName();
            String label = packageName == null ? "(unknown)" : packageName.toString();
            if (!seen.add(label)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(label);
            if (seen.size() >= 4) {
                break;
            }
        }
        return builder.toString();
    }

    private boolean matchesOverlayPackage(CharSequence packageName) {
        if (packageName == null) {
            return false;
        }
        for (String candidate : OVERLAY_PACKAGES) {
            if (candidate.contentEquals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldInspectOverlayRoots() {
        return SystemClock.elapsedRealtime() - lastLyricSignalAt <= LYRIC_SIGNAL_WINDOW_MS;
    }

    private boolean looksLikeLyricSignal(String status) {
        if (status == null || status.isEmpty()) {
            return false;
        }
        return status.contains("EVENT_LYRIC_STATE_CHANGED")
                || status.contains("EVENT_LYLIC_STATE_CHANGED")
                || status.contains("KEY_LYRIC_STATE")
                || status.contains("KEY_LYRIC_LOCK_STATE");
    }

    private void collectNodeTexts(
            AccessibilityNodeInfo node,
            int depth,
            Rect windowBounds,
            List<NodeText> out
    ) {
        if (node == null || depth > 10 || out.size() >= 240) {
            return;
        }

        addCandidateText(
                out,
                node.getText(),
                node,
                depth,
                windowBounds
        );
        addCandidateText(
                out,
                node.getContentDescription(),
                node,
                depth,
                windowBounds
        );

        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                collectNodeTexts(child, depth + 1, windowBounds, out);
            }
        }
    }

    private void addCandidateText(
            List<NodeText> out,
            CharSequence raw,
            AccessibilityNodeInfo node,
            int depth,
            Rect windowBounds
    ) {
        String text = normalizeLine(raw);
        if (text.isEmpty()) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        out.add(new NodeText(
                text,
                node.getViewIdResourceName(),
                node.getClassName() != null ? node.getClassName().toString() : "",
                bounds,
                new Rect(windowBounds),
                node.isClickable(),
                node.isFocusable(),
                depth
        ));
    }

    private NodeText chooseBestCandidate(List<NodeText> nodes) {
        NodeText best = null;
        int bestScore = Integer.MIN_VALUE;
        for (NodeText node : nodes) {
            int score = scoreNode(node);
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return bestScore >= 180 ? best : null;
    }

    private int scoreNode(NodeText node) {
        String normalized = flattenForComparison(node.text);
        if (normalized.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        if (!LETTER_OR_HAN_PATTERN.matcher(normalized).matches()) {
            return Integer.MIN_VALUE;
        }
        if (TIME_PATTERN.matcher(normalized).matches()) {
            return Integer.MIN_VALUE;
        }
        if (isIgnoredPhrase(normalized)) {
            return Integer.MIN_VALUE;
        }
        if (flattenForComparison(currentTitle).equals(normalized)
                || flattenForComparison(currentArtist).equals(normalized)) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        String viewId = safeLower(node.viewId);
        String className = safeLower(node.className);

        if (viewId.contains("lyric") || viewId.contains("lrc") || viewId.contains("krc")) {
            score += 6000;
        }
        if (LyricsHeuristics.looksLikeLyricsPayload(normalized)) {
            score += 2000;
        }
        if (node.text.contains("\n")) {
            score += 1200;
        }
        if (node.text.equals(lastPublishedLyric)) {
            score -= 180;
        }
        if (normalized.length() >= 2 && normalized.length() <= 32) {
            score += 180;
        } else if (normalized.length() <= 72) {
            score += 80;
        } else if (normalized.length() <= 120) {
            score -= 80;
        } else {
            score -= 260;
        }
        if (node.clickable || node.focusable) {
            score -= 120;
        } else {
            score += 80;
        }
        if (className.contains("textview")) {
            score += 220;
        }
        if (className.contains("button")) {
            score -= 1200;
        } else if (className.contains("image")) {
            score -= 700;
        } else if (className.contains("layout") || className.contains("viewgroup")) {
            score -= 260;
        }
        if (className.contains("edit")) {
            score -= 800;
        }
        if (viewId.contains("control")
                || viewId.contains("controller")
                || viewId.contains("button")
                || viewId.contains("btn")) {
            score -= 900;
        }
        if (viewId.contains("title")
                || viewId.contains("song")
                || viewId.contains("name")
                || viewId.contains("artist")
                || viewId.contains("singer")
                || viewId.contains("album")) {
            score -= 900;
        }
        score += scorePosition(node);
        return score;
    }

    private int scorePosition(NodeText node) {
        Rect windowBounds = node.windowBounds;
        Rect bounds = node.bounds;
        if (windowBounds.isEmpty() || bounds.isEmpty()) {
            return 0;
        }
        int score = 0;
        int centerOffset = Math.abs(bounds.centerX() - windowBounds.centerX());
        if (centerOffset <= Math.max(windowBounds.width() / 5, 1)) {
            score += 140;
        }
        float verticalRatio = (bounds.centerY() - windowBounds.top)
                / (float) Math.max(windowBounds.height(), 1);
        if (verticalRatio >= 0.03f && verticalRatio <= 0.22f) {
            score += 90;
        }
        if (verticalRatio >= 0.22f && verticalRatio <= 0.78f) {
            score += 120;
        }
        if (node.depth >= 4) {
            score += 50;
        }
        return score;
    }

    private boolean looksLikeHomeScreen(List<NodeText> nodes) {
        int markers = 0;
        for (NodeText node : nodes) {
            String text = node.text;
            for (String marker : HOME_MARKERS) {
                if (text.contains(marker)) {
                    markers++;
                    break;
                }
            }
            if (markers >= 3) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLyricHint(List<NodeText> nodes) {
        for (NodeText node : nodes) {
            String viewId = safeLower(node.viewId);
            if (viewId.contains("lyric") || viewId.contains("lrc") || viewId.contains("krc")) {
                return true;
            }
            if (LyricsHeuristics.looksLikeLyricsPayload(node.text)) {
                return true;
            }
        }
        return false;
    }

    private List<NodeText> filterOverlayNodes(List<NodeText> nodes) {
        List<NodeText> filtered = new ArrayList<>();
        for (NodeText node : nodes) {
            if (isLikelyOverlayLyricNode(node)) {
                filtered.add(node);
            }
        }
        return filtered;
    }

    private boolean isLikelyOverlayLyricNode(NodeText node) {
        String normalized = flattenForComparison(node.text);
        if (normalized.isEmpty()
                || isIgnoredPhrase(normalized)
                || TIME_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        if (flattenForComparison(currentTitle).equals(normalized)
                || flattenForComparison(currentArtist).equals(normalized)) {
            return false;
        }
        if (node.clickable || node.focusable) {
            return false;
        }

        String className = safeLower(node.className);
        if (className.contains("button")
                || className.contains("image")
                || className.contains("edit")) {
            return false;
        }

        Rect windowBounds = node.windowBounds;
        Rect bounds = node.bounds;
        if (windowBounds.isEmpty() || bounds.isEmpty()) {
            return false;
        }
        float verticalRatio = (bounds.centerY() - windowBounds.top)
                / (float) Math.max(windowBounds.height(), 1);
        if (verticalRatio < 0.0f || verticalRatio > 0.42f) {
            return false;
        }
        int centerOffset = Math.abs(bounds.centerX() - windowBounds.centerX());
        if (centerOffset > Math.max(windowBounds.width() / 2, 1)) {
            return false;
        }
        return LETTER_OR_HAN_PATTERN.matcher(normalized).matches();
    }

    private String buildNoMatchStatus(List<NodeText> nodes) {
        StringBuilder builder = new StringBuilder("无障碍未命中歌词");
        String samples = summarizeVisibleTexts(nodes);
        if (!samples.isEmpty()) {
            builder.append("，看到 ").append(samples);
        } else {
            int appended = 0;
            for (NodeText node : nodes) {
                String viewId = node.viewId;
                if (viewId == null || viewId.isEmpty()) {
                    continue;
                }
                if (appended == 0) {
                    builder.append("，节点 ");
                } else {
                    builder.append(", ");
                }
                builder.append(shortId(viewId));
                appended++;
                if (appended >= 3) {
                    break;
                }
            }
        }
        String candidates = summarizeTopCandidates(nodes);
        if (!candidates.isEmpty()) {
            builder.append("，候选 ").append(candidates);
        }
        return builder.toString();
    }

    private String summarizeVisibleTexts(List<NodeText> nodes) {
        StringBuilder builder = new StringBuilder();
        int appended = 0;
        Set<String> seen = new HashSet<>();
        for (NodeText node : nodes) {
            String normalized = flattenForComparison(node.text);
            if (normalized.isEmpty()) {
                continue;
            }
            if (isIgnoredPhrase(normalized)) {
                continue;
            }
            if (flattenForComparison(currentTitle).equals(normalized)
                    || flattenForComparison(currentArtist).equals(normalized)) {
                continue;
            }
            if (!seen.add(normalized)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(clip(normalized, 12));
            appended++;
            if (appended >= 3) {
                break;
            }
        }
        return builder.toString();
    }

    private void reportNoMatch(String status, String signature) {
        if (TextUtils.equals(lastNoMatchSignature, signature)) {
            return;
        }
        lastNoMatchSignature = signature;
        broadcastStatus(status);
    }

    private String summarizeTopCandidates(List<NodeText> nodes) {
        List<CandidateScore> candidates = new ArrayList<>();
        for (NodeText node : nodes) {
            int score = scoreNode(node);
            if (score == Integer.MIN_VALUE) {
                continue;
            }
            candidates.add(new CandidateScore(node, score));
        }
        if (candidates.isEmpty()) {
            return "";
        }
        Collections.sort(candidates, new Comparator<CandidateScore>() {
            @Override
            public int compare(CandidateScore left, CandidateScore right) {
                return Integer.compare(right.score, left.score);
            }
        });
        StringBuilder builder = new StringBuilder();
        int appended = 0;
        for (CandidateScore candidate : candidates) {
            if (appended > 0) {
                builder.append(" | ");
            }
            builder.append(candidate.node.describe())
                    .append('#')
                    .append(candidate.score);
            appended++;
            if (appended >= 3) {
                break;
            }
        }
        return builder.toString();
    }

    private String signatureFor(List<NodeText> nodes) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (NodeText node : nodes) {
            builder.append(node.text);
            if (node.viewId != null) {
                builder.append('@').append(shortId(node.viewId));
            }
            builder.append('|');
            count++;
            if (count >= 8) {
                break;
            }
        }
        return builder.toString();
    }

    private void publishLyric(String lyric, NodeText candidate, String candidates) {
        String normalized = LyricsHeuristics.normalizePayload(lyric);
        if (normalized.isEmpty() || normalized.equals(lastPublishedLyric)) {
            return;
        }
        lastPublishedLyric = normalized;
        lastNoMatchSignature = "";

        Intent intent = new Intent(MediaSyncContracts.ACTION_LYRICS_PAYLOAD);
        intent.putExtra(MediaSyncContracts.EXTRA_LYRICS, normalized);
        intent.putExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE, TARGET_PACKAGE);
        intent.putExtra(
                MediaSyncContracts.EXTRA_LYRICS_SOURCE,
                "accessibility:" + (candidate == null ? "text" : candidate.describe())
        );
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        String hitLabel = candidate == null ? "text" : candidate.shortDebug();
        broadcastStatus("无障碍命中 " + hitLabel + " -> " + clip(flattenForComparison(normalized), 28));
        if (candidates != null && !candidates.isEmpty()) {
            broadcastStatus("候选 " + candidates);
        }
    }

    private void broadcastStatus(String status) {
        if (status == null || status.equals(lastStatus)) {
            return;
        }
        lastStatus = status;
        Intent intent = new Intent(MediaSyncContracts.ACTION_STATUS);
        intent.putExtra(MediaSyncContracts.EXTRA_STATUS, status);
        intent.putExtra(MediaSyncContracts.EXTRA_SOURCE_PACKAGE, TARGET_PACKAGE);
        intent.putExtra(MediaSyncContracts.EXTRA_HAS_LYRICS, !lastPublishedLyric.isEmpty());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void refreshTrackContextIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTrackRefreshAt < TRACK_REFRESH_WINDOW_MS) {
            return;
        }
        lastTrackRefreshAt = now;

        currentTitle = "";
        currentArtist = "";
        MediaSessionManager manager =
                (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            List<MediaController> controllers = manager.getActiveSessions(
                    new ComponentName(this, QqSessionSniffer.class)
            );
            if (controllers == null) {
                return;
            }
            for (MediaController controller : controllers) {
                if (controller == null || !TARGET_PACKAGE.equals(controller.getPackageName())) {
                    continue;
                }
                MediaMetadata metadata = controller.getMetadata();
                if (metadata == null) {
                    continue;
                }
                CharSequence title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
                CharSequence artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
                currentTitle = title != null ? title.toString() : "";
                currentArtist = artist != null ? artist.toString() : "";
                return;
            }
        } catch (SecurityException ignored) {
            // Notification listener permission gating lives in the main flow.
        }
    }

    private boolean isIgnoredPhrase(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        String normalized = flattenForComparison(text);
        for (String ignored : IGNORE_EXACT) {
            if (ignored.equals(normalized)) {
                return true;
            }
        }
        String lower = normalized.toLowerCase(Locale.US);
        for (String ignored : IGNORE_CONTAINS) {
            if (normalized.contains(ignored) || lower.contains(ignored)) {
                return true;
            }
        }
        if (normalized.startsWith("来源：")
                || normalized.startsWith("歌词：")
                || normalized.startsWith("已连接")
                || normalized.startsWith("打开酷狗")
                || normalized.startsWith("无障碍")
                || normalized.startsWith("请启用")) {
            return true;
        }
        if (normalized.length() == 1) {
            return true;
        }
        return false;
    }

    private String shortId(String viewId) {
        if (viewId == null || viewId.isEmpty()) {
            return "";
        }
        int slash = viewId.lastIndexOf('/');
        return slash >= 0 && slash < viewId.length() - 1
                ? viewId.substring(slash + 1)
                : viewId;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private String normalizeLine(CharSequence raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.toString()
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String[] lines = normalized.split("\n");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String cleaned = line.replaceAll("\\s+", " ").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(cleaned);
        }
        return builder.toString();
    }

    private String flattenForComparison(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\n', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String buildLyricPayload(NodeText candidate, List<NodeText> nodes) {
        if (candidate.text.contains("\n")) {
            return normalizePayloadLines(candidate.text);
        }

        NodeText companion = null;
        int companionScore = Integer.MIN_VALUE;
        for (NodeText node : nodes) {
            if (node == candidate) {
                continue;
            }
            if (!shouldMergeWithCandidate(candidate, node)) {
                continue;
            }
            int score = scoreCompanion(candidate, node);
            if (score > companionScore) {
                companionScore = score;
                companion = node;
            }
        }

        String primary = normalizePayloadLines(candidate.text);
        if (companion == null) {
            return primary;
        }

        StringBuilder builder = new StringBuilder();
        Set<String> seen = new HashSet<>();
        if (!primary.isEmpty()) {
            builder.append(primary);
            seen.add(primary);
        }

        String secondary = normalizePayloadLines(companion.text);
        if (!secondary.isEmpty() && seen.add(secondary)) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(secondary);
        }
        return builder.length() == 0 ? primary : builder.toString();
    }

    private boolean shouldMergeWithCandidate(NodeText candidate, NodeText other) {
        String otherText = flattenForComparison(other.text);
        if (otherText.isEmpty() || isIgnoredPhrase(other.text)) {
            return false;
        }
        if (flattenForComparison(currentTitle).equals(otherText)
                || flattenForComparison(currentArtist).equals(otherText)) {
            return false;
        }
        if (TIME_PATTERN.matcher(otherText).matches()) {
            return false;
        }
        Rect base = candidate.bounds;
        Rect compare = other.bounds;
        if (base.isEmpty() || compare.isEmpty()) {
            return false;
        }
        int horizontalDistance = Math.abs(base.centerX() - compare.centerX());
        int verticalDistance = Math.abs(base.centerY() - compare.centerY());
        int widthAllowance = Math.max(candidate.windowBounds.width() / 3, 1);
        int heightAllowance = Math.max(candidate.windowBounds.height() / 5, 1);
        if (horizontalDistance > widthAllowance || verticalDistance > heightAllowance) {
            return false;
        }
        return !other.clickable
                && !other.focusable
                && Math.abs(other.depth - candidate.depth) <= 2;
    }

    private int scoreCompanion(NodeText candidate, NodeText other) {
        int score = 0;
        int verticalDistance = Math.abs(candidate.bounds.centerY() - other.bounds.centerY());
        int horizontalDistance = Math.abs(candidate.bounds.centerX() - other.bounds.centerX());
        if (other.bounds.centerY() >= candidate.bounds.centerY()) {
            score += 400;
        }
        score -= verticalDistance;
        score -= horizontalDistance / 2;
        return score;
    }

    private String normalizePayloadLines(String raw) {
        List<String> lines = LyricsHeuristics.displayLines(raw);
        if (lines.isEmpty()) {
            return normalizeLine(raw);
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String clip(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(maxChars - 1, 1)) + "…";
    }

    private static final class NodeText {
        private final String text;
        private final String viewId;
        private final String className;
        private final Rect bounds;
        private final Rect windowBounds;
        private final boolean clickable;
        private final boolean focusable;
        private final int depth;

        private NodeText(
                String text,
                String viewId,
                String className,
                Rect bounds,
                Rect windowBounds,
                boolean clickable,
                boolean focusable,
                int depth
        ) {
            this.text = text;
            this.viewId = viewId;
            this.className = className;
            this.bounds = bounds;
            this.windowBounds = windowBounds;
            this.clickable = clickable;
            this.focusable = focusable;
            this.depth = depth;
        }

        private String describe() {
            String id = viewId == null || viewId.isEmpty() ? "text" : viewId;
            return shortLabel(id) + ":" + text;
        }

        private String shortDebug() {
            String id = viewId == null || viewId.isEmpty() ? "text" : shortLabel(viewId);
            return id + ":" + shortClip(text.replace('\n', '/'), 20);
        }

        private String shortLabel(String raw) {
            int slash = raw.lastIndexOf('/');
            return slash >= 0 && slash < raw.length() - 1
                    ? raw.substring(slash + 1)
                    : raw;
        }

        private String shortClip(String value, int maxChars) {
            if (value == null || value.length() <= maxChars) {
                return value == null ? "" : value;
            }
            return value.substring(0, Math.max(maxChars - 1, 1)) + "…";
        }
    }

    private static final class CandidateScore {
        private final NodeText node;
        private final int score;

        private CandidateScore(NodeText node, int score) {
            this.node = node;
            this.score = score;
        }
    }
}
