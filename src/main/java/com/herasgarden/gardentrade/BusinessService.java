package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.GardenPlatform;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class BusinessService implements BusinessDirectory {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private final GardenPlatform platform;
    private final GardenTerritoryDirectory territories;
    private final LandAccessService land;

    public BusinessService(
            GardenPlatform platform,
            GardenTerritoryDirectory territories,
            LandAccessService land
    ) {
        this.platform = platform;
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
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("UPDATE gt_workplaces SET open_minute = ?, close_minute = ? WHERE workplace_uuid = ?")) {
            s.setInt(1, openMinute); s.setInt(2, closeMinute); s.setString(3, workplace.id().toString()); s.executeUpdate();
        }
        refreshSigns(workplace.id());
        return new Workplace(workplace.id(), workplace.businessId(), workplace.name(), workplace.territoryClaimId(), openMinute, closeMinute);
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
        if (wage < 0) throw new IllegalArgumentException("Wage cannot be negative.");
        Business business = requireOwnedBusiness(actor, businessName);
        Workplace workplace = requireWorkplace(business.id(), workplaceName);
        Position position = new Position(UUID.randomUUID(), workplace.id(), cleanName(title, 64, "Position title"), wage, null, null);
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("INSERT INTO gt_positions (position_uuid, workplace_uuid, title, wage, created_at) VALUES (?, ?, ?, ?, ?)")) {
            s.setString(1, position.id().toString()); s.setString(2, workplace.id().toString());
            s.setString(3, position.title()); s.setLong(4, position.wage()); s.setLong(5, System.currentTimeMillis()); s.executeUpdate();
        }
        return position;
    }

    public Position hire(Player actor, String businessName, UUID positionId, Player target) throws SQLException {
        Business business = requireOwnedBusiness(actor, businessName);
        Position position = requireBusinessPosition(business.id(), positionId);
        if (position.employeeId() != null) throw new IllegalArgumentException("That position is already filled.");
        if (!hire(position.id(), target.getUniqueId(), target.getName())) throw new IllegalArgumentException("That position changed before the hire could be saved.");
        return new Position(position.id(), position.workplaceId(), position.title(), position.wage(), target.getUniqueId(), target.getName());
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
        return new Position(position.id(), position.workplaceId(), position.title(), position.wage(), null, null);
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
        LocalTime t = LocalTime.now(ZONE);
        int now = t.getHour() * 60 + t.getMinute(), open = workplace.openMinute(), close = workplace.closeMinute();
        if (open == close) return true;
        return open < close ? now >= open && now < close : now >= open || now < close;
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
    public List<Vacancy> vacancies() throws SQLException {
        List<Vacancy> out = new ArrayList<>();
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("SELECT b.business_uuid, b.name business_name, w.workplace_uuid, w.name workplace_name, w.territory_claim_uuid, p.position_uuid, p.title, p.wage FROM gt_positions p JOIN gt_workplaces w ON w.workplace_uuid=p.workplace_uuid JOIN gt_businesses b ON b.business_uuid=w.business_uuid WHERE p.employee_uuid IS NULL ORDER BY b.name,w.name,p.title");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                String territory = r.getString("territory_claim_uuid");
                out.add(new Vacancy(UUID.fromString(r.getString("business_uuid")), r.getString("business_name"),
                        UUID.fromString(r.getString("workplace_uuid")), r.getString("workplace_name"),
                        territory == null ? null : UUID.fromString(territory),
                        UUID.fromString(r.getString("position_uuid")), r.getString("title"), r.getLong("wage")));
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
        try(Connection c=platform.storage().connection();PreparedStatement s=c.prepareStatement("SELECT 1 FROM gt_positions p JOIN gt_workplaces w ON w.workplace_uuid=p.workplace_uuid WHERE w.business_uuid=? AND p.employee_uuid=? LIMIT 1")){
            s.setString(1,businessId.toString());s.setString(2,playerId.toString());try(ResultSet r=s.executeQuery()){return r.next();}
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
    private int parseMinute(String raw){try{LocalTime t=LocalTime.parse(raw);return t.getHour()*60+t.getMinute();}catch(Exception e){throw new IllegalArgumentException("Use 24-hour HH:mm times, for example 09:00 or 17:30.");}}
    private String formatMinute(int m){return String.format("%02d:%02d",Math.floorMod(m,1440)/60,Math.floorMod(m,60));}
    private String shortLine(String s){return s.length()>15?s.substring(0,15):s;}
    private String cleanName(String raw,int max,String label){String c=raw==null?"":raw.trim().replaceAll("\\s+"," ");if(c.isBlank())throw new IllegalArgumentException(label+" is required.");return c.length()<=max?c:c.substring(0,max);}
    private boolean isDoor(Material t){String n=t.name();return n.endsWith("_DOOR")||n.endsWith("_TRAPDOOR");}
    private Business readBusiness(ResultSet r)throws SQLException{return new Business(UUID.fromString(r.getString("business_uuid")),r.getString("name"),UUID.fromString(r.getString("owner_uuid")),r.getString("owner_name"));}
    private Workplace readWorkplace(ResultSet r)throws SQLException{String t=r.getString("territory_claim_uuid");return new Workplace(UUID.fromString(r.getString("workplace_uuid")),UUID.fromString(r.getString("business_uuid")),r.getString("name"),t==null?null:UUID.fromString(t),r.getInt("open_minute"),r.getInt("close_minute"));}
    private Position readPosition(ResultSet r)throws SQLException{String e=r.getString("employee_uuid");return new Position(UUID.fromString(r.getString("position_uuid")),UUID.fromString(r.getString("workplace_uuid")),r.getString("title"),r.getLong("wage"),e==null?null:UUID.fromString(e),r.getString("employee_name"));}
    public record Business(UUID id,String name,UUID ownerId,String ownerName){}
    public record Workplace(UUID id,UUID businessId,String name,UUID territoryClaimId,int openMinute,int closeMinute){}
    public record Position(UUID id,UUID workplaceId,String title,long wage,UUID employeeId,String employeeName){}
}
