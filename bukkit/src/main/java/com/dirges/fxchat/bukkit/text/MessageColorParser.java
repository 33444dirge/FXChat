package com.dirges.fxchat.bukkit.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.EnumSet;
import java.util.Locale;

public final class MessageColorParser {
    private static final Pattern AMP_HEX = Pattern.compile("(?i)[&\\u00a7]#([0-9a-f]{6})");
    private static final Pattern BRACED_AMP_HEX = Pattern.compile("(?i)[&\\u00a7]\\{#([0-9a-f]{6})}");
    private static final Pattern BRACED_HEX = Pattern.compile("(?i)\\{#([0-9a-f]{6})}");

    private MessageColorParser() {
    }

    public static String convert(String input) {
        return convert(input, EnumSet.allOf(LegacyFeature.class));
    }

    public static String convert(String input, EnumSet<LegacyFeature> allowed) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = allowed.contains(LegacyFeature.COLOR) ? replaceLegacyHex(input) : input;
        if (allowed.contains(LegacyFeature.COLOR)) {
            result = replace(result, BRACED_AMP_HEX);
            result = replace(result, AMP_HEX);
            result = replace(result, BRACED_HEX);
        } else {
            result = result.replaceAll("(?i)[&\\u00a7](?:#[0-9a-f]{6}|\\{#[0-9a-f]{6}})", "\\\\$0");
        }
        return replaceLegacyCodes(result, allowed);
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

    private static String replaceLegacyCodes(String input, EnumSet<LegacyFeature> allowed) {
        StringBuilder result = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (isLegacyMarker(current) && index + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(index + 1));
                String tag = legacyTag(code);
                if (tag != null && allowed.contains(feature(code))) {
                    result.append('<').append(tag).append('>');
                    index++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    private static LegacyFeature feature(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' -> LegacyFeature.COLOR;
            case 'k' -> LegacyFeature.OBFUSCATED;
            case 'l' -> LegacyFeature.BOLD;
            case 'm' -> LegacyFeature.STRIKETHROUGH;
            case 'n' -> LegacyFeature.UNDERLINED;
            case 'o' -> LegacyFeature.ITALIC;
            case 'r' -> LegacyFeature.RESET;
            default -> LegacyFeature.COLOR;
        };
    }

    public enum LegacyFeature {
        COLOR, BOLD, UNDERLINED, ITALIC, STRIKETHROUGH, OBFUSCATED, RESET
    }

    private static final Pattern MINI_TAG = Pattern.compile("<(/?)([#a-zA-Z][a-zA-Z0-9_-]*)(?:(:[^<>]*))?>");

    public static String filterMiniMessage(String input, EnumSet<MiniFeature> allowed) {
        if (input == null || input.isEmpty()) return input;
        Matcher matcher = MINI_TAG.matcher(input);
        StringBuffer result = new StringBuffer(input.length());
        while (matcher.find()) {
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            MiniFeature feature = miniFeature(name);
            String replacement = feature != null && allowed.contains(feature)
                    ? matcher.group()
                    : "\\\\" + matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static MiniFeature miniFeature(String name) {
        if (name.startsWith("#") || name.equals("color") || name.equals("c")) return MiniFeature.COLOR;
        if (name.equals("shadow-color")) return MiniFeature.SHADOW_COLOR;
        if (name.equals("reset") || name.equals("reset-color") || name.equals("reset-decoration")) return MiniFeature.RESET;
        if (name.equals("bold") || name.equals("b") || name.equals("italic") || name.equals("i")
                || name.equals("underlined") || name.equals("u") || name.equals("strikethrough")
                || name.equals("st") || name.equals("obfuscated") || name.equals("obf")) return MiniFeature.DECORATION;
        return switch (name) {
            case "click" -> MiniFeature.CLICK;
            case "hover" -> MiniFeature.HOVER;
            case "keybind" -> MiniFeature.KEYBIND;
            case "translatable" -> MiniFeature.TRANSLATABLE;
            case "fallback" -> MiniFeature.FALLBACK;
            case "insertion" -> MiniFeature.INSERTION;
            case "rainbow" -> MiniFeature.RAINBOW;
            case "gradient" -> MiniFeature.GRADIENT;
            case "transition" -> MiniFeature.TRANSITION;
            case "font" -> MiniFeature.FONT;
            case "newline" -> MiniFeature.NEWLINE;
            case "selector" -> MiniFeature.SELECTOR;
            case "score" -> MiniFeature.SCORE;
            case "nbt" -> MiniFeature.NBT;
            case "pride" -> MiniFeature.PRIDE;
            case "sprite" -> MiniFeature.SPRITE;
            case "head" -> MiniFeature.HEAD;
            default -> null;
        };
    }

    public enum MiniFeature {
        COLOR, SHADOW_COLOR, DECORATION, RESET, CLICK, HOVER, KEYBIND, TRANSLATABLE,
        FALLBACK, INSERTION, RAINBOW, GRADIENT, TRANSITION, FONT, NEWLINE, SELECTOR,
        SCORE, NBT, PRIDE, SPRITE, HEAD
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
