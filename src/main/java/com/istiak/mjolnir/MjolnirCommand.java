package com.istiak.mjolnir;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class MjolnirCommand implements CommandExecutor, TabCompleter {

    private final MjolnirItem mjolnirItem;

    public MjolnirCommand(MjolnirItem mjolnirItem) {
        this.mjolnirItem = mjolnirItem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mjolnir.admin")) {
            sender.sendMessage(Component.text(
                    "You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length != 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text(
                    "Usage: /mjolnir give <player>", NamedTextColor.YELLOW));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "Player '" + args[1] + "' is not online.", NamedTextColor.RED));
            return true;
        }

        ItemStack item = mjolnirItem.create();
        var leftover = target.getInventory().addItem(item);

        if (!leftover.isEmpty()) {
            for (ItemStack stack : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), stack);
            }
            sender.sendMessage(Component.text(
                    "Mjolnir was given, but the player's inventory was full; it was dropped at their location.",
                    NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text(
                    "Mjolnir given to " + target.getName() + ".", NamedTextColor.GREEN));
        }

        target.sendMessage(Component.text("⚡ You received Mjolnir.", NamedTextColor.AQUA));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("give");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            List<String> result = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(prefix)) {
                    result.add(player.getName());
                }
            }
            return result;
        }

        return List.of();
    }
}
