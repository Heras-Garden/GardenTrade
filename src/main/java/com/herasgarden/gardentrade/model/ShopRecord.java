package com.herasgarden.gardentrade.model;

import java.util.UUID;

public record ShopRecord(
        UUID id,
        UUID ownerId,
        String ownerName,
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        String itemData,
        String itemLabel,
        int quantity,
        long price,
        String shopKind,
        String transactionMode,
        boolean unlimitedStock,
        UUID stockWorldId,
        String stockWorldName,
        Integer stockX,
        Integer stockY,
        Integer stockZ,
        String visualStyle,
        boolean enabled,
        long createdAt
) {
    public boolean signShop() {
        return "SIGN".equalsIgnoreCase(shopKind);
    }

    public boolean containerShop() {
        return "CONTAINER".equalsIgnoreCase(shopKind);
    }

    public boolean sellsToCustomer() {
        return "SELL".equalsIgnoreCase(transactionMode);
    }

    public boolean buysFromCustomer() {
        return "BUY".equalsIgnoreCase(transactionMode);
    }

    public boolean hasStockContainer() {
        return stockWorldId != null && stockX != null && stockY != null && stockZ != null;
    }
}
