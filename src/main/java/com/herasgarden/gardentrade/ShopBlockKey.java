package com.herasgarden.gardentrade;

import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public record ShopBlockKey(UUID worldId, int x, int y, int z) {
    public static boolean supported(Block block) {
        return block != null && block.getState() instanceof Container;
    }

    public static ShopBlockKey of(Block block) {
        if (!supported(block)) {
            throw new IllegalArgumentException("Look directly at a chest, barrel, or other container.");
        }
        if (block.getState() instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest doubleChest) {
                Block left = blockOf(doubleChest.getLeftSide());
                Block right = blockOf(doubleChest.getRightSide());
                if (left != null && right != null) {
                    Block canonical = compare(left, right) <= 0 ? left : right;
                    return direct(canonical);
                }
            }
        }
        return direct(block);
    }

    private static Block blockOf(InventoryHolder holder) {
        return holder instanceof Container container ? container.getBlock() : null;
    }

    private static ShopBlockKey direct(Block block) {
        return new ShopBlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private static int compare(Block a, Block b) {
        int x = Integer.compare(a.getX(), b.getX());
        if (x != 0) return x;
        int y = Integer.compare(a.getY(), b.getY());
        if (y != 0) return y;
        return Integer.compare(a.getZ(), b.getZ());
    }
}
