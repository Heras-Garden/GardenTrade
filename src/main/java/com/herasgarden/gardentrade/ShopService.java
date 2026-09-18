package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.land.LandAccessService;
import com.herasgarden.gardencore.api.order.GardenOrder;
import com.herasgarden.gardencore.api.order.OrderState;
import com.herasgarden.gardencore.api.order.OrderType;
import com.herasgarden.gardencore.api.organization.OrganizationCapability;
import com.herasgarden.gardencore.api.organization.OrganizationDirectory;
import com.herasgarden.gardencore.api.organization.OrganizationView;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardentrade.model.ShopPrincipal;
import com.herasgarden.gardentrade.model.ShopRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopService {
    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final LandAccessService land;
    private final OrganizationDirectory organizations;
    private final Map<UUID, Object> purchaseLocks = new ConcurrentHashMap<>();

    public ShopService(JavaPlugin plugin, GardenPlatform platform, LandAccessService land,
                       OrganizationDirectory organizations) {
        this.plugin = plugin;
        this.platform = platform;
        this.land = land;
        this.organizations = organizations;
    }

    public ShopRecord create(Player owner, Block block, ItemStack template, int quantity, long price, int maxShops)
            throws SQLException {
        ShopPrincipal principal = new ShopPrincipal("PLAYER", owner.getUniqueId(), owner.getName());
        if (!owner.hasPermission("gardentrade.shop.admin") && countPrincipal(principal) >= maxShops) {
            throw new IllegalArgumentException("You have reached your Garden shop limit.");
        }
        return createShop(owner, block, template, quantity, price, principal);
    }

    public ShopRecord createCompany(
            Player actor,
            String companyName,
            Block block,
            ItemStack template,
            int quantity,
            long price,
            int maxShops
    ) throws SQLException {
        return createOrganizationShop(
                actor, companyName, "COMPANY", "company", block, template, quantity, price, maxShops);
    }

    public ShopRecord createGovernment(
            Player actor,
            String governmentName,
            Block block,
            ItemStack template,
            int quantity,
            long price,
            int maxShops
    ) throws SQLException {
        return createOrganizationShop(
                actor, governmentName, "GOVERNMENT", "government", block, template, quantity, price, maxShops);
    }

    private ShopRecord createOrganizationShop(
            Player actor,
            String organizationName,
            String organizationType,
            String label,
            Block block,
            ItemStack template,
            int quantity,
            long price,
            int maxShops
    ) throws SQLException {
        OrganizationView organization = organization(organizationName, organizationType, label);
        if (!actor.hasPermission("gardentrade.shop.admin")
                && !organizations.has(
                organization.id(), actor.getUniqueId(), OrganizationCapability.COMMERCE_MANAGE)) {
            throw new IllegalArgumentException("Your " + label + " role cannot manage storefronts.");
        }

        ShopPrincipal principal = new ShopPrincipal(
                "ORGANIZATION", organization.id(), organization.name());
        if (!actor.hasPermission("gardentrade.shop.admin") && countPrincipal(principal) >= maxShops) {
            throw new IllegalArgumentException(
                    "That " + label + " has reached its Garden shop limit.");
        }
        return createShop(actor, block, template, quantity, price, principal);
    }

    private ShopRecord createShop(
            Player actor,
            Block block,
            ItemStack template,
            int quantity,
            long price,
            ShopPrincipal principal
    ) throws SQLException {
        if (!ShopBlockKey.supported(block)) {
            throw new IllegalArgumentException("Look directly at a chest, barrel, or other container.");
        }
        if (!actor.hasPermission("gardentrade.shop.admin")) {
            if (land == null || land.claimIdAt(block).isEmpty() || !land.canManage(actor, block)) {
                throw new IllegalArgumentException("You can only create a shop inside Garden land you manage.");
            }
        }
        if (template == null || template.getType().isAir()) {
            throw new IllegalArgumentException("Hold the item this shop should sell.");
        }
        if (quantity <= 0 || quantity > 2304) {
            throw new IllegalArgumentException("Quantity must be between 1 and 2304.");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be a positive whole number of Obols.");
        }

        ShopBlockKey key = ShopBlockKey.of(block);
        if (shopAt(key).isPresent()) {
            throw new IllegalArgumentException("That container already has a Garden shop.");
        }

        ItemStack one = template.clone();
        one.setAmount(1);
        String itemData = serialize(one);
        String label = pretty(one.getType());
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();

        try (Connection connection = platform.storage().connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO gt_shops "
                                + "(shop_uuid, owner_uuid, owner_name, world_uuid, world_name, x, y, z, "
                                + "item_data, item_label, quantity, price, enabled, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)")) {
                    statement.setString(1, id.toString());
                    statement.setString(2, actor.getUniqueId().toString());
                    statement.setString(3, actor.getName());
                    statement.setString(4, key.worldId().toString());
                    statement.setString(5, block.getWorld().getName());
                    statement.setInt(6, key.x());
                    statement.setInt(7, key.y());
                    statement.setInt(8, key.z());
                    statement.setString(9, itemData);
                    statement.setString(10, label);
                    statement.setInt(11, quantity);
                    statement.setLong(12, price);
                    statement.setLong(13, now);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO gt_shop_principals "
                                + "(shop_uuid, principal_kind, principal_id, display_name, created_at) "
                                + "VALUES (?, ?, ?, ?, ?)")) {
                    statement.setString(1, id.toString());
                    statement.setString(2, principal.kind());
                    statement.setString(3, principal.id().toString());
                    statement.setString(4, principal.displayName());
                    statement.setLong(5, now);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }

        return new ShopRecord(id, actor.getUniqueId(), actor.getName(),
                key.worldId(), block.getWorld().getName(), key.x(), key.y(), key.z(),
                itemData, label, quantity, price, true, now);
    }

    public Optional<ShopRecord> shopAt(Block block) throws SQLException {
        if (!ShopBlockKey.supported(block)) return Optional.empty();
        return shopAt(ShopBlockKey.of(block));
    }

    public Optional<ShopRecord> shopAt(ShopBlockKey key) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gt_shops WHERE world_uuid = ? AND x = ? AND y = ? AND z = ? LIMIT 1")) {
            statement.setString(1, key.worldId().toString());
            statement.setInt(2, key.x());
            statement.setInt(3, key.y());
            statement.setInt(4, key.z());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public List<ShopRecord> all() throws SQLException {
        List<ShopRecord> records = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gt_shops WHERE enabled = 1 ORDER BY created_at ASC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                records.add(read(result));
            }
        }
        return List.copyOf(records);
    }

    public ItemStack displayItem(ShopRecord shop) {
        return deserialize(shop.itemData());
    }

    public Block blockFor(ShopRecord shop) {
        return block(shop);
    }

    public boolean canManage(Player actor, ShopRecord shop) throws SQLException {
        return canManage(actor, shop, principal(shop));
    }

    public List<ShopRecord> ownedBy(UUID ownerId) throws SQLException {
        List<ShopRecord> records = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT s.* FROM gt_shops s "
                             + "LEFT JOIN gt_shop_principals p ON p.shop_uuid = s.shop_uuid "
                             + "WHERE (p.principal_kind = 'PLAYER' AND p.principal_id = ?) "
                             + "OR (p.shop_uuid IS NULL AND s.owner_uuid = ?) "
                             + "ORDER BY s.created_at ASC")) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(read(result));
            }
        }
        return List.copyOf(records);
    }

    public OrganizationView company(String companyName) {
        return organization(companyName, "COMPANY", "company");
    }

    public OrganizationView government(String governmentName) {
        return organization(governmentName, "GOVERNMENT", "government");
    }

    public List<ShopRecord> companyShops(String companyName) throws SQLException {
        return ownedByOrganization(company(companyName).id());
    }

    public List<ShopRecord> governmentShops(String governmentName) throws SQLException {
        return ownedByOrganization(government(governmentName).id());
    }

    private OrganizationView organization(String name, String type, String label) {
        if (organizations == null) {
            throw new IllegalArgumentException("Garden organization services are unavailable.");
        }
        return organizations.findByName(name)
                .filter(value -> type.equals(value.type()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "That Garden " + label + " does not exist."));
    }

    public ShopPrincipal principal(ShopRecord shop) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT principal_kind, principal_id, display_name FROM gt_shop_principals "
                             + "WHERE shop_uuid = ? LIMIT 1")) {
            statement.setString(1, shop.id().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new ShopPrincipal(
                            result.getString("principal_kind"),
                            UUID.fromString(result.getString("principal_id")),
                            result.getString("display_name")
                    );
                }
            }
        }
        // Backward compatibility for shops created before the principal table existed.
        return new ShopPrincipal("PLAYER", shop.ownerId(), shop.ownerName());
    }

    public List<ShopRecord> ownedByOrganization(UUID organizationId) throws SQLException {
        List<ShopRecord> records = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT s.* FROM gt_shops s "
                             + "JOIN gt_shop_principals p ON p.shop_uuid = s.shop_uuid "
                             + "WHERE p.principal_kind = 'ORGANIZATION' AND p.principal_id = ? "
                             + "ORDER BY s.created_at ASC")) {
            statement.setString(1, organizationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(read(result));
            }
        }
        return List.copyOf(records);
    }

    public boolean delete(Player actor, Block block) throws SQLException {
        Optional<ShopRecord> existing = shopAt(block);
        if (existing.isEmpty()) return false;
        ShopRecord shop = existing.get();
        ShopPrincipal principal = principal(shop);
        if (!canManage(actor, shop, principal)) {
            throw new IllegalArgumentException(principal.organization()
                    ? "Your organization role cannot manage this shop."
                    : "You do not own this shop.");
        }
        return delete(shop.id());
    }

    public boolean delete(UUID shopId) throws SQLException {
        try (Connection connection = platform.storage().connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement mapping = connection.prepareStatement(
                        "DELETE FROM gt_shop_principals WHERE shop_uuid = ?")) {
                    mapping.setString(1, shopId.toString());
                    mapping.executeUpdate();
                }
                int changed;
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM gt_shops WHERE shop_uuid = ?")) {
                    statement.setString(1, shopId.toString());
                    changed = statement.executeUpdate();
                }
                connection.commit();
                return changed > 0;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public PurchaseResult purchase(Player buyer, ShopRecord shop, int units) throws SQLException {
        if (units <= 0 || units > 64) {
            return PurchaseResult.failure("Purchase units must be between 1 and 64.");
        }
        if (!shop.enabled()) {
            return PurchaseResult.failure("That shop is disabled.");
        }

        ShopPrincipal principal = principal(shop);
        if (principal.player() && principal.id().equals(buyer.getUniqueId())) {
            return PurchaseResult.failure("You already own this shop.");
        }

        long total;
        int itemCount;
        try {
            total = Math.multiplyExact(shop.price(), units);
            itemCount = Math.multiplyExact(shop.quantity(), units);
        } catch (ArithmeticException exception) {
            return PurchaseResult.failure("That purchase is too large.");
        }

        Object lock = purchaseLocks.computeIfAbsent(shop.id(), ignored -> new Object());
        synchronized (lock) {
            Block block = block(shop);
            if (block == null || !(block.getState() instanceof Container container)) {
                return PurchaseResult.failure("The shop container is missing.");
            }

            ItemStack template;
            try {
                template = deserialize(shop.itemData());
            } catch (IllegalArgumentException exception) {
                return PurchaseResult.failure("The shop item data is invalid.");
            }

            Inventory stock = container.getInventory();
            if (countSimilar(stock, template) < itemCount) {
                return PurchaseResult.failure("That shop is out of stock.");
            }
            if (!hasSpace(buyer.getInventory(), template, itemCount)) {
                return PurchaseResult.failure("You do not have enough inventory space.");
            }

            GardenOrder order = platform.orders().create(
                    OrderType.SHOP_PURCHASE,
                    buyer.getUniqueId(),
                    principal.kind(),
                    principal.id().toString(),
                    total,
                    "gardentrade.shop",
                    shop.id().toString(),
                    "{\"units\":" + units + ",\"itemCount\":" + itemCount
                            + ",\"item\":\"" + json(shop.itemLabel())
                            + "\",\"sellerName\":\"" + json(principal.displayName()) + "\"}"
            );
            platform.orders().transition(order.id(), OrderState.READY, "Shop stock validated");
            platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Buy command confirms purchase");
            platform.orders().transition(order.id(), OrderState.PAYMENT_PENDING, "Collecting shop payment");

            if (!removeSimilar(stock, template, itemCount)) {
                platform.orders().transition(order.id(), OrderState.CANCELLED, "Shop stock changed");
                return PurchaseResult.failure("The shop stock changed. Try again.");
            }

            if (!platform.currency().withdraw(buyer.getUniqueId(), total)) {
                restore(stock, template, itemCount, block);
                platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED, "Buyer has insufficient Obols");
                return PurchaseResult.failure("You do not have enough Obols.");
            }

            if (!creditPrincipal(principal, total)) {
                boolean refunded = platform.currency().deposit(buyer.getUniqueId(), total);
                restore(stock, template, itemCount, block);
                platform.orders().transition(order.id(),
                        refunded ? OrderState.PAYMENT_FAILED : OrderState.FULFILLMENT_FAILED,
                        refunded
                                ? "Seller payment failed and buyer refunded"
                                : "Seller payment failed and buyer refund requires admin review");
                return PurchaseResult.failure(refunded
                        ? "The seller could not be paid. Your Obols were returned."
                        : "The purchase needs administrator review.");
            }

            platform.orders().transition(order.id(), OrderState.PAID, "Shop payment completed");
            platform.orders().transition(order.id(), OrderState.FULFILLING, "Delivering purchased items");

            int buyerItemCountBefore = countSimilar(buyer.getInventory(), template);
            List<ItemStack> delivery = stacks(template, itemCount);
            Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(delivery.toArray(ItemStack[]::new));
            if (!leftovers.isEmpty()) {
                int buyerItemCountAfter = countSimilar(buyer.getInventory(), template);
                int deliveredCount = Math.max(0, buyerItemCountAfter - buyerItemCountBefore);
                if (deliveredCount > 0) {
                    removeSimilar(buyer.getInventory(), template, deliveredCount);
                }
                restore(stock, template, itemCount, block);

                if (!debitPrincipal(principal, total)) {
                    platform.orders().transition(order.id(), OrderState.FULFILLMENT_FAILED,
                            "Inventory changed; seller payout could not be reversed and needs admin review");
                    return PurchaseResult.failure("The purchase needs administrator review.");
                }
                if (!platform.currency().deposit(buyer.getUniqueId(), total)) {
                    creditPrincipal(principal, total);
                    platform.orders().transition(order.id(), OrderState.FULFILLMENT_FAILED,
                            "Inventory changed; buyer refund failed and seller payout was restored");
                    return PurchaseResult.failure("The purchase needs administrator review.");
                }

                platform.orders().transition(order.id(), OrderState.REFUNDED,
                        "Inventory changed during fulfillment; transaction rolled back");
                return PurchaseResult.failure("Your inventory changed during the purchase. Your Obols were returned.");
            }

            platform.orders().transition(order.id(), OrderState.COMPLETED, "Items delivered");

            if (principal.player()) {
                Player seller = Bukkit.getPlayer(principal.id());
                if (seller != null && seller.isOnline()) {
                    GardenMessages.send(seller,
                            "Shop sale: " + itemCount + " " + shop.itemLabel() + " for "
                                    + platform.currency().symbol() + " " + total + ".");
                }
            }

            return PurchaseResult.success(itemCount, total, shop.itemLabel(), principal.displayName());
        }
    }

    private int countPrincipal(ShopPrincipal principal) throws SQLException {
        if (principal.player()) {
            try (Connection connection = platform.storage().connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) AS n FROM gt_shops s "
                                 + "LEFT JOIN gt_shop_principals p ON p.shop_uuid = s.shop_uuid "
                                 + "WHERE (p.principal_kind = 'PLAYER' AND p.principal_id = ?) "
                                 + "OR (p.shop_uuid IS NULL AND s.owner_uuid = ?)")) {
                statement.setString(1, principal.id().toString());
                statement.setString(2, principal.id().toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt("n") : 0;
                }
            }
        }

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) AS n FROM gt_shop_principals "
                             + "WHERE principal_kind = ? AND principal_id = ?")) {
            statement.setString(1, principal.kind());
            statement.setString(2, principal.id().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("n") : 0;
            }
        }
    }

    private boolean canManage(Player actor, ShopRecord shop, ShopPrincipal principal) {
        if (actor.hasPermission("gardentrade.shop.admin")) {
            return true;
        }
        if (principal.player()) {
            return principal.id().equals(actor.getUniqueId());
        }
        return organizations != null
                && organizations.has(principal.id(), actor.getUniqueId(), OrganizationCapability.COMMERCE_MANAGE);
    }

    private boolean creditPrincipal(ShopPrincipal principal, long amount) throws SQLException {
        if (principal.player()) {
            return platform.currency().deposit(principal.id(), amount);
        }
        return organizations != null && organizations.creditTreasury(principal.id(), amount);
    }

    private boolean debitPrincipal(ShopPrincipal principal, long amount) throws SQLException {
        if (principal.player()) {
            return platform.currency().withdraw(principal.id(), amount);
        }
        return organizations != null && organizations.debitTreasury(principal.id(), amount);
    }

    private Block block(ShopRecord shop) {
        World world = Bukkit.getWorld(shop.worldId());
        if (world == null) world = Bukkit.getWorld(shop.worldName());
        return world == null ? null : world.getBlockAt(shop.x(), shop.y(), shop.z());
    }

    private int countSimilar(Inventory inventory, ItemStack template) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.isSimilar(template)) total += item.getAmount();
        }
        return total;
    }

    private boolean removeSimilar(Inventory inventory, ItemStack template, int amount) {
        if (countSimilar(inventory, template) < amount) return false;
        int remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !item.isSimilar(template)) continue;
            int take = Math.min(remaining, item.getAmount());
            if (take == item.getAmount()) inventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - take);
            remaining -= take;
        }
        return remaining == 0;
    }

    private boolean hasSpace(Inventory inventory, ItemStack template, int amount) {
        int capacity = 0;
        int max = template.getMaxStackSize();
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) capacity += max;
            else if (item.isSimilar(template)) capacity += Math.max(0, max - item.getAmount());
            if (capacity >= amount) return true;
        }
        return false;
    }

    private List<ItemStack> stacks(ItemStack template, int amount) {
        List<ItemStack> result = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            ItemStack item = template.clone();
            item.setAmount(Math.min(template.getMaxStackSize(), remaining));
            result.add(item);
            remaining -= item.getAmount();
        }
        return result;
    }

    private void restore(Inventory inventory, ItemStack template, int amount, Block block) {
        Map<Integer, ItemStack> leftovers = inventory.addItem(stacks(template, amount).toArray(ItemStack[]::new));
        leftovers.values().forEach(item -> block.getWorld().dropItemNaturally(block.getLocation(), item));
    }

    private String serialize(ItemStack item) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        return yaml.saveToString();
    }

    private ItemStack deserialize(String data) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(data);
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("Invalid stored item.", exception);
        }
        ItemStack item = yaml.getItemStack("item");
        if (item == null || item.getType().isAir()) {
            throw new IllegalArgumentException("Invalid stored item.");
        }
        item.setAmount(1);
        return item;
    }

    private ShopRecord read(ResultSet result) throws SQLException {
        return new ShopRecord(
                UUID.fromString(result.getString("shop_uuid")),
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("owner_name"),
                UUID.fromString(result.getString("world_uuid")),
                result.getString("world_name"),
                result.getInt("x"),
                result.getInt("y"),
                result.getInt("z"),
                result.getString("item_data"),
                result.getString("item_label"),
                result.getInt("quantity"),
                result.getLong("price"),
                result.getInt("enabled") != 0,
                result.getLong("created_at")
        );
    }

    private String pretty(Material material) {
        String raw = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder(raw.length());
        boolean cap = true;
        for (char ch : raw.toCharArray()) {
            if (cap && Character.isLetter(ch)) {
                out.append(Character.toUpperCase(ch));
                cap = false;
            } else {
                out.append(ch);
            }
            if (ch == ' ') cap = true;
        }
        return out.toString();
    }

    private String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record PurchaseResult(boolean success, String message, int itemCount, long total) {
        public static PurchaseResult success(int itemCount, long total, String item, String seller) {
            return new PurchaseResult(true,
                    "Purchased " + itemCount + " " + item + " from " + seller
                            + " for ⟡ " + total + ".", itemCount, total);
        }

        public static PurchaseResult failure(String message) {
            return new PurchaseResult(false, message, 0, 0L);
        }
    }
}
