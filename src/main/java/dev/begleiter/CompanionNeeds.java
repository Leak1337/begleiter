package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import dev.begleiter.CompanionEntity.Task;

/**
 * Bedürfnisse statt fester Checkliste: jedes Bedürfnis bekommt laufend eine Dringlichkeit (0–100) mit Begründung,
 * das dringendste wird zum aktuellen Ziel. Das laufende Ziel bekommt einen kleinen Bonus (kein Hin- und
 * Herspringen). Überleben, akuter Hunger und kaputtes Werkzeug dürfen eine laufende Planer-Aufgabe unterbrechen;
 * Befehle des Spielers werden nie unterbrochen.
 */
public final class CompanionNeeds {
    private CompanionNeeds() {}

    public enum Need { SURVIVAL, HUNGER, ORDER, GEAR, BASE, PROGRESS, CARE, SUPPLY }

    public record Score(Need need, int value, Component reasonText) {
        public Score(Need need, int value, String reason) { this(need, value, CompanionTexts.literal(reason)); }
        public String reason() { return reasonText.getString(); }
    }

    /** Bonus für das laufende Ziel. */
    public static final int STICKY = 10;
    /** Diese Bedürfnisse dürfen laufende Planer-Aufgaben unterbrechen. */
    static final Set<Need> INTERRUPTS = Set.of(Need.SURVIVAL, Need.HUNGER, Need.GEAR);
    static final int INTERRUPT_MIN = 70, INTERRUPT_GAP = 25;

    // ------------------------------------------------------------------ Bewerten

    /** Alle Bedürfnisse mit Dringlichkeit > 0, dringendstes zuerst. */
    public static List<Score> evaluate(CompanionEntity e) {
        List<Score> out = new ArrayList<>();
        for (Need n : Need.values()) {
            Score s = score(e, n);
            if (s != null && s.value() > 0) out.add(s);
        }
        out.sort(Comparator.comparingInt((Score s) -> -s.value()));
        return out;
    }

    static Score score(CompanionEntity e, Need n) {
        return switch (n) {
            case SURVIVAL -> survival(e);
            case HUNGER -> hunger(e);
            case ORDER -> order(e);
            case GEAR -> gear(e);
            case BASE -> base(e);
            case PROGRESS -> progress(e);
            case CARE -> care(e);
            case SUPPLY -> supply(e);
        };
    }

    private static Score s(Need n, int v, String reason, Object... args) {
        return new Score(n, Math.max(0, Math.min(100, v)), CompanionTexts.tr("planner.reason." + reason, args));
    }

    private static Score survival(CompanionEntity e) {
        float hp = e.getHealth() / e.getMaxHealth();
        if (e.isSheltered()) return s(Need.SURVIVAL, 97, "shelter"); // vor allem anderen, auch "keine Spitzhacke" (96)
        // Unter Beschuss zählt ein gerade (wieder) gesetztes Ziel nicht: dann erst recht in Sicherheit bringen
        if (hp < 0.4f && (e.getTarget() == null || e.inDanger()))
            return s(Need.SURVIVAL, 75 + (int) ((0.4f - hp) * 60), "health", (int) e.getHealth());
        if (!(e.level() instanceof ServerLevel sl)) return null;
        // Nachts ohne heiles Haus in der Nähe: Nachtlager statt weiterzulaufen (wie beim Spielziel)
        if (!(CompanionBrain.wantsPearlHunt(e) && sl.isDarkOutside()) && CompanionBrain.needsNightCamp(e, sl))
            return s(Need.SURVIVAL, 86, "night");
        BlockPos inside = e.houseInside();
        if (inside != null && !(CompanionBrain.wantsPearlHunt(e) && sl.isDarkOutside())) {
            boolean night = sl.isDarkOutside();
            boolean underground = !sl.canSeeSky(e.blockPosition()) && e.blockPosition().getY() < inside.getY() - 6;
            if (night && !underground) return s(Need.SURVIVAL, 88, "night");
            if (!night && e.doorClosed) return s(Need.SURVIVAL, 60, "door_closed");
        }
        return null;
    }

    private static Score hunger(CompanionEntity e) {
        int food = e.foodCount();
        if (e.food.level() <= 12 && food < 4) return s(Need.HUNGER, 95 - e.food.level() * 2, "hunger", e.food.level(), food);
        if (CompanionCraft.rawFood(e) >= 3 && CompanionCraft.canSmelt(e)) return s(Need.HUNGER, 42, "raw_food");
        if (food < 4 && CompanionCraft.tier(e, "pickaxe") >= 2 && e.hasFoodAnimalNearby())
            return s(Need.HUNGER, 64, "low_food", food);
        return null;
    }

    /** Inventar fast voll (Müll zum Wegwerfen zählt nicht), es gibt etwas einzulagern, und das Lager war nicht gerade voll. */
    private static boolean wantsDeliver(CompanionEntity e) {
        return e.usedSlotsWithoutClutter() >= 28 && e.getHome() != null && e.depositableSlots() >= 4 && e.tickCount >= e.storageFullUntil;
    }

    private static Score order(CompanionEntity e) {
        if (e.hasScaffold()) return s(Need.ORDER, 76, "scaffold");
        if (e.pickup.looseItemsNearby()) return s(Need.ORDER, 70, "loose_items");
        if (wantsDeliver(e)) {
            int used = e.usedSlotsWithoutClutter();
            return s(Need.ORDER, 58 + (used - 28) * 4, "inventory", used);
        }
        return null;
    }

    private static Score gear(CompanionEntity e) {
        int pick = CompanionCraft.tier(e, "pickaxe");
        if (pick == 0) return s(Need.GEAR, 96, "no_pickaxe");
        if (pick < 2) return s(Need.GEAR, 82, "wooden_tools");
        ItemStack worn = wornTool(e);
        if (worn != null) return s(Need.GEAR, 74, "worn", worn.getHoverName());
        if (!e.isHouseBuilt() || e.getHome() == null) return null; // erst Basis
        if (wantsCopper(e)) return s(Need.GEAR, 58, "copper");
        if (pick < 3) return s(Need.GEAR, 56, "iron_tools");
        if (armorIngotsNeeded(e) > 0) return s(Need.GEAR, 52, "armor");
        if (diamondsNeeded(e) > 0) return s(Need.GEAR, 46, "diamond_tools");
        if (rangedCraftable(e)) return s(Need.GEAR, 40, "ranged");
        // Stirbt er im Nether wiederholt, vor dem nächsten Ausflug die volle Diamantausrüstung (sonst vor dem End)
        if (CompanionBrain.needsBlaze(e) && CompanionBrain.deathsIn(e, net.minecraft.world.level.Level.NETHER) >= 1
                && !fullDiamondGear(e) && !e.craftBlocked(DIAMOND_GEAR)) return s(Need.GEAR, 44, "nether_deaths");
        return null;
    }

    /** Bogen oder Pfeile lassen sich aus mitgeführtem Material herstellen. */
    static boolean rangedCraftable(CompanionEntity e) {
        if (!CompanionCraft.owns(e, "bow")) return CompanionCraft.count(e, "string") >= 3 && !e.craftBlocked("bow");
        return CompanionCraft.count(e, "arrow") < CompanionBrain.ARROWS && CompanionCraft.count(e, "flint") >= 1
                && CompanionCraft.count(e, "feather") >= 1 && !e.craftBlocked("arrow");
    }

    private static Score base(CompanionEntity e) {
        if (CompanionCraft.tier(e, "pickaxe") < 2) return null;
        if (!e.isHouseBuilt()) return s(Need.BASE, 62, "no_house");
        if (e.level() instanceof ServerLevel damagedAt && e.houseDamaged(damagedAt)) return s(Need.BASE, 60, "no_house");
        if (e.getHome() == null) return s(Need.BASE, 59, "no_storage");
        if (!e.doorClosed && e.houseNeedsDoor() && !e.craftBlocked("#door")) return s(Need.BASE, 50, "no_door");
        if (e.level() instanceof ServerLevel sl) {
            // Ausbau: dunkle Stellen ums Haus ausleuchten (gegen Monster-Spawns), dann ein eigenes Feld anlegen
            int dark = e.initiative.base.lightingWanted(sl);
            if (dark > 0) return s(Need.BASE, 34, "dark_spots", dark);
            if (e.initiative.base.wantsField(sl)) return s(Need.BASE, 32, "no_field");
        }
        return null;
    }

    private static Score progress(CompanionEntity e) {
        if (!e.isHouseBuilt() || e.getHome() == null || CompanionCraft.tier(e, "pickaxe") < 4) return null;
        if (e.getPortalPos() == null) return s(Need.PROGRESS, 40, "nether_portal");
        if (CompanionBrain.needsBlaze(e)) return s(Need.PROGRESS, 40, "blaze_rods");
        if (CompanionBrain.wantsPearlHunt(e))
            return e.level().isDarkOutside() ? s(Need.PROGRESS, 45, "endermen") : null;
        if (e.endPortalPos == null && CompanionBrain.pearlsAndEyes(e) >= CompanionBrain.PEARLS)
            return s(Need.PROGRESS, 42, "stronghold");
        if (e.endPortalPos != null && !CompanionBrain.dragonDefeated(e) && !readyForDragon(e))
            return s(Need.PROGRESS, 38, "dragon_gear");
        // Nach dem Drachen: komplette Diamantausrüstung als nächstes großes Ziel
        if (postDragonGear(e)) return s(Need.PROGRESS, 36, "post_dragon_gear", CompanionJobs.gearProgress(e, 4));
        return null;
    }

    /** Ab so vielen reifen Pflanzen lohnt sich ein Erntegang. */
    static final int HARVEST_MIN = 6;

    /** Reife Pflanzen am eigenen Feld (Lager/Haus), höchstens alle 10 s neu gezählt. */
    static int matureCrops(CompanionEntity e) {
        if (!(e.level() instanceof ServerLevel sl)) return 0;
        BlockPos center = e.getHome() != null ? e.getHome() : e.getHouseOrigin();
        if (center == null || center.distSqr(e.blockPosition()) > 48 * 48) return 0;
        if (e.tickCount - e.careScanTick >= 200) {
            e.careScanTick = e.tickCount;
            e.careMatureCrops = CompanionJobs.countMature(sl, center, CompanionJobs.FARM_RADIUS);
        }
        return e.careMatureCrops;
    }

    /** Art, die er gerade züchten könnte (Herde am Lager, Futter dabei, noch nicht zu viele), sonst null. */
    static String breedable(CompanionEntity e) {
        if (!e.herdKeeping || e.getHome() == null || !(e.level() instanceof ServerLevel sl)) return null;
        if (e.getHome().distSqr(e.blockPosition()) > 32 * 32) return null;
        for (String species : new String[]{"cow", "sheep", "pig", "chicken"}) {
            int adults = CompanionJobs.adults(sl, e.getHome(), 24, species);
            if (adults < 2 || adults >= CompanionJobs.HERD_MAX) continue;
            if (CompanionJobs.nextToFeed(sl, e, species) != null) return species;
        }
        return null;
    }

    private static Score care(CompanionEntity e) {
        int ripe = matureCrops(e);
        if (ripe >= HARVEST_MIN) return s(Need.CARE, 48, "crops", ripe);
        if (CompanionJobs.canRepairNow(e, 0.25)) return s(Need.CARE, 44, "repair");
        String species = breedable(e);
        if (species != null) return s(Need.CARE, 30, "herd", CompanionTexts.animal(species));
        return null;
    }

    private static boolean actCare(CompanionEntity e) {
        int ripe = matureCrops(e);
        if (ripe >= HARVEST_MIN) {
            stage(e, "harvest", ripe);
            BlockPos center = e.getHome() != null ? e.getHome() : e.getHouseOrigin();
            e.addTask(Task.farm(center));
            e.addTask(Task.collect(false));
            if (e.getHome() != null) e.addTask(Task.deliver(true));
            e.careScanTick = -100_000;
            return true;
        }
        if (CompanionJobs.canRepairNow(e, 0.25)) {
            stage(e, "care_repair");
            e.addTask(Task.repair(25));
            return true;
        }
        String species = breedable(e);
        if (species != null) {
            stage(e, "care_breed", CompanionTexts.animal(species));
            e.addTask(Task.breed(species, 1));
            return true;
        }
        return false;
    }

    private static Score supply(CompanionEntity e) {
        int wood = CompanionCraft.count(e, "#logs") + CompanionCraft.count(e, "#planks") / 4;
        if (wood < 16) return s(Need.SUPPLY, 26, "wood", wood);
        if (CompanionCraft.count(e, "#stone") < 32) return s(Need.SUPPLY, 22, "stone");
        int coal = CompanionCraft.count(e, "#coals");
        if (coal < 8) return s(Need.SUPPLY, 20, "coal", coal);
        return s(Need.SUPPLY, 5, "stocked");
    }

    // ------------------------------------------------------------------ Entscheiden

    /** Planer-Durchlauf (Oberwelt): dringendstes Bedürfnis umsetzen, das sich gerade umsetzen lässt. */
    public static void decide(CompanionEntity e) {
        e.planFeedback.completeIfIdle(e);
        if (e.planFeedback.active()) return; // ein laufender Bau / eine laufende Kette entscheidet noch selbst
        List<Score> ranked = new ArrayList<>(evaluate(e));
        ranked = new ArrayList<>(dev.begleiter.ai.CompanionDecisionEngine.rank(e, ranked));
        for (Score sc : ranked) {
            var objective = CompanionPlanFeedback.prepare(e, sc.need());
            if (act(e, sc)) {
                e.goal = sc.need();
                e.goalScore = sc.value();
                e.setGoalReason(sc.reasonText());
                e.planFeedback.begin(e, sc.need(), objective);
                return;
            }
            // Gerade nicht umsetzbar (z.B. Hunger, aber keine Tiere in Sicht): eine Weile nicht deswegen unterbrechen
            e.needBlockedUntil.put(sc.need(), e.tickCount + 600);
        }
        e.goal = null;
        e.setGoalReason(Component.empty());
        e.plannerCooldownUntil = e.tickCount + 200;
    }

    /**
     * Läuft eine Planer-Aufgabe und ein unterbrechendes Bedürfnis ist deutlich dringender: abbrechen und neu
     * entscheiden. Spieler-Befehle (goal == null) werden nie unterbrochen.
     */
    public static boolean checkInterrupt(CompanionEntity e) {
        if (e.goal == null || !e.hasTasks() || e.build != null) return false;
        for (Score sc : evaluate(e)) {
            if (sc.need() == e.goal) return false; // laufendes Ziel ist (noch) das dringendste
            if (!INTERRUPTS.contains(sc.need()) || e.tickCount < e.needBlockedUntil.getOrDefault(sc.need(), 0)) continue;
            if (sc.value() >= INTERRUPT_MIN && sc.value() >= e.goalScore + INTERRUPT_GAP) {
                e.logInfo(CompanionTexts.tr("log.planner.interrupt", CompanionTexts.tr("need." + e.goal.name().toLowerCase(java.util.Locale.ROOT)),
                        e.goalScore, CompanionTexts.tr("need." + sc.need().name().toLowerCase(java.util.Locale.ROOT)), sc.value(), sc.reasonText()));
                e.cancelAll();
                e.plannerCooldownUntil = 0;
                return true;
            }
            return false;
        }
        return false;
    }

    /** Text für Menü/Chat: "Ziel: Ausrüstung (56) – ich brauche Eisenwerkzeug". */
    public static String describe(CompanionEntity e) { return CompanionTexts.needs(e).getString(); }

    // ------------------------------------------------------------------ Umsetzen

    private static void stage(CompanionEntity e, String key, Object... args) {
        CompanionBrain.stage(e, CompanionTexts.tr("planner.stage." + key, args));
    }
    private static Task gather(Resources.Res r, int n) { return CompanionBrain.gather(r, n); }
    private static Task craft(String id) { return CompanionBrain.craft(id); }
    private static Task smelt() { return CompanionBrain.smelt(); }

    /** true = Aufgaben eingeplant (bzw. bewusst gewartet). */
    static boolean act(CompanionEntity e, Score sc) {
        return switch (sc.need()) {
            case SURVIVAL -> actSurvival(e);
            case HUNGER -> actHunger(e);
            case ORDER -> actOrder(e);
            case GEAR -> actGear(e);
            case BASE -> actBase(e);
            case PROGRESS -> actProgress(e);
            case CARE -> actCare(e);
            case SUPPLY -> actSupply(e);
        };
    }

    static boolean actJourney(CompanionEntity e, Need need) {
        return switch (need) {
            case BASE -> actBase(e);
            case GEAR -> actGear(e);
            case PROGRESS -> actProgress(e);
            default -> false;
        };
    }

    private static boolean actSurvival(CompanionEntity e) {
        if (e.isSheltered()) {
            stage(e, "wait_shelter");
            e.watchdog.noteWaiting();
            e.plannerCooldownUntil = e.tickCount + 100;
            return true;
        }
        if (e.getHealth() / e.getMaxHealth() < 0.4f && (e.getTarget() == null || e.inDanger())) {
            BlockPos inside = e.houseInside();
            // Unter Beschuss ist ein langer Heimweg tödlich (z.B. Skelett in der Höhle): sofort einmauern
            if (e.inDanger() && !e.isInsideHouse() && e.level() instanceof ServerLevel sl && e.sealIn(sl)) {
                stage(e, "retreat_seal", (int) e.getHealth());
                e.plannerCooldownUntil = e.tickCount + 100;
                return true;
            }
            if (inside != null && !e.isInsideHouse() && inside.distSqr(e.blockPosition()) < 96 * 96) {
                stage(e, "retreat_house", (int) e.getHealth());
                e.addTask(Task.moveTo(inside, 1));
            } else {
                stage(e, "retreat_rest", (int) e.getHealth());
                e.plannerCooldownUntil = e.tickCount + 200;
            }
            return true;
        }
        if (e.level() instanceof ServerLevel sl && CompanionBrain.needsNightCamp(e, sl)) {
            if (!CompanionBrain.nightCamp(e, sl)) return false;
            stage(e, "wait_shelter");
            e.watchdog.noteWaiting();
            return true;
        }
        return CompanionBrain.nightStep(e, CompanionCraft.tier(e, "pickaxe"));
    }

    private static boolean actHunger(CompanionEntity e) {
        int food = e.foodCount();
        if (e.food.level() <= 12 && food < 4) {
            if (e.hasFoodAnimalNearby()) {
                e.hungerSearches = 0;
                stage(e, "hunger_hunt");
                e.addTask(Task.hunt(6));
                return true;
            }
            if (e.level() instanceof net.minecraft.server.level.ServerLevel sl && CompanionFishing.hasOrCanMakeRod(e)
                    && CompanionFishing.findSpot(e, sl, 16) != null) {
                stage(e, "hunger_fish");
                e.addTask(Task.fish(4));
                return true;
            }
            if (food == 0) {
                // Wild ist knapp: an der Basis ein Feld anlegen (wie beim Spielziel), dann weiter suchen
                if (e.level() instanceof ServerLevel fieldLevel && e.isHouseBuilt() && e.initiative.base.wantsField(fieldLevel)
                        && e.initiative.base.startField(fieldLevel)) {
                    stage(e, "build_field");
                    return true;
                }
                stage(e, "hunger_search");
                // erst nah suchen, nach zwei leeren Runden in fester Richtung weiterziehen statt im Kreis
                Task ex = e.hungerSearches++ >= 2 ? e.relocatePoint() : null;
                if (ex == null) ex = e.explorePoint();
                if (ex != null) { e.addTask(ex); return true; }
            }
        } else e.hungerSearches = 0;
        if (CompanionCraft.rawFood(e) >= 3 && CompanionCraft.canSmelt(e)) {
            stage(e, "cook");
            e.addTask(smelt());
            return true;
        }
        if (food < 4 && e.hasFoodAnimalNearby()) {
            stage(e, "food_stock");
            e.addTask(Task.hunt(4));
            return true;
        }
        return false;
    }

    private static boolean actOrder(CompanionEntity e) {
        if (e.hasScaffold()) {
            stage(e, "clear_scaffold");
            e.addTask(Task.descend());
            return true;
        }
        if (e.pickup.looseItemsNearby()) {
            stage(e, "collect");
            e.addTask(Task.collect(false));
            return true;
        }
        if (wantsDeliver(e)) {
            stage(e, "deliver");
            e.addTask(Task.deliver(true));
            return true;
        }
        return false;
    }

    private static boolean actBase(CompanionEntity e) {
        if (!e.isHouseBuilt()) {
            stage(e, "build_house");
            e.addTask(Task.house(null));
            return true;
        }
        if (e.level() instanceof ServerLevel sl && e.houseDamaged(sl)) {
            stage(e, "build_house"); // an derselben Stelle ausbessern
            e.addTask(Task.house(e.house.origin()));
            return true;
        }
        if (e.getHome() == null) {
            stage(e, "build_storage");
            int planks = CompanionCraft.count(e, "#planks") + CompanionCraft.count(e, "#logs") * 4;
            if (planks < 12) e.addTask(gather(Resources.WOOD, 4));
            e.addTask(Task.station("chest", false));
            return true;
        }
        if (!e.doorClosed && e.houseNeedsDoor()) {
            stage(e, "house_door");
            e.craftFailedUntil.put("#door", e.tickCount + 6000); // klappt es nicht, nicht sofort wieder
            e.addTask(Task.door(e.houseDoor().getFirst(), "north"));
            return true;
        }
        if (e.level() instanceof ServerLevel sl) {
            if (e.initiative.base.lightingWanted(sl) > 0 && e.initiative.base.startLighting(sl)) {
                stage(e, "light_base");
                return true;
            }
            if (e.initiative.base.wantsField(sl) && e.initiative.base.startField(sl)) {
                stage(e, "build_field");
                return true;
            }
        }
        return false;
    }

    private static boolean actGear(CompanionEntity e) {
        int pick = CompanionCraft.tier(e, "pickaxe");
        if (pick == 0) {
            // Spitzhacke kaputt: mit dem, was er dabei hat, gleich eine gute neue bauen (Stöcke besorgt CRAFT selbst)
            String best = CompanionCraft.count(e, "iron_ingot") >= 3 ? "iron_pickaxe"
                    : CompanionCraft.count(e, "raw_iron") >= 3 ? "iron_pickaxe"
                    : CompanionCraft.count(e, "copper_ingot") >= 3 ? "copper_pickaxe"
                    : CompanionCraft.count(e, "cobblestone") + CompanionCraft.count(e, "cobbled_deepslate") >= 3 ? "stone_pickaxe" : null;
            if (best != null) {
                stage(e, "replace_pickaxe");
                if (best.equals("iron_pickaxe") && CompanionCraft.count(e, "iron_ingot") < 3)
                    e.addTask(Task.smelt("iron_ingot", 3));
                e.addTask(craft(best));
                return true;
            }
            stage(e, "wood_tools");
            int logsEq = CompanionCraft.count(e, "#logs") + CompanionCraft.count(e, "#planks") / 4;
            if (logsEq < 8) e.addTask(gather(Resources.WOOD, 8));
            e.addTask(craft("wooden_pickaxe"));
            if (CompanionCraft.tier(e, "axe") == 0) e.addTask(craft("wooden_axe"));
            if (CompanionCraft.tier(e, "sword") == 0) e.addTask(craft("wooden_sword"));
            if (CompanionCraft.tier(e, "shovel") == 0) e.addTask(craft("wooden_shovel"));
            return true;
        }
        if (pick < 2) {
            stage(e, "stone_tools");
            if (CompanionCraft.count(e, "#stone") < 24) e.addTask(gather(Resources.STONE, 32)); // gleich Vorrat für Ofen & Bauen
            e.addTask(craft("stone_pickaxe"));
            if (CompanionCraft.tier(e, "axe") < 2) e.addTask(craft("stone_axe"));
            if (CompanionCraft.tier(e, "sword") < 2) e.addTask(craft("stone_sword"));
            if (CompanionCraft.tier(e, "shovel") < 2) e.addTask(craft("stone_shovel"));
            return true;
        }
        ItemStack worn = wornTool(e);
        if (worn != null && CompanionJobs.canRepairNow(e, 0.08)) {
            stage(e, "repair_worn", worn.getHoverName());
            e.addTask(Task.repair(8));
            return true;
        }
        if (worn != null && replaceTool(e, worn)) return true;
        if (!e.isHouseBuilt() || e.getHome() == null) return false;

        // Zwischenstufe Kupfer (wenn Kupfer dabei oder gespürt): Spitzhacke, Schwert, Axt über den Zielplaner
        if (wantsCopper(e)) {
            boolean any = false;
            for (String id : new String[]{"copper_pickaxe", "copper_sword", "copper_axe"}) {
                if (CompanionCraft.owns(e, id) || e.craftBlocked(id)) continue;
                CompanionPlanner.Plan plan = CompanionPlanner.obtain(e, CompanionCraft.item(id), 1);
                if (plan == null) continue;
                if (!any) stage(e, "copper_tools");
                for (Task t : plan.tasks()) e.addTask(t);
                any = true;
                break; // eins nach dem anderen (der Plan rechnet mit dem aktuellen Inventar)
            }
            if (any) return true;
        }
        int iron = CompanionCraft.count(e, "raw_iron") + CompanionCraft.count(e, "iron_ingot");
        int coal = CompanionCraft.count(e, "#coals");
        if (pick < 3) {
            // Wie ein Spieler: einmal richtig Eisen farmen – genug für Werkzeug UND Rüstung, dann alles am Stück herstellen
            int want = 9 + armorIngotsNeeded(e);
            stage(e, "iron_materials", want);
            int coalWant = (want + 7) / 8 + 3;
            if (coal < coalWant) e.addTask(gather(Resources.COAL, coalWant - coal + 2));
            if (iron < want) e.addTask(gather(Resources.IRON, want - iron));
            e.addTask(smelt());
            e.addTask(craft("iron_pickaxe"));
            if (CompanionCraft.tier(e, "axe") < 3) e.addTask(craft("iron_axe"));
            if (CompanionCraft.tier(e, "sword") < 3) e.addTask(craft("iron_sword"));
            if (CompanionCraft.tier(e, "shovel") < 3) e.addTask(craft("iron_shovel"));
            for (String piece : ARMOR) if (!hasArmor(e, piece)) e.addTask(craft(piece));
            return true;
        }
        int needIngots = armorIngotsNeeded(e);
        if (needIngots > 0) {
            stage(e, "iron_armor");
            if (coal < 3) e.addTask(gather(Resources.COAL, 4));
            if (iron < needIngots) e.addTask(gather(Resources.IRON, needIngots - iron));
            e.addTask(smelt());
            for (String piece : ARMOR) if (!hasArmor(e, piece)) e.addTask(craft(piece));
            return true;
        }
        if (rangedCraftable(e) && craftRangedFromInventory(e)) return true;
        if (CompanionBrain.needsBlaze(e) && CompanionBrain.deathsIn(e, net.minecraft.world.level.Level.NETHER) >= 1
                && !e.craftBlocked(DIAMOND_GEAR) && diamondsNeeded(e) == 0 && diamondGearStep(e)) return true;
        int needDia = diamondsNeeded(e);
        if (needDia > 0) {
            stage(e, "diamonds");
            int dia = CompanionCraft.count(e, "diamond");
            if (dia < needDia) e.addTask(gather(Resources.DIAMOND, needDia - dia));
            if (pick < 4) e.addTask(craft("diamond_pickaxe"));
            // Schwert nur, wenn die Spitzhacke schon da ist oder es für beide reicht (Spitzhacke geht vor)
            if (CompanionCraft.tier(e, "sword") < 4 && (pick >= 4 || dia >= 5)) e.addTask(craft("diamond_sword"));
            return true;
        }
        return false;
    }

    private static final String[] ARMOR = {"shield", "iron_chestplate", "iron_leggings", "iron_helmet", "iron_boots"};

    /** Eisenteil oder schon ein besseres (Diamant, Netherit) derselben Art. */
    private static boolean hasArmor(CompanionEntity e, String piece) {
        if (CompanionCraft.owns(e, piece)) return true;
        return !piece.equals("shield") && CompanionJobs.pieceTier(e, piece.substring("iron_".length())) >= 3;
    }
    private static final int[] ARMOR_COST = {1, 8, 7, 5, 4};

    /**
     * Kupferwerkzeug lohnt sich zwischen Stein und Eisen, wenn Kupfer schon dabei ist oder in der Nähe gespürt wurde
     * (gibt es die Kupfer-Items in dieser Version nicht, nie).
     */
    static boolean wantsCopper(CompanionEntity e) {
        if (CompanionCraft.tier(e, "pickaxe") != 2) return false;
        if (CompanionCraft.item("copper_pickaxe") == net.minecraft.world.item.Items.AIR) return false;
        boolean missing = false;
        for (String id : new String[]{"copper_pickaxe", "copper_sword", "copper_axe"})
            if (!CompanionCraft.owns(e, id) && !e.craftBlocked(id)) missing = true;
        if (!missing) return false;
        int copper = CompanionCraft.count(e, "raw_copper") + CompanionCraft.count(e, "copper_ingot");
        if (copper >= 3) return true;
        return e.level() instanceof ServerLevel sl
                && e.memory.nearest(CompanionMemory.Kind.ORE, Resources.COPPER.key(), sl, e.blockPosition(), 48) != null;
    }

    static int armorIngotsNeeded(CompanionEntity e) {
        int n = 0;
        for (int i = 0; i < ARMOR.length; i++) if (!hasArmor(e, ARMOR[i])) n += ARMOR_COST[i];
        return n;
    }

    static void journeyIronGear(CompanionEntity e) {
        if (CompanionCraft.tier(e, "pickaxe") < 3) {
            journeyIronPiece(e, "iron_pickaxe", 3);
            return;
        }
        for (int i = 0; i < ARMOR.length; i++) {
            if (hasArmor(e, ARMOR[i])) continue;
            journeyIronPiece(e, ARMOR[i], ARMOR_COST[i]);
            return;
        }
    }

    static void journeyIronSword(CompanionEntity e) { journeyIronPiece(e, "iron_sword", 2); }

    private static void journeyIronPiece(CompanionEntity e, String item, int cost) {
        int ingots = CompanionCraft.count(e, "iron_ingot");
        if (ingots >= cost) {
            e.addTask(craft(item));
            return;
        }
        int raw = CompanionCraft.count(e, "raw_iron");
        if (raw == 0) {
            e.addTask(gather(Resources.IRON, cost - ingots));
            return;
        }
        double fuel = e.fuelLeft + 8 * CompanionCraft.count(e, "#coals")
                + 1.5 * (CompanionCraft.count(e, "#planks") + CompanionCraft.count(e, "#logs"));
        int smelt = Math.min(raw, cost - ingots);
        if (fuel < smelt) {
            e.addTask(gather(Resources.WOOD, (int) Math.ceil((smelt - fuel) / 1.5) + 2));
            return;
        }
        e.addTask(Task.smelt("iron_ingot", ingots + smelt));
    }

    static int diamondsNeeded(CompanionEntity e) {
        return (CompanionCraft.tier(e, "pickaxe") < 4 ? 3 : 0) + (CompanionCraft.tier(e, "sword") < 4 ? 2 : 0);
    }

    private static final String[] TOOL_KINDS = {"pickaxe", "sword", "axe"};
    private static final String[] TIER_PREFIX = {"", "wooden", "stone", "iron", "diamond", "netherite"};

    /** Bestes Werkzeug einer Art, das fast kaputt ist (unter 8 % Haltbarkeit) und keinen gleichwertigen Ersatz hat. */
    static ItemStack wornTool(CompanionEntity e) {
        for (String kind : TOOL_KINDS) {
            int tier = CompanionCraft.tier(e, kind);
            if (tier < 2 || tier > 4) continue;
            String id = TIER_PREFIX[tier] + "_" + kind;
            int spares = 0;
            ItemStack worst = null;
            for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
                ItemStack st = e.getInventory().getItem(i);
                if (!ItemIds.matches(st, id)) continue;
                spares++;
                if (st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() < st.getMaxDamage() * 0.08) worst = st;
            }
            ItemStack hand = e.getMainHandItem();
            if (ItemIds.matches(hand, id)) {
                spares++;
                if (hand.isDamageableItem() && hand.getMaxDamage() - hand.getDamageValue() < hand.getMaxDamage() * 0.08) worst = hand;
            }
            if (worst != null && spares < 2) return worst;
        }
        return null;
    }

    /** Ersatz für ein fast kaputtes Werkzeug herstellen (Material holen, ggf. schmelzen). */
    static boolean replaceTool(CompanionEntity e, ItemStack worn) {
        var key = BuiltInRegistries.ITEM.getKey(worn.getItem());
        if (!"minecraft".equals(key.getNamespace())) return false;
        String id = key.getPath();
        stage(e, "replace_worn", worn.getHoverName());
        int heads = id.endsWith("pickaxe") || id.endsWith("axe") ? 3 : 2;
        if (id.startsWith("stone")) {
            if (CompanionCraft.count(e, "#stone") < heads) e.addTask(gather(Resources.STONE, heads + 2));
        } else if (id.startsWith("iron")) {
            int iron = CompanionCraft.count(e, "raw_iron") + CompanionCraft.count(e, "iron_ingot");
            if (iron < heads) e.addTask(gather(Resources.IRON, heads - iron));
            if (CompanionCraft.count(e, "iron_ingot") < heads) {
                if (CompanionCraft.count(e, "#coals") < 1) e.addTask(gather(Resources.COAL, 2));
                e.addTask(smelt());
            }
        } else if (id.startsWith("diamond")) {
            int dia = CompanionCraft.count(e, "diamond");
            if (dia < heads) e.addTask(gather(Resources.DIAMOND, heads - dia));
        } else {
            return false;
        }
        // Stiele: ohne Stöcke, Bretter und Stämme erst Holz holen (beobachtet: 30 Minuten "Stock: Bretter 0/2")
        if (CompanionCraft.count(e, "stick") < 2 && CompanionCraft.count(e, "#planks") < 2
                && CompanionCraft.count(e, "#logs") == 0) e.addTask(gather(Resources.WOOD, 1));
        e.addTask(craft(id));
        return true;
    }

    /**
     * Bereit fürs End: Bogen und Pfeile für die Kristalle, volle Diamantausrüstung (außer die Diamantsuche ist gerade
     * gesperrt, weil sie wiederholt scheiterte) und ein Wassereimer gegen Fallschaden.
     */
    static boolean readyForDragon(CompanionEntity e) {
        return CompanionCraft.owns(e, "bow") && CompanionCraft.count(e, "arrow") >= CompanionBrain.ARROWS
                && (fullDiamondGear(e) || e.craftBlocked(DIAMOND_GEAR)) && CompanionCraft.owns(e, "water_bucket");
    }

    /** Sperrschlüssel, wenn die Diamantsuche mehrfach gescheitert ist (dann zählt Eisen fürs End). */
    static final String DIAMOND_GEAR = "#diamond_gear";

    static boolean fullDiamondGear(CompanionEntity e) { return CompanionJobs.nextGearPiece(e, 4, 0) == null; }

    private static int diamondCost(String piece) {
        if (piece.endsWith("sword")) return 2;
        if (piece.endsWith("pickaxe")) return 3;
        if (piece.endsWith("chestplate")) return 8;
        if (piece.endsWith("leggings")) return 7;
        if (piece.endsWith("helmet")) return 5;
        return 4; // Stiefel
    }

    /** Diamanten, die für die volle Diamantausrüstung noch fehlen (abzüglich mitgeführter). */
    static int diamondGearMissing(CompanionEntity e) {
        int need = 0;
        for (String kind : CompanionJobs.GEAR)
            if (!kind.equals("shield") && CompanionJobs.pieceTier(e, kind) < 4) need += diamondCost(kind);
        return Math.max(0, need - CompanionCraft.count(e, "diamond"));
    }

    /**
     * Nächstes Stück der Diamantausrüstung (Schwert, Spitzhacke, Brust, Hose, Helm, Stiefel; Schild bleibt Eisen):
     * Diamanten für dieses Stück abbauen, dann herstellen. false = schon komplett.
     */
    static boolean diamondGearStep(CompanionEntity e) {
        String next = CompanionJobs.nextGearPiece(e, 4, 0);
        if (next == null) return false;
        if (next.equals("shield")) {
            var plan = CompanionPlanner.obtain(e, net.minecraft.world.item.Items.SHIELD, 1);
            if (plan == null) return false;
            stage(e, "shield");
            for (Task t : plan.tasks()) e.addTask(t);
            return true;
        }
        int cost = diamondCost(next), have = CompanionCraft.count(e, "diamond");
        stage(e, "diamond_gear", CompanionJobs.gearProgress(e, 4));
        if (have < cost) e.addTask(gather(Resources.DIAMOND, cost - have));
        e.addTask(craft(next));
        return true;
    }

    /**
     * Bogen und Pfeile aus dem, was er dabei hat (Faden, Feuerstein, Federn); ohne Material nichts. Nützlich gegen
     * Skelette, Creeper, Ghasts und für die Endkristalle.
     */
    static boolean craftRangedFromInventory(CompanionEntity e) {
        if (!CompanionCraft.owns(e, "bow") && CompanionCraft.count(e, "string") >= 3 && !e.craftBlocked("bow")) {
            stage(e, "bow");
            e.addTask(craft("bow"));
            return true;
        }
        int arrows = CompanionCraft.count(e, "arrow");
        int flint = CompanionCraft.count(e, "flint"), feathers = CompanionCraft.count(e, "feather");
        if (CompanionCraft.owns(e, "bow") && arrows < CompanionBrain.ARROWS && flint >= 1 && feathers >= 1
                && CompanionCraft.count(e, "stick") + CompanionCraft.count(e, "#planks") + CompanionCraft.count(e, "#logs") > 0
                && !e.craftBlocked("arrow")) {
            stage(e, "arrows");
            e.addTask(Task.craft("arrow", Math.min(CompanionBrain.ARROWS, arrows + Math.min(flint, feathers) * 4)));
            return true;
        }
        return false;
    }

    /** Wassereimer gegen Fallschaden (Eimer herstellen, an der nächsten Quelle füllen). */
    static boolean waterBucketStep(CompanionEntity e) {
        if (CompanionCraft.owns(e, "water_bucket")) return false;
        if (!CompanionCraft.owns(e, "bucket")) {
            var plan = CompanionPlanner.obtain(e, net.minecraft.world.item.Items.BUCKET, 1);
            if (plan == null) return false;
            for (Task t : plan.tasks()) e.addTask(t);
        }
        stage(e, "water_bucket");
        e.addTask(Task.water(null));
        return true;
    }

    /** Tränke für den nächsten Abschnitt brauen, soweit Zutaten da sind (Feuerresistenz, Stärke vor dem Drachen ...). */
    static boolean potionStep(CompanionEntity e, boolean dragonSoon, int netherDeaths, int endDeaths) {
        for (var kind : CompanionPotions.Kind.values()) {
            int want = e.potions.wanted(kind, dragonSoon, netherDeaths, endDeaths);
            if (want > 0 && e.potions.count(kind) < want && !e.craftBlocked("#brew_" + kind.id())
                    && e.potions.planBrew(kind, want)) {
                stage(e, "brew", CompanionTexts.tr("potion." + kind.id()));
                return true;
            }
        }
        return false;
    }

    private static boolean actProgress(CompanionEntity e) {
        int coal = CompanionCraft.count(e, "#coals");
        // Netherportal: Eimer + Wasser, Feuerzeug, 10 Obsidian, Rahmen bauen und anzünden
        if (e.getPortalPos() == null) {
            stage(e, "nether_portal");
            boolean bucket = CompanionCraft.owns(e, "bucket") || CompanionCraft.owns(e, "water_bucket");
            boolean lighter = CompanionCraft.owns(e, "flint_and_steel");
            int needIron = (bucket ? 0 : 3) + (lighter ? 0 : 1);
            int ingots = CompanionCraft.count(e, "iron_ingot");
            if (ingots < needIron) {
                int raw = CompanionCraft.count(e, "raw_iron");
                if (ingots + raw < needIron) e.addTask(gather(Resources.IRON, needIron - ingots - raw));
                if (coal < 2) e.addTask(gather(Resources.COAL, 2));
                e.addTask(smelt());
            }
            if (!bucket) e.addTask(craft("bucket"));
            if (!lighter) {
                if (CompanionCraft.count(e, "flint") == 0) e.addTask(gather(Resources.GRAVEL, 10));
                e.addTask(craft("flint_and_steel"));
            }
            if (!CompanionCraft.owns(e, "water_bucket")) e.addTask(Task.water(null));
            int obs = CompanionCraft.count(e, "obsidian");
            if (obs < 10) e.addTask(gather(Resources.OBSIDIAN, 10 - obs));
            e.addTask(Task.portal(null));
            return true;
        }
        // Lohenruten aus der Netherfestung
        if (CompanionBrain.needsBlaze(e)) {
            BlockPos portal = e.portals.nearest(8);
            if (portal == null) portal = CompanionPortals.inside(e.getPortalPos()).getFirst();
            if (portal != null) {
                stage(e, "nether_trip");
                e.netherTrip = true;
                e.netherPortal = null;
                e.addTask(Task.moveTo(portal, 1));
                return true;
            }
        }
        // Enderperlen: nachts Endermen jagen
        if (CompanionBrain.wantsPearlHunt(e) && e.level().isDarkOutside()) {
            stage(e, "hunt_pearls", CompanionBrain.pearlsAndEyes(e), CompanionBrain.PEARLS);
            e.addTask(Task.fight(null, "enderman", null, CompanionBrain.PEARLS - CompanionCraft.count(e, "ender_eye")));
            return true;
        }
        // Enderaugen herstellen, Festung finden, Endportal öffnen
        if (e.endPortalPos == null && CompanionBrain.pearlsAndEyes(e) >= CompanionBrain.PEARLS) {
            int eyes = Math.max(0, CompanionBrain.PEARLS - CompanionCraft.count(e, "ender_eye"));
            if (eyes > 0) {
                stage(e, "eyes");
                e.addTask(Task.craft("ender_eye", eyes));
            }
            stage(e, "stronghold");
            e.addTask(Task.stronghold());
            return true;
        }
        // Bogen und Pfeile für das End, dazu Diamantausrüstung, Wassereimer und Tränke
        if (e.endPortalPos != null && !CompanionBrain.dragonDefeated(e)) {
            boolean night = e.level().isDarkOutside();
            if (!fullDiamondGear(e) && !e.craftBlocked(DIAMOND_GEAR) && diamondGearStep(e)) return true;
            if (waterBucketStep(e)) return true;
            if (potionStep(e, true, 0, 0)) return true;
            if (!CompanionCraft.owns(e, "bow")) {
                if (CompanionCraft.count(e, "string") >= 3) {
                    stage(e, "bow");
                    e.addTask(craft("bow"));
                    return true;
                }
                if (night) {
                    stage(e, "hunt_spiders");
                    e.addTask(Task.fight(null, "spider", null, 3));
                    return true;
                }
            }
            int arrows = CompanionCraft.count(e, "arrow");
            if (arrows < CompanionBrain.ARROWS) {
                int sets = (CompanionBrain.ARROWS - arrows + 3) / 4;
                int flint = CompanionCraft.count(e, "flint"), feathers = CompanionCraft.count(e, "feather");
                if (flint >= 1 && feathers >= 1) {
                    stage(e, "arrows");
                    e.addTask(Task.craft("arrow", Math.min(flint, feathers) * 4));
                    return true;
                }
                if (!night) {
                    stage(e, "arrow_materials");
                    if (flint < sets) e.addTask(gather(Resources.GRAVEL, 10));
                    if (feathers < sets) e.addTask(Task.fight(null, "chicken", null, sets));
                    return true;
                }
            }
        }
        if (postDragonGear(e)) {
            e.initiative.postDragonGearUntil = e.tickCount + 6000; // nicht in einer Schleife immer wieder wählen
            stage(e, "post_dragon_gear", CompanionJobs.gearProgress(e, 4));
            e.addTask(CompanionJobs.gearTask(4, 0, null, 0, 0));
            return true;
        }
        return false;
    }

    /** Drache besiegt, Diamantausrüstung noch nicht komplett und nicht gerade erst versucht. */
    static boolean postDragonGear(CompanionEntity e) {
        return e.tickCount >= e.initiative.postDragonGearUntil && CompanionBrain.dragonDefeated(e)
                && CompanionJobs.nextGearPiece(e, 4, 0) != null;
    }

    private static boolean actSupply(CompanionEntity e) {
        if (e.endPortalPos != null && readyForDragon(e) && !CompanionBrain.dragonDefeated(e)
                && !e.isStage("planner.stage.ready_dragon")) {
            stage(e, "ready_dragon", e.endPortalPos.toShortString());
        }
        List<dev.begleiter.ai.CompanionDecisionEngine.SupplyOption> options = CompanionSituation.supplyOptions(e);

        // Ohne Entscheidungsmodul bleibt die alte Reihenfolge Holz → Stein → Kohle
        var chosen = dev.begleiter.ai.CompanionDecisionEngine.enabled()
                ? CompanionSituation.chooseSupply(e, options)
                : options.isEmpty() ? null : options.getFirst();
        if (chosen != null) {
            Resources.Res resource = Resources.byKey(chosen.resourceKey());
            e.activeSupplyResource = resource.key();

            // Bewährte Wege wiederverwenden bzw. nach einem Fehlschlag einen anderen wählen
            if (dev.begleiter.ai.CompanionDecisionEngine.enabled()
                    && (e.experiences.penalty(Need.SUPPLY, resource.key(), e.tickCount) > 0
                        || e.experiences.hasRouteExperience(Need.SUPPLY, resource.key())
                        || CompanionSources.hasFailures(e))) {
                net.minecraft.world.item.Item desired = switch (resource.key()) {
                    case "holz" -> net.minecraft.world.item.Items.OAK_LOG;
                    case "stein" -> net.minecraft.world.item.Items.COBBLESTONE;
                    case "kohle" -> net.minecraft.world.item.Items.COAL;
                    default -> null;
                };
                if (desired != null) {
                    int wanted = switch (resource.key()) {
                        case "holz" -> 16;
                        case "stein" -> 32;
                        default -> 8;
                    };
                    var alternatives = CompanionPlanner.alternatives(e, desired, wanted);
                    var route = dev.begleiter.ai.AdaptiveAcquisition.choose(alternatives,
                            approach -> e.experiences.penalty(
                                    Need.SUPPLY, resource.key() + "_" + approach, e.tickCount),
                            approach -> e.experiences.learnedPreference(
                                    Need.SUPPLY, resource.key() + "_" + approach),
                            plan -> {
                                if (!(e.level() instanceof ServerLevel world)) return 0;
                                // Bekannte Gefahren- und Todesorte schließen einen Weg aus, statt ihn nur zu verteuern
                                for (Task step : plan.tasks()) {
                                    if (step.pos() != null && e.memory.dangerous(world, step.pos(), 8))
                                        return 150;
                                }
                                // Verletzt lieber ohne Umweg über den Bergbau beschaffen
                                return e.getHealth() < e.getMaxHealth() * 0.5f
                                        && "mining".equals(dev.begleiter.ai.AdaptiveAcquisition.approach(plan))
                                        ? 25 : 0;
                            });
                    if (route != null) {
                        e.activeSupplyApproach = route.approach();
                        stage(e, "acquire_route", CompanionTexts.resource(resource.key()), CompanionTexts.join(
                                route.plan().tasks().stream().map(CompanionTexts::task).toList(), " → "));
                        for (Task task : route.plan().tasks()) e.addTask(task);
                        return true;
                    }
                }
                e.activeSupplyApproach = null;
                stage(e, "source_unknown", CompanionTexts.resource(resource.key()));
                Task exploration = e.explorePoint();
                if (exploration != null) e.addTask(exploration);
                e.plannerCooldownUntil = e.tickCount + 400;
                return true;
            }

            e.activeSupplyApproach = "mining";
            stage(e, switch (resource.key()) {
                case "holz" -> "supply_wood";
                case "stein" -> "supply_stone";
                default -> "supply_coal";
            });
            // Ohne Entscheidungsmodul die alte Steinmenge beibehalten
            int amount = !dev.begleiter.ai.CompanionDecisionEngine.enabled() && resource == Resources.STONE
                    ? 32 : chosen.missing();
            e.addTask(gather(resource, amount));
            return true;
        }
        e.activeSupplyResource = null;
        e.activeSupplyApproach = null;
        if (!options.isEmpty()) {
            stage(e, "explore_failed");
        } else {
            stage(e, "explore_stocked");
        }
        Task ex = e.explorePoint();
        if (ex != null) e.addTask(ex);
        e.plannerCooldownUntil = e.tickCount + 400;
        return true;
    }
}
