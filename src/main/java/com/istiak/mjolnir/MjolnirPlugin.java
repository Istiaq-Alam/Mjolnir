package com.istiak.mjolnir;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class MjolnirPlugin extends JavaPlugin {

    private NamespacedKey itemKey;
    private NamespacedKey modeKey;
    private MjolnirItem mjolnirItem;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        itemKey = new NamespacedKey(this, "mjolnir");
        modeKey = new NamespacedKey(this, "mode");
        mjolnirItem = new MjolnirItem(itemKey, modeKey);

        MjolnirCommand mjolnirCommand = new MjolnirCommand(mjolnirItem);
        getCommand("mjolnir").setExecutor(mjolnirCommand);
        getCommand("mjolnir").setTabCompleter(mjolnirCommand);

        getServer().getPluginManager().registerEvents(
                new MjolnirListener(this, mjolnirItem),
                this
        );

        getLogger().info("Mjolnir 1.0.0 enabled for Paper 26.1.2.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Mjolnir disabled.");
    }
}
