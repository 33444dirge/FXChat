package com.dirges.fxchat.bukkit.chat;

import com.dirges.fxchat.bukkit.FXChatBukkit;
import com.dirges.fxchat.bukkit.api.CustomChannel;
import com.dirges.fxchat.bukkit.api.FXChatApi;
import com.dirges.fxchat.bukkit.config.MessageService;
import com.dirges.fxchat.bukkit.config.Settings;
import com.dirges.fxchat.bukkit.function.ChatFunctionService;
import com.dirges.fxchat.bukkit.hook.CustomNameplatesHook;
import com.dirges.fxchat.bukkit.moderation.*;
import com.dirges.fxchat.bukkit.player.*;
import com.dirges.fxchat.bukkit.proxy.BukkitProxyTransport;
import com.dirges.fxchat.bukkit.render.MessageRenderer;
import com.dirges.fxchat.bukkit.scheduler.SchedulerFacade;
import com.dirges.fxchat.bukkit.script.ChatScriptService;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomChannelTest {
    private final FXChatBukkit plugin = mock(FXChatBukkit.class);
    private final Plugin owner = mock(Plugin.class);
    private final SchedulerFacade scheduler = mock(SchedulerFacade.class);
    private final PlayerSessionManager sessions = new PlayerSessionManager();
    private final MessageService messages = mock(MessageService.class);
    private final MuteService mutes = mock(MuteService.class);
    private final ChatFilterService filters = mock(ChatFilterService.class);
    private final MessageRenderer renderer = mock(MessageRenderer.class);
    private final BukkitProxyTransport transport = mock(BukkitProxyTransport.class);
    private final CustomNameplatesHook bubbles = mock(CustomNameplatesHook.class);
    private final Map<UUID,String> groups = new HashMap<>();
    private final List<Runnable> deliveries = new ArrayList<>();
    private ChatService chat;
    private FXChatApi.Registration registration;
    private Player sender, member, outsider;
    private MockedStatic<Bukkit> bukkit;

    private Settings settings() {
        var channel = new Settings.ChannelSettings("public", "", 0, true, List.of("g"),
                "{player}: {message}", "", "", List.of());
        return new Settings("server", true, 0, 0, 1, 256, "public", "private", "!", "public",
                List.of(), Map.of("public", channel), "", "", "", null, "en_US");
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        sessions.join(player);
        return player;
    }

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        when(owner.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        doAnswer(call -> { deliveries.add(call.getArgument(1)); return null; })
                .when(scheduler).runAtEntity(any(), any());
        when(filters.filterChatPreservingMatches(anyString(), any(), any())).thenAnswer(call -> call.getArgument(0));
        when(renderer.render(any(), any(), any(), anyString(), anyBoolean())).thenReturn(
                new MessageRenderer.RenderedMessage(Component.text("message"), Set.of(), false, Map.of()));
        chat = new ChatService(plugin, scheduler, messages, settings(), sessions, renderer,
                mock(ChatFunctionService.class), mock(ChatScriptService.class), transport, ignored -> {},
                bubbles, mutes, mock(PrivateSpyService.class), mock(IgnoreService.class),
                mock(PlayerChannelService.class), filters);
        sender = player("sender"); member = player("member"); outsider = player("outsider");
        groups.put(sender.getUniqueId(), "island-a"); groups.put(member.getUniqueId(), "island-a");
        groups.put(outsider.getUniqueId(), "island-b");
        registration = chat.registerChannel(owner,
                new CustomChannel("island", List.of("ic"), "", "{message}", groups::get));
        assertTrue(chat.enterChannel(sender, "ic"));
    }

    @AfterEach void cleanup() { bukkit.close(); }
    private void deliver() { List.copyOf(deliveries).forEach(Runnable::run); deliveries.clear(); }

    @Test void routesOnlyToSameIslandThroughNormalPipeline() {
        chat.handleChat(sender, "hello"); deliver();
        verify(sender).sendMessage(Component.text("message"));
        verify(member).sendMessage(Component.text("message"));
        verify(outsider, never()).sendMessage(any(Component.class));
        verify(filters).filterChatPreservingMatches(eq("hello"), any(), any());
        verifyNoInteractions(transport, bubbles);
    }

    @Test void revokedMembershipIsCheckedAtDelivery() {
        chat.handleChat(sender, "hello"); groups.remove(member.getUniqueId()); deliver();
        verify(member, never()).sendMessage(any(Component.class));
    }

    @Test void senderChangingIslandBeforeDeliveryCancelsOldAudience() {
        chat.handleChat(sender, "hello"); groups.put(sender.getUniqueId(), "island-b"); deliver();
        verify(member, never()).sendMessage(any(Component.class));
        verify(outsider, never()).sendMessage(any(Component.class));
    }

    @Test void removedSenderCannotLeakToPublicChat() {
        groups.remove(sender.getUniqueId()); chat.handleChat(sender, "secret"); deliver();
        assertTrue(deliveries.isEmpty()); verifyNoInteractions(renderer, transport, bubbles);
        verify(messages).send(sender, "chat.channel-no-permission");
    }

    @Test void registrationCloseCancelsQueuedMessagesAndStaleSelection() {
        chat.handleChat(sender, "hello"); registration.close(); deliver();
        verify(member, never()).sendMessage(any(Component.class));
        clearInvocations(renderer); chat.handleChat(sender, "secret");
        verifyNoInteractions(renderer);
    }

    @Test void reloadKeepsChannelAndEnteringIsIdempotent() {
        chat.updateSettings(settings());
        assertEquals("island", chat.channelAlias("ic"));
        assertNull(chat.channelAlias("island")); // preserve /island owned by StarryIsland
        assertTrue(chat.enterChannel(sender, "ic")); assertEquals("island", chat.activeChannel(sender));
        assertTrue(chat.leaveChannel(sender)); assertEquals("public", chat.activeChannel(sender));
    }

    @Test void repeatingChannelCommandTogglesBackToPublicAndCanEnterAgain() {
        chat.handleChannelAlias(sender, chat.channelAlias("ic"), "");
        assertEquals("public", chat.activeChannel(sender));
        chat.handleChannelAlias(sender, chat.channelAlias("ic"), "");
        assertEquals("island", chat.activeChannel(sender));
        chat.updateSettings(settings());
        chat.handleChannelAlias(sender, chat.channelAlias("ic"), "");
        assertEquals("public", chat.activeChannel(sender));
    }

    @Test void channelCommandWithMessageDoesNotToggleSelectedChannel() {
        chat.handleChannelAlias(sender, chat.channelAlias("ic"), "hello");
        assertEquals("island", chat.activeChannel(sender));
        assertTrue(chat.leaveChannel(sender));
        chat.handleChannelAlias(sender, chat.channelAlias("ic"), "hello again");
        assertEquals("public", chat.activeChannel(sender));
    }

    @Test void ownerDisableUnregistersAndUnknownPlayersCannotEnter() {
        groups.remove(outsider.getUniqueId()); assertFalse(chat.enterChannel(outsider, "ic"));
        chat.unregisterChannels(owner); assertNull(chat.channelAlias("ic"));
    }

    @Test void muteStillApplies() {
        when(mutes.global()).thenReturn(new GlobalMuteRecord("reason", "admin", 1, 0));
        chat.handleChat(sender, "secret"); verifyNoInteractions(renderer);
    }
    @Test void cancelledSendEventDoesNotDeliver() {
        PluginManager manager = Bukkit.getPluginManager();
        doAnswer(call -> {
            ((com.dirges.fxchat.bukkit.api.event.FXChatSendEvent) call.getArgument(0)).setCancelled(true);
            return null;
        }).when(manager).callEvent(any());
        chat.handleChat(sender, "secret");
        verifyNoInteractions(renderer, transport, bubbles);
    }

    @Test void reloadRejectsFileChannelThatConflictsWithRegistration() {
        var conflict = new Settings.ChannelSettings("island", "", 0, true, List.of(),
                "{message}", "", "", List.of());
        assertThrows(IllegalArgumentException.class,
                () -> chat.updateSettings(settings().withChannels(Map.of("island", conflict))));
        assertEquals("island", chat.channelAlias("ic"));
    }

}
