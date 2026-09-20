package com.herasgarden.gardentrade;

import com.herasgarden.gardentrade.model.ShopRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ShopVisualService {
    private final JavaPlugin plugin;
    private final ShopService shops;
    private final NamespacedKey shopKey;
    private final NamespacedKey typeKey;
    private final Map<UUID, VisualSet> visuals = new HashMap<>();
    private final Map<String, Block> pendingStockRefresh = new HashMap<>();
    private BukkitTask maintenanceTask;
    private boolean stockRefreshScheduled;

    public ShopVisualService(JavaPlugin plugin, ShopService shops) {
        this.plugin = plugin;
        this.shops = shops;
        this.shopKey = new NamespacedKey(plugin, "shop-visual-id");
        this.typeKey = new NamespacedKey(plugin, "shop-visual-type");
    }

    public void start() {
        cleanupTaggedEntities();
        refresh();
        maintenanceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> refresh(), 1200L, 1200L);
    }

    public void stop() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
        for (UUID shopId : Set.copyOf(visuals.keySet())) removeVisuals(shopId);
        pendingStockRefresh.clear();
    }

    /**
     * Refresh all enabled shops without scanning every entity in every world.
     * The old implementation performed a global entity scan every five seconds,
     * which became very expensive on populated worlds.
     */
    public void refresh() {
        try {
            Set<UUID> active = new HashSet<>();
            for (ShopRecord shop : shops.all()) {
                active.add(shop.id());
                sync(shop);
            }
            for (UUID shopId : Set.copyOf(visuals.keySet())) {
                if (!active.contains(shopId)) removeVisuals(shopId);
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().warning("Could not refresh Garden shop visuals: " + exception.getMessage());
        }
    }

    public void refresh(UUID shopId) {
        if (shopId == null) return;
        try {
            Optional<ShopRecord> found = shops.find(shopId);
            if (found.isEmpty() || !found.get().enabled()) {
                removeVisuals(shopId);
                return;
            }
            sync(found.get());
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().warning("Could not refresh shop visual " + shopId + ": " + exception.getMessage());
        }
    }

    public void queueStockRefresh(Block block) {
        if (block == null || !ShopBlockKey.supported(block)) return;
        String key = block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        pendingStockRefresh.put(key, block);
        if (stockRefreshScheduled) return;

        stockRefreshScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            stockRefreshScheduled = false;
            Map<String, Block> pending = new HashMap<>(pendingStockRefresh);
            pendingStockRefresh.clear();
            for (Block changed : pending.values()) refreshForStock(changed);
        });
    }

    public void refreshForStock(Block block) {
        if (block == null) return;
        try {
            Set<UUID> affected = new HashSet<>();
            for (ShopRecord shop : shops.shopsUsingStock(block)) affected.add(shop.id());
            Optional<ShopRecord> storefront = shops.shopAt(block);
            if (storefront.isPresent() && storefront.get().containerShop()) affected.add(storefront.get().id());
            for (UUID shopId : affected) refresh(shopId);
        } catch (SQLException | IllegalArgumentException exception) {
            plugin.getLogger().warning("Could not refresh shop stock visual: " + exception.getMessage());
        }
    }

    public boolean isProtectedFrame(Entity entity) {
        if (!(entity instanceof ItemFrame)) return false;
        String type = entity.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return "frame".equalsIgnoreCase(type);
    }

    private void sync(ShopRecord shop) {
        Block block = shops.blockFor(shop);
        if (block == null || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            removeVisuals(shop.id());
            return;
        }

        String style = shop.visualStyle() == null ? "BOTH" : shop.visualStyle().toUpperCase();
        if ("NONE".equals(style)) {
            removeVisuals(shop.id());
            return;
        }

        boolean soldOut = shops.isSoldOut(shop);
        boolean cannotReceive = shop.buysFromCustomer() && !shops.canAcceptBuyback(shop);
        ItemStack item = shops.displayItem(shop);

        boolean container = shop.containerShop();
        boolean frameStyle = style.equals("FRAME") || style.equals("FRAME_NORMAL") || style.equals("FRAME_GLOW");
        boolean showFrame = container && (frameStyle || "BOTH".equals(style));
        boolean showItem = "ITEM".equals(style) || "BOTH".equals(style);
        boolean showText = "TEXT".equals(style) || "BOTH".equals(style);
        if (shop.signShop()) syncSignStatus(shop, block, soldOut, cannotReceive);

        BlockFace face = displayFace(block);
        Location frameLocation = frameLocation(block, face);
        Location itemLocation = block.getLocation().add(0.5, shop.signShop() ? 1.0 : 1.3, 0.5);
        Location textLocation;
        Component text;
        if (frameStyle && container) {
            textLocation = frameLocation.clone().add(face.getDirection().multiply(0.08)).add(0.0, -0.40, 0.0);
            text = compactFrameLabel(shop, soldOut, cannotReceive);
        } else {
            textLocation = block.getLocation().add(0.5, shop.signShop() ? 1.45 : 1.85, 0.5);
            text = fullLabel(shop, soldOut, cannotReceive);
        }

        VisualSet set = visuals.computeIfAbsent(shop.id(), ignored -> new VisualSet());
        set.frame = syncFrame(set.frame, shop.id(), block.getWorld(), frameLocation, face,
                frameItem(item, shop.itemLabel()), showFrame, style, shop.itemLabel());
        set.item = syncItem(set.item, shop.id(), block.getWorld(), itemLocation, item, showItem);
        set.text = syncText(set.text, shop.id(), block.getWorld(), textLocation, text, showText);

        if (set.empty()) visuals.remove(shop.id());
    }

    private ItemStack frameItem(ItemStack original, String itemLabel) {
        ItemStack display = original.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null && meta.displayName() == null) {
            meta.displayName(Component.text(itemLabel));
            display.setItemMeta(meta);
        }
        return display;
    }

    private UUID syncFrame(UUID entityId, UUID shopId, World world, Location location, BlockFace face,
                           ItemStack item, boolean show, String style, String itemLabel) {
        Entity existing = entity(entityId);
        if (!show) {
            if (existing != null) existing.remove();
            return null;
        }
        boolean glow = "FRAME_GLOW".equals(style);
        boolean visible = "FRAME_NORMAL".equals(style) || glow;
        if (existing instanceof ItemFrame frame && (glow == (frame instanceof GlowItemFrame))) {
            frame.teleport(location);
            frame.setFacingDirection(face, true);
            frame.setItem(item);
            configureFrame(frame, shopId, visible, itemLabel);
            return frame.getUniqueId();
        }
        if (existing != null) existing.remove();
        ItemFrame frame;
        if (glow) {
            frame = world.spawn(location, GlowItemFrame.class, created -> {
                configureFrame(created, shopId, true, itemLabel);
                created.setFacingDirection(face, true);
                created.setItem(item);
            });
        } else {
            frame = world.spawn(location, ItemFrame.class, created -> {
                configureFrame(created, shopId, visible, itemLabel);
                created.setFacingDirection(face, true);
                created.setItem(item);
            });
        }
        return frame.getUniqueId();
    }

    private UUID syncItem(UUID entityId, UUID shopId, World world, Location location, ItemStack item, boolean show) {
        Entity existing = entity(entityId);
        if (!show) {
            if (existing != null) existing.remove();
            return null;
        }
        if (existing instanceof ItemDisplay display) {
            display.teleport(location);
            display.setItemStack(item);
            return display.getUniqueId();
        }
        if (existing != null) existing.remove();
        ItemDisplay display = world.spawn(location, ItemDisplay.class, created -> {
            tag(created, shopId, "item");
            created.setItemStack(item);
            created.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(true);
        });
        return display.getUniqueId();
    }

    private UUID syncText(UUID entityId, UUID shopId, World world, Location location, Component text, boolean show) {
        Entity existing = entity(entityId);
        if (!show) {
            if (existing != null) existing.remove();
            return null;
        }
        if (existing instanceof TextDisplay display) {
            display.teleport(location);
            display.text(text);
            return display.getUniqueId();
        }
        if (existing != null) existing.remove();
        TextDisplay display = world.spawn(location, TextDisplay.class, created -> {
            tag(created, shopId, "text");
            created.text(text);
            created.setBillboard(Display.Billboard.CENTER);
            created.setSeeThrough(true);
            created.setShadowed(true);
            created.setPersistent(true);
        });
        return display.getUniqueId();
    }

    private void configureFrame(ItemFrame frame, UUID shopId, boolean visible, String itemLabel) {
        tag(frame, shopId, "frame");
        frame.setVisible(visible);
        frame.customName(Component.text(itemLabel));
        frame.setCustomNameVisible(false);
        frame.setFixed(true);
        frame.setInvulnerable(true);
        frame.setPersistent(true);
        frame.setSilent(true);
    }

    private void tag(Entity entity, UUID shopId, String type) {
        entity.getPersistentDataContainer().set(shopKey, PersistentDataType.STRING, shopId.toString());
        entity.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
    }

    private Entity entity(UUID entityId) {
        return entityId == null ? null : Bukkit.getEntity(entityId);
    }

    private void removeVisuals(UUID shopId) {
        VisualSet set = visuals.remove(shopId);
        if (set == null) return;
        remove(set.frame);
        remove(set.item);
        remove(set.text);
    }

    private void remove(UUID entityId) {
        Entity entity = entity(entityId);
        if (entity != null) entity.remove();
    }

    private void cleanupTaggedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(shopKey, PersistentDataType.STRING)) {
                    entity.remove();
                }
            }
        }
        visuals.clear();
    }

    public void removeShopVisuals(UUID shopId) {
        removeVisuals(shopId);
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                String tagged = entity.getPersistentDataContainer().get(shopKey, PersistentDataType.STRING);
                if (shopId.toString().equals(tagged)) entity.remove();
            }
        }
    }

    private void syncSignStatus(ShopRecord shop, Block block, boolean soldOut, boolean cannotReceive) {
        if (!(block.getState() instanceof Sign sign)) return;
        String line = soldOut ? "SOLD OUT" : cannotReceive ? "FULL" : shop.itemLabel();
        if (!line.equals(sign.getLine(3))) {
            sign.setLine(3, line);
            sign.update(true, false);
        }
    }

    private Component compactFrameLabel(ShopRecord shop, boolean soldOut, boolean cannotReceive) {
        if (soldOut) return Component.text("SOLD OUT", NamedTextColor.RED);
        if (cannotReceive) return Component.text("FULL", NamedTextColor.RED);
        return Component.text("⟡ " + shop.price(), NamedTextColor.WHITE);
    }

    private Component fullLabel(ShopRecord shop, boolean soldOut, boolean cannotReceive) {
        if (soldOut) {
            return Component.text("SOLD OUT", NamedTextColor.RED)
                    .append(Component.text(" • " + shop.quantity() + " " + shop.itemLabel()
                            + " • ⟡ " + shop.price(), NamedTextColor.WHITE));
        }
        if (cannotReceive) {
            return Component.text("CANNOT ACCEPT ITEMS", NamedTextColor.RED)
                    .append(Component.text(" • Buys " + shop.quantity() + " " + shop.itemLabel()
                            + " • ⟡ " + shop.price(), NamedTextColor.WHITE));
        }
        String action = shop.buysFromCustomer() ? "Buys " : "";
        String unlimited = shop.unlimitedStock() ? " • Unlimited" : "";
        return Component.text(action + shop.quantity() + " " + shop.itemLabel()
                + " • ⟡ " + shop.price() + unlimited, NamedTextColor.WHITE);
    }

    private BlockFace displayFace(Block block) {
        if (block.getBlockData() instanceof Directional directional) {
            BlockFace face = directional.getFacing();
            if (face == BlockFace.UP || face == BlockFace.DOWN) return BlockFace.NORTH;
            return face;
        }
        return BlockFace.NORTH;
    }

    private Location frameLocation(Block block, BlockFace face) {
        Vector offset = face.getDirection().multiply(0.51);
        return block.getLocation().add(0.5, 0.52, 0.5).add(offset);
    }

    private static final class VisualSet {
        private UUID frame;
        private UUID item;
        private UUID text;

        private boolean empty() {
            return frame == null && item == null && text == null;
        }
    }
}
