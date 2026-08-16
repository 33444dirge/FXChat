package com.dirges.fxchat.bukkit.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageColorParser {
    private static final Pattern AMP_HEX = Pattern.compile("(?i)[&\\u00a7]#([0-9a-f]{6})");
    private static final Pattern BRACED_AMP_HEX = Pattern.compile("(?i)[&\\u00a7]\\{#([0-9a-f]{6})}");
    private static final Pattern BRACED_HEX = Pattern.compile("(?i)\\{#([0-9a-f]{6})}");

    private MessageColorParser() {
    }

    public static String convert(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = replaceLegacyHex(input);
        result = replace(result, BRACED_AMP_HEX);
        result = replace(result, AMP_HEX);
        result = replace(result, BRACED_HEX);
        return replaceLegacyCodes(result);
    }

    public static String neutralizeSectionSigns(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.replace('\u00A7', '&');
    }

    private static String replaceLegacyHex(String input) {
        StringBuilder result = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            if (index + 13 < input.length()
                    && isLegacyMarker(input.charAt(index))
                    && Character.toLowerCase(input.charAt(index + 1)) == 'x'
                    && isLegacyHexSequence(input, index)) {
                result.append("<#");
                for (int offset = 3; offset <= 13; offset += 2) {
                    result.append(input.charAt(index + offset));
                }
                result.append('>');
                index += 13;
                continue;
            }
            result.append(input.charAt(index));
        }
        return result.toString();
    }

    private static boolean isLegacyHexSequence(String input, int index) {
        for (int offset = 2; offset <= 12; offset += 2) {
            if (!isLegacyMarker(input.charAt(index + offset))
                    || !isHex(input.charAt(index + offset + 1))) {
                return false;
            }
        }
        return true;
    }

    private static String replace(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder(input.length());
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement("<#" + matcher.group(1) + ">"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceLegacyCodes(String input) {
        StringBuilder result = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (isLegacyMarker(current) && index + 1 < input.length()) {
                String tag = legacyTag(input.charAt(index + 1));
                if (tag != null) {
                    result.append('<').append(tag).append('>');
                    index++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    private static boolean isLegacyMarker(char value) {
        return value == '&' || value == '§';
    }

    private static boolean isHex(char value) {
        char lower = Character.toLowerCase(value);
        return (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f');
    }

    private static String legacyTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            case 'k' -> "obfuscated";
            case 'l' -> "bold";
            case 'm' -> "strikethrough";
            case 'n' -> "underlined";
            case 'o' -> "italic";
            case 'r' -> "reset";
            default -> null;
        };
    }
}
