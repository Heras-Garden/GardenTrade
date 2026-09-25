package com.herasgarden.gardentrade.storage;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.ResultSet;
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
                    + "shop_kind VARCHAR(24) NOT NULL DEFAULT 'CONTAINER',"
                    + "transaction_mode VARCHAR(24) NOT NULL DEFAULT 'SELL',"
                    + "unlimited_stock INTEGER NOT NULL DEFAULT 0,"
                    + "stock_world_uuid VARCHAR(36) NULL,"
                    + "stock_world_name VARCHAR(128) NULL,"
                    + "stock_x INTEGER NULL,"
                    + "stock_y INTEGER NULL,"
                    + "stock_z INTEGER NULL,"
                    + "visual_style VARCHAR(24) NOT NULL DEFAULT 'BOTH',"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "created_at BIGINT NOT NULL)");

            ensureColumn(connection, "gt_shops", "shop_kind",
                    "ALTER TABLE gt_shops ADD COLUMN shop_kind VARCHAR(24) NOT NULL DEFAULT 'CONTAINER'");
            ensureColumn(connection, "gt_shops", "transaction_mode",
                    "ALTER TABLE gt_shops ADD COLUMN transaction_mode VARCHAR(24) NOT NULL DEFAULT 'SELL'");
            ensureColumn(connection, "gt_shops", "unlimited_stock",
                    "ALTER TABLE gt_shops ADD COLUMN unlimited_stock INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "gt_shops", "stock_world_uuid",
                    "ALTER TABLE gt_shops ADD COLUMN stock_world_uuid VARCHAR(36) NULL");
            ensureColumn(connection, "gt_shops", "stock_world_name",
                    "ALTER TABLE gt_shops ADD COLUMN stock_world_name VARCHAR(128) NULL");
            ensureColumn(connection, "gt_shops", "stock_x",
                    "ALTER TABLE gt_shops ADD COLUMN stock_x INTEGER NULL");
            ensureColumn(connection, "gt_shops", "stock_y",
                    "ALTER TABLE gt_shops ADD COLUMN stock_y INTEGER NULL");
            ensureColumn(connection, "gt_shops", "stock_z",
                    "ALTER TABLE gt_shops ADD COLUMN stock_z INTEGER NULL");
            ensureColumn(connection, "gt_shops", "visual_style",
                    "ALTER TABLE gt_shops ADD COLUMN visual_style VARCHAR(24) NOT NULL DEFAULT 'BOTH'");

            statement.executeUpdate("UPDATE gt_shops SET "
                    + "stock_world_uuid = world_uuid, stock_world_name = world_name, "
                    + "stock_x = x, stock_y = y, stock_z = z "
                    + "WHERE shop_kind = 'CONTAINER' AND stock_world_uuid IS NULL");

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

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_businesses ("
                    + "business_uuid VARCHAR(36) PRIMARY KEY,"
                    + "name VARCHAR(64) NOT NULL UNIQUE,"
                    + "owner_uuid VARCHAR(36) NOT NULL,"
                    + "owner_name VARCHAR(32) NOT NULL,"
                    + "state_override VARCHAR(12) NOT NULL DEFAULT 'AUTO',"
                    + "override_until_day BIGINT NULL,"
                    + "override_until_minute INTEGER NULL,"
                    + "created_at BIGINT NOT NULL)");
            ensureColumn(connection, "gt_businesses", "state_override",
                    "ALTER TABLE gt_businesses ADD COLUMN state_override VARCHAR(12) NOT NULL DEFAULT 'AUTO'");
            ensureColumn(connection, "gt_businesses", "override_until_day",
                    "ALTER TABLE gt_businesses ADD COLUMN override_until_day BIGINT NULL");
            ensureColumn(connection, "gt_businesses", "override_until_minute",
                    "ALTER TABLE gt_businesses ADD COLUMN override_until_minute INTEGER NULL");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gt_business_owner "
                    + "ON gt_businesses (owner_uuid, created_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_workplaces ("
                    + "workplace_uuid VARCHAR(36) PRIMARY KEY,"
                    + "business_uuid VARCHAR(36) NOT NULL,"
                    + "name VARCHAR(64) NOT NULL,"
                    + "territory_claim_uuid VARCHAR(36) NULL,"
                    + "open_minute INTEGER NOT NULL DEFAULT 540,"
                    + "close_minute INTEGER NOT NULL DEFAULT 1020,"
                    + "state_override VARCHAR(12) NOT NULL DEFAULT 'AUTO',"
                    + "override_until_day BIGINT NULL,"
                    + "override_until_minute INTEGER NULL,"
                    + "created_at BIGINT NOT NULL)");
            ensureColumn(connection, "gt_workplaces", "state_override",
                    "ALTER TABLE gt_workplaces ADD COLUMN state_override VARCHAR(12) NOT NULL DEFAULT 'AUTO'");
            ensureColumn(connection, "gt_workplaces", "override_until_day",
                    "ALTER TABLE gt_workplaces ADD COLUMN override_until_day BIGINT NULL");
            ensureColumn(connection, "gt_workplaces", "override_until_minute",
                    "ALTER TABLE gt_workplaces ADD COLUMN override_until_minute INTEGER NULL");
            ensureColumn(connection, "gt_workplaces", "territory_claim_uuid",
                    "ALTER TABLE gt_workplaces ADD COLUMN territory_claim_uuid VARCHAR(36) NULL");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gt_workplace_territory "
                    + "ON gt_workplaces (territory_claim_uuid)");

            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_gt_workplace_name "
                    + "ON gt_workplaces (business_uuid, name)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_workplace_schedules ("
                    + "workplace_uuid VARCHAR(36) NOT NULL,"
                    + "weekday VARCHAR(12) NOT NULL,"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "open_minute INTEGER NOT NULL,"
                    + "close_minute INTEGER NOT NULL,"
                    + "PRIMARY KEY (workplace_uuid, weekday))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_positions ("
                    + "position_uuid VARCHAR(36) PRIMARY KEY,"
                    + "workplace_uuid VARCHAR(36) NOT NULL,"
                    + "title VARCHAR(64) NOT NULL,"
                    + "wage BIGINT NOT NULL DEFAULT 0,"
                    + "audience VARCHAR(16) NOT NULL DEFAULT 'ANY',"
                    + "shift_start_minute INTEGER NOT NULL DEFAULT 540,"
                    + "shift_end_minute INTEGER NOT NULL DEFAULT 1020,"
                    + "permissions VARCHAR(128) NOT NULL DEFAULT 'ENTER',"
                    + "work_world_uuid VARCHAR(36) NULL,"
                    + "work_x INTEGER NULL,"
                    + "work_y INTEGER NULL,"
                    + "work_z INTEGER NULL,"
                    + "employee_uuid VARCHAR(36) NULL,"
                    + "employee_name VARCHAR(32) NULL,"
                    + "hired_at BIGINT NULL,"
                    + "created_at BIGINT NOT NULL)");
            ensureColumn(connection, "gt_positions", "audience",
                    "ALTER TABLE gt_positions ADD COLUMN audience VARCHAR(16) NOT NULL DEFAULT 'ANY'");
            ensureColumn(connection, "gt_positions", "shift_start_minute",
                    "ALTER TABLE gt_positions ADD COLUMN shift_start_minute INTEGER NOT NULL DEFAULT 540");
            ensureColumn(connection, "gt_positions", "shift_end_minute",
                    "ALTER TABLE gt_positions ADD COLUMN shift_end_minute INTEGER NOT NULL DEFAULT 1020");
            ensureColumn(connection, "gt_positions", "permissions",
                    "ALTER TABLE gt_positions ADD COLUMN permissions VARCHAR(128) NOT NULL DEFAULT 'ENTER'");
            ensureColumn(connection, "gt_positions", "work_world_uuid",
                    "ALTER TABLE gt_positions ADD COLUMN work_world_uuid VARCHAR(36) NULL");
            ensureColumn(connection, "gt_positions", "work_x",
                    "ALTER TABLE gt_positions ADD COLUMN work_x INTEGER NULL");
            ensureColumn(connection, "gt_positions", "work_y",
                    "ALTER TABLE gt_positions ADD COLUMN work_y INTEGER NULL");
            ensureColumn(connection, "gt_positions", "work_z",
                    "ALTER TABLE gt_positions ADD COLUMN work_z INTEGER NULL");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gt_position_workplace "
                    + "ON gt_positions (workplace_uuid, employee_uuid)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_workplace_doors ("
                    + "world_uuid VARCHAR(36) NOT NULL,"
                    + "x INTEGER NOT NULL,"
                    + "y INTEGER NOT NULL,"
                    + "z INTEGER NOT NULL,"
                    + "workplace_uuid VARCHAR(36) NOT NULL,"
                    + "PRIMARY KEY (world_uuid, x, y, z))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_workplace_signs ("
                    + "world_uuid VARCHAR(36) NOT NULL,"
                    + "x INTEGER NOT NULL,"
                    + "y INTEGER NOT NULL,"
                    + "z INTEGER NOT NULL,"
                    + "workplace_uuid VARCHAR(36) NOT NULL,"
                    + "PRIMARY KEY (world_uuid, x, y, z))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_shop_workplaces ("
                    + "shop_uuid VARCHAR(36) PRIMARY KEY,"
                    + "workplace_uuid VARCHAR(36) NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gt_shop_workplace "
                    + "ON gt_shop_workplaces (workplace_uuid)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_payroll_runs ("
                    + "position_uuid VARCHAR(36) NOT NULL,"
                    + "garden_day BIGINT NOT NULL,"
                    + "business_uuid VARCHAR(36) NOT NULL,"
                    + "employee_uuid VARCHAR(36) NOT NULL,"
                    + "amount BIGINT NOT NULL,"
                    + "paid_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (position_uuid, garden_day))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gt_payroll_business "
                    + "ON gt_payroll_runs (business_uuid, garden_day)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gt_container_shop_signs ("
                    + "shop_uuid VARCHAR(36) PRIMARY KEY,"
                    + "world_uuid VARCHAR(36) NOT NULL,"
                    + "x INTEGER NOT NULL,"
                    + "y INTEGER NOT NULL,"
                    + "z INTEGER NOT NULL)");
        }
    }

    private static void ensureColumn(Connection connection, String table, String column, String ddl) throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            if (result.next()) {
                return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }
}
