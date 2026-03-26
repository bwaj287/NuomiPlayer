package com.example.myapplication.shared;

import java.util.Locale;
import java.util.regex.Pattern;

public final class LyricsHeuristics {

    private static final Pattern LRC_TIME_TAG =
            Pattern.compile("\\[(\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?)]");
    private static final Pattern KRC_TIME_TAG =
            Pattern.compile("\\[(\\d{1,7}),(\\d{1,7})]");
    private static final Pattern KRC_WORD_TAG =
            Pattern.compile("<\\d+,\\d+,\\d+>");
    private static final Pattern META_LINE_TAG =
            Pattern.compile("^\\[(?:ti|ar|al|by|offset):.*]$", Pattern.CASE_INSENSITIVE);

    private LyricsHeuristics() {
    }

    public static boolean keyLooksLikeLyrics(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.US);
        return normalized.contains("lyric")
                || normalized.contains("lyrics")
                || normalized.contains("lrc")
                || normalized.contains("krc");
    }

    public static boolean looksLikeTimedLyrics(CharSequence raw) {
        if (raw == null) {
            return false;
        }
        String text = normalizePayload(raw.toString());
        return LRC_TIME_TAG.matcher(text).find() || KRC_TIME_TAG.matcher(text).find();
    }

    public static boolean looksLikeLyricsPayload(CharSequence raw) {
        if (raw == null) {
            return false;
        }
        String text = normalizePayload(raw.toString());
        if (text.length() < 8) {
            return false;
        }
        if (looksLikeTimedLyrics(text)) {
            return true;
        }
        int lineCount = 0;
        for (String line : text.split("\n")) {
            String cleaned = stripTimingMarkup(line).trim();
            if (!cleaned.isEmpty() && !META_LINE_TAG.matcher(cleaned).matches()) {
                lineCount++;
            }
        }
        return lineCount >= 3 && text.contains("\n");
    }

    public static String normalizePayload(String payload) {
        if (payload == null) {
            return "";
        }
        return payload.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    public static String stripTimingMarkup(String line) {
        if (line == null) {
            return "";
        }
        String cleaned = line;
        cleaned = cleaned.replaceAll("^\\[(?:\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?)]", "");
        cleaned = cleaned.replaceAll("^\\[(?:\\d{1,7}),(?:\\d{1,7})]", "");
        cleaned = KRC_WORD_TAG.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    public static String firstDisplayLine(String payload) {
        String normalized = normalizePayload(payload);
        if (normalized.isEmpty()) {
            return "";
        }
        for (String line : normalized.split("\n")) {
            String cleaned = stripTimingMarkup(line).trim();
            if (!cleaned.isEmpty() && !META_LINE_TAG.matcher(cleaned).matches()) {
                return cleaned;
            }
        }
        return "";
    }
}
