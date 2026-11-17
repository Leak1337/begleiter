package dev.begleiter;

import java.nio.file.Path;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;

/** Echter Redstone-Prototyp. Transport, Sortierung, Boxabbau und Ersatz laufen über normale Spielticks. */
public class RequestStorageGameTests {
    private static Container container(net.minecraft.server.level.ServerLevel level,BlockPos pos){
        var state=level.getBlockState(pos);
        return state.getBlock() instanceof ChestBlock chest?ChestBlock.getContainer(chest,state,level,pos,false):(Container)level.getBlockEntity(pos);
    }
    private static ItemStack box(net.minecraft.world.item.Item item,int count){
        var stack=new ItemStack(Items.SHULKER_BOX);stack.set(net.minecraft.core.component.DataComponents.CONTAINER,
            net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(item,count))));return stack;
    }
    private static void lever(net.minecraft.server.level.ServerLevel level,BlockPos pos,boolean on){
        var state=level.getBlockState(pos);level.setBlock(pos,state.setValue(LeverBlock.POWERED,on),3);
        level.updateNeighborsAt(pos.relative(state.getValue(LeverBlock.FACING).getOpposite()),Blocks.LEVER);
    }
    private static int emptyStock(net.minecraft.server.level.ServerLevel level,CompanionBuilder.BuildJob job){
        int total=0;for(var p:job.order)if(level.getBlockEntity(p.pos()) instanceof Container c)for(int slot=0;slot<c.getContainerSize();slot++){
            var stack=c.getItem(slot);if(!stack.is(Items.SHULKER_BOX))continue;var content=stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
            if(content==null||content.nonEmptyItemCopyStream().findAny().isEmpty())total+=stack.getCount();
        }return total;
    }
    @GameTest(maxTicks=40000)
    public void unloader(GameTestHelper h) throws Exception {
        if(!Boolean.getBoolean("begleiter.requestStorage")){h.succeed();return;}
        var level=h.getLevel();var file=Path.of(System.getProperty("begleiter.requestStorageNetwork")).resolveSibling("unloader.nbt");
        var plan=CompanionSchematics.load(level,file);var center=h.absolutePos(new BlockPos(1024,2,1024));
        var origin=center.offset(-plan.sizeX()/2,0,-plan.sizeZ()/2);var placed=CompanionSchematics.placeAtFull(plan,center,Rotation.NONE,false);
        for(int x=(origin.getX()-4)>>4;x<=(origin.getX()+plan.sizeX()+4)>>4;x++)for(int z=(origin.getZ()-4)>>4;z<=(origin.getZ()+plan.sizeZ()+4)>>4;z++){level.setChunkForced(x,z,true);level.getChunk(x,z);}
        var job=CompanionBuilder.create(level,"unloader",placed.blocks());
        for(var p:job.order){level.setBlock(p.pos(),p.state(),CompanionBuilder.PLACE_FLAGS);job.placed.add(p.pos());}CompanionBuilder.finish(level,job);
        int[] phase={0},ticks={0};h.onEachTick(()->{
            if(!level.isPositionEntityTicking(origin)||!level.isPositionEntityTicking(origin.offset(4,0,4)))return;ticks[0]++;
            if(phase[0]==0&&ticks[0]>100){
                var input=(Container)level.getBlockEntity(origin.offset(1,3,0));
                var a=new ItemStack(Items.SHULKER_BOX);a.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.COBBLESTONE,64))));
                var b=new ItemStack(Items.SHULKER_BOX);b.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.DIRT,32))));
                input.setItem(0,a);input.setItem(1,b);input.setItem(2,new ItemStack(Items.SHULKER_BOX));input.setChanged();phase[0]=1;ticks[0]=0;
            }else if(phase[0]==1&&ticks[0]>2500){
                var output=(Container)level.getBlockEntity(origin.offset(1,0,2));var empty=(Container)level.getBlockEntity(origin.offset(0,0,2));
                h.assertValueEqual(output.countItem(Items.COBBLESTONE),64,"Unloaded cobble");h.assertValueEqual(output.countItem(Items.DIRT),32,"Unloaded dirt");
                h.assertValueEqual(empty.countItem(Items.SHULKER_BOX),3,"Separated empty boxes");
                h.assertTrue(((Container)level.getBlockEntity(origin.offset(1,1,2))).isEmpty(),"Output item buffered");
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_UNLOADER_PASS 96 items, three empty boxes, automatic restart");h.succeed();
            }
        });
    }
    @GameTest(maxTicks=200000)
    public void network(GameTestHelper h) throws Exception {
        if(!Boolean.getBoolean("begleiter.requestStorage")){h.succeed();return;}
        var level=h.getLevel();var file=Path.of(System.getProperty("begleiter.requestStorageNetwork"));
        var meta=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file.resolveSibling(file.getFileName().toString().replace(".nbt",".json")))).getAsJsonObject();
        var plan=CompanionSchematics.load(level,file);var center=h.absolutePos(new BlockPos(128,2,128));
        var rotation=Rotation.values()[Integer.parseInt(System.getProperty("begleiter.requestStorageRotation","0"))];
        boolean sideways=rotation==Rotation.CLOCKWISE_90||rotation==Rotation.COUNTERCLOCKWISE_90;
        int width=sideways?plan.sizeZ():plan.sizeX(),depth=sideways?plan.sizeX():plan.sizeZ();
        var origin=center.offset(-width/2,0,-depth/2);
        java.util.function.Function<com.google.gson.JsonArray,BlockPos> at=a->origin.offset(CompanionSchematics.rotate(new BlockPos(a.get(0).getAsInt()-meta.getAsJsonArray("min").get(0).getAsInt(),a.get(1).getAsInt()-meta.getAsJsonArray("min").get(1).getAsInt(),a.get(2).getAsInt()-meta.getAsJsonArray("min").get(2).getAsInt()),rotation,plan.sizeX(),plan.sizeZ()));
        var placed=CompanionSchematics.placeAtFull(plan,center,rotation,false);
        for(int x=(origin.getX()-4)>>4;x<=(origin.getX()+width+4)>>4;x++)for(int z=(origin.getZ()-4)>>4;z<=(origin.getZ()+depth+4)>>4;z++){
            level.setChunkForced(x,z,true);level.getChunk(x,z);
        }
        var job=CompanionBuilder.create(level,"request-network",placed.blocks());placed.data().forEach((p,t)->job.data.put(p,t.copy()));
        job.entities.addAll(placed.entities());
        for(var p:job.order){level.setBlock(p.pos(),p.state(),CompanionBuilder.PLACE_FLAGS);job.placed.add(p.pos());CompanionBuilder.applyData(level,job,p.pos(),true,false);}
        CompanionBuilder.finish(level,job);CompanionBuilder.applyContents(level,job);
        var entityResult=CompanionBuilder.spawnEntities(level,job,null);h.assertValueEqual(entityResult.missing(),0,"Catalogue frames spawned");h.assertValueEqual(entityResult.emptyFrames(),0,"Frame icons retained");
        h.assertValueEqual(entityResult.spawned(),meta.has("automatic")?meta.getAsJsonArray("modules").size():0,"All frame labels created");
        boolean automatic=meta.has("automatic")&&meta.get("automatic").getAsBoolean();
        var modules=meta.getAsJsonArray("modules");int[] phase={0},ticks={0},next={0},gotBoxes={0},initialEmpty={0};int amount=modules.size()>4?4:32;
        var first=CompanionCraft.item(modules.get(0).getAsJsonObject().get("item").getAsString());
        var last=CompanionCraft.item(modules.get(modules.size()-1).getAsJsonObject().get("item").getAsString());
        var received=new java.util.HashMap<net.minecraft.world.item.Item,Integer>();
        h.onEachTick(()->{
            if(!level.isPositionEntityTicking(origin)||!level.isPositionEntityTicking(origin.offset(width,0,depth)))return;
            ticks[0]++;
            if(phase[0]==1&&ticks[0]==200){
                var pos=at.apply(meta.getAsJsonArray("input")).below();var list=new java.util.ArrayList<String>();
                for(var d:net.minecraft.core.Direction.values())list.add(d+"="+level.getBlockState(pos.relative(d))+" signal="+level.getSignal(pos.relative(d),d));
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_INPUT_DIAG {} ticking={} neighbors={}",level.getBlockState(pos),level.isPositionEntityTicking(pos),list);
            }
            if(phase[0]==1 && next[0]<=modules.size()) {
                var input=(Container)level.getBlockEntity(at.apply(meta.getAsJsonArray("input")));
                for(int slot=0;slot<input.getContainerSize()&&next[0]<=modules.size();slot++)if(input.getItem(slot).isEmpty()){
                    if(next[0]==modules.size() && (!input.isEmpty() || !((Container)level.getBlockEntity(at.apply(meta.getAsJsonArray("input")).below())).isEmpty() || !((Container)level.getBlockEntity(at.apply(meta.getAsJsonArray("input")).below(2))).isEmpty()))break;
                    if(next[0]==modules.size())com.mojang.logging.LogUtils.getLogger().info("REQUEST_METER knownItems={} ticks={} itemsPerSecond={}",modules.size()*amount,ticks[0],modules.size()*amount*20.0/ticks[0]);
                    input.setItem(slot,next[0]==modules.size()?new ItemStack(Items.ENDER_EYE,9):new ItemStack(CompanionCraft.item(modules.get(next[0]).getAsJsonObject().get("item").getAsString()),amount));next[0]++;input.setChanged();
                }
            }
            if(phase[0]==2||phase[0]==4||phase[0]==5) {
                var output=container(level,at.apply(meta.getAsJsonArray("output")));
                for(int slot=0;slot<output.getContainerSize();slot++)if(!output.getItem(slot).isEmpty()){
                    var box=output.removeItemNoUpdate(slot);var contents=box.get(net.minecraft.core.component.DataComponents.CONTAINER);
                    if(automatic)received.merge(box.getItem(),box.getCount(),Integer::sum);
                    else {h.assertTrue(contents!=null,"Output contains a loose item instead of a box");gotBoxes[0]++;contents.nonEmptyItemCopyStream().forEach(s->received.merge(s.getItem(),s.getCount(),Integer::sum));}
                }output.setChanged();
            }
            if(phase[0]==0&&ticks[0]>100){
                initialEmpty[0]=emptyStock(level,job);
                if(automatic){
                    var frames=level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,new net.minecraft.world.phys.AABB(center).inflate(Math.max(width,depth)+4));
                    h.assertValueEqual(frames.size(),modules.size(),"Live frame count");h.assertTrue(frames.stream().noneMatch(f->f.getItem().isEmpty()),"An item-frame label is empty");
                }
                var input=(Container)level.getBlockEntity(at.apply(meta.getAsJsonArray("input")));
                input.setChanged();phase[0]=1;ticks[0]=0;
            }else if(phase[0]==1&&(ticks[0]>(modules.size()>4?16000:5000)||
                    (next[0]>modules.size()&&ticks[0]%20==0&&container(level,at.apply(meta.getAsJsonArray("overflow"))).countItem(Items.ENDER_EYE)==9&&
                        java.util.stream.StreamSupport.stream(modules.spliterator(),false).allMatch(m->{var row=m.getAsJsonObject();var c=level.getBlockEntity(at.apply(row.getAsJsonArray("box")));return c instanceof Container cc&&cc.countItem(CompanionCraft.item(row.get("item").getAsString()))==amount;})))){
                var diag=new java.util.ArrayList<String>();
                for(var m:modules){var row=m.getAsJsonObject();var box=(Container)level.getBlockEntity(at.apply(row.getAsJsonArray("box")));var item=CompanionCraft.item(row.get("item").getAsString());
                    diag.add(row.get("item")+"="+(box==null?"missing":box.countItem(item)));}
                var loose=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(center).inflate(80)).stream().map(e->e.getItem()+" at "+e.position().subtract(net.minecraft.world.phys.Vec3.atLowerCornerOf(origin))).toList();
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_NETWORK {} loose={}",diag,loose);
                var inventories=new java.util.ArrayList<String>();
                for(var p:job.order)if(level.getBlockEntity(p.pos()) instanceof Container c)for(int slot=0;slot<c.getContainerSize();slot++)
                    if(!c.getItem(slot).isEmpty()&&!c.getItem(slot).is(Items.SHULKER_BOX)&&!c.getItem(slot).is(Items.PAPER))inventories.add(p.pos().subtract(origin)+"="+c.getItem(slot));
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_INVENTORIES {}",inventories);
                for(var m:modules){var row=m.getAsJsonObject();var item=CompanionCraft.item(row.get("item").getAsString());h.assertValueEqual(((Container)level.getBlockEntity(at.apply(row.getAsJsonArray("box")))).countItem(item),amount,"Sorted "+item);}
                h.assertValueEqual(((Container)level.getBlockEntity(at.apply(meta.getAsJsonArray("overflow")))).countItem(Items.ENDER_EYE),9,"Unknown items in overflow");
                for(var m:modules){var p=at.apply(m.getAsJsonObject().getAsJsonArray("button"));((ButtonBlock)level.getBlockState(p).getBlock()).press(level.getBlockState(p),level,p,null);}
                phase[0]=2;ticks[0]=0;
            }else if(phase[0]==2&&(ticks[0]>(modules.size()>4?16000:automatic?4000:1000)||
                    (ticks[0]>100&&java.util.stream.StreamSupport.stream(modules.spliterator(),false).allMatch(m->received.getOrDefault(CompanionCraft.item(m.getAsJsonObject().get("item").getAsString()),0)==amount)))){
                var output=container(level,at.apply(meta.getAsJsonArray("output")));
                var boxes=new java.util.ArrayList<String>();for(var p:job.order)if(level.getBlockEntity(p.pos()) instanceof Container c)for(int s=0;s<c.getContainerSize();s++){
                    var contents=c.getItem(s).get(net.minecraft.core.component.DataComponents.CONTAINER);if(contents!=null&&contents.nonEmptyItemCopyStream().findAny().isPresent())boxes.add(p.pos().subtract(origin)+" "+contents.nonEmptyItemCopyStream().map(Object::toString).toList());}
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_BOXES {} loose={}",boxes,level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(center).inflate(80)).stream().map(e->e.getItem()+" "+e.position().subtract(net.minecraft.world.phys.Vec3.atLowerCornerOf(origin))).toList());
                if(!automatic)h.assertValueEqual(gotBoxes[0],modules.size(),"All requested boxes at common output");
                for(var m:modules){var row=m.getAsJsonObject();var item=CompanionCraft.item(row.get("item").getAsString());int total=0;
                    for(int i=0;i<output.getContainerSize();i++){var contents=output.getItem(i).get(net.minecraft.core.component.DataComponents.CONTAINER);if(contents!=null)total+=contents.nonEmptyItemCopyStream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
                    h.assertValueEqual(received.getOrDefault(item,0),amount,"Retrieved "+item);h.assertTrue(level.getBlockEntity(at.apply(row.getAsJsonArray("box"))) instanceof Container,"Replacement "+item);}
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_NETWORK_PASS {} types sorted and retrieved, unknown items retained",modules.size());
                if(!automatic){h.succeed();return;}
                for(var m:modules){var p=at.apply(m.getAsJsonObject().getAsJsonArray("button"));((ButtonBlock)level.getBlockState(p).getBlock()).press(level.getBlockState(p),level,p,null);}
                lever(level,at.apply(meta.getAsJsonArray("mode_switch")),true);
                var restPos=at.apply(meta.getAsJsonArray("overflow"));((Container)level.getBlockEntity(restPos)).clearContent();level.updateNeighbourForOutputSignal(restPos,Blocks.BARREL);
                var input=(Container)level.getBlockEntity(at.apply(meta.getAsJsonArray("box_input")));
                input.setItem(0,box(first,16));input.setItem(1,box(last,16));input.setItem(2,new ItemStack(Items.SHULKER_BOX));input.setChanged();
                phase[0]=3;ticks[0]=0;
            }else if(phase[0]==3&&(ticks[0]>(modules.size()>4?12000:4000)||
                    (ticks[0]>100&&((Container)level.getBlockEntity(at.apply(modules.get(0).getAsJsonObject().getAsJsonArray("box")))).countItem(first)==16&&
                    ((Container)level.getBlockEntity(at.apply(modules.get(modules.size()-1).getAsJsonObject().getAsJsonArray("box")))).countItem(last)==16))){
                for(int i:new int[]{0,modules.size()-1}){var row=modules.get(i).getAsJsonObject();var item=CompanionCraft.item(row.get("item").getAsString());
                    h.assertValueEqual(((Container)level.getBlockEntity(at.apply(row.getAsJsonArray("box")))).countItem(item),16,"Incoming box sorted "+item);
                    var p=at.apply(row.getAsJsonArray("button"));((ButtonBlock)level.getBlockState(p).getBlock()).press(level.getBlockState(p),level,p,null);}
                phase[0]=4;ticks[0]=0;
            }else if(phase[0]==4&&(ticks[0]>(modules.size()>4?3000:1500)||container(level,at.apply(meta.getAsJsonArray("box_output"))).countItem(Items.SHULKER_BOX)==2)){
                var queue=(Container)level.getBlockEntity(at.apply(meta.getAsJsonArray("box_output")));
                h.assertValueEqual(queue.countItem(Items.SHULKER_BOX),2,"Whole box mode holds both requests");
                boolean taken=false;for(int slot=0;slot<queue.getContainerSize();slot++){
                    var contents=queue.getItem(slot).get(net.minecraft.core.component.DataComponents.CONTAINER);
                    if(contents!=null&&contents.nonEmptyItemCopyStream().anyMatch(s->s.is(first))){
                        var picked=queue.removeItemNoUpdate(slot).get(net.minecraft.core.component.DataComponents.CONTAINER);
                        h.assertValueEqual(picked.nonEmptyItemCopyStream().filter(s->s.is(first)).mapToInt(ItemStack::getCount).sum(),16,"Whole-box contents");
                        received.merge(first,16,Integer::sum);taken=true;break;
                    }
                }h.assertTrue(taken,"Whole requested box missing");queue.setChanged();
                lever(level,at.apply(meta.getAsJsonArray("mode_switch")),false);
                var input=(Container)level.getBlockEntity(at.apply(meta.getAsJsonArray("box_input")));input.setItem(0,new ItemStack(Items.SHULKER_BOX));input.setChanged();
                phase[0]=5;ticks[0]=0;
            }else if(phase[0]==5&&(ticks[0]>(modules.size()>4?12000:4000)||
                    (ticks[0]>200&&ticks[0]%20==0&&received.getOrDefault(first,0)==amount+16&&received.getOrDefault(last,0)==amount+16&&emptyStock(level,job)==initialEmpty[0]+3))){
                for(var m:modules){var row=m.getAsJsonObject();var item=CompanionCraft.item(row.get("item").getAsString());
                    h.assertValueEqual(received.getOrDefault(item,0),amount+(item==first||item==last?16:0),"Final item conservation "+item);}
                h.assertValueEqual(emptyStock(level,job),initialEmpty[0]+3,"All original and three incoming empty boxes conserved");
                var remaining=new java.util.HashMap<net.minecraft.world.item.Item,Integer>();
                for(var p:job.order)if(level.getBlockEntity(p.pos()) instanceof Container c)for(int slot=0;slot<c.getContainerSize();slot++){
                    var stack=c.getItem(slot);if(stack.getComponentsPatch().isEmpty())remaining.merge(stack.getItem(),stack.getCount(),Integer::sum);
                }
                for(var m:modules){var item=CompanionCraft.item(m.getAsJsonObject().get("item").getAsString());h.assertValueEqual(remaining.getOrDefault(item,0),84,"Reserved filter items retained "+item);}
                h.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(center).inflate(Math.max(width,depth)+4)).isEmpty(),"Loose items remain outside inventories");
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_COMPLETE_PASS types={} auto input, auto output, whole-box switch, restart, empty conservation={}",modules.size(),initialEmpty[0]+3);h.succeed();
            }
        });
    }
    @GameTest(maxTicks = 20000)
    public void prototype(GameTestHelper h) throws Exception {
        if (!Boolean.getBoolean("begleiter.requestStorage")) { h.succeed(); return; }
        var level=h.getLevel();
        var plan=CompanionSchematics.load(level,Path.of(System.getProperty("begleiter.requestStorageSource")));
        var center=h.absolutePos(new BlockPos(24,2,24));
        var origin=center.offset(-plan.sizeX()/2+1,0,-plan.sizeZ()/2+2);
        var placed=CompanionSchematics.placeAtFull(plan,center,Rotation.NONE,false);
        for(int x=(origin.getX()-4)>>4;x<=(origin.getX()+plan.sizeX()+4)>>4;x++)
            for(int z=(origin.getZ()-4)>>4;z<=(origin.getZ()+plan.sizeZ()+4)>>4;z++){
                level.setChunkForced(x,z,true);level.getChunk(x,z);
            }
        var job=CompanionBuilder.create(level,"request-prototype",placed.blocks());
        placed.data().forEach((p,t)->job.data.put(p,t.copy()));
        for(var p:job.order){level.setBlock(p.pos(),p.state(),CompanionBuilder.PLACE_FLAGS);job.placed.add(p.pos());CompanionBuilder.applyData(level,job,p.pos(),true,false);}
        CompanionBuilder.finish(level,job);CompanionBuilder.applyContents(level,job);
        int[] phase={0},ticks={0};
        h.onEachTick(()->{
            // Neu erzwungene Chunks ticken nicht sofort. Den Betrieb erst prüfen, wenn sie aktiv sind.
            if(!level.isPositionEntityTicking(origin)||!level.isPositionEntityTicking(origin.offset(plan.sizeX(),0,plan.sizeZ())))return;
            ticks[0]++;
            if(phase[0]==2 && (ticks[0]==10||ticks[0]==40||ticks[0]==100)) {
                var diag=new java.util.ArrayList<String>();
                for(var p:new int[][]{{6,3,2},{6,4,3},{4,4,4},{3,3,4},{2,3,3},{2,4,3},{1,4,3},{0,4,3},{0,4,2},{1,4,2},{3,4,2},{5,4,2},{5,4,1}})
                    diag.add(java.util.Arrays.toString(p)+"="+level.getBlockState(origin.offset(p[0],p[1],p[2])));
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_REPLACE {} {}",ticks[0],diag);
            }
            if(phase[0]==0 && ticks[0]>100){
                var input=(Container)level.getBlockEntity(origin.offset(2,12,2));
                input.setItem(0,new ItemStack(Items.COBBLESTONE,32));input.setChanged();phase[0]=1;ticks[0]=0;
            }else if(phase[0]==1 && ticks[0]>600){
                h.assertValueEqual(((Container)level.getBlockEntity(origin.offset(2,4,2))).countItem(Items.COBBLESTONE),32,"Sorted input");
                var at=origin.offset(6,3,2);((ButtonBlock)level.getBlockState(at).getBlock()).press(level.getBlockState(at),level,at,null);
                phase[0]=2;ticks[0]=0;
            }else if(phase[0]==2 && ticks[0]>100){
                h.assertTrue(level.getBlockEntity(origin.offset(2,4,2)) instanceof Container,"Replacement box missing");
                h.assertTrue(((Container)level.getBlockEntity(origin.offset(2,4,2))).isEmpty(),"Replacement not empty");
                var out=(Container)level.getBlockEntity(origin.offset(3,3,0));
                h.assertValueEqual(out.countItem(Items.SHULKER_BOX),1,"Exactly one output box");
                var content=out.getItem(0).get(net.minecraft.core.component.DataComponents.CONTAINER);
                h.assertValueEqual(content.nonEmptyItemCopyStream().filter(s->s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum(),32,"Retrieved box contents");
                var at=origin.offset(6,3,2);((ButtonBlock)level.getBlockState(at).getBlock()).press(level.getBlockState(at),level,at,null);
                phase[0]=3;ticks[0]=0;
            }else if(phase[0]==3 && ticks[0]>100){
                h.assertValueEqual(((Container)level.getBlockEntity(origin.offset(3,3,0))).countItem(Items.SHULKER_BOX),1,"Empty request emitted a box");
                com.mojang.logging.LogUtils.getLogger().info("REQUEST_PASS sorted32, retrieved32, replaced box, empty request blocked");h.succeed();
            }
        });
    }
}
