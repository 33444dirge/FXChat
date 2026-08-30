package com.dirges.fxchat.bukkit.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.List;

public final class ConfigCommand extends Command {
    public enum Kind {
        CHANNEL,
        PRIVATE,
        REPLY,
        MUTE,
        MUTE_ALL,
        TRC
    }

    private final FXChatCommand handler;
    private final Kind kind;

    public ConfigCommand(String name, FXChatCommand handler, Kind kind) {
        super(name);
        this.handler = handler;
        this.kind = kind;
    }

    @Override
    public boolean execute(@NonNull CommandSender sender, @NonNull String commandLabel, String @NonNull [] args) {
        return switch (kind) {
            case CHANNEL -> handler.onChannelCommand(sender, this, commandLabel, args);
            case PRIVATE -> handler.onPrivateCommand(sender, args);
            case REPLY -> handler.onReplyCommand(sender, args);
            case MUTE -> handler.onMuteCommand(sender, args);
            case MUTE_ALL -> handler.onMuteAllCommand(sender, args);
            case TRC -> handler.onTrcCommand(sender, args);
        };
    }

    @Override
    public @NonNull List<String> tabComplete(@NonNull CommandSender sender, @NonNull String alias, String @NonNull [] args) {
        if (kind == Kind.CHANNEL) {
            return List.of();
        }
        if (kind == Kind.MUTE) {
            return handler.onMuteTabComplete(args);
        }
        if (kind == Kind.MUTE_ALL) {
            return List.of("off");
        }
        if (kind == Kind.TRC) {
            return List.of("send", "sendproxy");
        }
        return handler.onPrivateTabComplete(args, kind == Kind.REPLY);
    }
}
