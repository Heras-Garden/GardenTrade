package com.herasgarden.gardentrade.api;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Account-based retail consumption for non-player Garden identities.
 * Purchases consume one configured shop unit rather than placing an ItemStack
 * into a Bukkit inventory.
 */
public interface SocietyMarketDirectory {
    Optional<Offer> nearestOpenOffer(
            UUID worldId,
            double x,
            double y,
            double z,
            long maxPrice
    ) throws SQLException;

    PurchaseResult consume(UUID consumerId, String consumerName, UUID shopId) throws SQLException;

    record Offer(UUID shopId, UUID worldId, double x, double y, double z,
                 String itemLabel, int quantity, long price) {}

    record PurchaseResult(boolean success, String message, long price, String itemLabel) {
        public static PurchaseResult failure(String message) {
            return new PurchaseResult(false, message, 0L, null);
        }
        public static PurchaseResult success(long price, String itemLabel) {
            return new PurchaseResult(true, "Purchase completed.", price, itemLabel);
        }
    }
}
