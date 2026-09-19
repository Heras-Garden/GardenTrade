package com.herasgarden.gardentrade;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;

public final class ShopStockListener implements Listener {
    private final ShopVisualService visuals;

    public ShopStockListener(ShopVisualService visuals) {
        this.visuals = visuals;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        queue(event.getView().getTopInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        queue(event.getView().getTopInventory());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        queue(event.getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        queue(event.getSource());
        queue(event.getDestination());
    }

    private void queue(Inventory inventory) {
        Location location = inventory == null ? null : inventory.getLocation();
        if (location == null || location.getWorld() == null) return;
        Block block = location.getBlock();
        if (ShopBlockKey.supported(block)) visuals.queueStockRefresh(block);
    }
}
