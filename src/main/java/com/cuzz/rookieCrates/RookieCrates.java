package com.cuzz.rookieCrates;

import com.cuzz.rookieCrates.core.meg.ModelEngine;
import com.cuzz.rookieCrates.core.meg.ModelEngine4;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import me.tofaa.entitylib.wrapper.WrapperEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public final class RookieCrates extends JavaPlugin {
    private ModelEngine modelEngine;

    private static RookieCrates plugin;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        //On Bukkit, calling this here is essential, hence the name "load"
        PacketEvents.getAPI().load();
    }

    public static RookieCrates getInstance(){
        PacketEvents.getAPI().init();

        Optional<RookieCrates> instance = Optional.of(RookieCrates.plugin);
        return instance.get();
    }

    public WrapperEntity createEntityPack(Player player){

            PacketEventsAPI<?> api =  PacketEvents.getAPI();// create PacketEventsAPI instance
            SpigotEntityLibPlatform platform = new SpigotEntityLibPlatform(this);
            APIConfig settings = new APIConfig(PacketEvents.getAPI())
                    .debugMode()
                    .tickTickables()
                    .trackPlatformEntities()
                    .usePlatformLogger();

            EntityLib.init(platform, settings);

            // Making a random entity using packet events raw, i strongly recommend using the EntityLib#createEntity method instead
            WrapperEntity faker_ = new WrapperEntity(EntityTypes.ARMOR_STAND);
//            EntityMeta entityMeta = faker_.getEntityMeta();
//            // You can cast the meta to the correct type depending on the entity type
//            ArmorStandMeta armorStandMeta = (ArmorStandMeta) entityMeta;
//
//            // Once you're done modifying the meta accordingly, you can convert it to a packet, and send it to whoever you want for them  to see the changes.
//            WrapperPlayServerEntityMetadata metaPacket = armorStandMeta.createPacket();
                faker_.addViewer(player.getUniqueId());
                faker_.spawn(SpigotConversionUtil.fromBukkitLocation(player.getLocation()));

                setFakeHelmet(player,new ItemStack(Material.CARVED_PUMPKIN));
                 setFakeGameMode(player, GameMode.SPECTATOR);
                 return faker_;
    }

    public void setFakeHelmet(Player player, ItemStack itemStack) {

      //  WrapperPlayServerSetPlayerInventory wrapperPlayServerSetPlayerInventory = new WrapperPlayServerSetPlayerInventory(11, SpigotConversionUtil.fromBukkitItemStack(itemStack));
        WrapperPlayServerSetSlot wrapperPlayServerSetSlot = new WrapperPlayServerSetSlot(0,4396,5,SpigotConversionUtil.fromBukkitItemStack(itemStack));
       // PacketEvents.getAPI().getPlayerManager().sendPacket(player, wrapperPlayServerSetPlayerInventory);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, wrapperPlayServerSetSlot);
        player.sendMessage("@@@@@@@@@@@@@@");

    }

    public void setFakeGameMode(Player player,com.github.retrooper.packetevents.protocol.player.GameMode gameMode){
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
//        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE);
//        WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(player.getUniqueId());
//        WrapperPlayServerPlayerInfoUpdate.PlayerInfo
//        playerInfo.setGameMode(gameMode);
//        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> playerInfos = List.of(playerInfo);
//        WrapperPlayServerPlayerInfoUpdate wrapperPlayServerPlayerInfoUpdatePack = new WrapperPlayServerPlayerInfoUpdate(actions,playerInfos);
//        PacketEvents.getAPI().getPlayerManager().sendPacket(player, wrapperPlayServerPlayerInfoUpdatePack);
//        player.sendMessage("CCCCCCCCCCCCCC");
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        RookieCrates.plugin=this;
        saveDefaultConfig();
        setupModelEngine();
        this.getCommand("RookieCrates").setExecutor(new Commands());
    }

    public ModelEngine getModelEngine() {
        return this.modelEngine;
    }

    public void setupModelEngine() {
        this.modelEngine = (ModelEngine)new ModelEngine4();
        getLogger().info("ModelEngine 4 found, using new model engine");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        PacketEvents.getAPI().terminate();
    }

    public void saveLocation(Player player, String type) {
        Location loc = player.getLocation();
        String path=null;
        switch (type){
            case "crateloc":
                path = "crateLocations";
                break;
            case "cameraloc":
                path = "cameraLocations";
                break;
            case "loot1":
                path = "loot1Locations";
                break;
            case "loot2":
                path = "loot2Locations";
                break;
            case "loot3":
                path = "loot3Locations";
                break;
            case "loot4":
                path = "loot4Locations";
                break;
            case "loot5":
                path = "loot5Locations";
                break;
            case "loot6":
                path = "loot6Locations";
                break;
            case "loot7":
                path = "loot7Locations";
                break;
            default:
                player.sendMessage("路径有误");
        }

        getConfig().set(path + ".world", loc.getWorld().getName());
        getConfig().set(path + ".x", loc.getX());
        getConfig().set(path + ".y", loc.getY());
        getConfig().set(path + ".z", loc.getZ());
        getConfig().set(path + ".yaw", loc.getYaw());
        getConfig().set(path + ".pitch", loc.getPitch());
        saveConfig();
    }

    public Location getLocation(Player player,String type) {
        String path = "null";
        switch (type){
            case "crateloc":
                path = "crateLocations";
                break;
            case "cameraloc":
                path = "cameraLocations";
                break;
            case "loot1":
                path = "loot1Locations";
                break;
            case "loot2":
                path = "loot2Locations";
                break;
            case "loot3":
                path = "loot3Locations";
                break;
            case "loot4":
                path = "loot4Locations";
                break;
            case "loot5":
                path = "loot5Locations";
                break;
            case "loot6":
                path = "loot6Locations";
                break;
            case "loot7":
                path = "loot7Locations";
                break;
            default:
                player.sendMessage("路径有误");
        }
        org.bukkit.World world = Bukkit.getWorld(getConfig().getString(path + ".world"));
        double x = getConfig().getDouble(path + ".x");
        double y = getConfig().getDouble(path + ".y");
        double z = getConfig().getDouble(path + ".z");
        float yaw = (float) getConfig().getDouble(path + ".yaw");
        float pitch = (float) getConfig().getDouble(path + ".pitch");

        return new Location(world, x, y, z, yaw, pitch);
    }

}
