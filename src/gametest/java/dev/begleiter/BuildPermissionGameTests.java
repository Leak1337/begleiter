package dev.begleiter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.begleiter.world.BuildPermission;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Weltänderungen nur, wo der Besitzer sie dürfte. Ein Schutz-Mod wird über das echte Fabric-Ereignis
 * {@link PlayerBlockBreakEvents#BEFORE} nachgestellt, das nur einzelne Positionen schützt.
 */
public class BuildPermissionGameTests implements ModInitializer {
    /** Geschützte Positionen (Dimension + Position); nur von diesen Tests befüllt. */
    static final Set<String> PROTECTED = ConcurrentHashMap.newKeySet();

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().identifier() + "@" + pos.asLong();
    }

    @Override
    public void onInitialize() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) ->
                !(level instanceof ServerLevel sl) || !PROTECTED.contains(key(sl, pos)));
    }

    private static final class OwnedCompanion extends CompanionEntity {
        ServerPlayer testOwner;
        OwnedCompanion(ServerLevel level) { super(CompanionMod.COMPANION, level); }
        @Override public ServerPlayer getOwnerAnywhere() { return testOwner; }
    }

    private static OwnedCompanion companion(GameTestHelper h, GameType mode) {
        var c = new OwnedCompanion(h.getLevel());
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        c.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
        c.stay();
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        c.testOwner = h.makeMockServerPlayerInLevel(); // mit Testverbindung, damit Spielmoduswechsel gesendet werden können
        c.testOwner.setGameMode(mode);
        c.setOwner(c.testOwner.getUUID());
        return c; // nicht gespawnt: keine parallelen KI-Ticks
    }

    @GameTest
    public void adventureOwnerBlocksBreakingAndPlacing(GameTestHelper h) {
        var c = companion(h, GameType.ADVENTURE);
        ServerLevel sl = h.getLevel();
        h.setBlock(3, 1, 2, Blocks.STONE);
        BlockPos stone = h.absolutePos(new BlockPos(3, 1, 2)), air = h.absolutePos(new BlockPos(1, 1, 2));
        h.assertTrue(!c.mineBlock(sl, stone, sl.getBlockState(stone)), "Mined in adventure mode");
        h.assertTrue(sl.getBlockState(stone).is(Blocks.STONE), "Stone removed in adventure mode");
        h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 0, "Loot booked although denied");
        h.assertTrue(!c.placeConfirmed(sl, air, Blocks.DIRT.defaultBlockState()), "Placed in adventure mode");
        h.assertTrue(sl.getBlockState(air).isAir(), "Block appeared in adventure mode");
        h.assertTrue(!new dev.begleiter.nav.NavContext(c).allowBreak, "Pathfinding plans breaking in adventure mode");
        h.assertTrue(c.edits.deniedChanges >= 2, "Denials not counted");

        c.testOwner.setGameMode(GameType.SURVIVAL);
        h.assertTrue(c.mineBlock(sl, stone, sl.getBlockState(stone)), "Mining denied in survival");
        h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 1, "Cobblestone once");
        h.assertTrue(c.placeConfirmed(sl, air, Blocks.DIRT.defaultBlockState()), "Placing denied in survival");
        h.assertTrue(new dev.begleiter.nav.NavContext(c).allowBreak, "Pathfinding forbids breaking in survival");
        h.succeed();
    }

    @GameTest
    public void spectatorOwnerStillLetsTheCompanionWork(GameTestHelper h) {
        var c = companion(h, GameType.SPECTATOR);
        BlockPos air = h.absolutePos(new BlockPos(1, 1, 2));
        h.assertTrue(c.placeConfirmed(h.getLevel(), air, Blocks.DIRT.defaultBlockState()), "Spectating owner stopped placing");
        h.succeed();
    }

    @GameTest
    public void protectedSpotInsideAnAllowedChunkStaysUntouched(GameTestHelper h) {
        var c = companion(h, GameType.SURVIVAL);
        ServerLevel sl = h.getLevel();
        h.setBlock(3, 1, 2, Blocks.STONE);
        h.setBlock(3, 1, 3, Blocks.STONE);
        BlockPos guarded = h.absolutePos(new BlockPos(3, 1, 2)), free = h.absolutePos(new BlockPos(3, 1, 3));
        PROTECTED.add(key(sl, guarded));
        try {
            h.assertTrue(!c.mineBlock(sl, guarded, sl.getBlockState(guarded)), "Mined a protected block");
            h.assertTrue(!c.worldBreak(sl, guarded, true), "Broke a protected block without loot");
            h.assertTrue(sl.getBlockState(guarded).is(Blocks.STONE), "Protected block changed");
            h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 0, "Loot from a protected block");
            h.assertTrue(c.mineBlock(sl, free, sl.getBlockState(free)), "Neighbour in the same chunk denied");
            h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 1, "Neighbour loot");
            // Rechte ändern sich: dieselbe Stelle wird wieder frei und ist sofort abbaubar
            PROTECTED.remove(key(sl, guarded));
            h.assertTrue(c.mineBlock(sl, guarded, sl.getBlockState(guarded)), "Released block still denied");
        } finally {
            PROTECTED.remove(key(sl, guarded));
        }
        h.succeed();
    }

    @GameTest
    public void buildHeightAndWorldBorderAlwaysApply(GameTestHelper h) {
        var c = companion(h, GameType.CREATIVE);
        ServerLevel sl = h.getLevel();
        BlockPos tooHigh = new BlockPos(c.getBlockX(), sl.getMaxY() + 1, c.getBlockZ());
        BlockPos outside = new BlockPos(30_000_100, 64, 0);
        h.assertTrue(!BuildPermission.mayPlace(c, sl, tooHigh), "Placing above build height allowed");
        h.assertTrue(!BuildPermission.mayPlace(c, sl, outside), "Placing outside the world border allowed");
        h.assertTrue(BuildPermission.mayPlace(c, sl, c.blockPosition()), "Placing at the companion denied");
        h.succeed();
    }
}
