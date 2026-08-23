package com.istiak.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    private final Map<UUID, Long> cooldownUntil =
            new HashMap<>();

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
     * Fighting Mode Mjolnir Smash cooldown.
     */
    private final Map<UUID, Long> mjolnirSmashCooldown =
            new HashMap<>();

    /*
     * One active Mjolnir storm cleanup task per world.
     */
    private final Map<UUID, BukkitTask> stormTasks =
            new HashMap<>();

    /*
     * Stores the weather state that existed before Mjolnir
     * started its own thunderstorm.
     */
    private final Map<UUID, WeatherState> previousWeather =
            new HashMap<>();

    /*
     * Players currently performing a Mjolnir Travel flight.
     */
    private final Set<UUID> travellingPlayers =
            new HashSet<>();

    /*
     * Players who have actually left the ground after
     * starting a Mjolnir Travel flight.
     */
    private final Set<UUID> airborneTravelPlayers =
            new HashSet<>();

    /*
     * When Lightning Strike or God Blast is triggered,
     * suppress the following normal melee attack.
     */
    private final Map<UUID, UUID> suppressedNormalAttacks =
            new HashMap<>();

    public MjolnirListener(
            JavaPlugin plugin,
            MjolnirItem mjolnirItem
    ) {
        this.plugin = plugin;
        this.mjolnirItem = mjolnirItem;

        startThrownMjolnirVisualTask();
    }

    /*
     * =========================================================
     * MODE SWITCHING
     * =========================================================
     */

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
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

        ItemStack held =
                player.getInventory().getItemInMainHand();

        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        long now = System.currentTimeMillis();

        long remaining =
                cooldownUntil.getOrDefault(
                        player.getUniqueId(),
                        0L
                ) - now;

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

        MjolnirItem.Mode current =
                mjolnirItem.getMode(held);

        MjolnirItem.Mode next =
                current == MjolnirItem.Mode.TRAVEL
                        ? MjolnirItem.Mode.FIGHTING
                        : MjolnirItem.Mode.TRAVEL;

        mjolnirItem.applyMode(
                held,
                next
        );

        player.getInventory().setItemInMainHand(held);

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
         * Do not remove Travel state here.
         *
         * Travel -> sky -> Fighting -> landing
         *
         * must still count as a Travel landing.
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
     * Priority:
     *
     * Airborne -> God Blast
     * Sprinting -> Lightning Strike
     * Normal melee -> Mjolnir Smash
     */

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerAnimation(
            PlayerAnimationEvent event
    ) {

        Player player = event.getPlayer();

        ItemStack held =
                player.getInventory()
                        .getItemInMainHand();

        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        if (mjolnirItem.getMode(held)
                != MjolnirItem.Mode.FIGHTING) {
            return;
        }

        /*
         * =====================================================
         * GOD BLAST
         * =====================================================
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

            performGodBlast(
                    player,
                    target
            );

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

            performLightningStrike(
                    player,
                    target
            );

            suppressNormalAttack(
                    player,
                    target
            );
        }
    }

    /*
     * =========================================================
     * NORMAL MELEE + MJOLNIR SMASH
     * =========================================================
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

        UUID playerId = player.getUniqueId();

        /*
         * =====================================================
         * SUPPRESS NORMAL DAMAGE
         *
         * Lightning Strike and God Blast already apply
         * their own custom damage.
         * =====================================================
         */

        UUID suppressedTarget =
                suppressedNormalAttacks.get(playerId);

        if (suppressedTarget != null
                && event.getEntity()
                .getUniqueId()
                .equals(suppressedTarget)) {

            event.setCancelled(true);

            suppressedNormalAttacks.remove(playerId);

            return;
        }

        /*
         * =====================================================
         * MJOLNIR SMASH
         * =====================================================
         */

        if (!(event.getEntity()
                instanceof LivingEntity target)) {
            return;
        }

        ItemStack held =
                player.getInventory()
                        .getItemInMainHand();

        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        if (mjolnirItem.getMode(held)
                != MjolnirItem.Mode.FIGHTING) {
            return;
        }

        /*
         * God Blast owns airborne attacks.
         */
        if (!player.isOnGround()) {
            return;
        }

        /*
         * Lightning Strike owns sprinting attacks.
         */
        if (player.isSprinting()) {
            return;
        }

        if (!plugin.getConfig()
                .getBoolean(
                        "fighting.mjolnir-smash.enabled",
                        true
                )) {
            return;
        }

        if (!isCooldownReady(
                mjolnirSmashCooldown,
                player,
                "fighting.mjolnir-smash.cooldown",
                2
        )) {
            return;
        }

        /*
         * IMPORTANT:
         *
         * Do not cancel this event.
         *
         * The directly hit entity keeps receiving
         * the normal melee damage.
         *
         * Mjolnir Smash adds the thunder shockwave.
         */
        performMjolnirSmash(
                player,
                target
        );
    }

    /*
     * =========================================================
     * TARGETING
     * =========================================================
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

                                    return !entity
                                            .getUniqueId()
                                            .equals(
                                                    player.getUniqueId()
                                            );
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

    private boolean isValidFightingTarget(
            LivingEntity target,
            Player attacker,
            String playerDamagePath
    ) {

        if (target.getUniqueId()
                .equals(attacker.getUniqueId())) {
            return false;
        }

        if (target instanceof Player) {

            return plugin.getConfig()
                    .getBoolean(
                            playerDamagePath,
                            false
                    );
        }

        return target instanceof Monster;
    }

    /*
     * =========================================================
     * COOLDOWNS
     * =========================================================
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

    /*
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

        world.strikeLightningEffect(
                targetLocation
        );

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

            spawnLightningTrail(
                    player.getEyeLocation(),
                    targetLocation
            );
        }

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

        double damage =
                Math.max(
                        0.0,
                        plugin.getConfig()
                                .getDouble(
                                        "fighting.lightning-strike.damage",
                                        12.0
                                )
                );

        if (damage > 0.0) {

            target.damage(
                    damage,
                    player
            );
        }

        broadcastAbilityMessage(
                player,
                "⚡ "
                        + player.getName()
                        + " caused a Lightning Strike!"
        );
    }

    /*
     * =========================================================
     * GOD BLAST
     * =========================================================
     */

    private void performGodBlast(
            Player player,
            LivingEntity target
    ) {

        Location center =
                target.getLocation().clone();

        World world =
                target.getWorld();

        double radius =
                Math.max(
                        1.0,
                        plugin.getConfig()
                                .getDouble(
                                        "fighting.god-blast.radius",
                                        5.0
                                )
                );

        double damage =
                Math.max(
                        0.0,
                        plugin.getConfig()
                                .getDouble(
                                        "fighting.god-blast.damage",
                                        18.0
                                )
                );

        double knockback =
                Math.max(
                        0.0,
                        plugin.getConfig()
                                .getDouble(
                                        "fighting.god-blast.knockback",
                                        1.2
                                )
                );

        world.strikeLightningEffect(center);

        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    center.clone().add(0, 1.0, 0),
                    180,
                    2.0,
                    1.0,
                    2.0,
                    0.18
            );

            world.spawnParticle(
                    Particle.CLOUD,
                    center.clone().add(0, 0.2, 0),
                    100,
                    2.5,
                    0.15,
                    2.5,
                    0.15
            );

            world.spawnParticle(
                    Particle.END_ROD,
                    center.clone().add(0, 1.0, 0),
                    60,
                    1.4,
                    1.5,
                    1.4,
                    0.08
            );

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

            if (living.getUniqueId()
                    .equals(player.getUniqueId())) {
                continue;
            }

            if (living instanceof Player) {

                if (!plugin.getConfig()
                        .getBoolean(
                                "fighting.god-blast.player-damage",
                                false
                        )) {
                    continue;
                }

            } else if (!(living instanceof Monster)) {
                continue;
            }

            if (living.getLocation()
                    .distanceSquared(center)
                    > radius * radius) {
                continue;
            }

            if (damage > 0.0) {

                living.damage(
                        damage,
                        player
                );
            }

            if (knockback > 0.0) {

                Vector direction =
                        living.getLocation()
                                .toVector()
                                .subtract(
                                        center.toVector()
                                );

                if (direction.lengthSquared()
                        < 0.0001) {

                    direction =
                            new Vector(0, 0.4, 0);

                } else {

                    direction.normalize()
                            .multiply(knockback);

                    direction.setY(
                            Math.max(
                                    0.35,
                                    direction.getY()
                            )
                    );
                }

                living.setVelocity(direction);
            }

            world.strikeLightningEffect(
                    living.getLocation()
            );
        }

        broadcastAbilityMessage(
                player,
                "🌩️ "
                        + player.getName()
                        + " unleashed a God Blast!"
        );
    }

    /*
     * =========================================================
     * MJOLNIR SMASH
     * =========================================================
     */

    private void performMjolnirSmash(
            Player player,
            LivingEntity target
    ) {

        World world =
                target.getWorld();

        Location center =
                target.getLocation()
                        .clone()
                        .add(0, 0.2, 0);

        double radius =
                Math.max(
                        1.0,
                        plugin.getConfig()
                                .getDouble(
                                        "fighting.mjolnir-smash.radius",
                                        4.0
                                )
                );

        double damage =
                Math.max(
                        0.0,
                        plugin.getConfig()
                                .getDouble(
                                        "fighting.mjolnir-smash.damage",
                                        8.0
                                )
                );

        double knockback =
                Math.max(
                        0.0,
                        plugin.getConfig()
                                .getDouble(
                                        "fighting.mjolnir-smash.knockback",
                                        0.8
                                )
                );

        /*
         * Cosmetic thunder impact.
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

            world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    center.clone().add(0, 0.8, 0),
                    100,
                    1.2,
                    0.7,
                    1.2,
                    0.15
            );

            world.spawnParticle(
                    Particle.CLOUD,
                    center,
                    60,
                    1.5,
                    0.15,
                    1.5,
                    0.12
            );

            world.spawnParticle(
                    Particle.END_ROD,
                    center.clone().add(0, 0.8, 0),
                    35,
                    0.8,
                    1.0,
                    0.8,
                    0.06
            );

            spawnMjolnirSmashRing(
                    center,
                    radius * 0.45,
                    0L
            );

            spawnMjolnirSmashRing(
                    center,
                    radius * 0.70,
                    2L
            );

            spawnMjolnirSmashRing(
                    center,
                    radius,
                    4L
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

            world.playSound(
                    center,
                    Sound.ITEM_TRIDENT_THUNDER,
                    1.4f,
                    0.85f
            );

            world.playSound(
                    center,
                    Sound.ENTITY_LIGHTNING_BOLT_IMPACT,
                    1.3f,
                    0.75f
            );

            world.playSound(
                    center,
                    Sound.ENTITY_GENERIC_EXPLODE,
                    0.8f,
                    1.3f
            );
        }

        /*
         * AREA DAMAGE
         */
        boolean playerDamage =
                plugin.getConfig()
                        .getBoolean(
                                "fighting.mjolnir-smash.player-damage",
                                false
                        );

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
             * Never damage the attacker.
             */
            if (living.getUniqueId()
                    .equals(player.getUniqueId())) {
                continue;
            }

            /*
             * Players are protected unless PvP damage
             * is enabled.
             */
            if (living instanceof Player) {

                if (!playerDamage) {
                    continue;
                }

            } else {

                /*
                 * Only hostile mobs.
                 */
                if (!(living instanceof Monster)) {
                    continue;
                }
            }

            /*
             * Exact spherical range.
             */
            if (living.getLocation()
                    .distanceSquared(center)
                    > radius * radius) {
                continue;
            }

            /*
             * Directly hit target already receives
             * normal melee damage.
             *
             * Do not double-damage it.
             */
            if (living.getUniqueId()
                    .equals(target.getUniqueId())) {
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

                if (direction.lengthSquared()
                        < 0.0001) {

                    direction =
                            new Vector(
                                    0,
                                    0.35,
                                    0
                            );

                } else {

                    direction.normalize()
                            .multiply(knockback);

                    direction.setY(
                            Math.max(
                                    0.25,
                                    direction.getY()
                            )
                    );
                }

                living.setVelocity(
                        direction
                );
            }

            /*
             * Individual lightning effect.
             */
            world.strikeLightningEffect(
                    living.getLocation()
            );
        }

        player.sendActionBar(
                Component.text(
                        "⚡ Mjolnir Smash!",
                        NamedTextColor.YELLOW
                )
        );
    }

    /*
     * =========================================================
     * PARTICLE HELPERS
     * =========================================================
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

    private void spawnMjolnirSmashRing(
            Location center,
            double radius,
            long delay
    ) {

        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {

                            World world =
                                    center.getWorld();

                            if (world == null) {
                                return;
                            }

                            int points = 36;

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

                                Location location =
                                        center.clone()
                                                .add(
                                                        x,
                                                        0.12,
                                                        z
                                                );

                                world.spawnParticle(
                                        Particle.CLOUD,
                                        location,
                                        1,
                                        0,
                                        0,
                                        0,
                                        0
                                );

                                if (i % 2 == 0) {

                                    world.spawnParticle(
                                            Particle.ELECTRIC_SPARK,
                                            location.clone()
                                                    .add(
                                                            0,
                                                            0.12,
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

    /*
     * =========================================================
     * THROWN MJOLNIR VISUAL
     * =========================================================
     */

    private static final NamespacedKey THROWING_MODEL =
            new NamespacedKey("mjolnir", "mjolnir_flying");

    private static final NamespacedKey FIGHTING_THROWING_MODEL =
            new NamespacedKey("mjolnir", "mjolnir_flying_fighting");

    private final Map<UUID, ItemDisplay> thrownMjolnirDisplays =
            new HashMap<>();

    private BukkitTask thrownMjolnirVisualTask;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMjolnirProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;

        ItemStack thrownItem = trident.getItemStack();
        if (!mjolnirItem.isMjolnir(thrownItem)) return;

        UUID projectileId = trident.getUniqueId();
        removeThrownMjolnirVisual(projectileId);

        ItemStack visualItem = thrownItem.clone();
        ItemMeta meta = visualItem.getItemMeta();
        if (meta != null) {
            MjolnirItem.Mode mode = mjolnirItem.getMode(thrownItem);
            meta.setItemModel(mode == MjolnirItem.Mode.FIGHTING
                    ? FIGHTING_THROWING_MODEL
                    : THROWING_MODEL);
            visualItem.setItemMeta(meta);
        }

        /*
         * Hide the real Trident before the client receives/updates its
         * normal entity rendering.  ProjectileLaunchEvent is an
         * EntitySpawnEvent, so this is the reliable server-side way to
         * keep the functional Trident while showing only our ItemDisplay.
         */
        trident.setVisibleByDefault(false);
        trident.setInvisible(true);

        ItemDisplay display = trident.getWorld().spawn(
                trident.getLocation(), ItemDisplay.class);
        display.setVisibleByDefault(true);
        display.setItemStack(visualItem);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setGravity(false);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        display.setViewRange(64.0f);

        thrownMjolnirDisplays.put(projectileId, display);

        updateThrownMjolnirVisual(trident, display);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMjolnirProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;
        if (!thrownMjolnirDisplays.containsKey(trident.getUniqueId())) return;
        if (trident.isDead() || !trident.isValid()) {
            removeThrownMjolnirVisual(trident.getUniqueId());
        }
    }

    private void startThrownMjolnirVisualTask() {
        if (thrownMjolnirVisualTask != null) thrownMjolnirVisualTask.cancel();

        thrownMjolnirVisualTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                () -> {
                    Set<UUID> ids = new HashSet<>(thrownMjolnirDisplays.keySet());
                    for (UUID projectileId : ids) {
                        ItemDisplay display = thrownMjolnirDisplays.get(projectileId);
                        if (display == null || display.isDead() || !display.isValid()) {
                            thrownMjolnirDisplays.remove(projectileId);
                            continue;
                        }

                        Entity entity = plugin.getServer().getEntity(projectileId);
                        if (!(entity instanceof Trident trident)
                                || trident.isDead() || !trident.isValid()) {
                            removeThrownMjolnirVisual(projectileId);
                            continue;
                        }

                        trident.setInvisible(true);
                        updateThrownMjolnirVisual(trident, display);
                    }
                }, 1L, 1L);
    }

    private void updateThrownMjolnirVisual(Trident trident, ItemDisplay display) {
        Location location = trident.getLocation();
        Vector velocity = trident.getVelocity();

        display.teleport(location);

        if (velocity.lengthSquared() <= 0.0001) {
            return;
        }

        Vector direction = velocity.clone().normalize();

        /*
         * IMPORTANT: the actual Mjolnir model is built vertically on its
         * local Y axis. The handle extends toward local -Y and the hammer
         * head is toward local +Y.
         *
         * Therefore the HAMMER HEAD must be aligned with the projectile
         * velocity, not local +X. Align local +Y with the real throw
         * direction so the hammer head leads and the handle trails behind.
         */
        // The Mjolnir model is NOT aligned on local +Y. Its handle runs
        // diagonally through the model from approximately (2,2) toward
        // (13,13), while the hammer head sits at the +X/+Y end.
        // Therefore the hammer's true forward/impact axis is the local
        // diagonal (+X,+Y,0), not +Y.
        //
        // Align that handle-to-head axis with the projectile velocity.
        // This makes the HAMMER HEAD lead the flight and the handle trail
        // behind it. On Loyalty return the velocity reverses, so the hammer
        // naturally turns around and the hammer head still leads the return.
        float invSqrt2 = 0.70710677f;
        Quaternionf rotation = new Quaternionf().rotationTo(
                invSqrt2,
                invSqrt2,
                0.0f,
                (float) direction.getX(),
                (float) direction.getY(),
                (float) direction.getZ()
        );

        /*
         * The real projectile's collision point is at the display origin.
         * The Mjolnir hammer head sits forward on the local diagonal
         * (+X,+Y,0) shaft axis, so we need to nudge the display back
         * along that SAME axis so the head's impact face lines up with
         * the projectile's actual collision point.
         *
         * BUG THAT WAS HERE: Transformation's translation is applied in
         * the entity's world/parent space, AFTER leftRotation - it does
         * NOT get rotated by `rotation` automatically. The old code fed
         * in a fixed vector (-0.70*invSqrt2, -0.70*invSqrt2, 0), which
         * only happened to look right when the hammer was flying toward
         * local +X/+Y and was wrong (misaligned head/handle) for every
         * other throw angle, including the return trip.
         *
         * Fix: rotate the local "backward along the shaft" offset by the
         * same `rotation` quaternion before using it as the translation,
         * so it always points backward along the CURRENT shaft direction
         * no matter which way the hammer is flying.
         */
        Vector3f offset = new Vector3f(
                -0.70f * invSqrt2,
                -0.70f * invSqrt2,
                0.0f
        );
        rotation.transform(offset);

        display.setTransformation(new Transformation(
                offset,
                rotation,
                new Vector3f(1.0f, 1.0f, 1.0f),
                new Quaternionf()
        ));
    }

    private void removeThrownMjolnirVisual(UUID projectileId) {
        ItemDisplay display = thrownMjolnirDisplays.remove(projectileId);
        if (display != null && !display.isDead()) display.remove();
    }

    /*
     * =========================================================
     * ITEM DURABILITY
     * =========================================================
     */

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onItemDamage(
            PlayerItemDamageEvent event
    ) {

        if (mjolnirItem.isMjolnir(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /*
     * =========================================================
     * TRAVEL FLIGHT DETECTION
     * =========================================================
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

        if (!mjolnirItem.isMjolnir(item)) {
            return;
        }

        if (mjolnirItem.getMode(item)
                != MjolnirItem.Mode.TRAVEL) {
            return;
        }

        UUID playerId =
                player.getUniqueId();

        travellingPlayers.add(playerId);

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

    /*
     * =========================================================
     * RELIABLE LANDING DETECTION
     * =========================================================
     */

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerMove(
            PlayerMoveEvent event
    ) {

        if (event.getTo() == null) {
            return;
        }

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

        if (!travellingPlayers.contains(playerId)) {
            return;
        }

        if (!player.isOnGround()) {

            airborneTravelPlayers.add(
                    playerId
            );

            return;
        }

        if (!airborneTravelPlayers.contains(playerId)) {
            return;
        }

        handleTravelLanding(player);
    }

    /*
     * =========================================================
     * FALL DAMAGE PROTECTION
     * =========================================================
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

        if (event.getCause()
                != EntityDamageEvent.DamageCause.FALL) {
            return;
        }

        /*
         * Gate purely on "is the Mjolnir in the player's main
         * hand right now" - NOT on the travellingPlayers flight
         * tracking set. That set only reflects the plugin's own
         * notion of "currently mid-flight" and is cleared by
         * handleTravelLanding() before this event is processed,
         * so it can never protect the actual landing hit - and
         * it never covered Fighting Mode at all.
         *
         * This applies in every mode. Holding anything else in
         * the main hand (even if a Mjolnir sits in the offhand
         * or elsewhere in the inventory) gets normal fall damage.
         */
        ItemStack held =
                player.getInventory()
                        .getItemInMainHand();

        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        /*
         * Feather Falling = zero damage.
         */
        if (hasFeatherFalling(player)) {

            event.setCancelled(true);

            return;
        }

        boolean cancelFallDamage =
                plugin.getConfig()
                        .getBoolean(
                                "travel.cancel-fall-damage",
                                true
                        );

        if (cancelFallDamage) {

            event.setCancelled(true);

            return;
        }

        /*
         * 2.0 = one heart.
         */
        double maximumDamage =
                Math.max(
                        0.0,
                        plugin.getConfig()
                                .getDouble(
                                        "travel.max-fall-damage",
                                        2.0
                                )
                );

        event.setDamage(
                Math.min(
                        event.getDamage(),
                        maximumDamage
                )
        );
    }

    /*
     * =========================================================
     * TRAVEL LANDING
     * =========================================================
     */

    private void handleTravelLanding(
            Player player
    ) {

        UUID playerId =
                player.getUniqueId();

        travellingPlayers.remove(playerId);
        airborneTravelPlayers.remove(playerId);

        ItemStack held =
                player.getInventory()
                        .getItemInMainHand();

        if (!mjolnirItem.isMjolnir(held)) {
            return;
        }

        spawnTravelLandingEffect(player);

        spawnTravelThunderAttack(player);
    }

    private boolean hasFeatherFalling(
            Player player
    ) {

        ItemStack boots =
                player.getInventory()
                        .getBoots();

        if (boots == null
                || boots.getType().isAir()) {
            return false;
        }

        return boots.containsEnchantment(
                Enchantment.FEATHER_FALLING
        );
    }

    /*
     * =========================================================
     * TRAVEL LANDING EFFECT
     * =========================================================
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

        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            Location impact =
                    location.clone()
                            .add(0, 0.15, 0);

            world.spawnParticle(
                    Particle.CLOUD,
                    impact,
                    35,
                    0.7,
                    0.15,
                    0.7,
                    0.12
            );

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

    /*
     * =========================================================
     * TRAVEL LANDING THUNDER ATTACK
     * =========================================================
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
                Math.max(
                        1.0,
                        plugin.getConfig()
                                .getDouble(
                                        "travel.landing-thunder.radius",
                                        9.0
                                )
                );

        double damage =
                Math.max(
                        0.0,
                        plugin.getConfig()
                                .getDouble(
                                        "travel.landing-thunder.damage",
                                        1000.0
                                )
                );

        if (plugin.getConfig()
                .getBoolean(
                        "effects.particles",
                        true
                )) {

            world.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    center.clone().add(0, 0.7, 0),
                    150,
                    2.0,
                    0.8,
                    2.0,
                    0.20
            );

            world.spawnParticle(
                    Particle.CLOUD,
                    center.clone().add(0, 0.15, 0),
                    100,
                    2.5,
                    0.15,
                    2.5,
                    0.18
            );

            world.spawnParticle(
                    Particle.END_ROD,
                    center.clone().add(0, 1.0, 0),
                    70,
                    1.5,
                    1.5,
                    1.5,
                    0.10
            );
        }

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
             * Never damage players.
             */
            if (living instanceof Player) {
                continue;
            }

            if (!(living instanceof Monster)) {
                continue;
            }

            if (living.getLocation()
                    .distanceSquared(center)
                    > radius * radius) {
                continue;
            }

            world.strikeLightningEffect(
                    living.getLocation()
            );

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

            if (damage > 0.0) {

                living.damage(
                        damage,
                        player
                );
            }
        }
    }

    /*
     * =========================================================
     * MODE ACTIVATION
     * =========================================================
     */

    private void activateFightingMode(
            Player player
    ) {

        player.sendActionBar(
                Component.text(
                        "⚡ Mjolnir: FIGHTING MODE",
                        NamedTextColor.YELLOW
                )
        );

        startThunderstorm(
                player.getWorld()
        );

        if (!plugin.getConfig()
                .getBoolean(
                        "effects.enabled",
                        true
                )) {
            return;
        }

        Location playerLocation =
                player.getLocation();

        player.getWorld().strikeLightningEffect(
                playerLocation
        );

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

    private void activateTravelMode(
            Player player
    ) {

        player.sendActionBar(
                Component.text(
                        "🌊 Mjolnir: TRAVEL MODE",
                        NamedTextColor.AQUA
                )
        );

        if (!plugin.getConfig()
                .getBoolean(
                        "effects.enabled",
                        true
                )) {
            return;
        }

        Location playerLocation =
                player.getLocation();

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

    /*
     * =========================================================
     * TRAVEL LANDING PARTICLE RING
     * =========================================================
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

    /*
     * =========================================================
     * WEATHER SYSTEM
     * =========================================================
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

        if (oldTask != null) {

            oldTask.cancel();

        } else {

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

        int safeTicks =
                (int) Math.min(
                        configuredTicks,
                        Integer.MAX_VALUE
                );

        world.setStorm(true);
        world.setThundering(true);

        world.setWeatherDuration(
                safeTicks
        );

        world.setThunderDuration(
                safeTicks
        );

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

    private void restoreWeather(
            World world
    ) {

        UUID worldId =
                world.getUID();

        stormTasks.remove(worldId);

        WeatherState previous =
                previousWeather.remove(worldId);

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

    /*
     * =========================================================
     * CHAT ANNOUNCEMENT
     * =========================================================
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

    /*
     * =========================================================
     * WEATHER STATE
     * =========================================================
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
