package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardentrade.model.ShopRecord;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.sql.SQLException;
import java.util.Optional;

public final class ShopContainerListener implements Listener {
    private final ShopService shops;
    private final ShopVisualService visuals;

    public ShopContainerListener(ShopService shops, ShopVisualService visuals) {
        this.shops = shops;
        this.visuals = visuals;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if ((event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK)
                || event.getClickedBlock() == null) {
            return;
        }

        Block block = event.getClickedBlock();
        if (!ShopBlockKey.supported(block)) return;

        Player player = event.getPlayer();
        try {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                ShopService.LinkResult link = shops.completePendingStockLink(player, block);
                if (link.handled()) {
                    event.setCancelled(true);
                    GardenMessages.send(player, link.message());
                    visuals.refresh();
                    return;
                }
            }

            Optional<ShopRecord> found = shops.shopAt(block);
            if (found.isEmpty() || !found.get().containerShop()) return;

            ShopRecord shop = found.get();
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
                GardenMessages.send(player, describe(shop));
                return;
            }

            if (shops.canManage(player, shop)) {
                return;
            }

            event.setCancelled(true);
            ShopService.PurchaseResult result = shops.purchase(player, shop, 1);
            GardenMessages.send(player, result.message());
            visuals.refresh();
        } catch (SQLException exception) {
            event.setCancelled(true);
            GardenMessages.send(player, "The shop system could not update right now.");
        }
    }

    private String describe(ShopRecord shop) {
        String action = shop.buysFromCustomer() ? "Buys " : "Sells ";
        return action + shop.quantity() + " " + shop.itemLabel() + " for ⟡ "
                + shop.price() + " per transaction.";
    }
}
