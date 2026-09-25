package com.herasgarden.gardentrade;

import com.herasgarden.gardencore.api.ui.GardenMessages;
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
                default->help(p);
            }
        }catch(NumberFormatException e){GardenMessages.send(p,"Wage must be a whole number of Obols.");}
        catch(IllegalArgumentException e){GardenMessages.send(p,e.getMessage());}
        catch(SQLException e){GardenMessages.send(p,"The business system could not update right now.");}
        return true;
    }
    private void workplace(Player p,String[] a)throws SQLException{
        if(a.length<2){GardenMessages.send(p,"Use /business workplace <create|territory|schedule|door|sign|shop>.");return;}
        switch(a[1].toLowerCase(Locale.ROOT)){
            case "create"->{if(a.length<4)throw new IllegalArgumentException("Use /business workplace create <business> <name>.");var w=businesses.createWorkplace(p,a[2],join(a,3));GardenMessages.send(p,"Created workplace "+w.name()+". Default hours are 09:00-17:00.");}
            case "territory"->{if(a.length<5)throw new IllegalArgumentException("Use /business workplace territory <business> <workplace> <territory>.");businesses.setTerritory(p,a[2],a[3],join(a,4));GardenMessages.send(p,"Bound "+a[3]+" to "+join(a,4)+".");}
            case "schedule"->{if(a.length<6)throw new IllegalArgumentException("Use /business workplace schedule <business> <workplace> <open HH:mm> <close HH:mm>.");businesses.setSchedule(p,a[2],a[3],a[4],a[5]);GardenMessages.send(p,"Updated "+a[3]+" schedule.");}
            case "door"->{if(a.length<4)throw new IllegalArgumentException("Use /business workplace door <business> <workplace> while looking at a door.");Block b=p.getTargetBlockExact(6);if(b==null)throw new IllegalArgumentException("Look directly at a door or trapdoor.");businesses.bindDoor(p,a[2],a[3],b);GardenMessages.send(p,"Linked that door to "+a[3]+".");}
            case "sign"->{if(a.length<4)throw new IllegalArgumentException("Use /business workplace sign <business> <workplace> while looking at a sign.");Block b=p.getTargetBlockExact(6);if(b==null)throw new IllegalArgumentException("Look directly at a sign.");businesses.bindSign(p,a[2],a[3],b);GardenMessages.send(p,"Linked and refreshed that OPEN/CLOSED sign.");}
            case "shop"->{if(a.length<5)throw new IllegalArgumentException("Use /business workplace shop <business> <workplace> <shop-uuid>.");businesses.bindShop(p,a[2],a[3],UUID.fromString(a[4]));GardenMessages.send(p,"Linked that shop to "+a[3]+". It now follows the workplace schedule.");}
            default->GardenMessages.send(p,"Use /business workplace <create|territory|schedule|door|sign|shop>.");
        }
    }
    private void position(Player p,String[] a)throws SQLException{
        if(a.length<6||!a[1].equalsIgnoreCase("create"))throw new IllegalArgumentException("Use /business position create <business> <workplace> <wage> <title>.");
        long wage=Long.parseLong(a[4].replace(",",""));var pos=businesses.createPosition(p,a[2],a[3],join(a,5),wage);
        GardenMessages.send(p,"Created position "+pos.title()+" ("+pos.id()+") at ⟡ "+pos.wage()+".");
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
    private String join(String[] a,int s){return String.join(" ",Arrays.copyOfRange(a,s,a.length));}
    private void help(Player p){GardenMessages.send(p,"/business create <name>, list, workplace <create|territory|schedule|door|sign|shop>, position create, hire, fire");}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1)return match(args[0],List.of("create","list","workplace","position","hire","fire"));
        if(args.length==2&&args[0].equalsIgnoreCase("workplace"))return match(args[1],List.of("create","territory","schedule","door","sign","shop"));
        if(args.length==2&&args[0].equalsIgnoreCase("position"))return match(args[1],List.of("create"));
        return List.of();
    }
    private List<String> match(String p,List<String> v){String q=p.toLowerCase(Locale.ROOT);return v.stream().filter(x->x.startsWith(q)).toList();}
}
