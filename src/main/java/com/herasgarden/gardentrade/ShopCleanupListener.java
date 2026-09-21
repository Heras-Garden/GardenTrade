package com.herasgarden.gardentrade;

import com.herasgarden.gardentrade.model.ShopRecord;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class ShopCleanupListener implements Listener {
    private final ShopService shops;
    private final ShopVisualService visuals;

    public ShopCleanupListener(ShopService shops, ShopVisualService visuals) {
        this.shops = shops;
        this.visuals = visuals;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        try {
            Optional<ShopRecord> storefront = shops.shopAt(event.getBlock());
            if (storefront.isPresent()) {
                shops.delete(storefront.get().id());
                visuals.removeShopVisuals(storefront.get().id());
            }

            if (!ShopBlockKey.supported(event.getBlock())) return;
            List<ShopRecord> linked = shops.shopsUsingStock(event.getBlock());
            for (ShopRecord shop : linked) {
                if (shop.signShop()) shops.unlinkStockBecauseBroken(shop.id());
            }
        } catch (SQLException exception) {
            event.getPlayer().getServer().getLogger().warning(
                    "GardenTrade cleanup failed at " + event.getBlock().getWorld().getName()
                            + " " + event.getBlock().getX() + "," + event.getBlock().getY() + ","
                            + event.getBlock().getZ() + ": " + exception.getMessage());
        }
    }
}
