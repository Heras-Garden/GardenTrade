package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.claim.ClaimBlockService;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardentrade.model.ShopRecord;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
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
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShopSignListener implements Listener {
    private static final Pattern PRICE = Pattern.compile("(?i)(?:^|\\s)[BS]?\\s*(\\d+)(?:\\s*:.*)?$");

    private final JavaPlugin plugin;
    private final ShopService shops;
    private final ShopVisualService visuals;
    private final ClaimBlockService claimBlocks;
    private final int maxShops;
    private final Map<String, PendingSign> pendingSigns = new HashMap<>();

    public ShopSignListener(
            JavaPlugin plugin,
            ShopService shops,
            ShopVisualService visuals,
            ClaimBlockService claimBlocks,
            int maxShops
    ) {
        this.plugin = plugin;
        this.shops = shops;
        this.visuals = visuals;
        this.claimBlocks = claimBlocks;
        this.maxShops = Math.max(1, maxShops);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreate(SignChangeEvent event) {
        String marker = safe(event.getLine(0));
        boolean directClaimBlocks = marker.equalsIgnoreCase("[claimblocks]");
        boolean signShop = marker.equalsIgnoreCase("[signshop]") || marker.equalsIgnoreCase("[buyshop]");
        boolean adminShop = marker.equalsIgnoreCase("[adminshop]") || marker.equalsIgnoreCase("[adminbuy]");
        if (!directClaimBlocks && !signShop && !adminShop) return;

        Player player = event.getPlayer();
        boolean unlimited = directClaimBlocks || adminShop;
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
        boolean buyback = marker.equalsIgnoreCase("[buyshop]") || marker.equalsIgnoreCase("[adminbuy]")
                || priceText.toUpperCase(Locale.ROOT).startsWith("S");
        boolean claimBlockProduct = directClaimBlocks || isClaimBlockProduct(itemText);

        if (claimBlockProduct) {
            if (!unlimited || buyback) {
                GardenMessages.send(player, "Claim blocks can only be sold by an admin sign store.");
                return;
            }
            if (claimBlocks == null) {
                GardenMessages.send(player, "Claim-block sales are unavailable until the updated GardenCore is installed.");
                return;
            }
            int quantity;
            try {
                quantity = Integer.parseInt(quantityText.replace(",", ""));
                if (quantity <= 0) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                GardenMessages.send(player, "Put the number of claim blocks to sell on line 2.");
                return;
            }
            writeClaimBlockSign(event, quantity);
            GardenMessages.send(player, "Admin claim-block store created. Its price follows the live Garden economy.");
            return;
        }

        if (quantityText.isBlank() || priceText.isBlank()) {
            GardenMessages.send(player,
                    "Sign shops use line 1 [SignShop] or [AdminShop], line 2 quantity, line 3 B <price> or S <price>. Leave line 4 blank.");
            return;
        }

        int quantity;
        long price;
        try {
            quantity = Integer.parseInt(quantityText.replace(",", ""));
            price = parsePrice(priceText);
        } catch (NumberFormatException exception) {
            GardenMessages.send(player, "Use a valid quantity and price. B means customers buy; S means customers sell.");
            return;
        }

        if (itemText.isBlank()) {
            event.setLine(0, unlimited ? "[AdminShop]" : "[SignShop]");
            event.setLine(1, Integer.toString(quantity));
            event.setLine(2, (buyback ? "S " : "B ") + price);
            event.setLine(3, "Right-click item");
            pendingSigns.put(key(event.getBlock()), new PendingSign(player.getUniqueId(), quantity, price, buyback, unlimited));
            GardenMessages.send(player, "Now right-click the sign while holding the exact item this shop should use.");
            return;
        }

        Material material = Material.matchMaterial(itemText.toUpperCase(Locale.ROOT).replace(' ', '_'));
        if (material == null || material.isAir() || !material.isItem()) {
            GardenMessages.send(player, "Leave line 4 blank, then right-click the finished sign while holding the item.");
            return;
        }

        ItemStack template = new ItemStack(material);
        try {
            ShopRecord shop = unlimited
                    ? shops.createAdminSign(player, event.getBlock(), template, quantity, price, buyback)
                    : shops.createSign(player, event.getBlock(), template, quantity, price, buyback, false, maxShops);
            event.setLine(0, unlimited ? "[AdminShop]" : "[SignShop]");
            event.setLine(1, Integer.toString(shop.quantity()));
            event.setLine(2, (buyback ? "S " : "B ") + shop.price());
            event.setLine(3, shop.itemLabel());
            plugin.getServer().getScheduler().runTask(plugin, () -> visuals.refresh(shop.id()));

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
                || !(event.getClickedBlock().getState() instanceof Sign sign)) {
            return;
        }

        if (isClaimBlockSign(sign)) {
            event.setCancelled(true);
            handleClaimBlockSign(event.getPlayer(), sign, event.getAction());
            return;
        }

        PendingSign pending = pendingSigns.get(key(event.getClickedBlock()));
        if (pending != null && event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            finishPendingSign(event.getPlayer(), sign, event.getClickedBlock(), pending);
            return;
        }

        try {
            Optional<ShopRecord> found = shops.shopAt(event.getClickedBlock());
            if (found.isEmpty() && isGeneratedContainerShopSign(sign)) {
                Block container = attachedSupport(event.getClickedBlock());
                if (container != null && ShopBlockKey.supported(container)) {
                    found = shops.shopAt(container);
                }
            }
            if (found.isEmpty()) return;

            ShopRecord shop = found.get();
            if (!shop.signShop() && !isGeneratedContainerShopSign(sign)) return;

            event.setCancelled(true);
            Player player = event.getPlayer();
            if (event.getAction() == Action.LEFT_CLICK_BLOCK || shops.canManage(player, shop)) {
                GardenMessages.send(player, describe(shop));
                return;
            }

            ShopService.PurchaseResult result = shops.purchase(player, shop, 1);
            GardenMessages.send(player, result.message());
            visuals.refresh(shop.id());
        } catch (SQLException exception) {
            event.setCancelled(true);
            GardenMessages.send(event.getPlayer(), "The shop system could not update right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign sign)) return;

        if (isClaimBlockSign(sign)) {
            if (!event.getPlayer().hasPermission("gardentrade.shop.admin")) {
                event.setCancelled(true);
                GardenMessages.send(event.getPlayer(), "Only administrators can remove claim-block stores.");
            }
            return;
        }

        try {
            Optional<ShopRecord> found = shops.shopAt(event.getBlock());
            if (found.isEmpty() && isGeneratedContainerShopSign(sign)) {
                Block container = attachedSupport(event.getBlock());
                if (container != null && ShopBlockKey.supported(container)) {
                    found = shops.shopAt(container);
                }
            }
            if (found.isEmpty()) return;
            if (!found.get().signShop() && !isGeneratedContainerShopSign(sign)) return;
            if (!shops.canManage(event.getPlayer(), found.get())) {
                event.setCancelled(true);
                GardenMessages.send(event.getPlayer(), "You do not manage this shop.");
                return;
            }
            UUID shopId = found.get().id();
            shops.delete(shopId);
            plugin.getServer().getScheduler().runTask(plugin, () -> visuals.refresh(shopId));
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

    private boolean isGeneratedContainerShopSign(Sign sign) {
        return safe(sign.getLine(0)).equalsIgnoreCase("[GardenShop]");
    }

    private void finishPendingSign(Player player, Sign sign, Block block, PendingSign pending) {
        if (!pending.creator().equals(player.getUniqueId()) && !player.hasPermission("gardentrade.shop.admin")) {
            GardenMessages.send(player, "Only the shop creator can set this shop's item.");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            GardenMessages.send(player, "Hold the exact item this shop should use, then right-click the sign again.");
            return;
        }
        try {
            ShopRecord shop = pending.unlimited()
                    ? shops.createAdminSign(player, block, held, pending.quantity(), pending.price(), pending.buyback())
                    : shops.createSign(player, block, held, pending.quantity(), pending.price(), pending.buyback(), false, maxShops);
            pendingSigns.remove(key(block));
            sign.setLine(0, pending.unlimited() ? "[AdminShop]" : "[SignShop]");
            sign.setLine(1, Integer.toString(shop.quantity()));
            sign.setLine(2, (pending.buyback() ? "S " : "B ") + shop.price());
            sign.setLine(3, shop.itemLabel());
            sign.update(true, false);
            visuals.refresh(shop.id());
            GardenMessages.send(player, "Sign shop item set to " + shop.itemLabel() + ".");
            if (!pending.unlimited()) {
                GardenMessages.send(player, "Now look at the sign, use /shop link, then right-click its stock/receiving container.");
            }
        } catch (SQLException | IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        }
    }

    private String key(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void handleClaimBlockSign(Player player, Sign sign, Action action) {
        if (claimBlocks == null) {
            GardenMessages.send(player, "Claim-block sales are unavailable right now.");
            return;
        }
        int quantity;
        try {
            quantity = Integer.parseInt(safe(sign.getLine(1)).replace(",", ""));
            if (quantity <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            GardenMessages.send(player, "This claim-block sign has an invalid quantity.");
            return;
        }

        long quote = claimBlocks.quote(quantity);
        updateClaimBlockSign(sign, quantity, quote);
        if (action == Action.LEFT_CLICK_BLOCK) {
            GardenMessages.send(player, quantity + " claim blocks currently cost ⟡ " + quote + ".");
            return;
        }

        try {
            if (!claimBlocks.purchase(player.getUniqueId(), quantity)) {
                GardenMessages.send(player, "You need ⟡ " + quote + " to buy " + quantity + " claim blocks.");
                return;
            }
            GardenMessages.send(player, "Bought " + quantity + " claim blocks for ⟡ " + quote + ".");
            updateClaimBlockSign(sign, quantity, claimBlocks.quote(quantity));
        } catch (SQLException | IllegalArgumentException exception) {
            GardenMessages.send(player, "That claim-block purchase could not be completed right now.");
        }
    }

    private void writeClaimBlockSign(SignChangeEvent event, int quantity) {
        event.setLine(0, "[ClaimBlocks]");
        event.setLine(1, Integer.toString(quantity));
        event.setLine(2, "⟡ " + claimBlocks.quote(quantity));
        event.setLine(3, "Dynamic Price");
    }

    private void updateClaimBlockSign(Sign sign, int quantity, long quote) {
        sign.setLine(0, "[ClaimBlocks]");
        sign.setLine(1, Integer.toString(quantity));
        sign.setLine(2, "⟡ " + quote);
        sign.setLine(3, "Dynamic Price");
        sign.update(true, false);
    }

    private boolean isClaimBlockProduct(String value) {
        String clean = safe(value).toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return clean.equals("claim_block") || clean.equals("claim_blocks");
    }

    private boolean isClaimBlockSign(Sign sign) {
        return safe(sign.getLine(0)).equalsIgnoreCase("[ClaimBlocks]");
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

    private record PendingSign(UUID creator, int quantity, long price, boolean buyback, boolean unlimited) {}
}
