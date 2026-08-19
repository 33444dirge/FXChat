package com.dirges.fxchat.bukkit.render;

import com.dirges.fxchat.bukkit.config.Settings;
import com.dirges.fxchat.bukkit.function.ChatFunctionService;
import com.dirges.fxchat.bukkit.hook.PapiHook;
import com.dirges.fxchat.bukkit.text.MessageColorParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MessageRenderer {
    private static final String PLAYER_TAG = "fxchat_player";
    private static final String SENDER_TAG = "fxchat_sender";
    private static final String TARGET_TAG = "fxchat_target";
    private static final String SERVER_TAG = "fxchat_server";
    private static final String CHANNEL_TAG = "fxchat_channel";
    private static final String MESSAGE_TAG = "fxchat_message";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PapiHook papi;
    private final ChatFunctionService functions;

    public MessageRenderer(PapiHook papi, ChatFunctionService functions) {
        this.papi = papi;
        this.functions = functions;
    }

    public RenderedMessage render(
            Player player, Settings settings, Settings.ChannelSettings channel, String message, boolean protectedInput
    ) {
        PreparedBody prepared = prepareBody(player, message);
        Component component = renderFormat(
                player, settings, channel.id(), channel.format(), prepared.body(), null);
        return new RenderedMessage(
                component,
                prepared.expansion().mentionedPlayers(),
                prepared.expansion().mentionAll(),
                prepared.expansion().showcases()
        );
    }

    public PrivateRenderedMessage renderPrivate(
            Player player,
            Settings settings,
            String message,
            String senderFormat,
            String receiverFormat,
            String spyFormat,
            String targetName,
            boolean protectedInput
    ) {
        PreparedBody prepared = prepareBody(player, message);
        return new PrivateRenderedMessage(
                renderFormat(player, settings, "private", senderFormat, prepared.body(), targetName),
                renderFormat(player, settings, "private", receiverFormat, prepared.body(), targetName),
                renderFormat(player, settings, "private", spyFormat, prepared.body(), targetName),
                prepared.expansion().mentionedPlayers(),
                prepared.expansion().mentionAll(),
                prepared.expansion().showcases()
        );
    }

    public Component renderInput(Player player, String message, ColorTarget target) {
        return deserialize(prepareInput(player, message, target), TagResolver.empty());
    }

    public String prepareInput(Player player, String message, ColorTarget target) {
        String result = message == null ? "" : message;
        boolean legacy = hasColorPermission(player, target, "legacy");
        boolean miniMessage = hasColorPermission(player, target, "minimessages");
        if (!miniMessage) {
            result = this.miniMessage.escapeTags(result);
        }
        if (legacy) {
            return MessageColorParser.convert(result);
        }
        return MessageColorParser.neutralizeSectionSigns(result);
    }

    public boolean hasAnyColorPermission(Player player, ColorTarget target) {
        return hasColorPermission(player, target, "legacy") || hasColorPermission(player, target, "minimessages");
    }

    public boolean hasColorPermission(Player player, ColorTarget target, String syntax) {
        String base = "fxchat.color." + target.permissionSegment();
        return player.hasPermission(base + ".*") || player.hasPermission(base + "." + syntax);
    }

    private PreparedBody prepareBody(Player player, String message) {
        String bodySource = message;
        if (papi != null) {
            bodySource = papi.expand(player, bodySource);
        }
        bodySource = prepareInput(player, bodySource, ColorTarget.CHAT);

        ChatFunctionService.PreparedMessage functionExpansion = functions.prepare(player, bodySource);
        TagResolver.Builder bodyResolvers = TagResolver.builder();
        for (ChatFunctionService.Token token : functionExpansion.tokens()) {
            bodyResolvers = bodyResolvers.resolver(Placeholder.component(token.key(), token.component()));
        }
        return new PreparedBody(
                functionExpansion,
                deserialize(functionExpansion.source(), bodyResolvers.build())
        );
    }

    private Component renderFormat(
            Player player,
            Settings settings,
            String channel,
            String formatTemplate,
            Component body,
            String targetName
    ) {
        String format = miniMessageFormat(formatTemplate);
        if (papi != null) {
            format = papi.expand(player, format);
        }
        format = MessageColorParser.convert(format);

        TagResolver formatResolvers = TagResolver.builder()
                .resolver(Placeholder.component(MESSAGE_TAG, body))
                .resolver(Placeholder.unparsed(PLAYER_TAG, player.getName()))
                .resolver(Placeholder.unparsed(SENDER_TAG, player.getName()))
                .resolver(Placeholder.unparsed(TARGET_TAG, targetName == null ? "" : targetName))
                .resolver(Placeholder.unparsed(SERVER_TAG, settings.serverName()))
                .resolver(Placeholder.unparsed(CHANNEL_TAG, channel))
                .build();
        return deserialize(format, formatResolvers);
    }

    private static String miniMessageFormat(String template) {
        String format = template == null ? "{message}" : template;
        return format
                .replace("{player}", tag(PLAYER_TAG))
                .replace("{sender}", tag(SENDER_TAG))
                .replace("{target}", tag(TARGET_TAG))
                .replace("{server}", tag(SERVER_TAG))
                .replace("{channel}", tag(CHANNEL_TAG))
                .replace("{message}", tag(MESSAGE_TAG));
    }

    private static String tag(String name) {
        return "<" + name + ">";
    }

    private Component deserialize(String source, TagResolver resolver) {
        try {
            return miniMessage.deserialize(source, resolver);
        } catch (RuntimeException exception) {
            return Component.text(source);
        }
    }

    public record RenderedMessage(
            Component component,
            Set<UUID> mentionedPlayers,
            boolean mentionAll,
            Map<String, String> showcases
    ) {
    }

    public record PrivateRenderedMessage(
            Component senderComponent,
            Component receiverComponent,
            Component spyComponent,
            Set<UUID> mentionedPlayers,
            boolean mentionAll,
            Map<String, String> showcases
    ) {
    }

    private record PreparedBody(ChatFunctionService.PreparedMessage expansion, Component body) {
    }

    public enum ColorTarget {
        CHAT("chat"),
        ANVIL("anvil"),
        SIGN("sign");

        private final String permissionSegment;

        ColorTarget(String permissionSegment) {
            this.permissionSegment = permissionSegment;
        }

        private String permissionSegment() {
            return permissionSegment;
        }
    }
}
