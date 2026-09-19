package com.herasgarden.gardentrade.model;

import java.util.UUID;

public record ShopPrincipal(
        String kind,
        UUID id,
        String displayName
) {
    public boolean organization() {
        return "ORGANIZATION".equals(kind);
    }

    public boolean player() {
        return "PLAYER".equals(kind);
    }

    public boolean server() {
        return "SERVER".equals(kind);
    }
}
