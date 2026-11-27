package dev.begleiter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Der Begleiter: ein "zweiter Spieler" mit 36-Slot-Inventar, Werkzeug-/Rüstungsnutzung,
 * Aufgabenwarteschlange und einem eigenen Überlebens-Planer (siehe CompanionBrain).
 * <p>Die Arbeit steckt in Bausteinen, die die Entity als Feld hält und die ihren Zustand selbst speichern
 * (gleiche Schlüssel wie zuvor): {@link CompanionMode} (Modus, Besitzer, Pause), {@link CompanionTick} (KI-Tick),
 * {@link CompanionOrders} (Aufträge in der Warteschlange), {@link CompanionLog}, {@link CompanionInventory},
 * {@link CompanionEquipment}, {@link CompanionStorage}, {@link CompanionStations}, {@link CompanionGathering},
 * {@link CompanionExplore}, {@link CompanionWatchdog}, {@link CompanionHazards}, {@link CompanionPickup},
 * {@link CompanionFlight}, {@link CompanionWorldEdit}, {@link CompanionTalk}, {@link CompanionSoul} sowie Haus, Essen,
 * Gerüst, Kampf, Unterschlupf, Fackeln, Portale und Expedition. Die öffentlichen Methoden hier sind meist
 * Weiterleitungen, damit Aufrufer nicht alle auf einmal umziehen müssen.
 */
public class CompanionEntity extends PathfinderMob implements net.minecraft.world.entity.ContainerUser {

    public enum TaskType { BREAK, PLACE, COLLECT, DELIVER, GIVE, CRAFT, SMELT, SORT, MOVE_TO, HUNT, FETCH, HOUSE, GATHER, STATION, TREE, CLIMB, DESCEND, MINE, WATER, POUR, PORTAL, PILLAR, TRAVEL, FIGHT, STRONGHOLD, CRYSTALS, BUILD, TAKE, EXTEND,
        FARM, BREED, TRADE, REPAIR, GEARUP, DOOR, REQUEST, PROCESS, FISH, BREW }

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
        public static Task brew(String potion, int count) { return new Task(new TaskSpec.Brew(potion, count)); }
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
        public static Task fish(int catches) { return new Task(new TaskSpec.Fish(null, catches)); }
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
    /** Standardname eines neuen Begleiters; Eigenname, darum in jeder Sprache gleich. */
    public static final String DEFAULT_NAME = "Leak";
    /** Früherer Standardname; nur genau dieser wird beim Laden ersetzt, eigene Namen bleiben. */
    static final String OLD_DEFAULT_NAME = "Begleiter";
    /**
     * Versionsstand der Begleiter-Daten. 0 = vor Einführung (alte Welten, wird normal gelesen). Höher als dieser Stand
     * = Welt aus einer neueren Mod-Version: lesen, was bekannt ist, aber nichts selbstständig fortsetzen.
     */
    public static final int DATA_VERSION = 1;
    /**
     * Wertlose Blöcke, die er zum Hochbauen, Brückenbauen und Zumauern verbraucht ("Müll"). Nichts Wertvolles.
     * Datapack-Tag {@code begleiter:junk}; Tags bindet das Spiel bei jedem Reload neu, die Abfrage veraltet nicht.
     */
    public static final TagKey<Item> JUNK = TagKey.create(Registries.ITEM, CompanionMod.id("junk"));
    /** Was er bei vollem Inventar als Erstes wegwirft (neben Müll-Blöcken): {@code begleiter:trash}. */
    public static final TagKey<Item> TRASH = TagKey.create(Registries.ITEM, CompanionMod.id("trash"));
    /** Erze, Ausbeute, Edelsteine, Barren: {@code begleiter:precious}. */
    public static final TagKey<Item> PRECIOUS = TagKey.create(Registries.ITEM, CompanionMod.id("precious"));
    /** Ausrüstung und Reisebedarf, den er nie wegwirft: {@code begleiter:keep}. */
    public static final TagKey<Item> KEEP = TagKey.create(Registries.ITEM, CompanionMod.id("keep"));

    private final SimpleContainer inventory = new SimpleContainer(36);
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();

    // ------------------------------------------------------------------ Bausteine

    public final CompanionMode mode = new CompanionMode(this);
    private final CompanionTick ticker = new CompanionTick(this);
    /** Spiel-Log, Problemmeldungen und Statuszeilen. */
    public final CompanionLog log = new CompanionLog(this);
    public final CompanionOrders orders = new CompanionOrders(this);
    public final CompanionWatchdog watchdog = new CompanionWatchdog(this);
    public final CompanionInventory items = new CompanionInventory(this);
    public final CompanionEquipment gear = new CompanionEquipment(this);
    public final CompanionStorage storage = new CompanionStorage(this);
    public final CompanionStations stations = new CompanionStations(this);
    public final CompanionGathering gathering = new CompanionGathering(this);
    public final CompanionExplore explore = new CompanionExplore(this);
    public final CompanionHazards hazards = new CompanionHazards(this);
    public final CompanionPickup pickup = new CompanionPickup(this);
    public final CompanionFlight flight = new CompanionFlight(this);
    public final CompanionWorldEdit edits = new CompanionWorldEdit(this);
    public final CompanionTalk talk = new CompanionTalk(this);
    public final CompanionSoul soul = new CompanionSoul(this);
    /** Haus mit Lager-Anbauten (speichert sich selbst). */
    public final CompanionHouse house = new CompanionHouse(this);
    /** Hunger und Essen (speichert sich selbst). */
    public final CompanionFood food = new CompanionFood(this);
    public final CompanionPotions potions = new CompanionPotions(this);
    /** Hochbauen und gemerkte Gerüstblöcke (speichert sich selbst). */
    public final CompanionScaffold scaffolding = new CompanionScaffold(this);
    /** Kampf, Rückzug und Bogen. */
    public final CompanionFighting fighting = new CompanionFighting(this);
    /** Notunterschlupf aus Müll-Blöcken. */
    public final CompanionShelter shelter = new CompanionShelter(this);
    public final CompanionTorches torches = new CompanionTorches(this);
    /** Eigeninitiative: Besitzer, Nacht, Gewohnheiten, Basis, Meldungen, Graben. */
    public final CompanionInitiative initiative = new CompanionInitiative(this);
    /** Wasser, Lava, Obsidian und Netherportal. */
    public final CompanionPortals portals = new CompanionPortals(this);
    /** Strukturen, Enderaugen, Spawner, Kristalle. */
    public final CompanionExpedition expedition = new CompanionExpedition(this);
    /** Schwimmen, Tauchen, aus Strömungen befreien. */
    public final CompanionSwim swim = new CompanionSwim(this);
    public final CompanionWork work = new CompanionWork(this);
    public final CompanionJourney journey = new CompanionJourney(this);
    /** Gespeicherte Arbeitsbereiche; unabhängig vom gerade gewählten Modus. */
    public final CompanionZones zones = new CompanionZones();
    /** Was er gesehen hat: Erze, Bäume, Wasser, Kisten, Gefahren ... (wird gespeichert, überlebt den Tod im Kern). */
    public final CompanionMemory memory = new CompanionMemory();
    /** Laufzeit-Lernsignal; begrenzt, speichert keine sensiblen Auftragsdaten. */
    public final dev.begleiter.ai.CompanionExperienceMemory experiences = new dev.begleiter.ai.CompanionExperienceMemory();
    public final CompanionRequestDialogue requestDialogue = new CompanionRequestDialogue();
    public final CompanionPlanFeedback planFeedback = new CompanionPlanFeedback();
    /** Bestätigter, aber noch nicht ausführbarer Projektentwurf. */
    public final CompanionProjektVerwaltung projekt = new CompanionProjektVerwaltung();
    public final dev.begleiter.nav.MotionController motion = new dev.begleiter.nav.MotionController(this);
    /** Wegfindung (dev.begleiter.nav). */
    public final dev.begleiter.nav.Navigator nav = new dev.begleiter.nav.Navigator(this);
    /** Trampelpfade der neuen Wegfindung (pro Welt, gespeichert). */
    public final dev.begleiter.nav.RouteMemory routes = new dev.begleiter.nav.RouteMemory();
    /** Chunks um ihn (nicht gespeichert): nur solange er etwas zu tun hat und nicht auf den Spieler wartet. */
    public final dev.begleiter.world.CompanionChunkLoader.State chunkLoader = new dev.begleiter.world.CompanionChunkLoader.State();

    // ------------------------------------------------------------------ Zustand, den Menü, Befehle, Planer und Tests direkt lesen

    public long dutyStorageFingerprint = Long.MIN_VALUE;
    final List<CompanionStockRoutine> stockRules = new ArrayList<>();
    java.util.UUID woodZoneOwner;
    int nextStockRule;
    /** Hinweis "kein Futter / nichts zu verkaufen" schon gesagt (nicht gespeichert, damit er nicht nervt). */
    boolean dutyHintGiven;
    /** Zählt Chatbefehle (nicht gespeichert); eine späte LLM-Antwort gilt nur, wenn seitdem kein neuer kam. */
    int chatSerial;
    /** Kurzer Gesprächsverlauf für freie LLM-Antworten (nicht gespeichert). */
    final dev.begleiter.llm.ChatHistory llmHistory = new dev.begleiter.llm.ChatHistory();
    // Nether-Ausflug: eigener Plan, folgt dem Besitzer dann nicht zurück
    public boolean netherTrip;
    public BlockPos netherPortal; // Ankunftsportal im Nether
    public BlockPos fortressPos;  // Netherfestung (Nether-Koordinaten)
    /** Bestätigter Lohen-Spawner (Nether): diese Festung wird nie verworfen. */
    public BlockPos blazeSpawnerPos;
    // Festung in der Oberwelt
    public BlockPos endRoomPos;   // Mitte des Portalraums
    public BlockPos endPortalPos; // aktiviertes Endportal
    public int crystalRounds;     // Runden "Kristalle abschießen" im End (Laufzeit)
    /** Herde halten: bei der Jagd immer ein Zuchtpaar je Art stehen lassen (nach dem ersten Zuchtauftrag). */
    public boolean herdKeeping;
    /** Türen/Zauntore, die er selbst geöffnet hat (untere Hälfte -> Tick), um sie hinter sich zu schließen. */
    public final Map<BlockPos, Integer> openedDoors = new HashMap<>();
    /** Kampfmodus: aggressiv (Standard), defensiv (nur verteidigen), passiv (nicht kämpfen). */
    public CompanionCombat.Stance stance = CompanionCombat.Stance.AGGRESSIVE;
    /** Zwischenspeicher für teure Umgebungs-Scans der Pflege-Bedürfnisse (Tick, reife Pflanzen am Feld). */
    public int careScanTick = -100_000, careMatureCrops;
    // Laufender Bauauftrag (Laufzeit)
    public CompanionBuilder.BuildJob build;
    /** Rohstoff, den der eigene Vorratsplan gerade auffüllt. */
    public String activeSupplyResource;
    /** Erster Beschaffungsweg des laufenden Vorratsplans. */
    public String activeSupplyApproach;
    /** Aktuelles Ziel des Planers (null = Spieler-Auftrag oder nichts), mit Dringlichkeit und Begründung. */
    public CompanionNeeds.Need goal;
    public int goalScore;
    public String goalReason = "";
    /** Herstellen schlug fehl: Item-ID -> bis zu diesem Tick nicht wieder als eigenes Ziel versuchen. */
    public final Map<String, Integer> craftFailedUntil = new HashMap<>();
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
    public String lastCraftProblem = "";
    public String problemText = "";
    public int problemTick = -100000;
    public String lastUiAction = "";
    /** Bis zu diesem Tick steuert die neue Wegfindung (dev.begleiter.nav) Bewegung und Sprint. */
    public int navControlUntil = -1;
    public BlockPos nearestLava;
    public BlockPos nearestWater;
    // Bergbau: Richtung von Treppe und Stollen
    public Direction mineDir;
    public int mineTripFails;
    public boolean doorClosed;
    /** Leere Nahrungssuchen hintereinander im freien Spiel (Laufzeit); ab 2 zieht er weiträumig weg. */
    public int hungerSearches;
    /** Wegschritte, die gescheitert sind (Ziel-Block -> bis wann meiden); der Wegplaner nimmt sie solange nicht. */
    public final Map<BlockPos, Integer> badSteps = new HashMap<>();
    /** Bis dahin nicht wieder zum Einlagern (Lager war voll und ließ sich nicht erweitern). */
    public int storageFullUntil;
    /**
     * Fester Bau-Modus (true = Kreativ, false = Survival); null = wie der Spielmodus des Besitzers.
     * Im Menü umschaltbar ({@link CompanionMenu#BUILD_MODE_TOGGLE}) und gespeichert als "BuildMode".
     */
    public Boolean buildModeOverride;
    /** Fest gewählte Bau-Bewegung; null = wie bisher (Kreativ fliegt, Survival läuft). Gespeichert als "BuildMovement". */
    public BuildMovement buildMovementOverride;
    /** Pro Begleiter gewählte Gesprächigkeit ("sei leiser"/"sprich mehr"), gespeichert; null = Serverkonfiguration. */
    public dev.begleiter.config.BegleiterConfig.Talk talkOverride;
    /** Nummer der jüngsten Schematic-Ladeanfrage (nicht gespeichert); ältere Ergebnisse verfallen. */
    public int schematicRequest;
    private boolean taggingRecoveryDrops;

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

    static String loadedName(String saved) {
        return saved == null || saved.isBlank() || saved.equals(OLD_DEFAULT_NAME) ? DEFAULT_NAME : saved;
    }

    // ------------------------------------------------------------------ Besitzer und Modus (Baustein CompanionMode)

    public void setOwner(UUID uuid) { mode.owner = uuid; }
    public UUID getOwnerUuid() { return mode.owner; }
    public boolean isOwner(Player p) { return p.getUUID().equals(mode.owner); }
    public Player getOwner() { return mode.owner == null ? null : level().getPlayerByUUID(mode.owner); }
    /** Besitzer auch in einer anderen Dimension. */
    public ServerPlayer getOwnerAnywhere() {
        if (mode.owner == null || !(level() instanceof ServerLevel sl)) return null;
        return sl.getServer().getPlayerList().getPlayer(mode.owner);
    }
    public SimpleContainer getInventory() { return inventory; }
    public boolean inOverworld() { return level().dimension() == Level.OVERWORLD; }
    /** Lager, Haus und Portal liegen in der Oberwelt – in anderen Dimensionen gibt es sie nicht. */
    public BlockPos getHome() { return mode.home(); }
    /** Gespeicherte Lagerkiste unabhängig von der Dimension. */
    BlockPos savedHome() { return mode.home; }
    public void setHome(BlockPos pos) { mode.home = pos.immutable(); }
    public void clearHome() { mode.home = null; mode.keeper = false; }
    public BlockPos getPortalPos() { return mode.portal(); }
    public void setPortalPos(BlockPos p) { mode.portal = p == null ? null : p.immutable(); }
    boolean hasPortal() { return mode.portal != null; }
    public BlockPos mine() { return mode.mine(); }
    public void setMine(BlockPos p) { mode.setMine(p); }
    public boolean isStaying() { return mode.staying; }
    public boolean isKeeper() { return mode.keeper; }
    public boolean isAutonomous() { return mode.autonomous; }
    /** Pausiert (Spieler oder Debug-Menü); ein Debug-Einzelschritt hebt die Pause für seine Ticks auf. */
    public boolean isPaused() { return mode.isPaused(); }
    /** Vom Spieler oder einem Auftrag angehalten (ohne Debug-Einfrieren). */
    boolean isPausedByOwner() { return mode.paused; }
    void setPaused(boolean paused) { mode.paused = paused; }
    public boolean isDebugFrozen() { return mode.isDebugFrozen(); }
    public void setDebugFrozen(boolean frozen) { mode.setDebugFrozen(frozen); }
    public void debugStep(int ticks) { mode.debugStep(ticks); }
    /** Sekunden, die die aktuelle Aufgabe schon läuft. */
    public int actionSeconds() { return mode.actionSeconds(); }
    public void follow() { mode.follow(); }
    public void freeRoam() { mode.freeRoam(); }
    public void stay() { mode.stay(); }
    public boolean makeKeeper() { return mode.makeKeeper(); }
    public CompanionDuties.Duty duty() { return mode.duty(); }
    boolean isManualDuty() { return mode.duty != null; }
    void enterWorkMode() { mode.enterWorkMode(); }
    public BlockPos dutyPos() { return mode.dutyPos(); }
    /** Daueraufgabe übernehmen: bleibt an {@code pos} und arbeitet dort, sobald er frei ist. */
    public void startDuty(CompanionDuties.Duty d, BlockPos pos) { mode.startDuty(d, pos); }
    public String modeText() { return mode.text(); }
    /** Interne Unterbrechung: Warteschlange leeren, ein offener Bau bleibt für die spätere Wiederaufnahme erhalten. */
    public void cancelAll() { mode.cancelAll(); }
    /** Ausdrücklicher Abbruch durch den Spieler: Warteschlange leeren und einen offenen Bau verwerfen. */
    public void cancelOrders() { mode.cancelOrders(); }
    /** Moduswechsel durch den Spieler: Warteschlange leeren, offenen Bau anhalten; liefert einen Hinweis oder leeren Text. */
    public Component holdOrdersComponent() { return mode.holdOrders(); }
    public void resume() { mode.resume(); }
    public void pause(String reason) { pause(CompanionTexts.literal(reason)); }
    public void pause(Component reason) { pauseWork(CompanionTexts.tr("handler.pause", reason)); }
    public Component pausedReason() { return mode.pausedReason.copy(); }
    void holdRecipeOrders(Component reason) { mode.holdRecipeOrders(reason); }
    public void pauseWork(String reason) { pauseWork(CompanionTexts.literal(reason)); }
    public void pauseWork(Component reason) { mode.pauseWork(reason); }
    public boolean isHouseBuilt() { return house.built(); }
    /** Haus komplett vergessen (Testgelände: jedes Haus-Szenario beginnt ohne Haus). */
    public void forgetHouse() { house.forget(); doorClosed = false; }
    public BlockPos getHouseOrigin() { return house.origin(); }
    public int houseAnnexes() { return house.annexes(); }
    /** Mitte des Hauses (Fußhöhe). */
    public BlockPos houseInside() { return house.inside(); }
    /** Steht er im Innenraum des Hauses samt Anbauten? */
    public boolean isInsideHouse() { return house.contains(blockPosition()); }
    /** Haustür fehlt (Öffnung frei, keine Tür, nicht für die Nacht zugemauert)? */
    public boolean houseNeedsDoor() { return house.needsDoor(); }
    /** Die zwei Blöcke der Türöffnung. */
    public List<BlockPos> houseDoor() { return house.door(); }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) { return mode.interact(player, hand); }

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

    // ------------------------------------------------------------------ Warteschlange (Aufträge: Baustein CompanionOrders)

    /** Die Warteschlange selbst, nur für die Bausteine; alle anderen nehmen die Methoden unten. */
    ArrayDeque<Task> queue() { return tasks; }
    public Task currentTask() { return tasks.peekFirst(); }

    /**
     * Netherportal nur absichtlich: als Ziel eines Laufauftrags (Netherausflug, Rückweg, dem Besitzer nach). Beobachtet:
     * auf der Flucht vor einem Creeper durchs eigene Portal neben dem Haus gestolpert und im Nether gelandet.
     */
    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        if (!super.canUsePortal(allowPassengers)) return false;
        if (level().isClientSide() || !touchingNetherPortal()) return true;
        Task t = currentTask();
        return t != null && t.spec() instanceof TaskSpec.MoveTo m
                && (m.portal() != null || level().getBlockState(m.pos()).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL));
    }

    private boolean touchingNetherPortal() {
        var box = getBoundingBox().deflate(1.0E-3);
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
            if (level().getBlockState(p).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) return true;
        return false;
    }
    /** Die nächsten n Aufgaben (Kopie; für das Debug-Overlay). */
    public List<Task> peekTasks(int n) { return tasks.stream().limit(n).toList(); }
    public void finishTask() { tasks.pollFirst(); }
    public void pushFront(Task t) { tasks.addFirst(t); }
    public void addTask(Task t) { tasks.addLast(t); }
    public boolean hasTasks() { return !tasks.isEmpty(); }
    public int taskCount() { return tasks.size(); }
    /** Ersetzt die aktuelle Aufgabe durch eine Liste (in Reihenfolge). */
    public void replaceCurrent(List<Task> list) {
        tasks.pollFirst();
        for (int i = list.size() - 1; i >= 0; i--) tasks.addFirst(list.get(i));
    }
    public boolean hasPlaceTaskFor(Item item) {
        for (Task t : tasks) if (t.type() == TaskType.PLACE && t.block() != null && t.block().asItem() == item) return true;
        return false;
    }
    public String currentTaskText() {
        Task t = tasks.peekFirst();
        return t == null ? (mode.paused ? "paused" : "nothing") : describe(t);
    }
    public static String describe(Task t) { return CompanionLog.describe(t); }

    void clearManagedTasks() {
        tasks.clear();
        releaseFetch();
        getNavigation().stop();
        nav.stop();
    }

    void clearJourneyTasks() {
        tasks.clear();
        if (huntTarget != null && getTarget() == huntTarget) setTarget(null);
        huntTarget = null;
        getNavigation().stop();
        nav.stop();
    }

    public void queueBuild() {
        if (!work.enabled() || !work.manages(CompanionWork.Kind.BUILD)) addTask(Task.build());
    }

    public void releaseFetch() {
        if (fetchTarget != null && fetchLeashed && fetchTarget instanceof net.minecraft.world.entity.Leashable l && l.getLeashHolder() == this) {
            l.removeLeash();
        }
        fetchTarget = null;
        fetchLeashed = false;
    }

    /** Läuft ein Rezept-Auftrag? Seine gespeicherten Zwischenstände stehen hinter allen Vorbereitungsaufgaben. */
    public boolean hasRecipeRequest() { return orders.hasRecipeRequest(); }
    public boolean scopedStockWork() { return orders.scopedStockWork(); }
    public boolean recoverRecipeProduction(Component reason) { return orders.recoverRecipeProduction(reason); }
    public void pauseRecipeRequest(Component reason) { orders.pauseRecipeRequest(reason); }
    /** Für einen offenen Rezept-Auftrag vorgesehene Items nicht von selbst anlegen, wegwerfen oder verbrauchen. */
    public boolean reservedForRecipe(ItemStack stack) { return orders.reservedForRecipe(stack); }

    /** Fehlschläge nur aus eigenen Plänen merken; Befehle des Besitzers bringen ihm nie ein schlechtes Ergebnis bei. */
    public void reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason reason, Task failedTask) {
        planFeedback.reportAutonomousFailure(this, reason, failedTask);
    }
    public boolean craftBlocked(String id) {
        return tickCount < craftFailedUntil.getOrDefault(ItemIds.canonicalOrOriginal(id), 0);
    }

    // ------------------------------------------------------------------ Chat und Protokoll (Bausteine CompanionLog, CompanionTalk)

    public void tell(String msg) { tell(CompanionTexts.literal(msg)); }
    public void tell(Component msg) {
        ServerPlayer owner = getOwnerAnywhere();
        if (owner != null && msg != null) owner.sendSystemMessage(CompanionTexts.prefixed(msg));
    }
    public void logInfo(String msg) { logInfo(BotDebug.eventKind(msg), CompanionTexts.literal(msg)); }
    public void logWarn(String msg) { logWarn(BotDebug.eventKind(msg), CompanionTexts.literal(msg)); }
    public void logError(String msg) { logError(BotDebug.eventKind(msg), CompanionTexts.literal(msg)); }
    public void logInfo(Component msg) { logInfo(EventKind.NONE, msg); }
    public void logWarn(Component msg) { logWarn(EventKind.NONE, msg); }
    public void logError(Component msg) { logError(EventKind.NONE, msg); }
    public void logInfo(EventKind kind, Component msg) { log.info(kind, msg); }
    public void logWarn(EventKind kind, Component msg) { log.warn(kind, msg); }
    public void logError(EventKind kind, Component msg) { log.error(kind, msg); }
    public void consoleDebug(String msg) { CompanionLog.console('I', "[Begleiter-Debug]", Component.literal(msg)); }
    public void clearLog() { log.clear(); }
    public int logSize() { return log.size(); }
    /** Neueste zuerst. errorsOnly: nur Warnungen und Fehler. */
    public List<CompanionLog.Entry> getLog(int offset, int count, boolean errorsOnly) { return log.entries(offset, count, errorsOnly); }
    public static Component formatLogText(CompanionLog.Entry e) { return CompanionLog.format(e); }
    /** Problem melden: ins Log UND (nicht zu oft) in den Chat. */
    public void problem(String msg) { problem(BotDebug.eventKind(msg), CompanionTexts.literal(msg)); }
    public void problem(Component msg) { problem(EventKind.NONE, msg); }
    public void problem(EventKind kind, Component msg) { log.problem(kind, msg); }
    public Component problemComponent() { return log.problemComponent(); }
    Component idleDiagnosticComponent() { return log.idleDiagnosticComponent(); }
    public void setUiFeedback(Component message) { log.setUiFeedback(message); }
    public Component uiFeedbackComponent() { return log.uiFeedbackComponent(); }
    public void setGoalReason(Component reason) { log.setGoalReason(reason); }
    public Component goalReasonComponent() { return log.goalReasonComponent(); }
    public void setStage(Component stage) { log.setStage(stage); }
    public Component stageComponent() { return log.stageComponent(); }
    public void setCraftProblem(Component reason) { log.setCraftProblem(reason); }
    public Component craftProblemComponent() { return log.craftProblemComponent(); }
    /** Planer-Prüfungen vergleichen den Schlüssel der Stufe, nicht den übersetzten Text. */
    public boolean isStage(String key) { return log.isStage(key); }
    public dev.begleiter.config.BegleiterConfig.Talk talk() { return talk.level(); }
    public boolean mayChatter(Component msg) { return talk.mayChatter(msg); }
    public boolean mayReportProgress() { return talk.mayReportProgress(); }

    // ------------------------------------------------------------------ Inventar, Ausrüstung, Kisten (Bausteine CompanionInventory, CompanionEquipment, CompanionStorage)

    public static boolean isJunk(Item item) { return item.builtInRegistryHolder().is(JUNK); }
    public static boolean isTrash(Item item) { return isJunk(item) || item.builtInRegistryHolder().is(TRASH); }
    /** Erze und ihre Ausbeute, Edelsteine, Barren: dafür wirft er notfalls auch seinen Gerüst-Vorrat weg. */
    public static boolean isPrecious(Item item) { return item.builtInRegistryHolder().is(PRECIOUS); }

    public boolean hasFreeSlot() {
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).isEmpty()) return true;
        return false;
    }

    public int usedSlots() {
        int used = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) if (!inventory.getItem(i).isEmpty()) used++;
        return used;
    }

    public int totalItems() {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) n += inventory.getItem(i).getCount();
        return n;
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

    public ItemStack giveToInventory(ItemStack stack) { return inventory.addItem(stack); }
    /** Belegte Plätze ohne das, was er ohnehin wegwerfen würde – dafür lohnt kein Weg zum Lager. */
    public int usedSlotsWithoutClutter() { return items.usedSlotsWithoutClutter(); }
    public int depositableSlots() { return items.depositableSlots(); }
    public int depositInto(Container target, boolean keepQuota) { return items.depositInto(target, keepQuota); }
    /** Wirft Items vor die Füße des Besitzers; key == null: alles außer Ausrüstung/Nahrung. */
    public void giveTo(Player owner, String key) { items.giveTo(owner, key, Integer.MAX_VALUE); }
    public int giveTo(Player owner, String key, int max) { return items.giveTo(owner, key, max); }
    public void equipToolFor(BlockState state) { gear.equipToolFor(state); }
    public boolean hasCorrectToolFor(BlockState s) { return gear.hasCorrectToolFor(s); }
    public int breakTicks(ServerLevel sl, BlockPos p, BlockState s) { return gear.breakTicks(sl, p, s); }

    public void swingArm() {
        Object a = CompanionEquipment.swingAnimation();
        if (a instanceof net.minecraft.world.item.component.SwingAnimation sa) swing(InteractionHand.MAIN_HAND, sa, false);
    }

    public Container containerAt(ServerLevel sl, BlockPos pos) { return storage.containerAt(sl, pos); }
    /** Nächste Kiste/Fass (Lager zuerst) im Umkreis, die dieses Item enthält. */
    public BlockPos findContainerWith(Item item, int r) { return storage.findContainerWith(item, r, p -> true); }
    public BlockPos findContainerWith(Item item, int r, Predicate<BlockPos> allowed) { return storage.findContainerWith(item, r, allowed); }
    public BlockPos findContainerWith(Item item, List<BlockPos> candidates, Predicate<BlockPos> allowed) {
        return storage.findContainerWith(item, candidates, allowed);
    }
    public List<BlockPos> storageBoxes(ServerLevel sl, BlockPos center) { return storage.storageBoxes(sl, center); }
    public Container orderStorage(ServerLevel world, BlockPos pos) { return storage.orderStorage(world, pos); }
    public Container workStorage(ServerLevel world, BlockPos pos) { return storage.workStorage(world, pos); }
    public int stockReserveAt(ServerLevel world, BlockPos pos, Item item) { return storage.stockReserveAt(world, pos, item); }
    public boolean stockRawBlock(ServerLevel world, BlockPos pos, BlockState state) { return CompanionStockRoutine.rawBlock(world, pos, state, this); }
    public void useContainer(ServerLevel sl, BlockPos pos) { storage.useContainer(sl, pos); }
    public void closeContainer() { storage.closeContainer(); }

    @Override
    public boolean hasContainerOpen(net.minecraft.world.level.block.entity.ContainerOpenersCounter counter, BlockPos pos) {
        return storage.isOpen(pos);
    }

    @Override
    public double getContainerInteractionRange() { return 6.0; }

    // ------------------------------------------------------------------ Stationen, Sammeln, Erkunden (Bausteine CompanionStations, CompanionGathering, CompanionExplore)

    public void rememberStation(String id, BlockPos pos) { stations.remember(id, pos); }
    public void markStationUnreachable(BlockPos p) { stations.markUnreachable(p); }
    /** Hat er die Station dabei oder kann sie sofort herstellen (Werkbank aus Holz, Ofen aus 8 Stein)? */
    public boolean canCarryStation(String id) { return stations.canCarry(id); }
    /** Sucht eine Werkbank/einen Ofen/eine Kiste in der Nähe (echte Blöcke in der Welt). */
    public BlockPos findStation(String id, int r) { return stations.find(List.of(id), r, pos -> true); }
    public BlockPos findStation(List<String> ids, int r) { return stations.find(ids, r, pos -> true); }
    public BlockPos findOrderStation(String id, int radius) { return stations.findOrderStation(id, radius); }
    BlockPos findLocalStockStation(String id) { return stations.findLocalStockStation(id); }
    /** Baut eine Liste von BREAK-Aufgaben für Blöcke, die er wirklich sehen kann (siehe {@link CompanionGathering}). */
    public List<Task> buildGather(Predicate<BlockState> pred, int count) { return buildGather(pred, count, false); }
    /** surface: Holz/Sand/Erde - hier ist Wasser in der Nähe okay, Lava nie. */
    public List<Task> buildGather(Predicate<BlockState> pred, int count, boolean surface) { return buildGather(pred, count, surface, false); }
    /** allowDive: auch Blöcke tief unter Wasser (Notfall – langsam, Ertrinkungsgefahr). */
    public List<Task> buildGather(Predicate<BlockState> pred, int count, boolean surface, boolean allowDive) {
        return gathering.gather(pred, count, surface, allowDive);
    }
    boolean gatherMissed(ServerLevel sl, BlockPos pos) { return gathering.missed(sl, pos); }
    void noteGatherMiss(ServerLevel sl, BlockPos pos) { gathering.noteMiss(sl, pos); }
    /** Sieht er diesen Block (freie Seite in Sichtlinie)? */
    public boolean seesBlock(ServerLevel sl, BlockPos p) { return gathering.canSee(sl, p); }
    public Task explorePoint() { return explore.point(); }
    /** Weiter Schritt in fester Richtung, um eine karge Gegend zu verlassen (siehe {@link CompanionExplore#far()}). */
    public Task relocatePoint() { return explore.far(); }
    /** Reichweite des Erz-Gespürs für Diamanten: wächst, je länger er erfolglos sucht (2/4/6 min -> 48/64/96). */
    public int diamondSenseRadius() { return gathering.senseRadius(tickCount); }
    public boolean hasFoodAnimalNearby() { return nearestFoodAnimal() != null; }
    public Mob nearestFoodAnimal() { return food.nearestAnimal(); }

    // ------------------------------------------------------------------ Gerüst (Baustein CompanionScaffold)

    /**
     * Wofür er hochbaut. Nur TREE wird als Gerüst gemerkt und später wieder abgebaut.
     * TREE = hässliche Säule, um an einen Baum oder ein hohes Ziel zu kommen;
     * PATH = normales Hochbauen (Weg, aus Schacht/Loch raus) – bleibt stehen.
     * Neue Aufrufer im Zweifel PATH: lieber ein Block zu viel stehen lassen als später ziellos zurücklaufen.
     */
    public enum Pillar { PATH, TREE }

    /** Der Müll-Block, von dem er am meisten dabei hat (siehe {@link CompanionScaffold#item()}). */
    public Item scaffoldItem() { return scaffolding.item(); }
    public Item scaffoldItem(Pillar kind) { return scaffolding.item(kind); }
    /** Wie viele Müll-Blöcke insgesamt (alle Sorten zusammen). */
    public int scaffoldCount() { return scaffolding.count(); }
    public boolean hasScaffold() { return scaffolding.any(); }
    public boolean isPillarActive() { return scaffolding.active(); }
    public void pillarReset() { scaffolding.reset(); }
    public void pruneScaffold(ServerLevel sl) { scaffolding.prune(sl); }
    /** Ein Hochbau-Schritt, siehe {@link CompanionScaffold#step}: 1 gesetzt, 0 läuft, 2 Hindernis, -1 unmöglich. */
    public int pillarStep(ServerLevel sl, Pillar kind) { return scaffolding.step(sl, kind); }

    // ------------------------------------------------------------------ Bauen: Material-Modus und Bewegung (Baustein CompanionFlight)

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

    public BuildMovement buildMovement() {
        if (buildMovementOverride != null) return buildMovementOverride;
        return creativeHelper() ? BuildMovement.FLY : BuildMovement.NORMAL;
    }
    public void keepCreativeFlight() { flight.keepCreativeFlight(); }
    public void keepGhost() { flight.keepGhost(); }

    // ------------------------------------------------------------------ Weltänderungen (Baustein CompanionWorldEdit)

    /** Beute und Verschleiß nur nach bestätigtem Entfernen genau des geprüften Blocks buchen. */
    public boolean mineBlock(ServerLevel sl, BlockPos p, BlockState s) { return edits.mineBlock(sl, p, s); }
    /** Weltänderung ausführen; eine Ausnahme der Welt (Schutz-Mod, Weltfehler) gilt als abgelehnt und wird protokolliert. */
    public boolean worldSet(ServerLevel sl, BlockPos p, BlockState st, int flags) { return edits.set(sl, p, st, flags); }
    /** Abbauen ohne Beute-Buchung, nur mit Erlaubnis. {@code drop}: Block lässt wie beim Spieler seine Beute fallen. */
    public boolean worldBreak(ServerLevel sl, BlockPos p, boolean drop) { return edits.breakBlock(sl, p, drop); }
    /**
     * Block/Flüssigkeit setzen und bestätigen: true nur, wenn danach wirklich dieser Block dort steht. Erst danach
     * Material abbuchen oder Fortschritt zählen (sonst Duplikat bzw. verschwundenes Material).
     */
    public boolean placeConfirmed(ServerLevel sl, BlockPos p, BlockState st) {
        return worldSet(sl, p, st, 3) && sl.getBlockState(p).is(st.getBlock());
    }
    /** Türen und Zauntore bedienen: wie ein Spieler, also nur, wo der Besitzer es dürfte. */
    public boolean mayUse(ServerLevel sl, BlockPos p) { return dev.begleiter.world.BuildPermission.mayInteract(this, sl, p); }

    /** Kleine Welt-API-Grenze; Tests können Ablehnung/Fehler ohne Eingriff in Vanilla simulieren. */
    protected boolean destroyMiningBlock(ServerLevel sl, BlockPos p) {
        return sl.destroyBlock(p, false, this);
    }

    /** Gegenstück fürs Setzen (Blöcke, Wasser, Luft beim Eimer-Füllen); Tests simulieren damit Ablehnung. */
    protected boolean setWorldBlock(ServerLevel sl, BlockPos p, BlockState st, int flags) {
        return sl.setBlock(p, st, flags);
    }

    public void noteOpenedDoor(BlockPos p) {
        if (level() instanceof ServerLevel sl) openedDoors.putIfAbsent(CompanionDoors.lower(sl, p).immutable(), tickCount);
    }

    public boolean isBadStep(BlockPos p) {
        Integer until = badSteps.get(p);
        if (until == null) return false;
        if (tickCount < until) return true;
        badSteps.remove(p);
        return false;
    }

    // ------------------------------------------------------------------ Tick (Baustein CompanionTick)

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) storage.tickOpened(); // auch ohne KI/pausiert schließen
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        ticker.serverStep(level);
    }

    public boolean needsLoadedChunks() {
        return isAlive() && !isPaused() && (hasTasks() || mode.duty != null || journey.active());
    }

    @Override
    public void onRemoval(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (reason.shouldDestroy() && level() instanceof ServerLevel sl)
            dev.begleiter.world.CompanionChunkLoader.forget(chunkLoader, sl, getUUID()); // tot/entfernt: nicht mehr wecken
        else dev.begleiter.world.CompanionChunkLoader.release(chunkLoader); // nur entladen: Weckeintrag bleibt
        closeContainer();
        super.onRemoval(reason);
    }

    // ------------------------------------------------------------------ Überleben und Kampf (Bausteine CompanionFighting, CompanionHazards, CompanionShelter, CompanionFood)

    public boolean isRetreating() { return fighting.retreating(); }
    /** Dieses Wesen eine Weile nicht als Ziel nehmen (z.B. Rückweg nicht nachweisbar). */
    public void ignoreTarget(LivingEntity t, int ticks) { fighting.ignore(t, ticks); }
    public boolean isIgnoredTarget(LivingEntity t) { return fighting.ignored(t); }
    public boolean hasBowAndArrows() { return fighting.hasBowAndArrows(); }
    public boolean canShoot() { return fighting.canShoot(); }
    /** Einen Pfeil auf ein Ziel schießen (auch auf Endkristalle, die keine Mobs sind). */
    public boolean shootArrow(net.minecraft.world.entity.Entity target) { return fighting.shoot(target); }
    /** In Gefahr: zieht sich gerade zurück oder wurde vor Kurzem getroffen. */
    public boolean inDanger() { return fighting.inDanger(); }
    void stopRetreat() { fighting.stopRetreat(); }
    /** Sprungkraft wie beim Absprung (Sprungstärke, Block-Faktor, Sprungkraft-Effekt) für die Sprung-Simulation. */
    public double navJumpPower() { return getJumpPower(); }
    public boolean isSheltered() { return shelter.active(); }
    /** Wie ein Spieler in der Not einmauern, siehe {@link CompanionShelter#sealIn}. */
    public boolean sealIn(ServerLevel sl) { return shelter.sealIn(sl); }
    /** Nachtlager ohne Haus, siehe {@link CompanionShelter#sealInUntilMorning}. */
    public boolean sealInUntilMorning(ServerLevel sl) { return shelter.sealInUntilMorning(sl); }
    /** Fehlen am Haus Blöcke (Explosion, abgebaut)? Dann schützt es nicht mehr. */
    public boolean houseDamaged(ServerLevel sl) { return house.damaged(sl); }
    public boolean craftTorches() { return torches.craft(); }
    /** Goldener Apfel für den Notfall (siehe {@link CompanionFood#eatGoldenApple()}). */
    public boolean eatGoldenApple() { return food.eatGoldenApple(); }
    /** Vor einem langen Weg essen, mit den üblichen Regeln und Auftragsreservierungen. */
    public void eatBeforeTrip() { food.eatBeforeTrip(); }
    /** Lava (immer) oder Wasser (wenn nicht erlaubt) direkt neben dem Block? */
    public boolean hazardAround(ServerLevel sl, BlockPos p, boolean allowWater) { return CompanionHazards.around(sl, p, allowWater); }

    /** Im Kreativ-Modus greift er von selbst keine Mobs an; nur ausdrückliche Kampfaufträge setzen ein Ziel. */
    @Override
    public void setTarget(LivingEntity target) {
        if (target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon && journey.fightingDragon()) return;
        if (target != null && creativeHelper() && !fighting.orderedFight()) return;
        super.setTarget(target);
    }

    /** Schwerer Treffer durch die Umgebung (Explosion, Lava, Sturz, Ersticken ...): Stelle als gefährlich merken. */
    public void rememberDanger(DamageSource source, float amount) {
        if (!(level() instanceof ServerLevel sl) || amount < 4 || isDeadOrDying()) return;
        if (source.getEntity() != null && !source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) return;
        // Ein Creeper am/im Haus macht das Zuhause nicht dauerhaft gefährlich (sonst sperrt die Wegfindung es)
        BlockPos inside = houseInside();
        if (inside != null && inside.distSqr(blockPosition()) <= 6 * 6 && source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) return;
        memory.remember(CompanionMemory.Kind.DANGER, source.getMsgId(), sl, blockPosition());
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

    /** In der Festung darf der Wegplaner auch Steinziegel abbauen. */
    public boolean inStronghold() {
        return endRoomPos != null && endPortalPos == null && blockPosition().distSqr(endRoomPos) < 96 * 96;
    }

    // ------------------------------------------------------------------ Tod, Seele und Kern (Baustein CompanionSoul)

    public UUID soulId() { return soul.id(); }
    public void setSoulId(UUID id) { soul.setId(id); }
    /** Was er nach dem Tod noch weiß: Lager, Haus, Modus, Name. */
    public CompoundTag saveMemory() { return soul.saveMemory(); }
    public void loadMemory(CompoundTag t) { soul.loadMemory(t); }

    @Override
    public ItemEntity spawnAtLocation(ServerLevel level, ItemStack stack) {
        var drop = super.spawnAtLocation(level, stack);
        if (drop != null && taggingRecoveryDrops) drop.addTag(journey.recoveryTag());
        return drop;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        journey.rememberDeath(level, blockPosition());
        taggingRecoveryDrops = journey.active();
        try {
            super.dropCustomDeathLoot(level, source, recentlyHit);
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack s = inventory.getItem(i);
                if (!s.isEmpty()) spawnAtLocation(level, s.copy());
                inventory.setItem(i, ItemStack.EMPTY);
            }
        } finally {
            taggingRecoveryDrops = false;
        }
        soul.afterDeathLoot(level, source);
    }

    // ------------------------------------------------------------------ Speichern

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("BegleiterDataV", DATA_VERSION);
        mode.save(out);
        out.putBoolean("HerdKeeping", herdKeeping);
        out.putBoolean("DoorClosed", doorClosed);
        food.save(out);
        out.putString("Stance", stance.name());
        out.store("WorkZones", CompanionZones.Zone.CODEC.listOf(), zones.all());
        house.save(out);
        out.putBoolean("NetherTrip", netherTrip);
        if (buildModeOverride != null) out.putString("BuildMode", buildModeOverride ? "creative" : "survival");
        if (buildMovementOverride != null) out.putString("BuildMovement", buildMovementOverride.name());
        if (talkOverride != null) out.putString("Talk", talkOverride.name());
        if (soul.savedId() != null) out.store("Soul", net.minecraft.core.UUIDUtil.CODEC, soul.savedId());
        if (netherPortal != null) out.store("NetherPortal", BlockPos.CODEC, netherPortal);
        if (fortressPos != null) out.store("Fortress", BlockPos.CODEC, fortressPos);
        if (blazeSpawnerPos != null) out.store("BlazeSpawner", BlockPos.CODEC, blazeSpawnerPos);
        if (endRoomPos != null) out.store("EndRoom", BlockPos.CODEC, endRoomPos);
        if (endPortalPos != null) out.store("EndPortal", BlockPos.CODEC, endPortalPos);
        inventory.storeAsItemList(out.list("CompanionInventory", ItemStack.CODEC));
        if (build != null) CompanionBuilder.save(out, build);
        out.store("Memory", CompoundTag.CODEC, memory.save());
        out.store("Routes", CompoundTag.CODEC, routes.save());
        out.store("ExperienceV1", CompoundTag.CODEC, experiences.save(tickCount));
        out.store("RequestDialogueV1", CompoundTag.CODEC, requestDialogue.save(level().getGameTime()));
        var workData = work.save(level().getGameTime());
        if (!workData.isEmpty()) out.store("WorkPlanV1", CompoundTag.CODEC, workData);
        var journeyData = journey.save(level().getGameTime());
        if (!journeyData.isEmpty()) out.store("JourneyV1", CompoundTag.CODEC, journeyData);
        if (!stockRules.isEmpty()) out.store("StockRulesV2", CompanionStockRoutine.RULES_CODEC, stockRules);
        if (woodZoneOwner != null) out.store("WoodZoneOwner", net.minecraft.core.UUIDUtil.CODEC, woodZoneOwner);
        if (projekt.vorhanden()) out.store("ProjectV1", CompoundTag.CODEC, projekt.speichern());
        scaffolding.save(out);
        orders.save(out);
        var initiativeData = initiative.save();
        if (initiativeData.size() > 1) out.store("InitiativeV1", CompoundTag.CODEC, initiativeData);
        if (fuelLeft > 0 && Double.isFinite(fuelLeft)) out.store("FuelLeft", com.mojang.serialization.Codec.DOUBLE, fuelLeft);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        guaranteeEquipmentDrops(); // alte Welten haben 1.0 gespeichert
        // Minecraft liest CustomName vorher; Welten mit dem alten Standardnamen bekommen den neuen.
        if (getCustomName() != null && getCustomName().getString().equals(OLD_DEFAULT_NAME))
            setCustomName(Component.literal(DEFAULT_NAME));
        mode.load(in);
        herdKeeping = in.getBooleanOr("HerdKeeping", false);
        doorClosed = in.getBooleanOr("DoorClosed", false);
        food.load(in);
        stance = CompanionCombat.Stance.byName(in.getStringOr("Stance", "AGGRESSIVE"));
        zones.load(in.read("WorkZones", CompanionZones.Zone.CODEC.listOf()).orElse(List.of()));
        house.load(in);
        netherTrip = in.getBooleanOr("NetherTrip", false);
        soul.setId(in.read("Soul", net.minecraft.core.UUIDUtil.CODEC).orElse(null));
        talkOverride = null;
        for (var level : dev.begleiter.config.BegleiterConfig.Talk.values())
            if (level.name().equals(in.getStringOr("Talk", ""))) talkOverride = level;
        buildModeOverride = switch (in.getStringOr("BuildMode", "")) {
            case "creative" -> Boolean.TRUE;
            case "survival" -> Boolean.FALSE;
            default -> null;
        };
        buildMovementOverride = BuildMovement.byName(in.getStringOr("BuildMovement", ""));
        netherPortal = in.read("NetherPortal", BlockPos.CODEC).orElse(null);
        fortressPos = in.read("Fortress", BlockPos.CODEC).orElse(null);
        blazeSpawnerPos = in.read("BlazeSpawner", BlockPos.CODEC).orElse(null);
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
        loadPart("WorkPlanV1", () -> work.load(in.read("WorkPlanV1", CompoundTag.CODEC).orElseGet(CompoundTag::new), level().getGameTime()));
        loadPart("JourneyV1", () -> journey.load(in.read("JourneyV1", CompoundTag.CODEC).orElseGet(CompoundTag::new), level().getGameTime()));
        stockRules.clear();
        woodZoneOwner = in.read("WoodZoneOwner", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        nextStockRule = 0;
        loadPart("StockRulesV2", () -> stockRules.addAll(in.read("StockRulesV2", CompanionStockRoutine.RULES_CODEC)
                .orElseGet(() -> in.read("StockRoutineV1", CompanionStockRoutine.CODEC).stream().toList())));
        loadPart("ProjectV1", () -> projekt.laden(in.read("ProjectV1", CompoundTag.CODEC).orElseGet(CompoundTag::new)));
        loadPart("Scaffold", () -> scaffolding.load(in));
        orders.load(in);
        loadPart("InitiativeV1", () -> initiative.load(in.read("InitiativeV1", CompoundTag.CODEC).orElseGet(CompoundTag::new)));
        fuelLeft = in.read("FuelLeft", com.mojang.serialization.Codec.DOUBLE)
                .filter(Double::isFinite).map(value -> Math.clamp(value, 0.0, 8.0)).orElse(0.0);
        orders.reconcileAfterLoad(in.getIntOr("BegleiterDataV", 0));
    }

    /** Einen optionalen Teil laden; bei einem Fehler bleibt er leer, der Begleiter selbst bleibt erhalten. */
    void loadPart(String part, Runnable load) {
        try {
            load.run();
        } catch (RuntimeException ex) {
            CompanionLog.LOG.error("Companion {}: saved part {} unreadable, discarded", getUUID(), part, ex);
            logWarn(CompanionTexts.tr("load.part_failed", CompanionTexts.literal(part)));
        }
    }
}
