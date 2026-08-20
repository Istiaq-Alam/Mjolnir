package com.istiak.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MjolnirListener implements Listener {

    private final JavaPlugin plugin;
    private final MjolnirItem mjolnirItem;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    public MjolnirListener(JavaPlugin plugin, MjolnirItem mjolnirItem) {
        this.plugin = plugin;
        this.mjolnirItem = mjolnirItem;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR
                && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();

        if (!player.isSneaking()) {
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();

        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        /*
         * Fully suppress vanilla trident behavior.
         *
         * Sneak + Right Click is used exclusively for switching
         * between Travel Mode and Fighting Mode.
         */
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        long now = System.currentTimeMillis();

        long remaining = cooldownUntil.getOrDefault(
                player.getUniqueId(),
                0L
        ) - now;

        /*
         * Check mode-switch cooldown.
         */
        if (remaining > 0) {

            long seconds = Math.max(
                    1,
                    (remaining + 999L) / 1000L
            );

            player.sendActionBar(
                    Component.text(
                            "⏳ Mjolnir recharging: "
                                    + seconds
                                    + "s",
                            NamedTextColor.RED
                    )
            );

            return;
        }

        /*
         * Get the current mode and determine the next mode.
         */
        MjolnirItem.Mode current =
                mjolnirItem.getMode(held);

        MjolnirItem.Mode next =
                current == MjolnirItem.Mode.TRAVEL
                        ? MjolnirItem.Mode.FIGHTING
                        : MjolnirItem.Mode.TRAVEL;

        /*
         * Apply the new Mjolnir mode.
         *
         * MjolnirItem.applyMode() preserves the item's current
         * custom name, allowing the Too Many Renames resource-pack
         * skin for "Mjolnir" to remain active.
         */
        mjolnirItem.applyMode(held, next);

        player.getInventory().setItemInMainHand(held);

        /*
         * Apply cooldown.
         */
        int cooldownSeconds = Math.max(
                0,
                plugin.getConfig().getInt(
                        "mode-switch-cooldown",
                        5
                )
        );

        cooldownUntil.put(
                player.getUniqueId(),
                now + cooldownSeconds * 1000L
        );

        /*
         * Activate effects for the selected mode.
         */
        if (next == MjolnirItem.Mode.FIGHTING) {
            activateFightingMode(player);
        } else {
            activateTravelMode(player);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onItemDamage(PlayerItemDamageEvent event) {

        if (mjolnirItem.isMjolnir(event.getItem())) {

            /*
             * Defense-in-depth:
             *
             * Mjolnir is already marked as unbreakable,
             * but this also prevents durability damage from
             * being applied through item damage events.
             */
            event.setCancelled(true);
        }
    }

    private void activateFightingMode(Player player) {

        /*
         * Show Fighting Mode actionbar.
         */
        player.sendActionBar(
                Component.text(
                        "⚡ Mjolnir: FIGHTING MODE",
                        NamedTextColor.YELLOW
                )
        );

        /*
         * Start the configurable thunderstorm.
         */
        startThunderstorm(player.getWorld());

        /*
         * Stop here if cosmetic effects are disabled.
         */
        if (!plugin.getConfig()
                .getBoolean("effects.enabled", true)) {
            return;
        }

        Location playerLocation = player.getLocation();

        /*
         * COSMETIC LIGHTNING STRIKE
         *
         * strikeLightningEffect() creates only the visual
         * lightning effect.
         *
         * It does NOT damage the player.
         * It does NOT damage other entities.
         * It does NOT create fire.
         * It does NOT destroy blocks.
         */
        player.getWorld().strikeLightningEffect(
                playerLocation
        );

        /*
         * PARTICLE EFFECTS
         */
        if (plugin.getConfig()
                .getBoolean("effects.particles", true)) {

            Location center = playerLocation.clone()
                    .add(0, 1.0, 0);

            /*
             * Main electric burst around the player.
             */
            player.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    center,
                    80,
                    0.9,
                    1.0,
                    0.9,
                    0.12
            );

            /*
             * Bright energy particles.
             */
            player.getWorld().spawnParticle(
                    Particle.END_ROD,
                    center,
                    40,
                    0.7,
                    1.0,
                    0.7,
                    0.05
            );

            /*
             * Electric effect around the player's feet/body.
             */
            player.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    playerLocation.clone().add(0, 0.3, 0),
                    50,
                    1.2,
                    0.3,
                    1.2,
                    0.08
            );

            /*
             * Lightning energy above the player.
             */
            player.getWorld().spawnParticle(
                    Particle.END_ROD,
                    playerLocation.clone().add(0, 2.5, 0),
                    25,
                    0.4,
                    1.0,
                    0.4,
                    0.03
            );
        }

        /*
         * SOUND EFFECTS
         */
        if (plugin.getConfig()
                .getBoolean("effects.sounds", true)) {

            /*
             * Trident thunder activation sound.
             */
            player.getWorld().playSound(
                    playerLocation,
                    Sound.ITEM_TRIDENT_THUNDER,
                    1.0f,
                    0.8f
            );

            /*
             * Main lightning thunder sound.
             */
            player.getWorld().playSound(
                    playerLocation,
                    Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                    0.8f,
                    1.0f
            );
        }
    }

    private void activateTravelMode(Player player) {

        /*
         * Show Travel Mode actionbar.
         */
        player.sendActionBar(
                Component.text(
                        "🌊 Mjolnir: TRAVEL MODE",
                        NamedTextColor.AQUA
                )
        );

        /*
         * Stop here if cosmetic effects are disabled.
         */
        if (!plugin.getConfig()
                .getBoolean("effects.enabled", true)) {
            return;
        }

        Location playerLocation = player.getLocation();

        /*
         * PARTICLE EFFECTS
         */
        if (plugin.getConfig()
                .getBoolean("effects.particles", true)) {

            Location center = playerLocation.clone()
                    .add(0, 1.0, 0);

            /*
             * Travel / energy effect.
             */
            player.getWorld().spawnParticle(
                    Particle.END_ROD,
                    center,
                    28,
                    0.8,
                    0.8,
                    0.8,
                    0.05
            );

            /*
             * Small electric transition effect.
             */
            player.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    center,
                    20,
                    0.6,
                    0.6,
                    0.6,
                    0.04
            );
        }

        /*
         * SOUND EFFECTS
         */
        if (plugin.getConfig()
                .getBoolean("effects.sounds", true)) {

            player.getWorld().playSound(
                    playerLocation,
                    Sound.ITEM_TRIDENT_RIPTIDE_2,
                    0.9f,
                    1.1f
            );
        }
    }

    private void startThunderstorm(World world) {

        int configuredSeconds = Math.max(
                0,
                plugin.getConfig().getInt(
                        "storm-duration",
                        180
                )
        );

        int configuredTicks = Math.min(
                Integer.MAX_VALUE,
                configuredSeconds * 20
        );

        /*
         * Never shorten an existing longer storm.
         *
         * This prevents the plugin from reducing a storm duration
         * that was already set by another player, command,
         * or plugin.
         */
        int weatherDuration = Math.max(
                world.getWeatherDuration(),
                configuredTicks
        );

        int thunderDuration = Math.max(
                world.getThunderDuration(),
                configuredTicks
        );

        world.setStorm(true);
        world.setThundering(true);
        world.setWeatherDuration(weatherDuration);
        world.setThunderDuration(thunderDuration);
    }
}
