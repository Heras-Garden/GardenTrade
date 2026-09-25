package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class BusinessCommand implements CommandExecutor, TabCompleter {
    private final BusinessService businesses;
    public BusinessCommand(BusinessService businesses){this.businesses=businesses;}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof Player p)){GardenMessages.send(sender,"Business commands must be used in-game.");return true;}
        try{
            if(args.length==0){help(p);return true;}
            switch(args[0].toLowerCase(Locale.ROOT)){
                case "create"->{if(args.length<2)throw new IllegalArgumentException("Use /business create <name>.");var b=businesses.createBusiness(p,join(args,1));GardenMessages.send(p,"Created business "+b.name()+".");}
                case "list"->{var all=businesses.businesses();GardenMessages.send(p,all.isEmpty()?"There are no registered businesses.":"Businesses: "+String.join(", ",all.stream().map(BusinessService.Business::name).toList()));}
                case "workplace"->workplace(p,args);
                case "position"->position(p,args);
                case "hire"->hire(p,args);
                case "fire"->fire(p,args);
                case "fund"->fund(p,args);
                case "balance"->balance(p,args);
                case "payroll"->payroll(p,args);
                case "state"->state(p,args);
                case "status"->status(p,args);
                default->help(p);
            }
        }catch(NumberFormatException e){GardenMessages.send(p,"Use whole numbers where an amount is required.");}
        catch(IllegalArgumentException e){GardenMessages.send(p,e.getMessage());}
        catch(SQLException e){GardenMessages.send(p,"The business system could not update right now.");}
        return true;
    }

    private void workplace(Player p,String[] a)throws SQLException{
        if(a.length<2){GardenMessages.send(p,"Use /business workplace <create|territory|schedule|override|door|sign|shop>.");return;}
        switch(a[1].toLowerCase(Locale.ROOT)){
            case "create"->{if(a.length<4)throw new IllegalArgumentException("Use /business workplace create <business> <name>.");var w=businesses.createWorkplace(p,a[2],join(a,3));GardenMessages.send(p,"Created workplace "+w.name()+". Default Garden hours are 09:00-17:00.");}
            case "territory"->{if(a.length<5)throw new IllegalArgumentException("Use /business workplace territory <business> <workplace> <territory>.");businesses.setTerritory(p,a[2],a[3],join(a,4));GardenMessages.send(p,"Bound "+a[3]+" to "+join(a,4)+".");}
            case "schedule"->{
                if(a.length==6){businesses.setSchedule(p,a[2],a[3],a[4],a[5]);GardenMessages.send(p,"Updated "+a[3]+" schedule for every Garden weekday.");}
                else if(a.length>=7){businesses.setWeeklySchedule(p,a[2],a[3],a[4],a[5],a[6]);GardenMessages.send(p,"Updated "+a[3]+" "+a[4]+" schedule.");}
                else throw new IllegalArgumentException("Use /business workplace schedule <business> <workplace> [weekday] <open HH:mm> <close HH:mm>.");
            }
            case "override"->{
                if(a.length<5)throw new IllegalArgumentException("Use /business workplace override <business> <workplace> <auto|open|closed> [minutes].");
                int minutes=a.length>=6?Integer.parseInt(a[5]):60;
                businesses.setWorkplaceOverride(p,a[2],a[3],a[4],minutes);
                GardenMessages.send(p,"Workplace override updated.");
            }
            case "door"->{if(a.length<4)throw new IllegalArgumentException("Use /business workplace door <business> <workplace> while looking at a door.");Block b=p.getTargetBlockExact(6);if(b==null)throw new IllegalArgumentException("Look directly at a door or trapdoor.");businesses.bindDoor(p,a[2],a[3],b);GardenMessages.send(p,"Linked that door to "+a[3]+".");}
            case "sign"->{if(a.length<4)throw new IllegalArgumentException("Use /business workplace sign <business> <workplace> while looking at a sign.");Block b=p.getTargetBlockExact(6);if(b==null)throw new IllegalArgumentException("Look directly at a sign.");businesses.bindSign(p,a[2],a[3],b);GardenMessages.send(p,"Linked and refreshed that OPEN/CLOSED sign.");}
            case "shop"->{if(a.length<5)throw new IllegalArgumentException("Use /business workplace shop <business> <workplace> <shop-uuid>.");businesses.bindShop(p,a[2],a[3],UUID.fromString(a[4]));GardenMessages.send(p,"Linked that shop to "+a[3]+". It now follows the Garden schedule.");}
            default->GardenMessages.send(p,"Use /business workplace <create|territory|schedule|override|door|sign|shop>.");
        }
    }

    private void position(Player p,String[] a)throws SQLException{
        if(a.length<2)throw new IllegalArgumentException("Use /business position <create|shift|anchor|permissions>.");
        switch(a[1].toLowerCase(Locale.ROOT)){
            case "create"->{
                if(a.length<6)throw new IllegalArgumentException("Use /business position create <business> <workplace> <wage> [slots audience] <title>.");
                long wage=Long.parseLong(a[4].replace(",",""));
                int slots=1;String audience="ANY";int titleStart=5;
                if(a.length>=8&&isInteger(a[5])&&isAudience(a[6])){slots=Integer.parseInt(a[5]);audience=a[6];titleStart=7;}
                var created=businesses.createPositions(p,a[2],a[3],join(a,titleStart),wage,slots,audience);
                GardenMessages.send(p,"Created "+created.size()+" "+audience.toUpperCase(Locale.ROOT)+" position slot"+(created.size()==1?"":"s")+" for "+created.getFirst().title()+".");
            }
            case "shift"->{if(a.length<6)throw new IllegalArgumentException("Use /business position shift <business> <position-uuid> <start HH:mm> <end HH:mm>.");var pos=businesses.setPositionShift(p,a[2],UUID.fromString(a[3]),a[4],a[5]);GardenMessages.send(p,"Updated shift for "+pos.title()+".");}
            case "anchor"->{if(a.length<4)throw new IllegalArgumentException("Use /business position anchor <business> <position-uuid> while standing at the work anchor.");var pos=businesses.setPositionAnchor(p,a[2],UUID.fromString(a[3]));GardenMessages.send(p,"Set work anchor for "+pos.title()+".");}
            case "permissions"->{if(a.length<5)throw new IllegalArgumentException("Use /business position permissions <business> <position-uuid> <ENTER,...>.");var pos=businesses.setPositionPermissions(p,a[2],UUID.fromString(a[3]),a[4]);GardenMessages.send(p,"Updated employee permissions for "+pos.title()+".");}
            default->throw new IllegalArgumentException("Use /business position <create|shift|anchor|permissions>.");
        }
    }

    private void hire(Player p,String[] a)throws SQLException{
        if(a.length<4)throw new IllegalArgumentException("Use /business hire <business> <position-uuid> <player>.");
        Player target=Bukkit.getPlayerExact(a[3]);if(target==null)throw new IllegalArgumentException("That player must be online for hiring.");
        var pos=businesses.hire(p,a[1],UUID.fromString(a[2]),target);GardenMessages.send(p,"Hired "+target.getName()+" as "+pos.title()+".");GardenMessages.send(target,"You were hired as "+pos.title()+" at "+a[1]+".");
    }
    private void fire(Player p,String[] a)throws SQLException{
        if(a.length<3)throw new IllegalArgumentException("Use /business fire <business> <position-uuid>.");
        var pos=businesses.fire(p,a[1],UUID.fromString(a[2]));GardenMessages.send(p,"Cleared employee from "+pos.title()+".");
    }
    private void fund(Player p,String[] a)throws SQLException{if(a.length<3)throw new IllegalArgumentException("Use /business fund <business> <amount>.");long balance=businesses.fundBusiness(p,a[1],Long.parseLong(a[2].replace(",","")));GardenMessages.send(p,"Business balance: ⟡ "+balance+".");}
    private void balance(Player p,String[] a)throws SQLException{if(a.length<2)throw new IllegalArgumentException("Use /business balance <business>.");GardenMessages.send(p,"Business balance: ⟡ "+businesses.businessBalance(p,join(a,1))+".");}
    private void payroll(Player p,String[] a)throws SQLException{if(a.length<2)throw new IllegalArgumentException("Use /business payroll <business>.");var r=businesses.runPayroll(p,join(a,1));GardenMessages.send(p,"Payroll: "+r.paid()+" paid for ⟡ "+r.total()+", "+r.skipped()+" waiting for funds.");}
    private void state(Player p,String[] a)throws SQLException{if(a.length<3)throw new IllegalArgumentException("Use /business state <business> <auto|open|closed>.");businesses.setBusinessState(p,a[1],a[2]);GardenMessages.send(p,"Business state set to "+a[2].toUpperCase(Locale.ROOT)+".");}
    private void status(Player p,String[] a)throws SQLException{
        if(a.length<2)throw new IllegalArgumentException("Use /business status <business>.");
        String name=join(a,1);
        long balance=businesses.businessBalance(p,name);
        p.sendMessage(GardenMessages.prefix().append(Component.text(name+" | Balance ⟡ "+balance+" ",NamedTextColor.WHITE))
                .append(Component.text("[OPEN]",NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/business state "+name+" open")))
                .append(Component.space())
                .append(Component.text("[CLOSED]",NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/business state "+name+" closed")))
                .append(Component.space())
                .append(Component.text("[AUTO]",NamedTextColor.GRAY).clickEvent(ClickEvent.runCommand("/business state "+name+" auto"))));
    }

    private String join(String[] a,int s){return String.join(" ",Arrays.copyOfRange(a,s,a.length));}
    private boolean isInteger(String s){try{Integer.parseInt(s);return true;}catch(Exception e){return false;}}
    private boolean isAudience(String s){String v=s.toUpperCase(Locale.ROOT);return v.equals("ANY")||v.equals("PLAYER")||v.equals("SOCIETY");}
    private void help(Player p){GardenMessages.send(p,"/business create, list, status, state, fund, balance, payroll, workplace <create|territory|schedule|override|door|sign|shop>, position <create|shift|anchor|permissions>, hire, fire");}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1)return match(args[0],List.of("create","list","status","state","fund","balance","payroll","workplace","position","hire","fire"));
        if(args.length==2&&args[0].equalsIgnoreCase("workplace"))return match(args[1],List.of("create","territory","schedule","override","door","sign","shop"));
        if(args.length==2&&args[0].equalsIgnoreCase("position"))return match(args[1],List.of("create","shift","anchor","permissions"));
        if(args.length==3&&args[0].equalsIgnoreCase("state"))return match(args[2],List.of("auto","open","closed"));
        return List.of();
    }
    private List<String> match(String p,List<String> v){String q=p.toLowerCase(Locale.ROOT);return v.stream().filter(x->x.startsWith(q)).toList();}
}
