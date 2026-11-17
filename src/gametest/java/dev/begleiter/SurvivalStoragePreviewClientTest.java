package dev.begleiter;

import java.nio.file.Path;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/** Gerenderte Ansicht des Kistenraums auf Anforderung, getrennt von den Betriebsprüfungen. */
public class SurvivalStoragePreviewClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try(var world=context.worldBuilder().create()){
            world.getConnection().waitForChunksDownload();
            world.getServer().runOnServer(server->{
                var player=server.getPlayerList().getPlayers().getFirst();var level=player.level();
                try{
                    var plan=CompanionSchematics.load(level,Path.of(System.getProperty("begleiter.survivalStorageFile")));
                    var placed=CompanionSchematics.placeAtFull(plan,new BlockPos(0,80,0),Rotation.NONE,false);
                    var job=CompanionBuilder.create(level,"room-preview",placed.blocks());placed.data().forEach((p,t)->job.data.put(p,t.copy()));job.entities.addAll(placed.entities());
                    for(var p:job.order){level.setBlock(p.pos(),p.state(),CompanionBuilder.PLACE_FLAGS);job.placed.add(p.pos());CompanionBuilder.applyData(level,job,p.pos(),true,false);}
                    CompanionBuilder.finish(level,job);CompanionBuilder.applyContents(level,job);CompanionBuilder.spawnEntities(level,job,null);
                    for(int y=82;y<=83;y++){var door=new BlockPos(0,y,-12);level.setBlock(door,level.getBlockState(door).setValue(net.minecraft.world.level.block.DoorBlock.OPEN,true),3);}
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set noon");
                    player.setNoGravity(true);player.teleportTo(level,.5,82,-12.3,Set.of(),0,2,true);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            context.runOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();});
            context.waitTicks(60);context.takeScreenshot("survival-storage-interior");
            world.getServer().runOnServer(server->{var p=server.getPlayerList().getPlayers().getFirst();p.teleportTo(p.level(),16,94,-24,Set.of(),43,24,true);});
            context.waitTicks(40);context.takeScreenshot("survival-storage-exterior");
        }
    }
}
