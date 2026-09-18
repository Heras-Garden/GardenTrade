package com.herasgarden.gardentrade;

import com.herasgarden.gardentrade.model.ShopRecord;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.sql.SQLException;
import java.util.Optional;

public final class ShopCleanupListener implements Listener {
    private final ShopService shops;

    public ShopCleanupListener(ShopService shops) {
        this.shops = shops;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!ShopBlockKey.supported(event.getBlock())) return;

        try {
            Optional<ShopRecord> shop = shops.shopAt(event.getBlock());
            if (shop.isPresent()) {
                shops.delete(shop.get().id());
            }
        } catch (SQLException exception) {
            // If cleanup cannot be persisted, canceling this late is unsafe.
            // The stale shop will fail purchases because its container is gone.
        }
    }
}
