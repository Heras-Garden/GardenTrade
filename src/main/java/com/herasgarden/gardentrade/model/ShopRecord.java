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
        boolean enabled,
        long createdAt
) {
}
