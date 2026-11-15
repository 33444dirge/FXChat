package com.dirges.fxchat.bukkit.text;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.object.SpriteObjectContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;

import static com.dirges.fxchat.bukkit.text.MessageColorParser.MiniFeature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MessageColorParserTest {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final EnumSet<MiniFeature> ALL = EnumSet.allOf(MiniFeature.class);

    @ParameterizedTest
    @ValueSource(strings = {
            "<sprite:gui/sprites/icon>",
            "<sprite:blocks:block/stone>",
            "<sprite:'minecraft:blocks':'minecraft:block/stone'>",
            "<head:Steve>",
            "<red>red</red><dark_grey>gray</dark_grey><colour:blue>blue</colour>",
            "<em>italic</em><!italic>plain</!italic><bold:false>plain</bold>",
            "<lang:chat.type.text:'<gold>name</gold>':message>",
            "<tr_or:missing.key:fallback><br/><sel:@a><data:entity:@s:CustomName>",
            "<hover:show_text:'<red>value > limit</red>'>hover</hover>",
            "<click:run_command:'/say > hello'>click</click>",
            "<gradient:red:blue>gradient</gradient><rainbow>rainbow</rainbow>",
            "\\<red>literal\\</red>"
    })
    void preservesStandardSyntax(String input) {
        String filtered = MessageColorParser.filterMiniMessage(input, ALL);
        assertEquals(input, filtered);
    }

    @Test
    void rendersSpriteWithDefaultAtlas() {
        ObjectComponent component = assertInstanceOf(ObjectComponent.class,
                MINI_MESSAGE.deserialize(MessageColorParser.filterMiniMessage(
                        "<sprite:block/stone>", EnumSet.of(MiniFeature.SPRITE))));
        SpriteObjectContents sprite = assertInstanceOf(SpriteObjectContents.class, component.contents());
        assertEquals(SpriteObjectContents.DEFAULT_ATLAS, sprite.atlas());
        assertEquals(Key.key("minecraft:block/stone"), sprite.sprite());
    }

    @Test
    void rendersSpriteWithExplicitAtlas() {
        ObjectComponent component = assertInstanceOf(ObjectComponent.class,
                MINI_MESSAGE.deserialize(MessageColorParser.filterMiniMessage(
                        "<sprite:'minecraft:blocks':'minecraft:block/stone'>", EnumSet.of(MiniFeature.SPRITE))));
        SpriteObjectContents sprite = assertInstanceOf(SpriteObjectContents.class, component.contents());
        assertEquals(Key.key("minecraft:blocks"), sprite.atlas());
        assertEquals(Key.key("minecraft:block/stone"), sprite.sprite());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<red>text</red>",
            "<!bold>text</!bold>",
            "<sprite:'minecraft:blocks':'minecraft:block/stone'>",
            "<br/>",
            "<hover:show_text:'<red>value > limit</red>'>hover</hover>"
    })
    void deniedTagsRenderLiterally(String input) {
        String filtered = MessageColorParser.filterMiniMessage(input, EnumSet.noneOf(MiniFeature.class));
        assertEquals(Component.text(input), MINI_MESSAGE.deserialize(filtered));
    }

    @Test
    void filtersNestedTagsWithoutDisablingAllowedHover() {
        String input = "<hover:show_text:'<red>hover</red>'>label</hover>";
        Component component = MINI_MESSAGE.deserialize(MessageColorParser.filterMiniMessage(
                input, EnumSet.of(MiniFeature.HOVER)));
        assertEquals(Component.text("<red>hover</red>"), component.hoverEvent().value());
    }

    @Test
    void respectsExistingEscapes() {
        assertEquals("\\<red>text\\</red>", MessageColorParser.filterMiniMessage(
                "\\<red>text\\</red>", EnumSet.noneOf(MiniFeature.class)));
        String filtered = MessageColorParser.filterMiniMessage(
                "\\\\<red>text", EnumSet.noneOf(MiniFeature.class));
        assertEquals(Component.text("\\<red>text"), MINI_MESSAGE.deserialize(filtered));
    }
}
