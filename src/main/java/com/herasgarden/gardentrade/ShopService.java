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
import org.bukkit.block.Sign;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopService {
    private static final String KIND_CONTAINER = "CONTAINER";
    private static final String KIND_SIGN = "SIGN";
    private static final String MODE_SELL = "SELL";
    private static final String MODE_BUY = "BUY";

    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final LandAccessService land;
    private final OrganizationDirectory organizations;
    private final Map<UUID, Object> purchaseLocks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> pendingStockLinks = new ConcurrentHashMap<>();

    public ShopService(JavaPlugin plugin, GardenPlatform platform, LandAccessService land,
                       OrganizationDirectory organizations) {
        this.plugin = plugin;
        this.platform = platform;
        this.land = land;
        this.organizations = organizations;
    }

    public ShopRecord create(Player owner, Block block, ItemStack template, int quantity, long price, int maxShops)
            throws SQLException {
        return createPlayerContainer(owner, block, template, quantity, price, MODE_SELL, maxShops);
    }

    public ShopRecord createBuyback(
            Player owner, Block block, ItemStack template, int quantity, long price, int maxShops)
            throws SQLException {
        return createPlayerContainer(owner, block, template, quantity, price, MODE_BUY, maxShops);
    }

    private ShopRecord createPlayerContainer(
            Player owner,
            Block block,
            ItemStack template,
            int quantity,
            long price,
            String mode,
            int maxShops
    ) throws SQLException {
        ShopPrincipal principal = new ShopPrincipal("PLAYER", owner.getUniqueId(), owner.getName());
        enforceLimit(owner, principal, maxShops, "You have reached your Garden shop limit.");
        return createShop(owner, block, template, quantity, price, principal,
                KIND_CONTAINER, mode, false, block);
    }

    public ShopRecord createCompany(
            Player actor, String companyName, Block block, ItemStack template,
            int quantity, long price, int maxShops) throws SQLException {
        return createOrganizationContainer(actor, companyName, "COMPANY", "company",
                block, template, quantity, price, MODE_SELL, maxShops);
    }

    public ShopRecord createCompanyBuyback(
            Player actor, String companyName, Block block, ItemStack template,
            int quantity, long price, int maxShops) throws SQLException {
        return createOrganizationContainer(actor, companyName, "COMPANY", "company",
                block, template, quantity, price, MODE_BUY, maxShops);
    }

    public ShopRecord createGovernment(
            Player actor, String governmentName, Block block, ItemStack template,
            int quantity, long price, int maxShops) throws SQLException {
        return createOrganizationContainer(actor, governmentName, "GOVERNMENT", "government",
                block, template, quantity, price, MODE_SELL, maxShops);
    }

    public ShopRecord createGovernmentBuyback(
            Player actor, String governmentName, Block block, ItemStack template,
            int quantity, long price, int maxShops) throws SQLException {
        return createOrganizationContainer(actor, governmentName, "GOVERNMENT", "government",
                block, template, quantity, price, MODE_BUY, maxShops);
    }

    private ShopRecord createOrganizationContainer(
            Player actor,
            String organizationName,
            String organizationType,
            String label,
            Block block,
            ItemStack template,
            int quantity,
            long price,
            String mode,
            int maxShops
    ) throws SQLException {
        ShopPrincipal principal = organizationPrincipal(actor, organizationName, organizationType, label, maxShops);
        return createShop(actor, block, template, quantity, price, principal,
                KIND_CONTAINER, mode, false, block);
    }

    public ShopRecord createSign(
            Player actor,
            Block signBlock,
            ItemStack template,
            int quantity,
            long price,
            boolean buyback,
            boolean unlimited,
            int maxShops
    ) throws SQLException {
        ShopPrincipal principal = new ShopPrincipal("PLAYER", actor.getUniqueId(), actor.getName());
        enforceLimit(actor, principal, maxShops, "You have reached your Garden shop limit.");
        return createShop(actor, signBlock, template, quantity, price, principal,
                KIND_SIGN, buyback ? MODE_BUY : MODE_SELL, unlimited, null);
    }

    public ShopRecord createAdminSign(
            Player actor, Block signBlock, ItemStack template, int quantity, long price, boolean buyback)
            throws SQLException {
        if (!actor.hasPermission("gardentrade.shop.admin")) {
            throw new IllegalArgumentException("Only administrators can create admin shops.");
        }
        ShopPrincipal principal = new ShopPrincipal("SERVER", new UUID(0L, 0L), "Server");
        return createShop(actor, signBlock, template, quantity, price, principal,
                KIND_SIGN, buyback ? MODE_BUY : MODE_SELL, true, null);
    }

    public ShopRecord createCompanySign(
            Player actor,
            String companyName,
            Block signBlock,
            ItemStack template,
            int quantity,
            long price,
            boolean buyback,
            int maxShops
    ) throws SQLException {
        ShopPrincipal principal = organizationPrincipal(actor, companyName, "COMPANY", "company", maxShops);
        return createShop(actor, signBlock, template, quantity, price, principal,
                KIND_SIGN, buyback ? MODE_BUY : MODE_SELL, false, null);
    }

    public ShopRecord createGovernmentSign(
            Player actor,
            String governmentName,
            Block signBlock,
            ItemStack template,
            int quantity,
            long price,
            boolean buyback,
            int maxShops
    ) throws SQLException {
        ShopPrincipal principal = organizationPrincipal(actor, governmentName, "GOVERNMENT", "government", maxShops);
        return createShop(actor, signBlock, template, quantity, price, principal,
                KIND_SIGN, buyback ? MODE_BUY : MODE_SELL, false, null);
    }

    private ShopPrincipal organizationPrincipal(
            Player actor, String organizationName, String organizationType, String label, int maxShops)
            throws SQLException {
        OrganizationView organization = organization(organizationName, organizationType, label);
        if (!actor.hasPermission("gardentrade.shop.admin")
                && !organizations.has(
                organization.id(), actor.getUniqueId(), OrganizationCapability.COMMERCE_MANAGE)) {
            throw new IllegalArgumentException("Your " + label + " role cannot manage storefronts.");
        }

        ShopPrincipal principal = new ShopPrincipal("ORGANIZATION", organization.id(), organization.name());
        enforceLimit(actor, principal, maxShops,
                "That " + label + " has reached its Garden shop limit.");
        return principal;
    }

    private void enforceLimit(Player actor, ShopPrincipal principal, int maxShops, String message)
            throws SQLException {
        if (!actor.hasPermission("gardentrade.shop.admin") && countPrincipal(principal) >= maxShops) {
            throw new IllegalArgumentException(message);
        }
    }

    private ShopRecord createShop(
            Player actor,
            Block storefront,
            ItemStack template,
            int quantity,
            long price,
            ShopPrincipal principal,
            String shopKind,
            String transactionMode,
            boolean unlimited,
            Block initialStock
    ) throws SQLException {
        boolean containerKind = KIND_CONTAINER.equals(shopKind);
        boolean signKind = KIND_SIGN.equals(shopKind);
        if (containerKind && !ShopBlockKey.supported(storefront)) {
            throw new IllegalArgumentException("Look directly at a chest, barrel, or other container.");
        }
        if (signKind && !(storefront.getState() instanceof Sign)) {
            throw new IllegalArgumentException("Look directly at the shop sign.");
        }
        if (unlimited && !actor.hasPermission("gardentrade.shop.admin")) {
            throw new IllegalArgumentException("Only administrators can create unlimited-stock shops.");
        }

        validateLand(actor, storefront);
        if (template == null || template.getType().isAir()) {
            throw new IllegalArgumentException(transactionMode.equals(MODE_BUY)
                    ? "Hold the item this shop should buy."
                    : "Hold the item this shop should sell.");
        }
        if (quantity <= 0 || quantity > 2304) {
            throw new IllegalArgumentException("Quantity must be between 1 and 2304.");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be a positive whole number of Obols.");
        }

        Block anchor = containerKind ? canonicalContainer(storefront) : storefront;
        if (shopAt(anchor).isPresent()) {
            throw new IllegalArgumentException("That storefront already has a Garden shop.");
        }

        Block stock = initialStock == null ? null : canonicalContainer(initialStock);
        ItemStack one = template.clone();
        one.setAmount(1);
        String itemData = serialize(one);
        String label = labelFor(one);
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();

        try (Connection connection = platform.storage().connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO gt_shops "
                                + "(shop_uuid, owner_uuid, owner_name, world_uuid, world_name, x, y, z, "
                                + "item_data, item_label, quantity, price, shop_kind, transaction_mode, "
                                + "unlimited_stock, stock_world_uuid, stock_world_name, stock_x, stock_y, stock_z, "
                                + "visual_style, enabled, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BOTH', 1, ?)")) {
                    statement.setString(1, id.toString());
                    statement.setString(2, actor.getUniqueId().toString());
                    statement.setString(3, actor.getName());
                    statement.setString(4, anchor.getWorld().getUID().toString());
                    statement.setString(5, anchor.getWorld().getName());
                    statement.setInt(6, anchor.getX());
                    statement.setInt(7, anchor.getY());
                    statement.setInt(8, anchor.getZ());
                    statement.setString(9, itemData);
                    statement.setString(10, label);
                    statement.setInt(11, quantity);
                    statement.setLong(12, price);
                    statement.setString(13, shopKind);
                    statement.setString(14, transactionMode);
                    statement.setInt(15, unlimited ? 1 : 0);
                    setStock(statement, 16, stock);
                    statement.setLong(21, now);
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

        return find(id).orElseThrow(() -> new SQLException("Created shop could not be reloaded."));
    }

    private void setStock(PreparedStatement statement, int startIndex, Block stock) throws SQLException {
        if (stock == null) {
            statement.setNull(startIndex, Types.VARCHAR);
            statement.setNull(startIndex + 1, Types.VARCHAR);
            statement.setNull(startIndex + 2, Types.INTEGER);
            statement.setNull(startIndex + 3, Types.INTEGER);
            statement.setNull(startIndex + 4, Types.INTEGER);
            return;
        }
        statement.setString(startIndex, stock.getWorld().getUID().toString());
        statement.setString(startIndex + 1, stock.getWorld().getName());
        statement.setInt(startIndex + 2, stock.getX());
        statement.setInt(startIndex + 3, stock.getY());
        statement.setInt(startIndex + 4, stock.getZ());
    }

    private void validateLand(Player actor, Block block) {
        if (actor.hasPermission("gardentrade.shop.admin")) return;
        if (land == null || land.claimIdAt(block).isEmpty() || !land.canManage(actor, block)) {
            throw new IllegalArgumentException("You can only create a shop inside Garden land you manage.");
        }
    }

    private Block canonicalContainer(Block block) {
        if (!ShopBlockKey.supported(block)) {
            throw new IllegalArgumentException("Choose a chest, barrel, or other container.");
        }
        ShopBlockKey key = ShopBlockKey.of(block);
        World world = Bukkit.getWorld(key.worldId());
        return world == null ? block : world.getBlockAt(key.x(), key.y(), key.z());
    }

    public Optional<ShopRecord> find(UUID shopId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gt_shops WHERE shop_uuid = ? LIMIT 1")) {
            statement.setString(1, shopId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public Optional<ShopRecord> shopAt(Block block) throws SQLException {
        if (block == null) return Optional.empty();
        if (ShopBlockKey.supported(block)) {
            ShopBlockKey key = ShopBlockKey.of(block);
            Optional<ShopRecord> canonical = shopAt(key.worldId(), key.x(), key.y(), key.z());
            if (canonical.isPresent()) return canonical;
        }
        return shopAt(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public Optional<ShopRecord> shopAt(ShopBlockKey key) throws SQLException {
        return shopAt(key.worldId(), key.x(), key.y(), key.z());
    }

    private Optional<ShopRecord> shopAt(UUID worldId, int x, int y, int z) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gt_shops WHERE world_uuid = ? AND x = ? AND y = ? AND z = ? LIMIT 1")) {
            statement.setString(1, worldId.toString());
            statement.setInt(2, x);
            statement.setInt(3, y);
            statement.setInt(4, z);
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
            while (result.next()) records.add(read(result));
        }
        return List.copyOf(records);
    }

    public ItemStack displayItem(ShopRecord shop) {
        return deserialize(shop.itemData());
    }

    public Block blockFor(ShopRecord shop) {
        World world = Bukkit.getWorld(shop.worldId());
        if (world == null) world = Bukkit.getWorld(shop.worldName());
        return world == null ? null : world.getBlockAt(shop.x(), shop.y(), shop.z());
    }

    public Block stockBlockFor(ShopRecord shop) {
        if (!shop.hasStockContainer()) return null;
        World world = Bukkit.getWorld(shop.stockWorldId());
        if (world == null && shop.stockWorldName() != null) {
            world = Bukkit.getWorld(shop.stockWorldName());
        }
        return world == null ? null : world.getBlockAt(shop.stockX(), shop.stockY(), shop.stockZ());
    }

    public boolean isSoldOut(ShopRecord shop) {
        if (!shop.sellsToCustomer() || shop.unlimitedStock()) return false;
        Block stockBlock = stockBlockFor(shop);
        if (stockBlock == null || !(stockBlock.getState() instanceof Container container)) return true;
        ItemStack template = displayItem(shop);
        return countSimilar(container.getInventory(), template) < shop.quantity();
    }

    public boolean canAcceptBuyback(ShopRecord shop) {
        if (!shop.buysFromCustomer()) return false;
        if (shop.unlimitedStock()) return true;
        Block stockBlock = stockBlockFor(shop);
        if (stockBlock == null || !(stockBlock.getState() instanceof Container container)) return false;
        return hasSpace(container.getInventory(), displayItem(shop), shop.quantity());
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

    public void beginStockLink(Player actor, ShopRecord shop) throws SQLException {
        if (!shop.signShop()) {
            throw new IllegalArgumentException("Chest shops already use their own container as stock.");
        }
        if (shop.unlimitedStock()) {
            throw new IllegalArgumentException("Unlimited admin shops do not need a stock container.");
        }
        if (!canManage(actor, shop)) {
            throw new IllegalArgumentException("You do not manage this shop.");
        }
        pendingStockLinks.put(actor.getUniqueId(), shop.id());
    }

    public LinkResult completePendingStockLink(Player actor, Block clicked) throws SQLException {
        UUID shopId = pendingStockLinks.get(actor.getUniqueId());
        if (shopId == null) return LinkResult.notPending();
        if (!ShopBlockKey.supported(clicked)) {
            return LinkResult.handled(false, "Choose a chest, barrel, or other container for stock.");
        }

        ShopRecord shop = find(shopId).orElse(null);
        if (shop == null) {
            pendingStockLinks.remove(actor.getUniqueId());
            return LinkResult.handled(false, "That shop no longer exists.");
        }
        if (!canManage(actor, shop)) {
            pendingStockLinks.remove(actor.getUniqueId());
            return LinkResult.handled(false, "You no longer manage that shop.");
        }

        validateLand(actor, clicked);
        Block stock = canonicalContainer(clicked);
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gt_shops SET stock_world_uuid = ?, stock_world_name = ?, "
                             + "stock_x = ?, stock_y = ?, stock_z = ? WHERE shop_uuid = ?")) {
            statement.setString(1, stock.getWorld().getUID().toString());
            statement.setString(2, stock.getWorld().getName());
            statement.setInt(3, stock.getX());
            statement.setInt(4, stock.getY());
            statement.setInt(5, stock.getZ());
            statement.setString(6, shop.id().toString());
            statement.executeUpdate();
        }
        pendingStockLinks.remove(actor.getUniqueId());
        return LinkResult.handled(true, "Stock container linked to " + shop.itemLabel() + " shop.");
    }

    public ShopRecord unlinkStock(Player actor, ShopRecord requested) throws SQLException {
        ShopRecord shop = find(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That shop no longer exists."));
        if (!shop.signShop()) {
            throw new IllegalArgumentException("Chest shops cannot unlink their own stock container.");
        }
        if (!canManage(actor, shop)) {
            throw new IllegalArgumentException("You do not manage this shop.");
        }
        clearStock(shop.id());
        return find(shop.id()).orElseThrow();
    }

    private void clearStock(UUID shopId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gt_shops SET stock_world_uuid = NULL, stock_world_name = NULL, "
                             + "stock_x = NULL, stock_y = NULL, stock_z = NULL WHERE shop_uuid = ?")) {
            statement.setString(1, shopId.toString());
            statement.executeUpdate();
        }
    }

    public List<ShopRecord> shopsUsingStock(Block block) throws SQLException {
        if (!ShopBlockKey.supported(block)) return List.of();
        Block stock = canonicalContainer(block);
        List<ShopRecord> records = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gt_shops WHERE stock_world_uuid = ? AND stock_x = ? AND stock_y = ? AND stock_z = ?")) {
            statement.setString(1, stock.getWorld().getUID().toString());
            statement.setInt(2, stock.getX());
            statement.setInt(3, stock.getY());
            statement.setInt(4, stock.getZ());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(read(result));
            }
        }
        return List.copyOf(records);
    }

    public void unlinkStockBecauseBroken(UUID shopId) throws SQLException {
        ShopRecord shop = find(shopId).orElse(null);
        if (shop != null && shop.signShop()) clearStock(shopId);
    }

    public ShopRecord setPrice(Player actor, ShopRecord requested, long price) throws SQLException {
        if (price <= 0) throw new IllegalArgumentException("Price must be a positive whole number of Obols.");
        return updateManagedLong(actor, requested, "price", price);
    }

    public ShopRecord setQuantity(Player actor, ShopRecord requested, int quantity) throws SQLException {
        if (quantity <= 0 || quantity > 2304) {
            throw new IllegalArgumentException("Quantity must be between 1 and 2304.");
        }
        return updateManagedLong(actor, requested, "quantity", quantity);
    }

    private ShopRecord updateManagedLong(Player actor, ShopRecord requested, String column, long value)
            throws SQLException {
        ShopRecord shop = find(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That shop no longer exists."));
        if (!canManage(actor, shop)) throw new IllegalArgumentException("You do not manage this shop.");
        if (!column.equals("price") && !column.equals("quantity")) {
            throw new IllegalArgumentException("That shop setting cannot be changed.");
        }
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gt_shops SET " + column + " = ? WHERE shop_uuid = ?")) {
            statement.setLong(1, value);
            statement.setString(2, shop.id().toString());
            statement.executeUpdate();
        }
        return find(shop.id()).orElseThrow();
    }

    public ShopRecord setTransactionMode(Player actor, ShopRecord requested, String mode) throws SQLException {
        ShopRecord shop = find(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That shop no longer exists."));
        if (!canManage(actor, shop)) throw new IllegalArgumentException("You do not manage this shop.");
        String normalized = mode == null ? "" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("BUYBACK")) normalized = MODE_BUY;
        if (!normalized.equals(MODE_SELL) && !normalized.equals(MODE_BUY)) {
            throw new IllegalArgumentException("Mode must be sell or buy.");
        }
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gt_shops SET transaction_mode = ? WHERE shop_uuid = ?")) {
            statement.setString(1, normalized);
            statement.setString(2, shop.id().toString());
            statement.executeUpdate();
        }
        return find(shop.id()).orElseThrow();
    }

    public ShopRecord setUnlimited(Player actor, ShopRecord requested, boolean unlimited) throws SQLException {
        ShopRecord shop = find(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That shop no longer exists."));
        if (!actor.hasPermission("gardentrade.shop.admin")) {
            throw new IllegalArgumentException("Only administrators can change unlimited-stock mode.");
        }
        if (!canManage(actor, shop)) throw new IllegalArgumentException("You do not manage this shop.");
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gt_shops SET unlimited_stock = ? WHERE shop_uuid = ?")) {
            statement.setInt(1, unlimited ? 1 : 0);
            statement.setString(2, shop.id().toString());
            statement.executeUpdate();
        }
        return find(shop.id()).orElseThrow();
    }

    public ShopRecord setItem(Player actor, ShopRecord requested, ItemStack template) throws SQLException {
        ShopRecord shop = find(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That shop no longer exists."));
        if (!canManage(actor, shop)) throw new IllegalArgumentException("You do not manage this shop.");
        if (template == null || template.getType().isAir()) throw new IllegalArgumentException("Hold the shop item first.");
        ItemStack one = template.clone();
        one.setAmount(1);
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gt_shops SET item_data = ?, item_label = ? WHERE shop_uuid = ?")) {
            statement.setString(1, serialize(one));
            statement.setString(2, labelFor(one));
            statement.setString(3, shop.id().toString());
            statement.executeUpdate();
        }
        return find(shop.id()).orElseThrow();
    }

    public ShopRecord setEnabled(Player actor, ShopRecord requested, boolean enabled) throws SQLException {
        ShopRecord shop = find(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That shop no longer exists."));
        if (!canManage(actor, shop)) throw new IllegalArgumentException("You do not manage this shop.");
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gt_shops SET enabled = ? WHERE shop_uuid = ?")) {
            statement.setInt(1, enabled ? 1 : 0);
            statement.setString(2, shop.id().toString());
            statement.executeUpdate();
        }
        return find(shop.id()).orElseThrow();
    }

    public ShopRecord setVisualStyle(Player actor, ShopRecord requested, String style) throws SQLException {
        ShopRecord shop = find(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That shop no longer exists."));
        if (!canManage(actor, shop)) {
            throw new IllegalArgumentException("You do not manage this shop.");
        }
        String normalized = style == null ? "" : style.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("INVISIBLE") || normalized.equals("INVISIBLEFRAME")) normalized = "FRAME";
        if (normalized.equals("NORMAL") || normalized.equals("NORMALFRAME")) normalized = "FRAME_NORMAL";
        if (normalized.equals("GLOW") || normalized.equals("GLOWFRAME")) normalized = "FRAME_GLOW";
        if (!List.of("BOTH", "ITEM", "TEXT", "FRAME", "FRAME_NORMAL", "FRAME_GLOW", "NONE").contains(normalized)) {
            throw new IllegalArgumentException("Appearance must be both, item, text, invisible, frame, glowframe, or none.");
        }
        if (normalized.startsWith("FRAME") && !shop.containerShop()) {
            throw new IllegalArgumentException("Item-frame displays are only available for container shops.");
        }
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gt_shops SET visual_style = ? WHERE shop_uuid = ?")) {
            statement.setString(1, normalized);
            statement.setString(2, shop.id().toString());
            statement.executeUpdate();
        }
        return find(shop.id()).orElseThrow();
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
        pendingStockLinks.entrySet().removeIf(entry -> entry.getValue().equals(shopId));
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

    public PurchaseResult purchase(Player customer, ShopRecord requested, int units) throws SQLException {
        if (units <= 0 || units > 64) {
            return PurchaseResult.failure("Transaction units must be between 1 and 64.");
        }

        ShopRecord shop = find(requested.id()).orElse(null);
        if (shop == null || !shop.enabled()) {
            return PurchaseResult.failure("That shop is unavailable.");
        }

        ShopPrincipal principal = principal(shop);
        if (principal.player() && principal.id().equals(customer.getUniqueId())) {
            return PurchaseResult.failure("You already own this shop.");
        }

        long total;
        int itemCount;
        try {
            total = Math.multiplyExact(shop.price(), units);
            itemCount = Math.multiplyExact(shop.quantity(), units);
        } catch (ArithmeticException exception) {
            return PurchaseResult.failure("That transaction is too large.");
        }

        Object lock = purchaseLocks.computeIfAbsent(shop.id(), ignored -> new Object());
        synchronized (lock) {
            return shop.buysFromCustomer()
                    ? sellToShop(customer, shop, principal, itemCount, total, units)
                    : buyFromShop(customer, shop, principal, itemCount, total, units);
        }
    }

    private PurchaseResult buyFromShop(
            Player buyer, ShopRecord shop, ShopPrincipal principal, int itemCount, long total, int units)
            throws SQLException {
        ItemStack template = displayItem(shop);
        Block stockBlock = null;
        Inventory stock = null;
        if (!shop.unlimitedStock()) {
            stockBlock = stockBlockFor(shop);
            if (stockBlock == null || !(stockBlock.getState() instanceof Container container)) {
                return PurchaseResult.failure(shop.signShop()
                        ? "That sign shop is not connected to a stock container."
                        : "The shop container is missing.");
            }
            stock = container.getInventory();
            if (countSimilar(stock, template) < itemCount) {
                return PurchaseResult.failure("That shop is sold out.");
            }
        }
        if (!hasSpace(buyer.getInventory(), template, itemCount)) {
            return PurchaseResult.failure("You do not have enough inventory space.");
        }

        GardenOrder order = order(buyer, shop, principal, total, units, itemCount, "SELL");
        platform.orders().transition(order.id(), OrderState.READY, "Shop stock validated");
        platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Shop interaction confirms purchase");
        platform.orders().transition(order.id(), OrderState.PAYMENT_PENDING, "Collecting shop payment");

        if (stock != null && !removeSimilar(stock, template, itemCount)) {
            platform.orders().transition(order.id(), OrderState.CANCELLED, "Shop stock changed");
            return PurchaseResult.failure("The shop stock changed. Try again.");
        }

        if (!platform.currency().withdraw(buyer.getUniqueId(), total)) {
            if (stock != null) restore(stock, template, itemCount, stockBlock);
            platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED, "Buyer has insufficient Obols");
            return PurchaseResult.failure("You do not have enough Obols.");
        }

        if (!creditPrincipal(principal, total)) {
            boolean refunded = platform.currency().deposit(buyer.getUniqueId(), total);
            if (stock != null) restore(stock, template, itemCount, stockBlock);
            platform.orders().transition(order.id(),
                    refunded ? OrderState.PAYMENT_FAILED : OrderState.FULFILLMENT_FAILED,
                    refunded ? "Seller payment failed and buyer refunded"
                            : "Seller payment failed and buyer refund requires admin review");
            return PurchaseResult.failure(refunded
                    ? "The shop owner could not be paid. Your Obols were returned."
                    : "The purchase needs administrator review.");
        }

        platform.orders().transition(order.id(), OrderState.PAID, "Shop payment completed");
        platform.orders().transition(order.id(), OrderState.FULFILLING, "Delivering purchased items");

        int before = countSimilar(buyer.getInventory(), template);
        Map<Integer, ItemStack> leftovers =
                buyer.getInventory().addItem(stacks(template, itemCount).toArray(ItemStack[]::new));
        if (!leftovers.isEmpty()) {
            int after = countSimilar(buyer.getInventory(), template);
            int delivered = Math.max(0, after - before);
            if (delivered > 0) removeSimilar(buyer.getInventory(), template, delivered);
            if (stock != null) restore(stock, template, itemCount, stockBlock);

            if (!debitPrincipal(principal, total)) {
                platform.orders().transition(order.id(), OrderState.FULFILLMENT_FAILED,
                        "Inventory changed; shop payout could not be reversed");
                return PurchaseResult.failure("The purchase needs administrator review.");
            }
            if (!platform.currency().deposit(buyer.getUniqueId(), total)) {
                creditPrincipal(principal, total);
                platform.orders().transition(order.id(), OrderState.FULFILLMENT_FAILED,
                        "Inventory changed; buyer refund failed");
                return PurchaseResult.failure("The purchase needs administrator review.");
            }
            platform.orders().transition(order.id(), OrderState.REFUNDED,
                    "Inventory changed during fulfillment; transaction rolled back");
            return PurchaseResult.failure("Your inventory changed during the purchase. Your Obols were returned.");
        }

        platform.orders().transition(order.id(), OrderState.COMPLETED, "Items delivered");
        notifyPrincipalSale(principal, shop, itemCount, total);
        return PurchaseResult.purchase(itemCount, total, shop.itemLabel(), principal.displayName());
    }

    private PurchaseResult sellToShop(
            Player seller, ShopRecord shop, ShopPrincipal principal, int itemCount, long total, int units)
            throws SQLException {
        ItemStack template = displayItem(shop);
        if (countSimilar(seller.getInventory(), template) < itemCount) {
            return PurchaseResult.failure("You do not have " + itemCount + " " + shop.itemLabel() + " to sell.");
        }

        Block stockBlock = null;
        Inventory stock = null;
        if (!shop.unlimitedStock()) {
            stockBlock = stockBlockFor(shop);
            if (stockBlock == null || !(stockBlock.getState() instanceof Container container)) {
                return PurchaseResult.failure(shop.signShop()
                        ? "That buyback shop is not connected to a receiving container."
                        : "The shop container is missing.");
            }
            stock = container.getInventory();
            if (!hasSpace(stock, template, itemCount)) {
                return PurchaseResult.failure("That shop's receiving container is full.");
            }
        }

        GardenOrder order = order(seller, shop, principal, total, units, itemCount, "BUYBACK");
        platform.orders().transition(order.id(), OrderState.READY, "Seller items and receiving stock validated");
        platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Shop interaction confirms sale");
        platform.orders().transition(order.id(), OrderState.PAYMENT_PENDING, "Collecting shop owner payout");

        if (!removeSimilar(seller.getInventory(), template, itemCount)) {
            platform.orders().transition(order.id(), OrderState.CANCELLED, "Seller inventory changed");
            return PurchaseResult.failure("Your inventory changed. Try again.");
        }

        if (!debitPrincipal(principal, total)) {
            restore(seller.getInventory(), template, itemCount, seller.getLocation().getBlock());
            platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED, "Shop owner has insufficient Obols");
            return PurchaseResult.failure("That shop does not currently have enough Obols to buy your items.");
        }

        if (stock != null) {
            Map<Integer, ItemStack> leftovers = stock.addItem(stacks(template, itemCount).toArray(ItemStack[]::new));
            if (!leftovers.isEmpty()) {
                int inserted = itemCount - leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
                if (inserted > 0) removeSimilar(stock, template, inserted);
                creditPrincipal(principal, total);
                restore(seller.getInventory(), template, itemCount, seller.getLocation().getBlock());
                platform.orders().transition(order.id(), OrderState.FULFILLMENT_FAILED,
                        "Receiving container changed during buyback; transaction rolled back");
                return PurchaseResult.failure("The receiving container changed. Your items were returned.");
            }
        }

        if (!platform.currency().deposit(seller.getUniqueId(), total)) {
            if (stock != null) removeSimilar(stock, template, itemCount);
            creditPrincipal(principal, total);
            restore(seller.getInventory(), template, itemCount, seller.getLocation().getBlock());
            platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED,
                    "Seller payout failed; received items and shop funds were restored");
            return PurchaseResult.failure("Your payout could not be completed. Your items were returned.");
        }

        platform.orders().transition(order.id(), OrderState.PAID, "Seller payout completed");
        platform.orders().transition(order.id(), OrderState.FULFILLING,
                stock == null ? "Items accepted by unlimited shop" : "Items stored in receiving container");
        platform.orders().transition(order.id(), OrderState.COMPLETED, "Buyback completed");
        notifyPrincipalBuyback(principal, shop, itemCount, total);
        return PurchaseResult.sale(itemCount, total, shop.itemLabel(), principal.displayName());
    }

    private GardenOrder order(
            Player customer, ShopRecord shop, ShopPrincipal principal,
            long total, int units, int itemCount, String mode) throws SQLException {
        return platform.orders().create(
                OrderType.SHOP_PURCHASE,
                customer.getUniqueId(),
                principal.kind(),
                principal.id().toString(),
                total,
                "gardentrade.shop",
                shop.id().toString(),
                "{\"mode\":\"" + mode + "\",\"units\":" + units
                        + ",\"itemCount\":" + itemCount
                        + ",\"item\":\"" + json(shop.itemLabel())
                        + "\",\"shopOwner\":\"" + json(principal.displayName()) + "\"}"
        );
    }

    private void notifyPrincipalSale(ShopPrincipal principal, ShopRecord shop, int itemCount, long total) {
        if (!principal.player()) return;
        Player owner = Bukkit.getPlayer(principal.id());
        if (owner != null && owner.isOnline()) {
            GardenMessages.send(owner, "Shop sale: " + itemCount + " " + shop.itemLabel()
                    + " for " + platform.currency().symbol() + " " + total + ".");
        }
    }

    private void notifyPrincipalBuyback(ShopPrincipal principal, ShopRecord shop, int itemCount, long total) {
        if (!principal.player()) return;
        Player owner = Bukkit.getPlayer(principal.id());
        if (owner != null && owner.isOnline()) {
            GardenMessages.send(owner, "Shop buyback: paid " + platform.currency().symbol() + " " + total
                    + " for " + itemCount + " " + shop.itemLabel() + ".");
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
                     "SELECT COUNT(*) AS n FROM gt_shop_principals WHERE principal_kind = ? AND principal_id = ?")) {
            statement.setString(1, principal.kind());
            statement.setString(2, principal.id().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("n") : 0;
            }
        }
    }

    private boolean canManage(Player actor, ShopRecord shop, ShopPrincipal principal) {
        if (actor.hasPermission("gardentrade.shop.admin")) return true;
        if (principal.server()) return false;
        if (principal.player()) return principal.id().equals(actor.getUniqueId());
        return organizations != null
                && organizations.has(principal.id(), actor.getUniqueId(), OrganizationCapability.COMMERCE_MANAGE);
    }

    private boolean creditPrincipal(ShopPrincipal principal, long amount) throws SQLException {
        if (principal.server()) return true;
        if (principal.player()) return platform.currency().deposit(principal.id(), amount);
        return organizations != null && organizations.creditTreasury(principal.id(), amount);
    }

    private boolean debitPrincipal(ShopPrincipal principal, long amount) throws SQLException {
        if (principal.server()) return true;
        if (principal.player()) return platform.currency().withdraw(principal.id(), amount);
        return organizations != null && organizations.debitTreasury(principal.id(), amount);
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

    private void restore(Inventory inventory, ItemStack template, int amount, Block fallback) {
        Map<Integer, ItemStack> leftovers = inventory.addItem(stacks(template, amount).toArray(ItemStack[]::new));
        if (fallback != null) {
            leftovers.values().forEach(item ->
                    fallback.getWorld().dropItemNaturally(fallback.getLocation().add(0.5, 0.5, 0.5), item));
        }
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
        String stockWorldRaw = result.getString("stock_world_uuid");
        Object stockX = result.getObject("stock_x");
        Object stockY = result.getObject("stock_y");
        Object stockZ = result.getObject("stock_z");
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
                result.getString("shop_kind"),
                result.getString("transaction_mode"),
                result.getInt("unlimited_stock") != 0,
                stockWorldRaw == null ? null : UUID.fromString(stockWorldRaw),
                result.getString("stock_world_name"),
                stockX == null ? null : result.getInt("stock_x"),
                stockY == null ? null : result.getInt("stock_y"),
                stockZ == null ? null : result.getInt("stock_z"),
                result.getString("visual_style"),
                result.getInt("enabled") != 0,
                result.getLong("created_at")
        );
    }

    private String labelFor(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            String custom = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();
            if (!custom.isBlank()) return custom;
        }
        return pretty(item.getType());
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

    public record LinkResult(boolean handled, boolean success, String message) {
        public static LinkResult notPending() {
            return new LinkResult(false, false, "");
        }

        public static LinkResult handled(boolean success, String message) {
            return new LinkResult(true, success, message);
        }
    }

    public record PurchaseResult(boolean success, String message, int itemCount, long total) {
        public static PurchaseResult purchase(int itemCount, long total, String item, String seller) {
            return new PurchaseResult(true,
                    "Purchased " + itemCount + " " + item + " from " + seller
                            + " for ⟡ " + total + ".", itemCount, total);
        }

        public static PurchaseResult sale(int itemCount, long total, String item, String buyer) {
            return new PurchaseResult(true,
                    "Sold " + itemCount + " " + item + " to " + buyer
                            + " for ⟡ " + total + ".", itemCount, total);
        }

        public static PurchaseResult failure(String message) {
            return new PurchaseResult(false, message, 0, 0L);
        }
    }
}
