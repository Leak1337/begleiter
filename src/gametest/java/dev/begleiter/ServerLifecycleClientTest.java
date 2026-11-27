package dev.begleiter;

import java.lang.reflect.Field;
import java.util.Map;

import dev.begleiter.debug.BotDebugServer;
import dev.begleiter.debug.BotDebugConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Zwei echte Welt-Lebenszyklen; Fixtures greifen nur im Test auf private Cache-Felder zu. */
public final class ServerLifecycleClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        // Keine frischen HUD-Abonnements beim Betreten der nächsten Welt erzeugen.
        context.runOnClient(client -> {
            var config = BotDebugConfig.get();
            config.hud = false;
            config.esp = BotDebugConfig.Esp.OFF;
            config.lines = false;
            config.nameTags = false;
            config.path = false;
        });
        for (int round = 0; round < 2; round++) {
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                world.getServer().runOnServer(server -> {
                    if (!cleared()) throw new AssertionError("Alte Serverdaten vor Weltstart vorhanden");
                    populate(server);
                });
            }
            context.waitFor(client -> cleared());
        }
        // Mehrfaches Aufräumen nach dem Stopp darf keine erneute Initialisierung auslösen.
        for (int attempt = 0; attempt < 2; attempt++) {
            CompanionRecipes.reset();
            CompanionPlanner.reset();
            ParkourCourse.reset();
            BotDebugServer.reset();
        }
        if (!cleared()) throw new AssertionError("Reset ist nicht wiederholbar");
    }

    private static void populate(MinecraftServer server) {
        var player = server.getPlayerList().getPlayers().getFirst();
        var level = player.level();
        CompanionEntity c = CompanionMod.COMPANION.create(level, EntitySpawnReason.COMMAND);
        if (c == null) throw new AssertionError("Testbegleiter fehlt");
        c.setOwner(player.getUUID());
        c.snapTo(player.getX(), player.getY(), player.getZ(), 0, 0);
        c.stay();
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 16));
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 6));
        c.giveToInventory(new ItemStack(Items.STICK, 4));
        if (CompanionRecipes.craftsFor(level, Items.STICK).isEmpty()
                || CompanionRecipes.smeltsFor(level, Items.IRON_INGOT).isEmpty())
            throw new AssertionError("Rezeptcache wurde nicht aufgebaut");
        // Der Planer hält keinen statischen Zustand mehr (PlanContext je Aufruf, siehe PlanContextGameTests);
        // hier nur prüfen, dass er nach dem Rezeptaufbau weiter plant.
        if (CompanionPlanner.obtain(c, Items.IRON_PICKAXE, 1) == null
                || CompanionPlanner.obtainPrepared(c, Items.IRON_PICKAXE, 1, CompanionPlanner.RoutePolicy.ANY) == null)
            throw new AssertionError("Planer plant nicht");

        // Kein Arena-Weltumbau: nur die Referenzen einer noch wartenden Vorführung vorbereiten.
        set(ParkourCourse.class, "origin", player.blockPosition());
        set(ParkourCourse.class, "originLevel", level);
        ParkourCourse.startShow(player, c);
        Object show = get(ParkourCourse.class, "show");
        set(show, "pause", Integer.MAX_VALUE);
        set(ParkourCourse.class, "lastShowOk", 7);

        Object sub = construct("dev.begleiter.debug.BotDebugServer$Sub");
        map(BotDebugServer.class, "SUBS").put(player.getUUID(), sub);
        if (get(CompanionRecipes.class, "builtFor") == null
                || get(ParkourCourse.class, "originLevel") != level
                || map(BotDebugServer.class, "SUBS").isEmpty())
            throw new AssertionError("Lebenszyklus-Fixture wurde nicht aufgebaut");
    }

    private static boolean cleared() {
        return get(CompanionRecipes.class, "builtFor") == null
                && get(CompanionRecipes.class, "crafts") == null
                && get(CompanionRecipes.class, "smelts") == null
                && get(CompanionRecipes.class, "smeltsAll") == null
                && get(ParkourCourse.class, "origin") == null
                && get(ParkourCourse.class, "originLevel") == null
                && get(ParkourCourse.class, "show") == null
                && ParkourCourse.lastShowOk() == -1
                && map(BotDebugServer.class, "SUBS").isEmpty();
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Testfeld nicht erreichbar: " + name, ex);
        }
    }

    private static Object get(Class<?> type, String name) {
        try { return field(type, name).get(null); }
        catch (IllegalAccessException ex) { throw new AssertionError(ex); }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Class<?> type = target instanceof Class<?> c ? c : target.getClass();
            field(type, name).set(target instanceof Class<?> ? null : target, value);
        } catch (IllegalAccessException ex) { throw new AssertionError(ex); }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Class<?> type, String name) {
        return (Map<Object, Object>) get(type, name);
    }

    private static Object construct(String type) {
        try {
            var constructor = Class.forName(type).getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
}
