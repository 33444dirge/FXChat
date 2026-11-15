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

A channel may add an optional `dynamic-formats` list to vary its format by world and permission. Each rule is evaluated top-to-bottom and the first matching rule wins. A rule's `world` and `permission` conditions must all hold for it to match (`world` and `permission` each accept a single value or a list; an omitted condition is unrestricted, and a list of permissions requires every entry). When a rule matches, its `format` replaces the channel `format` entirely, or — if only `prefix`/`suffix` are given — they wrap the channel `format`. The prefix/suffix/format strings support the same MiniMessage, PlaceholderAPI, and `{player}`/`{message}` placeholders as `format`. The world is the sender's current world at send time, so cross-server messages carry the already-rendered prefix. For example, adding `dynamic-formats: [{world: xingyu, prefix: "<gold>[xingyu]</gold> "}]` to `channels/公开.yml` prepends `[xingyu]` for players chatting in the `xingyu` world.

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

MiniMessage tags use the Adventure version provided by the server. Sprite and head tags require Minecraft 1.21.9+ with Adventure 4.25.0+. The notation `<sprite[:atlas]:sprite>` describes an optional atlas; use `<sprite:block/stone>` or `<sprite:'minecraft:blocks':'minecraft:block/stone'>` in messages. Quote namespaced IDs so their colons are not treated as argument separators. Player sprite input uses the `fxchat.color.chat.minimessages.sprite` permission (or the MiniMessage parent permission).

Mute data is stored in `database.yml`. The default is file-based H2 at `data/fxchat`; set `type: mysql` and fill in the `mysql` section when all servers should share one database. Mute durations accept seconds (`60` or `60s`), minutes, hours, days, weeks, or `永久`.

With the default function settings, send `[container]` while aiming at a container within six blocks to create a read-only container showcase. `[chest]` and `[barrel]` are aliases; the keys and range are configurable under `functions.yml -> container-show`.

With the default function settings, mention a local player with `@Name`. `@all` requires `fxchat.function.mentionall`. The `[item]`, `[inv]`, and `[ender]` tokens create short-lived, read-only showcase snapshots; their tokens are forwarded with cross-server chat packets.

`custom-functions.yml` keeps TrChat's `Custom:` layout. Each rule supports `enabled`, `priority`, `pattern`, optional `text-filter`, and `display.text`. Put all MiniMessage formatting, hover, click, and copy tags directly in the single `display.text` value. Display text accepts PlaceholderAPI variables, `{0}`, MiniMessage, legacy `&` codes, `&x` hex codes, and `&#RRGGBB`. Run `/fxchat reload` after editing this file.

Chat scripts are JavaScript files in `scripts/`. Each file can define `onChat(event)`, `onPrivateSent(event)`, `onPrivateReceived(event)`, or `onChannelSwitch(event)`. Private events add `target` / `sender` and `direction`; channel switches add `fromChannel` and `toChannel`. The event exposes `player`, `message`, `channel`, `server`, `uuid`, and `world`, plus `contains(text)`, `matches(regex)`, `hasPermission(permission)`, `send(text)`, `actionbar(text)`, `sound(name, volume, pitch, category)`, `title(title, subtitle, fadeIn, stay, fadeOut)`, `language(key)`, `command(command, "player")`, and `consoleCommand(command)`. Files ending in `.disabled.js` are ignored. `scripts/message-pickup.js` plays `ENTITY_ITEM_PICKUP` after each local chat message, `scripts/private-message.js` plays separate private send/receive sounds, and `scripts/channel-switch.js` plays separate exit/enter sounds. Run `/fxchat reload` after editing scripts.

## Threading boundary

Network decoding and configuration parsing only carry immutable snapshots. Live Bukkit objects are accessed from their entity or region scheduler callback. Shared session, cooldown, and deduplication maps are concurrent and cleared on shutdown. The proxy never receives or stores Bukkit objects.


### Bukkit 自定义频道 API

其他插件可通过 Bukkit ServicesManager 获取 `com.dirges.fxchat.bukkit.api.FXChatApi`，并在 plugin.yml 声明 `depend: [FXChat]`。API 包在 Bukkit JAR 内，依赖方只需 compileOnly，不能把这些类打包进自己的 JAR。

```java
FXChatApi api = Bukkit.getServicesManager().load(FXChatApi.class);
FXChatApi.Registration registration = api.registerChannel(this,
    new CustomChannel("island", List.of("ic", "islandchat"), "starry.player",
        "<aqua>[岛屿]</aqua> {player} » {message}", playerId -> findIslandId(playerId)));
api.enterChannel(player, "island"); // 重复调用仍停留在该频道
api.leaveChannel(player);           // 回到当前配置的默认频道
registration.close();
```

`groupResolver` 返回稳定的分组 ID（例如岛屿 UUID），无权参与时返回 null。仅同组玩家能收到消息；发送者和接收者身份会在实际投递时复核。回调必须线程安全，不得访问其他玩家的实体/世界状态。注册、注销在全局线程执行；玩家操作在该玩家实体线程执行。

只把 `commands` 注册为命令，频道 ID 不会抢占同名插件命令。命令名称不带 `/` 或参数，已有命令冲突会拒绝注册。频道与已有频道 ID/别名也不能重名。注册会保留到显式关闭或所属插件禁用，并跨 FXChat 配置重载保留；配置文件若与注册频道冲突，重载会失败并保留旧频道配置。

此 API 的频道仅限当前服务器，沿用 FXChat 禁言、过滤、冷却、防重复、发送事件、格式化与屏蔽规则，不跨服发送，也不产生 CustomNameplates 公共气泡。`/ic` 进入频道，再次输入 `/ic` 返回默认公共频道；`/ic 内容` 仅向该频道发送一次，不切换当前频道。
