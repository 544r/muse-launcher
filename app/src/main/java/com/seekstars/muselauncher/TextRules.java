package com.seekstars.muselauncher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按扩展字素簇截断，不拆开代理对、肤色或 ZWJ Emoji。 */
final class TextRules {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private TextRules() {}

    static String limit(String text, int count) {
        if (text == null || count <= 0) return "";
        Matcher matcher = GRAPHEME.matcher(text);
        int end = 0;
        while (count-- > 0 && matcher.find()) end = matcher.end();
        return text.substring(0, end);
    }

    static boolean hasSession(String cookies) {
        if (cookies == null) return false;
        for (String cookie : cookies.split(";")) {
            int eq = cookie.indexOf('=');
            if (eq > 0 && "hatch_sess".equals(cookie.substring(0, eq).trim())
                    && !cookie.substring(eq + 1).trim().isEmpty()) return true;
        }
        return false;
    }
}
