# FXChat

FXChat is a small, standalone chat plugin split into a Paper/Folia backend and a Velocity bridge. It does not depend on TrChat or TabooLib.

## Features

- Paper 1.21.x and Folia support through one scheduler facade.
- Cross-server chat through the modern `fxchat:main` plugin-message channel.
- MiniMessage formats and permission-gated MiniMessage player input.
- Optional PlaceholderAPI expansion in both formats and player messages.
- Legacy and MiniMessage color parsing for player input requires an explicit `fxchat.color=true` grant, including `&x&F&F&0&0&0&0` and `&#RRGGBB`.
- TrChat-compatible standard chat functions: player mentions, `@all`, item sharing, inventory sharing, and ender-chest sharing.
- TrChat-compatible custom functions for URL, QQ, and Bilibili `BV` sharing.
- Private-message spy mode with cross-server delivery through Velocity.
- Configurable channels, aliases, ranges, cross-server forwarding, cooldowns, and literal word filters.
- Bukkit event API: `com.dirges.fxchat.bukkit.api.event.FXChatSendEvent`.
- Optional Lands chat compatibility: switching to an FXChat channel clears Lands land/nation chat mode.
- Optional CustomNameplates compatibility: public FXChat channels can show bubbles, while private messages never create bubbles.
- Split configuration files: `proxy.yml`, `database.yml`, `private.yml`, `channels/*.yml`, `filters.yml`, `functions.yml`, `custom-functions.yml`, and `scripts/*.js`; chat settings are in `config.yml`.
- Language files: `lang/zh_CN.yml` and `lang/en_US.yml`.

## Build

```text
gradlew.bat build
```

Artifacts:

- `bukkit/build/libs/FXChat-Bukkit-1.0.0-SNAPSHOT.jar`
- `velocity/build/libs/FXChat-Velocity-1.0.0-SNAPSHOT.jar`

The common protocol also has a standard-library self-check:

```text
gradlew.bat :common:protocolCheck
```

## Install

1. Put the Bukkit jar on every Paper/Folia backend.
2. Put the Velocity jar in the proxy `plugins` directory.
3. Keep `fxchat:main` enabled on the proxy and backends.
4. Set a different `server-name` in every backend `proxy.yml`. The value must exactly match the corresponding Velocity server name.
5. Install PlaceholderAPI only when its placeholders are needed. FXChat remains functional without it.
6. Install Lands only when its land/nation chat is used. FXChat clears the Lands chat mode when a player switches to an FXChat or private channel.
7. Install CustomNameplates only when its nameplates/bubbles are used. FXChat takes over its chat provider so private messages are excluded from bubbles.

Keep the same `channels/*.yml` files on every backend so a remote channel is accepted and rendered consistently. The default channel files are `channels/公开.yml`, `channels/附近.yml` (80 blocks), and `channels/私聊.yml`; each file's `id` field is the channel id. Every value in `aliases` is registered as a channel command, so `aliases: [g]` enables `/g message` and `aliases: [f]` enables `/f message`. Private command names and aliases are configured under the separate top-level `private.yml -> commands` file and are registered at startup. These configurable commands are intentionally absent from `plugin.yml`. Set `language` in `config.yml` to `zh_CN` or `en_US`.

Private messages use `channels/<private-channel>.yml -> sender-format` for the sender, `receiver-format` for the target, and `spy-format` for listeners, including cross-server messages. In those formats, `<player>` / `<sender>` is the sender and `<target>` is the recipient. The old `format` key remains the fallback for existing configurations.

Commands:

- `/fxchat help`
- `/fxchat channel [name] [player]` (`私聊` requires a player)
- `/fxchat reload`
- `/fxchat version`
- `/fxchat spy [on|off]` (requires `fxchat.spy`)
- `/mute <player> <reason> <duration>` (requires `fxchat.mute`; duration examples: `30s`, `10m`, `2h`, `1d`, `永久`)
- `/g [message]` for the public channel, `/f [message]` for the nearby channel
- `/msg <player> [message]`, `/tell`, `/w`; omit `[message]` to enter a private chat channel
- `/reply <message>`, `/r`
- `/view-item <token>`
- `/view-inventory <token>`
- `/view-enderchest <token>`
- `/view-container <token>`

Use `/fxchat channel 公开` or `/g message` for the public channel. Use `/fxchat channel 附近` or `/f message` for the 80-block nearby channel. MiniMessage is always enabled for player chat. Use `/fxchat reload` after changing configuration.

Mute data is stored in `database.yml`. The default is file-based H2 at `data/fxchat`; set `type: mysql` and fill in the `mysql` section when all servers should share one database. Mute durations accept seconds (`60` or `60s`), minutes, hours, days, weeks, or `永久`.

With the default function settings, send `[container]` while aiming at a container within six blocks to create a read-only container showcase. `[chest]` and `[barrel]` are aliases; the keys and range are configurable under `functions.yml -> container-show`.

With the default function settings, mention a local player with `@Name`. `@all` requires `fxchat.function.mentionall`. The `[item]`, `[inv]`, and `[ender]` tokens create short-lived, read-only showcase snapshots; their tokens are forwarded with cross-server chat packets.

`custom-functions.yml` keeps TrChat's `Custom:` layout. Each rule supports `enabled`, `priority`, `pattern`, optional `text-filter`, and `display.text`. Put all MiniMessage formatting, hover, click, and copy tags directly in the single `display.text` value. Display text accepts PlaceholderAPI variables, `{0}`, MiniMessage, legacy `&` codes, `&x` hex codes, and `&#RRGGBB`. Run `/fxchat reload` after editing this file.

Chat scripts are JavaScript files in `scripts/`. Each file can define `onChat(event)`, `onPrivateSent(event)`, `onPrivateReceived(event)`, or `onChannelSwitch(event)`. Private events add `target` / `sender` and `direction`; channel switches add `fromChannel` and `toChannel`. The event exposes `player`, `message`, `channel`, `server`, `uuid`, and `world`, plus `contains(text)`, `matches(regex)`, `hasPermission(permission)`, `send(text)`, `actionbar(text)`, `sound(name, volume, pitch, category)`, `title(title, subtitle, fadeIn, stay, fadeOut)`, `language(key)`, `command(command, "player")`, and `consoleCommand(command)`. Files ending in `.disabled.js` are ignored. `scripts/message-pickup.js` plays `ENTITY_ITEM_PICKUP` after each local chat message, `scripts/private-message.js` plays separate private send/receive sounds, and `scripts/channel-switch.js` plays separate exit/enter sounds. Run `/fxchat reload` after editing scripts.

## Threading boundary

Network decoding and configuration parsing only carry immutable snapshots. Live Bukkit objects are accessed from their entity or region scheduler callback. Shared session, cooldown, and deduplication maps are concurrent and cleared on shutdown. The proxy never receives or stores Bukkit objects.
