package com.example.velocityglobalchat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Pattern;

/**
 * Listens for chat events on the proxy and broadcasts a formatted message
 * to all players on participating servers, denying the original event so
 * the backend never receives the packet and cannot echo it locally.
 */
public class GlobalChatListener {

    /**
     * Detects common MiniMessage tag patterns such as {@code <red>}, {@code </bold>},
     * {@code <#ff0000>}, {@code <!italic>}, {@code <gradient:red:blue>}.
     * Used to auto-select the correct Adventure deserializer.
     */
    private static final Pattern MINI_MESSAGE_TAG =
            Pattern.compile("<[!/]?[a-zA-Z#][a-zA-Z0-9_:#.-]*(?::[^>]*)?>", Pattern.CASE_INSENSITIVE);

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final ProxyServer server;
    private final Config config;
    private final LuckPermsHook luckPerms;

    public GlobalChatListener(ProxyServer server, Config config, LuckPermsHook luckPerms) {
        this.server   = server;
        this.config   = config;
        this.luckPerms = luckPerms;
    }

    // -------------------------------------------------------------------------
    // Event handler
    // -------------------------------------------------------------------------

    @Subscribe(order = PostOrder.LAST)
    public void onPlayerChat(PlayerChatEvent event) {
        if (!config.isEnabled()) return;

        Player player = event.getPlayer();

        // Resolve current backend server
        String serverName = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse(null);

        if (serverName == null) return;

        // Check sender's server is included in the configured list
        if (!config.getServers().isEmpty()
                && !config.getServers().contains(serverName.toLowerCase())) {
            return;
        }

        String prefix      = luckPerms.getPrefix(player);
        String suffix      = luckPerms.getSuffix(player);
        String group       = luckPerms.getGroup(player);
        String message     = event.getMessage();
        String displayName = config.getDisplayName(serverName);

        Component formatted = buildComponent(displayName, player.getUsername(), prefix, suffix, group, message);

        broadcast(formatted);

        // Deny the event so Velocity does NOT forward the chat packet to the backend.
        // This prevents the backend from echoing the message locally (which would cause
        // players to see the message twice — once from the proxy broadcast above and once
        // from the backend's local chat event).
        //
        // ChatResult.denied() is safe on Velocity 3.3+ / Minecraft 1.19.3+: the proxy
        // simply swallows the packet without any client-visible warning or disconnect.
        // The earlier concern about disconnects only applied to Minecraft 1.19.1–1.19.2
        // strict-signing enforcement, which was relaxed in 1.19.3.
        event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    /**
     * Builds the final Adventure Component.
     *
     * <p>The format string is split at {@code {message}} so the player's message
     * content is always inserted as a plain-text Component — this prevents
     * players from injecting colour codes or MiniMessage tags into the chat
     * format and impersonating prefixes or formatting other players' names.</p>
     */
    private Component buildComponent(String serverName, String playerName,
                                     String prefix, String suffix, String group, String message) {
        String format = config.getFormat();

        // Split at the {message} placeholder (max 2 parts)
        String[] parts = format.split("\\{message\\}", 2);

        String prefixPart = applyPlaceholders(parts[0], serverName, playerName, prefix, suffix, group);
        String suffixPart = parts.length > 1
            ? applyPlaceholders(parts[1], serverName, playerName, prefix, suffix, group)
                : "";

        Component prefixComp  = deserialize(prefixPart);
        Component messageComp = Component.text(message);   // always plain — no injection
        Component suffixComp  = suffixPart.isEmpty()
                ? Component.empty()
                : deserialize(suffixPart);

        return prefixComp.append(messageComp).append(suffixComp);
    }

    private static String applyPlaceholders(String text, String serverName,
                            String playerName, String prefix,
                            String suffix, String group) {
        return text
                .replace("{server}",  serverName)
                .replace("{player}",  playerName)
            .replace("{prefix}",  prefix)
            .replace("{suffix}",  suffix)
            .replace("{group}",   group);
    }

    /**
     * Auto-detects the format used in the string and returns the appropriate
     * Adventure Component:
     * <ul>
     *   <li>MiniMessage — if the text contains {@code <tag>} patterns</li>
     *   <li>Legacy {@code &} codes — otherwise (the default config style)</li>
     * </ul>
     */
    static Component deserialize(String text) {
        if (text.isEmpty()) return Component.empty();
        if (!MINI_MESSAGE_TAG.matcher(text).find()) {
            return LEGACY.deserialize(text);
        }

        StringBuilder mm = new StringBuilder(text.length() * 2);
        String activeLegacy = null;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (ch == '&' && i + 1 < text.length()) {
                String tag = legacyToMiniMessageTag(text, i);
                if (tag != null) {
                    if ("<reset>".equals(tag)) {
                        if (activeLegacy != null) {
                            mm.append(closeLegacyTag(activeLegacy));
                            activeLegacy = null;
                        }
                        mm.append("<reset>");
                        i++;
                        continue;
                    }
                    if (activeLegacy != null && !activeLegacy.equals(tag)) {
                        mm.append(closeLegacyTag(activeLegacy));
                        activeLegacy = null;
                    }
                    mm.append(tag);
                    activeLegacy = tag;
                    i++;
                    continue;
                }
            }

            if (activeLegacy != null && ch == ' ') {
                int next = i + 1;
                while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                    next++;
                }
                if (next < text.length() && text.charAt(next) == '<') {
                    int end = text.indexOf('>', next);
                    if (end > next) {
                        String token = text.substring(next, end + 1);
                        if (MINI_MESSAGE_TAG.matcher(token).matches()) {
                            mm.append(closeLegacyTag(activeLegacy));
                            activeLegacy = null;
                            i = next - 1;
                            continue;
                        }
                    }
                }
            }

            if (ch == '<') {
                int end = text.indexOf('>', i);
                if (end > i) {
                    String token = text.substring(i, end + 1);
                    if (MINI_MESSAGE_TAG.matcher(token).matches()) {
                        if (activeLegacy != null) {
                            mm.append(closeLegacyTag(activeLegacy));
                            activeLegacy = null;
                        }
                        mm.append(token);
                        i = end;
                        continue;
                    }
                }
            }

            mm.append(ch);
        }

        if (activeLegacy != null) {
            mm.append(closeLegacyTag(activeLegacy));
        }

        return MINI_MESSAGE.deserialize(mm.toString());
    }

    private static String legacyToMiniMessageTag(String text, int index) {
        char code = Character.toLowerCase(text.charAt(index + 1));
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }

    private static String closeLegacyTag(String tag) {
        return switch (tag) {
            case "<black>" -> "</black>";
            case "<dark_blue>" -> "</dark_blue>";
            case "<dark_green>" -> "</dark_green>";
            case "<dark_aqua>" -> "</dark_aqua>";
            case "<dark_red>" -> "</dark_red>";
            case "<dark_purple>" -> "</dark_purple>";
            case "<gold>" -> "</gold>";
            case "<gray>" -> "</gray>";
            case "<dark_gray>" -> "</dark_gray>";
            case "<blue>" -> "</blue>";
            case "<green>" -> "</green>";
            case "<aqua>" -> "</aqua>";
            case "<red>" -> "</red>";
            case "<light_purple>" -> "</light_purple>";
            case "<yellow>" -> "</yellow>";
            case "<white>" -> "</white>";
            case "<obfuscated>" -> "</obfuscated>";
            case "<bold>" -> "</bold>";
            case "<strikethrough>" -> "</strikethrough>";
            case "<underlined>" -> "</underlined>";
            case "<italic>" -> "</italic>";
            default -> "";
        };
    }

    // -------------------------------------------------------------------------
    // Broadcasting
    // -------------------------------------------------------------------------

    /**
     * Sends the component to every eligible player, including the sender.
     *
     * <p>SignedVelocity (required companion proxy plugin) handles the Minecraft
     * 1.19+ chat-signing handshake so that {@link PlayerChatEvent.ChatResult#denied()}
     * properly acknowledges the packet to the client. This suppresses the
     * client-side optimistic render, meaning the sender receives exactly one
     * message — the formatted broadcast sent here.</p>
     *
     * <p>Recipients are filtered so only players on servers in the configured
     * {@code servers} list receive the message; an empty list means all
     * servers.</p>
     */
    private void broadcast(Component component) {
        for (Player p : server.getAllPlayers()) {
            if (config.getServers().isEmpty()) {
                p.sendMessage(component);
            } else {
                p.getCurrentServer().ifPresent(conn -> {
                    if (config.getServers().contains(
                            conn.getServerInfo().getName().toLowerCase())) {
                        p.sendMessage(component);
                    }
                });
            }
        }
    }
}
