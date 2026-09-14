package com.example.velocityglobalchat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalChatListenerTest {

    @Test
    void mixedLegacyAndMiniMessageFormattingShouldBlendWithoutLosingColors() {
        Component parsed = GlobalChatListener.deserialize("&8[&bHub&8] &7Player <red>fencraft</red>");

        assertEquals(
                Component.text()
                        .append(Component.text("[", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Hub", NamedTextColor.AQUA))
                        .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Player", NamedTextColor.GRAY))
                        .append(Component.text("fencraft", NamedTextColor.RED))
                        .build(),
                parsed
        );
    }
}
