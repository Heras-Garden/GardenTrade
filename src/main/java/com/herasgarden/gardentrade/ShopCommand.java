package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardencore.api.organization.OrganizationView;
import com.herasgarden.gardentrade.model.ShopPrincipal;
import com.herasgarden.gardentrade.model.ShopRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ShopCommand implements CommandExecutor, TabCompleter {
    private final ShopService shops;
    private final int maxShops;
    private final int maxOrganizationShops;
    private final int targetDistance;

    public ShopCommand(ShopService shops, int maxShops, int maxOrganizationShops, int targetDistance) {
        this.shops = shops;
        this.maxShops = Math.max(1, maxShops);
        this.maxOrganizationShops = Math.max(1, maxOrganizationShops);
        this.targetDistance = Math.max(3, targetDistance);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "Shop commands must be used in-game.");
            return true;
        }
        if (!player.hasPermission("gardentrade.shop")) {
            send(player, "You do not have permission to use Garden shops.");
            return true;
        }
        if (args.length == 0) {
            help(player);
            return true;
        }

        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(player, args);
                case "companycreate" -> companyCreate(player, args);
                case "companylist" -> companyList(player, args);
                case "governmentcreate" -> governmentCreate(player, args);
                case "governmentlist" -> governmentList(player, args);
                case "info" -> info(player);
                case "buy" -> buy(player, args);
                case "delete" -> delete(player);
                case "list" -> list(player);
                default -> {
                    help(player);
                    yield true;
                }
            };
        } catch (IllegalArgumentException exception) {
            send(player, exception.getMessage());
        } catch (SQLException exception) {
            send(player, "The shop system could not update right now.");
        }
        return true;
    }

    private boolean create(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardentrade.shop.create")) {
            send(player, "You do not have permission to create shops.");
            return true;
        }
        if (args.length < 3) {
            send(player, "Hold the item to sell, look at the stock container, then use /shop create <quantity> <price>.");
            return true;
        }

        int quantity;
        long price;
        try {
            quantity = Integer.parseInt(args[1]);
            price = Long.parseLong(args[2]);
        } catch (NumberFormatException exception) {
            send(player, "Quantity and price must be whole numbers.");
            return true;
        }

        Block block = target(player);
        if (block == null) return true;
        ItemStack item = player.getInventory().getItemInMainHand();
        ShopRecord shop = shops.create(player, block, item, quantity, price, maxShops);
        send(player, "Shop " + shop.id().toString().substring(0, 8) + " created. It sells "
                + shop.quantity() + " " + shop.itemLabel() + " for ⟡ " + shop.price() + " per purchase.");
        return true;
    }

    private boolean companyCreate(Player player, String[] args) throws SQLException {
        return organizationCreate(player, args, true);
    }

    private boolean governmentCreate(Player player, String[] args) throws SQLException {
        return organizationCreate(player, args, false);
    }

    private boolean organizationCreate(Player player, String[] args, boolean company) throws SQLException {
        String kind = company ? "company" : "government";
        String permission = company
                ? "gardentrade.shop.company.create"
                : "gardentrade.shop.government.create";
        String commandName = company ? "companycreate" : "governmentcreate";

        if (!player.hasPermission(permission)) {
            send(player, "You do not have permission to create " + kind + " shops.");
            return true;
        }
        if (args.length < 4) {
            send(player, "Hold the item to sell, look at the stock container, then use "
                    + "/shop " + commandName + " <" + kind + " name> <quantity> <price>.");
            return true;
        }

        int quantity;
        long price;
        try {
            quantity = Integer.parseInt(args[args.length - 2]);
            price = Long.parseLong(args[args.length - 1]);
        } catch (NumberFormatException exception) {
            send(player, "Quantity and price must be whole numbers.");
            return true;
        }

        String organizationName = String.join(
                " ", java.util.Arrays.copyOfRange(args, 1, args.length - 2)).trim();
        if (organizationName.isBlank()) {
            send(player, "Specify the Garden " + kind + " that should own this shop.");
            return true;
        }

        Block target = target(player);
        if (target == null) return true;
        ItemStack item = player.getInventory().getItemInMainHand();
        ShopRecord shop = company
                ? shops.createCompany(
                    player, organizationName, target, item, quantity, price, maxOrganizationShops)
                : shops.createGovernment(
                    player, organizationName, target, item, quantity, price, maxOrganizationShops);

        ShopPrincipal principal = shops.principal(shop);
        send(player, Character.toUpperCase(kind.charAt(0)) + kind.substring(1)
                + " shop " + shop.id().toString().substring(0, 8)
                + " created for " + principal.displayName() + ". It sells "
                + shop.quantity() + " " + shop.itemLabel() + " for ⟡ "
                + shop.price() + " per purchase.");
        return true;
    }

    private boolean companyList(Player player, String[] args) throws SQLException {
        return organizationList(player, args, true);
    }

    private boolean governmentList(Player player, String[] args) throws SQLException {
        return organizationList(player, args, false);
    }

    private boolean organizationList(Player player, String[] args, boolean company) throws SQLException {
        String kind = company ? "company" : "government";
        String permission = company
                ? "gardentrade.shop.company.list"
                : "gardentrade.shop.government.list";
        if (!player.hasPermission(permission)) {
            send(player, "You do not have permission to view " + kind + " shops.");
            return true;
        }
        if (args.length < 2) {
            send(player, "Use /shop " + (company ? "companylist" : "governmentlist")
                    + " <" + kind + " name>.");
            return true;
        }

        String organizationName = String.join(
                " ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        OrganizationView organization = company
                ? shops.company(organizationName)
                : shops.government(organizationName);
        List<ShopRecord> records = shops.ownedByOrganization(organization.id());

        player.sendMessage(GardenMessages.prefix()
                .append(Component.text(organization.name() + " shops", NamedTextColor.WHITE)));
        if (records.isEmpty()) {
            player.sendMessage(Component.text(
                    "This " + kind + " does not own any Garden shops.", NamedTextColor.WHITE));
            return true;
        }

        for (ShopRecord shop : records) {
            player.sendMessage(Component.text(
                    shop.id().toString().substring(0, 8) + " | "
                            + shop.quantity() + " " + shop.itemLabel() + " | ⟡ " + shop.price()
                            + " | " + shop.worldName() + " "
                            + shop.x() + "," + shop.y() + "," + shop.z(),
                    NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean info(Player player) throws SQLException {
        Block block = target(player);
        if (block == null) return true;
        Optional<ShopRecord> shop = shops.shopAt(block);
        if (shop.isEmpty()) {
            send(player, "That container is not a Garden shop.");
            return true;
        }
        ShopRecord value = shop.get();
        ShopPrincipal principal = shops.principal(value);
        player.sendMessage(GardenMessages.prefix()
                .append(Component.text("Shop", NamedTextColor.WHITE)));
        player.sendMessage(Component.text(
                value.quantity() + " " + value.itemLabel() + " for ⟡ " + value.price(),
                NamedTextColor.WHITE));
        player.sendMessage(Component.text(
                "Seller: " + principal.displayName()
                        + (principal.organization() ? " (Organization)" : "")
                        + " | ID: " + value.id().toString().substring(0, 8),
                NamedTextColor.GRAY));
        return true;
    }

    private boolean buy(Player player, String[] args) throws SQLException {
        Block block = target(player);
        if (block == null) return true;
        Optional<ShopRecord> shop = shops.shopAt(block);
        if (shop.isEmpty()) {
            send(player, "That container is not a Garden shop.");
            return true;
        }

        int units = 1;
        if (args.length >= 2) {
            try {
                units = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                send(player, "Purchase units must be a whole number.");
                return true;
            }
        }

        ShopService.PurchaseResult result = shops.purchase(player, shop.get(), units);
        send(player, result.message());
        return true;
    }

    private boolean delete(Player player) throws SQLException {
        Block block = target(player);
        if (block == null) return true;
        send(player, shops.delete(player, block) ? "Shop deleted." : "That container is not a Garden shop.");
        return true;
    }

    private boolean list(Player player) throws SQLException {
        List<ShopRecord> records = shops.ownedBy(player.getUniqueId());
        player.sendMessage(GardenMessages.prefix()
                .append(Component.text("Your shops", NamedTextColor.WHITE)));
        if (records.isEmpty()) {
            player.sendMessage(Component.text("You do not own any Garden shops.", NamedTextColor.WHITE));
            return true;
        }
        for (ShopRecord shop : records) {
            player.sendMessage(Component.text(
                    shop.id().toString().substring(0, 8) + " | "
                            + shop.quantity() + " " + shop.itemLabel() + " | ⟡ " + shop.price()
                            + " | " + shop.worldName() + " " + shop.x() + "," + shop.y() + "," + shop.z(),
                    NamedTextColor.WHITE));
        }
        return true;
    }

    private Block target(Player player) {
        Block block = player.getTargetBlockExact(targetDistance);
        if (block == null || !ShopBlockKey.supported(block)) {
            send(player, "Look directly at a chest, barrel, or other container first.");
            return null;
        }
        return block;
    }

    private void help(Player player) {
        send(player, "/shop create <quantity> <price>, "
                + "/shop companycreate <company name> <quantity> <price>, "
                + "/shop governmentcreate <government name> <quantity> <price>, "
                + "/shop info, /shop buy [units], /shop delete, /shop list, "
                + "/shop companylist <company name>, /shop governmentlist <government name>");
    }

    private void send(CommandSender sender, String message) {
        GardenMessages.send(sender, message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of(
                    "create", "companycreate", "companylist", "governmentcreate", "governmentlist",
                    "info", "buy", "delete", "list").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
