package dev.begleiter.task;

import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionMemory;
import dev.begleiter.Resources;

/**
 * Bergbau-Wissen eines Begleiters, das bewusst über einzelne Aufgaben hinweg gilt: GATHER und MINE reihen sich nach jedem
 * Schritt neu ein, Erinnerungsreisen, versuchte und unerreichbare Erze dürfen dabei nicht verloren gehen (sonst Schleifen).
 * Alles, was nur zu einer laufenden Aufgabe gehört, lebt im jeweiligen Handler.
 */
public final class MiningKnowledge {
    private static final Resources.Res[] BONUS_ORES = {Resources.COAL, Resources.IRON, Resources.COPPER, Resources.GOLD,
            Resources.REDSTONE, Resources.LAPIS, Resources.DIAMOND, Resources.EMERALD};
    private static final int ORE_BLACKLIST_TICKS = 20 * 60 * 10;

    private final CompanionEntity mob;
    /** Schon eingeplante/versuchte Erze im Vorbeigehen (nicht doppelt, keine Schleife bei unerreichbaren). */
    private final Set<BlockPos> oresTried = new HashSet<>();
    /** Erze, an die er nicht herankam: so lange nicht wieder ansteuern. */
    private final Map<BlockPos, Integer> oreBlacklist = new HashMap<>();
    /** Zuletzt aus dem Gedächtnis angesteuerter Ort (zweimal ohne Erfolg dort = vergessen). */
    private BlockPos memoryTrip;
    private int oreVisits;
    /** MINE wurde auf den Weg in die gemerkte Mine geschickt (beim nächsten MINE prüfen, ob er ankam). */
    boolean mineTrip;
    /** Holz für den Abstieg schon einmal geholt (nicht endlos, falls kein Baum in der Nähe ist). */
    boolean woodForMineTried;

    public MiningKnowledge(CompanionEntity mob) {
        this.mob = mob;
    }

    /**
     * Wie ein Spieler: Erze, die er beim Graben ohnehin sieht (freiliegend, in 5 Blöcken, mit passendem Werkzeug),
     * nimmt er mit – auch wenn er gerade etwas anderes sucht. Höchstens 4 auf einmal.
     */
    public List<Task> bonusOres(ServerLevel sl, Resources.Res except) {
        return bonusOres(sl, except, Double.MAX_VALUE);
    }

    /** maxEyeDistSqr: nur Erze in dieser Entfernung von den Augen (Reichweite = abbauen ohne hinzulaufen). */
    public List<Task> bonusOres(ServerLevel sl, Resources.Res except, double maxEyeDistSqr) {
        if (oresTried.size() > 300) oresTried.clear();
        List<Task> out = new ArrayList<>();
        BlockPos c = mob.blockPosition();
        Vec3 eye = mob.getEyePosition();
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-5, -3, -5), c.offset(5, 4, 5))) {
            if (out.size() >= 4) break;
            BlockState s = sl.getBlockState(q);
            if (!s.is(BlockTags.ORES) || eye.distanceToSqr(Vec3.atCenterOf(q)) > maxEyeDistSqr) continue;
            Resources.Res res = null;
            for (Resources.Res r : BONUS_ORES) if (r.block().test(s)) { res = r; break; }
            if (res == null || res == except || !mob.hasCorrectToolFor(s) || plentyOf(res)) continue;
            // nicht für Nebenbei-Erze nach oben klettern, wenn er eigentlich tiefer will
            if (except != null && q.getY() > c.getY() + 2) continue;
            BlockPos p = q.immutable();
            if (oresTried.contains(p) || mob.hazardAround(sl, p, false) || CompanionEntity.deepWater(sl, p) || !mob.seesBlock(sl, p)) continue;
            oresTried.add(p);
            out.add(Task.breakAt(p, s.getBlock()));
        }
        if (!out.isEmpty()) mob.logInfo(EventKind.LOOT, CompanionTexts.tr("log.memory.pass_ore", out.size()));
        return out;
    }

    /** Genug davon dabei: im Vorbeigehen nicht mehr mitnehmen (sonst baut er stundenlang Kohleadern ab). */
    private boolean plentyOf(Resources.Res res) {
        if (res == Resources.COAL) return CompanionCraft.count(mob, "coal") >= 32;
        if (res == Resources.COPPER) return CompanionCraft.count(mob, "raw_copper") + CompanionCraft.count(mob, "copper_ingot") >= 16;
        if (res == Resources.IRON) return CompanionCraft.count(mob, "raw_iron") + CompanionCraft.count(mob, "iron_ingot") >= 32;
        if (res == Resources.REDSTONE) return CompanionCraft.count(mob, "redstone") >= 32;
        if (res == Resources.LAPIS) return CompanionCraft.count(mob, "lapis_lazuli") >= 32;
        return false; // Gold, Diamant, Smaragd immer
    }

    /**
     * Gemerkten Ort ansteuern (Reise + hingehen), danach {@code again} erneut versuchen; true = Warteschlange ersetzt.
     * War er schon dort oder steht direkt daneben und es hat trotzdem nicht geklappt, stimmt die Erinnerung nicht mehr:
     * vergessen, nächsten nehmen.
     */
    public boolean goRemembered(ServerLevel sl, CompanionMemory.Kind kind, String key, int maxDist, Task again) {
        for (int guard = 0; guard < 8; guard++) {
            CompanionMemory.Place p = mob.memory.nearest(kind, key, sl, mob.blockPosition(), maxDist);
            if (p == null) return false;
            if (p.pos.equals(memoryTrip) || p.pos.distSqr(mob.blockPosition()) <= 100) {
                mob.logInfo(EventKind.MEMORY, CompanionTexts.tr("log.memory.invalid", CompanionTexts.tr("memory.kind." + kind.name().toLowerCase(java.util.Locale.ROOT)), p.pos.toShortString()));
                mob.memory.reject(p, sl);
                memoryTrip = null;
                continue;
            }
            memoryTrip = p.pos;
            Component what = kind == CompanionMemory.Kind.ORE ? CompanionTexts.resource(p.key) : CompanionTexts.tr("memory.kind." + kind.name().toLowerCase(java.util.Locale.ROOT));
            mob.logInfo(EventKind.MEMORY, CompanionTexts.tr("log.memory.remembered", what, CompanionTexts.location(p, mob.blockPosition())));
            List<Task> next = new ArrayList<>();
            if (p.pos.distSqr(mob.blockPosition()) > 24 * 24) next.add(Task.travel(p.pos));
            next.add(Task.moveTo(p.pos, 16));
            next.add(again);
            mob.replaceCurrent(next);
            return true;
        }
        return false;
    }

    /**
     * Bekanntes Erz (gesehen oder per Erz-Gespür) gezielt abbauen: nah genug -> die Blöcke der Ader als BREAK-Aufgaben
     * (der Weg dorthin wird bei Bedarf gegraben), weit weg -> erst hinreisen. Danach {@code again} für den Rest.
     * true = Warteschlange ersetzt.
     */
    boolean mineKnownOre(ServerLevel sl, Resources.Res res, int want, Task again, boolean allowDive) {
        for (int guard = 0; guard < 8; guard++) {
            CompanionMemory.Place p = mob.memory.nearest(CompanionMemory.Kind.ORE, res.key(), sl, mob.blockPosition(), 160);
            if (p == null) return false;
            if (p.pos.distSqr(mob.blockPosition()) > 40 * 40) return goRemembered(sl, CompanionMemory.Kind.ORE, res.key(), 160, again);
            List<BlockPos> vein = new ArrayList<>();
            for (BlockPos q : BlockPos.betweenClosed(p.pos.offset(-2, -2, -2), p.pos.offset(2, 2, 2)))
                if (res.block().test(sl.getBlockState(q)) && !mob.hazardAround(sl, q, sl.getBlockState(q.above()).is(Blocks.WATER))
                        && (allowDive || !CompanionEntity.deepWater(sl, q)))
                    vein.add(q.immutable());
            boolean again2 = p.pos.equals(memoryTrip);
            if (vein.isEmpty() || (again2 && (oreVisits >= 3 || vein.stream().allMatch(q -> q.distSqr(mob.blockPosition()) > 36)))) {
                mob.memory.forget(p); // nicht (mehr) da oder nicht erreichbar
                memoryTrip = null;
                oreVisits = 0;
                continue;
            }
            oreVisits = again2 ? oreVisits + 1 : 0;
            memoryTrip = p.pos;
            vein.sort(Comparator.comparingDouble(q -> q.distSqr(mob.blockPosition())));
            List<Task> next = new ArrayList<>();
            for (int i = 0; i < Math.min(vein.size(), want); i++) next.add(Task.breakAt(vein.get(i), sl.getBlockState(vein.get(i)).getBlock()));
            if (again.spec() instanceof TaskSpec.ResourceGoal) next.add(again);
            mob.logInfo(EventKind.LOOT, CompanionTexts.tr("log.memory.ore", CompanionTexts.resource(res.key()), CompanionTexts.location(p, mob.blockPosition())));
            mob.replaceCurrent(next);
            return true;
        }
        return false;
    }

    /** Erze ohne Sperre (unerreichbar markierte bleiben eine Weile außen vor). */
    boolean blacklisted(BlockPos p) {
        oreBlacklist.values().removeIf(until -> until < mob.tickCount);
        return oreBlacklist.containsKey(p);
    }

    public void markUnreachable(List<BlockPos> ores) {
        for (BlockPos p : ores) oreBlacklist.put(p, mob.tickCount + ORE_BLACKLIST_TICKS);
    }
}
