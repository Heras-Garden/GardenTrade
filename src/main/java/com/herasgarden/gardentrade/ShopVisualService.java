package com.herasgarden.gardentrade;

import com.herasgarden.gardentrade.model.ShopRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ShopVisualService {
    private final JavaPlugin plugin;
    private final ShopService shops;
    private final NamespacedKey shopKey;
    private final NamespacedKey typeKey;

    public ShopVisualService(JavaPlugin plugin, ShopService shops) {
        this.plugin = plugin;
        this.shops = shops;
        this.shopKey = new NamespacedKey(plugin, "shop-visual-id");
        this.typeKey = new NamespacedKey(plugin, "shop-visual-type");
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, 100L);
    }

    public void refresh() {
        try {
            Map<UUID, ShopRecord> active = new HashMap<>();
            for (ShopRecord shop : shops.all()) active.put(shop.id(), shop);

            Map<String, Entity> existing = new HashMap<>();
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    String shopId = entity.getPersistentDataContainer().get(shopKey, PersistentDataType.STRING);
                    String type = entity.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
                    if (shopId == null || type == null) continue;
                    UUID id;
                    try {
                        id = UUID.fromString(shopId);
                    } catch (IllegalArgumentException ignored) {
                        entity.remove();
                        continue;
                    }
                    if (!active.containsKey(id)) {
                        entity.remove();
                        continue;
                    }
                    existing.put(id + ":" + type, entity);
                }
            }

            Set<String> expected = new HashSet<>();
            for (ShopRecord shop : active.values()) {
                org.bukkit.block.Block block = shops.blockFor(shop);
                if (block == null) continue;

                String style = shop.visualStyle() == null ? "BOTH" : shop.visualStyle().toUpperCase();
                boolean soldOut = shops.isSoldOut(shop);
                boolean cannotReceive = shop.buysFromCustomer() && !shops.canAcceptBuyback(shop);
                boolean warning = soldOut || cannotReceive;

                ItemStack item = warning ? new ItemStack(Material.BARRIER) : shops.displayItem(shop);
                Component text = label(shop, soldOut, cannotReceive);
                Location itemLocation = block.getLocation().add(0.5, shop.signShop() ? 1.0 : 1.25, 0.5);
                Location textLocation = block.getLocation().add(0.5, shop.signShop() ? 1.45 : 1.75, 0.5);

                boolean showItem = warning || style.equals("BOTH") || style.equals("ITEM");
                boolean showText = warning || style.equals("BOTH") || style.equals("TEXT");

                if (showItem) {
                    String itemKey = shop.id() + ":item";
                    expected.add(itemKey);
                    Entity itemEntity = existing.get(itemKey);
                    if (itemEntity instanceof ItemDisplay itemDisplay) {
                        itemDisplay.teleport(itemLocation);
                        itemDisplay.setItemStack(item);
                    } else {
                        spawnItem(shop.id(), itemLocation, item);
                    }
                }

                if (showText) {
                    String textKey = shop.id() + ":text";
                    expected.add(textKey);
                    Entity textEntity = existing.get(textKey);
                    if (textEntity instanceof TextDisplay textDisplay) {
                        textDisplay.teleport(textLocation);
                        textDisplay.text(text);
                    } else {
                        spawnText(shop.id(), textLocation, text);
                    }
                }
            }

            for (Map.Entry<String, Entity> entry : existing.entrySet()) {
                if (!expected.contains(entry.getKey())) entry.getValue().remove();
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().warning("Could not refresh Garden shop visuals: " + exception.getMessage());
        }
    }

    private Component label(ShopRecord shop, boolean soldOut, boolean cannotReceive) {
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

    private void spawnItem(UUID shopId, Location location, ItemStack item) {
        location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.getPersistentDataContainer().set(shopKey, PersistentDataType.STRING, shopId.toString());
            display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "item");
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBillboard(Display.Billboard.CENTER);
        });
    }

    private void spawnText(UUID shopId, Location location, Component text) {
        location.getWorld().spawn(location, TextDisplay.class, display -> {
            display.getPersistentDataContainer().set(shopKey, PersistentDataType.STRING, shopId.toString());
            display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "text");
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
        });
    }
}
