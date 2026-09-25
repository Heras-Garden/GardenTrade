package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.sql.SQLException;

public final class BusinessAccessListener implements Listener {
    private final BusinessService businesses;
    public BusinessAccessListener(BusinessService businesses){this.businesses=businesses;}

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onDoor(PlayerInteractEvent event){
        if(event.getAction()!=Action.RIGHT_CLICK_BLOCK||event.getClickedBlock()==null)return;
        try{
            if(businesses.handleDoor(event.getPlayer(),event.getClickedBlock())){
                event.setCancelled(true);GardenMessages.send(event.getPlayer(),"This workplace is currently closed.");
            }
        }catch(SQLException ignored){}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getNewCurrent() <= 0) return;
        try {
            if (businesses.blockRedstone(event.getBlock())) {
                event.setNewCurrent(0);
            }
        } catch (SQLException ignored) {
        }
    }
}
