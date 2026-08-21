package com.istiak.mjolnir;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class MjolnirPlugin extends JavaPlugin {

    private NamespacedKey itemKey;
    private NamespacedKey modeKey;

    private MjolnirItem mjolnirItem;

    @Override
    public void onEnable() {

        /*
         * Create config.yml if it does not exist.
         */
        saveDefaultConfig();

        /*
         * PDC keys.
         *
         * These are stored inside the Mjolnir ItemStack.
         */
        itemKey =
                new NamespacedKey(
                        this,
                        "mjolnir"
                );

        modeKey =
                new NamespacedKey(
                        this,
                        "mode"
                );

        /*
         * Create the Mjolnir item manager.
         */
        mjolnirItem =
                new MjolnirItem(
                        itemKey,
                        modeKey
                );

        /*
         * =========================
         * COMMAND
         * =========================
         */
        MjolnirCommand mjolnirCommand =
                new MjolnirCommand(
                        mjolnirItem
                );

        if (getCommand("mjolnir") == null) {

            getLogger().severe(
                    "The /mjolnir command is missing from plugin.yml!"
            );

            getServer().getPluginManager().disablePlugin(this);

            return;
        }

        getCommand("mjolnir")
                .setExecutor(
                        mjolnirCommand
                );

        getCommand("mjolnir")
                .setTabCompleter(
                        mjolnirCommand
                );

        /*
         * =========================
         * MJOLNIR GAMEPLAY LISTENER
         * =========================
         */
        getServer()
                .getPluginManager()
                .registerEvents(
                        new MjolnirListener(
                                this,
                                mjolnirItem
                        ),
                        this
                );

        /*
         * =========================
         * RESOURCE PACK LISTENER
         * =========================
         */
        getServer()
                .getPluginManager()
                .registerEvents(
                        new MjolnirResourcePack(
                                this
                        ),
                        this
                );

        getLogger().info(
                "Mjolnir 1.0.0 enabled for Paper 26.1.2."
        );
    }

    @Override
    public void onDisable() {

        getLogger().info(
                "Mjolnir disabled."
        );
    }
}
