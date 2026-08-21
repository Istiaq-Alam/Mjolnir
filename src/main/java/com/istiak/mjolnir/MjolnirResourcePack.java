package com.istiak.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class MjolnirResourcePack implements Listener {

    private final JavaPlugin plugin;

    /*
     * Unique ID for the Mjolnir resource pack.
     *
     * This is important because Minecraft can distinguish this
     * pack from other resource packs installed by the server.
     */
    private static final UUID MJOLNIR_RESOURCE_PACK_ID =
            UUID.fromString(
                    "7d3b8c2e-6f41-4a9e-9d52-1c7b5e8a4f30"
            );

    public MjolnirResourcePack(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Send the Mjolnir resource pack after the player joins.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {

        if (!plugin.getConfig().getBoolean(
                "resource-pack.enabled",
                true
        )) {
            return;
        }

        if (!plugin.getConfig().getBoolean(
                "resource-pack.send-on-join",
                true
        )) {
            return;
        }

        Player player = event.getPlayer();

        long delay = Math.max(
                0L,
                plugin.getConfig().getLong(
                        "resource-pack.send-delay-ticks",
                        40L
                )
        );

        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {

                            if (!player.isOnline()) {
                                return;
                            }

                            addResourcePack(player);

                        },
                        delay
                );
    }

    /**
     * Adds the Mjolnir resource pack to the client's
     * resource-pack stack.
     *
     * IMPORTANT:
     *
     * This uses addResourcePack().
     *
     * Do NOT use setResourcePack() here.
     *
     * setResourcePack() replaces/switches the active pack.
     * addResourcePack() adds another pack.
     */
    private void addResourcePack(Player player) {

        String url =
                plugin.getConfig().getString(
                        "resource-pack.url",
                        ""
                );

        if (url == null || url.isBlank()) {

            plugin.getLogger().warning(
                    "Mjolnir resource pack is enabled, "
                            + "but resource-pack.url is empty."
            );

            return;
        }

        String sha1 =
                plugin.getConfig().getString(
                        "resource-pack.sha1",
                        ""
                );

        if (sha1 == null) {
            sha1 = "";
        }

        sha1 = sha1.trim();

        boolean required =
                plugin.getConfig().getBoolean(
                        "resource-pack.required",
                        false
                );

        String promptText =
                plugin.getConfig().getString(
                        "resource-pack.prompt",
                        "Download the Mjolnir resource pack."
                );

        /*
         * The old API uses a byte[] SHA-1.
         *
         * A SHA-1 digest is 20 bytes.
         */
        byte[] hashBytes = null;

        if (!sha1.isBlank()) {

            hashBytes = hexToBytes(sha1);

            if (hashBytes == null) {

                plugin.getLogger().warning(
                        "Invalid Mjolnir resource-pack SHA-1. "
                                + "Expected 40 hexadecimal characters."
                );

                return;
            }
        }

        /*
         * Add the pack instead of replacing the server pack.
         */
        player.addResourcePack(
                MJOLNIR_RESOURCE_PACK_ID,
                url,
                hashBytes,
                promptText,
                required
        );

        plugin.getLogger().info(
                "Added Mjolnir resource pack to "
                        + player.getName()
        );
    }

    /**
     * Converts a 40-character hexadecimal SHA-1 string
     * into the 20-byte array required by addResourcePack().
     */
    private byte[] hexToBytes(String hex) {

        if (hex.length() != 40) {
            return null;
        }

        byte[] result =
                new byte[20];

        try {

            for (int i = 0; i < 20; i++) {

                int index = i * 2;

                result[i] =
                        (byte) Integer.parseInt(
                                hex.substring(
                                        index,
                                        index + 2
                                ),
                                16
                        );
            }

        } catch (NumberFormatException exception) {

            return null;
        }

        return result;
    }

    /**
     * Resource-pack status listener.
     */
    @EventHandler
    public void onResourcePackStatus(
            PlayerResourcePackStatusEvent event
    ) {

        Player player =
                event.getPlayer();

        /*
         * Only react to our Mjolnir pack.
         *
         * This prevents the Mjolnir plugin from treating
         * another server resource pack's status as its own.
         */
        if (!MJOLNIR_RESOURCE_PACK_ID.equals(
                event.getID()
        )) {
            return;
        }

        switch (event.getStatus()) {

            case ACCEPTED:

                player.sendActionBar(
                        Component.text(
                                "📦 Mjolnir pack downloading...",
                                NamedTextColor.YELLOW
                        )
                );

                break;

            case DOWNLOADED:

                player.sendActionBar(
                        Component.text(
                                "📦 Mjolnir pack downloaded.",
                                NamedTextColor.GREEN
                        )
                );

                break;

            case SUCCESSFULLY_LOADED:

                player.sendActionBar(
                        Component.text(
                                "⚡ Mjolnir resource pack loaded!",
                                NamedTextColor.AQUA
                        )
                );

                plugin.getLogger().info(
                        player.getName()
                                + " successfully loaded "
                                + "the Mjolnir resource pack."
                );

                break;

            case DECLINED:

                player.sendActionBar(
                        Component.text(
                                "⚠ Mjolnir resource pack declined.",
                                NamedTextColor.RED
                        )
                );

                plugin.getLogger().warning(
                        player.getName()
                                + " declined the Mjolnir resource pack."
                );

                break;

            case FAILED_DOWNLOAD:

                player.sendActionBar(
                        Component.text(
                                "❌ Failed to download Mjolnir pack.",
                                NamedTextColor.RED
                        )
                );

                plugin.getLogger().warning(
                        player.getName()
                                + " failed to download "
                                + "the Mjolnir resource pack."
                );

                break;

            case FAILED_RELOAD:

                player.sendActionBar(
                        Component.text(
                                "❌ Failed to load Mjolnir pack.",
                                NamedTextColor.RED
                        )
                );

                plugin.getLogger().warning(
                        player.getName()
                                + " failed to reload "
                                + "the Mjolnir resource pack."
                );

                break;

            case INVALID_URL:

                player.sendActionBar(
                        Component.text(
                                "❌ Invalid Mjolnir pack URL.",
                                NamedTextColor.RED
                        )
                );

                plugin.getLogger().warning(
                        "Invalid Mjolnir resource-pack URL."
                );

                break;

            case DISCARDED:

                plugin.getLogger().info(
                        player.getName()
                                + " discarded the Mjolnir resource pack."
                );

                break;

            default:
                break;
        }
    }
}
