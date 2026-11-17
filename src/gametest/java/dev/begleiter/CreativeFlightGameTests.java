package dev.begleiter;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Kreativ-Bau in großer Höhe (wie ein Mob-Turm): Schacht + Plattform nur fliegend erreichbar, danach landen. */
public class CreativeFlightGameTests {
    private static final String TALL = "begleiter-gametest:tall";

    @GameTest(maxTicks = 6000, structure = TALL)
    public void kreativBautSchachtUndPlattformInDerLuftUndLandetDanach(GameTestHelper h) {
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) h.setBlock(x, 0, z, Blocks.SMOOTH_STONE);
        var bot = h.spawn(CompanionMod.COMPANION, new BlockPos(4, 1, 4));
        bot.setPersistenceRequired();
        bot.buildModeOverride = true;
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        BlockState stone = Blocks.SMOOTH_STONE.defaultBlockState();
        // Schacht 4×4 (innen 2×2 frei) bis Höhe 16, darauf eine 11×11-Plattform mit Fackeln an den Ecken
        for (int y = 1; y <= 16; y++)
            for (int x = 18; x <= 21; x++) for (int z = 18; z <= 21; z++)
                if (x == 18 || x == 21 || z == 18 || z == 21) plan.put(h.absolutePos(new BlockPos(x, y, z)), stone);
        for (int x = 14; x <= 24; x++) for (int z = 14; z <= 24; z++)
            if (x < 19 || x > 20 || z < 19 || z > 20) plan.put(h.absolutePos(new BlockPos(x, 17, z)), stone);
        for (int[] c : new int[][]{{14, 14}, {24, 14}, {14, 24}, {24, 24}})
            plan.put(h.absolutePos(new BlockPos(c[0], 18, c[1])), Blocks.TORCH.defaultBlockState());
        bot.build = CompanionBuilder.create(h.getLevel(), "Turm", plan);
        bot.addTask(CompanionEntity.Task.build());
        h.onEachTick(() -> {
            h.assertTrue(bot.isAlive(), "Begleiter gestorben (Absturz?)");
            if (bot.build != null) return;
            for (var en : plan.entrySet())
                h.assertTrue(CompanionBuilder.matches(h.getLevel().getBlockState(en.getKey()), en.getValue()),
                        "falsch gebaut bei " + h.relativePos(en.getKey()).toShortString());
            // gelandet: wieder mit Schwerkraft auf festem Boden (Plattform oder Gelände), nicht schwebend
            if (bot.onGround() && !bot.isNoGravity()) h.succeed();
        });
    }

    @GameTest(maxTicks = 400, structure = TALL)
    public void schwebenderBegleiterLandetOhneBauauftragSanft(GameTestHelper h) {
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) h.setBlock(x, 0, z, Blocks.SMOOTH_STONE);
        var bot = h.spawn(CompanionMod.COMPANION, new BlockPos(20, 30, 20));
        bot.setPersistenceRequired();
        bot.setNoGravity(true); // wie nach einem Neustart mitten im Flug
        float health = bot.getHealth();
        h.onEachTick(() -> {
            if (!bot.onGround() || bot.isNoGravity()) return;
            h.assertTrue(bot.getHealth() >= health, "Fallschaden beim Landen");
            h.succeed();
        });
    }
}
