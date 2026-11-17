package dev.begleiter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.nav.BlockFlags;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.task.TaskSpec;

/**
 * Der Begleiter: ein "zweiter Spieler" mit 36-Slot-Inventar, Werkzeug-/Rüstungsnutzung,
 * Aufgabenwarteschlange und einem eigenen Überlebens-Planer (siehe CompanionBrain).
 */
public class CompanionEntity extends PathfinderMob {

    public enum TaskType { BREAK, PLACE, COLLECT, DELIVER, GIVE, CRAFT, SMELT, SORT, MOVE_TO, HUNT, FETCH, HOUSE, GATHER, STATION, TREE, CLIMB, DESCEND, MINE, WATER, POUR, PORTAL, PILLAR, TRAVEL, FIGHT, STRONGHOLD, CRYSTALS, BUILD, TAKE, EXTEND,
        FARM, BREED, TRADE, REPAIR, GEARUP, DOOR, REQUEST, PROCESS }

    /**
     * Eine Aufgabe der Queue: typisierte Angaben ({@link TaskSpec}) plus Versuchszähler. Gleichheit nach Wert; der
     * laufende Handler hängt an der Identität (TaskSlot).
     */
    public record Task(TaskSpec spec, int tries) {
        public Task {
            java.util.Objects.requireNonNull(spec, "spec");
        }

        public Task(TaskSpec spec) { this(spec, 0); }

        public TaskType type() { return spec.type(); }
        public BlockPos pos() { return spec.pos(); }
        public Block block() { return spec.block(); }

        /** Dieselbe Aufgabe als nächster Versuch. */
        public Task retry() { return new Task(spec, tries + 1); }

        public static Task moveTo(BlockPos pos) { return moveTo(pos, 0); }
        public static Task moveTo(BlockPos pos, int reachSqr) { return new Task(new TaskSpec.MoveTo(pos, reachSqr)); }
        public static Task give(String what, int max) { return new Task(new TaskSpec.Give(what, max)); }
        public static Task place(BlockPos pos, Block block) { return new Task(new TaskSpec.Place(pos, block)); }
        public static Task breakAt(BlockPos pos, Block block) { return new Task(new TaskSpec.Break(pos, block)); }
        public static Task collect(boolean anyAge) { return new Task(new TaskSpec.Collect(anyAge)); }
        public static Task deliver(boolean keepStock) { return new Task(new TaskSpec.Deliver(keepStock)); }
        public static Task sort(BlockPos pos) { return new Task(new TaskSpec.Sort(pos)); }
        public static Task take(BlockPos pos, String item, int count) { return new Task(new TaskSpec.Take(pos, item, count)); }
        public static Task craft(String item, int count) { return new Task(new TaskSpec.Craft(item, count)); }
        public static Task process(String item, int count, String recipe) { return new Task(new TaskSpec.Process(item, count, recipe)); }
        public static Task request(String item, int count, boolean materials) {
            return new Task(new TaskSpec.Request(item, count, materials, 0, 0));
        }
        public static Task smelt(String item, int count) { return new Task(new TaskSpec.Smelt(item, count)); }
        public static Task smeltAnything() { return smelt(null, 0); }
        public static Task station(String item, boolean portable) { return new Task(new TaskSpec.Station(item, portable)); }
        public static Task repair(int belowPercent) { return new Task(new TaskSpec.Repair(belowPercent)); }
        public static Task door(BlockPos pos, String facing) { return new Task(new TaskSpec.Door(pos, facing)); }
        public static Task gather(String resource, int count) { return new Task(new TaskSpec.Gather(resource, count)); }
        public static Task mine(String resource, int count) { return new Task(new TaskSpec.Mine(resource, count)); }
        public static Task tree(BlockPos base, int logs) { return new Task(new TaskSpec.Tree(base, logs)); }
        public static Task climb(BlockPos pos) { return new Task(new TaskSpec.Climb(pos)); }
        public static Task descend() { return new Task(new TaskSpec.Descend()); }
        public static Task pillar(BlockPos top) { return new Task(new TaskSpec.Pillar(top)); }
        public static Task hunt(int food) { return new Task(new TaskSpec.Hunt(food)); }
        public static Task fetch(String animal) { return new Task(new TaskSpec.Fetch(animal)); }
        public static Task fight(BlockPos rally, String mob, String loot, int lootCount) { return new Task(new TaskSpec.Fight(rally, mob, loot, lootCount)); }
        public static Task crystals() { return new Task(new TaskSpec.Crystals()); }
        public static Task breed(String species, int pairs) { return new Task(new TaskSpec.Breed(species, pairs)); }
        public static Task farm(BlockPos center) { return new Task(new TaskSpec.Farm(center)); }
        public static Task trade(String item, int count) { return new Task(new TaskSpec.Trade(item, count)); }
        public static Task build() { return new Task(new TaskSpec.Build()); }
        public static Task house(BlockPos site) { return new Task(new TaskSpec.House(site)); }
        public static Task extend(int annex) { return new Task(new TaskSpec.Extend(annex)); }
        public static Task portal(BlockPos site) { return new Task(new TaskSpec.Portal(site)); }
        public static Task water(BlockPos source) { return new Task(new TaskSpec.Water(source)); }
        public static Task pour(BlockPos lava) { return new Task(new TaskSpec.Pour(lava)); }
        public static Task travel(BlockPos target) { return new Task(new TaskSpec.Travel(target)); }
        public static Task stronghold() { return new Task(new TaskSpec.Stronghold()); }
    }

    public static final int HOUSE_NEED = 96;
    private static final Set<String> FOOD_ANIMALS = RegistryIds.minecraftSet("cow", "pig", "sheep", "chicken");
    private static final Set<String> FLYING_TARGETS = RegistryIds.minecraftSet("blaze", "ender_dragon", "phantom", "ghast");

    private final SimpleContainer inventory = new SimpleContainer(36);
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private UUID ownerUuid;
    private BlockPos homePos;
    private boolean staying;
    private boolean keeper;
    private boolean paused;
    private boolean autonomous = true;
    /** Gespeicherte Arbeitsbereiche; unabhängig vom gerade gewählten Modus. */
    public final CompanionZones zones = new CompanionZones();
    public long dutyStorageFingerprint = Long.MIN_VALUE;
    private String dutyDimension;
    /** Daueraufgabe an dutyPos oder im zugehörigen Arbeitsbereich; null = keine. */
    private CompanionDuties.Duty duty;
    private BlockPos dutyPos;
    private int dutyNextTick;
    /** Hinweis "kein Futter / nichts zu verkaufen" schon gesagt (nicht gespeichert, damit er nicht nervt). */
    boolean dutyHintGiven;
    /** Zählt Chatbefehle (nicht gespeichert); eine späte LLM-Antwort gilt nur, wenn seitdem kein neuer kam. */
    int chatSerial;
    /** Kurzer Gesprächsverlauf für freie LLM-Antworten (nicht gespeichert). */
    final dev.begleiter.llm.ChatHistory llmHistory = new dev.begleiter.llm.ChatHistory();
    private boolean houseBuilt;
    private BlockPos portalPos; // Netherportal in der Oberwelt (Bauplatz-Ecke)
    // Nether-Ausflug: eigener Plan, folgt dem Besitzer dann nicht zurück
    public boolean netherTrip;
    public BlockPos netherPortal; // Ankunftsportal im Nether
    public BlockPos fortressPos;  // Netherfestung (Nether-Koordinaten)
    // Festung in der Oberwelt
    public BlockPos endRoomPos;   // Mitte des Portalraums
    public BlockPos endPortalPos; // aktiviertes Endportal
    public int crystalRounds;     // Runden "Kristalle abschießen" im End (Laufzeit)
    /** Herde halten: bei der Jagd immer ein Zuchtpaar je Art stehen lassen (nach dem ersten Zuchtauftrag). */
    public boolean herdKeeping;
    /** Türen/Zauntore, die er selbst geöffnet hat (untere Hälfte -> Tick), um sie hinter sich zu schließen. */
    public final Map<BlockPos, Integer> openedDoors = new HashMap<>();

    public void noteOpenedDoor(BlockPos p) {
        if (level() instanceof ServerLevel sl) openedDoors.putIfAbsent(CompanionDoors.lower(sl, p).immutable(), tickCount);
    }
    /** Kampfmodus: aggressiv (Standard), defensiv (nur verteidigen), passiv (nicht kämpfen). */
    public CompanionCombat.Stance stance = CompanionCombat.Stance.AGGRESSIVE;
    /** Zwischenspeicher für teure Umgebungs-Scans der Pflege-Bedürfnisse (Tick, reife Pflanzen am Feld). */
    public int careScanTick = -100_000, careMatureCrops;
    // Laufender Bauauftrag (Laufzeit)
    public CompanionBuilder.BuildJob build;
    /** Was er gesehen hat: Erze, Bäume, Wasser, Kisten, Gefahren ... (wird gespeichert, überlebt den Tod im Kern). */
    public final CompanionMemory memory = new CompanionMemory();
    /** Laufzeit-Lernsignal; begrenzt, speichert keine sensiblen Auftragsdaten. */
    public final dev.begleiter.ai.CompanionExperienceMemory experiences = new dev.begleiter.ai.CompanionExperienceMemory();
    public final CompanionRequestDialogue requestDialogue = new CompanionRequestDialogue();
    public final CompanionPlanFeedback planFeedback = new CompanionPlanFeedback();
    /** Bestätigter, aber noch nicht ausführbarer Projektentwurf. */
    public final CompanionProjektVerwaltung projekt = new CompanionProjektVerwaltung();
    /** Rohstoff, den der eigene Vorratsplan gerade auffüllt. */
    public String activeSupplyResource;
    /** Erster Beschaffungsweg des laufenden Vorratsplans. */
    public String activeSupplyApproach;

    /** Fehlschläge nur aus eigenen Plänen merken; Befehle des Besitzers bringen ihm nie ein schlechtes Ergebnis bei. */
    public void reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason reason, Task failedTask) {
        if (goal == null) return;
        if (planFeedback.failed(this, reason, failedTask)) return;
        var source = CompanionSources.failureSource(this, goal, reason, failedTask);
        if (source != null) {
            experiences.sources.failed(source, reason, tickCount);
            return;
        }
        String target = goal == CompanionNeeds.Need.SUPPLY ? activeSupplyResource
                : failedTask == null ? null : failureTarget(failedTask.spec());
        experiences.record(new dev.begleiter.ai.DecisionOutcome(goal, reason, target, tickCount));
        if (goal == CompanionNeeds.Need.SUPPLY && target != null && activeSupplyApproach != null) {
            // Ein mehrstufiger Plan baut vielleicht erst ab und geht dann zur Kiste, oder craftet erst Werkzeug:
            // bestraft wird der Schritt, der wirklich scheiterte
            String actualMethod = dev.begleiter.ai.AdaptiveAcquisition.failedApproach(
                    failedTask, activeSupplyApproach);
            experiences.recordTargetOnly(new dev.begleiter.ai.DecisionOutcome(
                    goal, reason, target + "_" + actualMethod, tickCount));
        }
        consoleDebug("Intelligence: " + goal + " failed (" + reason
                + (target == null ? "" : ", " + target) + ")");
    }

    /** Schwimmen, Tauchen, aus Strömungen befreien. */
    public final CompanionSwim swim = new CompanionSwim(this);
    /** Aktuelles Ziel des Planers (null = Spieler-Auftrag oder nichts), mit Dringlichkeit und Begründung. */
    public CompanionNeeds.Need goal;
    public int goalScore;
    public String goalReason = "";
    private Component goalReasonText = Component.empty();
    private String goalReasonSource = "";
    /** Herstellen schlug fehl: Item-ID -> bis zu diesem Tick nicht wieder als eigenes Ziel versuchen. */
    public final Map<String, Integer> craftFailedUntil = new HashMap<>();
    public boolean craftBlocked(String id) {
        return tickCount < craftFailedUntil.getOrDefault(ItemIds.canonicalOrOriginal(id), 0);
    }
    /** Bedürfnisse, die sich zuletzt nicht umsetzen ließen: bis dahin kein Unterbrechen deswegen. */
    public final java.util.Map<CompanionNeeds.Need, Integer> needBlockedUntil = new java.util.EnumMap<>(CompanionNeeds.Need.class);
    public int buildRetryAt;

    // Laufzeit-Zustand (wird nicht gespeichert)
    public Mob huntTarget;
    public Mob fetchTarget;
    public boolean fetchLeashed;
    public int collectBlockedUntil;
    public int plannerCooldownUntil;
    public int lastDeliverQueued = -10000;
    public double fuelLeft;
    public String lastStage = "";
    private Component lastStageText = Component.empty();
    private String lastStageSource = "";
    public String lastCraftProblem = "";
    private Component lastCraftProblemText = Component.empty();
    private String lastCraftProblemSource = "";
    public String problemText = "";
    private Component lastProblemText = Component.empty();
    private String lastProblemSource = "";
    public int problemTick = -100000;
    private Component lastTold = Component.empty();
    private int lastToldTick = -100000;
    private int lastWorkTick;
    private int targetSince = -1;
    private int ignoreFarTargetsUntil;
    private BlockPos tablePos;
    private BlockPos furnacePos;
    private BlockPos houseOrigin;
    /** Anzahl Lager-Anbauten hinten am Haus (je 5x5, für mehr Kisten). Noch in Arbeit. */
    private int houseAnnexes;
    public int hunger = 20;
    /** Bis zu diesem Tick steuert die neue Wegfindung (dev.begleiter.nav) Bewegung und Sprint. */
    public int navControlUntil = -1;
    public final dev.begleiter.nav.MotionController motion = new dev.begleiter.nav.MotionController(this);
    /** Wegfindung (dev.begleiter.nav). */
    public final dev.begleiter.nav.Navigator nav = new dev.begleiter.nav.Navigator(this);
    /** Trampelpfade der neuen Wegfindung (pro Welt, gespeichert). */
    public final dev.begleiter.nav.RouteMemory routes = new dev.begleiter.nav.RouteMemory();
    private int hungerTimer;
    private double exploreAngle = Double.NaN;
    public BlockPos nearestLava;
    public BlockPos nearestWater;
    // Hochbauen (Gerüst) beim Baumfällen / für hohe Ziele
    public final List<BlockPos> scaffold = new ArrayList<>();
    private boolean pillarActive;
    /** Art des laufenden Sprungs (beim Absprung festgelegt, nicht vom Aufrufer des Lande-Ticks). */
    private Pillar pillarKind = Pillar.PATH;
    private int pillarStartY, pillarX, pillarZ;
    private int pillarTicks;
    public BlockPos pillarObstacle;
    // Bergbau: Richtung von Treppe und Stollen
    public Direction mineDir;
    /**
     * Seine Mine: tiefster Stollenpunkt (Oberwelt), dauerhaft gemerkt – dahin geht er beim nächsten Graben zurück.
     * Gilt nur in der Welt, in der er sie gegraben hat (Seed): der Kern nimmt sein Gedächtnis in andere Welten mit.
     */
    private BlockPos minePos;
    private long mineSeed;
    public int mineTripFails;

    public BlockPos mine() {
        // wie Lager und Portal nur in der Oberwelt: im Nether lägen dieselben Koordinaten woanders
        return minePos != null && inOverworld() && level() instanceof ServerLevel sl && sl.getSeed() == mineSeed ? minePos : null;
    }

    public void setMine(BlockPos p) {
        if (p != null && !inOverworld()) return; // Stollen im Nether/End überschreibt die Oberwelt-Mine nicht
        minePos = p == null ? null : p.immutable();
        if (p != null && level() instanceof ServerLevel sl) mineSeed = sl.getSeed();
    }
    // Überleben
    private int retreatUntil;
    private LivingEntity fleeFrom;
    private int lastTorchTick = -1000;
    public boolean doorClosed;

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        guaranteeEquipmentDrops();
    }

    /**
     * Ausrüstung fällt beim Tod immer und unbeschädigt. Erst Werte über 1 gelten bei Minecraft als „sicher“; mit 1.0
     * fiel sie nur nach einem Spielertreffer und bekam zufälligen Haltbarkeitsschaden. Minecraft speichert die Werte in
     * der Welt, darum auch nach dem Laden neu setzen.
     */
    private void guaranteeEquipmentDrops() {
        for (EquipmentSlot slot : EquipmentSlot.values())
            setDropChance(slot, net.minecraft.world.entity.DropChances.PRESERVE_ITEM_DROP_CHANCE);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new CompanionSwim.Float(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2, true));
        goalSelector.addGoal(2, new CompanionTaskGoal(this));
        goalSelector.addGoal(3, new CompanionFollowGoal(this));
        goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        // Wer den Besitzer angreift, kommt zuerst dran; dann wer ihn selbst angreift (außer passiv)
        targetSelector.addGoal(1, new CompanionCombat.DefendOwnerGoal(this, false));
        targetSelector.addGoal(2, new HurtByTargetGoal(this) {
            @Override public boolean canUse() { return stance != CompanionCombat.Stance.PASSIVE && super.canUse(); }
        });
        targetSelector.addGoal(3, new CompanionCombat.DefendOwnerGoal(this, true));
        // Neutrale Mobs (Endermen …) nicht von selbst angreifen (wie ein Spieler); Kampfmodus entscheidet den Rest
        targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                (t, sl) -> CompanionCombat.wantsToEngage(this, t) && !isIgnoredTarget(t)));
    }

    @Override
    public boolean removeWhenFarAway(double distanceSqr) {
        return false;
    }

    // ------------------------------------------------------------------ Besitzer / Zustand

    public void setOwner(UUID uuid) { this.ownerUuid = uuid; }
    /** Standardname eines neuen Begleiters; Eigenname, darum in jeder Sprache gleich. */
    public static final String DEFAULT_NAME = "Leak";
    /** Früherer Standardname; nur genau dieser wird beim Laden ersetzt, eigene Namen bleiben. */
    static final String OLD_DEFAULT_NAME = "Begleiter";

    static String loadedName(String saved) {
        return saved == null || saved.isBlank() || saved.equals(OLD_DEFAULT_NAME) ? DEFAULT_NAME : saved;
    }

    public boolean isOwner(Player p) { return p.getUUID().equals(ownerUuid); }
    public Player getOwner() { return ownerUuid == null ? null : level().getPlayerByUUID(ownerUuid); }
    /** Besitzer auch in einer anderen Dimension. */
    public ServerPlayer getOwnerAnywhere() {
        if (ownerUuid == null || !(level() instanceof ServerLevel sl)) return null;
        return sl.getServer().getPlayerList().getPlayer(ownerUuid);
    }
    public SimpleContainer getInventory() { return inventory; }
    public boolean inOverworld() { return level().dimension() == Level.OVERWORLD; }
    /** Lager, Haus und Portal liegen in der Oberwelt – in anderen Dimensionen gibt es sie nicht. */
    public BlockPos getHome() { return inOverworld() ? homePos : null; }
    public BlockPos getPortalPos() { return inOverworld() ? portalPos : null; }
    public void setPortalPos(BlockPos p) { portalPos = p == null ? null : p.immutable(); }
    public boolean isStaying() { return staying; }
    public boolean isKeeper() { return keeper; }
    /** Pausiert (Spieler oder Debug-Menü); ein Debug-Einzelschritt hebt die Pause für seine Ticks auf. */
    public boolean isPaused() { return paused || (debugFrozen && debugStepTicks <= 0); }
    public boolean isDebugFrozen() { return debugFrozen; }
    public void setDebugFrozen(boolean frozen) { debugFrozen = frozen; if (!frozen) debugStepTicks = 0; }
    public void debugStep(int ticks) { debugFrozen = true; debugStepTicks = ticks; }
    /** Sekunden, die die aktuelle Aufgabe schon läuft. */
    public int actionSeconds() { return (tickCount - trackedSince) / 20; }
    private boolean debugFrozen;
    private int debugStepTicks;
    private Task trackedTask;
    private int trackedSince;
    public boolean isAutonomous() { return autonomous; }
    public boolean isHouseBuilt() { return houseBuilt; }

    public void follow() { staying = false; keeper = false; paused = false; autonomous = false; duty = null; }
    public void freeRoam() { staying = false; keeper = false; paused = false; autonomous = true; duty = null; }
    public void stay() { staying = true; keeper = false; autonomous = false; duty = null; getNavigation().stop(); }
    public CompanionDuties.Duty duty() { return duty; }
    public BlockPos dutyPos() {
        if (duty == null || !(level() instanceof ServerLevel sl) || !CompanionMemory.dim(sl).equals(dutyDimension)) return null;
        var kind = CompanionZones.kindFor(duty);
        var z = kind == null ? null : zones.get(kind, sl);
        return z == null ? dutyPos : z.center();
    }
    /** Daueraufgabe übernehmen: bleibt an {@code pos} und arbeitet dort, sobald er frei ist. */
    public void startDuty(CompanionDuties.Duty d, BlockPos pos) {
        staying = false; keeper = false; paused = false; autonomous = false;
        duty = d; dutyPos = pos.immutable(); dutyDimension = CompanionMemory.dim((ServerLevel) level());
        dutyNextTick = tickCount; dutyHintGiven = false; dutyStorageFingerprint = Long.MIN_VALUE;
    }
    public void setHome(BlockPos pos) { homePos = pos.immutable(); }
    /** Haus komplett vergessen (Testgelände: jedes Haus-Szenario beginnt ohne Haus). */
    public void forgetHouse() { houseOrigin = null; houseBuilt = false; houseAnnexes = 0; doorClosed = false; }
    public void clearHome() { homePos = null; keeper = false; }

    /** Unterwegs aufgestellte Werkbänke/Öfen: nach Gebrauch wieder einpacken. */
    public final List<BlockPos> portableStations = new ArrayList<>();

    /** Hat er die Station dabei oder kann sie sofort herstellen (Werkbank aus Holz, Ofen aus 8 Stein)? */
    public boolean canCarryStation(String id) {
        Item item = CompanionCraft.item(id);
        if (item == Items.AIR) return false;
        if (findItem(item) >= 0) return true;
        if (item == Items.CRAFTING_TABLE) return CompanionCraft.count(this, "#planks") >= 4;
        if (item == Items.FURNACE) return CompanionCraft.count(this, "#stone") >= 8 && findStation("crafting_table", 8) != null;
        return false;
    }

    /** Werkbank/Ofen, die er unterwegs aufgestellt hat, wieder abbauen und einsammeln, wenn er sie nicht mehr braucht. */
    private void packUpStations(ServerLevel sl) {
        if (portableStations.isEmpty()) return;
        Task cur = tasks.peekFirst();
        for (Task t : tasks) if (t.type() == TaskType.CRAFT || t.type() == TaskType.SMELT || t.type() == TaskType.STATION || t.type() == TaskType.PROCESS) return;
        if (cur != null && cur.type() == TaskType.BREAK) return;
        var it = portableStations.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            BlockState st = sl.getBlockState(p);
            boolean station = st.is(Blocks.CRAFTING_TABLE) || st.is(Blocks.FURNACE);
            if (!station || p.distSqr(blockPosition()) > 32 * 32) { it.remove(); continue; }
            if (st.is(Blocks.FURNACE) && containerAt(sl, p) instanceof Container c && !c.isEmpty()) continue; // noch Sachen drin
            it.remove();
            logInfo(EventKind.CRAFT, CompanionTexts.tr("log.station.pack", st.getBlock().getName()));
            tasks.addFirst(Task.collect(false));
            tasks.addFirst(Task.breakAt(p, st.getBlock()));
            return;
        }
    }
    public boolean makeKeeper() {
        if (homePos == null) return false;
        keeper = true; staying = false; autonomous = false; duty = null; return true;
    }
    /** Interne Unterbrechung: Warteschlange leeren, ein offener Bau bleibt für die spätere Wiederaufnahme erhalten. */
    public void cancelAll() {
        planFeedback.finish(this, true);
        activeSupplyResource = activeSupplyApproach = null;
        tasks.clear(); paused = false; huntTarget = null; setTarget(null); pillarActive = false; goal = null;
        releaseFetch();
        getNavigation().stop();
    }
    /** Ausdrücklicher Abbruch durch den Spieler: Warteschlange leeren und einen offenen Bau verwerfen. */
    public void cancelOrders() {
        requestDialogue.clear();
        cancelAll();
        build = null;
        buildRetryAt = 0;
        nav.stop();
    }
    /**
     * Moduswechsel durch den Spieler (Folgen, Warten, …): Warteschlange leeren und einen offenen Bau
     * anhalten, damit er nicht von selbst weiterläuft. Liefert einen Hinweis für die Antwort oder einen leeren Text.
     */
    public Component holdOrdersComponent() {
        requestDialogue.clear();
        cancelAll();
        if (build == null || build.paused) return Component.empty();
        build.paused = true;
        return CompanionTexts.tr("reply.build.held", CompanionTexts.buildName(build.name));
    }
    public void resume() {
        if (paused && hasRecipeRequest()) {
            // Während der Pause kann der Spieler die blockierte Quelle repariert haben
            List<Task> resumed = tasks.stream().map(task -> task.spec() instanceof TaskSpec.Request r && r.phase() == 4
                    ? new Task(new TaskSpec.Request(r.item(), r.count(), r.materials(), r.phase(), r.baseline())) : task).toList();
            tasks.clear();
            tasks.addAll(resumed);
        }
        paused = false;
    }

    public void tell(String msg) {
        tell(CompanionTexts.literal(msg));
    }

    public void tell(Component msg) {
        ServerPlayer owner = getOwnerAnywhere();
        if (owner != null && msg != null) owner.sendSystemMessage(CompanionTexts.prefixed(msg));
    }

    private Component lastUiMessage = Component.empty();
    private String lastUiMessageSource = "";

    public void setUiFeedback(Component message) {
        lastUiMessage = message.copy();
        lastUiAction = message.getString();
        lastUiMessageSource = lastUiAction;
    }

    public Component uiFeedbackComponent() {
        return lastUiAction.equals(lastUiMessageSource) ? lastUiMessage.copy() : CompanionTexts.literal(lastUiAction);
    }

    public void setGoalReason(Component reason) {
        goalReasonText = reason.copy();
        goalReason = reason.getString();
        goalReasonSource = goalReason;
    }

    public Component goalReasonComponent() {
        return goalReason.equals(goalReasonSource) ? goalReasonText.copy() : CompanionTexts.literal(goalReason);
    }

    public void setStage(Component stage) {
        lastStageText = stage.copy();
        lastStage = stage.getString();
        lastStageSource = lastStage;
    }

    public Component stageComponent() {
        return lastStage.equals(lastStageSource) ? lastStageText.copy() : CompanionTexts.literal(lastStage);
    }

    public void setCraftProblem(Component reason) {
        lastCraftProblemText = reason.copy();
        lastCraftProblem = reason.getString();
        lastCraftProblemSource = lastCraftProblem;
    }

    public Component craftProblemComponent() {
        return lastCraftProblem.equals(lastCraftProblemSource) ? lastCraftProblemText.copy() : CompanionTexts.literal(lastCraftProblem);
    }

    /** Planer-Prüfungen vergleichen den Schlüssel der Stufe, nicht den übersetzten Text. */
    public boolean isStage(String key) {
        return stageComponent().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tr
                && tr.getKey().equals("server.begleiter." + key);
    }

    public void pause(String reason) { pause(CompanionTexts.literal(reason)); }

    public void pause(Component reason) {
        pauseWork(CompanionTexts.tr("handler.pause", reason));
    }

    /** Auftrag erhalten und anhalten, ohne einen Materialmangel zu behaupten. */
    public void pauseWork(String reason) { pauseWork(CompanionTexts.literal(reason)); }
    public void pauseWork(Component reason) {
        paused = true;
        tell(reason);
    }

    public void releaseFetch() {
        if (fetchTarget != null && fetchLeashed && fetchTarget instanceof net.minecraft.world.entity.Leashable l && l.getLeashHolder() == this) {
            l.removeLeash();
        }
        fetchTarget = null;
        fetchLeashed = false;
    }

    // ------------------------------------------------------------------ Aufgaben

    // ------------------------------------------------------------------ Log / Debug

    public static final class LogEntry {
        private int tick;
        private final char level;
        private final Component text;
        private final EventKind kind;
        private int count = 1;
        LogEntry(int tick, char level, String text) {
            this(tick, level, CompanionTexts.literal(text), BotDebug.eventKind(text));
        }
        LogEntry(int tick, char level, Component text, EventKind kind) {
            this.tick = tick; this.level = level; this.text = text.copy(); this.kind = kind;
        }
        public int tick() { return tick; }
        public char level() { return level; }
        public String text() { return text.getString(); }
        public Component textComponent() { return text.copy(); }
        public EventKind kind() { return kind; }
        public int count() { return count; }
    }

    private static final Logger LOG = LogUtils.getLogger();
    /**
     * Spiel-Log (Info/Warnung) und Diagnose zusätzlich in die Server-Konsole: nur in der Entwicklungsumgebung
     * (GameTests, runClient) oder mit -Dbegleiter.log.console=true. Sonst landen sie auf DEBUG; das
     * In-Game-Log und der Dump-Knopf haben sie trotzdem. Fehler gehen immer in die Konsole.
     */
    private static final boolean CONSOLE_LOG = Boolean.getBoolean("begleiter.log.console")
            || net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();

    private static void console(char level, String prefix, Component msg) {
        if (level != 'E' && !CONSOLE_LOG && !LOG.isDebugEnabled()) return; // Text gar nicht erst auflösen
        String text = msg.getString();
        if (level == 'E') LOG.error("{} {}", prefix, text);
        else if (!CONSOLE_LOG) LOG.debug("{} {}", prefix, text);
        else if (level == 'W') LOG.warn("{} {}", prefix, text);
        else LOG.info("{} {}", prefix, text);
    }
    private final ArrayDeque<LogEntry> logBuf = new ArrayDeque<>();
    public String lastUiAction = "";

    /** Wiederholte Einträge behalten die neueste Zeit; Sprache und Farben entscheiden nie, ob es derselbe Eintrag ist. */
    private boolean addLog(char level, EventKind kind, Component msg) {
        LogEntry first = logBuf.peekFirst();
        if (first != null && first.level == level && first.kind == kind && first.text.equals(msg)) {
            first.count++;
            first.tick = tickCount;
            return false;
        }
        logBuf.addFirst(new LogEntry(tickCount, level, msg, kind));
        while (logBuf.size() > 150) logBuf.removeLast();
        return true;
    }

    public void logInfo(String msg) { logInfo(BotDebug.eventKind(msg), CompanionTexts.literal(msg)); }
    public void logWarn(String msg) { logWarn(BotDebug.eventKind(msg), CompanionTexts.literal(msg)); }
    public void logError(String msg) { logError(BotDebug.eventKind(msg), CompanionTexts.literal(msg)); }
    public void logInfo(Component msg) { logInfo(EventKind.NONE, msg); }
    public void logWarn(Component msg) { logWarn(EventKind.NONE, msg); }
    public void logError(Component msg) { logError(EventKind.NONE, msg); }
    public void logInfo(EventKind kind, Component msg) { if (addLog('I', kind, msg)) console('I', "[Begleiter]", msg); }
    public void logWarn(EventKind kind, Component msg) { if (addLog('W', kind, msg)) console('W', "[Begleiter]", msg); }
    public void logError(EventKind kind, Component msg) { if (addLog('E', kind, msg)) console('E', "[Begleiter]", msg); }
    public void consoleDebug(String msg) { console('I', "[Begleiter-Debug]", Component.literal(msg)); }
    public void clearLog() { logBuf.clear(); }
    public int logSize() { return logBuf.size(); }

    /** Problem melden: ins Log UND (nicht zu oft) in den Chat. */
    public void problem(String msg) { problem(BotDebug.eventKind(msg), CompanionTexts.literal(msg)); }
    public void problem(Component msg) { problem(EventKind.NONE, msg); }

    public void problem(EventKind kind, Component msg) {
        lastProblemText = msg.copy();
        problemText = msg.getString();
        lastProblemSource = problemText;
        problemTick = tickCount;
        logWarn(kind, msg);
        if (!msg.equals(lastTold) || tickCount - lastToldTick > 1200) {
            tell(CompanionTexts.tr("problem", msg));
            lastTold = msg.copy();
            lastToldTick = tickCount;
        }
    }

    public Component problemComponent() {
        return problemText.equals(lastProblemSource) ? lastProblemText.copy() : CompanionTexts.literal(problemText);
    }

    /** Wiederholt sich die Leerlauf-Meldung, den ursprünglichen Grund behalten statt Meldungen ineinander zu schachteln. */
    Component idleDiagnosticComponent() {
        Component previous = problemComponent();
        Component detail;
        if (previous.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tr
                && tr.getKey().equals("server.begleiter.entity.idle") && tr.getArgs().length == 2
                && tr.getArgs()[1] instanceof Component priorDetail) {
            detail = priorDetail.copy();
        } else {
            detail = problemText.isEmpty() ? Component.empty() : CompanionTexts.tr("entity.idle.previous", previous);
        }
        return CompanionTexts.tr("entity.idle", lastStage.isEmpty() ? CompanionTexts.tr("entity.idle.no_plan") : stageComponent(), detail);
    }

    /** Neueste zuerst. errorsOnly: nur Warnungen und Fehler. */
    public List<LogEntry> getLog(int offset, int count, boolean errorsOnly) {
        List<LogEntry> out = new ArrayList<>();
        int skipped = 0;
        for (LogEntry e : logBuf) {
            if (errorsOnly && e.level() == 'I') continue;
            if (skipped++ < offset) continue;
            out.add(e);
            if (out.size() >= count) break;
        }
        return out;
    }

    public static Component formatLogText(LogEntry e) {
        return CompanionTexts.logLine(e.level(), Math.max(0, e.tick() / 20), e.count(), e.textComponent());
    }

    /** Worauf sich ein Fehlschlag bezieht (Ressource, Item, Tierart) – Schlüssel der Erfahrung; null = nichts Benennbares. */
    private static String failureTarget(TaskSpec spec) {
        return switch (spec) {
            case TaskSpec.ResourceGoal g -> g.resource();
            case TaskSpec.Craft c -> c.item();
            case TaskSpec.Smelt s -> s.item();
            case TaskSpec.Station s -> s.item();
            case TaskSpec.Take tk -> tk.item();
            case TaskSpec.Give g -> g.what();
            case TaskSpec.Trade tr -> tr.item();
            case TaskSpec.Fight f -> f.mob();
            case TaskSpec.Breed b -> b.species();
            case TaskSpec.Fetch f -> f.animal();
            default -> null;
        };
    }

    public static String describe(Task t) {
        String pos = t.pos() == null ? "" : " " + t.pos().toShortString();
        // Suchen ist nicht Abbauen: "MINE diamant x5" las sich wie "baut gerade Diamanterz ab"
        if (t.spec() instanceof TaskSpec.ResourceGoal goal && goal.resource() != null)
            return t.type() + " looking for " + goal.resource() + (goal.count() > 0 ? ", " + goal.count() + " left" : "") + pos;
        return t.type() + details(t.spec()) + pos;
    }

    /** Kurzangaben je Aufgabe für Log und Anzeige, etwa " bucket x2". */
    private static String details(TaskSpec spec) {
        return switch (spec) {
            case TaskSpec.Give g -> amount(g.what(), g.max());
            case TaskSpec.Take tk -> amount(tk.item(), tk.count());
            case TaskSpec.Craft c -> amount(c.item(), c.count());
            case TaskSpec.Smelt s -> amount(s.item(), s.count());
            case TaskSpec.Station s -> amount(s.item(), 0) + (s.portable() ? " (portable)" : "");
            case TaskSpec.Repair r -> r.belowPercent() > 0 ? " below " + r.belowPercent() + " %" : "";
            case TaskSpec.GearUp g -> " tier " + g.tier() + amount(g.lastPiece(), g.attempts());
            case TaskSpec.Door d -> amount(d.facing(), 0);
            case TaskSpec.ResourceGoal g -> amount(g.resource(), g.count());
            case TaskSpec.Tree tr -> amount(null, tr.logs());
            case TaskSpec.Hunt hu -> amount(null, hu.food());
            case TaskSpec.Fetch f -> amount(f.animal(), 0);
            case TaskSpec.Fight f -> amount(f.loot() == null ? f.mob() : f.mob() + " -> " + f.loot(), f.lootCount());
            case TaskSpec.Breed b -> amount(b.species(), b.pairs());
            case TaskSpec.Trade tr -> amount(tr.item(), tr.count());
            case TaskSpec.Extend ex -> amount(null, ex.annex());
            default -> "";
        };
    }

    private static String amount(String what, int n) {
        return (what == null ? "" : " " + what) + (n > 0 ? " x" + n : "");
    }

    public BlockPos getHouseOrigin() { return inOverworld() ? houseOrigin : null; }

    // ------------------------------------------------------------------ Wächter gegen Stillstand

    private int watchSig;
    private Task watchTask;
    private double watchBest;
    private int watchSince;
    private int lastPlanSig;
    private int planRepeats;

    private int totalItems() {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) n += inventory.getItem(i).getCount();
        return n;
    }

    /** Hat sich bei vorhandenen Aufgaben 20 Sekunden lang nichts verändert, wird die Aufgabe gemeldet und übersprungen. */
    private void progressWatch() {
        // Schmelzen: steht lange am Ofen, ohne dass sich die Item-Zahl ändert (Erz -> Barren) – das ist kein Hänger
        if (tasks.isEmpty() || isPaused() || getTarget() != null || tasks.peekFirst().type() == TaskType.SMELT) {
            watchSince = tickCount;
            return;
        }
        if (tasks.peekFirst().spec() instanceof TaskSpec.Request r && (r.phase() == 1 || r.phase() == 2)
                && (getOwner() == null || distanceToSqr(getOwner()) <= 6.25)) {
            watchSince = tickCount;
            return;
        }
        // Aufgabe mit Ziel: nur näher kommen zählt (im Kreis laufen ist kein Fortschritt);
        // sonst nur waagerecht: auf der Stelle springen (z.B. auf einer Fackel) ist kein Fortschritt
        BlockPos p = blockPosition();
        Task cur = tasks.peekFirst();
        int move;
        if (cur.pos() != null && cur.type() != TaskType.BUILD) {
            double d = Math.sqrt(cur.pos().distSqr(p));
            if (cur != watchTask) { watchTask = cur; watchBest = d; }
            if (d < watchBest - 1.0) watchBest = d;
            move = (int) Math.round(watchBest * 4);
        } else {
            watchTask = null;
            move = java.util.Objects.hash(p.getX(), p.getZ());
        }
        int sig = java.util.Objects.hash(cur, move, totalItems(), tasks.size(),
                build == null ? -1 : build.index, scaffold.size());
        if (sig != watchSig) { watchSig = sig; watchSince = tickCount; return; }
        if (tickCount - watchSince > 400) {
            Task h = tasks.pollFirst();
            reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason.STALLED, h);
            if (hasRecipeRequest()) {
                problem(CompanionTexts.tr("request.interrupted", CompanionTexts.task(h)));
                cancelAll();
            } else if (goal == CompanionNeeds.Need.SUPPLY || goal == CompanionNeeds.Need.ORDER) {
                // nach einer gescheiterten Voraussetzung sind die abhängigen eigenen Aufgaben ungültig
                planFeedback.finish(this, false);
                cancelAll();
                plannerCooldownUntil = tickCount + 200;
            }
            problem(EventKind.DANGER, CompanionTexts.tr("log.problem.stalled_task", CompanionTexts.task(h), CompanionTexts.tr(getNavigation().isDone() ? "log.nav.done" : "log.nav.running")));
            watchSince = tickCount;
        }
    }

    /** Wird vom Planer bei jeder Entscheidung gerufen: erkennt Endlosschleifen ohne Veränderung. */
    public void noteStage(String msg) { noteStage(Component.literal(msg)); }

    public void noteStage(Component msg) {
        int sig = java.util.Objects.hash(msg, blockPosition(), totalItems());
        if (sig == lastPlanSig) planRepeats++; else { lastPlanSig = sig; planRepeats = 0; }
        if (planRepeats >= 5) {
            planRepeats = 0;
            problem(EventKind.DANGER, CompanionTexts.tr("log.problem.loop", msg));
            reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason.STALLED, currentTask());
            planFeedback.finish(this, false);
            tasks.clear();
            Task ex = explorePoint();
            if (ex != null) tasks.addLast(ex);
            plannerCooldownUntil = tickCount + 100;
        }
    }

    /** Bewusstes Warten (z.B. nachts im Haus) ist keine Endlosschleife. */
    public void noteWaiting() { planRepeats = 0; }

    public List<Component> debugComponents() {
        List<Component> l = new ArrayList<>();
        l.add(CompanionTexts.tr("diagnostic.position", blockPosition().toShortString(), CompanionTexts.mode(this),
                paused ? CompanionTexts.tr("status.paused") : Component.empty()));
        l.add(CompanionTexts.tr("diagnostic.health", (int) getHealth(), (int) getMaxHealth(), hunger, foodCount()));
        l.add(CompanionTexts.tr("diagnostic.planner", lastStage.isEmpty() ? Component.literal("-") : stageComponent(),
                Math.max(0, plannerCooldownUntil - tickCount)));
        int i = 0;
        for (Task t : tasks) { if (i++ >= 6) break; l.add(CompanionTexts.tr("diagnostic.task", i, CompanionTexts.task(t))); }
        l.add(CompanionTexts.tr("diagnostic.tasks", tasks.size(), getTarget() == null
                ? CompanionTexts.tr("diagnostic.no_target") : getTarget().getName()));
        if (planFeedback.last() != null) l.add(CompanionTexts.tr("diagnostic.feedback", CompanionTexts.literal(planFeedback.last().toString())));
        l.add(CompanionTexts.tr("diagnostic.navigation", CompanionTexts.tr(getNavigation().isDone() ? "log.nav.done" : "log.nav.running"),
                CompanionTexts.tr(getNavigation().getPath() == null ? "diagnostic.no_path" : "diagnostic.has_path")));
        l.add(nav.debugComponent());
        if (nav.trace.enabled()) l.add(CompanionTexts.tr("diagnostic.motion", CompanionTexts.literal(nav.trace.metrics().toString())));
        l.add(CompanionTexts.tr("diagnostic.hazard", CompanionTexts.hazards(this), problemText.isEmpty() ? Component.literal("-") : problemComponent()));
        return l;
    }

    public void rememberStation(String id, BlockPos pos) {
        String canonical = ItemIds.canonical(id);
        if ("minecraft:crafting_table".equals(canonical)) tablePos = pos.immutable();
        else if ("minecraft:furnace".equals(canonical)) furnacePos = pos.immutable();
    }

    private static boolean isBlockId(BlockState s, String id) {
        return isBlockId(s, ItemIds.parse(id));
    }

    private static boolean isBlockId(BlockState s, net.minecraft.resources.Identifier id) {
        return id != null && BuiltInRegistries.BLOCK.getKey(s.getBlock()).equals(id);
    }

    /** Seit wann (Tick) er Diamanten sucht, ohne einen zu bekommen; -1 = sucht gerade keine. */
    private int diamondSearchSince = -1;
    private int diamondsHad;

    /** Diamanten-Suche verfolgen: Ziel in der Warteschlange? Neuer Diamant -> Zähler von vorn. */
    private void diamondSearchTick() {
        boolean seeking = false;
        for (Task t : tasks)
            if (t.spec() instanceof TaskSpec.ResourceGoal goal && Resources.DIAMOND.key().equals(goal.resource())) { seeking = true; break; }
        int have = CompanionCraft.count(this, "diamond");
        // erst ab Diamanten-Tiefe zählen (der Abstieg vom Berg ist keine erfolglose Suche)
        boolean deep = getBlockY() <= 16 && level().dimension() == Level.OVERWORLD;
        if (!seeking || (!deep && diamondSearchSince < 0)) { diamondSearchSince = -1; diamondsHad = have; return; }
        if (diamondSearchSince < 0 || have > diamondsHad) {
            if (diamondSearchSince >= 0 && diamondSenseRadius() > CompanionMemory.XRAY_RADIUS) logInfo(EventKind.LOOT, CompanionTexts.tr("log.sense.found"));
            diamondSearchSince = tickCount;
        }
        int before = diamondSenseRadius(tickCount - 20);
        diamondsHad = have;
        int now = diamondSenseRadius();
        if (now > before) logInfo(EventKind.LOOT, CompanionTexts.tr("log.sense.expanded", now));
    }

    /** Reichweite des Erz-Gespürs für Diamanten: wächst, je länger er erfolglos sucht (2/4/6 min -> 48/64/96). */
    public int diamondSenseRadius() { return diamondSenseRadius(tickCount); }

    private int diamondSenseRadius(int at) {
        if (diamondSearchSince < 0) return CompanionMemory.XRAY_RADIUS;
        int min = (at - diamondSearchSince) / (20 * 60);
        return min >= 6 ? 96 : min >= 4 ? 64 : min >= 2 ? 48 : CompanionMemory.XRAY_RADIUS;
    }

    /** Stationen, zu denen er nicht hinkam (Position -> bis Tick): solange nimmt er lieber eine eigene. */
    private final Map<BlockPos, Integer> unreachableStations = new HashMap<>();

    public void markStationUnreachable(BlockPos p) {
        unreachableStations.put(p.immutable(), tickCount + 6000);
        if (p.equals(tablePos)) tablePos = null;
        if (p.equals(furnacePos)) furnacePos = null;
    }

    private boolean stationUnreachable(BlockPos p) {
        Integer until = unreachableStations.get(p);
        if (until == null) return false;
        if (tickCount < until) return true;
        unreachableStations.remove(p);
        return false;
    }

    /** Sucht eine Werkbank/einen Ofen/eine Kiste in der Nähe (echte Blöcke in der Welt). */
    public BlockPos findStation(String id, int r) {
        if (!(level() instanceof ServerLevel sl)) return null;
        var stationId = ItemIds.parse(id);
        if (stationId == null) return null;
        id = stationId.toString();
        BlockPos cached = id.equals("minecraft:crafting_table") ? tablePos : id.equals("minecraft:furnace") ? furnacePos : null;
        if (cached != null && sl.hasChunkAt(cached) && isBlockId(sl.getBlockState(cached), stationId) && !stationUnreachable(cached)
                && cached.distSqr(blockPosition()) <= (double) r * r * 4) return cached;
        BlockPos c = blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -5, -r), c.offset(r, 5, r))) {
            if (!sl.hasChunkAt(p)) continue;
            if (!isBlockId(sl.getBlockState(p), stationId) || stationUnreachable(p)) continue;
            double d = p.distSqr(c);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        if (best != null) rememberStation(id, best);
        return best;
    }

    /** Freier Platz für Werkbank/Ofen/Kiste: im Haus (hintere Reihe), sonst neben dem Begleiter. here = true: tragbare Station direkt neben sich. */
    public BlockPos stationSpot(boolean here) {
        if (!(level() instanceof ServerLevel sl)) return null;
        if (houseOrigin != null && !here) {
            // (3,1,2) bleibt frei: Weg zum Anbau
            int[][] spots = {{1, 1, 3}, {2, 1, 3}, {3, 1, 3}, {1, 1, 2}, {1, 1, 1}, {3, 1, 1}};
            for (int[] d : spots) {
                BlockPos p = houseOrigin.offset(d[0], d[1], d[2]);
                if (sl.getBlockState(p).isAir() && !sl.getBlockState(p.below()).getCollisionShape(sl, p.below()).isEmpty())
                    return p;
            }
        }
        BlockPos c = blockPosition();
        // Nie in die Haustür-Öffnung: sonst steht die Werkbank für die Tür genau dort, wo die Tür hin soll
        List<BlockPos> doorway = houseDoor();
        for (int r = 2; r <= 4; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = c.offset(dx, 0, dz);
                    if (doorway.contains(p)) continue;
                    if (sl.getBlockState(p).isAir() && sl.getBlockState(p.above()).isAir()
                            && !sl.getBlockState(p.below()).getCollisionShape(sl, p.below()).isEmpty()) return p;
                }
        // Eng (auf einer Säule, im Tunnel): direkt neben sich in die Luft (seitlich an den eigenen Block) ...
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = c.relative(d);
            if (sl.getBlockState(p).isAir() && !getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))) return p;
        }
        // ... oder in die Wand (der Block wird abgebaut)
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = c.relative(d);
            if (mayDigForStation(sl, p, sl.getBlockState(p))) return p;
        }
        return null;
    }

    /** Darf für eine Station aus der Wand weichen? Nur gewachsener Fels/Boden, nie Gebautes (Bretter, Glas, Wolle). */
    static boolean mayDigForStation(ServerLevel sl, BlockPos p, BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && s.getDestroySpeed(sl, p) >= 0 && s.getDestroySpeed(sl, p) < 5
                && !(s.getBlock() instanceof net.minecraft.world.level.block.EntityBlock) && !isPrecious(s.getBlock().asItem())
                && naturalGround(s);
    }

    public String modeText() {
        return keeper ? "storage keeper" : staying ? "waiting" : autonomous ? "playing freely"
                : duty != null ? "duty " + duty.name().toLowerCase(java.util.Locale.ROOT) : "following the owner";
    }

    public String currentTaskText() {
        Task t = tasks.peekFirst();
        return t == null ? (paused ? "paused" : "nothing") : describe(t);
    }

    public int taskCount() { return tasks.size(); }

    public int usedSlots() {
        int used = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) if (!inventory.getItem(i).isEmpty()) used++;
        return used;
    }

    public void openPanel(ServerPlayer p, CompanionMenu.Page page) { openPanel(p, page, false); }
    void openPanel(ServerPlayer p, CompanionMenu.Page page, boolean remote) {
        if (!(remote ? CompanionAccess.canControl(p, this) : CompanionAccess.canManage(p, this))) return;
        p.openMenu(new net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider<CompanionSnapshot>() {
            @Override public Component getDisplayName() { return CompanionTexts.tr("panel.title"); }
            @Override public CompanionSnapshot getScreenOpeningData(ServerPlayer player) {
                return CompanionSnapshot.capture(CompanionEntity.this, -1, page);
            }
            @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                    int id, net.minecraft.world.entity.player.Inventory inv, Player player) {
                return (remote ? CompanionAccess.canControl(player, CompanionEntity.this) : CompanionAccess.canManage(player, CompanionEntity.this))
                        ? new CompanionMenu(id, inv, CompanionEntity.this, page, remote) : null;
            }
        });
        if (p.containerMenu instanceof CompanionMenu) CompanionRemoteMenu.remember(p, this);
    }

    public Task currentTask() { return tasks.peekFirst(); }
    /** Die nächsten n Aufgaben (Kopie; für das Debug-Overlay). */
    public List<Task> peekTasks(int n) { return tasks.stream().limit(n).toList(); }
    public void finishTask() { tasks.pollFirst(); }
    public void pushFront(Task t) { tasks.addFirst(t); }
    public void addTask(Task t) { tasks.addLast(t); }
    public boolean hasTasks() { return !tasks.isEmpty(); }

    /** Läuft ein Rezept-Auftrag? Seine gespeicherten Zwischenstände stehen hinter allen Vorbereitungsaufgaben. */
    public boolean hasRecipeRequest() { return tasks.stream().anyMatch(t -> t.spec() instanceof TaskSpec.Request); }

    /** Zwischenstände behalten; nur der fehlende Vorrat wird aus dem tatsächlichen Inventar neu geplant. */
    public void recoverRecipeSource(String item, int target, BlockPos excludedSource) {
        int attempts = 0;
        String canonical = ItemIds.canonicalOrOriginal(item);
        for (Task queued : tasks) {
            if (queued.spec() instanceof TaskSpec.Request r && r.phase() == 4 && r.item().equals(canonical)) {
                attempts = Math.max(attempts, queued.tries());
                target = Math.max(target, r.count());
            }
        }
        replaceCurrent(List.of(new Task(new TaskSpec.Request(canonical, target, false, 4, 0, excludedSource), attempts)));
        getNavigation().stop();
        nav.stop();
        Component status = CompanionTexts.tr("request.replanning", Math.max(0, target - CompanionCraft.count(this, canonical)),
                CompanionTexts.item(canonical));
        setUiFeedback(status);
        logInfo(status);
        tell(status);
    }

    /** Ein Fehlschlag ist kein Abbruch: Warteschlange samt Herstell- und Lieferschritten bleibt erhalten. */
    public void pauseRecipeRequest(Component reason) {
        getNavigation().stop();
        nav.stop();
        setUiFeedback(reason);
        logWarn(reason);
        pauseWork(reason);
    }

    /** Für einen offenen Rezept-Auftrag vorgesehene Items nicht von selbst anlegen, wegwerfen oder verbrauchen. */
    public boolean reservedForRecipe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (Task task : tasks) {
            if (task.spec() instanceof TaskSpec.Request request) {
                if ((request.phase() == 1 || request.phase() == 2 || request.phase() == 4 || !request.materials() && (request.phase() == 0 || request.phase() == 3))
                        && ItemIds.matches(stack, request.item())) return true;
                if (request.phase() == 0 && level() instanceof ServerLevel sl)
                    for (var recipe : CompanionRecipes.processingFor(sl, ItemIds.resolve(request.item())))
                        if (recipe.ingredients().stream().anyMatch(ingredient -> ingredient.test(stack))) return true;
            } else if (task.spec() instanceof TaskSpec.Process process && level() instanceof ServerLevel sl) {
                var recipe = CompanionRecipes.processingRecipe(sl, process.recipe());
                if (recipe != null && recipe.ingredients().stream().anyMatch(ingredient -> ingredient.test(stack))) return true;
            }
        }
        return false;
    }

    /** Ersetzt die aktuelle Aufgabe durch eine Liste (in Reihenfolge). */
    public void replaceCurrent(List<Task> list) {
        tasks.pollFirst();
        for (int i = list.size() - 1; i >= 0; i--) tasks.addFirst(list.get(i));
    }

    public boolean hasPlaceTaskFor(Item item) {
        for (Task t : tasks) if (t.type() == TaskType.PLACE && t.block() != null && t.block().asItem() == item) return true;
        return false;
    }

    private static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) if (open(level, p.relative(d))) return true;
        return false;
    }

    /** Luft oder Wasser: dort kann er hinsehen/hinschwimmen. */
    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || (s.getBlock() == Blocks.WATER);
    }

    /** Sichtlinie von den Augen zu einer freien Seite des Blocks (kein Röntgenblick). */
    private boolean canSee(ServerLevel sl, BlockPos target) {
        Vec3 from = getEyePosition();
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            if (!open(sl, n)) continue;
            Vec3 delta = Vec3.atCenterOf(n).subtract(from);
            int steps = Math.max(1, (int) Math.ceil(delta.length() * 2));
            boolean clear = true;
            for (int i = 1; i < steps; i++) {
                BlockPos bp = BlockPos.containing(from.add(delta.scale(i / (double) steps)));
                if (bp.equals(n)) break;
                BlockState bs = sl.getBlockState(bp);
                if (!bs.getCollisionShape(sl, bp).isEmpty()) { clear = false; break; }
            }
            if (clear) return true;
        }
        return false;
    }

    public boolean hasCorrectToolFor(BlockState s) {
        if (getMainHandItem().isCorrectToolForDrops(s)) return true;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack st = inventory.getItem(i);
            if (!st.isEmpty() && st.isCorrectToolForDrops(s)) return true;
        }
        return false;
    }

    /** Baut eine Liste von BREAK-Aufgaben für Blöcke, die er wirklich sehen kann. */
    public List<Task> buildGather(Predicate<BlockState> pred, int count) {
        return buildGather(pred, count, false);
    }

    private boolean reachable(BlockPos target) {
        var path = getNavigation().createPath(target, 1);
        if (path == null) return false;
        if (path.canReach()) return true;
        var end = path.getEndNode();
        return end != null && target.distSqr(new BlockPos(end.x, end.y, end.z)) <= 20.0;
    }

    /** Standplatz unter einem Block: erster fester Boden darunter (Stämme und Laub zählen nicht), höchstens 12 tief. */
    private static BlockPos standBelow(ServerLevel sl, BlockPos pos) {
        BlockPos.MutableBlockPos m = pos.mutable();
        for (int i = 0; i < 12; i++) {
            m.move(Direction.DOWN);
            BlockState st = sl.getBlockState(m);
            if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.getCollisionShape(sl, m).isEmpty()) continue;
            return m.above().immutable();
        }
        return pos;
    }

    /** surface: Holz/Sand/Erde - hier ist Wasser in der Nähe okay, Lava nie. */
    public List<Task> buildGather(Predicate<BlockState> pred, int count, boolean surface) {
        return buildGather(pred, count, surface, false);
    }

    /** Über dem Block mindestens zwei Wasser: er müsste tauchen. */
    public static boolean deepWater(ServerLevel sl, BlockPos p) {
        return sl.getBlockState(p.above()).is(Blocks.WATER) && sl.getBlockState(p.above(2)).is(Blocks.WATER);
    }

    /** allowDive: auch Blöcke tief unter Wasser (Notfall – langsam, Ertrinkungsgefahr). */
    public List<Task> buildGather(Predicate<BlockState> pred, int count, boolean surface, boolean allowDive) {
        List<Task> out = new ArrayList<>();
        if (!(level() instanceof ServerLevel sl)) return out;
        BlockPos c = blockPosition();
        int rh = 24;
        List<BlockPos> found = new ArrayList<>();
        // An der Oberfläche auch höher suchen (Stammreste hoch im Baum, Hang), so weit wie das Gedächtnis schaut
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-rh, -8, -rh), c.offset(rh, surface ? 10 : 5, rh))) {
            if (!sl.hasChunkAt(p)) continue;
            if (pred.test(sl.getBlockState(p)) && exposed(sl, p)) found.add(p.immutable());
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        int checked = 0, pathChecks = 0;
        for (BlockPos pos : found) {
            if (out.size() >= count || ++checked > 300 || pathChecks > 40) break;
            // Stämme nur von gewachsenen Bäumen: Blockhütten und Pfosten aus Stämmen baut er nicht ab
            BlockState candidate = sl.getBlockState(pos);
            if (candidate.is(BlockTags.LOGS) && !CompanionTerraform.natural(sl, pos, candidate)) continue;
            if (!canSee(sl, pos)) continue;
            // Wasser daneben flutet sonst den Stollen – am Grund eines Gewässers ist es egal. Tief unter Wasser
            // (Kopf unter Wasser) dauert Abbauen lange und er kann ertrinken: nur im Notfall (allowDive).
            boolean seabed = sl.getBlockState(pos.above()).is(Blocks.WATER);
            if (seabed && deepWater(sl, pos) && !allowDive) continue;
            if (hazardAround(sl, pos, surface || seabed)) continue;
            pathChecks++;
            // Stamm hoch oben (Rest eines halb gefällten Baums): reicht, wenn er darunter stehen kann – TREE baut hoch
            if (!reachable(pos)) {
                if (!sl.getBlockState(pos).is(BlockTags.LOGS)) continue;
                pathChecks++;
                if (!reachable(standBelow(sl, pos))) continue;
            }
            out.add(Task.breakAt(pos, sl.getBlockState(pos).getBlock()));
        }
        if (!out.isEmpty()) resetExplore();
        return out;
    }

    /** Treppe schräg nach unten graben (so findet er Erze, ohne durch Wände zu sehen). */
    public List<Task> staircase(int steps) {
        return staircase(steps, getDirection());
    }

    /** Wie {@link #staircase(int)}, in fester Richtung; endet mit MOVE_TO auf die unterste Stufe. */
    public List<Task> staircase(int steps, Direction dir) {
        List<Task> out = new ArrayList<>();
        if (!(level() instanceof ServerLevel sl)) return out;
        BlockPos p = blockPosition();
        BlockPos last = null;
        for (int i = 1; i <= steps; i++) {
            p = p.relative(dir).below();
            BlockPos below = p.below();
            BlockState bs = sl.getBlockState(below);
            if (!bs.getFluidState().isEmpty() || bs.isAir()) break;
            boolean stop = false;
            List<BlockPos> cells = new ArrayList<>();
            for (BlockPos q : new BlockPos[]{p.above(2), p.above(), p}) {
                BlockState st = sl.getBlockState(q);
                // nur Natürliches: Kisten, Bretter, Glas, Blockhütten-Stämme ... (Haus, Lager) werden nie durchgegraben
                if (!st.getFluidState().isEmpty() || st.getDestroySpeed(sl, q) < 0 || hazardAround(sl, q, false)
                        || (!st.isAir() && !st.canBeReplaced() && !naturalAt(sl, q, st))) { stop = true; break; }
                if (!st.isAir()) cells.add(q);
            }
            if (stop) break;
            for (BlockPos q : cells) out.add(Task.breakAt(q, null));
            last = p.immutable();
        }
        if (!out.isEmpty()) out.add(Task.moveTo(last));
        return out;
    }

    /** Stollen (2 hoch) geradeaus graben; endet mit MOVE_TO ans Ende. Stoppt vor Wasser, Lava und Löchern. */
    public List<Task> tunnel(Direction dir, int len) {
        List<Task> out = new ArrayList<>();
        if (!(level() instanceof ServerLevel sl)) return out;
        BlockPos p = blockPosition();
        BlockPos last = null;
        for (int i = 1; i <= len; i++) {
            p = p.relative(dir);
            BlockState floor = sl.getBlockState(p.below());
            if (!floor.getFluidState().isEmpty() || floor.getCollisionShape(sl, p.below()).isEmpty()) break;
            boolean stop = false;
            List<BlockPos> cells = new ArrayList<>();
            for (BlockPos q : new BlockPos[]{p.above(), p}) {
                BlockState st = sl.getBlockState(q);
                // nur Natürliches: Kisten, Bretter, Glas, Blockhütten-Stämme ... (Haus, Lager) werden nie durchgegraben
                if (!st.getFluidState().isEmpty() || st.getDestroySpeed(sl, q) < 0 || hazardAround(sl, q, false)
                        || (!st.isAir() && !st.canBeReplaced() && !naturalAt(sl, q, st))) { stop = true; break; }
                if (!st.isAir()) cells.add(q);
            }
            if (stop) break;
            for (BlockPos q : cells) out.add(Task.breakAt(q, null));
            last = p.immutable();
        }
        if (last != null) out.add(Task.moveTo(last));
        return out;
    }

    /** Natürlich gewachsen (wie die Wegfindung); Stämme nur an einem gewachsenen Baum, nie Blockhütten. */
    static boolean naturalAt(ServerLevel sl, BlockPos p, BlockState st) {
        return BlockFlags.has(BlockFlags.of(st), BlockFlags.NATURAL)
                && (!st.is(BlockTags.LOGS) || CompanionTerraform.natural(sl, p, st));
    }

    /** Höhe, auf der ein Spieler nach diesem Erz gräbt. */
    public static int oreLevel(String key) {
        return switch (key) {
            case "kohle", "kupfer" -> 48;
            case "eisen", "smaragd" -> 16;
            case "lapis" -> 0;
            case "gold" -> -16;
            case "diamant", "redstone" -> -56;
            case "obsidian" -> -50; // Lavaseen tief unten
            default -> 40;
        };
    }

    // ------------------------------------------------------------------ Bäume komplett fällen

    /** Alle zusammenhängenden Stämme eines Baums (inkl. Äste). Ohne Blätter in der Nähe: nur der eine Block. */
    public List<BlockPos> treeLogs(ServerLevel sl, BlockPos seed) {
        BlockPos start = null;
        if (sl.getBlockState(seed).is(BlockTags.LOGS)) start = seed;
        else {
            double bd = Double.MAX_VALUE;
            // Nach oben so weit wie der Baum selbst (+45): ist der Fuß schon gefällt, hängen die obersten Stämme
            // bei hohen Bäumen weit über ihm – sonst gilt der Baum nach 7 Stämmen als fertig
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -3; dy <= 45; dy++) {
                BlockPos q = seed.offset(dx, dy, dz);
                if (sl.getBlockState(q).is(BlockTags.LOGS) && q.distSqr(seed) < bd) { bd = q.distSqr(seed); start = q; }
            }
        }
        if (start == null) return List.of();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> out = new ArrayList<>();
        queue.add(start);
        seen.add(start);
        int leaves = 0;
        while (!queue.isEmpty() && out.size() < 200) {
            BlockPos p = queue.poll();
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos n = p.offset(dx, dy, dz);
                if (Math.abs(n.getX() - start.getX()) > 12 || Math.abs(n.getZ() - start.getZ()) > 12
                        || n.getY() < start.getY() - 1 || n.getY() > start.getY() + 45) continue;
                if (!seen.add(n.immutable())) continue;
                BlockState st = sl.getBlockState(n);
                if (st.is(BlockTags.LOGS)) queue.add(n.immutable());
                else if (st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                        && !st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) leaves++; // gesetztes Laub zählt nicht
            }
        }
        if (leaves < 3) return List.of(start); // kein Baum (z.B. Holzhütte): nur diesen Block
        return out;
    }

    /** Statt einzelner Stämme: ganze Bäume als TREE-Aufgaben. */
    public List<Task> buildTrees(int count) {
        List<Task> out = new ArrayList<>();
        if (!(level() instanceof ServerLevel sl)) return out;
        List<Task> singles = buildGather(st -> st.is(BlockTags.LOGS), 40, true);
        Set<BlockPos> claimed = new HashSet<>();
        int total = 0;
        for (Task bt : singles) {
            if (total >= count) break;
            if (claimed.contains(bt.pos())) continue;
            List<BlockPos> cluster = treeLogs(sl, bt.pos());
            claimed.addAll(cluster);
            claimed.add(bt.pos());
            if (cluster.size() <= 1) { out.add(bt); total += 1; continue; }
            BlockPos base = cluster.get(0);
            for (BlockPos q : cluster) if (q.getY() < base.getY()) base = q;
            out.add(Task.tree(base, cluster.size()));
            total += cluster.size();
        }
        return out;
    }

    // ------------------------------------------------------------------ Gerüst (Hochbauen und wieder abbauen)

    /** Wertlose Blöcke, die er zum Hochbauen, Brückenbauen und Zumauern verbraucht ("Müll"). Nichts Wertvolles. */
    public static final Set<String> JUNK = RegistryIds.minecraftSet("dirt", "coarse_dirt", "rooted_dirt", "cobblestone", "cobbled_deepslate",
            "netherrack", "stone", "andesite", "diorite", "granite", "tuff", "calcite", "deepslate", "blackstone", "basalt",
            "smooth_basalt", "end_stone", "dripstone_block", "mud", "soul_soil");

    public static boolean isJunk(Item item) {
        return JUNK.contains(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    /** Der Müll-Block, von dem er am meisten dabei hat (so geht keine Sorte zuerst aus). */
    public Item scaffoldItem() {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && !reservedForRecipe(s) && isJunk(s.getItem())) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /**
     * Gerüst, das er später selbst wieder abbaut (TREE): nur Müll, den er mit seinem Werkzeug auch abbauen kann –
     * sonst sitzt er ohne Spitzhacke oben auf einer Bruchstein-Säule fest. Sonst wie {@link #scaffoldItem()}.
     */
    public Item scaffoldItem(Pillar kind) {
        if (kind != Pillar.TREE) return scaffoldItem();
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || reservedForRecipe(s) || !isJunk(s.getItem())) continue;
            BlockState st = Block.byItem(s.getItem()).defaultBlockState();
            if (st.requiresCorrectToolForDrops() && !hasCorrectToolFor(st)) continue;
            counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Wie viele Müll-Blöcke insgesamt (alle Sorten zusammen). */
    public int scaffoldCount() {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && isJunk(s.getItem())) n += s.getCount();
        }
        return n;
    }

    public boolean hasScaffold() { return !scaffold.isEmpty(); }
    public boolean isPillarActive() { return pillarActive; }
    public void pillarReset() { pillarActive = false; }

    public void pruneScaffold(ServerLevel sl) {
        scaffold.removeIf(p -> !sl.hasChunkAt(p) || sl.getBlockState(p).isAir());
    }

    // Auf stehende Mobs wirkt jeden Tick Schwerkraft (deltaMovement.y ~ -0.08), daher onGround statt Geschwindigkeit prüfen
    private boolean grounded() {
        return onGround() && getY() - Math.floor(getY()) < 0.02;
    }

    /** Fackeln, Blumen, Gras u.ä. (ohne Kollision, sofort abbaubar) wegnehmen – sie stehen beim Hochbauen im Weg. */
    private void clearSoft(ServerLevel sl, BlockPos p) {
        BlockState st = sl.getBlockState(p);
        if (st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty()) return;
        if (!st.getCollisionShape(sl, p).isEmpty() || st.getDestroySpeed(sl, p) != 0) return;
        sl.destroyBlock(p, true, this);
    }

    /**
     * Wofür er hochbaut. Nur TREE wird als Gerüst gemerkt und später wieder abgebaut.
     * TREE = hässliche Säule, um an einen Baum oder ein hohes Ziel zu kommen;
     * PATH = normales Hochbauen (Weg, aus Schacht/Loch raus) – bleibt stehen.
     * Neue Aufrufer im Zweifel PATH: lieber ein Block zu viel stehen lassen als später ziellos zurücklaufen.
     */
    public enum Pillar { PATH, TREE }

    /**
     * Ein Hochbau-Schritt: springen, Block unter sich setzen. 1 = Block gesetzt, 0 = läuft, 2 = Hindernis (pillarObstacle), -1 = nicht möglich.
     * kind zählt beim Absprung; ein laufender Sprung behält seine Art, auch wenn ihn ein anderer Aufrufer zu Ende führt.
     */
    public int pillarStep(ServerLevel sl, Pillar kind) {
        Item item = scaffoldItem(pillarActive ? pillarKind : kind);
        if (item == null) return -1;
        // Sehr viele Gerüstblöcke: die ältesten nicht mehr zum Abbauen vormerken (bleiben stehen), aber weiterbauen
        while (scaffold.size() >= 96) scaffold.remove(0);
        BlockPos feet = blockPosition();
        if (!pillarActive) {
            if (!grounded()) return 0;
            for (int up = 2; up <= 3; up++) {
                BlockPos o = feet.above(up);
                clearSoft(sl, o);
                BlockState st = sl.getBlockState(o);
                if (!st.isAir() && !st.canBeReplaced()) {
                    if (st.getDestroySpeed(sl, o) < 0 || hazardAround(sl, o, true)) return -1;
                    pillarObstacle = o.immutable();
                    return 2;
                }
            }
            // Erst genau auf die Blockmitte, sonst rutscht er beim Springen vom eigenen Pfeiler
            double cx = feet.getX() + 0.5 - getX(), cz = feet.getZ() + 0.5 - getZ();
            getNavigation().stop();
            if (cx * cx + cz * cz > 0.04) {
                getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.6);
                return 0;
            }
            pillarStartY = feet.getY();
            pillarKind = kind;
            pillarActive = true;
            pillarTicks = 0;
            pillarX = feet.getX();
            pillarZ = feet.getZ();
            getJumpControl().jump();
            return 0;
        }
        pillarTicks++;
        // In der Luft mittig über dem Pfeiler bleiben
        setDeltaMovement((pillarX + 0.5 - getX()) * 0.5, getDeltaMovement().y, (pillarZ + 0.5 - getZ()) * 0.5);
        getMoveControl().setWantedPosition(pillarX + 0.5, getY(), pillarZ + 0.5, 0.0);
        if (getY() >= pillarStartY + 1.0) {
            BlockPos under = new BlockPos(pillarX, pillarStartY, pillarZ);
            clearSoft(sl, under); // eigene Fackel im Schacht o.ä.: erst wegnehmen
            BlockState cur = sl.getBlockState(under);
            pillarActive = false;
            if (cur.isAir() || cur.canBeReplaced()) {
                int slot = findItem(item);
                if (slot < 0) return -1;
                BlockState place = Block.byItem(item).defaultBlockState();
                if (!placeConfirmed(sl, under, place)) return 0; // abgelehnt: nichts abbuchen, neuer Sprung
                sl.playSound(null, under, place.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
                inventory.getItem(slot).shrink(1);
                if (pillarKind == Pillar.TREE) scaffold.add(under.immutable());
                return 1;
            }
            return 0;
        }
        if (pillarTicks > 30) pillarActive = false; // Sprung hat nicht geklappt -> nochmal
        return 0;
    }

    /** Befehls-Variante: hängt BREAK-Aufgaben + Abliefern an. */
    public int queueGather(Block block, int count) {
        List<Task> list = buildGather(s -> s.is(block), count);
        for (Task t : list) tasks.addLast(t);
        if (!list.isEmpty()) tasks.addLast(Task.deliver(false));
        paused = false;
        return list.size();
    }

    public void resetExplore() { exploreAngle = Double.NaN; }

    /** Mit Decke (Nether): die Heightmap zeigt aufs Dach. Stattdessen nahe der eigenen Höhe einen Boden mit 2 Luft darüber suchen. */
    private int floorBelowCeiling(ServerLevel sl, int x, int z) {
        int base = getBlockY();
        for (int dy = 0; dy <= 12; dy++) {
            for (int y : new int[] { base + dy, base - dy }) {
                BlockPos p = new BlockPos(x, y, z);
                if (sl.getBlockState(p).isAir() && sl.getBlockState(p.above()).isAir()
                        && !sl.getBlockState(p.below()).getCollisionShape(sl, p.below()).isEmpty()) return y;
                if (dy == 0) break;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Die Heightmap endet auch auf Dschungelstämmen und Bambus: den begehbaren Boden unter dem Blätterdach suchen. */
    int explorationSurfaceY(ServerLevel sl, int x, int z) {
        if (sl.dimensionType().hasCeiling()) return floorBelowCeiling(sl, x, z);
        int top = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int bottom = Math.max(sl.getMinY() + 1, top - 48);
        for (int y = top; y >= bottom; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            BlockState ground = sl.getBlockState(feet.below());
            if (ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS)
                    || ground.is(Blocks.RED_MUSHROOM_BLOCK) || ground.is(Blocks.BROWN_MUSHROOM_BLOCK)
                    || ground.is(Blocks.MUSHROOM_STEM)) continue;
            int support = BlockFlags.of(ground);
            if (!BlockFlags.has(support, BlockFlags.STAND)) continue;
            int low = BlockFlags.of(sl.getBlockState(feet));
            int high = BlockFlags.of(sl.getBlockState(feet.above()));
            if (BlockFlags.has(low, BlockFlags.PASS) && BlockFlags.has(high, BlockFlags.PASS)) return y;
            // nicht durch Gelände/Wasser hindurch nach einer fremden Höhle darunter suchen
            return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** Erkundungsziel: behält die Richtung bei (wie ein Spieler, der in eine Richtung läuft), meidet Wasser und Lava. */
    public Task explorePoint() {
        if (!(level() instanceof ServerLevel sl)) return null;
        Player o = getOwner();
        if (Double.isNaN(exploreAngle)) exploreAngle = getRandom().nextDouble() * Math.PI * 2;
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = exploreAngle + (getRandom().nextDouble() - 0.5) * 0.6;
            double d = 14 + getRandom().nextInt(12);
            int x = (int) (getX() + Math.cos(ang) * d), z = (int) (getZ() + Math.sin(ang) * d);
            boolean ok = !(o != null && o.distanceToSqr(x, o.getY(), z) > 80.0 * 80.0);
            if (ok && !sl.hasChunkAt(new BlockPos(x, 64, z))) ok = false;
            if (ok) {
                int y = explorationSurfaceY(sl, x, z);
                if (y == Integer.MIN_VALUE) { exploreAngle += 0.9; continue; }
                BlockPos ground = new BlockPos(x, y - 1, z);
                BlockState gs = sl.getBlockState(ground);
                if (!gs.getFluidState().isEmpty() || gs.is(BlockTags.LEAVES) || gs.getCollisionShape(sl, ground).isEmpty()
                        || hazardAround(sl, ground.above(), false)) ok = false;
                if (ok) {
                    exploreAngle = ang;
                    return Task.moveTo(new BlockPos(x, y, z));
                }
            }
            exploreAngle += 0.9; // anderes Gelände probieren (Wasser, Hang ...)
            if (attempt == 6 && o != null) exploreAngle = Math.atan2(o.getZ() - getZ(), o.getX() - getX());
        }
        return null;
    }

    public void queueTidy() {
        tasks.addLast(Task.collect(true));
        tasks.addLast(Task.deliver(false));
        paused = false;
    }

    public int queuePlatform(ServerPlayer p, Block b, int w, int d) {
        Direction f = p.getDirection(), r = f.getClockWise();
        BlockPos origin = p.blockPosition().below().relative(f, 2);
        for (int j = 0; j < d; j++)
            for (int i = 0; i < w; i++)
                tasks.addLast(Task.place(origin.relative(f, j).relative(r, i - w / 2), b));
        paused = false;
        return w * d;
    }

    public int queueWall(ServerPlayer p, Block b, int w, int h) {
        Direction f = p.getDirection(), r = f.getClockWise();
        BlockPos origin = p.blockPosition().relative(f, 2);
        for (int y = 0; y < h; y++)
            for (int i = 0; i < w; i++)
                tasks.addLast(Task.place(origin.above(y).relative(r, i - w / 2), b));
        paused = false;
        return w * h;
    }

    public int queueClear(ServerPlayer p, int w, int h, int d) {
        if (!(level() instanceof ServerLevel sl)) return 0;
        Direction f = p.getDirection(), r = f.getClockWise();
        BlockPos origin = p.blockPosition().relative(f, 1);
        int n = 0;
        for (int y = h - 1; y >= 0; y--)
            for (int j = 0; j < d; j++)
                for (int i = 0; i < w; i++) {
                    BlockPos pos = origin.above(y).relative(f, j).relative(r, i - w / 2);
                    BlockState found = sl.getBlockState(pos);
                    if (!found.isAir()) {
                        // Nur den Block abbauen, der beim Befehl dort stand: Was der Spieler danach hinstellt
                        // (z.B. eine Kiste), bleibt stehen.
                        tasks.addLast(Task.breakAt(pos, found.getBlock()));
                        n++;
                    }
                }
        if (n > 0) tasks.addLast(Task.deliver(false));
        paused = false;
        return n;
    }

    // ------------------------------------------------------------------ Haus

    /** Sucht einen (fast) ebenen 5x5-Platz in der Nähe des Besitzers. Gibt die Boden-Ebene zurück. */
    public BlockPos findBuildSite() {
        if (!(level() instanceof ServerLevel sl)) return null;
        Player o = getOwner();
        BlockPos base = o != null ? o.blockPosition() : blockPosition();
        for (int i = 0; i < 100; i++) {
            int x = base.getX() + getRandom().nextInt(41) - 22;
            int z = base.getZ() + getRandom().nextInt(41) - 22;
            BlockPos site = buildSiteAt(sl, x, z);
            if (site != null) return site;
        }
        return null;
    }

    /**
     * Eignet sich die 5x5-Fläche ab (x, z) als Bauplatz? Boden eben, fest und gewachsen (kein Dach, keine Plattform,
     * kein Bretterboden des Spielers), darüber nichts Gebautes. Rückgabe: Boden-Ebene oder null.
     */
    static BlockPos buildSiteAt(ServerLevel sl, int x, int z) {
        if (!sl.hasChunkAt(new BlockPos(x, 64, z)) || !sl.hasChunkAt(new BlockPos(x + 4, 64, z + 4))) return null;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = 0; dx < 5; dx++)
            for (int dz = 0; dz < 5; dz++) {
                int h = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                BlockPos g = new BlockPos(x + dx, h - 1, z + dz);
                BlockState gs = sl.getBlockState(g);
                if (!gs.getFluidState().isEmpty() || gs.is(BlockTags.LEAVES) || gs.is(BlockTags.LOGS)
                        || gs.getCollisionShape(sl, g).isEmpty() || !naturalGround(gs)) return null;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        if (max - min > 1 || !freeAbove(sl, x, max, z)) return null;
        return new BlockPos(x, max, z);
    }

    private static boolean naturalGround(BlockState s) {
        return dev.begleiter.nav.BlockFlags.has(dev.begleiter.nav.BlockFlags.of(s), dev.begleiter.nav.BlockFlags.NATURAL);
    }

    /** Über dem Platz (5x5, 5 hoch) nur Luft, Gras/Blumen/Schnee oder Natürliches – keine Fackeln, Redstone, Schienen. */
    private static boolean freeAbove(ServerLevel sl, int x, int y, int z) {
        for (int dx = 0; dx < 5; dx++)
            for (int dz = 0; dz < 5; dz++)
                for (int dy = 0; dy < 5; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    BlockState s = sl.getBlockState(p);
                    if (!s.isAir() && !s.canBeReplaced() && !naturalGround(s)) return false;
                }
        return true;
    }

    /** Baumaterial im Inventar (Bretter, Bruchstein, Stein ...), größte Menge zuerst. */
    public Map<Item, Integer> buildMaterials() {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(ItemTags.PLANKS) || s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.STONE_BRICKS)
                    || s.is(Items.BRICKS) || s.is(Items.COBBLED_DEEPSLATE)) {
                m.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        List<Map.Entry<Item, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        Map<Item, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : es) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }

    private static boolean isHouseBlock(BlockState s) {
        if (s.is(BlockTags.PLANKS)) return true;
        return s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE) || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.BRICKS)
                || s.is(Blocks.COBBLED_DEEPSLATE);
    }

    /** Alle Bauplätze des 5x5-Hauses: Boden, Wände (mit Türöffnung), Dach. */
    private List<BlockPos> houseSpots(BlockPos o) {
        List<BlockPos> spots = new ArrayList<>();
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) spots.add(o.offset(dx, 0, dz));
        for (int dy = 1; dy <= 3; dy++)
            for (int dx = 0; dx < 5; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    boolean door = dz == 0 && dx == 2 && dy <= 2;
                    boolean annexDoor = houseAnnexes > 0 && dx == 4 && dz == 2 && dy <= 2;
                    if (edge && !door && !annexDoor) spots.add(o.offset(dx, dy, dz));
                }
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) spots.add(o.offset(dx, 4, dz));
        return spots;
    }

    private List<Task> houseTasks(BlockPos o, Map<Item, Integer> mats, List<BlockPos> need) {
        ServerLevel sl = (ServerLevel) level();
        List<Task> out = new ArrayList<>();
        for (int dy = 0; dy <= 4; dy++)
            for (int dx = 0; dx < 5; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    BlockPos p = o.offset(dx, dy, dz);
                    BlockState s = sl.getBlockState(p);
                    if (!s.isAir() && !s.canBeReplaced() && !isHouseBlock(s)) out.add(Task.breakAt(p, s.getBlock()));
                }
        List<Map.Entry<Item, Integer>> avail = new ArrayList<>(mats.entrySet());
        int idx = 0, used = 0;
        for (BlockPos p : need) {
            if (idx >= avail.size()) break;
            out.add(Task.place(p, Block.byItem(avail.get(idx).getKey())));
            if (++used >= avail.get(idx).getValue()) { idx++; used = 0; }
        }
        return out;
    }

    /** HOUSE-Aufgabe: berechnet, was noch fehlt, besorgt Material und plant dann den Bau. */
    public void houseStep(Task t) {
        if (!(level() instanceof ServerLevel sl)) return;
        CompanionCraft.convertLogsToPlanks(this, Integer.MAX_VALUE);
        BlockPos site = t.pos();
        if (site == null) {
            site = findBuildSite();
            if (site == null) { problem(CompanionTexts.tr("log.problem.no_house_site")); tasks.pollFirst(); return; }
        }
        List<BlockPos> need = new ArrayList<>();
        for (BlockPos p : houseSpots(site)) if (!isHouseBlock(sl.getBlockState(p))) need.add(p);
        Map<Item, Integer> mats = buildMaterials();
        int have = mats.values().stream().mapToInt(Integer::intValue).sum();
        if (need.isEmpty()) {
            houseBuilt = true;
            houseOrigin = site;
            if (houseNeedsDoor()) replaceCurrent(List.of(Task.door(houseDoor().getFirst(), "north")));
            else tasks.pollFirst();
            tell(CompanionTexts.tr("entity.house.done"));
            return;
        }
        if (have >= need.size()) {
            houseBuilt = true;
            houseOrigin = site;
            List<Task> build = new ArrayList<>(houseTasks(site, mats, need));
            build.add(Task.door(site.offset(2, 1, 0), "north")); // zum Schluss die Tür
            replaceCurrent(build);
            tell(CompanionTexts.tr("entity.house.start"));
            logInfo(EventKind.BUILD, CompanionTexts.tr("log.house.blocks", need.size(), site.toShortString()));
            return;
        }
        if (t.tries() > 6) { problem(CompanionTexts.tr("log.problem.house_material", have, need.size())); tasks.pollFirst(); return; }
        int logs = (need.size() - have + 3) / 4;
        logInfo(EventKind.BUILD, CompanionTexts.tr("log.house.material", have, need.size(), logs, t.tries() + 1));
        List<Task> list = new ArrayList<>();
        list.add(Task.gather(Resources.WOOD.key(), logs));
        list.add(Task.craft("#planks_from_logs", 0));
        list.add(new Task(new TaskSpec.House(site), t.tries() + 1));
        replaceCurrent(list);
    }

    public static final int MAX_ANNEXES = 4;

    /**
     * Anbau k (1, 2, ...) seitlich am Haus (+x): 4 Blöcke lang, gleiche Tiefe/Höhe, teilt die Wand mit dem Teil davor.
     * Bauplätze: Boden, Seitenwände, Stirnwand, Dach.
     */
    private List<BlockPos> annexSpots(int k) {
        List<BlockPos> spots = new ArrayList<>();
        int x0 = 4 * k + 1, x1 = 4 * k + 4;
        for (int dx = x0; dx <= x1; dx++) for (int dz = 0; dz < 5; dz++) spots.add(houseOrigin.offset(dx, 0, dz));
        for (int dy = 1; dy <= 3; dy++)
            for (int dx = x0; dx <= x1; dx++)
                for (int dz = 0; dz < 5; dz++)
                    if (dz == 0 || dz == 4 || dx == x1) spots.add(houseOrigin.offset(dx, dy, dz));
        for (int dx = x0; dx <= x1; dx++) for (int dz = 0; dz < 5; dz++) spots.add(houseOrigin.offset(dx, 4, dz));
        return spots;
    }

    /**
     * Steht im Bereich von Anbau k etwas Gebautes, das nicht zum Haus gehört (Kiste, Farm, Redstone des Spielers)?
     * Dann nicht dort bauen: Der Anbau dürfte sonst Fremdes abreißen. Natürliches, Pflanzen und Hausblöcke sind
     * in Ordnung; ungeladen zählt als belegt.
     */
    private BlockPos annexBlocked(ServerLevel sl, int k) {
        for (int dy = 0; dy <= 4; dy++)
            for (int dx = 4 * k + 1; dx <= 4 * k + 4; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    BlockPos p = houseOrigin.offset(dx, dy, dz);
                    if (!sl.hasChunkAt(p)) return p;
                    BlockState s = sl.getBlockState(p);
                    if (s.isAir() || s.canBeReplaced() || isHouseBlock(s) || naturalAt(sl, p, s)) continue;
                    return p;
                }
        return null;
    }

    /** EXTEND-Aufgabe: Lager voll -> Haus um einen Raum für Kisten verlängern (n = Nummer des Anbaus). */
    public void extendStep(Task t) {
        if (!(level() instanceof ServerLevel sl) || getHouseOrigin() == null) { tasks.pollFirst(); return; }
        int annex = ((TaskSpec.Extend) t.spec()).annex();
        int k = annex > 0 ? annex : houseAnnexes + 1;
        if (k > MAX_ANNEXES) { problem(CompanionTexts.tr("log.problem.house_large")); tasks.pollFirst(); return; }
        BlockPos blocked = annexBlocked(sl, k);
        if (blocked != null) { problem(CompanionTexts.tr("log.problem.annex_blocked", blocked.toShortString())); tasks.pollFirst(); return; }
        CompanionCraft.convertLogsToPlanks(this, Integer.MAX_VALUE);
        List<BlockPos> need = new ArrayList<>();
        for (BlockPos p : annexSpots(k)) if (!isHouseBlock(sl.getBlockState(p))) need.add(p);
        Map<Item, Integer> mats = buildMaterials();
        int have = mats.values().stream().mapToInt(Integer::intValue).sum();
        if (have >= need.size()) {
            houseAnnexes = Math.max(houseAnnexes, k);
            List<Task> out = new ArrayList<>();
            // Durchgang in der Wand davor, Innenraum frei räumen, Fremdes in Wänden/Boden/Dach weg
            List<BlockPos> passage = new ArrayList<>(List.of(houseOrigin.offset(4 * k, 1, 2), houseOrigin.offset(4 * k, 2, 2)));
            // im Hauptraum muss der Weg zum Durchgang (3,1,2) frei sein (dort kann eine Station stehen)
            if (k == 1) passage.addAll(List.of(houseOrigin.offset(3, 1, 2), houseOrigin.offset(3, 2, 2)));
            for (BlockPos p : passage) {
                BlockState s = sl.getBlockState(p);
                if (!s.isAir() && !s.canBeReplaced()) out.add(Task.breakAt(p, s.getBlock()));
            }
            for (int dy = 0; dy <= 4; dy++)
                for (int dx = 4 * k + 1; dx <= 4 * k + 4; dx++)
                    for (int dz = 0; dz < 5; dz++) {
                        BlockPos p = houseOrigin.offset(dx, dy, dz);
                        BlockState s = sl.getBlockState(p);
                        if (s.isAir() || s.canBeReplaced()) continue;
                        boolean inner = dy >= 1 && dy <= 3 && dz >= 1 && dz <= 3 && dx < 4 * k + 4;
                        if (inner || !isHouseBlock(s)) out.add(Task.breakAt(p, s.getBlock()));
                    }
            List<Map.Entry<Item, Integer>> avail = new ArrayList<>(mats.entrySet());
            int idx = 0, used = 0;
            for (BlockPos p : need) {
                if (idx >= avail.size()) break;
                out.add(Task.place(p, Block.byItem(avail.get(idx).getKey())));
                if (++used >= avail.get(idx).getValue()) { idx++; used = 0; }
            }
            replaceCurrent(out);
            tell(CompanionTexts.tr("entity.house.annex"));
            logInfo(EventKind.BUILD, CompanionTexts.tr("log.annex.blocks", k, need.size()));
            return;
        }
        if (t.tries() > 6) { problem(CompanionTexts.tr("log.problem.annex_material", have, need.size())); tasks.pollFirst(); return; }
        int logs = (need.size() - have + 3) / 4;
        logInfo(EventKind.BUILD, CompanionTexts.tr("log.annex.material", have, need.size(), logs, t.tries() + 1));
        List<Task> list = new ArrayList<>();
        list.add(Task.gather(Resources.WOOD.key(), logs));
        list.add(Task.craft("#planks_from_logs", 0));
        list.add(new Task(new TaskSpec.Extend(k), t.tries() + 1));
        replaceCurrent(list);
    }

    /** Kistenplätze im Haus samt Blickrichtung: Seiten des Hauptraums, in Anbauten die Reihen links/rechts vom Mittelgang. */
    private Map<BlockPos, Direction> houseChestSpots() {
        Map<BlockPos, Direction> m = new LinkedHashMap<>();
        if (getHouseOrigin() == null) return m;
        for (int dz : new int[]{3, 2, 1}) m.put(houseOrigin.offset(1, 1, dz), Direction.EAST);
        for (int dz : new int[]{3, 1}) m.put(houseOrigin.offset(3, 1, dz), Direction.WEST);
        for (int k = 1; k <= houseAnnexes; k++)
            for (int dx = 4 * k + 1; dx <= 4 * k + 3; dx++) {
                m.put(houseOrigin.offset(dx, 1, 1), Direction.SOUTH);
                m.put(houseOrigin.offset(dx, 1, 3), Direction.NORTH);
            }
        return m;
    }

    public int houseAnnexes() { return houseAnnexes; }

    // ------------------------------------------------------------------ Inventar / Ausrüstung

    private static Object swingDefault;
    private static boolean swingTried;

    /** Die Schwing-Animation (SwingAnimation) hat in 26.x keinen bekannten Standardwert -> per Reflection suchen. */
    private static Object resolveSwing() {
        if (swingTried) return swingDefault;
        swingTried = true;
        try {
            Class<?> c = net.minecraft.world.item.component.SwingAnimation.class;
            for (java.lang.reflect.Field f : c.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == c) {
                    swingDefault = f.get(null);
                    return swingDefault;
                }
            }
            Class<?> tc = net.minecraft.world.item.SwingAnimationType.class;
            Object type = null;
            Object[] consts = tc.getEnumConstants();
            for (Object o : consts) if (o.toString().equalsIgnoreCase("whack")) type = o;
            if (type == null) type = consts[0];
            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                Class<?>[] pt = ctor.getParameterTypes();
                if (pt.length == 2 && pt[0] == tc && pt[1] == int.class) {
                    ctor.setAccessible(true);
                    swingDefault = ctor.newInstance(type, 6);
                    return swingDefault;
                }
            }
        } catch (Exception | LinkageError e) {
            LOG.warn("[Begleiter] Swing animation unavailable: {}", e.toString());
        }
        return null;
    }

    public void swingArm() {
        Object a = resolveSwing();
        if (a instanceof net.minecraft.world.item.component.SwingAnimation sa) swing(InteractionHand.MAIN_HAND, sa, false);
    }

    public boolean hasFreeSlot() {
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).isEmpty()) return true;
        return false;
    }

    /** Was er bei vollem Inventar als Erstes wegwirft (neben Müll-Blöcken). */
    private static final Set<String> TRASH = RegistryIds.minecraftSet("rotten_flesh", "poisonous_potato", "spider_eye", "gravel", "sand",
            "red_sand", "kelp", "dead_bush", "short_grass", "tall_grass", "fern", "pointed_dripstone", "bone_block",
            "wheat_seeds", "beetroot_seeds", "pumpkin_seeds", "melon_seeds", "egg", "ink_sac");
    /** So viele Müll-Blöcke behält er immer zum Hochbauen/Zumauern (außer für Diamanten & Co.). */
    private static final int KEEP_SCAFFOLD = 32;

    public static boolean isTrash(Item item) {
        return isJunk(item) || TRASH.contains(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    /** Erze und ihre Ausbeute, Edelsteine, Barren: dafür wirft er notfalls auch seinen Gerüst-Vorrat weg. */
    public static boolean isPrecious(Item item) {
        var key = BuiltInRegistries.ITEM.getKey(item);
        if (!"minecraft".equals(key.getNamespace())) return false;
        String id = key.getPath();
        return id.endsWith("_ore") || id.startsWith("raw_") || id.endsWith("_ingot") || id.equals("diamond")
                || id.equals("emerald") || id.equals("ancient_debris") || id.equals("netherite_scrap") || id.equals("lapis_lazuli")
                || id.equals("redstone") || id.equals("coal") || id.equals("obsidian") || id.equals("ender_pearl") || id.equals("blaze_rod");
    }

    /** Welchen Stapel er für {@code wanted} wegwerfen würde (-1 = keinen). */
    private int trashSlotFor(Item wanted, boolean precious) {
        if (isTrash(wanted)) return -1; // Müll gegen Müll tauschen lohnt nicht
        int best = -1, bestScore = Integer.MAX_VALUE, junk = scaffoldCount();
        Item mainJunk = scaffoldItem();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || !isTrash(s.getItem()) || (isJunk(s.getItem()) && keepStack(s))) continue; // Gammelfleisch o.ä. darf weg
            // echter Abfall zuerst, dann Müll-Blöcke (kleinste Stapel, Hauptsorte zuletzt); Gerüst-Vorrat nur für Wertvolles
            int score = s.getCount() + (isJunk(s.getItem()) ? 100 : 0) + (s.is(mainJunk) ? 100 : 0);
            if (isJunk(s.getItem()) && junk - s.getCount() < KEEP_SCAFFOLD && !precious) continue;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    /**
     * Wie ein Spieler: Inventar (fast) voll -> Abfall und Müll-Blöcke über einen Vorrat von 64 hinaus wegwerfen,
     * statt deswegen zurück zum Lager zu laufen.
     */
    private void declutter(ServerLevel sl) {
        if (usedSlots() < inventory.getContainerSize() - 1 || build != null) return; // Bauauftrag braucht evtl. alles
        for (int i : clutterSlots()) {
            ItemStack out = inventory.removeItemNoUpdate(i);
            ItemEntity dropped = spawnAtLocation(sl, out);
            if (dropped != null) ignoredItems.add(dropped.getUUID());
            logInfo(CompanionTexts.tr("log.inventory.discard", out.getCount(), out.getHoverName()));
        }
    }

    /** Stapel, die declutter wegwerfen würde: Abfall und Müll-Blöcke über einen Vorrat von 64 (Hauptsorte 128) hinaus. */
    private List<Integer> clutterSlots() {
        List<Integer> out = new ArrayList<>();
        int junk = scaffoldCount();
        Item mainJunk = scaffoldItem();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || !isTrash(s.getItem()) || (isJunk(s.getItem()) && keepStack(s))) continue;
            if (isJunk(s.getItem()) && (junk - s.getCount() < 64 || (s.is(mainJunk) && junk - s.getCount() < 128))) continue;
            if (isJunk(s.getItem())) junk -= s.getCount();
            out.add(i);
        }
        return out;
    }

    /** Belegte Plätze ohne das, was er ohnehin wegwerfen würde – dafür lohnt kein Weg zum Lager. */
    public int usedSlotsWithoutClutter() {
        return usedSlots() - clutterSlots().size();
    }

    public boolean canMakeRoomFor(Item wanted, boolean precious) {
        return hasFreeSlot() || trashSlotFor(wanted, precious) >= 0;
    }

    /** Inventar voll, aber {@code wanted} ist wichtig: einen Stapel Abfall/Müll wegwerfen (und nicht wieder aufheben). */
    public boolean makeRoomFor(Item wanted, boolean precious) {
        if (hasFreeSlot()) return true;
        if (!(level() instanceof ServerLevel sl)) return false;
        int slot = trashSlotFor(wanted, precious);
        if (slot < 0) return false;
        ItemStack s = inventory.removeItemNoUpdate(slot);
        ItemEntity dropped = spawnAtLocation(sl, s);
        if (dropped != null) ignoredItems.add(dropped.getUUID());
        logInfo(CompanionTexts.tr("log.inventory.replace", s.getCount(), s.getHoverName(), new ItemStack(wanted).getHoverName()));
        return true;
    }

    public int findItem(Item item) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }

    public int foodCount() {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && s.has(DataComponents.FOOD)) n += s.getCount();
        }
        return n;
    }

    public ItemStack giveToInventory(ItemStack stack) {
        return inventory.addItem(stack);
    }

    private boolean keepStack(ItemStack s) {
        return reservedForRecipe(s) || s.has(DataComponents.EQUIPPABLE) || s.has(DataComponents.TOOL) || s.has(DataComponents.FOOD)
                || s.is(ItemTags.SWORDS) || hasPlaceTaskFor(s.getItem())
                || s.is(Items.TORCH) || s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE) || s.is(Items.SHIELD) || s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)
                || s.is(Items.FLINT_AND_STEEL) || (portalPos == null && s.is(Items.OBSIDIAN))
                || s.is(Items.BLAZE_ROD) || s.is(Items.BLAZE_POWDER) || s.is(Items.ENDER_PEARL) || s.is(Items.ENDER_EYE)
                || s.is(Items.BOW) || s.is(Items.ARROW) || s.is(Items.STRING) || s.is(Items.FEATHER) || s.is(Items.FLINT);
    }

    /** Wegschritte, die gescheitert sind (Ziel-Block -> bis wann meiden); der Wegplaner nimmt sie solange nicht. */
    public final Map<BlockPos, Integer> badSteps = new HashMap<>();

    public boolean isBadStep(BlockPos p) {
        Integer until = badSteps.get(p);
        if (until == null) return false;
        if (tickCount < until) return true;
        badSteps.remove(p);
        return false;
    }

    /**
     * Beim Graben in eine Höhle gefallen: liegen die nächsten Grab-/Laufschritte deutlich über ihm, passen sie nicht
     * mehr (sonst baut er sich zur alten Treppe hoch). Alles bis zur nächsten MINE-Aufgabe verwerfen -> plant von hier.
     */
    public boolean droppedBelowDigPlan() {
        Task cur = tasks.peekFirst();
        if (cur == null || cur.pos() == null || cur.pos().getY() <= getBlockY() + 3 || !onGround()) return false;
        if (cur.type() != TaskType.MOVE_TO && cur.type() != TaskType.BREAK && cur.type() != TaskType.PILLAR
                && cur.type() != TaskType.PLACE) return false;
        // gezielt angesteuertes Erz (auch über ihm) ist kein veralteter Grabschritt
        if (cur.type() == TaskType.BREAK && cur.block() != null && cur.block().defaultBlockState().is(BlockTags.ORES)) return false;
        boolean mine = false;
        for (Task q : tasks) if (q.type() == TaskType.MINE) { mine = true; break; }
        if (!mine) return false;
        // Gerüst/Bauen für den Hausbau o.ä. nicht anfassen: nur, wenn zwischen hier und MINE nichts anderes kommt
        for (Task q : tasks) {
            if (q.type() == TaskType.MINE) break;
            if (q.type() != TaskType.MOVE_TO && q.type() != TaskType.BREAK && q.type() != TaskType.PILLAR
                    && q.type() != TaskType.PLACE) return false;
        }
        while (tasks.peekFirst() != null && tasks.peekFirst().type() != TaskType.MINE) tasks.pollFirst();
        logInfo(CompanionTexts.tr("log.mine.fallen", getBlockY()));
        return true;
    }

    /** Bis dahin nicht wieder zum Einlagern (Lager war voll und ließ sich nicht erweitern). */
    public int storageFullUntil;

    /**
     * Wie viele Stapel das Einlagern (mit Vorrat: 16 Holz, 32 Stein, 8 Kohle, 16 Eisen) wirklich leeren würde.
     * Ausrüstung, Essen, Fackeln ... und der Vorrat bleiben bei ihm.
     */
    public int depositableSlots() {
        Map<String, Integer> quota = new HashMap<>(Map.of("wood", 16, "stone", 32, "coal", 8, "iron", 16));
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || keepStack(s)) continue;
            String key = quotaKey(s);
            int keep = 0;
            if (key != null) {
                int q = quota.get(key);
                keep = Math.min(q, s.getCount());
                quota.put(key, q - keep);
            }
            if (keep == 0) n++; // ganz leer (teilweise geleerte Stapel machen keinen Platz frei)
        }
        return n;
    }

    public boolean hasDepositable() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && !keepStack(s)) return true;
        }
        return false;
    }

    private static String quotaKey(ItemStack s) {
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) return "wood";
        if (s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)) return "stone";
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) return "coal";
        if (s.is(Items.RAW_IRON) || s.is(Items.IRON_INGOT)) return "iron";
        return null;
    }

    /**
     * Lagert ein; Rückgabe = wie viele Items nicht mehr hineinpassten (0 = alles drin). keepQuota: behält einen kleinen
     * Vorrat (Holz, Stein, Kohle, Eisen) für sich – wie ein Spieler.
     */
    public int depositInto(Container target, boolean keepQuota) {
        int left = 0;
        Map<String, Integer> quota = new HashMap<>(Map.of("wood", 16, "stone", 32, "coal", 8, "iron", 16));
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || keepStack(s)) continue;
            int keep = 0;
            String key = keepQuota ? quotaKey(s) : null;
            if (key != null) {
                int q = quota.get(key);
                keep = Math.min(q, s.getCount());
                quota.put(key, q - keep);
            }
            int move = s.getCount() - keep;
            if (move <= 0) continue;
            ItemStack part = s.copy();
            part.setCount(move);
            ItemStack rest = HopperBlockEntity.addItem(null, target, part, null);
            s.shrink(move - rest.getCount());
            left += rest.getCount();
        }
        return left;
    }

    /** Seine Lager-Kisten: die Lagerkiste und alle Kisten/Fässer in 6 Blöcken darum (Lagerkiste zuerst). */
    public List<BlockPos> storageChests(ServerLevel sl) {
        List<BlockPos> out = new ArrayList<>();
        if (homePos == null) return out;
        out.add(homePos);
        List<BlockPos> area = new ArrayList<>();
        BlockPos.betweenClosed(homePos.offset(-6, -2, -6), homePos.offset(6, 3, 6)).forEach(p -> area.add(p.immutable()));
        // dazu das ganze Haus samt Anbauten (die Lagerkiste kann auch außerhalb stehen)
        if (getHouseOrigin() != null)
            for (BlockPos p : BlockPos.betweenClosed(houseOrigin.offset(1, 1, 1), houseOrigin.offset(4 * houseAnnexes + 3, 1, 3)))
                if (Math.abs(p.getX() - homePos.getX()) > 6 || Math.abs(p.getZ() - homePos.getZ()) > 6
                        || p.getY() - homePos.getY() > 3 || homePos.getY() - p.getY() > 2) area.add(p.immutable());
        for (BlockPos p : area) {
            // Lager weit weg (ungeladen): nur die Lagerkiste selbst nennen, nichts nachladen
            if (p.equals(homePos) || !sl.hasChunkAt(p) || !isStorage(sl.getBlockState(p))) continue;
            BlockState bs = sl.getBlockState(p);
            // Doppelkiste nur einmal (die andere Hälfte zählt mit)
            if (bs.getBlock() instanceof ChestBlock && bs.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = p.relative(ChestBlock.getConnectedDirection(bs));
                if (out.contains(other)) continue;
            }
            out.add(p.immutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.equals(homePos) ? -1 : p.distSqr(homePos)));
        return out;
    }

    /**
     * Lager voll: neue Kiste daneben stellen. Erst eine einzelne Kiste zur Doppelkiste erweitern, sonst eine neue
     * Kiste neben die anderen. Braucht eine Kiste im Inventar. Rückgabe: Position oder null (kein Platz/keine Kiste).
     */
    public BlockPos placeStorageChest(ServerLevel sl) {
        int slot = findItem(Items.CHEST);
        if (slot < 0 || homePos == null) return null;
        List<BlockPos> chests = storageChests(sl);
        // Mit Haus: nur auf feste Plätze im Haus (Gänge bleiben frei); ohne Haus irgendwo neben die anderen
        Map<BlockPos, Direction> inHouse = getHouseOrigin() == null ? null : houseChestSpots();
        BlockPos spot = null;
        BlockState place = Blocks.CHEST.defaultBlockState();
        BlockPos partner = null; // vorhandene Kiste, die erst NACH dem bestätigten Setzen zur Hälfte wird
        BlockState partnerState = null;
        // 1. Einzelne Kiste -> Doppelkiste
        for (BlockPos c : chests) {
            BlockState bs = sl.getBlockState(c);
            if (!(bs.getBlock() instanceof ChestBlock) || bs.getValue(ChestBlock.TYPE) != ChestType.SINGLE) continue;
            Direction facing = bs.getValue(ChestBlock.FACING);
            for (boolean cw : new boolean[]{true, false}) {
                BlockPos p = c.relative(cw ? facing.getClockWise() : facing.getCounterClockWise());
                if (!freeChestSpot(sl, p) || (inHouse != null && !inHouse.containsKey(p))) continue;
                // Hälfte, deren Partner im Uhrzeigersinn liegt = LEFT
                partner = c;
                partnerState = bs.setValue(ChestBlock.TYPE, cw ? ChestType.LEFT : ChestType.RIGHT);
                place = place.setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, cw ? ChestType.RIGHT : ChestType.LEFT);
                spot = p;
                break;
            }
            if (spot != null) break;
        }
        // 2. Sonst eine neue einzelne Kiste (setBlock verbindet nicht von selbst mit Nachbarkisten)
        if (spot == null && inHouse != null) {
            for (Map.Entry<BlockPos, Direction> e : inHouse.entrySet())
                if (freeChestSpot(sl, e.getKey())) {
                    spot = e.getKey();
                    place = place.setValue(ChestBlock.FACING, e.getValue());
                    break;
                }
        } else if (spot == null) {
            outer:
            for (int r = 1; r <= 3; r++)
                for (BlockPos c : chests)
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos p = c.relative(d, r);
                        if (!freeChestSpot(sl, p)) continue;
                        spot = p;
                        BlockState ref = sl.getBlockState(c);
                        if (ref.getBlock() instanceof ChestBlock) place = place.setValue(ChestBlock.FACING, ref.getValue(ChestBlock.FACING));
                        break outer;
                    }
        }
        if (spot == null || !placeConfirmed(sl, spot, place)) return null;
        if (partner != null && sl.getBlockState(partner).is(Blocks.CHEST)) sl.setBlock(partner, partnerState, 3);
        sl.playSound(null, spot, place.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        inventory.getItem(slot).shrink(1);
        swingArm();
        logInfo(EventKind.BUILD, CompanionTexts.tr("log.storage.expanded", spot.toShortString()));
        return spot;
    }

    private boolean freeChestSpot(ServerLevel sl, BlockPos p) {
        return sl.getBlockState(p).isAir() && sl.getBlockState(p.above()).isAir()
                && sl.getBlockState(p.below()).isFaceSturdy(sl, p.below(), Direction.UP)
                && !getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p));
    }

    public void dropToOwner(Player owner) {
        giveTo(owner, null);
    }

    /** Wirft Items vor die Füße des Besitzers; key == null: alles außer Ausrüstung/Nahrung. */
    public void giveTo(Player owner, String key) { giveTo(owner, key, Integer.MAX_VALUE); }

    /**
     * key: Resources-Schlüssel, "item:<id>" (genau dieses Item, höchstens max Stück) oder null (alles Überflüssige).
     * Gibt zurück, wie viele Items er abgeworfen hat.
     */
    public int giveTo(Player owner, String key, int max) {
        if (!(level() instanceof ServerLevel sl)) return 0;
        Item exact = key != null && key.startsWith("item:") ? CompanionCraft.item(key.substring(5)) : null;
        Resources.Res res = key == null || exact != null ? null : Resources.byKey(key);
        if (exact == null) max = Integer.MAX_VALUE;
        BlockPos drop = safeDropSpot(sl, owner);
        int given = 0;
        for (int i = 0; i < inventory.getContainerSize() && given < max; i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) continue;
            boolean take = exact != null ? s.is(exact) : res != null ? res.item().test(s) : !keepStack(s);
            if (!take) continue;
            if (drop == null) { // nirgends sicher: lieber behalten als in Lava/Abgrund werfen
                tell(CompanionTexts.tr("entity.give.unsafe"));
                return 0;
            }
            int n = Math.min(s.getCount(), max - given);
            ItemEntity ie = new ItemEntity(sl, drop.getX() + 0.5, drop.getY() + 0.05, drop.getZ() + 0.5, s.copyWithCount(n), 0, 0, 0);
            ie.setDefaultPickUpDelay();
            if (!sl.addFreshEntity(ie)) continue; // abgelehnt: behalten statt verlieren
            given += n;
            s.shrink(n);
        }
        collectBlockedUntil = tickCount + 300;
        tell(given > 0 ? CompanionTexts.tr("entity.give.done", given) : CompanionTexts.tr("entity.give.none"));
        return given;
    }

    /**
     * Wohin abgegebene Items fallen. Zuerst ein Block vor dem Besitzer (Blickrichtung), dann seine Füße und die
     * Felder neben ihm, zuletzt die eigenen Füße. Nur Stellen, an denen die Items auf festem Boden liegen bleiben:
     * keine Lava, kein Feuer, kein Kaktus, kein Wasser (treibt weg), kein tiefer Abgrund. null = nirgends sicher.
     */
    public BlockPos safeDropSpot(ServerLevel sl, Player owner) {
        List<BlockPos> spots = new ArrayList<>();
        if (owner != null && owner.level() == sl) {
            BlockPos feet = owner.blockPosition();
            spots.add(feet.relative(owner.getDirection()));
            spots.add(feet);
            for (Direction d : Direction.Plane.HORIZONTAL) spots.add(feet.relative(d));
        }
        spots.add(blockPosition());
        for (BlockPos p : spots) if (dropSafe(sl, p)) return p;
        return null;
    }

    private static boolean dropSafe(ServerLevel sl, BlockPos p) {
        if (!sl.hasChunkAt(p)) return false;
        for (int d = 0; d <= 6; d++) {
            BlockPos q = p.below(d);
            if (q.getY() < sl.getMinY()) return false;
            BlockState st = sl.getBlockState(q);
            if (dropHazard(st) || !st.getFluidState().isEmpty()) return false;
            for (Direction side : Direction.Plane.HORIZONTAL) { // Lava/Feuer/Kaktus direkt daneben greift beim Fallen
                BlockState n = sl.getBlockState(q.relative(side));
                if (dropHazard(n) || n.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return false;
            }
            var shape = st.getCollisionShape(sl, q);
            if (!shape.isEmpty()) return d == 0 && shape.max(Direction.Axis.Y) < 1; // Stufe/Teppich ja, volle Wand nein
            BlockPos under = q.below();
            BlockState below = sl.getBlockState(under);
            if (!below.getCollisionShape(sl, under).isEmpty()) return !dropHazard(below) && below.getFluidState().isEmpty();
        }
        return false; // tiefer als 6 Blöcke: Abgrund
    }

    private static boolean dropHazard(BlockState st) {
        return st.is(BlockTags.FIRE) || st.is(BlockTags.CAMPFIRES) || st.is(Blocks.CACTUS) || st.is(Blocks.MAGMA_BLOCK)
                || st.is(Blocks.LAVA_CAULDRON) || st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    public Container containerAt(ServerLevel sl, BlockPos pos) {
        if (!sl.hasChunkAt(pos)) return null; // gemerkte Kiste in ungeladenem Chunk: unbekannt, nicht nachladen
        BlockState bs = sl.getBlockState(pos);
        BlockEntity be = sl.getBlockEntity(pos);
        Container box = bs.getBlock() instanceof ChestBlock cb ? ChestBlock.getContainer(cb, bs, sl, pos, true)
                : be instanceof Container c ? c : null;
        if (box != null) memory.noteChest(sl, pos, box); // hineingeschaut -> Inhalt merken
        return box;
    }

    private static boolean isStorage(BlockState s) {
        return s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock;
    }

    /**
     * Fester Bau-Modus (true = Kreativ, false = Survival); null = wie der Spielmodus des Besitzers.
     * Im Menü umschaltbar ({@link CompanionMenu#BUILD_MODE_TOGGLE}) und gespeichert als "BuildMode".
     */
    public Boolean buildModeOverride;

    /** Ist der Besitzer im Kreativmodus? Dann baut er mit unbegrenztem Material. */
    public boolean creativeHelper() {
        if (buildModeOverride != null) return buildModeOverride;
        ServerPlayer o = getOwnerAnywhere();
        return o != null && o.isCreative();
    }

    /**
     * Wie er sich beim Bauen zu den Baustellen bewegt (unabhängig vom Material-Modus, zum Ausbalancieren):
     * NORMAL läuft und klettert, FLY fliegt wie ein Kreativspieler, TELEPORT fliegt und springt bei weiten
     * oder festgefahrenen Wegen direkt zur Baustelle, GHOST fliegt durch alles hindurch und setzt die Blöcke sofort
     * (ohne Bautempo, ohne Ausweichen; Material kostet im Survival trotzdem).
     */
    public enum BuildMovement {
        NORMAL("leather_boots"), FLY("elytra"), TELEPORT("ender_pearl"), GHOST("phantom_membrane");

        public final String icon;
        BuildMovement(String icon) { this.icon = icon; }

        public BuildMovement next() { return values()[(ordinal() + 1) % values().length]; }

        public static BuildMovement byName(String name) {
            for (BuildMovement m : values()) if (m.name().equals(name)) return m;
            return null;
        }
    }

    /** Fest gewählte Bau-Bewegung; null = wie bisher (Kreativ fliegt, Survival läuft). Gespeichert als "BuildMovement". */
    public BuildMovement buildMovementOverride;

    public BuildMovement buildMovement() {
        if (buildMovementOverride != null) return buildMovementOverride;
        return creativeHelper() ? BuildMovement.FLY : BuildMovement.NORMAL;
    }

    /** Letzter Tick, in dem der Kreativ-Bau ihn in der Luft halten wollte (nicht gespeichert). */
    private int creativeFlightTick = Integer.MIN_VALUE / 2;

    /** Kreativ-Bau: diesen Tick ohne Schwerkraft schweben (der Bauauftrag ruft das jeden Tick, solange er fliegt). */
    public void keepCreativeFlight() {
        creativeFlightTick = tickCount;
        if (!isNoGravity()) setNoGravity(true);
        resetFallDistance();
    }

    /** Letzter Tick im Geist-Bau (nicht gespeichert): solange fliegt er durch Blöcke. */
    private int ghostTick = Integer.MIN_VALUE / 2;

    /** Geist-Bau: diesen Tick durch Blöcke fliegen (der Bauauftrag ruft das jeden Tick). */
    public void keepGhost() {
        ghostTick = tickCount;
        noPhysics = true;
        keepCreativeFlight();
    }

    /** Geist vorbei: wieder fest; steckt er in einem Block, nach oben ins Freie setzen. */
    private void endGhost() {
        if (!noPhysics || tickCount - ghostTick <= 2) return;
        noPhysics = false;
        if (level() instanceof net.minecraft.server.level.ServerLevel sl && !sl.noCollision(this)) {
            BlockPos p = blockPosition();
            for (int dy = 0; dy < 32; dy++) {
                BlockPos up = p.above(dy);
                if (sl.isOutsideBuildHeight(up)) break;
                if (sl.noCollision(this, getBoundingBox().move(0, up.getY() - getY(), 0))) {
                    snapTo(getX(), up.getY(), getZ(), getYRot(), getXRot());
                    break;
                }
            }
        }
    }

    /**
     * Fliegt er nicht mehr (Bau fertig, pausiert, andere Aufgabe, Neustart mitten im Flug): langsam senkrecht
     * landen, ohne Fallschaden, dann wieder mit Schwerkraft.
     */
    private void landAfterCreativeFlight() {
        if (!isNoGravity() || tickCount - creativeFlightTick <= 10) return;
        resetFallDistance();
        if (onGround() || isInWater() || isInLava()) { setNoGravity(false); return; }
        Vec3 v = getDeltaMovement();
        setDeltaMovement(v.x * 0.5, -0.4, v.z * 0.5);
    }

    /** Nächste Kiste/Fass (Lager zuerst) im Umkreis, die dieses Item enthält. */
    public BlockPos findContainerWith(Item item, int r) {
        return findContainerWith(item, r, p -> true);
    }

    /** Mit Quellenfilter für eigene Pläne; Suchen im Auftrag des Spielers nehmen die Variante ohne Filter. */
    public BlockPos findContainerWith(Item item, int r, java.util.function.Predicate<BlockPos> allowed) {
        if (!(level() instanceof ServerLevel sl)) return null;
        List<BlockPos> candidates = new ArrayList<>();
        if (getHome() != null) candidates.add(getHome());
        BlockPos c = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r / 2, -r), c.offset(r, r / 2, r)))
            if (sl.hasChunkAt(p) && isStorage(sl.getBlockState(p))) candidates.add(p.immutable());
        candidates.sort(Comparator.comparingDouble(p -> p.equals(getHome()) ? -1 : p.distSqr(c)));
        for (BlockPos p : candidates) {
            if (!allowed.test(p)) continue;
            Container box = containerAt(sl, p);
            if (box == null) continue;
            for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(item)) return p;
        }
        // Weiter weg: Kisten, in die er schon mal geschaut hat
        for (int guard = 0; guard < 8; guard++) {
            CompanionMemory.Place known = memory.chestWith(item, sl, c, 48, allowed);
            if (known == null) return null;
            if (!sl.hasChunkAt(known.pos)) return known.pos;
            Container box = containerAt(sl, known.pos); // frischt den gemerkten Inhalt auf
            if (box == null) { memory.forget(known); continue; }
            for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(item)) return known.pos;
        }
        return null;
    }

    public BlockPos findNearestContainer(BlockPos center, int r) {
        if (!(level() instanceof ServerLevel sl)) return null;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r / 2, -r), center.offset(r, r / 2, r))) {
            if (!sl.hasChunkAt(p)) continue;
            if (!isStorage(sl.getBlockState(p))) continue;
            double d = p.distSqr(center);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    /** Kisten/Fässer im Umkreis (Doppelkisten nur einmal), sortiert nach Abstand zur Mitte. */
    public List<BlockPos> storageBoxes(ServerLevel sl, BlockPos center) {
        List<BlockPos> out = new ArrayList<>();
        var zone = CompanionZones.work(this, CompanionZones.Kind.STORAGE);
        BlockPos from = zone == null ? center.offset(-8, -4, -8) : zone.min();
        BlockPos to = zone == null ? center.offset(8, 4, 8) : zone.max();
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            if (!sl.hasChunkAt(p)) continue;
            BlockState bs = sl.getBlockState(p);
            if (!isStorage(bs)) continue;
            if (bs.getBlock() instanceof ChestBlock && bs.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue;
            if (zone != null && bs.getBlock() instanceof ChestBlock && bs.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    && !zone.contains(p.relative(ChestBlock.getConnectedDirection(bs)))) continue;
            if (containerAt(sl, p.immutable()) != null) out.add(p.immutable());
        }
        out.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(center)).thenComparingLong(BlockPos::asLong));
        return out;
    }

    /** Sortiert alle Kisten/Fässer im Umkreis: zusammenführen und alphabetisch nach Item-ID füllen. */
    public int sortChests(ServerLevel sl, BlockPos center) {
        List<Container> boxes = new ArrayList<>();
        for (BlockPos p : storageBoxes(sl, center)) {
            Container c = containerAt(sl, p);
            if (c != null) boxes.add(c);
        }
        if (boxes.isEmpty()) return 0;
        List<ItemStack> all = new ArrayList<>();
        for (Container c : boxes) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) all.add(s.copy());
            }
            c.clearContent();
        }
        all.sort(Comparator.comparing((ItemStack s) -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack s : all) {
            ItemStack rest = s.copy();
            for (ItemStack m : merged) {
                if (rest.isEmpty()) break;
                if (ItemStack.isSameItemSameComponents(m, rest) && m.getCount() < m.getMaxStackSize()) {
                    int move = Math.min(rest.getCount(), m.getMaxStackSize() - m.getCount());
                    m.grow(move);
                    rest.shrink(move);
                }
            }
            if (!rest.isEmpty()) merged.add(rest);
        }
        int idx = 0;
        for (Container c : boxes) {
            for (int i = 0; i < c.getContainerSize() && idx < merged.size(); i++) c.setItem(i, merged.get(idx++));
            c.setChanged();
        }
        while (idx < merged.size()) spawnAtLocation(sl, merged.get(idx++)); // sollte nie passieren
        return boxes.size();
    }

    public boolean hasFoodAnimalNearby() { return nearestFoodAnimal() != null; }

    public Mob nearestFoodAnimal() {
        if (!(level() instanceof ServerLevel sl)) return null;
        List<Mob> l = sl.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(40),
                e -> e.isAlive() && !isIgnoredTarget(e) && FOOD_ANIMALS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())
                        && CompanionJobs.mayHunt(this, e));
        return l.stream().min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
    }

    private static double armorScore(ItemStack s, EquipmentSlot slot) {
        var m = s.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (m == null) return 0;
        return m.compute(Attributes.ARMOR, 0, slot) + 0.5 * m.compute(Attributes.ARMOR_TOUGHNESS, 0, slot);
    }

    private static double weaponScore(ItemStack s) {
        var m = s.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (m == null) return 0;
        // nur Bonus-Schaden zählt, sonst gilt jeder Block (Basis 1) als bessere Waffe als die leere Hand
        return m.compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND) - 1;
    }

    private void swapIn(EquipmentSlot slot, int invIndex) {
        ItemStack candidate = inventory.getItem(invIndex).copy();
        ItemStack old = getItemBySlot(slot).copy();
        setItemSlot(slot, candidate);
        inventory.setItem(invIndex, old);
    }

    private void autoEquip() {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            int best = -1;
            double bestScore = getItemBySlot(slot).isEmpty() ? -1 : armorScore(getItemBySlot(slot), slot);
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack s = inventory.getItem(i);
                if (s.isEmpty() || reservedForRecipe(s)) continue;
                var eq = s.get(DataComponents.EQUIPPABLE);
                if (eq == null || eq.slot() != slot) continue;
                double sc = armorScore(s, slot);
                if (sc > bestScore) { bestScore = sc; best = i; }
            }
            if (best >= 0) swapIn(slot, best);
        }
        if (getOffhandItem().isEmpty()) {
            int shield = findItem(Items.SHIELD);
            if (shield >= 0 && !reservedForRecipe(inventory.getItem(shield))) swapIn(EquipmentSlot.OFFHAND, shield);
        }
        if (getTarget() != null || tasks.isEmpty()) {
            int best = -1;
            double bestScore = weaponScore(getMainHandItem());
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack s = inventory.getItem(i);
                if (s.isEmpty() || reservedForRecipe(s)) continue;
                double sc = weaponScore(s);
                if (sc > bestScore) { bestScore = sc; best = i; }
            }
            if (best >= 0) swapIn(EquipmentSlot.MAINHAND, best);
        }
    }

    /** Bewertung eines Werkzeugs für einen Block: richtiges Werkzeug zählt zuerst, dann die Abbaugeschwindigkeit. */
    private static double toolScore(ItemStack s, BlockState state) {
        double sc = s.getDestroySpeed(state);
        if (state.requiresCorrectToolForDrops() && s.isCorrectToolForDrops(state)) sc += 1000;
        // Werkzeug-Pflege: fast kaputtes Werkzeug nur, wenn es nicht anders geht (lieber Ersatz oder die Hand)
        if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 3) sc -= 500;
        return sc;
    }

    public void equipToolFor(BlockState state) {
        double best = toolScore(getMainHandItem(), state);
        int bestIdx = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || reservedForRecipe(s)) continue;
            double sc = toolScore(s, state);
            if (sc > best + 0.01) { best = sc; bestIdx = i; }
        }
        if (bestIdx >= 0) { swapIn(EquipmentSlot.MAINHAND, bestIdx); return; }
        // Kein Werkzeug hilft bei diesem Block (z.B. Erde ohne Schaufel): Hand freimachen statt Spitzhacke zu verschleißen
        ItemStack held = getMainHandItem();
        if (!held.isEmpty() && best <= 1.01 && held.has(DataComponents.TOOL)) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).isEmpty()) {
                    inventory.setItem(i, held.copy());
                    setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    return;
                }
            }
        }
    }

    /** Beute und Verschleiß nur nach bestätigtem Entfernen genau des geprüften Blocks buchen. */
    public boolean mineBlock(ServerLevel sl, BlockPos p, BlockState s) {
        try {
            if (sl != level() || sl.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null
                    || s.isAir() || !sl.getBlockState(p).equals(s) || s.getDestroySpeed(sl, p) < 0
                    || (s.requiresCorrectToolForDrops() && !getMainHandItem().isCorrectToolForDrops(s))) return false;
            boolean damages = s.getDestroySpeed(sl, p) != 0;
            BlockEntity be = sl.getBlockEntity(p);
            List<ItemStack> drops = Block.getDrops(s, sl, p, be, this, getMainHandItem());
            if (!destroyMiningBlock(sl, p) || sl.getBlockState(p).equals(s)) return false;
            for (ItemStack d : drops) {
                ItemStack rest = inventory.addItem(d);
                if (!rest.isEmpty()) spawnAtLocation(sl, rest);
            }
            if (damages && !getMainHandItem().isEmpty() && getMainHandItem().getDestroySpeed(s) > 1.0f) {
                getMainHandItem().hurtAndBreak(1, this, EquipmentSlot.MAINHAND);
            }
            return true;
        } finally {
            sl.destroyBlockProgress(getId(), p, -1);
        }
    }

    /** Kleine Welt-API-Grenze; Tests können Ablehnung/Fehler ohne Eingriff in Vanilla simulieren. */
    protected boolean destroyMiningBlock(ServerLevel sl, BlockPos p) {
        return sl.destroyBlock(p, false, this);
    }

    /** Gegenstück fürs Setzen (Blöcke, Wasser, Luft beim Eimer-Füllen); Tests simulieren damit Ablehnung. */
    protected boolean setWorldBlock(ServerLevel sl, BlockPos p, BlockState st, int flags) {
        return sl.setBlock(p, st, flags);
    }

    public boolean worldSet(ServerLevel sl, BlockPos p, BlockState st, int flags) {
        return setWorldBlock(sl, p, st, flags);
    }

    /**
     * Block/Flüssigkeit setzen und bestätigen: true nur, wenn danach wirklich dieser Block dort steht. Erst danach
     * Material abbuchen oder Fortschritt zählen (sonst Duplikat bzw. verschwundenes Material).
     */
    public boolean placeConfirmed(ServerLevel sl, BlockPos p, BlockState st) {
        return setWorldBlock(sl, p, st, 3) && sl.getBlockState(p).is(st.getBlock());
    }

    public int breakTicks(ServerLevel sl, BlockPos p, BlockState s) {
        float hardness = s.getDestroySpeed(sl, p);
        ItemStack tool = getMainHandItem();
        float speed = Math.max(1f, tool.getDestroySpeed(s));
        boolean harvest = !s.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(s);
        return Math.max(5, (int) Math.ceil(hardness * (harvest ? 30 : 100) / speed));
    }

    // ------------------------------------------------------------------ Tick

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        endGhost();
        landAfterCreativeFlight();
        if (debugStepTicks > 0) debugStepTicks--;
        Task now = tasks.peekFirst();
        if (now != trackedTask) { trackedTask = now; trackedSince = tickCount; }

        if (tickCount % 20 == 0) {
            autoEquip();
            if (isSprinting()) hungerTimer++; // Sprinten macht doppelt so schnell hungrig
            if (++hungerTimer >= 30) { hungerTimer = 0; if (hunger > 0) hunger--; } // ca. alle 30 s ein Hungerpunkt
            if (hunger <= 8 || (hunger <= 16 && getHealth() < getMaxHealth() - 8)
                    || (hunger < 20 && getHealth() < 16)) eatSomething();
            scanHazards();
        }
        if (tickCount % 100 == 0 && getHealth() < getMaxHealth() && hunger >= 14) heal(1.0f);
        if (tickCount % 5 == 0) hazardTick();
        // Kreativ-Modus: unverwundbar (damit sehen Mobs ihn auch nicht als Feind und greifen ihn nicht an)
        if (tickCount % 5 == 0 && isPermanentlyInvulnerable() != creativeHelper()) setPermanentlyInvulnerable(creativeHelper());
        if (tickCount % 5 == 0) combatTick();
        if (tickCount % 20 == 0) shelterTick(level);
        if (tickCount % 10 == 0) torchTick(level);
        if (tickCount % 5 == 0 && !openedDoors.isEmpty()) CompanionDoors.closeBehind(level, this);
        if (tickCount % 4 == 0) autoPickup(level);
        if (tickCount % 6000 == 0) ignoredItems.clear();
        swim.tick(level);
        sprintTick(level);
        if (tickCount % 20 == 0) packUpStations(level);
        // Hintergrundscans je Entity versetzen; relative Fristen und Überlebenstimer bleiben unverändert.
        if (perceptionTick(5)) memory.scan(this, level);
        if (tickCount % 20 == 0) diamondSearchTick();
        if (perceptionTick(2)) memory.xrayScan(this, level);
        if (perceptionTick(100)) memory.verify(this, level);
        if (perceptionTick(200)) memory.checkVillage(this, level);
        if (tickCount % 40 == 0 && !keeper) declutter(level);
        // Unterbrochener Bauauftrag (z.B. nach fehlgeschlagener Materialsuche): später weitermachen
        if (tickCount % 20 == 0 && build != null && !build.paused && tasks.isEmpty() && !isPaused() && getTarget() == null && tickCount >= buildRetryAt) {
            tasks.addLast(Task.build());
        }
        // Gerüst/Pfeiler wieder abbauen, sobald nichts mehr zu tun ist – in jedem Modus
        if (tickCount % 40 == 0 && tasks.isEmpty() && hasScaffold() && !staying && getTarget() == null && !isPaused()) {
            tasks.addLast(Task.descend());
        }
        if (tickCount % 20 == 0) progressWatch();
        if (tickCount % 40 == 0) followAcrossDimensions(level);

        // Zu weit vom Besitzer entfernt -> zurückholen
        if (tickCount % 100 == 0 && !keeper && !staying && duty == null) {
            Player o = getOwner();
            // mit Auftrag (z.B. unter Tage graben) darf er weiter weg sein; auf dem Nether-Ausflug ist er allein unterwegs
            double limit = autonomous || !tasks.isEmpty() ? 160.0 : 64.0;
            if (o != null && !hasRecipeRequest() && !netherTrip && dev.begleiter.nav.FollowRecovery.beyond(distanceToSqr(o), limit)) {
                Vec3 safe = CompanionSafeTeleport.nearOwner(this, level, o.position());
                if (safe != null && CompanionSafeTeleport.move(this, level, safe)) {
                    planFeedback.finish(this, true);
                    tasks.clear();
                    nav.recoveryEvent(CompanionTexts.tr("nav.recovery.catchup"));
                }
            }
        }

        // Ziel-Timeout: nicht ewig an einem unerreichbaren Monster hängen bleiben
        LivingEntity tgt = getTarget();
        if (tgt != null) {
            if (targetSince < 0) targetSince = tickCount;
            boolean far = distanceToSqr(tgt) > 36.0;
            if (tickCount < ignoreFarTargetsUntil && far && tgt != huntTarget) {
                setTarget(null);
                targetSince = -1;
            } else if (!tgt.isAlive() || distanceToSqr(tgt) > 24.0 * 24.0 || tickCount - targetSince > 500) {
                if (tgt.isAlive() && tickCount - targetSince > 500) {
                    problem(CompanionTexts.tr("log.problem.target", tgt.getName()));
                    ignoreFarTargetsUntil = tickCount + 600;
                }
                if (tgt == huntTarget) huntTarget = null;
                setTarget(null);
                targetSince = -1;
            }
        } else {
            targetSince = -1;
        }

        // Wachhund: wenn er lange nichts tut, im Chat sagen warum
        if (!tasks.isEmpty() || getTarget() != null || staying || keeper) lastWorkTick = tickCount;
        if (autonomous && !staying && !keeper && tickCount % 200 == 0 && tickCount - lastWorkTick > 600) {
            problem(idleDiagnosticComponent());
            lastWorkTick = tickCount;
        }

        // Lagerverwalter
        if (keeper && homePos != null && tasks.isEmpty() && tickCount % 400 == 0) {
            boolean loose = !level.getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(16)).isEmpty();
            if (loose) tasks.addLast(Task.collect(true));
            if (loose || hasDepositable()) tasks.addLast(Task.deliver(false));
        }
        // Daueraufgabe (Feld, Tiere, Handel): im Leerlauf am Ort nach Arbeit schauen
        if (duty != null && !isPaused() && tasks.isEmpty() && getTarget() == null && tickCount >= dutyNextTick) {
            try {
                dutyNextTick = tickCount + CompanionDuties.tick(this, level);
            } catch (RuntimeException ex) {
                logError(CompanionTexts.tr("entity.error.duty", CompanionTexts.literal(ex.toString())));
                dutyNextTick = tickCount + 1200;
            }
        }

        planFeedback.completeIfIdle(this);
        // Dringenderes Bedürfnis (Nacht, wenig Leben, Hunger, Werkzeug kaputt) unterbricht eine Planer-Aufgabe
        if (autonomous && !staying && !keeper && !isPaused() && tickCount % 40 == 0 && getTarget() == null) {
            try { CompanionNeeds.checkInterrupt(this); } catch (RuntimeException ex) { logError(CompanionTexts.tr("entity.error.needs", CompanionTexts.literal(ex.toString()))); }
        }
        // Selbstständig spielen, wenn nichts zu tun ist
        if (autonomous && !staying && !keeper && !isPaused() && tasks.isEmpty() && getTarget() == null
                && tickCount % 10 == 0 && tickCount >= plannerCooldownUntil) {
            try {
                CompanionBrain.plan(this);
            } catch (RuntimeException ex) {
                logError(CompanionTexts.tr("entity.error.planner", CompanionTexts.literal(ex.toString())));
                plannerCooldownUntil = tickCount + 400;
            }
        }
        nav.sampleMotion(); // erst nachdem Schwimmen/Kampf entschieden haben, wer in diesem Tick steuert
    }

    /** Gleiche Scanfrequenz, aber verschiedene Phasen für gleichzeitig erzeugte Begleiter. */
    private boolean perceptionTick(int interval) {
        return Math.floorMod((long) tickCount + getId(), interval) == 0;
    }

    // ------------------------------------------------------------------ Kampf und Rückzug

    public boolean isRetreating() { return tickCount < retreatUntil; }

    /** Wie ein Spieler: bei wenig Leben zurückziehen und essen, vor zischenden Creepern weg, Schild gegen Fernkämpfer. */
    private LivingEntity watchTarget;
    private int combatSince;
    private double watchBestDist;
    private float watchHealth;
    /** Mobs, an die er nicht herankam: bis zu diesem Tick nicht wieder angreifen. */
    private final Map<UUID, Integer> ignoredTargets = new HashMap<>();

    /** Sprungkraft wie beim Absprung (Sprungstärke, Block-Faktor, Sprungkraft-Effekt) für die Sprung-Simulation. */
    public double navJumpPower() { return getJumpPower(); }

    public boolean isIgnoredTarget(LivingEntity t) {
        Integer until = ignoredTargets.get(t.getUUID());
        return until != null && tickCount < until;
    }

    /** Kommt er seinem Kampfziel 8 s lang nicht näher und trifft es nicht (Höhle, hinter Wand): aufgeben. */
    private void watchCombatTarget(LivingEntity tgt) {
        if (tgt == null || !tgt.isAlive()) { watchTarget = null; return; }
        double d = distanceToSqr(tgt);
        if (tgt != watchTarget) {
            watchTarget = tgt; combatSince = tickCount; watchBestDist = d; watchHealth = tgt.getHealth();
            return;
        }
        // Fortschritt: näher gekommen oder selbst getroffen (Sonnenbrand o.ä. zählt nicht)
        boolean hitByMe = tgt.getHealth() < watchHealth && tgt.getLastHurtByMob() == this;
        if (d < watchBestDist - 1.0 || hitByMe) {
            watchBestDist = Math.min(watchBestDist, d); watchHealth = tgt.getHealth(); combatSince = tickCount;
            return;
        }
        if (tickCount - combatSince > 160 && !(canShoot() && hasLineOfSight(tgt))) {
            logWarn(EventKind.DANGER, CompanionTexts.tr("log.combat.unreachable", tgt.getName()));
            ignoredTargets.put(tgt.getUUID(), tickCount + 600);
            ignoredTargets.values().removeIf(u -> u < tickCount);
            setTarget(null);
            if (huntTarget == tgt) huntTarget = null;
            getNavigation().stop();
            watchTarget = null;
        }
    }

    /** Im Kreativ-Modus greift er von selbst keine Mobs an; nur ausdrückliche Kampfaufträge setzen ein Ziel. */
    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && creativeHelper() && !orderedFight()) return;
        super.setTarget(target);
    }

    /** Kämpft er gerade für einen Auftrag (Jagen, Kämpfen, Kristalle)? Dann entscheidet der Auftrag das Ziel. */
    private boolean orderedFight() {
        Task t = tasks.peekFirst();
        return huntTarget != null || (t != null && (t.type() == TaskType.FIGHT || t.type() == TaskType.HUNT
                || t.type() == TaskType.CRYSTALS || t.type() == TaskType.FETCH));
    }

    public boolean hasBowAndArrows() { return findItem(Items.BOW) >= 0 && findItem(Items.ARROW) >= 0; }

    private int lastCombatEat = -1000;

    private void combatTick() {
        LivingEntity tgt = getTarget();
        boolean ordered = orderedFight();
        // Passiv: nicht von selbst kämpfen; wer ihn angreift, vor dem flieht er (zum Besitzer)
        if (stance == CompanionCombat.Stance.PASSIVE && !ordered) {
            if (tgt != null) { setTarget(null); tgt = null; }
            LivingEntity attacker = getLastHurtByMob();
            if (attacker != null && attacker.isAlive() && tickCount - getLastHurtByMobTimestamp() < 10 && !isRetreating())
                startRetreat(attacker, 60, CompanionTexts.tr("log.retreat.attacked"));
        }
        // Im Notunterschlupf: nicht kämpfen, nur heilen (die Wände halten Pfeile und Zombies ab)
        if (isSheltered()) {
            if (tgt != null) setTarget(null);
            return;
        }
        // Wichtigere Bedrohung? (z.B. ein Skelett, das auf den Besitzer schießt, statt der Spinne neben ihm)
        // Nicht mit wenig Leben: sonst greift er nach jedem Rückzug wieder an und wird erneut getroffen
        if (!isRetreating() && !ordered && stance != CompanionCombat.Stance.PASSIVE && tickCount % 10 == 0 && getHealth() >= 12) {
            LivingEntity best = CompanionCombat.bestThreat(this, 16);
            if (best != null && best != tgt) {
                double cur = tgt == null || !tgt.isAlive() ? Double.NEGATIVE_INFINITY : CompanionCombat.threat(this, tgt);
                if (CompanionCombat.threat(this, best) > cur + 20) {
                    if (tgt != null) logInfo(EventKind.DANGER, CompanionTexts.tr("log.combat.priority", best.getName(), tgt.getName()));
                    setTarget(best);
                }
            }
        }
        if (!isRetreating()) watchCombatTarget(getTarget());
        tgt = getTarget();
        // Creeper: mit Bogen auf Abstand gehen und schießen, ohne Bogen zuschlagen und gleich zurückspringen
        if (tgt instanceof Creeper cr && !isRetreating() && getHealth() >= 12) {
            double d2 = distanceToSqr(cr);
            if (hasBowAndArrows() && d2 < 6 * 6) startRetreat(cr, 20, CompanionTexts.tr("log.retreat.creeper_close"));
            else if (!hasBowAndArrows() && cr.hurtTime > 0 && getLastHurtMob() == cr && d2 < 9) startRetreat(cr, 15, CompanionTexts.tr("log.retreat.creeper_hit"));
        }
        if (tgt != null && !isRetreating()) {
            if (getHealth() < 12) {
                startRetreat(tgt, 120, CompanionTexts.tr("log.retreat.low_health", (int) getHealth()));
                eatSomething();
            } else if (tgt instanceof Creeper cr && distanceToSqr(cr) < 16 && (cr.getSwellDir() > 0 || cr.isIgnited())) {
                startRetreat(tgt, 40, CompanionTexts.tr("log.retreat.creeper_hiss"));
            }
        }
        if (isRetreating()) {
            motion.claim(dev.begleiter.nav.MotionController.Owner.COMBAT);
            nav.stop();
            if (getTarget() != null) setTarget(null);
            if (fleeFrom != null && fleeFrom.isAlive()) {
                // Zum Besitzer fliehen, wenn der weiter weg von der Gefahr steht; sonst einfach weg
                Player owner = getOwner();
                if (owner != null && owner.level() == level() && owner.distanceToSqr(this) < 32 * 32
                        && owner.distanceToSqr(this) > 9 && owner.distanceToSqr(fleeFrom) > distanceToSqr(fleeFrom) + 4) {
                    getNavigation().moveTo(owner, 1.3);
                } else {
                    Vec3 away = position().subtract(fleeFrom.position());
                    if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
                    away = away.normalize().scale(8);
                    getNavigation().moveTo(getX() + away.x, getY(), getZ() + away.z, 1.3);
                }
            }
        } else {
            // Erholt (oder nur kurz auf Abstand gegangen): weiterkämpfen
            if (fleeFrom != null && fleeFrom.isAlive() && getTarget() == null && getHealth() >= 12
                    && stance != CompanionCombat.Stance.PASSIVE && distanceToSqr(fleeFrom) < 20 * 20 && !isIgnoredTarget(fleeFrom))
                setTarget(fleeFrom);
            fleeFrom = null;
        }

        // Im Kampf heilen: bei wenig Leben goldener Apfel, sonst essen, wenn gerade niemand direkt dran ist
        if ((getTarget() != null || isRetreating()) && getHealth() < getMaxHealth() * 0.5f && tickCount - lastCombatEat >= 40) {
            LivingEntity near = isRetreating() ? fleeFrom : getTarget();
            boolean safe = near == null || distanceToSqr(near) > 6 * 6 || isRetreating();
            if (getHealth() < 8 && eatGoldenApple()) lastCombatEat = tickCount;
            else if (safe && foodCount() > 0) { eatSomething(); lastCombatEat = tickCount; }
        }

        // Schild: gegen Pfeile hochnehmen, für den Nahkampf wieder runter
        boolean shield = getOffhandItem().is(Items.SHIELD);
        LivingEntity threat = isRetreating() ? fleeFrom : getTarget();
        boolean block = shield && threat != null && threat.isAlive() && distanceToSqr(threat) < 24 * 24
                && ((threat instanceof RangedAttackMob && distanceToSqr(threat) > 9)
                || (threat instanceof Creeper cr && cr.getSwellDir() > 0));
        if (block && !isUsingItem()) startUsingItem(InteractionHand.OFF_HAND);
        else if (!block && isUsingItem() && getUsedItemHand() == InteractionHand.OFF_HAND) stopUsingItem();

        // Bogen: Ziel weiter weg oder in der Luft (Lohe, Drache, Phantom, Ghast)
        LivingEntity aim = getTarget();
        if (aim != null && aim.isAlive() && canShoot() && hasLineOfSight(aim)) {
            double d2 = distanceToSqr(aim);
            boolean flying = FLYING_TARGETS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(aim.getType()).toString());
            if (d2 < 48 * 48 && (d2 > 6 * 6 || (flying && aim.getY() > getY() + 2)) && !CompanionCombat.ownerInLine(this, aim)) shootArrow(aim);
        }
    }

    private void startRetreat(LivingEntity from, int ticks, Component why) {
        fleeFrom = from;
        retreatUntil = tickCount + ticks;
        setTarget(null);
        logWarn(EventKind.DANGER, why);
    }

    // ------------------------------------------------------------------ Notunterschlupf

    /** Blöcke des Notunterschlupfs (leer = keiner); nur zur Laufzeit, nach dem Neuladen bleiben sie einfach stehen. */
    private final List<BlockPos> shelter = new ArrayList<>();
    private BlockPos shelterFeet;

    public boolean isSheltered() { return !shelter.isEmpty(); }

    /** In Gefahr: zieht sich gerade zurück oder wurde vor Kurzem getroffen. */
    public boolean inDanger() {
        return isRetreating() || (getLastHurtByMob() != null && tickCount - getLastHurtByMobTimestamp() < 100);
    }

    /** Die freien Plätze um Füße und Kopf und über dem Kopf, die zugemauert werden müssen. */
    private List<BlockPos> shelterGaps(ServerLevel sl, BlockPos feet) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos head = feet.above();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            out.add(feet.relative(d));
            out.add(head.relative(d));
        }
        out.add(head.above());
        out.removeIf(p -> !sl.getBlockState(p).canBeReplaced());
        return out;
    }

    /**
     * Wie ein Spieler in der Not: Müll-Blöcke um sich herum und über den Kopf setzen und drinnen heilen.
     * false = nicht möglich (in der Luft, zu wenig Blöcke, ein Mob steht in einer Lücke).
     */
    public boolean sealIn(ServerLevel sl) {
        if (!onGround() || isSheltered()) return false;
        BlockPos feet = blockPosition();
        Vec3 centered = Vec3.atBottomCenterOf(feet);
        if (!CompanionSafeTeleport.safe(this, sl, centered)) return false;
        if (!sl.getBlockState(feet).canBeReplaced() || !sl.getBlockState(feet.above()).canBeReplaced()) return false;
        List<BlockPos> gaps = shelterGaps(sl, feet);
        if (gaps.size() > scaffoldCount()) return false;
        for (BlockPos p : gaps)
            if (!sl.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(p), e -> e != this).isEmpty()) return false;
        if (!CompanionSafeTeleport.move(this, sl, centered)) return false;
        for (BlockPos p : gaps) {
            Item item = scaffoldItem();
            int slot = item == null ? -1 : findItem(item);
            if (slot < 0) break;
            BlockState st = Block.byItem(item).defaultBlockState();
            if (!placeConfirmed(sl, p, st)) continue;
            inventory.getItem(slot).shrink(1);
            shelter.add(p.immutable());
        }
        sl.playSound(null, feet, net.minecraft.sounds.SoundEvents.STONE_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        shelterFeet = feet.immutable();
        retreatUntil = 0;
        fleeFrom = null;
        setTarget(null);
        logInfo(CompanionTexts.tr("log.shelter.built", feet.toShortString(), shelter.size()));
        return true;
    }

    /** Geheilt: Unterschlupf wieder abbauen (Blöcke sammelt er ein). Woanders hin geschickt: einfach vergessen. */
    private void shelterTick(ServerLevel sl) {
        if (shelter.isEmpty()) return;
        if (shelterFeet == null || !blockPosition().equals(shelterFeet)) {
            shelter.clear();
            return;
        }
        if (getHealth() < getMaxHealth() * 0.8f) return;
        // nur seine eigenen Müll-Blöcke abbauen; was dort inzwischen anderes steht, bleibt
        for (BlockPos p : shelter) {
            BlockState st = sl.getBlockState(p);
            if (!st.isAir() && isJunk(st.getBlock().asItem())) sl.destroyBlock(p, true, this);
        }
        shelter.clear();
        logInfo(CompanionTexts.tr("log.shelter.healed", (int) getHealth()));
    }

    // ------------------------------------------------------------------ Aufheben

    /** Wie ein Spieler: Items, über die er läuft, landen im Inventar (nicht kurz nachdem er dir etwas gegeben hat). */
    private void autoPickup(ServerLevel sl) {
        if (tickCount < collectBlockedUntil || !isAlive()) return;
        for (ItemEntity ie : sl.getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.0, 0.5, 1.0),
                e -> e.isAlive() && !e.hasPickUpDelay() && !ignoredItems.contains(e.getUUID()))) {
            ItemStack before = ie.getItem();
            ItemStack rest = inventory.addItem(before.copy());
            // Voll, aber das hier ist wichtig: Müll wegwerfen und nochmal
            if (rest.getCount() == before.getCount() && makeRoomFor(before.getItem(), isPrecious(before.getItem())))
                rest = inventory.addItem(before.copy());
            int taken = before.getCount() - rest.getCount();
            if (taken <= 0) continue;
            take(ie, taken);
            if (rest.isEmpty()) ie.discard(); else ie.setItem(rest);
        }
    }

    /** Items, an die er nicht herankam – die versucht er nicht wieder (Laufzeit, wird ab und zu geleert). */
    public final Set<UUID> ignoredItems = new HashSet<>();

    /**
     * Item, dem er nachläuft, und seit wann. Gilt über einzelne COLLECT-Aufgaben hinweg: wird eine übersprungen und die
     * nächste jagt dasselbe Item, läuft die Frist weiter, statt endlos neu zu beginnen.
     */
    private UUID chasedItem;
    private int chasedSince;

    /** Diesem Item nachlaufen; true, wenn es ein neues Ziel ist (Frist beginnt). */
    public boolean chase(ItemEntity ie) {
        if (ie.getUUID().equals(chasedItem)) return false;
        chasedItem = ie.getUUID();
        chasedSince = tickCount;
        return true;
    }

    /** Wie lange er dem aktuellen Item schon nachläuft. */
    public int chaseTicks() { return tickCount - chasedSince; }

    /** Dieser Tick zählt nicht als vergeblicher Versuch (z. B. Wegsuche wartet auf Rechenzeit). */
    public void holdChase() { chasedSince++; }

    /** Kommt nicht heran: liegen lassen und nicht wieder versuchen. */
    public void giveUpChase(ItemEntity ie) {
        ignoredItems.add(ie.getUUID());
        chasedItem = null;
    }

    /** Lohnt es sich hinzulaufen? Nicht auf Blättern (Baumkronen), nicht hoch über ihm, nicht schon gescheitert. */
    public boolean collectible(ItemEntity ie) {
        if (!ie.isAlive() || ignoredItems.contains(ie.getUUID()) || !CompanionZones.collectionAllowed(this, ie.blockPosition())) return false;
        if (ie.getY() - getY() > 3) return false;
        BlockPos p = ie.blockPosition();
        return !level().getBlockState(p).is(BlockTags.LEAVES) && !level().getBlockState(p.below()).is(BlockTags.LEAVES);
    }

    /** Liegen in der Nähe frische, erreichbare Items herum (nicht von ihm selbst gerade abgegeben)? */
    public boolean looseItemsNearby() {
        if (!(level() instanceof ServerLevel sl) || tickCount < collectBlockedUntil || !hasFreeSlot()) return false;
        return !sl.getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(12),
                e -> e.getAge() < 6000 && collectible(e)).isEmpty();
    }

    // ------------------------------------------------------------------ Fackeln

    /** Unter Tage im Dunkeln eine Fackel auf den Boden stellen (bei Bedarf aus Kohle + Stock herstellen). */
    private void torchTick(ServerLevel sl) {
        if (hasRecipeRequest()) return; // Fackeln nicht aus Zutaten herstellen, die für einen Auftrag vorgesehen sind
        if (tickCount - lastTorchTick < 40 || (tasks.isEmpty() && !autonomous)) return;
        // Nicht beim Hochbauen (Fackel im Schacht unter den Füßen) und nicht mitten in einem Sprung/Klettern des Weges
        Task cur = tasks.peekFirst();
        var move = nav.executor() == null || nav.executor().current() == null ? null : nav.executor().current().move.kind;
        if (pillarActive || (cur != null && cur.type() == TaskType.PILLAR) || move == dev.begleiter.nav.Move.Kind.PILLAR
                || move == dev.begleiter.nav.Move.Kind.PARKOUR || !onGround()) return;
        BlockPos feet = blockPosition();
        if (sl.getBrightness(LightLayer.BLOCK, feet) >= 4 || sl.getBrightness(LightLayer.SKY, feet) >= 4) return;
        if (!sl.getBlockState(feet).isAir() || !sl.getBlockState(feet.below()).isFaceSturdy(sl, feet.below(), Direction.UP)) return;
        if (findItem(Items.TORCH) < 0) craftTorches();
        int slot = findItem(Items.TORCH);
        if (slot < 0) return;
        // Wie ein Spieler an die Wand (steht nicht im Weg, z.B. beim Hochbauen im Schacht); sonst auf den Boden
        BlockState torch = Blocks.TORCH.defaultBlockState();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos wall = feet.relative(d);
            if (sl.getBlockState(wall).isFaceSturdy(sl, wall, d.getOpposite())) {
                torch = Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, d.getOpposite());
                break;
            }
        }
        if (!placeConfirmed(sl, feet, torch)) return;
        inventory.getItem(slot).shrink(1);
        lastTorchTick = tickCount;
        logInfo(CompanionTexts.tr("log.torch.placed", feet.toShortString()));
    }

    /** 4 Fackeln, wenn Kohle und Holz (Stock/Bretter/Stamm) da sind. */
    public boolean craftTorches() {
        if (CompanionCraft.count(this, "#coals") < 1) return false;
        if (CompanionCraft.count(this, "stick") < 1 && CompanionCraft.count(this, "#planks") < 2
                && CompanionCraft.count(this, "#logs") < 1) return false;
        return CompanionCraft.craft(this, "torch", 4);
    }

    // ------------------------------------------------------------------ Hunger, Wasser, Lava

    private static boolean isGoldenApple(ItemStack st) {
        return st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    /** Goldener Apfel für den Notfall: Regeneration und Absorption. */
    public boolean eatGoldenApple() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack st = inventory.getItem(i);
            if (!isGoldenApple(st)) continue;
            boolean enchanted = st.is(Items.ENCHANTED_GOLDEN_APPLE);
            st.shrink(1);
            applyGoldenApple(enchanted);
            hunger = Math.min(20, hunger + 4);
            logInfo(EventKind.FOOD, CompanionTexts.tr("log.food.apple", (int) getHealth()));
            return true;
        }
        return false;
    }

    private void applyGoldenApple(boolean enchanted) {
        addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION,
                enchanted ? 400 : 100, enchanted ? 1 : 1));
        addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION,
                2400, enchanted ? 3 : 0));
        heal(2f);
    }

    /** Vor einem langen Weg essen, mit den üblichen Regeln und Auftragsreservierungen. */
    public void eatBeforeTrip() { if (hunger < 12) eatSomething(); }

    private void eatSomething() {
        int best = -1, bestN = -1;
        boolean emergency = getHealth() < 8;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack st = inventory.getItem(i);
            var food = st.get(DataComponents.FOOD);
            if (st.isEmpty() || food == null || reservedForRecipe(st)) continue;
            // goldene Äpfel nur im Notfall (oder wenn es nichts anderes gibt)
            int score = isGoldenApple(st) ? (emergency ? 100 : -1) : food.nutrition();
            if (score > bestN) { best = i; bestN = score; }
        }
        if (best < 0) return;
        ItemStack st = inventory.getItem(best);
        if (isGoldenApple(st)) {
            boolean enchanted = st.is(Items.ENCHANTED_GOLDEN_APPLE);
            st.shrink(1);
            applyGoldenApple(enchanted);
            hunger = Math.min(20, hunger + 4);
            logInfo(EventKind.FOOD, CompanionTexts.tr("log.food.apple", (int) getHealth()));
            return;
        }
        bestN = st.get(DataComponents.FOOD).nutrition();
        Component foodName = st.getHoverName();
        hunger = Math.min(20, hunger + bestN);
        heal(bestN / 2.0f);
        st.shrink(1);
        logInfo(EventKind.FOOD, CompanionTexts.tr("log.food.eaten", foodName, hunger));
    }

    public static boolean isBlockIdPublic(BlockState s, String id) { return isBlockId(s, id); }

    /** Lava (immer) oder Wasser (wenn nicht erlaubt) direkt neben dem Block? */
    public boolean hazardAround(ServerLevel sl, BlockPos p, boolean allowWater) {
        for (Direction d : Direction.values()) {
            BlockState st = sl.getBlockState(p.relative(d));
            if (st.getFluidState().isEmpty()) continue;
            if (st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
            if (!allowWater) return true;
        }
        return false;
    }

    /**
     * Wie ein Spieler sprinten: auf freien, längeren Wegstücken und wenn ein Gegner noch weg ist. Nicht mit wenig
     * Hunger/Leben, nicht im Wasser und nicht, wenn auf den nächsten Metern Lava oder ein Abgrund kommt.
     */
    private void sprintTick(ServerLevel sl) {
        if (!onGround()) return; // in der Luft bleibt es, wie es ist (Sprint-Sprung)
        if (tickCount <= navControlUntil) return; // die neue Wegfindung steuert den Sprint selbst
        boolean want = false;
        if (!isInWater() && !isInLava() && hunger > 6 && getHealth() > 6 && !pillarActive && !isPaused()) {
            LivingEntity target = getTarget();
            if (target != null) {
                want = distanceToSqr(target) > 5 * 5;
            } else if (!getNavigation().isDone() && getNavigation().getPath() != null) {
                var path = getNavigation().getPath();
                int next = path.getNextNodeIndex(), left = path.getNodeCount() - next;
                want = left >= 5;
                for (int i = next; want && i < Math.min(path.getNodeCount(), next + 5); i++) {
                    BlockPos q = path.getNodePos(i);
                    if (hazardAround(sl, q, false) || sl.getBlockState(q.below()).isAir() && sl.getBlockState(q.below(2)).isAir())
                        want = false;
                }
            }
        }
        if (want != isSprinting()) setSprinting(want);
    }

    /** Merkt sich die nächste Lava / das nächste Wasser in der Nähe (für Menü und Warnungen). */
    private void scanHazards() {
        if (!(level() instanceof ServerLevel sl)) return;
        BlockPos c = blockPosition(), lava = null, water = null;
        double ld = Double.MAX_VALUE, wd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-5, -3, -5), c.offset(5, 3, 5))) {
            BlockState st = sl.getBlockState(p);
            if (st.getFluidState().isEmpty()) continue;
            double d = p.distSqr(c);
            if (isBlockId(st, "lava")) { if (d < ld) { ld = d; lava = p.immutable(); } }
            else if (d < wd) { wd = d; water = p.immutable(); }
        }
        nearestLava = lava;
        nearestWater = water;
        if (lava != null && ld <= 16) logWarn(CompanionTexts.tr("log.lava.near", lava.toShortString()));
    }

    /** Englisch: geht nur in den LLM-Kontext und ins Bot-Trace, nie direkt an Spieler. */
    public static final String NO_HAZARD = "none nearby";

    public String hazardText() {
        BlockPos c = blockPosition();
        String lava = nearestLava == null ? null
                : "lava " + (int) Math.sqrt(nearestLava.distSqr(c)) + " blocks (" + nearestLava.toShortString() + ")";
        String water = nearestWater == null ? null
                : "water " + (int) Math.sqrt(nearestWater.distSqr(c)) + " blocks";
        if (lava == null && water == null) return NO_HAZARD;
        return (lava == null ? "" : lava) + (lava != null && water != null ? ", " : "") + (water == null ? "" : water);
    }

    private BlockPos safeSpot(ServerLevel sl, int r) {
        Vec3 safe = CompanionSafeTeleport.find(this, sl, position(), r, 3, 3);
        return safe == null ? null : BlockPos.containing(safe);
    }

    /** Nächste sichere Stelle an Land (fester Boden, kein Wasser/Lava), Umkreis r. */
    public BlockPos landSpot(ServerLevel sl, int r) { return safeSpot(sl, r); }

    /** Notfall: aus Lava/Feuer retten, aus tiefem Wasser an Land schwimmen. */
    private void hazardTick() {
        if (!(level() instanceof ServerLevel sl)) return;
        boolean lava = isInLava();
        boolean burning = isOnFire() && !isInWater();
        boolean drowning = isInWater() && getAirSupply() < 100;
        if (!(lava || burning || drowning)) return;
        BlockPos spot = safeSpot(sl, 8);
        if (spot == null) return;
        if (lava || burning) {
            if (!CompanionSafeTeleport.move(this, sl, Vec3.atBottomCenterOf(spot))) return;
            planFeedback.finish(this, true); // die Notrettung bricht den bisherigen Plan ab
            tasks.clear();
            setTarget(null);
            problem(CompanionTexts.tr(lava ? "entity.hazard.lava" : "entity.hazard.fire"));
        } else if (getNavigation().isDone()) {
            getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.2);
        }
    }

    // ------------------------------------------------------------------ Interaktion

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Client kennt den Besitzer nicht: trotzdem SUCCESS, sonst wird die Interaktion für die zweite Hand wiederholt
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        // Einzelspieler: ist der alte Besitzer nicht da (z.B. anderer Name im Test-Client), übernimmt der Spieler ihn.
        // Nicht in einer für LAN geöffneten Welt: dort könnte sonst ein Gast den Begleiter eines anderen übernehmen.
        if (!isOwner(player) && getOwnerAnywhere() == null && level() instanceof ServerLevel sl
                && sl.getServer().isSingleplayer() && !sl.getServer().isPublished()) {
            setOwner(player.getUUID());
            player.sendSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("reply.owned")));
        }
        if (!isOwner(player)) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer sp) openPanel(sp, CompanionMenu.Page.MAIN);
        } else if (staying || keeper || autonomous || duty != null) {
            Component held = holdOrdersComponent();
            follow();
            player.sendSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("reply.follow")).append(held));
        } else {
            Component held = holdOrdersComponent();
            freeRoam();
            player.sendSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("reply.free")).append(held));
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty()) spawnAtLocation(level, s.copy());
            inventory.setItem(i, ItemStack.EMPTY);
        }
        // Wie ein Spieler, der respawnt: Sachen bleiben liegen, aber der Kern mit seinen Erinnerungen geht an den Besitzer
        BlockPos at = blockPosition();
        memory.remember(CompanionMemory.Kind.DEATH, source.getMsgId(), level, at);
        ItemStack core = new ItemStack(CompanionMod.COMPANION_CORE);
        CustomData.set(DataComponents.CUSTOM_DATA, core, saveMemory());
        tell(CompanionTexts.tr("entity.death", at.toShortString(), source.getLocalizedDeathMessage(this)));
        Player owner = getOwner();
        if (owner == null || !owner.getInventory().add(core)) {
            // Besitzer offline oder Inventar voll: Der Kern darf nicht nach 5 Minuten verschwinden.
            var dropped = spawnAtLocation(level, core);
            if (dropped != null) dropped.setUnlimitedLifetime();
        }
    }

    /** Was er nach dem Tod noch weiß: Lager, Haus, Modus, Name. */
    public CompoundTag saveMemory() {
        CompoundTag t = new CompoundTag();
        if (homePos != null) t.putLong("Home", homePos.asLong());
        if (houseOrigin != null) t.putLong("HouseOrigin", houseOrigin.asLong());
        if (portalPos != null) t.putLong("Portal", portalPos.asLong());
        t.putBoolean("HouseBuilt", houseBuilt);
        t.putInt("HouseAnnexes", houseAnnexes);
        if (minePos != null) { t.putLong("Mine", minePos.asLong()); t.putLong("MineSeed", mineSeed); }
        t.putBoolean("Autonomous", autonomous);
        t.putBoolean("Keeper", keeper);
        if (getCustomName() != null) t.putString("Name", getCustomName().getString());
        t.put("Memory", memory.save());
        t.put("Routes", routes.save());
        t.put("ExperienceV1", experiences.save(tickCount));
        if (projekt.vorhanden()) t.put("ProjectV1", projekt.speichern());
        return t;
    }

    public void loadMemory(CompoundTag t) {
        // Wiederbelebung wie bisher: Der Kern enthält Erinnerungen, keinen Hungerzustand.
        hunger = 20;
        hungerTimer = 0;
        if (t.contains("Home")) homePos = BlockPos.of(t.getLongOr("Home", 0));
        if (t.contains("HouseOrigin")) houseOrigin = BlockPos.of(t.getLongOr("HouseOrigin", 0));
        if (t.contains("Portal")) portalPos = BlockPos.of(t.getLongOr("Portal", 0));
        houseBuilt = t.getBooleanOr("HouseBuilt", false);
        houseAnnexes = Math.clamp(t.getIntOr("HouseAnnexes", 0), 0, MAX_ANNEXES);
        if (t.contains("Mine")) { minePos = BlockPos.of(t.getLongOr("Mine", 0)); mineSeed = t.getLongOr("MineSeed", 0); }
        autonomous = t.getBooleanOr("Autonomous", true);
        keeper = t.getBooleanOr("Keeper", false) && homePos != null;
        if (t.contains("Name")) setCustomName(Component.literal(loadedName(t.getStringOr("Name", DEFAULT_NAME))));
        memory.load(t.getCompoundOrEmpty("Memory"));
        if (t.contains("Routes")) routes.load(t.getCompoundOrEmpty("Routes"));
        experiences.load(t.getCompoundOrEmpty("ExperienceV1"), tickCount);
        projekt.laden(t.getCompoundOrEmpty("ProjectV1"));
    }

    /** Schwerer Treffer durch die Umgebung (Explosion, Lava, Sturz, Ersticken ...): Stelle als gefährlich merken. */
    public void rememberDanger(DamageSource source, float amount) {
        if (!(level() instanceof ServerLevel sl) || amount < 4 || isDeadOrDying()) return;
        if (source.getEntity() != null && !source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) return;
        memory.remember(CompanionMemory.Kind.DANGER, source.getMsgId(), sl, blockPosition());
    }

    /** Sieht er diesen Block (freie Seite in Sichtlinie)? */
    public boolean seesBlock(ServerLevel sl, BlockPos p) { return canSee(sl, p); }

    /** Mitte des Hauses (Fußhöhe) bzw. die zwei Blöcke der Türöffnung. */
    public BlockPos houseInside() { return getHouseOrigin() == null ? null : houseOrigin.offset(2, 1, 2); }
    /** Steht er im 3x3-Innenraum des Hauses? */
    public boolean isInsideHouse() {
        if (getHouseOrigin() == null) return false;
        BlockPos p = blockPosition().subtract(houseOrigin);
        return p.getX() >= 1 && p.getX() <= 3 + 4 * houseAnnexes && p.getZ() >= 1 && p.getZ() <= 3 && p.getY() >= 1 && p.getY() <= 2;
    }

    /** Haustür fehlt (Öffnung frei, keine Tür, nicht für die Nacht zugemauert)? */
    public boolean houseNeedsDoor() {
        if (getHouseOrigin() == null || !(level() instanceof ServerLevel sl)) return false;
        BlockPos d = houseDoor().getFirst();
        return !CompanionDoors.isDoor(sl, d) && CompanionDoors.canPlace(sl, d);
    }

    public List<BlockPos> houseDoor() {
        return getHouseOrigin() == null ? List.of() : List.of(houseOrigin.offset(2, 1, 0), houseOrigin.offset(2, 2, 0));
    }

    // ------------------------------------------------------------------ Wasser, Lava, Obsidian, Netherportal

    /** Sichtbare, erreichbare Lava-Quellen, nächste zuerst. */
    public List<BlockPos> lavaSources(int r) {
        List<BlockPos> out = new ArrayList<>();
        if (!(level() instanceof ServerLevel sl)) return out;
        BlockPos c = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -8, -r), c.offset(r, 5, r))) {
            if (sl.hasChunkAt(p) && sl.getFluidState(p).isSourceOfType(Fluids.LAVA) && exposed(sl, p)) out.add(p.immutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        List<BlockPos> ok = new ArrayList<>();
        for (BlockPos p : out) {
            if (ok.size() >= 3) break;
            if (canSee(sl, p) && reachable(p)) ok.add(p);
        }
        return ok;
    }

    public BlockPos findWaterSource(int r) {
        if (!(level() instanceof ServerLevel sl)) return null;
        BlockPos c = blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -6, -r), c.offset(r, 4, r))) {
            if (sl.hasChunkAt(p) && sl.getFluidState(p).isSourceOfType(Fluids.WATER) && exposed(sl, p)) found.add(p.immutable());
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        for (int i = 0; i < Math.min(found.size(), 20); i++) if (reachable(found.get(i))) return found.get(i);
        return null;
    }

    /** Wassereimer füllen: Quelle wird zu Luft, Eimer zu Wassereimer. */
    public boolean fillBucket(ServerLevel sl, BlockPos water) {
        int slot = findItem(Items.BUCKET);
        if (slot < 0 || !sl.getFluidState(water).isSourceOfType(Fluids.WATER)) return false;
        // erst die Quelle nehmen, dann den Eimer tauschen: abgelehnt -> kein Wasser aus dem Nichts
        if (!placeConfirmed(sl, water, Blocks.AIR.defaultBlockState())) return false;
        inventory.getItem(slot).shrink(1);
        giveToInventory(new ItemStack(Items.WATER_BUCKET));
        sl.playSound(null, water, net.minecraft.sounds.SoundEvents.BUCKET_FILL, net.minecraft.sounds.SoundSource.NEUTRAL, 1f, 1f);
        swingArm();
        return true;
    }

    /**
     * Wasser auf Lava gießen und wieder aufnehmen (wie ein Spieler): die Quelle und angrenzende Quellen werden
     * Obsidian (Rückgabe), fließende Lava daneben Bruchstein. Der Wassereimer bleibt voll.
     */
    public List<BlockPos> pourWater(ServerLevel sl, BlockPos lava) {
        List<BlockPos> made = new ArrayList<>();
        if (findItem(Items.WATER_BUCKET) < 0 || !sl.getFluidState(lava).isSourceOfType(Fluids.LAVA)) return made;
        List<BlockPos> cells = new ArrayList<>(List.of(lava));
        for (Direction d : Direction.values()) cells.add(lava.relative(d));
        for (BlockPos p : cells) {
            var fs = sl.getFluidState(p);
            if (fs.isSourceOfType(Fluids.LAVA)) { if (placeConfirmed(sl, p, Blocks.OBSIDIAN.defaultBlockState())) made.add(p.immutable()); }
            else if (fs.is(net.minecraft.tags.FluidTags.LAVA)) placeConfirmed(sl, p, Blocks.COBBLESTONE.defaultBlockState());
        }
        sl.playSound(null, lava, net.minecraft.sounds.SoundEvents.LAVA_EXTINGUISH, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        swingArm();
        logInfo(EventKind.PORTAL, CompanionTexts.tr("log.portal.obsidian", lava.toShortString(), made.size()));
        return made;
    }

    /** Rahmen eines 4x5-Portals (ohne Ecken, 10 Obsidian) an der Bauplatz-Ecke o, in x-Richtung bei z+2. */
    public static List<BlockPos> portalFrame(BlockPos o) {
        BlockPos b = o.offset(0, 0, 2);
        List<BlockPos> l = new ArrayList<>();
        l.add(b.offset(1, 0, 0)); l.add(b.offset(2, 0, 0));
        for (int y = 1; y <= 3; y++) { l.add(b.offset(0, y, 0)); l.add(b.offset(3, y, 0)); }
        l.add(b.offset(1, 4, 0)); l.add(b.offset(2, 4, 0));
        return l;
    }

    public static List<BlockPos> portalInside(BlockPos o) {
        BlockPos b = o.offset(0, 0, 2);
        List<BlockPos> l = new ArrayList<>();
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 2; x++) l.add(b.offset(x, y, 0));
        return l;
    }

    /** Mit dem Feuerzeug anzünden; true, wenn danach Portalblöcke da sind. */
    public boolean lightPortal(ServerLevel sl, BlockPos o) {
        int slot = findItem(Items.FLINT_AND_STEEL);
        if (slot < 0) return false;
        BlockPos fire = portalInside(o).getFirst();
        if (!sl.getBlockState(fire).isAir()) return false;
        if (!worldSet(sl, fire, net.minecraft.world.level.block.BaseFireBlock.getState(sl, fire), 11)) return false;
        sl.playSound(null, fire, net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        // Haltbarkeit nur, wenn wirklich etwas brennt (Feuer oder sofort entstandenes Portal)
        if (sl.getBlockState(fire).is(Blocks.NETHER_PORTAL) || sl.getBlockState(fire).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)
            inventory.getItem(slot).hurtAndBreak(1, sl, null, item -> {});
        swingArm();
        return sl.getBlockState(fire).is(Blocks.NETHER_PORTAL);
    }

    /** Nächster Portalblock in der Nähe (bekanntes Portal zuerst). */
    public BlockPos nearestPortal(int r) {
        if (!(level() instanceof ServerLevel sl)) return null;
        BlockPos known = getPortalPos();
        if (known != null) {
            for (BlockPos p : portalInside(known))
                if (sl.hasChunkAt(p) && sl.getBlockState(p).is(Blocks.NETHER_PORTAL)) return p.immutable();
        }
        BlockPos c = blockPosition(), best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r / 2, -r), c.offset(r, r / 2, r))) {
            if (!sl.getBlockState(p).is(Blocks.NETHER_PORTAL) || sl.getBlockState(p.below()).is(Blocks.NETHER_PORTAL)) continue;
            double d = p.distSqr(c);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    /**
     * Wie bei gezähmten Wölfen zählt sein Treffer als Treffer des Besitzers: sonst lassen z.B. Lohen keine
     * Lohenruten fallen (Beute nur "vom Spieler getötet") und es gibt keine Erfahrung.
     * Wird vor dem Schaden aufgerufen (CompanionMod, ALLOW_DAMAGE), damit auch der tödliche Treffer zählt.
     */
    public void creditOwner(LivingEntity victim) {
        ServerPlayer o = getOwnerAnywhere();
        if (o != null && victim != o) victim.setLastHurtByPlayer(o, 100);
    }

    // ------------------------------------------------------------------ Fernkampf mit Bogen

    private int lastShotTick = -100;

    public boolean canShoot() {
        return findItem(Items.BOW) >= 0 && findItem(Items.ARROW) >= 0 && tickCount - lastShotTick >= 20;
    }

    /** Einen Pfeil auf ein Ziel schießen (auch auf Endkristalle, die keine Mobs sind). */
    public boolean shootArrow(net.minecraft.world.entity.Entity target) {
        if (!(level() instanceof ServerLevel sl) || !canShoot()) return false;
        ItemStack bow = inventory.getItem(findItem(Items.BOW));
        var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(sl, this, new ItemStack(Items.ARROW), bow.copy());
        double dx = target.getX() - getX(), dy = target.getY(0.5) - arrow.getY(), dz = target.getZ() - getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + d * 0.12, dz, 2.6f, 1.5f);
        sl.addFreshEntity(arrow);
        inventory.getItem(findItem(Items.ARROW)).shrink(1);
        bow.hurtAndBreak(1, sl, null, item -> {});
        getLookControl().setLookAt(target);
        sl.playSound(null, blockPosition(), net.minecraft.sounds.SoundEvents.ARROW_SHOOT, net.minecraft.sounds.SoundSource.NEUTRAL, 1f, 1f);
        lastShotTick = tickCount;
        return true;
    }

    /** Nächste Netherfestung (wie ein erfahrener Spieler, der weiß, wo er suchen muss). */
    public BlockPos locateFortress() {
        if (!(level() instanceof ServerLevel sl) || sl.dimension() != Level.NETHER) return null;
        var reg = sl.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var fortress = reg.get(net.minecraft.world.level.levelgen.structure.BuiltinStructures.FORTRESS);
        if (fortress.isEmpty()) return null;
        return sl.findNearestMapStructure(net.minecraft.core.HolderSet.direct(fortress.get()), blockPosition(), 40, false);
    }

    // ------------------------------------------------------------------ Festung und Endportal (Oberwelt)

    /**
     * Festung, zu der ein Enderauge fliegen würde, und darin der Portalraum. Den Portalraum liest er aus der
     * Weltstruktur (statt die ganze Festung abzusuchen). Gibt die Mitte des Portalraums zurück.
     */
    public BlockPos locateEndRoom() {
        if (!(level() instanceof ServerLevel sl) || !inOverworld()) return null;
        BlockPos start = sl.findNearestMapStructure(net.minecraft.tags.StructureTags.EYE_OF_ENDER_LOCATED, blockPosition(), 100, false);
        if (start == null) return null;
        var reg = sl.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var stronghold = reg.get(net.minecraft.world.level.levelgen.structure.BuiltinStructures.STRONGHOLD);
        if (stronghold.isPresent()) {
            sl.getChunk(start); // Struktur-Daten laden
            var ss = sl.structureManager().getStructureAt(start, stronghold.get().value());
            if (ss != null && ss.isValid()) {
                for (var piece : ss.getPieces())
                    if (piece instanceof net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.PortalRoom)
                        return piece.getBoundingBox().getCenter();
            }
        }
        return new BlockPos(start.getX(), 30, start.getZ()); // Fallback: Festungsmitte, Tiefe geschätzt
    }

    /** Ein Enderauge in Richtung Ziel werfen (wie ein Spieler; es kann zerbrechen oder liegen bleiben). */
    public void throwEye(BlockPos target) {
        if (!(level() instanceof ServerLevel sl)) return;
        int slot = findItem(Items.ENDER_EYE);
        if (slot < 0) return;
        var eye = new net.minecraft.world.entity.projectile.EyeOfEnder(sl, getX(), getY(0.5), getZ());
        eye.setItem(inventory.getItem(slot).copyWithCount(1));
        eye.signalTo(Vec3.atLowerCornerOf(target));
        sl.addFreshEntity(eye);
        inventory.getItem(slot).shrink(1);
        sl.playSound(null, blockPosition(), net.minecraft.sounds.SoundEvents.ENDER_EYE_LAUNCH,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.5f, 0.4f);
        swingArm();
    }

    /** Endportal-Rahmen in der Nähe. */
    public List<BlockPos> portalFrames(int r) {
        List<BlockPos> out = new ArrayList<>();
        if (!(level() instanceof ServerLevel sl)) return out;
        BlockPos c = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -12, -r), c.offset(r, 12, r)))
            if (sl.getBlockState(p).is(Blocks.END_PORTAL_FRAME)) out.add(p.immutable());
        return out;
    }

    public static boolean frameHasEye(ServerLevel sl, BlockPos p) {
        BlockState s = sl.getBlockState(p);
        return s.is(Blocks.END_PORTAL_FRAME) && s.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE);
    }

    /** Enderauge über die normale Item-Logik einsetzen – das letzte Auge öffnet das Portal wie beim Spieler. */
    public boolean placeEye(ServerLevel sl, BlockPos frame) {
        int slot = findItem(Items.ENDER_EYE);
        if (slot < 0 || frameHasEye(sl, frame)) return false;
        ItemStack one = inventory.getItem(slot).copyWithCount(1);
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(frame).add(0, 0.5, 0), Direction.UP, frame, false);
        var ctx = new net.minecraft.world.item.context.UseOnContext(sl, null, InteractionHand.MAIN_HAND, one, hit) {};
        Items.ENDER_EYE.useOn(ctx);
        if (one.isEmpty()) inventory.getItem(slot).shrink(1);
        swingArm();
        return frameHasEye(sl, frame);
    }

    /** In der Festung darf der Wegplaner auch Steinziegel abbauen. */
    public boolean inStronghold() {
        return endRoomPos != null && endPortalPos == null && blockPosition().distSqr(endRoomPos) < 96 * 96;
    }

    /** Endkristalle (keine Mobs, nur mit Pfeilen zu treffen), nächste zuerst. */
    public List<net.minecraft.world.entity.boss.enderdragon.EndCrystal> endCrystals(int r) {
        if (!(level() instanceof ServerLevel sl)) return List.of();
        List<net.minecraft.world.entity.boss.enderdragon.EndCrystal> l = new ArrayList<>(sl.getEntitiesOfClass(
                net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, getBoundingBox().inflate(r, 128, r),
                net.minecraft.world.entity.Entity::isAlive));
        l.sort(Comparator.comparingDouble(this::distanceToSqr));
        return l;
    }

    /** Nächster Mob dieser Art (Entity-ID wie "blaze"), optional nur nahe center. */
    public Mob nearestOfType(String id, int r, BlockPos center) {
        if (!(level() instanceof ServerLevel sl)) return null;
        var wanted = RegistryIds.parse(id);
        if (wanted == null) return null;
        Mob best = null;
        double bd = Double.MAX_VALUE;
        for (Mob m : sl.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(r), Mob::isAlive)) {
            if (!BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).equals(wanted)) continue;
            if (center != null && m.blockPosition().distSqr(center) > 48 * 48) continue;
            double d = distanceToSqr(m);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    /** Nächster Monster-Spawner (in Festungen: Lohen-Spawner). */
    public BlockPos nearestSpawner(int r) {
        if (!(level() instanceof ServerLevel sl)) return null;
        BlockPos c = blockPosition(), best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r / 2, -r), c.offset(r, r / 2, r))) {
            if (!sl.getBlockState(p).is(Blocks.SPAWNER)) continue;
            double d = p.distSqr(c);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    /** Nächster Spawner in den geladenen Chunks im Umkreis r (Festungen sind größer als der Blockscan von nearestSpawner). */
    public BlockPos nearestLoadedSpawner(int r) {
        if (!(level() instanceof ServerLevel sl)) return null;
        BlockPos c = blockPosition(), best = null;
        double bd = Double.MAX_VALUE;
        for (int cx = (c.getX() - r) >> 4; cx <= (c.getX() + r) >> 4; cx++) {
            for (int cz = (c.getZ() - r) >> 4; cz <= (c.getZ() + r) >> 4; cz++) {
                var chunk = sl.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockPos p : chunk.getBlockEntities().keySet()) {
                    if (!sl.getBlockState(p).is(Blocks.SPAWNER)) continue;
                    double d = p.distSqr(c);
                    if (d < bd) { bd = d; best = p.immutable(); }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Dem Besitzer in andere Dimensionen folgen

    private int crossSince = -1;

    /** Besitzer in einer anderen Dimension: zum Portal laufen und durchgehen; klappt das nicht, direkt nachkommen. */
    private void followAcrossDimensions(ServerLevel level) {
        ServerPlayer o = getOwnerAnywhere();
        if (o == null || o.level() == level || keeper || staying || duty != null || netherTrip || hasRecipeRequest()) { crossSince = -1; return; }
        if (crossSince < 0) {
            crossSince = tickCount;
            tell(CompanionTexts.tr("entity.portal.follow"));
        }
        // Netherportal nur zwischen Oberwelt und Nether; ins/aus dem End kommt er direkt nach
        boolean viaNether = level.dimension() == Level.NETHER || o.level().dimension() == Level.NETHER;
        BlockPos portal = viaNether && tickCount - crossSince < 1200 ? nearestPortal(16) : null;
        if (portal != null) {
            Task t = currentTask();
            if (t == null || t.type() != TaskType.MOVE_TO || !portal.equals(t.pos())) {
                cancelAll();
                addTask(Task.moveTo(portal, 1));
            }
            return;
        }
        if (tickCount - crossSince >= 200) {
            ServerLevel destination = o.level();
            Vec3 safe = CompanionSafeTeleport.nearOwner(this, destination, o.position());
            if (safe == null) return;
            var moved = teleport(new net.minecraft.world.level.portal.TeleportTransition(destination, safe,
                    Vec3.ZERO, getYRot(), getXRot(), net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            if (moved instanceof CompanionEntity arrived) {
                CompanionSafeTeleport.resetMotion(arrived);
                arrived.logInfo(EventKind.PORTAL, CompanionTexts.tr("log.portal.direct"));
            }
        }
    }

    // ------------------------------------------------------------------ Speichern

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        if (ownerUuid != null) out.store("Owner", UUIDUtil.CODEC, ownerUuid);
        if (homePos != null) out.store("Home", BlockPos.CODEC, homePos);
        out.putBoolean("Staying", staying);
        out.putBoolean("Keeper", keeper);
        out.putBoolean("HerdKeeping", herdKeeping);
        out.putInt("Hunger", hunger);
        out.putInt("HungerTimer", hungerTimer);
        out.putString("Stance", stance.name());
        out.putBoolean("Autonomous", autonomous);
        out.store("WorkZones", CompanionZones.Zone.CODEC.listOf(), zones.all());
        if (duty != null && dutyPos != null) { out.putString("Duty", duty.name()); out.store("DutyPos", BlockPos.CODEC, dutyPos); out.putString("DutyDimension", dutyDimension); }
        out.putBoolean("HouseBuilt", houseBuilt);
        out.putInt("HouseAnnexes", houseAnnexes);
        if (minePos != null) { out.store("Mine", BlockPos.CODEC, minePos); out.putLong("MineSeed", mineSeed); }
        if (houseOrigin != null) out.store("HouseOrigin", BlockPos.CODEC, houseOrigin);
        if (portalPos != null) out.store("Portal", BlockPos.CODEC, portalPos);
        out.putBoolean("NetherTrip", netherTrip);
        if (buildModeOverride != null) out.putString("BuildMode", buildModeOverride ? "creative" : "survival");
        if (buildMovementOverride != null) out.putString("BuildMovement", buildMovementOverride.name());
        if (netherPortal != null) out.store("NetherPortal", BlockPos.CODEC, netherPortal);
        if (fortressPos != null) out.store("Fortress", BlockPos.CODEC, fortressPos);
        if (endRoomPos != null) out.store("EndRoom", BlockPos.CODEC, endRoomPos);
        if (endPortalPos != null) out.store("EndPortal", BlockPos.CODEC, endPortalPos);
        inventory.storeAsItemList(out.list("CompanionInventory", ItemStack.CODEC));
        if (build != null) CompanionBuilder.save(out, build);
        out.store("Memory", CompoundTag.CODEC, memory.save());
        out.store("Routes", CompoundTag.CODEC, routes.save());
        out.store("ExperienceV1", CompoundTag.CODEC, experiences.save(tickCount));
        out.store("RequestDialogueV1", CompoundTag.CODEC, requestDialogue.save(level().getGameTime()));
        if (projekt.vorhanden()) out.store("ProjectV1", CompoundTag.CODEC, projekt.speichern());
        if (!scaffold.isEmpty()) out.store("Scaffold", BlockPos.CODEC.listOf(), scaffold);
        // Nur Spieleraufträge überdauern den Neustart; eigene Pläne (goal != null) plant er danach neu
        if (goal == null && !tasks.isEmpty()) {
            CompoundTag queue = dev.begleiter.task.TaskQueueCodec.save(tasks, paused);
            if (queue != null) out.store("TaskQueueV2", CompoundTag.CODEC, queue);
            else logWarn(CompanionTexts.tr("queue.save.too_many", tasks.size()));
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        guaranteeEquipmentDrops(); // alte Welten haben 1.0 gespeichert
        // Minecraft liest CustomName vorher; Welten mit dem alten Standardnamen bekommen den neuen.
        if (getCustomName() != null && getCustomName().getString().equals(OLD_DEFAULT_NAME))
            setCustomName(Component.literal(DEFAULT_NAME));
        ownerUuid = in.read("Owner", UUIDUtil.CODEC).orElse(null);
        homePos = in.read("Home", BlockPos.CODEC).orElse(null);
        staying = in.getBooleanOr("Staying", false);
        keeper = in.getBooleanOr("Keeper", false);
        herdKeeping = in.getBooleanOr("HerdKeeping", false);
        hunger = Math.clamp(in.getIntOr("Hunger", 20), 0, 20);
        hungerTimer = Math.clamp(in.getIntOr("HungerTimer", 0), 0, 29);
        stance = CompanionCombat.Stance.byName(in.getStringOr("Stance", "AGGRESSIVE"));
        autonomous = in.getBooleanOr("Autonomous", true);
        zones.load(in.read("WorkZones", CompanionZones.Zone.CODEC.listOf()).orElse(List.of()));
        dutyDimension = in.getStringOr("DutyDimension", level().dimension().identifier().toString());
        dutyPos = in.read("DutyPos", BlockPos.CODEC).orElse(null);
        duty = dutyPos == null ? null : CompanionDuties.Duty.byName(in.getStringOr("Duty", ""));
        if (duty != null) { autonomous = false; staying = false; keeper = false; }
        houseBuilt = in.getBooleanOr("HouseBuilt", false);
        houseAnnexes = Math.clamp(in.getIntOr("HouseAnnexes", 0), 0, MAX_ANNEXES); // Bereich der Lagerscans begrenzen
        minePos = in.read("Mine", BlockPos.CODEC).orElse(null);
        mineSeed = in.getLongOr("MineSeed", 0);
        houseOrigin = in.read("HouseOrigin", BlockPos.CODEC).orElse(null);
        portalPos = in.read("Portal", BlockPos.CODEC).orElse(null);
        netherTrip = in.getBooleanOr("NetherTrip", false);
        buildModeOverride = switch (in.getStringOr("BuildMode", "")) {
            case "creative" -> Boolean.TRUE;
            case "survival" -> Boolean.FALSE;
            default -> null;
        };
        buildMovementOverride = BuildMovement.byName(in.getStringOr("BuildMovement", ""));
        netherPortal = in.read("NetherPortal", BlockPos.CODEC).orElse(null);
        fortressPos = in.read("Fortress", BlockPos.CODEC).orElse(null);
        endRoomPos = in.read("EndRoom", BlockPos.CODEC).orElse(null);
        endPortalPos = in.read("EndPortal", BlockPos.CODEC).orElse(null);
        inventory.fromItemList(in.listOrEmpty("CompanionInventory", ItemStack.CODEC));
        // Ab hier optionale Teile. Eine Exception beim Laden verwirft bei Minecraft den ganzen Begleiter samt
        // Inventar; ein unlesbarer Teil darf darum nur sich selbst kosten.
        build = null;
        loadPart("Build", () -> {
            CompanionBuilder.BuildJob loaded = CompanionBuilder.load(in);
            if (loaded != null && level() instanceof ServerLevel sl) loaded.inDimension(sl);
            build = loaded;
        });
        loadPart("Memory", () -> in.read("Memory", CompoundTag.CODEC).ifPresent(memory::load));
        loadPart("Routes", () -> in.read("Routes", CompoundTag.CODEC).ifPresent(routes::load));
        loadPart("ExperienceV1", () -> experiences.load(in.read("ExperienceV1", CompoundTag.CODEC).orElseGet(CompoundTag::new), tickCount));
        loadPart("RequestDialogueV1", () -> requestDialogue.load(in.read("RequestDialogueV1", CompoundTag.CODEC).orElseGet(CompoundTag::new), level().getGameTime()));
        loadPart("ProjectV1", () -> projekt.laden(in.read("ProjectV1", CompoundTag.CODEC).orElseGet(CompoundTag::new)));
        scaffold.clear();
        loadPart("Scaffold", () -> in.read("Scaffold", BlockPos.CODEC.listOf()).ifPresent(scaffold::addAll));
        tasks.clear();
        // V1 (alte Welten) bleibt lesbar; gespeichert wird nur noch V2
        loadPart("TaskQueue", () -> in.read("TaskQueueV2", CompoundTag.CODEC).or(() -> in.read("TaskQueueV1", CompoundTag.CODEC))
                .ifPresent(this::restoreTasks));
    }

    /** Einen optionalen Teil laden; bei einem Fehler bleibt er leer, der Begleiter selbst bleibt erhalten. */
    private void loadPart(String part, Runnable load) {
        try {
            load.run();
        } catch (RuntimeException ex) {
            LOG.error("Companion {}: saved part {} unreadable, discarded", getUUID(), part, ex);
            logWarn(CompanionTexts.tr("load.part_failed", CompanionTexts.literal(part)));
        }
    }

    /** Gespeicherte Spieleraufträge übernehmen; die vorderste Aufgabe startet frisch und prüft die Welt selbst. */
    private void restoreTasks(CompoundTag queue) {
        var loaded = dev.begleiter.task.TaskQueueCodec.load(queue);
        if (loaded.problem() != null) {
            logWarn(CompanionTexts.tr("queue.load.rejected", loaded.problemText()));
            return;
        }
        tasks.addAll(loaded.tasks());
        paused = loaded.paused();
        if (loaded.skipped() > 0) logInfo(CompanionTexts.tr("queue.load.skipped", loaded.skipped()));
        if (!tasks.isEmpty()) logInfo(CompanionTexts.tr("queue.load.resumed", tasks.size(), paused ? CompanionTexts.tr("queue.load.paused") : Component.empty()));
    }
}
