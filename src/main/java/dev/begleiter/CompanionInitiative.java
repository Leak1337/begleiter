package dev.begleiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.begleiter.CompanionEntity.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Eigeninitiative: auf den Besitzer aufpassen, nachts im Haus Arbeit erledigen,
 * wiederkehrende Wünsche als Vorratsregel vorschlagen, die Basis ausbauen und ausleuchten, nach dem Drachen weitere
 * Ziele verfolgen, von sich aus melden bzw. um Hilfe bitten und unter Tage neue Gebiete statt schon abgesuchter wählen.
 * Gewohnheitszähler und abgesuchte Felder überstehen das Neuladen ({@code InitiativeV1}, siehe {@link InitiativeStore});
 * Abklingzeiten, Todesort und offene Vorschläge bleiben bewusst Laufzeit.
 */
public final class CompanionInitiative {
    private final CompanionEntity e;
    public final OwnerCare ownerCare;
    public final Habits habits = new Habits();
    public final BaseCare base;
    public final MiningCells mining = new MiningCells();
    /** Nach dem Drachen: so lange nicht erneut die Diamantausrüstung als Ziel wählen (Wechsel mit anderen Zielen). */
    public int postDragonGearUntil;

    // Meldungen und Hilferufe
    private Object lastResult;
    private CompanionNeeds.Need failedNeed;
    private int failedInRow;
    private int lastHelpTick = -100_000;
    private int lastDiamondReportTick = -100_000;
    private final Set<BlockPos> reportedDiamonds = new HashSet<>();
    private boolean storageFullReported;
    private int slowTick;
    private int helpRequests;

    // Nächtliche Hausarbeit: erledigte Arbeiten in dieser Nacht (Bitmaske) und wann zuletzt eine dran war
    private int choresDone;
    private int lastChoreTick = -100_000;

    CompanionInitiative(CompanionEntity e) {
        this.e = e;
        this.ownerCare = new OwnerCare(e);
        this.base = new BaseCare(e);
    }

    /** Gespeicherter Teil: Gewohnheiten und abgesuchte Felder. */
    net.minecraft.nbt.CompoundTag save() {
        return InitiativeStore.write(habits.asks, habits.declined, mining.visits);
    }

    /** Fehlt der Teil (ältere Welten), bleibt alles leer; unbekannte Items werden verworfen. */
    void load(net.minecraft.nbt.CompoundTag tag) {
        InitiativeStore.read(tag, habits.asks, habits.declined, mining.visits);
        habits.asks.keySet().removeIf(id -> Habits.item(id) == null);
        habits.declined.removeIf(id -> Habits.item(id) == null);
    }

    /** Alle 10 Ticks aus der Entity. */
    void tick(ServerLevel sl) {
        try {
            ownerCare.tick(sl);
            if (++slowTick % 10 == 0) reports(sl); // alle 5 s
        } catch (RuntimeException ex) {
            e.logError(CompanionTexts.tr("initiative.error", CompanionTexts.literal(ex.toString())));
        }
    }

    /** Wie oft er bisher um Hilfe gebeten hat (Diagnose, Tests). */
    public int helpRequests() { return helpRequests; }

    // ------------------------------------------------------------------ 2: Nacht sinnvoll nutzen

    /**
     * Nachts im verschlossenen Haus statt nur zu warten: kochen und schmelzen, Werkzeug reparieren, Fackeln herstellen,
     * Überschuss einlagern und die Lagerkiste sortieren. Jede Arbeit höchstens einmal pro Nacht. true = Arbeit geplant.
     */
    boolean nightChore(ServerLevel sl) {
        if (e.tickCount - lastChoreTick > 6000) choresDone = 0; // neue Nacht
        BlockPos inside = e.houseInside();
        if (inside == null || !e.isInsideHouse()) return false;
        BlockPos home = e.getHome();
        boolean homeInHouse = home != null && home.distSqr(inside) <= 10 * 10;
        boolean furnaceHere = CompanionCraft.count(e, "furnace") > 0 || CompanionCraft.count(e, "cobblestone") >= 8;
        int raw = CompanionCraft.rawFood(e) + CompanionCraft.count(e, "raw_iron") + CompanionCraft.count(e, "raw_gold")
                + CompanionCraft.count(e, "raw_copper");
        if (chore(1) && raw > 0 && furnaceHere && CompanionCraft.canSmelt(e)) {
            CompanionBrain.stage(e, CompanionTexts.tr("planner.stage.night_smelt"));
            e.addTask(Task.smeltAnything());
            return true;
        }
        if (chore(2) && CompanionJobs.canRepairNow(e, 0.5)) {
            CompanionBrain.stage(e, CompanionTexts.tr("planner.stage.night_repair"));
            e.addTask(Task.repair(50));
            return true;
        }
        if (chore(4) && CompanionCraft.count(e, "torch") < 16 && CompanionCraft.count(e, "#coals") >= 2) {
            int made = 0;
            for (int i = 0; i < 4 && CompanionCraft.count(e, "torch") < 16; i++) {
                if (!e.craftTorches()) break;
                made += 4;
            }
            if (made > 0) {
                CompanionBrain.stage(e, CompanionTexts.tr("planner.stage.night_torches", made));
                e.plannerCooldownUntil = e.tickCount + 60;
                return true;
            }
        }
        if (chore(8) && homeInHouse && e.depositableSlots() >= 4 && e.tickCount >= e.storageFullUntil) {
            CompanionBrain.stage(e, CompanionTexts.tr("planner.stage.night_deliver"));
            e.addTask(Task.deliver(true));
            return true;
        }
        if (chore(16) && homeInHouse) {
            CompanionBrain.stage(e, CompanionTexts.tr("planner.stage.night_sort"));
            e.addTask(Task.sort(home));
            return true;
        }
        return false;
    }

    /** Diese Arbeit ist heute Nacht noch nicht dran gewesen? Markiert sie gleich als erledigt. */
    private boolean chore(int bit) {
        if ((choresDone & bit) != 0) return false;
        choresDone |= bit;
        lastChoreTick = e.tickCount;
        return true;
    }

    // ------------------------------------------------------------------ 6: Melden und um Hilfe bitten

    /** Alle 5 s aus {@link #tick}; Paketzugriff für GameTests. */
    void reports(ServerLevel sl) {
        if (e.getOwnerAnywhere() == null) return;
        // Wiederholt gescheiterte selbstständige Pläne: den Besitzer um Hilfe bitten statt still weiterzuprobieren
        var result = e.planFeedback.last();
        if (result != null && result != lastResult) {
            lastResult = result;
            if (result.status() == CompanionPlanFeedback.Status.FAILURE) {
                failedInRow = result.need() == failedNeed ? failedInRow + 1 : 1;
                failedNeed = result.need();
                if (failedInRow >= 3 && e.tickCount - lastHelpTick >= 12_000 && e.mayReportProgress()) {
                    String need = result.need().name().toLowerCase(Locale.ROOT);
                    e.tell(CompanionTexts.tr("initiative.help", CompanionTexts.tr("need." + need), failedInRow,
                            CompanionTexts.tr("initiative.help.hint." + need)));
                    lastHelpTick = e.tickCount;
                    failedInRow = 0;
                    helpRequests++;
                }
            } else if (result.status() == CompanionPlanFeedback.Status.SUCCESS
                    || result.status() == CompanionPlanFeedback.Status.PARTIAL) {
                if (result.need() == failedNeed) failedInRow = 0;
            }
        }
        // Gespürte Diamanten melden (höchstens alle 5 min, jede Fundstelle einmal)
        if (e.tickCount - lastDiamondReportTick >= 6000) {
            var found = e.memory.find(CompanionMemory.Kind.ORE, Resources.DIAMOND.key(), sl, e.blockPosition());
            for (var place : found) {
                if (reportedDiamonds.contains(place.pos)) continue;
                for (var p : found) if (place.pos.distSqr(p.pos) < 16 * 16) reportedDiamonds.add(p.pos);
                if (reportedDiamonds.size() > 256) reportedDiamonds.clear();
                if (e.mayReportProgress()) e.tell(CompanionTexts.tr("initiative.diamonds", place.pos.toShortString()));
                lastDiamondReportTick = e.tickCount;
                break;
            }
        }
        // Lager voll: einmal sagen, bis es wieder Platz hat
        boolean full = e.getHome() != null && e.tickCount < e.storageFullUntil;
        if (full && !storageFullReported && e.mayReportProgress()) {
            e.tell(CompanionTexts.tr("initiative.storage_full"));
            storageFullReported = true;
        } else if (!full) storageFullReported = false;
    }

    // ------------------------------------------------------------------ 1: Auf den Besitzer aufpassen

    /** Hunger, Creeper, wenig Leben und verlorene Sachen nach dem Tod des Besitzers. */
    public static final class OwnerCare {
        private final CompanionEntity e;
        private int lastFoodTick = -100_000, lastCreeperTick = -100_000, lastLowHealthTick = -100_000, lastAppleTick = -100_000;
        // Tod des Besitzers: Ort, Dimension, Zeit, die liegengebliebenen Items und was er davon zurückgeben will
        private BlockPos deathPos;
        private String deathDim;
        private int deathTick;
        private boolean snapshotTaken;
        private final Set<UUID> deathItems = new HashSet<>();
        private final List<ItemStack> toReturn = new ArrayList<>();
        private boolean retrieving;
        private int retrieveMoves, lastMoveTick = -100_000;

        OwnerCare(CompanionEntity e) { this.e = e; }

        void tick(ServerLevel sl) {
            Player owner = e.getOwner();
            if (owner == null || owner.level() != sl) {
                if (deathPos != null && e.tickCount - deathTick > 6000) resetDeath();
                return;
            }
            if (owner.isDeadOrDying()) {
                if (deathPos == null) noteDeath(sl, owner);
                return;
            }
            if (deathPos != null) retrieve(sl, owner);
            // Fähigkeiten statt isCreative(): folgt dem tatsächlichen Spielmodus (Kreativ fliegt/baut sofort, braucht kein Essen)
            if (owner.getAbilities().instabuild || owner.isSpectator()) return;
            if (e.distanceToSqr(owner) > 16 * 16) return;
            warnCreeper(sl, owner);
            guardLowHealth(sl, owner);
            feed(sl, owner);
        }

        /** Eigener Hunger 10 oder weniger, nichts Essbares dabei: zwei bis vier gute Essensstücke abgeben. */
        private void feed(ServerLevel sl, Player owner) {
            if (e.tickCount - lastFoodTick < 1200 || owner.getFoodData().getFoodLevel() > 10) return;
            if (e.distanceToSqr(owner) > 8 * 8 || e.getTarget() != null) return;
            for (int i = 0; i < owner.getInventory().getContainerSize(); i++)
                if (goodFood(owner.getInventory().getItem(i))) return; // hat selbst etwas dabei
            int best = -1, bestNutrition = 0, total = 0;
            for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
                ItemStack s = e.getInventory().getItem(i);
                if (!goodFood(s)) continue;
                total += s.getCount();
                int n = s.get(DataComponents.FOOD).nutrition();
                if (n > bestNutrition) { bestNutrition = n; best = i; }
            }
            if (best < 0 || total < 3) return; // selbst etwas behalten
            ItemStack stack = e.getInventory().getItem(best);
            int give = Math.min(Math.min(4, stack.getCount()), total - 2);
            if (give <= 0) return;
            lastFoodTick = e.tickCount;
            Component name = stack.getHoverName();
            if (drop(sl, owner, stack.copyWithCount(give))) {
                stack.shrink(give);
                e.tell(CompanionTexts.tr("initiative.owner.food", give, name));
            }
        }

        static boolean goodFood(ItemStack s) {
            if (s.isEmpty() || !s.has(DataComponents.FOOD)) return false;
            return !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO)
                    && !s.is(Items.PUFFERFISH) && !s.is(Items.CHICKEN) && !s.is(Items.SUSPICIOUS_STEW)
                    && !s.is(Items.GOLDEN_APPLE) && !s.is(Items.ENCHANTED_GOLDEN_APPLE) && !s.is(Items.CHORUS_FRUIT);
        }

        /** Creeper hinter dem Besitzer (außerhalb seines Blickfelds) oder schon am Zischen: laut warnen. */
        private void warnCreeper(ServerLevel sl, Player owner) {
            if (e.tickCount - lastCreeperTick < 200) return;
            Vec3 look = owner.getViewVector(1.0f).multiply(1, 0, 1);
            for (Creeper c : sl.getEntitiesOfClass(Creeper.class, owner.getBoundingBox().inflate(7), Creeper::isAlive)) {
                Vec3 to = c.position().subtract(owner.position()).multiply(1, 0, 1);
                boolean behind = to.lengthSqr() > 0.01 && look.lengthSqr() > 0.01 && to.normalize().dot(look.normalize()) < 0.2;
                boolean hissing = c.getSwellDir() > 0 || c.isIgnited();
                if (!behind && !hissing) continue;
                lastCreeperTick = e.tickCount;
                e.tell(CompanionTexts.tr(hissing ? "initiative.owner.creeper_hiss" : "initiative.owner.creeper_behind"));
                return;
            }
        }

        /** Besitzer hat kaum noch Leben: Monster, die es auf ihn abgesehen haben, zuerst angreifen; Notfall-Apfel abgeben. */
        private void guardLowHealth(ServerLevel sl, Player owner) {
            if (owner.getHealth() > 6) return;
            Mob attacker = null;
            double best = Double.MAX_VALUE;
            if (e.stance != CompanionCombat.Stance.PASSIVE) {
                for (Mob m : sl.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(16),
                        m -> m instanceof Enemy && m.isAlive() && m.getTarget() == owner && !CompanionCombat.isFriend(e, m)
                                && !e.isIgnoredTarget(m))) {
                    double d = m.distanceToSqr(owner);
                    if (d < best) { best = d; attacker = m; }
                }
                if (attacker != null && e.getTarget() != attacker && !(e.getTarget() instanceof Mob cur && cur.getTarget() == owner)) {
                    e.setTarget(attacker);
                    e.logInfo(dev.begleiter.debug.BotDebug.EventKind.DANGER,
                            CompanionTexts.tr("log.combat.defend_owner", attacker.getName()));
                }
            }
            if (e.tickCount - lastLowHealthTick >= 600) {
                lastLowHealthTick = e.tickCount;
                e.tell(CompanionTexts.tr(attacker != null ? "initiative.owner.low_health_fight" : "initiative.owner.low_health"));
            }
            // Zwei goldene Äpfel dabei: einen abgeben (einen behält er für seinen eigenen Notfall)
            if (owner.getHealth() <= 4 && e.tickCount - lastAppleTick >= 2400 && e.distanceToSqr(owner) < 6 * 6) {
                int slot = e.findItem(Items.GOLDEN_APPLE);
                if (slot >= 0 && CompanionCraft.count(e, "golden_apple") >= 2) {
                    ItemStack apple = e.getInventory().getItem(slot);
                    lastAppleTick = e.tickCount;
                    if (drop(sl, owner, apple.copyWithCount(1))) {
                        apple.shrink(1);
                        e.tell(CompanionTexts.tr("initiative.owner.apple"));
                    }
                }
            }
        }

        private void noteDeath(ServerLevel sl, Player owner) {
            deathPos = owner.blockPosition().immutable();
            deathDim = CompanionMemory.dim(sl);
            deathTick = e.tickCount;
            snapshotTaken = false;
            deathItems.clear();
            toReturn.clear();
            retrieving = false;
            retrieveMoves = 0;
        }

        private void resetDeath() {
            deathPos = null;
            deathItems.clear();
            toReturn.clear();
            retrieving = false;
        }

        /** Nach dem Wiederbeleben: zum Todesort, die eigenen Items des Besitzers einsammeln und ihm zurückgeben. */
        private void retrieve(ServerLevel sl, Player owner) {
            if (!CompanionMemory.dim(sl).equals(deathDim) || e.tickCount - deathTick > 6000) {
                if (!toReturn.isEmpty()) giveBack(sl, owner);
                resetDeath();
                return;
            }
            if (!snapshotTaken) {
                snapshotTaken = true;
                for (ItemEntity ie : sl.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(deathPos).inflate(4),
                        ie -> ie.isAlive() && ie.getAge() < 400)) {
                    deathItems.add(ie.getUUID());
                    toReturn.add(ie.getItem().copy());
                }
                boolean reachable = !sl.getFluidState(deathPos).is(net.minecraft.tags.FluidTags.LAVA)
                        && deathPos.getY() > sl.getMinY() && e.blockPosition().distSqr(deathPos) < 160 * 160;
                if (deathItems.isEmpty() || !reachable) {
                    resetDeath();
                    return;
                }
                retrieving = true;
                e.tell(CompanionTexts.tr("initiative.owner.death", deathPos.toShortString()));
            }
            List<ItemEntity> left = new ArrayList<>();
            for (ItemEntity ie : sl.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(deathPos).inflate(8),
                    ie -> ie.isAlive() && deathItems.contains(ie.getUUID()))) left.add(ie);
            if (retrieving && !left.isEmpty() && retrieveMoves < 6) {
                if (e.tickCount - lastMoveTick >= 200 && !hasMoveTo(deathPos)) {
                    ItemEntity next = left.stream().min(java.util.Comparator.comparingDouble(e::distanceToSqr)).orElseThrow();
                    e.pushFront(Task.moveTo(next.blockPosition(), 0));
                    lastMoveTick = e.tickCount;
                    retrieveMoves++;
                }
                return;
            }
            retrieving = false;
            if (toReturn.isEmpty() || e.distanceToSqr(owner) > 4 * 4) {
                if (!toReturn.isEmpty() && !e.hasTasks() && e.tickCount - lastMoveTick >= 200) {
                    e.addTask(Task.moveTo(owner.blockPosition(), 4));
                    lastMoveTick = e.tickCount;
                }
                return;
            }
            giveBack(sl, owner);
            resetDeath();
        }

        private boolean hasMoveTo(BlockPos near) {
            Task cur = e.currentTask();
            return cur != null && cur.type() == CompanionEntity.TaskType.MOVE_TO && cur.pos() != null && cur.pos().distSqr(near) < 10 * 10;
        }

        /** Gibt zurück, was er von den Items des Besitzers aufgehoben hat (gleiches Item mit gleichen Daten). */
        private void giveBack(ServerLevel sl, Player owner) {
            int given = 0;
            for (ItemStack wanted : toReturn) {
                int need = wanted.getCount();
                for (int i = 0; i < e.getInventory().getContainerSize() && need > 0; i++) {
                    ItemStack s = e.getInventory().getItem(i);
                    if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, wanted)) continue;
                    int n = Math.min(need, s.getCount());
                    if (!drop(sl, owner, s.copyWithCount(n))) continue;
                    s.shrink(n);
                    need -= n;
                    given += n;
                }
            }
            toReturn.clear();
            if (given > 0) e.tell(CompanionTexts.tr("initiative.owner.returned", given));
        }

        /** Wie beim Abgeben: an eine sichere Stelle vor dem Besitzer werfen. */
        private boolean drop(ServerLevel sl, Player owner, ItemStack stack) {
            BlockPos at = e.items.safeDropSpot(sl, owner);
            if (at == null || stack.isEmpty()) return false;
            ItemEntity ie = new ItemEntity(sl, at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5, stack, 0, 0.12, 0);
            ie.setDefaultPickUpDelay();
            if (!sl.addFreshEntity(ie)) return false;
            e.getLookControl().setLookAt(owner);
            e.swingArm();
            e.collectBlockedUntil = e.tickCount + 300;
            return true;
        }
    }

    // ------------------------------------------------------------------ 3: Gewohnheiten erkennen

    /** Zählt, wie oft der Besitzer dasselbe Item bestellt, und schlägt nach dem dritten Mal eine Vorratsregel vor. */
    public static final class Habits {
        static final int SUGGEST_AFTER = 3;
        private static final Set<String> YES = Set.of("ja", "ja bitte", "jo", "jap", "gerne", "gern", "klar", "ok", "okay",
                "mach ruhig", "yes", "yes please", "sure", "do it", "yep");
        private static final Set<String> NO = Set.of("nein", "nee", "nein danke", "no", "no thanks", "nope");
        /** Item-ID → [Bestellungen seit dem letzten Vorschlag, größte Menge]; gespeichert. */
        final Map<String, int[]> asks = new HashMap<>();
        /** Abgelehnte Vorschläge (Item-IDs); gespeichert. */
        final Set<String> declined = new HashSet<>();
        private Item pending;
        private int pendingAmount;
        private long pendingUntil;
        private UUID pendingOwner;

        /** Nach einer Bestellung aufrufen; liefert den Vorschlag als Zusatz zur Antwort oder null. */
        Component note(CompanionEntity e, ServerPlayer player, Item item, int amount) {
            if (item == null || player == null || !e.isOwner(player) || item.getDefaultMaxStackSize() <= 1) return null;
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            String id = key.toString();
            if (declined.contains(id) || asks.size() >= InitiativeStore.MAX_ASKS && !asks.containsKey(id)) return null;
            if (e.stockRules.stream().anyMatch(rule -> rule.item().equals(key))) return null;
            int[] a = asks.computeIfAbsent(id, k -> new int[2]);
            a[0]++;
            a[1] = Math.max(a[1], amount);
            if (a[0] < SUGGEST_AFTER || e.getHome() == null || e.stockRules.size() >= CompanionStockRoutine.MAX_RULES) return null;
            int times = a[0];
            a[0] = 0;
            pending = item;
            pendingAmount = suggestedAmount(a[1], item.getDefaultMaxStackSize());
            pendingUntil = e.level().getGameTime() + 2400;
            pendingOwner = player.getUUID();
            return CompanionTexts.tr("initiative.habit.suggest", times, CompanionTexts.item(key.toString()), pendingAmount);
        }

        /** Doppelt so viel wie die größte Bestellung, auf 8 aufgerundet, höchstens ein Stapel. */
        static int suggestedAmount(int largest, int stack) {
            int n = Math.max(8, ((Math.max(1, largest) * 2 + 7) / 8) * 8);
            return Math.min(n, Math.max(1, stack));
        }

        /** "ja"/"nein" auf einen offenen Vorschlag; sonst null. */
        Component answer(CompanionEntity e, ServerPlayer player, String text) {
            if (pending == null || player == null || !player.getUUID().equals(pendingOwner)) return null;
            if (e.level().getGameTime() > pendingUntil) { pending = null; return null; }
            String t = text.strip().toLowerCase(Locale.ROOT).replaceAll("[.!?,]", "").replaceAll("\\s+", " ").trim();
            Item item = pending;
            if (YES.contains(t)) {
                pending = null;
                return CompanionStockRoutine.addRule(player.getUUID(), e, BuiltInRegistries.ITEM.getKey(item), pendingAmount);
            }
            if (NO.contains(t)) {
                pending = null;
                declined.add(BuiltInRegistries.ITEM.getKey(item).toString());
                return CompanionTexts.tr("initiative.habit.declined");
            }
            return null;
        }

        public boolean hasSuggestion() { return pending != null; }

        /** Wie oft dieses Item seit dem letzten Vorschlag bestellt wurde (Tests, Diagnose). */
        public int asked(Item item) {
            int[] a = asks.get(BuiltInRegistries.ITEM.getKey(item).toString());
            return a == null ? 0 : a[0];
        }

        public boolean declined(Item item) { return declined.contains(BuiltInRegistries.ITEM.getKey(item).toString()); }

        /** Registriertes, stapelbares Item zur ID; sonst null (fremde oder entfernte Items aus alten Speicherständen). */
        static Item item(String id) {
            Identifier key = Identifier.tryParse(id);
            if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return null;
            Item item = BuiltInRegistries.ITEM.getValue(key);
            return item == Items.AIR || item.getDefaultMaxStackSize() <= 1 ? null : item;
        }
    }

    // ------------------------------------------------------------------ 4: Basis ausbauen und sichern

    /** Weizenfeld neben dem Haus anlegen und dunkle Stellen um das Haus mit Fackeln gegen Monster sichern. */
    public static final class BaseCare {
        static final int FIELD_MIN_FARMLAND = 12;
        static final int MAX_TORCHES = 8;
        private final CompanionEntity e;
        private int fieldCheckedAt = -100_000, fieldTriedUntil;
        private boolean hasField;
        private int lightCheckedAt = -100_000;
        private List<BlockPos> dark = List.of();
        /** Schon einmal mit einer Fackel versuchte Stellen (bis Tick): nicht in einer Schleife immer wieder hin. */
        private final Map<BlockPos, Integer> tried = new HashMap<>();

        BaseCare(CompanionEntity e) { this.e = e; }

        private BlockPos center() {
            BlockPos home = e.getHome();
            return home != null ? home : e.getHouseOrigin();
        }

        /** Gibt es um Haus/Lager schon ein Feld (genug Ackerboden)? Höchstens alle 2 min neu gezählt. */
        public boolean hasField(ServerLevel sl, boolean refresh) {
            BlockPos c = center();
            if (c == null) return false;
            if (refresh || e.tickCount - fieldCheckedAt >= 2400) {
                fieldCheckedAt = e.tickCount;
                int n = 0;
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                outer:
                for (int dx = -24; dx <= 24; dx++)
                    for (int dz = -24; dz <= 24; dz++)
                        for (int dy = -4; dy <= 4; dy++) {
                            p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                            if (!sl.hasChunkAt(p)) continue;
                            if (sl.getBlockState(p).is(Blocks.FARMLAND) && ++n >= FIELD_MIN_FARMLAND) break outer;
                        }
                hasField = n >= FIELD_MIN_FARMLAND;
            }
            return hasField;
        }

        /** Ab Steinwerkzeug (beobachtet: Essen blieb ohne Feld der Engpass), ohne laufenden Bau und ohne Feld: eines anlegen (nach einem Fehlversuch 10 min Pause). */
        public boolean wantsField(ServerLevel sl) {
            if (e.tickCount < fieldTriedUntil || e.build != null || CompanionCraft.tier(e, "pickaxe") < 2) return false;
            return !hasField(sl, false);
        }

        /**
         * Ein Feld reicht nicht für Vorrat und Netherausflug (beobachtet: jede Ernte am selben Tag gegessen, nie die
         * Mahlzeiten für die Reise beisammen): ein zweites anlegen, solange weniger als zwei Felder Ackerboden da sind.
         */
        public boolean wantsMoreField(ServerLevel sl) {
            if (e.tickCount < fieldTriedUntil || e.build != null || CompanionCraft.tier(e, "pickaxe") < 2) return false;
            BlockPos c = center();
            if (c == null) return false;
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-24, -4, -24), c.offset(24, 4, 24))) {
                if (!sl.hasChunkAt(p)) continue;
                if (sl.getBlockState(p).is(Blocks.FARMLAND) && ++n >= 80) return false;
            }
            return true;
        }

        /** Bauplatz suchen und den Feldbau als Bauauftrag einreihen. */
        public boolean startField(ServerLevel sl) {
            fieldTriedUntil = e.tickCount + 12_000;
            // erst das große Feld, im hügeligen Gelände notfalls ein kleineres (Fundament gleicht Stufen aus)
            int size = 7;
            BlockPos site = findFieldSite(sl, size + 2);
            if (site == null) site = findFieldSite(sl, (size = 5) + 2);
            if (site == null) {
                e.logInfo(CompanionTexts.tr("initiative.base.no_field_site"));
                return false;
            }
            var s = CompanionStructures.wheatFarm(size);
            var raw = CompanionSchematics.place(s, BlockPos.ZERO, Direction.NORTH, Rotation.NONE, true);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (BlockPos p : raw.keySet()) {
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            }
            int dx = site.getX() - minX, dy = site.getY() - 1 - minY, dz = site.getZ() - minZ;
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            for (var en : raw.entrySet()) blocks.put(en.getKey().offset(dx, dy, dz), en.getValue());
            CompanionStructures.addFoundation(sl, blocks, Blocks.DIRT);
            CompanionBuilder.BuildJob job = CompanionBrain.buildJob(sl, s.name(), blocks, false);
            if (job == null) return false;
            e.build = job;
            e.queueBuild();
            return true;
        }

        /** Ebene 9x9-Fläche aus gewachsenem Boden in 8–28 Blöcken Abstand, nicht auf Haus oder Lager. */
        BlockPos findFieldSite(ServerLevel sl) { return findFieldSite(sl, 9); }

        BlockPos findFieldSite(ServerLevel sl, int n) {
            BlockPos h = e.getHouseOrigin();
            if (h == null) return null;
            int houseMaxX = h.getX() + 4 + 4 * e.houseAnnexes();
            BlockPos home = e.getHome();
            for (int i = 0; i < 160; i++) {
                int x = h.getX() + e.getRandom().nextInt(57) - 28;
                int z = h.getZ() + e.getRandom().nextInt(57) - 28;
                // Abstand zum Haus (mit 3 Blöcken Rand) und zur Lagerkiste
                boolean overlapsHouse = x + n - 1 >= h.getX() - 3 && x <= houseMaxX + 3 && z + n - 1 >= h.getZ() - 3 && z <= h.getZ() + 7;
                if (overlapsHouse) continue;
                if (home != null && home.getX() >= x - 3 && home.getX() <= x + n + 2 && home.getZ() >= z - 3 && home.getZ() <= z + n + 2) continue;
                BlockPos site = fieldSiteAt(sl, x, z, n);
                if (site != null && Math.abs(site.getY() - h.getY()) <= 4) return site;
            }
            return null;
        }

        /** Wie {@link CompanionHouse#buildSiteAt}, für n×n und 3 Blöcke frei darüber. Rückgabe: erste Luftschicht. */
        static BlockPos fieldSiteAt(ServerLevel sl, int x, int z, int n) {
            if (!sl.hasChunkAt(new BlockPos(x, 64, z)) || !sl.hasChunkAt(new BlockPos(x + n - 1, 64, z + n - 1))) return null;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int dx = 0; dx < n; dx++)
                for (int dz = 0; dz < n; dz++) {
                    int top = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                    BlockPos g = new BlockPos(x + dx, top - 1, z + dz);
                    BlockState gs = sl.getBlockState(g);
                    if (!gs.getFluidState().isEmpty() || gs.is(BlockTags.LEAVES) || gs.is(BlockTags.LOGS)
                            || gs.getCollisionShape(sl, g).isEmpty() || !CompanionHouse.naturalGround(gs)) return null;
                    for (int up = 0; up < 3; up++) {
                        BlockState above = sl.getBlockState(g.above(1 + up));
                        if (!above.isAir() && !above.canBeReplaced()) return null;
                    }
                    min = Math.min(min, top);
                    max = Math.max(max, top);
                }
            if (max - min > (n >= 9 ? 1 : 2)) return null; // kleines Ausweichfeld: eine Stufe mehr gleicht addFoundation aus
            return new BlockPos(x, max, z);
        }

        /** Stellen um das Haus ohne jedes Blocklicht (dort spawnen nachts Monster), höchstens 8, je 6 Blöcke auseinander. */
        public List<BlockPos> darkSpots(ServerLevel sl, boolean fresh) {
            if (!fresh && e.tickCount - lightCheckedAt < 2400) return dark;
            lightCheckedAt = e.tickCount;
            BlockPos in = e.houseInside();
            if (in == null) return dark = List.of();
            List<BlockPos> out = new ArrayList<>();
            outer:
            for (int dx = -12; dx <= 12; dx += 3)
                for (int dz = -12; dz <= 12; dz += 3) {
                    int x = in.getX() + dx, z = in.getZ() + dz;
                    if (!sl.hasChunkAt(new BlockPos(x, in.getY(), z))) continue;
                    int top = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (Math.abs(top - in.getY()) > 3) continue;
                    BlockPos p = new BlockPos(x, top, z);
                    if (!sl.getBlockState(p).isAir()) continue;
                    BlockPos below = p.below();
                    BlockState g = sl.getBlockState(below);
                    if (!g.isFaceSturdy(sl, below, Direction.UP) || g.is(Blocks.FARMLAND) || g.is(BlockTags.LEAVES)
                            || !g.getFluidState().isEmpty()) continue;
                    if (sl.getBrightness(LightLayer.BLOCK, p) > 0 || e.tickCount < tried.getOrDefault(p, Integer.MIN_VALUE)) continue;
                    if (out.stream().anyMatch(o -> o.distSqr(p) < 6 * 6)) continue;
                    out.add(p);
                    if (out.size() >= MAX_TORCHES) break outer;
                }
            return dark = out;
        }

        /** Tagsüber dunkle Stellen da und Fackeln vorhanden bzw. herstellbar? */
        public int lightingWanted(ServerLevel sl) {
            if (sl.isDarkOutside() || e.houseInside() == null) return 0;
            boolean torches = CompanionCraft.count(e, "torch") > 0 || CompanionCraft.count(e, "#coals") > 0;
            return torches ? darkSpots(sl, false).size() : 0;
        }

        /** Fackeln herstellen und an die dunklen Stellen setzen; true = Aufgaben eingereiht. */
        public boolean startLighting(ServerLevel sl) {
            List<BlockPos> spots = darkSpots(sl, true);
            for (int i = 0; i < 4 && CompanionCraft.count(e, "torch") < spots.size(); i++)
                if (!e.craftTorches()) break;
            int torches = CompanionCraft.count(e, "torch");
            int n = Math.min(torches, spots.size());
            tried.values().removeIf(until -> until <= e.tickCount);
            for (int i = 0; i < n; i++) {
                e.addTask(Task.place(spots.get(i), Blocks.TORCH));
                tried.put(spots.get(i), e.tickCount + 24_000);
            }
            lightCheckedAt = e.tickCount; // nach den Fackeln erst in 2 min neu nachsehen
            dark = List.of();
            return n > 0;
        }
    }

    // ------------------------------------------------------------------ 7: Gezielt graben

    /**
     * Merkt sich, welche 16×16-Felder er auf Erztiefe schon abgesucht hat, und gräbt bevorzugt in die Richtung mit den
     * wenigsten besuchten Feldern voraus (das Erz-Gespür deckt etwa zwei Felder zu jeder Seite ab).
     */
    public static final class MiningCells {
        static final int CELL = 16;
        /** Feldschlüssel → Besuche; gespeichert. */
        final Map<Long, Integer> visits = new HashMap<>();

        public Direction direction(CompanionEntity mob, ServerLevel sl, Direction current) {
            BlockPos at = mob.blockPosition();
            visits.merge(key(at), 1, Integer::sum);
            if (visits.size() > InitiativeStore.MAX_CELLS) visits.clear();
            Direction best = current;
            int bestScore = Integer.MAX_VALUE;
            for (Direction d : new Direction[]{current, current.getClockWise(), current.getCounterClockWise(), current.getOpposite()}) {
                if (mob.memory.dangerous(sl, at.relative(d, 8), 6)) continue;
                Direction side = d.getClockWise();
                int score = 0;
                for (int ahead = 1; ahead <= 3; ahead++)
                    for (int lateral = -1; lateral <= 1; lateral++)
                        score += visits.getOrDefault(key(at.relative(d, ahead * CELL).relative(side, lateral * CELL)), 0);
                score = score * 4 + (d == current ? 0 : d == current.getOpposite() ? 2 : 1);
                if (score < bestScore) { bestScore = score; best = d; }
            }
            return best;
        }

        public int visited() { return visits.size(); }

        static long key(BlockPos p) {
            return ((long) Math.floorDiv(p.getX(), CELL) << 32) ^ (Math.floorDiv(p.getZ(), CELL) & 0xffffffffL);
        }
    }
}
