package com.istiak.mjolnir;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Makes a thrown Mjolnir Trident entity invisible and renders a synchronized
 * ItemDisplay using the resource-pack Mjolnir model instead.
 *
 * Paper 26.1.2 / Java 25.
 */
public final class MjolnirThrownVisuals implements Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey mjolnirKey;
    private final NamespacedKey modeKey;
    private final Map<UUID, ItemDisplay> visuals = new HashMap<>();

    public MjolnirThrownVisuals(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mjolnirKey = new NamespacedKey(plugin, "mjolnir");
        this.modeKey = new NamespacedKey(plugin, "mode");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onMjolnirLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;
        if (!(trident.getShooter() instanceof Player player)) return;

        // Use the actual item stored by the thrown Trident. This is more reliable
        // than reading the player inventory after the throw has already started.
        ItemStack thrownItem = trident.getItem();
        if (!isFightingMode(thrownItem)) return;

        trident.setInvisible(true);

        ItemDisplay display = trident.getWorld().spawn(
                trident.getLocation(),
                ItemDisplay.class,
                d -> {
                    d.setItemStack(createVisualItem());
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setShadowRadius(0.0f);
                    d.setShadowStrength(0.0f);
                    d.setViewRange(64.0f);
                    d.setInterpolationDuration(1);
                    d.setInterpolationDelay(0);
                }
        );

        visuals.put(trident.getUniqueId(), display);
        startTracking(trident, display);
    }

    private void startTracking(Trident trident, ItemDisplay display) {
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!trident.isValid() || trident.isDead() || !display.isValid()) {
                if (display.isValid()) display.remove();
                visuals.remove(trident.getUniqueId());
                task.cancel();
                return;
            }

            Location loc = trident.getLocation();
            display.teleport(loc);

            Vector3f direction = new Vector3f(
                    (float) trident.getVelocity().getX(),
                    (float) trident.getVelocity().getY(),
                    (float) trident.getVelocity().getZ()
            );

            if (direction.lengthSquared() > 0.0001f) {
                direction.normalize();

                // The Mjolnir model's handle/head axis is local +Y.
                // Rotate +Y to the current projectile velocity so the hammer
                // visibly follows the actual throwing direction.
                Quaternionf rotation = new Quaternionf()
                        .rotationTo(new Vector3f(0, 1, 0), direction);

                display.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),
                        rotation,
                        new Vector3f(1.0f, 1.0f, 1.0f),
                        new Quaternionf()
                ));
            }
        }, 1L, 1L);
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Trident trident)) return;

        ItemDisplay display = visuals.remove(trident.getUniqueId());
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private ItemStack createVisualItem() {
        // The actual material is irrelevant: ITEM_MODEL tells the client to
        // render assets/mjolnir/items/mjolnir_flying.json.
        ItemStack visual = new ItemStack(Material.PAPER);
        ItemMeta meta = visual.getItemMeta();
        meta.setItemModel(new NamespacedKey("mjolnir", "mjolnir_flying"));
        visual.setItemMeta(meta);
        return visual;
    }

    private boolean isMjolnir(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(mjolnirKey);
    }

    private boolean isFightingMode(ItemStack item) {
        if (!isMjolnir(item)) return false;
        String mode = item.getItemMeta().getPersistentDataContainer().get(modeKey,
                org.bukkit.persistence.PersistentDataType.STRING);
        return mode != null && mode.equalsIgnoreCase("fighting");
    }
}
