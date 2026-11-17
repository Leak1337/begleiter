package dev.begleiter;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public class BuildPersistenceGameTests {
    private static CompoundTag save(GameTestHelper h, CompanionBuilder.BuildJob job) {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        CompanionBuilder.save(out, job);
        return out.buildResult();
    }

    private static CompanionBuilder.BuildJob load(GameTestHelper h, CompoundTag tag) {
        return CompanionBuilder.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
    }

    private static CompanionBuilder.BuildJob wide() {
        return new CompanionBuilder.BuildJob("wide", List.of(
                new CompanionBuilder.Placement(new BlockPos(-5000, -64, 5000), Blocks.STONE.defaultBlockState()),
                new CompanionBuilder.Placement(new BlockPos(5000, 319, -5000), Blocks.OAK_STAIRS.defaultBlockState()),
                new CompanionBuilder.Placement(new BlockPos(29_999_000, 100, -29_999_000), Blocks.AIR.defaultBlockState())));
    }

    @GameTest
    public void build_persistence_wide_coordinates_and_progress(GameTestHelper h) {
        var job = wide();
        job.index = 2;
        job.repairRounds = 3;
        job.paused = true;
        job.terrainOnly = true;
        job.inDimension(h.getLevel());
        var tag = save(h, job);
        var data = tag.getCompoundOrEmpty("Build");
        h.assertTrue(data.getLongArray("PosL").isPresent(), "Positionen fehlen als Long-Array");
        h.assertTrue(!data.contains("Pos"), "Neuer Spielstand verwendet noch relative Positionen");
        var restored = load(h, tag);
        h.assertTrue(restored != null, "Weit auseinanderliegender Auftrag verloren");
        h.assertValueEqual(restored.order, job.order, "Koordinaten, Zustände oder Reihenfolge verändert");
        h.assertValueEqual(restored.index, 2, "Fortschritt verloren");
        h.assertValueEqual(restored.repairRounds, 3, "Reparaturfortschritt verloren");
        h.assertTrue(restored.paused && restored.terrainOnly && restored.inDimension(h.getLevel()), "Auftragsschalter verloren");
        h.assertValueEqual(restored.placed, job.order.subList(0, 2).stream().map(CompanionBuilder.Placement::pos).toList(),
                "Bereits gebaute Positionen nicht wiederhergestellt");
        h.succeed();
    }

    private static CompoundTag legacy(GameTestHelper h) {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        var build = out.child("Build");
        build.putString("Name", "legacy");
        build.store("Origin", BlockPos.CODEC, new BlockPos(-5000, 70, 5000));
        build.store("Palette", BlockState.CODEC.listOf(), List.of(Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState()));
        build.putIntArray("Pos", new int[] {1024 << 21 | 512 << 11 | 1024, 0 << 21 | 511 << 11 | 2047});
        build.putIntArray("State", new int[] {0, 1});
        build.putInt("Index", 1);
        return out.buildResult();
    }

    @GameTest
    public void build_persistence_legacy_migrates(GameTestHelper h) {
        var job = load(h, legacy(h));
        h.assertTrue(job != null, "Alter Auftrag nicht lesbar");
        h.assertValueEqual(job.order.get(0).pos(), new BlockPos(-5000, 70, 5000), "Alter Ursprung verändert");
        h.assertValueEqual(job.order.get(1).pos(), new BlockPos(-6024, 69, 6023), "Alte relative Position falsch");
        h.assertValueEqual(job.order.get(1).state(), Blocks.GLASS.defaultBlockState(), "Palette verändert");
        h.assertValueEqual(job.index, 1, "Alter Fortschritt verloren");
        h.assertValueEqual(load(h, save(h, job)).order, job.order, "Migration verändert Auftrag");
        h.succeed();
    }

    @GameTest
    public void build_persistence_new_coordinates_take_precedence(GameTestHelper h) {
        var tag = save(h, wide());
        tag.getCompoundOrEmpty("Build").putIntArray("Pos", new int[] {0});
        h.assertValueEqual(load(h, tag).order, wide().order, "Altdaten überschreiben PosL");
        h.succeed();
    }

    @GameTest
    public void build_persistence_rejects_corrupt_arrays(GameTestHelper h) {
        var tag = legacy(h);
        tag.getCompoundOrEmpty("Build").putString("PosL", "broken");
        h.assertTrue(load(h, tag) == null, "Beschädigtes PosL fällt unbemerkt auf Altdaten zurück");
        tag = save(h, wide());
        tag.getCompoundOrEmpty("Build").putLongArray("PosL", new long[] {0});
        h.assertTrue(load(h, tag) == null, "Unterschiedliche Arraylängen akzeptiert");
        tag = save(h, wide());
        tag.getCompoundOrEmpty("Build").putIntArray("State", new int[] {0, -1, 0});
        h.assertTrue(load(h, tag) == null, "Ungültige Palette akzeptiert");
        tag = save(h, wide());
        tag.getCompoundOrEmpty("Build").putLongArray("PosL", new long[0]);
        h.assertTrue(load(h, tag) == null, "Leere Positionen akzeptiert");
        h.succeed();
    }

    @GameTest
    public void build_persistence_progress_is_clamped(GameTestHelper h) {
        var tag = save(h, wide());
        tag.getCompoundOrEmpty("Build").putInt("Index", Integer.MAX_VALUE);
        h.assertValueEqual(load(h, tag).index, 3, "Fortschritt überschreitet Auftrag");
        tag.getCompoundOrEmpty("Build").putInt("Index", -1);
        h.assertValueEqual(load(h, tag).index, 0, "Negativer Fortschritt");
        h.succeed();
    }

    @GameTest
    public void build_persistence_entity_roundtrip(GameTestHelper h) {
        var c = new CompanionEntity(CompanionMod.COMPANION, h.getLevel());
        c.build = wide();
        c.build.index = 1;
        c.build.paused = true;
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        var restored = new CompanionEntity(CompanionMod.COMPANION, h.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(restored.build != null, "Entity verliert Auftrag");
        h.assertValueEqual(restored.build.order, c.build.order, "Entity verändert Auftrag");
        h.assertValueEqual(restored.build.index, 1, "Entity verliert Fortschritt");
        h.assertTrue(restored.build.paused, "Entity verliert Baupause");
        h.succeed();
    }
}
