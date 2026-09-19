package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.organization.OrganizationView;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardentrade.model.ShopPrincipal;
import com.herasgarden.gardentrade.model.ShopRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ShopCommand implements CommandExecutor, TabCompleter {
    private final ShopService shops;
    private final ShopVisualService visuals;
    private final int maxShops;
    private final int maxOrganizationShops;
    private final int targetDistance;

    public ShopCommand(
            ShopService shops,
            ShopVisualService visuals,
            int maxShops,
            int maxOrganizationShops,
            int targetDistance
    ) {
        this.shops = shops;
        this.visuals = visuals;
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
                case "create" -> createContainer(player, args, false);
                case "createbuy" -> createContainer(player, args, true);
                case "companycreate" -> organizationContainer(player, args, true, false);
                case "companycreatebuy" -> organizationContainer(player, args, true, true);
                case "governmentcreate" -> organizationContainer(player, args, false, false);
                case "governmentcreatebuy" -> organizationContainer(player, args, false, true);
                case "companysign" -> organizationSign(player, args, true);
                case "governmentsign" -> organizationSign(player, args, false);
                case "companylist" -> organizationList(player, args, true);
                case "governmentlist" -> organizationList(player, args, false);
                case "info" -> info(player);
                case "buy" -> transact(player, args);
                case "settings" -> settings(player);
                case "link" -> link(player);
                case "unlink" -> unlink(player);
                case "appearance" -> appearance(player, args);
                case "setprice" -> setPrice(player, args);
                case "setquantity" -> setQuantity(player, args);
                case "mode" -> mode(player, args);
                case "enable" -> enabled(player, true);
                case "disable" -> enabled(player, false);
                case "unlimited" -> unlimited(player, args);
                case "delete" -> delete(player);
                case "list" -> list(player);
                default -> {
                    help(player);
                    yield true;
                }
            };
        } catch (NumberFormatException exception) {
            send(player, "Quantity and price must be whole numbers.");
        } catch (IllegalArgumentException exception) {
            send(player, exception.getMessage());
        } catch (SQLException exception) {
            send(player, "The shop system could not update right now.");
        }
        return true;
    }

    private boolean createContainer(Player player, String[] args, boolean buyback) throws SQLException {
        if (!player.hasPermission("gardentrade.shop.create")) {
            send(player, "You do not have permission to create shops.");
            return true;
        }
        if (args.length < 3) {
            send(player, "Hold the item, look at the stock container, then use /shop "
                    + (buyback ? "createbuy" : "create") + " <quantity> <price>.");
            return true;
        }

        int quantity = Integer.parseInt(args[1]);
        long price = Long.parseLong(args[2]);
        Block block = targetContainer(player);
        if (block == null) return true;
        ItemStack item = player.getInventory().getItemInMainHand();
        ShopRecord shop = buyback
                ? shops.createBuyback(player, block, item, quantity, price, maxShops)
                : shops.create(player, block, item, quantity, price, maxShops);
        visuals.refresh();
        sendCreated(player, shop);
        return true;
    }

    private boolean organizationContainer(
            Player player, String[] args, boolean company, boolean buyback) throws SQLException {
        String kind = company ? "company" : "government";
        String permission = company
                ? "gardentrade.shop.company.create"
                : "gardentrade.shop.government.create";
        String commandName = company
                ? (buyback ? "companycreatebuy" : "companycreate")
                : (buyback ? "governmentcreatebuy" : "governmentcreate");

        if (!player.hasPermission(permission)) {
            send(player, "You do not have permission to create " + kind + " shops.");
            return true;
        }
        if (args.length < 4) {
            send(player, "Hold the item, look at the stock container, then use /shop "
                    + commandName + " <" + kind + " name> <quantity> <price>.");
            return true;
        }

        int quantity = Integer.parseInt(args[args.length - 2]);
        long price = Long.parseLong(args[args.length - 1]);
        String organizationName = String.join(
                " ", Arrays.copyOfRange(args, 1, args.length - 2)).trim();
        if (organizationName.isBlank()) {
            send(player, "Specify the Garden " + kind + " that should own this shop.");
            return true;
        }

        Block target = targetContainer(player);
        if (target == null) return true;
        ItemStack item = player.getInventory().getItemInMainHand();
        ShopRecord shop;
        if (company) {
            shop = buyback
                    ? shops.createCompanyBuyback(
                            player, organizationName, target, item, quantity, price, maxOrganizationShops)
                    : shops.createCompany(
                            player, organizationName, target, item, quantity, price, maxOrganizationShops);
        } else {
            shop = buyback
                    ? shops.createGovernmentBuyback(
                            player, organizationName, target, item, quantity, price, maxOrganizationShops)
                    : shops.createGovernment(
                            player, organizationName, target, item, quantity, price, maxOrganizationShops);
        }
        visuals.refresh();
        sendCreated(player, shop);
        return true;
    }

    private boolean organizationSign(Player player, String[] args, boolean company) throws SQLException {
        String kind = company ? "company" : "government";
        String permission = company
                ? "gardentrade.shop.company.create"
                : "gardentrade.shop.government.create";
        if (!player.hasPermission(permission)) {
            send(player, "You do not have permission to create " + kind + " sign shops.");
            return true;
        }
        if (args.length < 5) {
            send(player, "Hold the item, look at an unused sign, then use /shop "
                    + (company ? "companysign" : "governmentsign")
                    + " <" + kind + " name> <sell|buy> <quantity> <price>.");
            return true;
        }

        String mode = args[args.length - 3].toLowerCase(Locale.ROOT);
        if (!mode.equals("sell") && !mode.equals("buy")) {
            throw new IllegalArgumentException("Sign shop mode must be sell or buy.");
        }
        int quantity = Integer.parseInt(args[args.length - 2]);
        long price = Long.parseLong(args[args.length - 1]);
        String organizationName = String.join(
                " ", Arrays.copyOfRange(args, 1, args.length - 3)).trim();
        if (organizationName.isBlank()) {
            throw new IllegalArgumentException("Specify the Garden " + kind + " that should own this shop.");
        }

        Block sign = targetSign(player);
        if (sign == null) return true;
        ItemStack item = player.getInventory().getItemInMainHand();
        ShopRecord shop = company
                ? shops.createCompanySign(
                        player, organizationName, sign, item, quantity, price, mode.equals("buy"), maxOrganizationShops)
                : shops.createGovernmentSign(
                        player, organizationName, sign, item, quantity, price, mode.equals("buy"), maxOrganizationShops);
        visuals.refresh();
        sendCreated(player, shop);
        send(player, "Use /shop link while looking at the sign, then right-click its stock/receiving container.");
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

        String organizationName = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
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
        for (ShopRecord shop : records) player.sendMessage(shopRow(shop));
        return true;
    }

    private boolean info(Player player) throws SQLException {
        ShopRecord shop = targetShop(player);
        if (shop == null) return true;
        ShopPrincipal principal = shops.principal(shop);
        player.sendMessage(GardenMessages.prefix().append(Component.text("Shop", NamedTextColor.WHITE)));
        player.sendMessage(Component.text(summary(shop), NamedTextColor.WHITE));
        player.sendMessage(Component.text(
                "Owner: " + principal.displayName()
                        + (principal.organization() ? " (Organization)" : "")
                        + " | ID: " + shortId(shop),
                NamedTextColor.GRAY));
        player.sendMessage(Component.text(stockSummary(shop), NamedTextColor.GRAY));
        return true;
    }

    private boolean transact(Player player, String[] args) throws SQLException {
        ShopRecord shop = targetShop(player);
        if (shop == null) return true;
        int units = args.length >= 2 ? Integer.parseInt(args[1]) : 1;
        ShopService.PurchaseResult result = shops.purchase(player, shop, units);
        send(player, result.message());
        visuals.refresh();
        return true;
    }

    private boolean settings(Player player) throws SQLException {
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;

        send(player, "Shop settings: " + shortId(shop) + " | " + summary(shop));
        send(player, stockSummary(shop));

        Component row = GardenMessages.prefix()
                .append(button("[Sell]", "/shop mode sell", "Shop sells items to customers"))
                .append(Component.space())
                .append(button("[Buyback]", "/shop mode buy", "Shop buys items from customers"))
                .append(Component.space())
                .append(button("[Enable]", "/shop enable", "Enable transactions"))
                .append(Component.space())
                .append(button("[Disable]", "/shop disable", "Disable transactions"));
        player.sendMessage(row);

        Component appearance = GardenMessages.prefix()
                .append(button("[Both]", "/shop appearance both", "Show item and text"))
                .append(Component.space())
                .append(button("[Item]", "/shop appearance item", "Show only the item"))
                .append(Component.space())
                .append(button("[Text]", "/shop appearance text", "Show only text"))
                .append(Component.space())
                .append(button("[Frame]", "/shop appearance frame", "Use an invisible locked item frame on the container with compact text"))
                .append(Component.space())
                .append(button("[None]", "/shop appearance none", "Hide normal shop holograms"));
        player.sendMessage(appearance);

        if (shop.signShop() && !shop.unlimitedStock()) {
            player.sendMessage(GardenMessages.prefix()
                    .append(button("[Link Stock]", "/shop link", "Click a container after selecting this"))
                    .append(Component.space())
                    .append(button("[Unlink Stock]", "/shop unlink", "Remove the connected stock container")));
        }
        if (player.hasPermission("gardentrade.shop.admin")) {
            player.sendMessage(GardenMessages.prefix()
                    .append(button("[Unlimited On]", "/shop unlimited on", "Ignore physical stock"))
                    .append(Component.space())
                    .append(button("[Unlimited Off]", "/shop unlimited off", "Require physical stock")));
        }
        send(player, "Change values with /shop setprice <obols> and /shop setquantity <amount>.");
        return true;
    }

    private boolean link(Player player) throws SQLException {
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        shops.beginStockLink(player, shop);
        send(player, "Now right-click the chest, barrel, or container this sign shop should use for stock.");
        return true;
    }

    private boolean unlink(Player player) throws SQLException {
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        ShopRecord updated = shops.unlinkStock(player, shop);
        visuals.refresh();
        send(player, "Stock container unlinked from " + updated.itemLabel() + " shop.");
        return true;
    }

    private boolean appearance(Player player, String[] args) throws SQLException {
        if (args.length != 2) {
            send(player, "Use /shop appearance <both|item|text|frame|none>.");
            return true;
        }
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        ShopRecord updated = shops.setVisualStyle(player, shop, args[1]);
        visuals.refresh();
        send(player, "Shop appearance set to " + updated.visualStyle().toLowerCase(Locale.ROOT) + ".");
        return true;
    }

    private boolean setPrice(Player player, String[] args) throws SQLException {
        if (args.length != 2) {
            send(player, "Use /shop setprice <obols>.");
            return true;
        }
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        ShopRecord updated = shops.setPrice(player, shop, Long.parseLong(args[1].replace(",", "")));
        visuals.refresh();
        send(player, "Shop price set to ⟡ " + updated.price() + ".");
        return true;
    }

    private boolean setQuantity(Player player, String[] args) throws SQLException {
        if (args.length != 2) {
            send(player, "Use /shop setquantity <amount>.");
            return true;
        }
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        ShopRecord updated = shops.setQuantity(player, shop, Integer.parseInt(args[1].replace(",", "")));
        visuals.refresh();
        send(player, "Shop quantity set to " + updated.quantity() + ".");
        return true;
    }

    private boolean mode(Player player, String[] args) throws SQLException {
        if (args.length != 2) {
            send(player, "Use /shop mode <sell|buy>.");
            return true;
        }
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        ShopRecord updated = shops.setTransactionMode(player, shop, args[1]);
        visuals.refresh();
        send(player, updated.buysFromCustomer()
                ? "Shop now buys items from customers."
                : "Shop now sells items to customers.");
        return true;
    }

    private boolean enabled(Player player, boolean enabled) throws SQLException {
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        shops.setEnabled(player, shop, enabled);
        visuals.refresh();
        send(player, "Shop " + (enabled ? "enabled." : "disabled."));
        return true;
    }

    private boolean unlimited(Player player, String[] args) throws SQLException {
        if (args.length != 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            send(player, "Use /shop unlimited <on|off>.");
            return true;
        }
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        ShopRecord updated = shops.setUnlimited(player, shop, args[1].equalsIgnoreCase("on"));
        visuals.refresh();
        send(player, "Unlimited stock " + (updated.unlimitedStock() ? "enabled." : "disabled."));
        return true;
    }

    private boolean delete(Player player) throws SQLException {
        ShopRecord shop = managedTargetShop(player);
        if (shop == null) return true;
        shops.delete(shop.id());
        visuals.refresh();
        send(player, "Shop deleted.");
        return true;
    }

    private boolean list(Player player) throws SQLException {
        List<ShopRecord> records = shops.ownedBy(player.getUniqueId());
        player.sendMessage(GardenMessages.prefix().append(Component.text("Your shops", NamedTextColor.WHITE)));
        if (records.isEmpty()) {
            player.sendMessage(Component.text("You do not own any Garden shops.", NamedTextColor.WHITE));
            return true;
        }
        for (ShopRecord shop : records) player.sendMessage(shopRow(shop));
        return true;
    }

    private ShopRecord managedTargetShop(Player player) throws SQLException {
        ShopRecord shop = targetShop(player);
        if (shop != null && !shops.canManage(player, shop)) {
            send(player, "You do not manage this shop.");
            return null;
        }
        return shop;
    }

    private ShopRecord targetShop(Player player) throws SQLException {
        Block block = player.getTargetBlockExact(targetDistance);
        if (block == null) {
            send(player, "Look directly at a Garden shop first.");
            return null;
        }
        ShopRecord shop = shops.shopAt(block).orElse(null);
        if (shop == null) send(player, "That block is not a Garden shop.");
        return shop;
    }

    private Block targetContainer(Player player) {
        Block block = player.getTargetBlockExact(targetDistance);
        if (block == null || !ShopBlockKey.supported(block)) {
            send(player, "Look directly at a chest, barrel, or other container first.");
            return null;
        }
        return block;
    }

    private Block targetSign(Player player) {
        Block block = player.getTargetBlockExact(targetDistance);
        if (block == null || !(block.getState() instanceof Sign)) {
            send(player, "Look directly at an unused sign first.");
            return null;
        }
        return block;
    }

    private void sendCreated(Player player, ShopRecord shop) throws SQLException {
        ShopPrincipal principal = shops.principal(shop);
        send(player, "Shop " + shortId(shop) + " created for " + principal.displayName()
                + ". " + summary(shop));
    }

    private String summary(ShopRecord shop) {
        String action = shop.buysFromCustomer() ? "Buys " : "Sells ";
        return action + shop.quantity() + " " + shop.itemLabel() + " for ⟡ " + shop.price()
                + (shop.enabled() ? "" : " | Disabled");
    }

    private String stockSummary(ShopRecord shop) {
        if (shop.unlimitedStock()) return "Stock: unlimited.";
        if (shop.hasStockContainer()) {
            return "Stock: connected at " + shop.stockWorldName() + " "
                    + shop.stockX() + "," + shop.stockY() + "," + shop.stockZ() + ".";
        }
        return "Stock: not connected.";
    }

    private Component shopRow(ShopRecord shop) {
        return Component.text(
                shortId(shop) + " | " + summary(shop) + " | "
                        + shop.worldName() + " " + shop.x() + "," + shop.y() + "," + shop.z(),
                NamedTextColor.WHITE);
    }

    private Component button(String label, String command, String hover) {
        return Component.text(label, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    private String shortId(ShopRecord shop) {
        return shop.id().toString().substring(0, 8);
    }

    private void help(Player player) {
        send(player, "/shop create <quantity> <price> or /shop createbuy <quantity> <price> for chest shops.");
        send(player, "For wall/standing signs, use [SignShop], [BuyShop], [AdminShop], or [AdminBuy].");
        send(player, "/shop settings, link, unlink, appearance, setprice, setquantity, mode, enable, disable, delete.");
        send(player, "/shop companycreate..., companycreatebuy..., companysign..., and matching government commands are supported.");
    }

    private void send(CommandSender sender, String message) {
        GardenMessages.send(sender, message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of(
                    "create", "createbuy",
                    "companycreate", "companycreatebuy", "companysign", "companylist",
                    "governmentcreate", "governmentcreatebuy", "governmentsign", "governmentlist",
                    "info", "buy", "settings", "link", "unlink", "appearance",
                    "setprice", "setquantity", "mode", "enable", "disable", "unlimited",
                    "delete", "list").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("appearance")) {
            return List.of("both", "item", "text", "frame", "none");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return List.of("sell", "buy");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unlimited")) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
