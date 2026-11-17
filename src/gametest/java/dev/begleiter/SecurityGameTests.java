package dev.begleiter;

import dev.begleiter.nav.RouteMemory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** Regressionstests für nicht vertrauenswürdige Schematics und gespeicherten NPC-Zustand. */
public class SecurityGameTests {
    @GameTest
    public void riesigesSpongeSchematicWirdVorParsingAbgelehnt(GameTestHelper h) {
        CompoundTag data = new CompoundTag();
        data.putShort("Width", (short) 1000);
        data.putShort("Height", (short) 1000);
        data.putShort("Length", (short) 1000);
        boolean rejected = false;
        try {
            CompanionSchematics.loadSponge(null, "too-large", data);
        } catch (IllegalArgumentException ex) {
            rejected = true;
        }
        h.assertTrue(rejected, "Riesiges Sponge-Schematic darf nicht eingelesen werden");
        h.succeed();
    }

    @GameTest
    public void litematicRegionWirdVorBlockZuweisungAbgelehnt(GameTestHelper h) {
        CompoundTag root = new CompoundTag();
        CompoundTag regions = new CompoundTag();
        CompoundTag region = new CompoundTag();
        CompoundTag size = new CompoundTag();
        size.putInt("x", 4000);
        size.putInt("y", 4000);
        size.putInt("z", 4000);
        region.put("Size", size);
        regions.put("huge", region);
        root.put("Regions", regions);
        boolean rejected = false;
        try {
            CompanionSchematics.loadLitematic(null, "too-large", root);
        } catch (IllegalArgumentException ex) {
            rejected = true;
        }
        h.assertTrue(rejected, "Riesige Litematic-Region darf nicht allokiert werden");
        h.succeed();
    }

    @GameTest
    public void fremdeOrtsdatenKoennenLimitsNichtUmgehen(GameTestHelper h) {
        CompoundTag root = new CompoundTag();
        ListTag places = new ListTag();
        for (int i = 0; i < 240; i++) {
            CompoundTag row = new CompoundTag();
            row.putString("K", "TREE");
            row.putString("D", "minecraft:overworld");
            row.putLong("P", new BlockPos(i, 65, 0).asLong());
            row.putInt("N", 999999);
            places.add(row);
        }
        root.put("Places", places);
        CompanionMemory memory = new CompanionMemory();
        memory.load(root);
        h.assertValueEqual(memory.size(), 160, "Ortsgedaechtnis begrenzt nicht pro Art");
        h.assertValueEqual(memory.all().getFirst().count, 64, "Fehlerhafter Ressourcen-Zaehler nicht begrenzt");
        h.succeed();
    }

    @GameTest
    public void kistenMetadatenWerdenBeimLadenBegrenzt(GameTestHelper h) {
        CompoundTag root = new CompoundTag();
        ListTag places = new ListTag();
        CompoundTag row = new CompoundTag();
        row.putString("K", "CHEST");
        row.putString("D", "minecraft:overworld");
        row.putLong("P", new BlockPos(1, 64, 1).asLong());
        CompoundTag items = new CompoundTag();
        for (int i = 0; i < 180; i++) items.putInt("test:item_" + i, 999999);
        row.put("Items", items);
        places.add(row);
        root.put("Places", places);
        CompanionMemory memory = new CompanionMemory();
        memory.load(root);
        h.assertValueEqual(memory.all().getFirst().items.size(), 128, "Kistenmetadata nicht begrenzt");
        h.assertTrue(memory.all().getFirst().items.values().stream().allMatch(n -> n <= 4096),
                "Fehlerhafte Inventar-Zaehler nicht begrenzt");
        h.succeed();
    }

    @GameTest
    public void trittsiegelLimitAuchBeiManipuliertemSave(GameTestHelper h) {
        CompoundTag tag = new CompoundTag();
        long[] coords = new long[5000];
        int[] counts = new int[5000];
        for (int i = 0; i < coords.length; i++) {
            coords[i] = new BlockPos(i, 64, 0).asLong();
            counts[i] = 9999;
        }
        tag.putLongArray("Pos", coords);
        tag.putIntArray("N", counts);
        RouteMemory memory = new RouteMemory();
        memory.load(tag);
        h.assertValueEqual(memory.size(), 4096, "Trampelpfadlimit beim Laden umgangen");
        h.assertValueEqual(memory.count(new BlockPos(0, 64, 0)), 100,
                "Manipulierter Zaehler darf nicht unbeschraenkt geladen werden");
        h.succeed();
    }
    @GameTest
    public void spielerOhneAdminrechteDarfKeineParkourArenaLoeschen(GameTestHelper h) {
        var player = h.makeMockServerPlayerInLevel();
        String message = ParkourCourse.build(player);
        h.assertTrue(message.startsWith("Nur Administratoren"),
                "Parkour-Arena wurde ohne Adminrechte gebaut");
        h.succeed();
    }

}
