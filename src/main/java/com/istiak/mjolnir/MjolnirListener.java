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
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MjolnirListener implements Listener {

    private final JavaPlugin plugin;
    private final MjolnirItem mjolnirItem;

    /*
     * Mode switch cooldown per player.
     */
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    /*
     * One active Mjolnir storm cleanup task per world.
     *
     * UUID = World UUID
     */
    private final Map<UUID, BukkitTask> stormTasks = new HashMap<>();

    /*
     * Stores the weather state that existed before Mjolnir
     * started its own thunderstorm.
     *
     * This allows us to restore the world's previous weather
     * instead of always forcing clear weather.
     */
    private final Map<UUID, WeatherState> previousWeather =
            new HashMap<>();

    public MjolnirListener(
            JavaPlugin plugin,
            MjolnirItem mjolnirItem
    ) {
        this.plugin = plugin;
        this.mjolnirItem = mjolnirItem;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onInteract(PlayerInteractEvent event) {

        /*
         * Prevent duplicate processing from the off-hand.
         */
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR
                && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();

        /*
         * Mjolnir mode switching only happens while sneaking.
         */
        if (!player.isSneaking()) {
            return;
        }

        ItemStack held =
                player.getInventory().getItemInMainHand();

        /*
         * Only the real PDC-marked Mjolnir can activate.
         */
        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        /*
         * Sneak + Right Click is reserved exclusively
         * for switching Mjolnir modes.
         */
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        long now = System.currentTimeMillis();

        long remaining =
                cooldownUntil.getOrDefault(
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
         * Determine the next mode.
         */
        MjolnirItem.Mode current =
                mjolnirItem.getMode(held);

        MjolnirItem.Mode next =
                current == MjolnirItem.Mode.TRAVEL
                        ? MjolnirItem.Mode.FIGHTING
                        : MjolnirItem.Mode.TRAVEL;

        /*
         * Apply the new mode.
         */
        mjolnirItem.applyMode(
                held,
                next
        );

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
         * Activate the selected mode.
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

        /*
         * Extra protection against durability damage.
         */
        if (mjolnirItem.isMjolnir(event.getItem())) {
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
         * Start or reset the Mjolnir thunderstorm
         * for this world.
         */
        startThunderstorm(player.getWorld());

        /*
         * Stop here if cosmetic effects are disabled.
         */
        if (!plugin.getConfig()
                .getBoolean(
                        "effects.enabled",
                        true
                )) {
            return;
        }

        Location playerLocation =
                player.getLocation();

        /*
         * Cosmetic lightning only.
         *
         * No entity damage.
         * No fire.
         * No block destruction.
         */
        player.getWorld().strikeLightningEffect(
                playerLocation
        );

        /*
         * PARTICLE EFFECTS
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            Location center =
                    playerLocation.clone()
                            .add(0, 1.0, 0);

            player.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    center,
                    80,
                    0.9,
                    1.0,
                    0.9,
                    0.12
            );

            player.getWorld().spawnParticle(
                    Particle.END_ROD,
                    center,
                    40,
                    0.7,
                    1.0,
                    0.7,
                    0.05
            );

            player.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    playerLocation.clone()
                            .add(0, 0.3, 0),
                    50,
                    1.2,
                    0.3,
                    1.2,
                    0.08
            );

            player.getWorld().spawnParticle(
                    Particle.END_ROD,
                    playerLocation.clone()
                            .add(0, 2.5, 0),
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
                .getBoolean(
                        "effects.sounds",
                        true
                )) {

            player.getWorld().playSound(
                    playerLocation,
                    Sound.ITEM_TRIDENT_THUNDER,
                    1.0f,
                    0.8f
            );

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
                .getBoolean(
                        "effects.enabled",
                        true
                )) {
            return;
        }

        Location playerLocation =
                player.getLocation();

        /*
         * PARTICLE EFFECTS
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            Location center =
                    playerLocation.clone()
                            .add(0, 1.0, 0);

            player.getWorld().spawnParticle(
                    Particle.END_ROD,
                    center,
                    28,
                    0.8,
                    0.8,
                    0.8,
                    0.05
            );

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
                .getBoolean(
                        "effects.sounds",
                        true
                )) {

            player.getWorld().playSound(
                    playerLocation,
                    Sound.ITEM_TRIDENT_RIPTIDE_2,
                    0.9f,
                    1.1f
            );
        }
    }

    /**
     * Starts a thunderstorm owned and managed by Mjolnir.
     *
     * Only one cleanup task can exist per world.
     *
     * If Mjolnir is activated again before the storm ends,
     * the old timer is cancelled and a new timer starts.
     */
    private void startThunderstorm(World world) {

        int configuredSeconds = Math.max(
                1,
                plugin.getConfig().getInt(
                        "storm-duration",
                        180
                )
        );

        /*
         * Convert seconds to ticks.
         *
         * Using long prevents overflow before scheduling.
         */
        long configuredTicks =
                (long) configuredSeconds * 20L;

        UUID worldId = world.getUID();

        BukkitTask oldTask =
                stormTasks.remove(worldId);

        /*
         * If Mjolnir already owns an active storm in this world,
         * cancel the previous cleanup timer.
         */
        if (oldTask != null) {

            oldTask.cancel();

        } else {

            /*
             * Save the weather state before Mjolnir changes it.
             *
             * This happens only for the first Mjolnir activation.
             * Repeated activations should not overwrite the
             * original weather state.
             */
            previousWeather.put(
                    worldId,
                    new WeatherState(
                            world.hasStorm(),
                            world.isThundering(),
                            world.getWeatherDuration(),
                            world.getThunderDuration()
                    )
            );
        }

        /*
         * Minecraft weather durations use int ticks.
         */
        int safeTicks = (int) Math.min(
                configuredTicks,
                Integer.MAX_VALUE
        );

        /*
         * Start Mjolnir's thunderstorm.
         */
        world.setStorm(true);
        world.setThundering(true);

        /*
         * Set EXACTLY the configured duration.
         *
         * No Math.max() with an old natural weather duration.
         */
        world.setWeatherDuration(safeTicks);
        world.setThunderDuration(safeTicks);

        /*
         * Schedule exactly one cleanup task for this world.
         */
        BukkitTask cleanupTask =
                plugin.getServer()
                        .getScheduler()
                        .runTaskLater(
                                plugin,
                                () -> restoreWeather(world),
                                configuredTicks
                        );

        stormTasks.put(
                worldId,
                cleanupTask
        );
    }

    /**
     * Restores the weather that existed before
     * Mjolnir activated its thunderstorm.
     */
    private void restoreWeather(World world) {

        UUID worldId = world.getUID();

        /*
         * Remove the active task marker.
         */
        stormTasks.remove(worldId);

        WeatherState previous =
                previousWeather.remove(worldId);

        /*
         * Safety check.
         */
        if (previous == null) {
            return;
        }

        /*
         * Restore the exact previous weather state.
         */
        world.setStorm(previous.storm);
        world.setThundering(
                previous.thundering
        );

        world.setWeatherDuration(
                previous.weatherDuration
        );

        world.setThunderDuration(
                previous.thunderDuration
        );
    }

    /**
     * Stores the world's weather state before
     * Mjolnir starts its temporary thunderstorm.
     */
    private static final class WeatherState {

        private final boolean storm;
        private final boolean thundering;

        private final int weatherDuration;
        private final int thunderDuration;

        private WeatherState(
                boolean storm,
                boolean thundering,
                int weatherDuration,
                int thunderDuration
        ) {
            this.storm = storm;
            this.thundering = thundering;

            this.weatherDuration =
                    weatherDuration;

            this.thunderDuration =
                    thunderDuration;
        }
    }
}
