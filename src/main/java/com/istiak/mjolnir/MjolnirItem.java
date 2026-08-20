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

import java.util.ArrayList;
import java.util.List;

public final class MjolnirItem {

    public enum Mode {
        TRAVEL,
        FIGHTING
    }

    private final NamespacedKey itemKey;
    private final NamespacedKey modeKey;

    public MjolnirItem(NamespacedKey itemKey, NamespacedKey modeKey) {
        this.itemKey = itemKey;
        this.modeKey = modeKey;
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        applyMode(item, Mode.TRAVEL);
        return item;
    }

    public boolean isMjolnir(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        Byte marker = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public Mode getMode(ItemStack item) {
        if (!isMjolnir(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        String value = meta.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);

        if ("fighting".equalsIgnoreCase(value)) {
            return Mode.FIGHTING;
        }
        return Mode.TRAVEL;
    }

    public void applyMode(ItemStack item, Mode mode) {
        if (item.getType() != Material.TRIDENT) {
            item.setType(Material.TRIDENT);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        var pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(modeKey, PersistentDataType.STRING,
                mode == Mode.FIGHTING ? "fighting" : "travel");

        meta.customName(Component.text("Mjolnir", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true));

        meta.setUnbreakable(true);

        // Remove both mode-specific enchantments before rebuilding the exact mode.
        meta.removeEnchant(Enchantment.RIPTIDE);
        meta.removeEnchant(Enchantment.CHANNELING);
        meta.removeEnchant(Enchantment.IMPALING);
        meta.removeEnchant(Enchantment.LOYALTY);
        meta.removeEnchant(Enchantment.UNBREAKING);
        meta.removeEnchant(Enchantment.MENDING);

        if (mode == Mode.TRAVEL) {
            meta.addEnchant(Enchantment.RIPTIDE, 10, true);
            meta.addEnchant(Enchantment.UNBREAKING, 10, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.lore(travelLore());
        } else {
            meta.addEnchant(Enchantment.CHANNELING, 1, true);
            meta.addEnchant(Enchantment.IMPALING, 10, true);
            meta.addEnchant(Enchantment.LOYALTY, 10, true);
            meta.addEnchant(Enchantment.UNBREAKING, 10, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.lore(fightingLore());
        }

        item.setItemMeta(meta);
    }

    private List<Component> travelLore() {
        return List.of(
                Component.empty(),
                Component.text("🌊 Travel Mode", NamedTextColor.AQUA),
                Component.empty(),
                Component.text("Riptide X", NamedTextColor.GRAY),
                Component.text("Unbreaking X", NamedTextColor.GRAY),
                Component.text("Mending I", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Sneak + Right Click to switch mode",
                        NamedTextColor.DARK_GRAY)
        );
    }

    private List<Component> fightingLore() {
        return List.of(
                Component.empty(),
                Component.text("⚡ Fighting Mode", NamedTextColor.YELLOW),
                Component.empty(),
                Component.text("Channeling I", NamedTextColor.GRAY),
                Component.text("Impaling X", NamedTextColor.GRAY),
                Component.text("Loyalty X", NamedTextColor.GRAY),
                Component.text("Unbreaking X", NamedTextColor.GRAY),
                Component.text("Mending I", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Sneak + Right Click to switch mode",
                        NamedTextColor.DARK_GRAY)
        );
    }
}
