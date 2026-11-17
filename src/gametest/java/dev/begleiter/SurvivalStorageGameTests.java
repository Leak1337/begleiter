package dev.begleiter;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/** Compact storage operating checks: real hopper transport and redstone, no simulated routing. */
public class SurvivalStorageGameTests {
    private static Container container(net.minecraft.server.level.ServerLevel level,BlockPos pos){
        var s=level.getBlockState(pos);return s.getBlock() instanceof ChestBlock c?ChestBlock.getContainer(c,s,level,pos,false):(Container)level.getBlockEntity(pos);
    }
    @GameTest(maxTicks=60000)
    public void compactRoom(GameTestHelper h) throws Exception {
        if(!Boolean.getBoolean("begleiter.survivalStorage")){h.succeed();return;}
        var file=Path.of(System.getProperty("begleiter.survivalStorageFile"));
        var meta=JsonParser.parseString(Files.readString(file.resolveSibling("compact.json"))).getAsJsonObject();
        var level=h.getLevel();var plan=CompanionSchematics.load(level,file);
        var rotation=Rotation.values()[Integer.getInteger("begleiter.survivalStorageRotation",0)];
        boolean side=rotation==Rotation.CLOCKWISE_90||rotation==Rotation.COUNTERCLOCKWISE_90;
        int width=side?plan.sizeZ():plan.sizeX(),depth=side?plan.sizeX():plan.sizeZ();
        var center=h.absolutePos(new BlockPos(96,3,96));var origin=center.offset(-width/2,0,-depth/2);
        java.util.function.Function<JsonArray,BlockPos> at=a->origin.offset(CompanionSchematics.rotate(new BlockPos(
            a.get(0).getAsInt()-meta.getAsJsonArray("min").get(0).getAsInt(),a.get(1).getAsInt()-meta.getAsJsonArray("min").get(1).getAsInt(),a.get(2).getAsInt()-meta.getAsJsonArray("min").get(2).getAsInt()),rotation,plan.sizeX(),plan.sizeZ()));
        for(int x=(origin.getX()-4)>>4;x<=(origin.getX()+width+4)>>4;x++)for(int z=(origin.getZ()-4)>>4;z<=(origin.getZ()+depth+4)>>4;z++){level.setChunkForced(x,z,true);level.getChunk(x,z);}
        var placed=CompanionSchematics.placeAtFull(plan,center,rotation,false);var job=CompanionBuilder.create(level,"survival-storage",placed.blocks());
        placed.data().forEach((p,t)->job.data.put(p,t.copy()));job.entities.addAll(placed.entities());
        for(var p:job.order){level.setBlock(p.pos(),p.state(),CompanionBuilder.PLACE_FLAGS);job.placed.add(p.pos());CompanionBuilder.applyData(level,job,p.pos(),true,false);}
        h.assertValueEqual(CompanionBuilder.finish(level,job),0,"All room blocks match after placement");CompanionBuilder.applyContents(level,job);
        h.assertValueEqual(CompanionBuilder.spawnEntities(level,job,null).missing(),0,"Frames placed");
        var modules=meta.getAsJsonArray("modules");var inputPos=at.apply(meta.getAsJsonArray("input"));var restPos=at.apply(meta.getAsJsonArray("overflow"));
        var first=CompanionCraft.item(modules.get(0).getAsJsonObject().get("item").getAsString());
        var last=CompanionCraft.item(modules.get(modules.size()-1).getAsJsonObject().get("item").getAsString());
        var box=new AABB(center).inflate(Math.max(width,depth)+5);
        int[] phase={0},ticks={0};
        h.onEachTick(()->{
            if(!level.isPositionEntityTicking(origin)||!level.isPositionEntityTicking(origin.offset(width,0,depth)))return;ticks[0]++;
            if(phase[0]==0&&ticks[0]>100){
                var frames=level.getEntitiesOfClass(ItemFrame.class,box);h.assertValueEqual(frames.size(),modules.size(),"All visible item frames survived");h.assertTrue(frames.stream().noneMatch(f->f.getItem().isEmpty()),"Frame label empty");
                var input=container(level,inputPos);h.assertTrue(input!=null,"Input chest accessible");
                for(int i=0;i<modules.size();i++){var row=modules.get(i).getAsJsonObject();h.assertTrue(container(level,at.apply(row.getAsJsonArray("chest")))!=null,"Lower storage chest can open");h.assertTrue(container(level,at.apply(row.getAsJsonArray("upper_chest")))!=null,"Upper storage chest can open");input.setItem(i,new ItemStack(CompanionCraft.item(row.get("item").getAsString()),16));}
                input.setItem(modules.size(),new ItemStack(Items.FLINT,9));input.setItem(modules.size()+1,new ItemStack(Items.IRON_PICKAXE));input.setChanged();phase[0]=1;ticks[0]=0;
            }else if(phase[0]==1&&ticks[0]>4500){
                for(var m:modules){var row=m.getAsJsonObject();var item=CompanionCraft.item(row.get("item").getAsString());int stored=container(level,at.apply(row.getAsJsonArray("chest"))).countItem(item)+container(level,at.apply(row.getAsJsonArray("upper_chest"))).countItem(item);
                    int buffer=container(level,at.apply(row.getAsJsonArray("connector"))).countItem(item)+container(level,at.apply(row.getAsJsonArray("extractor"))).countItem(item);
                    h.assertValueEqual(stored+buffer,17,"Sorted into correct bay "+item);h.assertTrue(stored>0,"Items reach storage chests "+item);}
                var rest=container(level,restPos);h.assertValueEqual(rest.countItem(Items.FLINT),9,"Unknown stack retained");h.assertValueEqual(rest.countItem(Items.IRON_PICKAXE),1,"Unstackable retained");
                // Ein Fach ganz füllen; das nächste muss weiter sortieren, ohne Filter zu verunreinigen.
                var full=container(level,at.apply(modules.get(0).getAsJsonObject().getAsJsonArray("chest")));
                for(int i=0;i<full.getContainerSize();i++)full.setItem(i,new ItemStack(first,64));full.setChanged();
                var connector=container(level,at.apply(modules.get(0).getAsJsonObject().getAsJsonArray("connector")));
                for(int i=0;i<connector.getContainerSize();i++)connector.setItem(i,new ItemStack(first,64));connector.setChanged();
                container(level,at.apply(modules.get(0).getAsJsonObject().getAsJsonArray("upper_chest"))).clearContent();
                container(level,at.apply(modules.get(0).getAsJsonObject().getAsJsonArray("extractor"))).setItem(0,new ItemStack(first,1));
                container(level,inputPos).setItem(0,new ItemStack(first,32));container(level,inputPos).setChanged();phase[0]=5;ticks[0]=0;
            }else if(phase[0]==5&&ticks[0]>1600){
                var upper=container(level,at.apply(modules.get(0).getAsJsonObject().getAsJsonArray("upper_chest")));
                h.assertValueEqual(upper.countItem(first),32,"Upper double chest fills when lower double chest is full");
                for(int i=0;i<upper.getContainerSize();i++)upper.setItem(i,new ItemStack(first,64));upper.setChanged();
                container(level,at.apply(modules.get(0).getAsJsonObject().getAsJsonArray("chest"))).clearContent();
                container(level,at.apply(modules.get(0).getAsJsonObject().getAsJsonArray("connector"))).clearContent();
                container(level,inputPos).setItem(0,new ItemStack(first,32));container(level,inputPos).setChanged();phase[0]=6;ticks[0]=0;
            }else if(phase[0]==6&&ticks[0]>1600){
                var firstRow=modules.get(0).getAsJsonObject();var lower=container(level,at.apply(firstRow.getAsJsonArray("chest")));
                h.assertTrue(lower.countItem(first)>0,"Lower double chest receives items when upper double chest is full");
                for(int i=0;i<lower.getContainerSize();i++)lower.setItem(i,new ItemStack(first,64));lower.setChanged();
                var connector=container(level,at.apply(firstRow.getAsJsonArray("connector")));for(int i=0;i<connector.getContainerSize();i++)connector.setItem(i,new ItemStack(first,64));connector.setChanged();
                container(level,at.apply(firstRow.getAsJsonArray("extractor"))).setItem(0,new ItemStack(first,1));
                var input=container(level,inputPos);for(int i=0;i<8;i++)input.setItem(i,new ItemStack(first,64));input.setItem(8,new ItemStack(last,32));input.setChanged();phase[0]=2;ticks[0]=0;
            }else if(phase[0]==2&&ticks[0]>6000){
                var finalRow=modules.get(modules.size()-1).getAsJsonObject();int lastCount=0;for(var key:List.of("chest","upper_chest","extractor","connector"))lastCount+=container(level,at.apply(finalRow.getAsJsonArray(key))).countItem(last);
                h.assertValueEqual(lastCount,49,"Neighbour continues after full bay");
                var totals=new HashMap<Item,Integer>();
                for(var p:job.order)if(level.getBlockEntity(p.pos()) instanceof Container c)for(int i=0;i<c.getContainerSize();i++){
                    var s=c.getItem(i);if(s.getComponentsPatch().isEmpty())totals.merge(s.getItem(),s.getCount(),Integer::sum);
                }
                h.assertValueEqual(totals.getOrDefault(first,0),108*64+5*64+512+2,"Full storage item conservation");
                for(var m:modules){var row=m.getAsJsonObject();var item=CompanionCraft.item(row.get("item").getAsString());
                    if(item!=first)h.assertValueEqual(totals.getOrDefault(item,0),(item==last?48:16)+2,"Item conservation "+item);
                    var filter=(Container)level.getBlockEntity(at.apply(row.getAsJsonArray("filter")));for(int i=1;i<5;i++)h.assertValueEqual(filter.getItem(i).getCount(),11,"Filter filler intact");}
                h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,box).isEmpty(),"An item escaped its inventory");
                // Ein voller Überlauf muss sicher zurückstauen und abfließen, sobald der Spieler Platz schafft.
                var rest=container(level,restPos);for(int i=0;i<rest.getContainerSize();i++)rest.setItem(i,new ItemStack(Items.FLINT,64));rest.setChanged();
                container(level,inputPos).setItem(0,new ItemStack(Items.AMETHYST_SHARD,64));container(level,inputPos).setChanged();phase[0]=3;ticks[0]=0;
            }else if(phase[0]==3&&ticks[0]>900){
                int emerald=0;for(var p:job.order)if(level.getBlockEntity(p.pos()) instanceof Container c)emerald+=c.countItem(Items.AMETHYST_SHARD);
                h.assertValueEqual(emerald,64,"Full overflow loses no input");h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,box).isEmpty(),"Overflow drops items");
                var rest=container(level,restPos);rest.clearContent();rest.setChanged();level.updateNeighbourForOutputSignal(restPos,Blocks.CHEST);phase[0]=4;ticks[0]=0;
            }else if(phase[0]==4&&ticks[0]>1200){
                h.assertValueEqual(container(level,restPos).countItem(Items.AMETHYST_SHARD),64,"Input resumes after overflow emptied");
                com.mojang.logging.LogUtils.getLogger().info("SURVIVAL_STORAGE_PASS rotation={} types=24 input, matching storage, nonstackables, full bay isolation, full overflow, resume; size={}x{}x{} hoppers={}",rotation,width,plan.sizeY(),depth,meta.get("hopper_count"));
                h.succeed();
            }
        });
    }
}
