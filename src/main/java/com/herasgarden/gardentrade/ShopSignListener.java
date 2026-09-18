package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardentrade.model.ShopRecord;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.sign.Side;
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
    private static final Pattern BUY_PRICE = Pattern.compile("(?i)(?:^|\\s)B?\\s*(\\d+)(?:\\s*:.*)?$");

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
        Block signBlock = event.getBlock();
        Block container = attachedSupport(signBlock);
        if (container == null || !ShopBlockKey.supported(container)) {
            return;
        }

        String marker = safe(event.getLine(0));
        if (!marker.isBlank() && !marker.equalsIgnoreCase("[shop]")) {
            return;
        }

        String quantityText = safe(event.getLine(1));
        String priceText = safe(event.getLine(2));
        if (quantityText.isBlank() || priceText.isBlank()) {
            return;
        }

        int quantity;
        long price;
        try {
            quantity = Integer.parseInt(quantityText.replace(",", ""));
            price = parseBuyPrice(priceText);
        } catch (NumberFormatException exception) {
            GardenMessages.send(event.getPlayer(),
                    "Shop signs use: line 1 [Shop], line 2 quantity, line 3 B price, line 4 ?.");
            return;
        }

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            GardenMessages.send(player, "Hold the item this shop should sell while creating the sign.");
            return;
        }

        try {
            ShopRecord shop = shops.create(player, container, held, quantity, price, maxShops);
            event.setLine(0, player.getName());
            event.setLine(1, Integer.toString(shop.quantity()));
            event.setLine(2, "B " + shop.price());
            event.setLine(3, shop.itemLabel());
            plugin.getServer().getScheduler().runTask(plugin, visuals::refresh);
            GardenMessages.send(player,
                    "Chest shop created: " + shop.quantity() + " " + shop.itemLabel()
                            + " for ⟡ " + shop.price() + ".");
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
                || !(event.getClickedBlock().getState() instanceof Sign sign)
                || !isShopSign(sign)) {
            return;
        }

        Block container = attachedSupport(event.getClickedBlock());
        if (container == null || !ShopBlockKey.supported(container)) {
            return;
        }

        try {
            Optional<ShopRecord> found = shops.shopAt(container);
            if (found.isEmpty()) {
                return;
            }
            event.setCancelled(true);
            ShopRecord shop = found.get();
            Player player = event.getPlayer();

            if (event.getAction() == Action.LEFT_CLICK_BLOCK || shops.canManage(player, shop)) {
                GardenMessages.send(player,
                        shop.quantity() + " " + shop.itemLabel() + " for ⟡ " + shop.price()
                                + " per purchase.");
                return;
            }

            ShopService.PurchaseResult result = shops.purchase(player, shop, 1);
            GardenMessages.send(player, result.message());
            visuals.refresh();
        } catch (SQLException exception) {
            GardenMessages.send(event.getPlayer(), "The shop system could not update right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign sign) || !isShopSign(sign)) {
            return;
        }
        Block container = attachedSupport(event.getBlock());
        if (container == null || !ShopBlockKey.supported(container)) {
            return;
        }

        try {
            Optional<ShopRecord> found = shops.shopAt(container);
            if (found.isEmpty()) {
                return;
            }
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

    private Block attachedSupport(Block signBlock) {
        String material = signBlock.getType().name();
        if (!material.contains("_WALL_") || !material.endsWith("_SIGN")
                || !(signBlock.getBlockData() instanceof Directional directional)) {
            return null;
        }
        return signBlock.getRelative(directional.getFacing().getOppositeFace());
    }

    private boolean isShopSign(Sign sign) {
        String price = sign.getSide(Side.FRONT).getLine(2);
        return price != null && price.trim().toUpperCase(Locale.ROOT).startsWith("B ");
    }

    private long parseBuyPrice(String value) {
        String clean = value.trim().replace(",", "");
        Matcher matcher = BUY_PRICE.matcher(clean);
        if (!matcher.find()) {
            throw new NumberFormatException("Invalid shop price");
        }
        long price = Long.parseLong(matcher.group(1));
        if (price <= 0) {
            throw new NumberFormatException("Invalid shop price");
        }
        return price;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
