package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.calendar.GardenCalendar;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.LandAccessService;
import com.herasgarden.gardentrade.api.BusinessDirectory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class BusinessService implements BusinessDirectory {
    private final GardenPlatform platform;
    private final GardenCalendar calendar;
    private final GardenTerritoryDirectory territories;
    private final LandAccessService land;

    public BusinessService(
            GardenPlatform platform,
            GardenCalendar calendar,
            GardenTerritoryDirectory territories,
            LandAccessService land
    ) {
        this.platform = platform;
        this.calendar = calendar;
        this.territories = territories;
        this.land = land;
    }

    public Business createBusiness(Player owner, String name) throws SQLException {
        String clean = cleanName(name, 64, "Business name");
        if (businessByName(clean).isPresent()) throw new IllegalArgumentException("A business with that name already exists.");
        Business business = new Business(UUID.randomUUID(), clean, owner.getUniqueId(), owner.getName());
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("INSERT INTO gt_businesses (business_uuid, name, owner_uuid, owner_name, created_at) VALUES (?, ?, ?, ?, ?)")) {
            s.setString(1, business.id().toString()); s.setString(2, business.name());
            s.setString(3, business.ownerId().toString()); s.setString(4, business.ownerName());
            s.setLong(5, System.currentTimeMillis()); s.executeUpdate();
        }
        return business;
    }

    public Workplace createWorkplace(Player actor, String businessName, String name) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        String clean = cleanName(name, 64, "Workplace name");
        if (workplaceByName(business.id(), clean).isPresent()) throw new IllegalArgumentException("That business already has a workplace with that name.");
        Workplace workplace = new Workplace(UUID.randomUUID(), business.id(), clean, null, 540, 1020);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("INSERT INTO gt_workplaces (workplace_uuid, business_uuid, name, open_minute, close_minute, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            s.setString(1, workplace.id().toString()); s.setString(2, business.id().toString());
            s.setString(3, workplace.name()); s.setInt(4, workplace.openMinute()); s.setInt(5, workplace.closeMinute());
            s.setLong(6, System.currentTimeMillis()); s.executeUpdate();
        }
        return workplace;
    }

    public Workplace setSchedule(Player actor, String businessName, String workplaceName, String open, String close) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        int openMinute = parseMinute(open), closeMinute = parseMinute(close);
        try (Connection c = platform.storage().connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE gt_workplaces SET open_minute = ?, close_minute = ? WHERE workplace_uuid = ?");
                 PreparedStatement d = c.prepareStatement(
                    "DELETE FROM gt_workplace_schedules WHERE workplace_uuid = ?");
                 PreparedStatement i = c.prepareStatement(
                    "INSERT INTO gt_workplace_schedules (workplace_uuid, weekday, enabled, open_minute, close_minute) VALUES (?, ?, 1, ?, ?)")) {
                s.setInt(1, openMinute); s.setInt(2, closeMinute); s.setString(3, workplace.id().toString()); s.executeUpdate();
                d.setString(1, workplace.id().toString()); d.executeUpdate();
                for (GardenCalendar.Weekday day : GardenCalendar.Weekday.values()) {
                    i.setString(1, workplace.id().toString());
                    i.setString(2, day.name());
                    i.setInt(3, openMinute);
                    i.setInt(4, closeMinute);
                    i.addBatch();
                }
                i.executeBatch();
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
        refreshSigns(workplace.id());
        return new Workplace(workplace.id(), workplace.businessId(), workplace.name(), workplace.territoryClaimId(), openMinute, closeMinute);
    }

    public void setWeeklySchedule(Player actor, String businessName, String workplaceName, String weekday,
                                  String open, String close) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        GardenCalendar.Weekday day = parseWeekday(weekday);
        int openMinute = parseMinute(open), closeMinute = parseMinute(close);
        try (Connection c = platform.storage().connection()) {
            int changed;
            try (PreparedStatement u = c.prepareStatement(
                    "UPDATE gt_workplace_schedules SET enabled=1, open_minute=?, close_minute=? WHERE workplace_uuid=? AND weekday=?")) {
                u.setInt(1, openMinute); u.setInt(2, closeMinute);
                u.setString(3, workplace.id().toString()); u.setString(4, day.name());
                changed = u.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement i = c.prepareStatement(
                        "INSERT INTO gt_workplace_schedules (workplace_uuid, weekday, enabled, open_minute, close_minute) VALUES (?, ?, 1, ?, ?)")) {
                    i.setString(1, workplace.id().toString()); i.setString(2, day.name());
                    i.setInt(3, openMinute); i.setInt(4, closeMinute); i.executeUpdate();
                }
            }
        }
        refreshSigns(workplace.id());
    }

    public void setWorkplaceOverride(Player actor, String businessName, String workplaceName, String state,
                                     int durationMinutes) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        OverrideWindow override = overrideWindow(state, durationMinutes);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE gt_workplaces SET state_override=?, override_until_day=?, override_until_minute=? WHERE workplace_uuid=?")) {
            s.setString(1, override.state());
            if (override.untilDay() == null) s.setObject(2, null); else s.setLong(2, override.untilDay());
            if (override.untilMinute() == null) s.setObject(3, null); else s.setInt(3, override.untilMinute());
            s.setString(4, workplace.id().toString());
            s.executeUpdate();
        }
        refreshSigns(workplace.id());
    }

    public void setBusinessState(Player actor, String businessName, String state) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        String normalized = normalizeState(state);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE gt_businesses SET state_override=?, override_until_day=NULL, override_until_minute=NULL WHERE business_uuid=?")) {
            s.setString(1, normalized);
            s.setString(2, business.id().toString());
            s.executeUpdate();
        }
        for (Workplace workplace : workplaces(business.id())) refreshSigns(workplace.id());
    }

    public long fundBusiness(Player actor, String businessName, long amount) throws SQLException {
        if (amount <= 0) throw new IllegalArgumentException("Funding amount must be positive.");
        Business business = requireOwnedBusiness(actor, businessName);
        if (!platform.currency().withdraw(actor.getUniqueId(), amount)) {
            throw new IllegalArgumentException("You do not have enough Obols.");
        }
        if (!platform.currency().deposit(business.id(), amount)) {
            platform.currency().deposit(actor.getUniqueId(), amount);
            throw new IllegalArgumentException("Business funding failed; your Obols were returned.");
        }
        return platform.currency().balance(business.id());
    }

    public long businessBalance(Player actor, String businessName) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        return platform.currency().balance(business.id());
    }

    public Position setPositionShift(Player actor, String businessName, UUID positionId, String start, String end) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Position position = requireBusinessPosition(business.id(), positionId);
        int startMinute = parseMinute(start), endMinute = parseMinute(end);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE gt_positions SET shift_start_minute=?, shift_end_minute=? WHERE position_uuid=?")) {
            s.setInt(1, startMinute); s.setInt(2, endMinute); s.setString(3, position.id().toString()); s.executeUpdate();
        }
        return position(position.id()).orElseThrow();
    }

    public Position setPositionAnchor(Player actor, String businessName, UUID positionId) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Position position = requireBusinessPosition(business.id(), positionId);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE gt_positions SET work_world_uuid=?, work_x=?, work_y=?, work_z=? WHERE position_uuid=?")) {
            s.setString(1, actor.getWorld().getUID().toString());
            s.setInt(2, actor.getLocation().getBlockX()); s.setInt(3, actor.getLocation().getBlockY()); s.setInt(4, actor.getLocation().getBlockZ());
            s.setString(5, position.id().toString()); s.executeUpdate();
        }
        return position(position.id()).orElseThrow();
    }

    public Position setPositionPermissions(Player actor, String businessName, UUID positionId, String permissions) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Position position = requireBusinessPosition(business.id(), positionId);
        String normalized = normalizePermissions(permissions);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("UPDATE gt_positions SET permissions=? WHERE position_uuid=?")) {
            s.setString(1, normalized); s.setString(2, position.id().toString()); s.executeUpdate();
        }
        return position(position.id()).orElseThrow();
    }

    public Workplace setTerritory(Player actor, String businessName, String workplaceName, String territoryName)
            throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        UUID territoryClaimId = territories.findByName(territoryName)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."))
                .claimId();
        if (!territories.canManage(actor, territoryClaimId) && !actor.hasPermission("gardentrade.business.admin")) {
            throw new IllegalArgumentException("You must manage that territory to bind a workplace to it.");
        }
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("UPDATE gt_workplaces SET territory_claim_uuid = ? WHERE workplace_uuid = ?")) {
            s.setString(1, territoryClaimId.toString());
            s.setString(2, workplace.id().toString());
            s.executeUpdate();
        }
        return new Workplace(workplace.id(), workplace.businessId(), workplace.name(), territoryClaimId,
                workplace.openMinute(), workplace.closeMinute());
    }

    public Position createPosition(Player actor, String businessName, String workplaceName, String title, long wage) throws SQLException {
        return createPositions(actor, businessName, workplaceName, title, wage, 1, "ANY").getFirst();
    }

    public List<Position> createPositions(Player actor, String businessName, String workplaceName, String title,
                                          long wage, int slots, String audience) throws SQLException {
        if (wage < 0) throw new IllegalArgumentException("Wage cannot be negative.");
        if (slots < 1 || slots > 25) throw new IllegalArgumentException("Position slots must be between 1 and 25.");
        String normalizedAudience = normalizeAudience(audience);
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        String cleanTitle = cleanName(title, 64, "Position title");
        List<Position> created = new ArrayList<>();
        try (Connection c = platform.storage().connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO gt_positions (position_uuid, workplace_uuid, title, wage, audience, shift_start_minute, shift_end_minute, permissions, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'ENTER', ?)")) {
                for (int i = 0; i < slots; i++) {
                    Position position = new Position(UUID.randomUUID(), workplace.id(), cleanTitle, wage,
                            normalizedAudience, workplace.openMinute(), workplace.closeMinute(), "ENTER",
                            null, null, null, null, null, null);
                    s.setString(1, position.id().toString());
                    s.setString(2, workplace.id().toString());
                    s.setString(3, position.title());
                    s.setLong(4, position.wage());
                    s.setString(5, normalizedAudience);
                    s.setInt(6, position.shiftStartMinute());
                    s.setInt(7, position.shiftEndMinute());
                    s.setLong(8, System.currentTimeMillis());
                    s.addBatch();
                    created.add(position);
                }
                s.executeBatch();
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
        return List.copyOf(created);
    }

    public Position hire(Player actor, String businessName, UUID positionId, Player target) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Position position = requireBusinessPosition(business.id(), positionId);
        if (position.employeeId() != null) throw new IllegalArgumentException("That position is already filled.");
        if (!hire(position.id(), target.getUniqueId(), target.getName())) throw new IllegalArgumentException("That position changed before the hire could be saved.");
        return new Position(position.id(), position.workplaceId(), position.title(), position.wage(), position.audience(), position.shiftStartMinute(), position.shiftEndMinute(), position.permissions(), position.workWorldId(), position.workX(), position.workY(), position.workZ(), target.getUniqueId(), target.getName());
    }

    @Override
    public boolean hire(UUID positionId, UUID employeeId, String employeeName) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("UPDATE gt_positions SET employee_uuid = ?, employee_name = ?, hired_at = ? WHERE position_uuid = ? AND employee_uuid IS NULL")) {
            s.setString(1, employeeId.toString());
            s.setString(2, employeeName == null ? employeeId.toString().substring(0, 8) : employeeName);
            s.setLong(3, System.currentTimeMillis()); s.setString(4, positionId.toString());
            return s.executeUpdate() == 1;
        }
    }

    @Override
    public boolean vacate(UUID positionId, UUID employeeId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE gt_positions SET employee_uuid = NULL, employee_name = NULL, hired_at = NULL "
                             + "WHERE position_uuid = ? AND employee_uuid = ?")) {
            s.setString(1, positionId.toString());
            s.setString(2, employeeId.toString());
            return s.executeUpdate() == 1;
        }
    }

    public Position fire(Player actor, String businessName, UUID positionId) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Position position = requireBusinessPosition(business.id(), positionId);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("UPDATE gt_positions SET employee_uuid = NULL, employee_name = NULL, hired_at = NULL WHERE position_uuid = ?")) {
            s.setString(1, position.id().toString()); s.executeUpdate();
        }
        return new Position(position.id(), position.workplaceId(), position.title(), position.wage(), position.audience(), position.shiftStartMinute(), position.shiftEndMinute(), position.permissions(), position.workWorldId(), position.workX(), position.workY(), position.workZ(), null, null);
    }

    public void bindDoor(Player actor, String businessName, String workplaceName, Block block) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        if (!isDoor(block.getType())) throw new IllegalArgumentException("Look directly at a door or trapdoor.");
        validateManagedBlock(actor, block);
        bindBlocks("gt_workplace_doors", workplace.id(), doorBlocks(block));
    }

    public void bindSign(Player actor, String businessName, String workplaceName, Block block) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        if (!(block.getState() instanceof Sign)) throw new IllegalArgumentException("Look directly at a sign.");
        validateManagedBlock(actor, block);
        bindBlocks("gt_workplace_signs", workplace.id(), List.of(block));
        refreshSigns(workplace.id());
    }

    public void bindShop(
            Player actor,
            String businessName,
            String workplaceName,
            UUID shopId
    ) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT owner_uuid FROM gt_shops WHERE shop_uuid = ? LIMIT 1")) {
            s.setString(1, shopId.toString());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("That Garden shop does not exist.");
                UUID owner = UUID.fromString(r.getString("owner_uuid"));
                if (!owner.equals(actor.getUniqueId()) && !actor.hasPermission("gardentrade.business.admin")) {
                    throw new IllegalArgumentException("You can only attach a shop you own to your workplace.");
                }
            }
        }
        try (Connection c = platform.storage().connection()) {
            String existing = null;
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT workplace_uuid FROM gt_shop_workplaces WHERE shop_uuid = ?")) {
                q.setString(1, shopId.toString());
                try (ResultSet r = q.executeQuery()) {
                    if (r.next()) existing = r.getString("workplace_uuid");
                }
            }
            if (existing != null && !existing.equals(workplace.id().toString())) {
                throw new IllegalArgumentException(
                        "That shop is already attached to another workplace. Unbind it there first.");
            }
            if (existing == null) {
                try (PreparedStatement i = c.prepareStatement(
                        "INSERT INTO gt_shop_workplaces (shop_uuid, workplace_uuid) VALUES (?, ?)")) {
                    i.setString(1, shopId.toString());
                    i.setString(2, workplace.id().toString());
                    i.executeUpdate();
                }
            }
        }
    }

    public Optional<String> transactionBlockReason(UUID shopId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT w.* FROM gt_shop_workplaces x "
                             + "JOIN gt_workplaces w ON w.workplace_uuid = x.workplace_uuid "
                             + "WHERE x.shop_uuid = ? LIMIT 1")) {
            s.setString(1, shopId.toString());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                Workplace workplace = readWorkplace(r);
                return isOpen(workplace)
                        ? Optional.empty()
                        : Optional.of("This workplace is currently closed.");
            }
        }
    }

    public boolean handleDoor(Player player, Block block) throws SQLException {
        Workplace workplace = workplaceForDoor(block).orElse(null);
        return workplace != null && !isOpen(workplace) && !isStaff(player.getUniqueId(), workplace.businessId());
    }

    public boolean blockRedstone(Block block) throws SQLException {
        Workplace workplace = workplaceForDoor(block).orElse(null);
        if (workplace == null || isOpen(workplace)) return false;
        closeDoor(block);
        return true;
    }

    public void closeClosedDoors() {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT d.world_uuid,d.x,d.y,d.z,w.* FROM gt_workplace_doors d "
                             + "JOIN gt_workplaces w ON w.workplace_uuid=d.workplace_uuid");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                Workplace workplace = readWorkplace(r);
                if (isOpen(workplace)) continue;
                var world = Bukkit.getWorld(UUID.fromString(r.getString("world_uuid")));
                if (world == null) continue;
                closeDoor(world.getBlockAt(r.getInt("x"), r.getInt("y"), r.getInt("z")));
            }
        } catch (SQLException ignored) {
        }
    }

    public boolean isOpen(Workplace workplace) {
        GardenCalendar.CalendarSnapshot now = calendar.snapshot();
        try {
            OverrideState businessState = businessOverride(workplace.businessId(), now);
            if (businessState == OverrideState.OPEN) return true;
            if (businessState == OverrideState.CLOSED) return false;

            OverrideState workplaceState = workplaceOverride(workplace.id(), now);
            if (workplaceState == OverrideState.OPEN) return true;
            if (workplaceState == OverrideState.CLOSED) return false;

            Schedule schedule = schedule(workplace.id(), now.weekday()).orElse(
                    new Schedule(true, workplace.openMinute(), workplace.closeMinute()));
            if (!schedule.enabled()) return false;
            int minute = now.minuteOfDay();
            int open = schedule.openMinute(), close = schedule.closeMinute();
            if (open == close) return true;
            return open < close ? minute >= open && minute < close : minute >= open || minute < close;
        } catch (SQLException exception) {
            return false;
        }
    }

    public void refreshAllSigns() {
        try {
            for (Business b : businesses()) for (Workplace w : workplaces(b.id())) refreshSigns(w.id());
        } catch (SQLException ignored) {}
    }

    public List<Business> businesses() throws SQLException {
        List<Business> out = new ArrayList<>();
        try (Connection c = platform.storage().connection(); PreparedStatement s = c.prepareStatement("SELECT * FROM gt_businesses ORDER BY name"); ResultSet r = s.executeQuery()) {
            while (r.next()) out.add(readBusiness(r));
        }
        return List.copyOf(out);
    }

    public List<Workplace> workplaces(UUID businessId) throws SQLException {
        List<Workplace> out = new ArrayList<>();
        try (Connection c = platform.storage().connection(); PreparedStatement s = c.prepareStatement("SELECT * FROM gt_workplaces WHERE business_uuid = ? ORDER BY name")) {
            s.setString(1, businessId.toString());
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(readWorkplace(r)); }
        }
        return List.copyOf(out);
    }

    public List<Position> positions(UUID workplaceId) throws SQLException {
        List<Position> out = new ArrayList<>();
        try (Connection c = platform.storage().connection(); PreparedStatement s = c.prepareStatement("SELECT * FROM gt_positions WHERE workplace_uuid = ? ORDER BY title")) {
            s.setString(1, workplaceId.toString());
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(readPosition(r)); }
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<BusinessDirectory.Assignment> assignment(UUID employeeId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT b.business_uuid,b.name business_name,w.workplace_uuid,w.name workplace_name,"
                             + "w.territory_claim_uuid,p.position_uuid,p.title,p.wage,p.audience,"
                             + "p.shift_start_minute,p.shift_end_minute,p.work_world_uuid,p.work_x,p.work_y,p.work_z "
                             + "FROM gt_positions p JOIN gt_workplaces w ON w.workplace_uuid=p.workplace_uuid "
                             + "JOIN gt_businesses b ON b.business_uuid=w.business_uuid WHERE p.employee_uuid=? LIMIT 1")) {
            s.setString(1, employeeId.toString());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                String territory=r.getString("territory_claim_uuid"), workWorld=r.getString("work_world_uuid");
                return Optional.of(new BusinessDirectory.Assignment(
                        UUID.fromString(r.getString("business_uuid")),r.getString("business_name"),
                        UUID.fromString(r.getString("workplace_uuid")),r.getString("workplace_name"),
                        territory==null?null:UUID.fromString(territory),
                        UUID.fromString(r.getString("position_uuid")),r.getString("title"),r.getLong("wage"),
                        r.getString("audience"),r.getInt("shift_start_minute"),r.getInt("shift_end_minute"),
                        workWorld==null?null:UUID.fromString(workWorld),
                        nullableInt(r,"work_x"),nullableInt(r,"work_y"),nullableInt(r,"work_z")));
            }
        }
    }

    @Override
    public boolean workplaceOpen(UUID workplaceId) throws SQLException {
        Workplace workplace = workplace(workplaceId).orElse(null);
        return workplace != null && isOpen(workplace);
    }

    @Override
    public List<Vacancy> vacancies() throws SQLException {
        List<Vacancy> out = new ArrayList<>();
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT b.business_uuid, b.name business_name, w.workplace_uuid, w.name workplace_name, "
                             + "w.territory_claim_uuid, p.position_uuid, p.title, p.wage, p.audience, "
                             + "p.shift_start_minute, p.shift_end_minute, p.work_world_uuid, p.work_x, p.work_y, p.work_z "
                             + "FROM gt_positions p JOIN gt_workplaces w ON w.workplace_uuid=p.workplace_uuid "
                             + "JOIN gt_businesses b ON b.business_uuid=w.business_uuid "
                             + "WHERE p.employee_uuid IS NULL ORDER BY b.name,w.name,p.title");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                String territory = r.getString("territory_claim_uuid");
                String workWorld = r.getString("work_world_uuid");
                out.add(new Vacancy(
                        UUID.fromString(r.getString("business_uuid")), r.getString("business_name"),
                        UUID.fromString(r.getString("workplace_uuid")), r.getString("workplace_name"),
                        territory == null ? null : UUID.fromString(territory),
                        UUID.fromString(r.getString("position_uuid")), r.getString("title"), r.getLong("wage"),
                        r.getString("audience"), r.getInt("shift_start_minute"), r.getInt("shift_end_minute"),
                        workWorld == null ? null : UUID.fromString(workWorld),
                        nullableInt(r, "work_x"), nullableInt(r, "work_y"), nullableInt(r, "work_z")));
            }
        }
        return List.copyOf(out);
    }

    public Optional<Business> businessByName(String name) throws SQLException {
        try (Connection c=platform.storage().connection(); PreparedStatement s=c.prepareStatement("SELECT * FROM gt_businesses WHERE LOWER(name)=LOWER(?)")) {
            s.setString(1,name); try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(readBusiness(r)):Optional.empty();}
        }
    }

    private Optional<Business> business(UUID id) throws SQLException {
        try(Connection c=platform.storage().connection();PreparedStatement s=c.prepareStatement("SELECT * FROM gt_businesses WHERE business_uuid=?")){
            s.setString(1,id.toString());try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(readBusiness(r)):Optional.empty();}
        }
    }
    private Optional<Workplace> workplace(UUID id) throws SQLException {
        try(Connection c=platform.storage().connection();PreparedStatement s=c.prepareStatement("SELECT * FROM gt_workplaces WHERE workplace_uuid=?")){
            s.setString(1,id.toString());try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(readWorkplace(r)):Optional.empty();}
        }
    }
    private Optional<Workplace> workplaceByName(UUID businessId,String name) throws SQLException {
        try(Connection c=platform.storage().connection();PreparedStatement s=c.prepareStatement("SELECT * FROM gt_workplaces WHERE business_uuid=? AND LOWER(name)=LOWER(?)")){
            s.setString(1,businessId.toString());s.setString(2,name);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(readWorkplace(r)):Optional.empty();}
        }
    }
    private Optional<Position> position(UUID id) throws SQLException {
        try(Connection c=platform.storage().connection();PreparedStatement s=c.prepareStatement("SELECT * FROM gt_positions WHERE position_uuid=?")){
            s.setString(1,id.toString());try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(readPosition(r)):Optional.empty();}
        }
    }

    private Business requireOwnedBusiness(Player actor,String name) throws SQLException {
        Business b=businessByName(name).orElseThrow(()->new IllegalArgumentException("That business does not exist."));
        if(!b.ownerId().equals(actor.getUniqueId())&&!actor.hasPermission("gardentrade.business.admin")) throw new IllegalArgumentException("You do not own that business.");
        return b;
    }
    private Workplace requireWorkplace(UUID businessId,String name) throws SQLException {
        return workplaceByName(businessId,name).orElseThrow(()->new IllegalArgumentException("That workplace does not exist."));
    }
    private Position requireBusinessPosition(UUID businessId,UUID positionId) throws SQLException {
        Position p=position(positionId).orElseThrow(()->new IllegalArgumentException("That position does not exist."));
        Workplace w=workplace(p.workplaceId()).orElseThrow(()->new IllegalArgumentException("That workplace no longer exists."));
        if(!w.businessId().equals(businessId)) throw new IllegalArgumentException("That position belongs to another business.");
        return p;
    }
    private boolean isStaff(UUID playerId,UUID businessId) throws SQLException {
        Business b=business(businessId).orElse(null); if(b!=null&&b.ownerId().equals(playerId)) return true;
        try(Connection c=platform.storage().connection();PreparedStatement s=c.prepareStatement(
                "SELECT p.permissions FROM gt_positions p JOIN gt_workplaces w ON w.workplace_uuid=p.workplace_uuid "
                        + "WHERE w.business_uuid=? AND p.employee_uuid=?")){
            s.setString(1,businessId.toString());s.setString(2,playerId.toString());
            try(ResultSet r=s.executeQuery()){
                while(r.next()) if(hasPermissionToken(r.getString("permissions"),"ENTER")) return true;
                return false;
            }
        }
    }
    private Optional<Workplace> workplaceForBlock(String table,Block block) throws SQLException {
        String sql="SELECT w.* FROM "+table+" x JOIN gt_workplaces w ON w.workplace_uuid=x.workplace_uuid WHERE x.world_uuid=? AND x.x=? AND x.y=? AND x.z=?";
        try(Connection c=platform.storage().connection();PreparedStatement s=c.prepareStatement(sql)){
            s.setString(1,block.getWorld().getUID().toString());s.setInt(2,block.getX());s.setInt(3,block.getY());s.setInt(4,block.getZ());
            try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(readWorkplace(r)):Optional.empty();}
        }
    }
    private void bindBlocks(String table, UUID workplaceId, List<Block> blocks) throws SQLException {
        try (Connection c = platform.storage().connection()) {
            c.setAutoCommit(false);
            try {
                for (Block block : blocks) {
                    try (PreparedStatement q = c.prepareStatement(
                            "SELECT workplace_uuid FROM " + table + " WHERE world_uuid=? AND x=? AND y=? AND z=?")) {
                        q.setString(1, block.getWorld().getUID().toString());
                        q.setInt(2, block.getX());
                        q.setInt(3, block.getY());
                        q.setInt(4, block.getZ());
                        try (ResultSet r = q.executeQuery()) {
                            if (r.next() && !workplaceId.toString().equals(r.getString("workplace_uuid"))) {
                                throw new IllegalArgumentException(
                                        "That block is already linked to another workplace.");
                            }
                        }
                    }
                }
                for (Block block : blocks) {
                    try (PreparedStatement d = c.prepareStatement(
                            "DELETE FROM " + table + " WHERE world_uuid=? AND x=? AND y=? AND z=?")) {
                        d.setString(1, block.getWorld().getUID().toString());
                        d.setInt(2, block.getX());
                        d.setInt(3, block.getY());
                        d.setInt(4, block.getZ());
                        d.executeUpdate();
                    }
                    try (PreparedStatement s = c.prepareStatement(
                            "INSERT INTO " + table + " (world_uuid,x,y,z,workplace_uuid) VALUES (?,?,?,?,?)")) {
                        s.setString(1, block.getWorld().getUID().toString());
                        s.setInt(2, block.getX());
                        s.setInt(3, block.getY());
                        s.setInt(4, block.getZ());
                        s.setString(5, workplaceId.toString());
                        s.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private void validateManagedBlock(Player actor, Block block) {
        if (actor.hasPermission("gardentrade.business.admin")) return;
        if (land == null || land.claimIdAt(block).isEmpty() || !land.canManage(actor, block)) {
            throw new IllegalArgumentException(
                    "You can only bind workplace doors and signs inside Garden land you manage.");
        }
    }

    private List<Block> doorBlocks(Block block) {
        if (!(block.getBlockData() instanceof Door door)) {
            return List.of(block);
        }
        Block other = door.getHalf() == Bisected.Half.TOP
                ? block.getRelative(0, -1, 0)
                : block.getRelative(0, 1, 0);
        return isDoor(other.getType()) ? List.of(block, other) : List.of(block);
    }

    private Optional<Workplace> workplaceForDoor(Block block) throws SQLException {
        Optional<Workplace> direct = workplaceForBlock("gt_workplace_doors", block);
        if (direct.isPresent() || !(block.getBlockData() instanceof Door door)) return direct;
        Block other = door.getHalf() == Bisected.Half.TOP
                ? block.getRelative(0, -1, 0)
                : block.getRelative(0, 1, 0);
        return workplaceForBlock("gt_workplace_doors", other);
    }

    private void closeDoor(Block block) {
        for (Block part : doorBlocks(block)) {
            if (part.getBlockData() instanceof Openable openable && openable.isOpen()) {
                openable.setOpen(false);
                part.setBlockData(openable, false);
            }
        }
    }
    private void refreshSigns(UUID workplaceId) throws SQLException {
        Workplace w=workplace(workplaceId).orElse(null);if(w==null)return;Business b=business(w.businessId()).orElse(null);if(b==null)return;
        try(Connection c=platform.storage().connection();PreparedStatement s=c.prepareStatement("SELECT world_uuid,x,y,z FROM gt_workplace_signs WHERE workplace_uuid=?")){
            s.setString(1,workplaceId.toString());try(ResultSet r=s.executeQuery()){
                while(r.next()){
                    var world=Bukkit.getWorld(UUID.fromString(r.getString("world_uuid")));if(world==null)continue;
                    Block block=world.getBlockAt(r.getInt("x"),r.getInt("y"),r.getInt("z"));if(!(block.getState() instanceof Sign sign))continue;
                    sign.setLine(0,shortLine(b.name()));sign.setLine(1,shortLine(w.name()));sign.setLine(2,isOpen(w)?"OPEN":"CLOSED");
                    sign.setLine(3,formatMinute(w.openMinute())+"-"+formatMinute(w.closeMinute()));sign.update(true,false);
                }
            }
        }
    }
    public PayrollResult runPayroll(Player actor, String businessName) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        return runPayrollForBusiness(business, calendar.snapshot().day());
    }

    public void runScheduledPayroll() {
        long day = calendar.snapshot().day();
        try {
            for (Business business : businesses()) runPayrollForBusiness(business, day);
        } catch (SQLException ignored) {
        }
    }

    private PayrollResult runPayrollForBusiness(Business business, long day) throws SQLException {
        int paid = 0, skipped = 0;
        long total = 0L;
        try (Connection c = platform.storage().connection();
             PreparedStatement q = c.prepareStatement(
                     "SELECT p.* FROM gt_positions p JOIN gt_workplaces w ON w.workplace_uuid=p.workplace_uuid "
                             + "WHERE w.business_uuid=? AND p.employee_uuid IS NOT NULL AND p.wage>0")) {
            q.setString(1, business.id().toString());
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    Position p = readPosition(r);
                    if (payrollAlreadyRun(p.id(), day)) continue;
                    if (!platform.currency().withdraw(business.id(), p.wage())) { skipped++; continue; }
                    if (!platform.currency().deposit(p.employeeId(), p.wage())) {
                        platform.currency().deposit(business.id(), p.wage());
                        skipped++;
                        continue;
                    }
                    try (Connection c2 = platform.storage().connection();
                         PreparedStatement i = c2.prepareStatement(
                                 "INSERT INTO gt_payroll_runs (position_uuid,garden_day,business_uuid,employee_uuid,amount,paid_at) VALUES (?,?,?,?,?,?)")) {
                        i.setString(1,p.id().toString()); i.setLong(2,day); i.setString(3,business.id().toString());
                        i.setString(4,p.employeeId().toString()); i.setLong(5,p.wage()); i.setLong(6,System.currentTimeMillis());
                        i.executeUpdate();
                    } catch (SQLException e) {
                        platform.currency().withdraw(p.employeeId(), p.wage());
                        platform.currency().deposit(business.id(), p.wage());
                        throw e;
                    }
                    paid++; total += p.wage();
                }
            }
        }
        return new PayrollResult(paid, skipped, total);
    }

    private boolean payrollAlreadyRun(UUID positionId, long day) throws SQLException {
        try (Connection c=platform.storage().connection();
             PreparedStatement s=c.prepareStatement("SELECT 1 FROM gt_payroll_runs WHERE position_uuid=? AND garden_day=?")) {
            s.setString(1,positionId.toString()); s.setLong(2,day);
            try(ResultSet r=s.executeQuery()){return r.next();}
        }
    }

    private Optional<Schedule> schedule(UUID workplaceId, GardenCalendar.Weekday day) throws SQLException {
        try(Connection c=platform.storage().connection();
            PreparedStatement s=c.prepareStatement(
                    "SELECT enabled,open_minute,close_minute FROM gt_workplace_schedules WHERE workplace_uuid=? AND weekday=?")){
            s.setString(1,workplaceId.toString()); s.setString(2,day.name());
            try(ResultSet r=s.executeQuery()){
                return r.next()?Optional.of(new Schedule(r.getInt("enabled")!=0,r.getInt("open_minute"),r.getInt("close_minute"))):Optional.empty();
            }
        }
    }

    private OverrideState businessOverride(UUID businessId, GardenCalendar.CalendarSnapshot now) throws SQLException {
        try(Connection c=platform.storage().connection();
            PreparedStatement s=c.prepareStatement("SELECT state_override,override_until_day,override_until_minute FROM gt_businesses WHERE business_uuid=?")){
            s.setString(1,businessId.toString());
            try(ResultSet r=s.executeQuery()){return r.next()?activeOverride(r,now):OverrideState.AUTO;}
        }
    }

    private OverrideState workplaceOverride(UUID workplaceId, GardenCalendar.CalendarSnapshot now) throws SQLException {
        try(Connection c=platform.storage().connection();
            PreparedStatement s=c.prepareStatement("SELECT state_override,override_until_day,override_until_minute FROM gt_workplaces WHERE workplace_uuid=?")){
            s.setString(1,workplaceId.toString());
            try(ResultSet r=s.executeQuery()){return r.next()?activeOverride(r,now):OverrideState.AUTO;}
        }
    }

    private OverrideState activeOverride(ResultSet r, GardenCalendar.CalendarSnapshot now) throws SQLException {
        OverrideState state;
        try { state=OverrideState.valueOf(r.getString("state_override")); } catch(Exception e){ return OverrideState.AUTO; }
        if(state==OverrideState.AUTO) return state;
        Object d=r.getObject("override_until_day"), m=r.getObject("override_until_minute");
        if(d==null||m==null) return state;
        long end=((Number)d).longValue()*1440L+((Number)m).intValue();
        long current=now.day()*1440L+now.minuteOfDay();
        return current<=end?state:OverrideState.AUTO;
    }

    private OverrideWindow overrideWindow(String raw, int durationMinutes) {
        String state=normalizeState(raw);
        if(state.equals("AUTO")) return new OverrideWindow(state,null,null);
        if(durationMinutes<1||durationMinutes>10080) throw new IllegalArgumentException("Override duration must be 1-10080 minutes.");
        GardenCalendar.CalendarSnapshot now=calendar.snapshot();
        long total=now.day()*1440L+now.minuteOfDay()+durationMinutes;
        return new OverrideWindow(state,Math.floorDiv(total,1440L),Math.floorMod((int)total,1440));
    }

    private String normalizeState(String raw){
        String v=raw==null?"AUTO":raw.trim().toUpperCase(java.util.Locale.ROOT);
        if(!v.equals("AUTO")&&!v.equals("OPEN")&&!v.equals("CLOSED")) throw new IllegalArgumentException("State must be auto, open, or closed.");
        return v;
    }
    private GardenCalendar.Weekday parseWeekday(String raw){
        try{return GardenCalendar.Weekday.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));}
        catch(Exception e){throw new IllegalArgumentException("Weekday must be Monday through Sunday.");}
    }
    private String normalizeAudience(String raw){
        String v=raw==null?"ANY":raw.trim().toUpperCase(java.util.Locale.ROOT);
        if(!v.equals("ANY")&&!v.equals("PLAYER")&&!v.equals("SOCIETY")) throw new IllegalArgumentException("Audience must be ANY, PLAYER, or SOCIETY.");
        return v;
    }
    private String normalizePermissions(String raw){
        if(raw==null||raw.isBlank()) return "";
        java.util.LinkedHashSet<String> out=new java.util.LinkedHashSet<>();
        for(String token:raw.split(",")){
            String v=token.trim().toUpperCase(java.util.Locale.ROOT);
            if(!v.isBlank()) out.add(v);
        }
        return String.join(",",out);
    }
    private boolean hasPermissionToken(String raw,String token){
        if(raw==null)return false;
        for(String value:raw.split(",")) if(value.trim().equalsIgnoreCase(token)) return true;
        return false;
    }
    private Integer nullableInt(ResultSet r,String column)throws SQLException{int v=r.getInt(column);return r.wasNull()?null:v;}
    private int parseMinute(String raw){try{LocalTime t=LocalTime.parse(raw);return t.getHour()*60+t.getMinute();}catch(Exception e){throw new IllegalArgumentException("Use 24-hour HH:mm times, for example 09:00 or 17:30.");}}
    private String formatMinute(int m){return String.format("%02d:%02d",Math.floorMod(m,1440)/60,Math.floorMod(m,60));}
    private String shortLine(String s){return s.length()>15?s.substring(0,15):s;}
    private String cleanName(String raw,int max,String label){String c=raw==null?"":raw.trim().replaceAll("\\s+"," ");if(c.isBlank())throw new IllegalArgumentException(label+" is required.");return c.length()<=max?c:c.substring(0,max);}
    private boolean isDoor(Material t){String n=t.name();return n.endsWith("_DOOR")||n.endsWith("_TRAPDOOR");}
    private Business readBusiness(ResultSet r)throws SQLException{return new Business(UUID.fromString(r.getString("business_uuid")),r.getString("name"),UUID.fromString(r.getString("owner_uuid")),r.getString("owner_name"));}
    private Workplace readWorkplace(ResultSet r)throws SQLException{String t=r.getString("territory_claim_uuid");return new Workplace(UUID.fromString(r.getString("workplace_uuid")),UUID.fromString(r.getString("business_uuid")),r.getString("name"),t==null?null:UUID.fromString(t),r.getInt("open_minute"),r.getInt("close_minute"));}
    private Position readPosition(ResultSet r)throws SQLException{
        String e=r.getString("employee_uuid"), ww=r.getString("work_world_uuid");
        return new Position(UUID.fromString(r.getString("position_uuid")),UUID.fromString(r.getString("workplace_uuid")),
                r.getString("title"),r.getLong("wage"),r.getString("audience"),
                r.getInt("shift_start_minute"),r.getInt("shift_end_minute"),r.getString("permissions"),
                ww==null?null:UUID.fromString(ww),nullableInt(r,"work_x"),nullableInt(r,"work_y"),nullableInt(r,"work_z"),
                e==null?null:UUID.fromString(e),r.getString("employee_name"));
    }
    public record Business(UUID id,String name,UUID ownerId,String ownerName){}
    public record Workplace(UUID id,UUID businessId,String name,UUID territoryClaimId,int openMinute,int closeMinute){}
    public record Position(UUID id,UUID workplaceId,String title,long wage,String audience,int shiftStartMinute,int shiftEndMinute,
                           String permissions,UUID workWorldId,Integer workX,Integer workY,Integer workZ,UUID employeeId,String employeeName){}
    public record PayrollResult(int paid,int skipped,long total){}
    private record Schedule(boolean enabled,int openMinute,int closeMinute){}
    private record OverrideWindow(String state,Long untilDay,Integer untilMinute){}
    private enum OverrideState{AUTO,OPEN,CLOSED}
}
