package com.herasgarden.gardentrade.storage;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class TradeSchema {
    private TradeSchema() {}

    public static void ensure(GardenStorage storage) throws SQLException {
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_shops ("
                    + "shop_uuid VARCHAR(36) PRIMARY KEY,"
                    + "owner_uuid VARCHAR(36) NOT NULL,"
                    + "owner_name VARCHAR(32) NOT NULL,"
                    + "world_uuid VARCHAR(36) NOT NULL,"
                    + "world_name VARCHAR(128) NOT NULL,"
                    + "x INTEGER NOT NULL,"
                    + "y INTEGER NOT NULL,"
                    + "z INTEGER NOT NULL,"
                    + "item_data TEXT NOT NULL,"
                    + "item_label VARCHAR(128) NOT NULL,"
                    + "quantity INTEGER NOT NULL,"
                    + "price BIGINT NOT NULL,"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "created_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_gt_shops_block "
                    + "ON gt_shops (world_uuid, x, y, z)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gt_shops_owner "
                    + "ON gt_shops (owner_uuid, created_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_shop_principals ("
                    + "shop_uuid VARCHAR(36) PRIMARY KEY,"
                    + "principal_kind VARCHAR(24) NOT NULL,"
                    + "principal_id VARCHAR(36) NOT NULL,"
                    + "display_name VARCHAR(64) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gt_shop_principal "
                    + "ON gt_shop_principals (principal_kind, principal_id)");
        }
    }
}
