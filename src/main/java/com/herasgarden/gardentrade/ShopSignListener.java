package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardentrade.model.ShopRecord;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShopSignListener implements Listener {
    private static final Pattern PRICE = Pattern.compile("(?i)(?:^|\\s)[BS]?\\s*(\\d+)(?:\\s*:.*)?$");

    private final JavaPlugin plugin;
    private final ShopService shops;
    private final ShopVisualService visuals;
    private final int maxShops;

    public ShopSignListener(JavaPlugin plugin, ShopService shops, ShopVisualService visuals, int maxShops) {
        this.plugin = plugin;
        this.shops = shops;
        this.visuals = visuals;
        this.maxShops = Math.max(1, maxShops);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreate(SignChangeEvent event) {
        String marker = safe(event.getLine(0));
        boolean sell = marker.equalsIgnoreCase("[signshop]") || marker.equalsIgnoreCase("[adminshop]");
        boolean buyback = marker.equalsIgnoreCase("[buyshop]") || marker.equalsIgnoreCase("[adminbuy]");
        if (!sell && !buyback) return;

        Player player = event.getPlayer();
        boolean unlimited = marker.equalsIgnoreCase("[adminshop]") || marker.equalsIgnoreCase("[adminbuy]");
        if (unlimited) {
            if (!player.hasPermission("gardentrade.shop.admin")) {
                GardenMessages.send(player, "Only administrators can create unlimited sign shops.");
                return;
            }
        } else if (!player.hasPermission("gardentrade.shop.sign")) {
            GardenMessages.send(player, "You do not have permission to create sign shops.");
            return;
        }

        String quantityText = safe(event.getLine(1));
        String priceText = safe(event.getLine(2));
        String itemText = safe(event.getLine(3));
        if (quantityText.isBlank() || priceText.isBlank() || itemText.isBlank()) {
            GardenMessages.send(player,
                    "Sign shops use: line 1 [SignShop] or [BuyShop], line 2 quantity, line 3 price, line 4 item.");
            return;
        }

        int quantity;
        long price;
        Material material;
        try {
            quantity = Integer.parseInt(quantityText.replace(",", ""));
            price = parsePrice(priceText);
            material = Material.matchMaterial(itemText.toUpperCase(Locale.ROOT).replace(' ', '_'));
            if (material == null || material.isAir() || !material.isItem()) {
                throw new NumberFormatException("Invalid shop item");
            }
        } catch (NumberFormatException exception) {
            GardenMessages.send(player, "Use a valid quantity, price, and Minecraft item name on the sign.");
            return;
        }

        ItemStack template = new ItemStack(material);
        try {
            ShopRecord shop = shops.createSign(
                    player, event.getBlock(), template, quantity, price, buyback, unlimited, maxShops);
            event.setLine(0, unlimited
                    ? (buyback ? "[AdminBuy]" : "[AdminShop]")
                    : (buyback ? "[BuyShop]" : "[SignShop]"));
            event.setLine(1, Integer.toString(shop.quantity()));
            event.setLine(2, (buyback ? "S " : "B ") + shop.price());
            event.setLine(3, shop.itemLabel());
            plugin.getServer().getScheduler().runTask(plugin, visuals::refresh);

            String behavior = buyback ? "buys" : "sells";
            GardenMessages.send(player, "Sign shop created: " + behavior + " "
                    + shop.quantity() + " " + shop.itemLabel() + " for ⟡ " + shop.price() + ".");
            if (!unlimited) {
                GardenMessages.send(player,
                        "Look at this sign and use /shop link, then right-click the chest or container that should hold stock.");
            }
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not create shop sign: " + exception.getMessage());
            GardenMessages.send(player, "That shop could not be saved right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if ((event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK)
                || event.getClickedBlock() == null
                || !(event.getClickedBlock().getState() instanceof Sign)) {
            return;
        }

        try {
            Optional<ShopRecord> found = shops.shopAt(event.getClickedBlock());
            if (found.isEmpty() || !found.get().signShop()) return;

            event.setCancelled(true);
            ShopRecord shop = found.get();
            Player player = event.getPlayer();
            if (event.getAction() == Action.LEFT_CLICK_BLOCK || shops.canManage(player, shop)) {
                GardenMessages.send(player, describe(shop));
                return;
            }

            ShopService.PurchaseResult result = shops.purchase(player, shop, 1);
            GardenMessages.send(player, result.message());
            visuals.refresh();
        } catch (SQLException exception) {
            event.setCancelled(true);
            GardenMessages.send(event.getPlayer(), "The shop system could not update right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign)) return;

        try {
            Optional<ShopRecord> found = shops.shopAt(event.getBlock());
            if (found.isEmpty() || !found.get().signShop()) return;
            if (!shops.canManage(event.getPlayer(), found.get())) {
                event.setCancelled(true);
                GardenMessages.send(event.getPlayer(), "You do not manage this shop.");
                return;
            }
            shops.delete(found.get().id());
            plugin.getServer().getScheduler().runTask(plugin, visuals::refresh);
            GardenMessages.send(event.getPlayer(), "Shop removed.");
        } catch (SQLException exception) {
            event.setCancelled(true);
            GardenMessages.send(event.getPlayer(), "That shop could not be removed right now.");
        }
    }

    private String describe(ShopRecord shop) {
        String action = shop.buysFromCustomer() ? "Buys " : "Sells ";
        String stock = shop.unlimitedStock()
                ? " | Unlimited stock"
                : shop.hasStockContainer() ? " | Stock connected" : " | Stock not connected";
        return action + shop.quantity() + " " + shop.itemLabel() + " for ⟡ "
                + shop.price() + stock + ".";
    }

    private long parsePrice(String value) {
        String clean = value.trim().replace(",", "");
        Matcher matcher = PRICE.matcher(clean);
        if (!matcher.find()) throw new NumberFormatException("Invalid shop price");
        long price = Long.parseLong(matcher.group(1));
        if (price <= 0) throw new NumberFormatException("Invalid shop price");
        return price;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
