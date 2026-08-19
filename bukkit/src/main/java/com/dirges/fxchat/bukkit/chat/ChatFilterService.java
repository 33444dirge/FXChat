package com.dirges.fxchat.bukkit.chat;

import com.dirges.fxchat.bukkit.config.Settings;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies local filters immediately and refreshes TrChat-compatible cloud word lists off-thread. */
public final class ChatFilterService implements AutoCloseable {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final SchedulerFacade scheduler;
    private final File file;
    private final Consumer<String> warning;
    private final AtomicReference<List<Settings.FilterRule>> local = new AtomicReference<>(List.of());
    private final AtomicReference<List<Settings.FilterRule>> cloud = new AtomicReference<>(List.of());
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Targets targets = Targets.DISABLED;

    public ChatFilterService(SchedulerFacade scheduler, File file, Consumer<String> warning) {
        this.scheduler = scheduler;
        this.file = file;
        this.warning = warning;
    }

    public void update(List<Settings.FilterRule> localRules) {
        local.set(List.copyOf(localRules));
        long currentGeneration = generation.incrementAndGet();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        boolean enabled = config.getBoolean("enabled", false);
        targets = enabled ? new Targets(
                config.getBoolean("targets.chat", true),
                config.getBoolean("targets.sign.enabled", config.getBoolean("targets.sign", true)),
                config.getBoolean("targets.anvil.enabled", config.getBoolean("targets.anvil", true)),
                config.getString("targets.sign.replacement", config.getString("replacement", "*")),
                config.getString("targets.anvil.replacement", config.getString("replacement", "*"))
        ) : Targets.DISABLED;
        if (!enabled || !config.getBoolean("cloud.enabled", false)) {
            cloud.set(List.of());
            return;
        }
        String replacement = config.getString("replacement", "***");
        List<String> urls = config.getStringList("cloud.urls");
        List<String> ignored = config.getStringList("cloud.ignored");
        scheduler.runAsync(() -> refresh(currentGeneration, urls, ignored, replacement));
    }

    public String filterChat(String message) {
        return targets.chat() ? filter(message) : message;
    }

    /** Filters chat while preserving complete @player-name mention tokens for the mention resolver. */
    public String filterChatPreservingMentions(String message, Collection<String> playerNames) {
        if (!targets.chat() || message == null || message.isEmpty() || playerNames == null || playerNames.isEmpty()) {
            return filterChat(message);
        }
        String names = playerNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        if (names.isBlank()) {
            return filterChat(message);
        }
        Matcher mentions = Pattern.compile("@(?:" + names + ")(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(message);
        if (!mentions.find()) {
            return filterChat(message);
        }
        StringBuilder result = new StringBuilder(message.length());
        int end = 0;
        do {
            result.append(filter(message.substring(end, mentions.start())));
            result.append(mentions.group());
            end = mentions.end();
        } while (mentions.find());
        result.append(filter(message.substring(end)));
        return result.toString();
    }

    public String filterSign(String message) {
        return targets.sign() ? filter(message, targets.signReplacement()) : message;
    }

    public String filterAnvil(String message) {
        return targets.anvil() ? filter(message, targets.anvilReplacement()) : message;
    }

    private String filter(String message) {
        return filter(message, null);
    }

    private String filter(String message, String replacement) {
        return apply(apply(message, local.get(), replacement), cloud.get(), replacement);
    }

    private void refresh(long expectedGeneration, List<String> urls, List<String> ignored, String replacement) {
        List<String> words = new ArrayList<>();
        for (String url : urls) {
            try {
                words.addAll(readWords(url, ignored));
            } catch (Exception exception) {
                warning.accept("Could not refresh cloud chat filter " + url + ": " + exception.getMessage());
            }
        }
        if (!closed.get() && generation.get() == expectedGeneration) {
            cloud.set(compile(words, replacement));
        }
    }

    private List<String> readWords(String url, List<String> ignored) throws Exception {
        File cache = new File(new File(file.getParentFile(), "filter-cache"), cacheName(url));
        Exception failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Accept", "application/json")
                        .header("User-Agent", "FXChat cloud-filter")
                        .timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                List<String> words = parseWords(response.body(), ignored);
                Files.createDirectories(cache.toPath().getParent());
                Files.writeString(cache.toPath(), response.body(), StandardCharsets.UTF_8);
                return words;
            } catch (Exception exception) {
                failure = exception;
            }
        }
        if (cache.isFile()) {
            return parseWords(Files.readString(cache.toPath(), StandardCharsets.UTF_8), ignored);
        }
        throw failure == null ? new IllegalStateException("No response") : failure;
    }

    private static List<String> parseWords(String body, List<String> ignored) {
        JsonObject database = JsonParser.parseString(body).getAsJsonObject();
        if (!database.has("words") || !database.get("words").isJsonArray()) {
            throw new IllegalArgumentException("missing words array");
        }
        List<String> words = new ArrayList<>();
        for (JsonElement entry : database.getAsJsonArray("words")) {
            String word = entry.getAsString();
            if (!word.isBlank() && ignored.stream().noneMatch(value -> value.equalsIgnoreCase(word))) {
                words.add(word);
            }
        }
        return words;
    }

    private static String cacheName(String url) {
        return Integer.toUnsignedString(url.hashCode(), 16) + ".json";
    }

    private static List<Settings.FilterRule> compile(List<String> words, String replacement) {
        List<Settings.FilterRule> rules = new ArrayList<>();
        for (String word : words) {
            try {
                rules.add(new Settings.FilterRule(Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                        replacement));
            } catch (RuntimeException ignored) {
                // A malformed remote entry must not discard the rest of the dictionary.
            }
        }
        return List.copyOf(rules);
    }

    private static String apply(String message, List<Settings.FilterRule> rules, String replacementOverride) {
        String result = message;
        for (Settings.FilterRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(result);
            StringBuffer replacement = new StringBuffer(result.length());
            Settings.FilterRule effectiveRule = replacementOverride == null
                    ? rule
                    : new Settings.FilterRule(rule.pattern(), replacementOverride);
            while (matcher.find()) {
                matcher.appendReplacement(replacement, Matcher.quoteReplacement(effectiveRule.replacementFor(matcher.group())));
            }
            matcher.appendTail(replacement);
            result = replacement.toString();
        }
        return result;
    }

    @Override
    public void close() {
        closed.set(true);
        local.set(List.of());
        cloud.set(List.of());
        targets = Targets.DISABLED;
    }

    private record Targets(boolean chat, boolean sign, boolean anvil, String signReplacement, String anvilReplacement) {
        private static final Targets DISABLED = new Targets(false, false, false, "*", "*");
    }
}
