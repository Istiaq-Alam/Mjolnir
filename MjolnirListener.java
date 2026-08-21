package com.istiak.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MjolnirListener implements Listener {

    private final JavaPlugin plugin;
    private final MjolnirItem mjolnirItem;

    /*
     * Mode switch cooldown per player.
     */
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    /*
     * Fighting Mode Lightning Strike cooldown.
     */
    private final Map<UUID, Long> lightningStrikeCooldown =
            new HashMap<>();

    /*
     * Fighting Mode God Blast cooldown.
     */
    private final Map<UUID, Long> godBlastCooldown =
            new HashMap<>();

    /*
     * One active Mjolnir storm cleanup task per world.
     *
     * UUID = World UUID
     */
    private final Map<UUID, BukkitTask> stormTasks = new HashMap<>();

    /*
     * Stores the weather state that existed before Mjolnir
     * started its own thunderstorm.
     */
    private final Map<UUID, WeatherState> previousWeather =
            new HashMap<>();

    /*
     * Players currently performing a Mjolnir Travel flight.
     *
     * IMPORTANT:
     *
     * This state is independent of the player's CURRENT mode.
     *
     * Example:
     *
     * Travel Mode
     *      ↓
     * Riptide
     *      ↓
     * switch to Fighting Mode in the sky
     *      ↓
     * fall
     *      ↓
     * landing protection still works
     */
    private final Set<UUID> travellingPlayers =
            new HashSet<>();

    /*
     * Players who have actually left the ground after
     * starting a Mjolnir Travel flight.
     *
     * This is used by PlayerMoveEvent to reliably detect
     * the actual landing.
     */
    private final Set<UUID> airborneTravelPlayers =
            new HashSet<>();

    /*
     * When a special Fighting Mode attack is triggered,
     * the following normal melee EntityDamageByEntityEvent
     * must be cancelled.
     *
     * UUID = Player UUID
     * UUID = Target UUID
     */
    private final Map<UUID, UUID> suppressedNormalAttacks =
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
         * IMPORTANT:
         *
         * We intentionally DO NOT remove the player
         * from travellingPlayers when switching modes.
         *
         * Therefore:
         *
         * Travel → sky → Fighting → landing
         *
         * still counts as a Mjolnir Travel landing.
         */
        if (next == MjolnirItem.Mode.FIGHTING) {
            activateFightingMode(player);
        } else {
            activateTravelMode(player);
        }
    }

    /*
     * =========================================================
     * FIGHTING MODE SPECIAL ATTACK DETECTION
     * =========================================================
     *
     * PlayerAnimationEvent detects the player's left-click
     * arm swing.
     *
     * We use ray tracing to determine exactly what the player
     * is aiming at.
     *
     * Priority:
     *
     *     Jumping / airborne → God Blast
     *     Sprinting          → Lightning Strike
     *     Otherwise          → Normal Mjolnir attack
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerAnimation(
            PlayerAnimationEvent event
    ) {

        Player player =
                event.getPlayer();

        /*
         * Only main-hand Mjolnir.
         */
        ItemStack held =
                player.getInventory()
                        .getItemInMainHand();

        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        /*
         * Only Fighting Mode.
         */
        if (mjolnirItem.getMode(held)
                != MjolnirItem.Mode.FIGHTING) {
            return;
        }

        /*
         * Do not interfere with sneaking mode switching.
         *
         * Sneak + Right Click is already handled separately.
         */
        if (player.isSneaking()
                && player.isBlocking()) {
            return;
        }

        /*
         * =====================================================
         * GOD BLAST
         * =====================================================
         *
         * Jumping takes priority over sprinting.
         */
        if (!player.isOnGround()) {

            if (!plugin.getConfig()
                    .getBoolean(
                            "fighting.god-blast.enabled",
                            true
                    )) {
                return;
            }

            double range =
                    plugin.getConfig()
                            .getDouble(
                                    "fighting.god-blast.range",
                                    12.0
                            );

            LivingEntity target =
                    findFightingTarget(
                            player,
                            range
                    );

            if (target == null) {
                return;
            }

            /*
             * Only hostile mobs or players when PvP
             * is enabled.
             */
            if (!isValidFightingTarget(
                    target,
                    player,
                    "fighting.god-blast.player-damage"
            )) {
                return;
            }

            if (!isCooldownReady(
                    godBlastCooldown,
                    player,
                    "fighting.god-blast.cooldown",
                    8
            )) {
                return;
            }

            /*
             * Activate God Blast.
             */
            performGodBlast(
                    player,
                    target
            );

            /*
             * Prevent the normal melee hit from also
             * damaging the target.
             */
            suppressNormalAttack(
                    player,
                    target
            );

            return;
        }

        /*
         * =====================================================
         * LIGHTNING STRIKE
         * =====================================================
         *
         * Sprint + left click.
         */
        if (player.isSprinting()) {

            if (!plugin.getConfig()
                    .getBoolean(
                            "fighting.lightning-strike.enabled",
                            true
                    )) {
                return;
            }

            double range =
                    plugin.getConfig()
                            .getDouble(
                                    "fighting.lightning-strike.range",
                                    9.0
                            );

            LivingEntity target =
                    findFightingTarget(
                            player,
                            range
                    );

            if (target == null) {
                return;
            }

            /*
             * Only hostile mobs or players when PvP
             * is enabled.
             */
            if (!isValidFightingTarget(
                    target,
                    player,
                    "fighting.lightning-strike.player-damage"
            )) {
                return;
            }

            if (!isCooldownReady(
                    lightningStrikeCooldown,
                    player,
                    "fighting.lightning-strike.cooldown",
                    4
            )) {
                return;
            }

            /*
             * Activate Lightning Strike.
             */
            performLightningStrike(
                    player,
                    target
            );

            /*
             * Prevent normal melee damage from also
             * being applied.
             */
            suppressNormalAttack(
                    player,
                    target
            );
        }
    }

    /**
     * Handles the normal melee damage event.
     *
     * Normally we leave vanilla Mjolnir damage untouched.
     *
     * If a special attack was triggered immediately before
     * the melee event, cancel that normal hit so the player
     * doesn't get both:
     *
     *     Lightning/God Blast damage
     *             +
     *     normal melee damage
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onEntityDamageByEntity(
            EntityDamageByEntityEvent event
    ) {

        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        UUID playerId =
                player.getUniqueId();

        UUID targetId =
                suppressedNormalAttacks.get(
                        playerId
                );

        if (targetId == null) {
            return;
        }

        if (!event.getEntity()
                .getUniqueId()
                .equals(targetId)) {
            return;
        }

        /*
         * Cancel the normal melee damage because the
         * special attack has already handled the attack.
         */
        event.setCancelled(true);

        /*
         * Remove immediately.
         */
        suppressedNormalAttacks.remove(
                playerId
        );
    }

    /**
     * Finds the entity directly under the player's crosshair.
     *
     * This uses Bukkit's ray tracing instead of simply finding
     * the closest mob.
     */
    private LivingEntity findFightingTarget(
            Player player,
            double range
    ) {

        Location eye =
                player.getEyeLocation();

        Vector direction =
                eye.getDirection()
                        .normalize();

        RayTraceResult result =
                player.getWorld()
                        .rayTraceEntities(
                                eye,
                                direction,
                                range,
                                0.25,
                                entity -> {

                                    if (!(entity
                                            instanceof LivingEntity)) {
                                        return false;
                                    }

                                    /*
                                     * Never select the attacker
                                     * themselves.
                                     */
                                    if (entity
                                            .getUniqueId()
                                            .equals(
                                                    player.getUniqueId()
                                            )) {
                                        return false;
                                    }

                                    return true;
                                }
                        );

        if (result == null) {
            return null;
        }

        Entity hit =
                result.getHitEntity();

        if (!(hit instanceof LivingEntity living)) {
            return null;
        }

        return living;
    }

    /**
     * Checks whether an entity is a valid target for
     * a Fighting Mode special attack.
     *
     * Default:
     *
     *     Hostile mobs = YES
     *     Players      = NO
     *
     * If player-damage is enabled:
     *
     *     Other players = YES
     *     Attacker      = NEVER
     */
    private boolean isValidFightingTarget(
            LivingEntity target,
            Player attacker,
            String playerDamagePath
    ) {

        /*
         * Never damage the attacker themselves.
         */
        if (target.getUniqueId()
                .equals(attacker.getUniqueId())) {
            return false;
        }

        /*
         * Players are only valid when PvP damage
         * has explicitly been enabled.
         */
        if (target instanceof Player) {

            return plugin.getConfig()
                    .getBoolean(
                            playerDamagePath,
                            false
                    );
        }

        /*
         * Only hostile mobs.
         */
        return target instanceof Monster;
    }

    /**
     * Checks and consumes an ability cooldown.
     */
    private boolean isCooldownReady(
            Map<UUID, Long> cooldownMap,
            Player player,
            String configPath,
            int defaultSeconds
    ) {

        long now =
                System.currentTimeMillis();

        UUID playerId =
                player.getUniqueId();

        long cooldownEnd =
                cooldownMap.getOrDefault(
                        playerId,
                        0L
                );

        if (cooldownEnd > now) {

            long remaining =
                    cooldownEnd - now;

            long seconds =
                    Math.max(
                            1,
                            (remaining + 999L) / 1000L
                    );

            player.sendActionBar(
                    Component.text(
                            "⏳ Mjolnir ability recharging: "
                                    + seconds
                                    + "s",
                            NamedTextColor.RED
                    )
            );

            return false;
        }

        int configuredSeconds =
                Math.max(
                        0,
                        plugin.getConfig()
                                .getInt(
                                        configPath,
                                        defaultSeconds
                                )
                );

        cooldownMap.put(
                playerId,
                now + configuredSeconds * 1000L
        );

        return true;
    }

    /**
     * Prevents the vanilla melee attack from also
     * happening after a special attack.
     */
    private void suppressNormalAttack(
            Player player,
            LivingEntity target
    ) {

        UUID playerId =
                player.getUniqueId();

        suppressedNormalAttacks.put(
                playerId,
                target.getUniqueId()
        );

        /*
         * Safety cleanup.
         *
         * The actual EntityDamageByEntityEvent normally
         * happens immediately after the arm swing.
         */
        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> suppressedNormalAttacks.remove(
                                playerId
                        ),
                        2L
                );
    }

    /**
     * =========================================================
     * LIGHTNING STRIKE
     * =========================================================
     */
    private void performLightningStrike(
            Player player,
            LivingEntity target
    ) {

        Location targetLocation =
                target.getLocation()
                        .clone()
                        .add(0, 1.0, 0);

        World world =
                target.getWorld();

        /*
         * Cosmetic lightning.
         *
         * No automatic vanilla lightning damage.
         */
        world.strikeLightningEffect(
                targetLocation
        );

        /*
         * PARTICLES
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    targetLocation,
                    70,
                    0.7,
                    1.0,
                    0.7,
                    0.15
            );

            world.spawnParticle(
                    Particle.END_ROD,
                    targetLocation,
                    25,
                    0.4,
                    0.8,
                    0.4,
                    0.08
            );

            /*
             * Small electric trail between the player
             * and the target.
             */
            spawnLightningTrail(
                    player.getEyeLocation(),
                    targetLocation
            );
        }

        /*
         * SOUND
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.sounds",
                        true
                )) {

            world.playSound(
                    targetLocation,
                    Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                    1.2f,
                    1.0f
            );

            world.playSound(
                    player.getLocation(),
                    Sound.ITEM_TRIDENT_THUNDER,
                    0.8f,
                    1.15f
            );
        }

        /*
         * DAMAGE
         */
        double damage =
                plugin.getConfig()
                        .getDouble(
                                "fighting.lightning-strike.damage",
                                12.0
                        );

        damage =
                Math.max(
                        0.0,
                        damage
                );

        if (damage > 0.0) {

            target.damage(
                    damage,
                    player
            );
        }

        /*
         * CHAT ANNOUNCEMENT
         */
        broadcastAbilityMessage(
                player,
                "⚡ "
                        + player.getName()
                        + " caused a Lightning Strike!"
        );
    }

    /**
     * =========================================================
     * GOD BLAST
     * =========================================================
     */
    private void performGodBlast(
            Player player,
            LivingEntity target
    ) {

        Location center =
                target.getLocation()
                        .clone();

        World world =
                target.getWorld();

        double radius =
                plugin.getConfig()
                        .getDouble(
                                "fighting.god-blast.radius",
                                5.0
                        );

        radius =
                Math.max(
                        1.0,
                        radius
                );

        double damage =
                plugin.getConfig()
                        .getDouble(
                                "fighting.god-blast.damage",
                                18.0
                        );

        damage =
                Math.max(
                        0.0,
                        damage
                );

        double knockback =
                plugin.getConfig()
                        .getDouble(
                                "fighting.god-blast.knockback",
                                1.2
                        );

        /*
         * =====================================================
         * MAIN THUNDER IMPACT
         * =====================================================
         */

        world.strikeLightningEffect(
                center
        );

        /*
         * PARTICLES
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            /*
             * Large central electrical explosion.
             */
            world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    center.clone()
                            .add(0, 1.0, 0),
                    180,
                    2.0,
                    1.0,
                    2.0,
                    0.18
            );

            /*
             * Wind blast.
             */
            world.spawnParticle(
                    Particle.CLOUD,
                    center.clone()
                            .add(0, 0.2, 0),
                    100,
                    2.5,
                    0.15,
                    2.5,
                    0.15
            );

            /*
             * Vertical energy.
             */
            world.spawnParticle(
                    Particle.END_ROD,
                    center.clone()
                            .add(0, 1.0, 0),
                    60,
                    1.4,
                    1.5,
                    1.4,
                    0.08
            );

            /*
             * Expanding rings.
             */
            spawnGodBlastRing(
                    player,
                    center,
                    radius,
                    0L
            );

            spawnGodBlastRing(
                    player,
                    center,
                    radius * 0.65,
                    2L
            );
        }

        /*
         * SOUND
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.sounds",
                        true
                )) {

            world.playSound(
                    center,
                    Sound.ITEM_TRIDENT_THUNDER,
                    2.0f,
                    0.55f
            );

            world.playSound(
                    center,
                    Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                    2.0f,
                    0.75f
            );
        }

        /*
         * =====================================================
         * DAMAGE NEARBY HOSTILE MOBS
         * =====================================================
         */
        for (Entity entity :
                world.getNearbyEntities(
                        center,
                        radius,
                        radius,
                        radius
                )) {

            if (!(entity instanceof LivingEntity living)) {
                continue;
            }

            /*
             * Never damage the attacking player.
             */
            if (living instanceof Player) {

                /*
                 * Other players are only damaged if
                 * explicitly enabled.
                 */
                if (!plugin.getConfig()
                        .getBoolean(
                                "fighting.god-blast.player-damage",
                                false
                        )) {
                    continue;
                }

            } else {

                /*
                 * Non-player entities must be hostile mobs.
                 */
                if (!(living instanceof Monster)) {
                    continue;
                }
            }

            /*
             * Exact spherical radius check.
             */
            if (living.getLocation()
                    .distanceSquared(center)
                    > radius * radius) {
                continue;
            }

            /*
             * Do not accidentally damage the attacker.
             */
            if (living.getUniqueId()
                    .equals(player.getUniqueId())) {
                continue;
            }

            /*
             * Damage.
             */
            if (damage > 0.0) {

                living.damage(
                        damage,
                        player
                );
            }

            /*
             * Knockback.
             */
            if (knockback > 0.0) {

                Vector direction =
                        living.getLocation()
                                .toVector()
                                .subtract(
                                        center.toVector()
                                );

                /*
                 * Avoid a zero-length vector.
                 */
                if (direction.lengthSquared()
                        < 0.0001) {

                    direction =
                            new Vector(
                                    0,
                                    0.4,
                                    0
                            );

                } else {

                    direction.normalize();

                    direction.multiply(
                            knockback
                    );

                    /*
                     * Give the blast a small vertical lift.
                     */
                    direction.setY(
                            Math.max(
                                    0.35,
                                    direction.getY()
                            )
                    );
                }

                living.setVelocity(
                        direction
                );
            }

            /*
             * Individual mob lightning effect.
             */
            world.strikeLightningEffect(
                    living.getLocation()
            );
        }

        /*
         * CHAT ANNOUNCEMENT
         */
        broadcastAbilityMessage(
                player,
                "🌩️ "
                        + player.getName()
                        + " unleashed a God Blast!"
        );
    }

    /**
     * Sends the Fighting Mode ability announcement
     * to everyone in the server.
     */
    private void broadcastAbilityMessage(
            Player player,
            String message
    ) {

        plugin.getServer()
                .broadcast(
                        Component.text(
                                message,
                                NamedTextColor.AQUA
                        )
                );
    }

    /**
     * Small particle trail from the player toward
     * the Lightning Strike target.
     */
    private void spawnLightningTrail(
            Location start,
            Location end
    ) {

        Vector difference =
                end.toVector()
                        .subtract(
                                start.toVector()
                        );

        double distance =
                difference.length();

        if (distance <= 0.1) {
            return;
        }

        Vector direction =
                difference.normalize();

        double spacing = 0.5;

        int points =
                (int) Math.ceil(
                        distance / spacing
                );

        World world =
                start.getWorld();

        if (world == null) {
            return;
        }

        for (int i = 0;
             i <= points;
             i++) {

            double distanceAlong =
                    Math.min(
                            distance,
                            i * spacing
                    );

            Location point =
                    start.clone()
                            .add(
                                    direction.clone()
                                            .multiply(
                                                    distanceAlong
                                            )
                            );

            world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    point,
                    2,
                    0.08,
                    0.08,
                    0.08,
                    0.03
            );
        }
    }

    /**
     * Creates a horizontal God Blast particle ring.
     */
    private void spawnGodBlastRing(
            Player player,
            Location center,
            double radius,
            long delay
    ) {

        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {

                            if (!player.isOnline()) {
                                return;
                            }

                            World world =
                                    center.getWorld();

                            if (world == null) {
                                return;
                            }

                            int points = 40;

                            for (int i = 0;
                                 i < points;
                                 i++) {

                                double angle =
                                        (Math.PI * 2.0 * i)
                                                / points;

                                double x =
                                        Math.cos(angle)
                                                * radius;

                                double z =
                                        Math.sin(angle)
                                                * radius;

                                Location particleLocation =
                                        center.clone()
                                                .add(
                                                        x,
                                                        0.15,
                                                        z
                                                );

                                world.spawnParticle(
                                        Particle.CLOUD,
                                        particleLocation,
                                        1,
                                        0,
                                        0,
                                        0,
                                        0
                                );

                                if (i % 3 == 0) {

                                    world.spawnParticle(
                                            Particle.ELECTRIC_SPARK,
                                            particleLocation
                                                    .clone()
                                                    .add(
                                                            0,
                                                            0.2,
                                                            0
                                                    ),
                                            1,
                                            0,
                                            0,
                                            0,
                                            0
                                    );
                                }
                            }

                        },
                        delay
                );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onItemDamage(
            PlayerItemDamageEvent event
    ) {

        /*
         * Extra protection against durability damage.
         */
        if (mjolnirItem.isMjolnir(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /**
     * Detects when Mjolnir Travel/Riptide starts.
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onMjolnirRiptide(
            PlayerRiptideEvent event
    ) {

        Player player =
                event.getPlayer();

        ItemStack item =
                event.getItem();

        /*
         * Must be the real Mjolnir.
         */
        if (!mjolnirItem.isMjolnir(item)) {
            return;
        }

        /*
         * Riptide flight must begin in Travel Mode.
         */
        if (mjolnirItem.getMode(item)
                != MjolnirItem.Mode.TRAVEL) {
            return;
        }

        UUID playerId =
                player.getUniqueId();

        /*
         * Remember this player as a Mjolnir traveller.
         */
        travellingPlayers.add(playerId);

        /*
         * Start as soon as the player actually leaves
         * the ground.
         */
        if (!player.isOnGround()) {

            airborneTravelPlayers.add(
                    playerId
            );
        }

        /*
         * Safety cleanup after 30 seconds.
         */
        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {

                            travellingPlayers.remove(
                                    playerId
                            );

                            airborneTravelPlayers.remove(
                                    playerId
                            );

                        },
                        600L
                );
    }

    /**
     * RELIABLE LANDING DETECTOR.
     *
     * Detects:
     *
     *     AIR → GROUND
     *
     * Works in Survival and Creative.
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerMove(
            PlayerMoveEvent event
    ) {

        /*
         * Ignore movement where nothing changed.
         */
        if (event.getFrom().getX()
                == event.getTo().getX()
                && event.getFrom().getY()
                == event.getTo().getY()
                && event.getFrom().getZ()
                == event.getTo().getZ()) {
            return;
        }

        Player player =
                event.getPlayer();

        UUID playerId =
                player.getUniqueId();

        /*
         * Only Mjolnir Travel players.
         */
        if (!travellingPlayers.contains(playerId)) {
            return;
        }

        /*
         * Player is airborne.
         */
        if (!player.isOnGround()) {

            airborneTravelPlayers.add(
                    playerId
            );

            return;
        }

        /*
         * Player is on ground.
         *
         * Was airborne before?
         */
        if (!airborneTravelPlayers.contains(playerId)) {
            return;
        }

        /*
         * Actual landing.
         */
        handleTravelLanding(player);
    }

    /**
     * Fall damage protection.
     *
     * PlayerMoveEvent handles the landing animation.
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onTravelFallDamage(
            EntityDamageEvent event
    ) {

        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        /*
         * Only FALL damage.
         */
        if (event.getCause()
                != EntityDamageEvent.DamageCause.FALL) {
            return;
        }

        UUID playerId =
                player.getUniqueId();

        /*
         * Only Mjolnir Travel flights.
         */
        if (!travellingPlayers.contains(playerId)) {
            return;
        }

        /*
         * Feather Falling = zero damage.
         */
        if (hasFeatherFalling(player)) {

            event.setCancelled(true);

        } else {

            boolean cancelFallDamage =
                    plugin.getConfig()
                            .getBoolean(
                                    "travel.cancel-fall-damage",
                                    true
                            );

            if (cancelFallDamage) {

                event.setCancelled(true);

            } else {

                /*
                 * Optional maximum damage.
                 *
                 * 2.0 = one heart.
                 */
                double maximumDamage =
                        plugin.getConfig()
                                .getDouble(
                                        "travel.max-fall-damage",
                                        2.0
                                );

                maximumDamage =
                        Math.max(
                                0.0,
                                maximumDamage
                        );

                event.setDamage(
                        Math.min(
                                event.getDamage(),
                                maximumDamage
                        )
                );
            }
        }
    }

    /**
     * Handles actual Travel landing.
     */
    private void handleTravelLanding(
            Player player
    ) {

        UUID playerId =
                player.getUniqueId();

        /*
         * Remove states FIRST.
         *
         * Prevents duplicate landing detection.
         */
        travellingPlayers.remove(
                playerId
        );

        airborneTravelPlayers.remove(
                playerId
        );

        /*
         * Mjolnir must still be in the main hand.
         */
        ItemStack held =
                player.getInventory()
                        .getItemInMainHand();

        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        /*
         * Landing animation.
         */
        spawnTravelLandingEffect(
                player
        );

        /*
         * Travel Mode thunder attack.
         */
        spawnTravelThunderAttack(
                player
        );
    }

    /**
     * Checks Feather Falling.
     */
    private boolean hasFeatherFalling(
            Player player
    ) {

        ItemStack boots =
                player.getInventory().getBoots();

        if (boots == null
                || boots.getType().isAir()) {
            return false;
        }

        return boots.containsEnchantment(
                Enchantment.FEATHER_FALLING
        );
    }

    /**
     * Travel Mode landing animation.
     */
    private void spawnTravelLandingEffect(
            Player player
    ) {

        if (!plugin.getConfig()
                .getBoolean(
                        "effects.enabled",
                        true
                )) {
            return;
        }

        Location location =
                player.getLocation().clone();

        World world =
                player.getWorld();

        /*
         * PARTICLES
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            Location impact =
                    location.clone()
                            .add(0, 0.15, 0);

            /*
             * Wind blast.
             */
            world.spawnParticle(
                    Particle.CLOUD,
                    impact,
                    35,
                    0.7,
                    0.15,
                    0.7,
                    0.12
            );

            /*
             * Electric energy.
             */
            world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    location.clone()
                            .add(0, 0.4, 0),
                    45,
                    1.0,
                    0.45,
                    1.0,
                    0.12
            );

            /*
             * Vertical energy.
             */
            world.spawnParticle(
                    Particle.END_ROD,
                    location.clone()
                            .add(0, 0.8, 0),
                    20,
                    0.5,
                    0.8,
                    0.5,
                    0.08
            );

            /*
             * Expanding landing rings.
             */
            spawnLandingRing(
                    player,
                    1.0,
                    0L
            );

            spawnLandingRing(
                    player,
                    1.6,
                    2L
            );

            spawnLandingRing(
                    player,
                    2.2,
                    4L
            );

            spawnLandingRing(
                    player,
                    2.8,
                    6L
            );
        }

        /*
         * SOUND
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.sounds",
                        true
                )) {

            world.playSound(
                    location,
                    Sound.ITEM_TRIDENT_RIPTIDE_3,
                    1.0f,
                    0.8f
            );

            world.playSound(
                    location,
                    Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                    0.55f,
                    1.25f
            );
        }
    }

    /**
     * Travel Mode thunder attack.
     *
     * Radius:
     *     9 blocks by default.
     *
     * Hostile mobs only by default.
     *
     * Players are never damaged.
     */
    private void spawnTravelThunderAttack(
            Player player
    ) {

        if (!plugin.getConfig()
                .getBoolean(
                        "travel.landing-thunder.enabled",
                        true
                )) {
            return;
        }

        Location center =
                player.getLocation().clone();

        World world =
                player.getWorld();

        double radius =
                plugin.getConfig()
                        .getDouble(
                                "travel.landing-thunder.radius",
                                9.0
                        );

        radius =
                Math.max(
                        1.0,
                        radius
                );

        double damage =
                plugin.getConfig()
                        .getDouble(
                                "travel.landing-thunder.damage",
                                1000.0
                        );

        damage =
                Math.max(
                        0.0,
                        damage
                );

        /*
         * LARGE THUNDER IMPACT
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    center.clone()
                            .add(0, 0.7, 0),
                    150,
                    2.0,
                    0.8,
                    2.0,
                    0.20
            );

            world.spawnParticle(
                    Particle.CLOUD,
                    center.clone()
                            .add(0, 0.15, 0),
                    100,
                    2.5,
                    0.15,
                    2.5,
                    0.18
            );

            world.spawnParticle(
                    Particle.END_ROD,
                    center.clone()
                            .add(0, 1.0, 0),
                    70,
                    1.5,
                    1.5,
                    1.5,
                    0.10
            );
        }

        /*
         * THUNDER SOUND
         */
        if (plugin.getConfig()
                .getBoolean(
                        "effects.sounds",
                        true
                )) {

            world.playSound(
                    center,
                    Sound.ITEM_TRIDENT_THUNDER,
                    2.0f,
                    0.55f
            );

            world.playSound(
                    center,
                    Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                    2.0f,
                    0.75f
            );
        }

        /*
         * FIND NEARBY ENTITIES
         */
        for (Entity entity :
                world.getNearbyEntities(
                        center,
                        radius,
                        radius,
                        radius
                )) {

            if (!(entity instanceof LivingEntity living)) {
                continue;
            }

            /*
             * NEVER damage players.
             */
            if (living instanceof Player) {
                continue;
            }

            /*
             * Only hostile mobs.
             */
            if (!(living instanceof Monster)) {
                continue;
            }

            /*
             * Exact spherical radius.
             */
            if (living.getLocation()
                    .distanceSquared(center)
                    > radius * radius) {
                continue;
            }

            /*
             * Cosmetic lightning.
             */
            world.strikeLightningEffect(
                    living.getLocation()
            );

            /*
             * MOB PARTICLES
             */
            if (plugin.getConfig()
                    .getBoolean(
                            "effects.particles",
                            true
                    )) {

                Location mobLocation =
                        living.getLocation()
                                .clone()
                                .add(0, 1.0, 0);

                world.spawnParticle(
                        Particle.ELECTRIC_SPARK,
                        mobLocation,
                        45,
                        0.6,
                        0.9,
                        0.6,
                        0.15
                );

                world.spawnParticle(
                        Particle.END_ROD,
                        mobLocation,
                        15,
                        0.3,
                        0.7,
                        0.3,
                        0.08
                );
            }

            /*
             * ACTUAL MOB DAMAGE
             */
            if (damage > 0.0) {

                living.damage(
                        damage,
                        player
                );
            }
        }
    }

    /**
     * Fighting Mode activation.
     */
    private void activateFightingMode(
            Player player
    ) {

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
         * Start/reset Mjolnir thunderstorm.
         */
        startThunderstorm(
                player.getWorld()
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
         * Cosmetic lightning only.
         */
        player.getWorld().strikeLightningEffect(
                playerLocation
        );

        /*
         * PARTICLES
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
         * SOUNDS
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

    /**
     * Travel Mode activation.
     */
    private void activateTravelMode(
            Player player
    ) {

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
         * PARTICLES
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
         * SOUNDS
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
     * Creates one horizontal expanding Travel landing ring.
     */
    private void spawnLandingRing(
            Player player,
            double radius,
            long delay
    ) {

        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {

                            if (!player.isOnline()) {
                                return;
                            }

                            Location center =
                                    player.getLocation()
                                            .clone()
                                            .add(0, 0.15, 0);

                            World world =
                                    player.getWorld();

                            int points = 32;

                            for (int i = 0;
                                 i < points;
                                 i++) {

                                double angle =
                                        (Math.PI * 2.0 * i)
                                                / points;

                                double x =
                                        Math.cos(angle)
                                                * radius;

                                double z =
                                        Math.sin(angle)
                                                * radius;

                                Location particleLocation =
                                        center.clone()
                                                .add(
                                                        x,
                                                        0,
                                                        z
                                                );

                                world.spawnParticle(
                                        Particle.CLOUD,
                                        particleLocation,
                                        1,
                                        0,
                                        0,
                                        0,
                                        0
                                );

                                if (i % 3 == 0) {

                                    world.spawnParticle(
                                            Particle.ELECTRIC_SPARK,
                                            particleLocation
                                                    .clone()
                                                    .add(
                                                            0,
                                                            0.15,
                                                            0
                                                    ),
                                            1,
                                            0,
                                            0,
                                            0,
                                            0
                                    );
                                }
                            }

                        },
                        delay
                );
    }

    /**
     * Starts a Mjolnir-owned thunderstorm.
     */
    private void startThunderstorm(
            World world
    ) {

        int configuredSeconds =
                Math.max(
                        1,
                        plugin.getConfig().getInt(
                                "storm-duration",
                                180
                        )
                );

        long configuredTicks =
                (long) configuredSeconds * 20L;

        UUID worldId =
                world.getUID();

        BukkitTask oldTask =
                stormTasks.remove(worldId);

        /*
         * If Mjolnir already owns an active storm,
         * cancel the previous cleanup timer.
         */
        if (oldTask != null) {

            oldTask.cancel();

        } else {

            /*
             * Save original weather.
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
        int safeTicks =
                (int) Math.min(
                        configuredTicks,
                        Integer.MAX_VALUE
                );

        /*
         * Start Mjolnir thunderstorm.
         */
        world.setStorm(true);
        world.setThundering(true);

        world.setWeatherDuration(
                safeTicks
        );

        world.setThunderDuration(
                safeTicks
        );

        /*
         * Schedule cleanup.
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
     * Restores previous weather.
     */
    private void restoreWeather(
            World world
    ) {

        UUID worldId =
                world.getUID();

        stormTasks.remove(
                worldId
        );

        WeatherState previous =
                previousWeather.remove(
                        worldId
                );

        if (previous == null) {
            return;
        }

        world.setStorm(
                previous.storm
        );

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

            this.storm =
                    storm;

            this.thundering =
                    thundering;

            this.weatherDuration =
                    weatherDuration;

            this.thunderDuration =
                    thunderDuration;
        }
    }
}
