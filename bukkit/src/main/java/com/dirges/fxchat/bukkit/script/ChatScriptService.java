package com.dirges.fxchat.bukkit.script;

import com.dirges.fxchat.bukkit.config.MessageService;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Runs trusted, local JS chat hooks with a deliberately small event API. */
public final class ChatScriptService implements AutoCloseable {
    private static final ContextFactory CONTEXT_FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            Context context = super.makeContext();
            context.setOptimizationLevel(-1);
            context.setInstructionObserverThreshold(100_000);
            return context;
        }

        @Override
        protected void observeInstructionCount(Context context, int instructionCount) {
            throw new EvaluatorException("FXChat script exceeded the instruction limit");
        }
    };

    private final SchedulerFacade scheduler;
    private final MessageService messages;
    private final Consumer<String> warning;
    private volatile List<ScriptFile> scripts;
    private volatile boolean closed;

    public ChatScriptService(
            SchedulerFacade scheduler,
            MessageService messages,
            List<ScriptFile> scripts,
            Consumer<String> warning
    ) {
        this.scheduler = scheduler;
        this.messages = messages;
        this.warning = warning;
        this.scripts = List.copyOf(scripts);
    }

    public static List<ScriptFile> load(File directory, Consumer<String> warning) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            warning.accept("Could not create script directory: " + directory);
            return List.of();
        }
        File[] files = directory.listFiles(file -> {
            String name = file.getName().toLowerCase(Locale.ROOT);
            return file.isFile() && name.endsWith(".js") && !name.endsWith(".disabled.js");
        });
        if (files == null) {
            return List.of();
        }
        Arrays.sort(files, (left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.getName(), right.getName()));
        List<ScriptFile> result = new ArrayList<>();
        for (File file : files) {
            try {
                String source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                if (!source.isBlank()) {
                    Script compiled = compile(file.getName(), source, warning);
                    if (compiled != null) {
                        result.add(new ScriptFile(file.getName(), compiled));
                    }
                }
            } catch (IOException exception) {
                warning.accept("Could not read chat script " + file.getName() + ": " + exception.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private static Script compile(String name, String source, Consumer<String> warning) {
        Context context = CONTEXT_FACTORY.enterContext();
        try {
            return context.compileString(source, name, 1, null);
        } catch (RuntimeException exception) {
            warning.accept("Could not compile chat script " + name + ": " + exception.getMessage());
            return null;
        } finally {
            Context.exit();
        }
    }

    public void update(List<ScriptFile> scripts) {
        this.scripts = List.copyOf(scripts);
    }

    /** Called after a local chat message has been accepted and broadcast. */
    public void trigger(Player player, String message, String channel, String server) {
        trigger("onChat", player, message, channel, server, Map.of());
    }

    public void triggerPrivateSent(
            Player player, String targetName, String message, String server, String channel
    ) {
        trigger("onPrivateSent", player, message, channel, server, Map.of(
                "target", targetName,
                "targetName", targetName,
                "direction", "sent"
        ));
    }

    public void triggerPrivateReceived(
            Player player, String senderName, String message, String server, String channel
    ) {
        trigger("onPrivateReceived", player, message, channel, server, Map.of(
                "sender", senderName,
                "senderName", senderName,
                "direction", "received"
        ));
    }

    public void triggerChannelSwitch(
            Player player, String fromChannel, String toChannel, String server, String privateChannel
    ) {
        trigger("onChannelSwitch", player, "", toChannel, server, Map.of(
                "fromChannel", fromChannel,
                "toChannel", toChannel,
                "previousChannel", fromChannel,
                "from", fromChannel,
                "to", toChannel,
                "privateChannel", privateChannel
        ));
    }

    private void trigger(
            String handlerName,
            Player player,
            String message,
            String channel,
            String server,
            Map<String, ?> extra
    ) {
        if (closed || message == null || channel == null) {
            return;
        }
        List<ScriptFile> currentScripts = scripts;
        if (currentScripts.isEmpty()) {
            return;
        }
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (ScriptFile script : currentScripts) {
                run(script, handlerName, player, message, channel, server, extra);
            }
        });
    }

    private void run(
            ScriptFile script,
            String handlerName,
            Player player,
            String message,
            String channel,
            String server,
            Map<String, ?> extra
    ) {
        Context context = CONTEXT_FACTORY.enterContext();
        try {
            context.setClassShutter(className -> false);
            Scriptable scope = context.initStandardObjects();
            script.compiled().exec(context, scope);
            Object rawHandler = scope.get(handlerName, scope);
            if (rawHandler instanceof Function handler) {
                handler.call(context, scope, scope,
                        new Object[]{event(context, scope, player, message, channel, server, extra)});
            }
        } catch (RuntimeException exception) {
            warning.accept("Chat script " + script.name() + " failed: " + exception.getMessage());
        } finally {
            Context.exit();
        }
    }

    private Scriptable event(
            Context context,
            Scriptable scope,
            Player player,
            String message,
            String channel,
            String server,
            Map<String, ?> extra
    ) {
        Scriptable event = context.newObject(scope);
        Map<String, Object> variables = variables(player, message, channel, server, extra);
        event.put("player", event, player.getName());
        event.put("message", event, message);
        event.put("channel", event, channel);
        event.put("server", event, server);
        event.put("uuid", event, player.getUniqueId().toString());
        event.put("world", event, player.getWorld().getName());
        extra.forEach((key, value) -> event.put(key, event, value));
        event.put("contains", event, function(args -> message.contains(argument(args, 0, ""))));
        event.put("matches", event, function(args -> matches(message, argument(args, 0, ""))));
        event.put("hasPermission", event, function(args ->
                player.hasPermission(argument(args, 0, ""))));
        event.put("send", event, function(args -> {
            sendText(player, argument(args, 0, ""), variables);
            return Undefined.instance;
        }));
        event.put("actionbar", event, function(args -> {
            sendAction(player, "actionbar", Map.of("text", argument(args, 0, "")),
                    variables);
            return Undefined.instance;
        }));
        event.put("sound", event, function(args -> {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "sound");
            action.put("sound", argument(args, 0, ""));
            action.put("volume", argument(args, 1, "1"));
            action.put("pitch", argument(args, 2, "1"));
            action.put("category", argument(args, 3, ""));
            sendAction(player, action, variables);
            return Undefined.instance;
        }));
        event.put("title", event, function(args -> {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "title");
            action.put("title", argument(args, 0, ""));
            action.put("subtitle", argument(args, 1, ""));
            action.put("fade-in", argument(args, 2, "10"));
            action.put("stay", argument(args, 3, "70"));
            action.put("fade-out", argument(args, 4, "20"));
            sendAction(player, action, variables);
            return Undefined.instance;
        }));
        event.put("language", event, function(args -> {
            String key = argument(args, 0, "");
            if (!key.isBlank()) {
                messages.send(player, key, variables);
            }
            return Undefined.instance;
        }));
        event.put("command", event, function(args -> {
            runCommand(player, argument(args, 0, ""), argument(args, 1, "player"));
            return Undefined.instance;
        }));
        event.put("consoleCommand", event, function(args -> {
            runCommand(player, argument(args, 0, ""), "console");
            return Undefined.instance;
        }));
        return event;
    }

    private void sendText(Player player, String text, Map<String, ?> variables) {
        sendAction(player, "text", Map.of("text", text), variables);
    }

    private void sendAction(Player player, String type, Map<String, ?> values, Map<String, ?> variables) {
        Map<String, Object> action = new LinkedHashMap<>(values);
        action.put("type", type);
        sendAction(player, action, variables);
    }

    private void sendAction(Player player, Map<String, ?> action, Map<String, ?> variables) {
        messages.sendActions(player, List.of(action), variables);
    }

    private void runCommand(Player player, String command, String source) {
        String normalized = command.strip();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return;
        }
        if (source.equalsIgnoreCase("console")) {
            String consoleCommand = normalized;
            scheduler.runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCommand));
        } else if (source.equalsIgnoreCase("player")) {
            player.performCommand(normalized);
        }
    }

    private static Map<String, Object> variables(
            Player player,
            String message,
            String channel,
            String server,
            Map<String, ?> extra
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("player", player.getName());
        values.put("message", message);
        values.put("channel", channel);
        values.put("server", server);
        values.put("uuid", player.getUniqueId());
        values.put("world", player.getWorld().getName());
        values.putAll(extra);
        return values;
    }

    private static BaseFunction function(Handler handler) {
        return new BaseFunction() {
            @Override
            public Object call(Context context, Scriptable scope, Scriptable thisObject, Object[] args) {
                return handler.apply(args);
            }
        };
    }

    private static boolean matches(String source, String expression) {
        if (expression.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(source).find();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String argument(Object[] args, int index, String fallback) {
        if (index >= args.length || args[index] == null || args[index] == Undefined.instance) {
            return fallback;
        }
        return Context.toString(args[index]);
    }

    @Override
    public void close() {
        closed = true;
        scripts = List.of();
    }

    @FunctionalInterface
    private interface Handler {
        Object apply(Object[] args);
    }

    public record ScriptFile(String name, Script compiled) {
    }
}
