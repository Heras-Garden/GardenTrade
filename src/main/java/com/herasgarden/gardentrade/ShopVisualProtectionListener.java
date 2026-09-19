package com.herasgarden.gardentrade;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public final class ShopVisualProtectionListener implements Listener {
    private final ShopVisualService visuals;

    public ShopVisualProtectionListener(ShopVisualService visuals) {
        this.visuals = visuals;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (visuals.isProtectedFrame(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (visuals.isProtectedFrame(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(HangingBreakEvent event) {
        if (visuals.isProtectedFrame(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
