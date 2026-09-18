package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.land.LandAccessService;
import com.herasgarden.gardencore.api.organization.OrganizationDirectory;
import com.herasgarden.gardentrade.storage.TradeSchema;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class GardenTrade extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<GardenPlatform> platformRegistration =
                getServer().getServicesManager().getRegistration(GardenPlatform.class);
        if (platformRegistration == null || platformRegistration.getProvider() == null) {
            getLogger().severe("GardenCore platform service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        GardenPlatform platform = platformRegistration.getProvider();

        try {
            TradeSchema.ensure(platform.storage());
        } catch (SQLException exception) {
            getLogger().severe("GardenTrade could not prepare storage: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RegisteredServiceProvider<LandAccessService> landRegistration =
                getServer().getServicesManager().getRegistration(LandAccessService.class);
        LandAccessService land = landRegistration == null ? null : landRegistration.getProvider();

        RegisteredServiceProvider<OrganizationDirectory> organizationRegistration =
                getServer().getServicesManager().getRegistration(OrganizationDirectory.class);
        if (organizationRegistration == null || organizationRegistration.getProvider() == null) {
            getLogger().severe("GardenCore organization service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        OrganizationDirectory organizations = organizationRegistration.getProvider();

        ShopService shops = new ShopService(this, platform, land, organizations);
        int maxPlayerShops = getConfig().getInt("shops.max-per-player", 15);
        ShopVisualService visuals = new ShopVisualService(this, shops);
        visuals.start();

        ShopCommand shopCommand = new ShopCommand(
                shops,
                maxPlayerShops,
                getConfig().getInt("shops.max-per-organization", 30),
                getConfig().getInt("shops.target-distance", 6)
        );

        PluginCommand shop = getCommand("shop");
        if (shop != null) {
            shop.setExecutor(shopCommand);
            shop.setTabCompleter(shopCommand);
        }

        getServer().getPluginManager().registerEvents(new ShopCleanupListener(shops), this);
        getServer().getPluginManager().registerEvents(
                new ShopSignListener(this, shops, visuals, maxPlayerShops), this);
        getLogger().info("GardenTrade enabled. ChestShop-style sign shops, visuals, and organization storefront transactions are active.");
    }
}
