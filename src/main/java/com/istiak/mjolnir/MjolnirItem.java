package com.istiak.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class MjolnirItem {

    public enum Mode {
        TRAVEL,
        FIGHTING
    }

    /*
     * These are the PDC keys used to identify Mjolnir and store
     * its current mode.
     */
    private final NamespacedKey itemKey;
    private final NamespacedKey modeKey;

    /*
     * Resource-pack item models.
     *
     * IMPORTANT:
     * These names must match the item-model identifiers in your
     * Mjolnir resource pack.
     *
     * Namespace:
     *     mjolnir
     *
     * Travel:
     *     mjolnir:mjolnir
     *
     * Fighting:
     *     mjolnir:mjolnir_fighting
     */
    private static final NamespacedKey TRAVEL_SKIN_MODEL =
            new NamespacedKey(
                    "mjolnir",
                    "mjolnir"
            );

    private static final NamespacedKey FIGHTING_SKIN_MODEL =
            new NamespacedKey(
                    "mjolnir",
                    "mjolnir_fighting"
            );

    public MjolnirItem(
            NamespacedKey itemKey,
            NamespacedKey modeKey
    ) {
        this.itemKey = itemKey;
        this.modeKey = modeKey;
    }

    /**
     * Creates a new Mjolnir.
     */
    public ItemStack create() {

        ItemStack item = new ItemStack(Material.TRIDENT);

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        /*
         * Default Mjolnir name.
         */
        meta.customName(
                Component.text(
                        "Mjolnir",
                        NamedTextColor.AQUA
                ).decoration(
                        TextDecoration.BOLD,
                        true
                )
        );

        item.setItemMeta(meta);

        /*
         * Start in Travel Mode.
         */
        applyMode(
                item,
                Mode.TRAVEL
        );

        return item;
    }

    /**
     * Checks whether an item is the real Mjolnir.
     *
     * Identification is based on PDC, not the visible name.
     */
    public boolean isMjolnir(ItemStack item) {

        if (item == null) {
            return false;
        }

        if (item.getType() != Material.TRIDENT) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return false;
        }

        Byte marker =
                meta.getPersistentDataContainer().get(
                        itemKey,
                        PersistentDataType.BYTE
                );

        return marker != null
                && marker == (byte) 1;
    }

    /**
     * Returns the current Mjolnir mode.
     */
    public Mode getMode(ItemStack item) {

        if (!isMjolnir(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return null;
        }

        String value =
                meta.getPersistentDataContainer().get(
                        modeKey,
                        PersistentDataType.STRING
                );

        if ("fighting".equalsIgnoreCase(value)) {
            return Mode.FIGHTING;
        }

        return Mode.TRAVEL;
    }

    /**
     * Applies the selected Mjolnir mode.
     *
     * This method:
     *
     * - keeps the PDC identity
     * - updates the mode
     * - makes Mjolnir unbreakable
     * - removes old enchantments
     * - applies the correct enchantments
     * - changes the resource-pack item model
     * - updates the lore
     * - preserves the current custom name
     */
    public void applyMode(
            ItemStack item,
            Mode mode
    ) {

        if (item == null) {
            return;
        }

        if (mode == null) {
            mode = Mode.TRAVEL;
        }

        /*
         * Mjolnir is always a trident.
         */
        if (item.getType() != Material.TRIDENT) {
            item.setType(Material.TRIDENT);
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return;
        }

        /*
         * Preserve the current custom name.
         *
         * This is important because the resource-pack appearance
         * may also be affected by the item's displayed name in
         * other resource-pack systems.
         */
        Component currentName =
                meta.hasCustomName()
                        ? meta.customName()
                        : null;

        /*
         * Persistent Data Container.
         */
        var pdc =
                meta.getPersistentDataContainer();

        /*
         * Mark this item as Mjolnir.
         */
        pdc.set(
                itemKey,
                PersistentDataType.BYTE,
                (byte) 1
        );

        /*
         * Store current mode.
         */
        pdc.set(
                modeKey,
                PersistentDataType.STRING,
                mode == Mode.FIGHTING
                        ? "fighting"
                        : "travel"
        );

        /*
         * Mjolnir can never break.
         */
        meta.setUnbreakable(true);

        /*
         * Select the correct resource-pack model.
         */
        if (mode == Mode.FIGHTING) {

            meta.setItemModel(
                    FIGHTING_SKIN_MODEL
            );

        } else {

            meta.setItemModel(
                    TRAVEL_SKIN_MODEL
            );
        }

        /*
         * Remove all Mjolnir mode enchantments first.
         *
         * This prevents incompatible enchantments from remaining
         * when switching between modes.
         */
        meta.removeEnchant(
                Enchantment.RIPTIDE
        );

        meta.removeEnchant(
                Enchantment.CHANNELING
        );

        meta.removeEnchant(
                Enchantment.IMPALING
        );

        meta.removeEnchant(
                Enchantment.LOYALTY
        );

        meta.removeEnchant(
                Enchantment.UNBREAKING
        );

        meta.removeEnchant(
                Enchantment.MENDING
        );

        /*
         * =========================
         * TRAVEL MODE
         * =========================
         */
        if (mode == Mode.TRAVEL) {

            meta.addEnchant(
                    Enchantment.RIPTIDE,
                    10,
                    true
            );

            meta.addEnchant(
                    Enchantment.UNBREAKING,
                    10,
                    true
            );

            meta.addEnchant(
                    Enchantment.MENDING,
                    1,
                    true
            );

            meta.lore(
                    travelLore()
            );

        } else {

            /*
             * =========================
             * FIGHTING MODE
             * =========================
             */

            meta.addEnchant(
                    Enchantment.CHANNELING,
                    1,
                    true
            );

            meta.addEnchant(
                    Enchantment.IMPALING,
                    10,
                    true
            );

            meta.addEnchant(
                    Enchantment.LOYALTY,
                    10,
                    true
            );

            meta.addEnchant(
                    Enchantment.UNBREAKING,
                    10,
                    true
            );

            meta.addEnchant(
                    Enchantment.MENDING,
                    1,
                    true
            );

            meta.lore(
                    fightingLore()
            );
        }

        /*
         * Restore the original custom name.
         */
        if (currentName != null) {
            meta.customName(currentName);
        }

        /*
         * Finally write the metadata back to the ItemStack.
         */
        item.setItemMeta(meta);
    }

    /**
     * Travel Mode lore.
     */
    private List<Component> travelLore() {

        return List.of(

                Component.empty(),

                Component.text(
                        "🌊 Travel Mode",
                        NamedTextColor.AQUA
                ),

                Component.empty(),

                Component.text(
                        "Riptide X",
                        NamedTextColor.GRAY
                ),

                Component.text(
                        "Unbreaking X",
                        NamedTextColor.GRAY
                ),

                Component.text(
                        "Mending I",
                        NamedTextColor.GRAY
                ),

                Component.empty(),

                Component.text(
                        "Sneak + Right Click to switch mode",
                        NamedTextColor.DARK_GRAY
                )
        );
    }

    /**
     * Fighting Mode lore.
     */
    private List<Component> fightingLore() {

        return List.of(

                Component.empty(),

                Component.text(
                        "⚡ Fighting Mode",
                        NamedTextColor.YELLOW
                ),

                Component.empty(),

                Component.text(
                        "Channeling I",
                        NamedTextColor.GRAY
                ),

                Component.text(
                        "Impaling X",
                        NamedTextColor.GRAY
                ),

                Component.text(
                        "Loyalty X",
                        NamedTextColor.GRAY
                ),

                Component.text(
                        "Unbreaking X",
                        NamedTextColor.GRAY
                ),

                Component.text(
                        "Mending I",
                        NamedTextColor.GRAY
                ),

                Component.empty(),

                Component.text(
                        "Sneak + Right Click to switch mode",
                        NamedTextColor.DARK_GRAY
                )
        );
    }
}
