package com.istiak.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.persistence.PersistentDataType;
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
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
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

        // Fully suppress vanilla trident behavior (throw / Riptide launch) so
        // sneak + right click is exclusively a mode-switch input while holding Mjolnir.
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        long now = System.currentTimeMillis();
        long remaining = cooldownUntil.getOrDefault(player.getUniqueId(), 0L) - now;
        if (remaining > 0) {
            long seconds = Math.max(1, (remaining + 999L) / 1000L);
            player.sendActionBar(Component.text(
                    "⏳ Mjolnir recharging: " + seconds + "s",
                    NamedTextColor.RED
            ));
            return;
        }

        MjolnirItem.Mode current = mjolnirItem.getMode(held);
        MjolnirItem.Mode next = current == MjolnirItem.Mode.TRAVEL
                ? MjolnirItem.Mode.FIGHTING
                : MjolnirItem.Mode.TRAVEL;

        mjolnirItem.applyMode(held, next);
        player.getInventory().setItemInMainHand(held);

        int cooldownSeconds = Math.max(0, plugin.getConfig().getInt("mode-switch-cooldown", 5));
        cooldownUntil.put(
                player.getUniqueId(),
                now + cooldownSeconds * 1000L
        );

        if (next == MjolnirItem.Mode.FIGHTING) {
            activateFightingMode(player);
        } else {
            activateTravelMode(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (mjolnirItem.isMjolnir(event.getItem())) {
            // Defense-in-depth: Mjolnir is already marked unbreakable,
            // but this also prevents any plugin from applying durability damage.
            event.setCancelled(true);
        }
    }

    private void activateFightingMode(Player player) {
        player.sendActionBar(Component.text(
                "⚡ Mjolnir: FIGHTING MODE",
                NamedTextColor.YELLOW
        ));

        startThunderstorm(player.getWorld());

        if (!plugin.getConfig().getBoolean("effects.enabled", true)) {
            return;
        }

        if (plugin.getConfig().getBoolean("effects.particles", true)) {
            var location = player.getLocation().add(0, 1.0, 0);
            player.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK, location, 40, 0.8, 1.0, 0.8, 0.08
            );
            player.getWorld().spawnParticle(
                    Particle.END_ROD, location, 24, 0.6, 0.9, 0.6, 0.03
            );
        }

        if (plugin.getConfig().getBoolean("effects.sounds", true)) {
            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.ITEM_TRIDENT_THUNDER,
                    1.0f,
                    0.8f
            );
            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                    0.65f,
                    1.0f
            );
        }
    }

    private void activateTravelMode(Player player) {
        player.sendActionBar(Component.text(
                "🌊 Mjolnir: TRAVEL MODE",
                NamedTextColor.AQUA
        ));

        if (!plugin.getConfig().getBoolean("effects.enabled", true)) {
            return;
        }

        if (plugin.getConfig().getBoolean("effects.particles", true)) {
            var location = player.getLocation().add(0, 1.0, 0);
            player.getWorld().spawnParticle(
                    Particle.END_ROD, location, 28, 0.8, 0.8, 0.8, 0.05
            );
        }

        if (plugin.getConfig().getBoolean("effects.sounds", true)) {
            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.ITEM_TRIDENT_RIPTIDE_2,
                    0.9f,
                    1.1f
            );
        }
    }

    private void startThunderstorm(World world) {
        int configuredSeconds = Math.max(
                0,
                plugin.getConfig().getInt("storm-duration", 180)
        );
        int configuredTicks = Math.min(
                Integer.MAX_VALUE,
                configuredSeconds * 20
        );

        // Never shorten an existing longer storm. This avoids weather stacking
        // or unexpectedly reducing a storm another plugin/player already created.
        int weatherDuration = Math.max(world.getWeatherDuration(), configuredTicks);
        int thunderDuration = Math.max(world.getThunderDuration(), configuredTicks);

        world.setStorm(true);
        world.setThundering(true);
        world.setWeatherDuration(weatherDuration);
        world.setThunderDuration(thunderDuration);
    }
}
