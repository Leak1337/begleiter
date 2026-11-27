package dev.begleiter.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.ItemIds;
import dev.begleiter.RegistryIds;

/**
 * Typisierte Angaben einer Aufgabe: je Aufgabentyp ein Record mit benannten Feldern. Der Versuchszähler ist für
 * alle Typen gleich und bleibt deshalb an {@link dev.begleiter.CompanionEntity.Task}. Die Feldnamen sind zugleich die
 * Schlüssel im Speicherformat (TaskQueueCodec V2): Umbenennen nur mit Formatwechsel.
 */
public sealed interface TaskSpec {
    TaskType type();

    default BlockPos pos() { return null; }

    default Block block() { return null; }

    // ------------------------------------------------------------------ Grundaufgaben

    /** Zur Position laufen; reachSqr = Reichweite² (0 = 9). */
    record MoveTo(BlockPos pos, int reachSqr, boolean stockReturn, String portal) implements TaskSpec {
        public MoveTo(BlockPos pos, int reachSqr) { this(pos, reachSqr, false); }
        public MoveTo(BlockPos pos, int reachSqr, boolean stockReturn) { this(pos, reachSqr, stockReturn, null); }
        @Override public TaskType type() { return TaskType.MOVE_TO; }
    }

    /** Dem Besitzer geben: what = Resources-Key, "item:<id>" oder null (alles Überflüssige); max = höchstens (0 = alles). */
    record Give(String what, int max) implements TaskSpec {
        public Give {
            if (what != null && what.startsWith("item:")) what = "item:" + ItemIds.canonicalOrOriginal(what.substring(5));
        }
        @Override public TaskType type() { return TaskType.GIVE; }
    }

    /** Block setzen. */
    record Place(BlockPos pos, Block block) implements TaskSpec {
        @Override public TaskType type() { return TaskType.PLACE; }
    }

    /** Block abbauen; block == null: jeden nicht-leeren Block. */
    record Break(BlockPos pos, Block block, String stockItem) implements TaskSpec {
        public Break(BlockPos pos, Block block) { this(pos, block, null); }
        public boolean scoped() { return stockItem != null; }
        @Override public TaskType type() { return TaskType.BREAK; }
    }

    // ------------------------------------------------------------------ Lager

    /** Herumliegende Items aufheben; anyAge: alles im Umkreis 24 (auch alte), sonst nur frische im Umkreis 12. */
    record Collect(boolean anyAge) implements TaskSpec {
        @Override public TaskType type() { return TaskType.COLLECT; }
    }

    /** Inventar ins Lager bringen; keepStock: Vorrat/Quoten behalten. */
    record Deliver(boolean keepStock) implements TaskSpec {
        @Override public TaskType type() { return TaskType.DELIVER; }
    }

    /** Lagerkisten um pos nach Kategorien sortieren. */
    record Sort(BlockPos pos) implements TaskSpec {
        @Override public TaskType type() { return TaskType.SORT; }
    }

    /** Aus der Kiste an pos nehmen: item = Item-ID, count = Anzahl. */
    record Take(BlockPos pos, String item, int count) implements TaskSpec {
        public Take { item = ItemIds.canonicalOrOriginal(item); }
        @Override public TaskType type() { return TaskType.TAKE; }
    }

    // ------------------------------------------------------------------ Herstellung

    /** Recipe orders use phases 0–4. Home orders use 5 for planning and 6 for delivery. */
    record Request(String item, int count, boolean materials, int phase, int baseline, BlockPos excludedSource,
                   BlockPos pos, String fuel) implements TaskSpec {
        public Request(String item, int count, boolean materials, int phase, int baseline) {
            this(item, count, materials, phase, baseline, null);
        }
        public Request(String item, int count, boolean materials, int phase, int baseline, BlockPos excludedSource) {
            this(item, count, materials, phase, baseline, excludedSource, null);
        }
        public Request(String item, int count, boolean materials, int phase, int baseline, BlockPos excludedSource, BlockPos pos) {
            this(item, count, materials, phase, baseline, excludedSource, pos, null);
        }
        public Request {
            item = ItemIds.canonicalOrOriginal(item);
            excludedSource = excludedSource == null ? null : excludedSource.immutable();
            pos = pos == null ? null : pos.immutable();
        }
        public boolean homeOrder() { return phase == 5 || phase == 6; }
        @Override public TaskType type() { return TaskType.REQUEST; }
    }

    /** Herstellen: item = Item-ID oder Sonderrezept wie "#planks_from_logs", count = Anzahl (0 = einmal/alles). */
    record Craft(String item, int count) implements TaskSpec {
        public Craft { item = ItemIds.canonicalOrOriginal(item); }
        @Override public TaskType type() { return TaskType.CRAFT; }
    }

    /** Schmieden/Steinsägen: das gewählte Rezept bleibt über Wiederholungen und Speichern/Laden gleich. */
    record Process(String item, int count, String recipe) implements TaskSpec {
        public Process {
            item = ItemIds.canonicalOrOriginal(item);
            recipe = RegistryIds.canonicalOrOriginal(recipe);
        }
        @Override public TaskType type() { return TaskType.PROCESS; }
    }

    /** Schmelzen: item = gewünschtes Ergebnis (null = was eben schmelzbar ist), count = Zielmenge. */
    record Smelt(String item, int count) implements TaskSpec {
        public Smelt { item = ItemIds.canonicalOrOriginal(item); }
        @Override public TaskType type() { return TaskType.SMELT; }
    }

    /** Werkbank/Ofen/Kiste aufstellen; portable: nur eine ganz nahe zählt, danach packt er sie wieder ein. */
    record Station(String item, boolean portable) implements TaskSpec {
        public Station { item = ItemIds.canonicalOrOriginal(item); }
        @Override public TaskType type() { return TaskType.STATION; }
    }

    /** Tränke brauen: potion = Art (fire, strength, healing, slow_fall), count = gewünschte Gesamtzahl. */
    record Brew(String potion, int count) implements TaskSpec {
        @Override public TaskType type() { return TaskType.BREW; }
    }

    /** Werkzeuge/Rüstung reparieren, die unter belowPercent Haltbarkeit liegen (0 = 90). */
    record Repair(int belowPercent) implements TaskSpec {
        @Override public TaskType type() { return TaskType.REPAIR; }
    }

    /**
     * Ausrüstungsziel: tier = Stufe, lastPiece = zuletzt angegangenes Teil (null = keins), attempts = Versuche an diesem
     * Teil, skipMask = Bitmaske übersprungener Teile. Die Runde zählt Task.tries.
     */
    record GearUp(int tier, String lastPiece, int attempts, int skipMask) implements TaskSpec {
        public GearUp {
            if (lastPiece != null && lastPiece.isEmpty()) lastPiece = null;
            lastPiece = ItemIds.canonicalOrOriginal(lastPiece);
        }

        /** Alte Form "Stufe:zuletzt:Versuche"; Unlesbares fällt auf Stufe 3 / 0 Versuche zurück wie bisher. */
        static GearUp parse(String arg, int skipMask) {
            String[] a = (arg == null ? "3" : arg).split(":", -1);
            int tier;
            try { tier = Integer.parseInt(a[0]); } catch (NumberFormatException ex) { tier = 3; }
            int attempts = 0;
            try { if (a.length > 2) attempts = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) { }
            return new GearUp(tier, a.length > 1 ? a[1] : null, attempts, skipMask);
        }

        @Override public TaskType type() { return TaskType.GEARUP; }
    }

    /** Tür an pos einsetzen; facing = Blickrichtung ("north" …). */
    record Door(BlockPos pos, String facing) implements TaskSpec {
        @Override public TaskType type() { return TaskType.DOOR; }
    }

    // ------------------------------------------------------------------ Ressourcen

    /** Eine Ressource (Resources-Key) in einer Menge besorgen: GATHER sucht sichtbar, MINE gräbt danach. */
    sealed interface ResourceGoal extends TaskSpec permits Gather, Mine {
        String resource();
        int count();
        int targetTotal();
        ResourceGoal withTarget(int targetTotal);
        /** Dasselbe Ziel mit anderer Restmenge. */
        ResourceGoal withCount(int count);
    }

    record Gather(String resource, int count, int targetTotal, String exactItem) implements ResourceGoal {
        public Gather { exactItem = ItemIds.canonicalOrOriginal(exactItem); }
        public Gather(String resource, int count) { this(resource, count, 0, null); }
        public Gather(String resource, int count, int targetTotal) { this(resource, count, targetTotal, null); }
        @Override public Gather withTarget(int total) { return new Gather(resource, count, total, exactItem); }
        @Override public TaskType type() { return TaskType.GATHER; }
        @Override public Gather withCount(int count) { return new Gather(resource, count, targetTotal, exactItem); }
    }

    record Mine(String resource, int count, int targetTotal) implements ResourceGoal {
        public Mine(String resource, int count) { this(resource, count, 0); }
        @Override public Mine withTarget(int total) { return new Mine(resource, count, total); }
        @Override public TaskType type() { return TaskType.MINE; }
        @Override public Mine withCount(int count) { return new Mine(resource, count, targetTotal); }
    }

    /** Baum an pos (unterster Stamm) fällen; logs = erwartete Stämme. */
    record Tree(BlockPos pos, int logs) implements TaskSpec {
        @Override public TaskType type() { return TaskType.TREE; }
    }

    /** Auf pos hinaufklettern (Gerüst). */
    record Climb(BlockPos pos) implements TaskSpec {
        @Override public TaskType type() { return TaskType.CLIMB; }
    }

    /** Vom Gerüst/Baum wieder herunter. */
    record Descend() implements TaskSpec {
        @Override public TaskType type() { return TaskType.DESCEND; }
    }

    /** Bis zur Höhe von pos hochbauen. */
    record Pillar(BlockPos pos) implements TaskSpec {
        @Override public TaskType type() { return TaskType.PILLAR; }
    }

    // ------------------------------------------------------------------ Tiere und Kampf

    /** Tiere jagen, bis food Essen da ist (mindestens 2). */
    record Hunt(int food) implements TaskSpec {
        @Override public TaskType type() { return TaskType.HUNT; }
    }

    /** Ein Tier anleinen und zum Besitzer bringen; animal = Typ-ID, "any"/null = irgendein Nutztier. */
    record Fetch(String animal) implements TaskSpec {
        public Fetch {
            if (!"any".equals(animal)) animal = RegistryIds.canonicalOrOriginal(animal);
        }
        @Override public TaskType type() { return TaskType.FETCH; }
    }

    /**
     * Mobs einer Art (mob = Typ-ID) bekämpfen, bis lootCount Beute da ist; loot = Beute-Item (null = übliche Beute der
     * Art, siehe {@link #lootItem}); pos = optionaler Sammelpunkt wie ein Spawner.
     */
    record Fight(BlockPos pos, String mob, String loot, int lootCount) implements TaskSpec {
        public Fight {
            mob = RegistryIds.canonicalOrOriginal(mob);
            loot = ItemIds.canonicalOrOriginal(loot);
        }
        /** Alte Form "mob" oder "mob|beute". */
        static Fight parse(BlockPos pos, String arg, int lootCount) {
            if (arg == null) return new Fight(pos, null, null, lootCount);
            int bar = arg.indexOf('|');
            return bar < 0 ? new Fight(pos, arg, null, lootCount)
                    : new Fight(pos, arg.substring(0, bar), arg.substring(bar + 1), lootCount);
        }

        /** Beute, an der er erkennt, dass es genug ist; null = kein Ende über Beute. */
        public String lootItem() {
            if (loot != null || mob == null) return loot;
            return ItemIds.canonicalOrOriginal(switch (mob) {
                case "minecraft:blaze" -> "blaze_rod";
                case "minecraft:enderman" -> "ender_pearl";
                case "minecraft:spider" -> "string";
                case "minecraft:chicken" -> "feather";
                default -> null;
            });
        }

        @Override public TaskType type() { return TaskType.FIGHT; }
    }

    /** Sichtbare Endkristalle abschießen. */
    record Crystals() implements TaskSpec {
        @Override public TaskType type() { return TaskType.CRYSTALS; }
    }

    /** Je zwei erwachsene Tiere der Art species füttern; pairs = Paare (0 = so viele wie möglich). */
    record Breed(String species, int pairs) implements TaskSpec {
        public Breed { species = RegistryIds.canonicalOrOriginal(species); }
        @Override public TaskType type() { return TaskType.BREED; }
    }

    /** Angeln (simuliert): pos = Wurfstelle (null = suchen), catches = noch zu fangen (0 = Vorgabe 6). */
    record Fish(BlockPos pos, int catches) implements TaskSpec {
        @Override public TaskType type() { return TaskType.FISH; }
    }

    /** Feld um pos ernten und bestellen. */
    record Farm(BlockPos pos, boolean forage, boolean plantBerries) implements TaskSpec {
        public Farm(BlockPos pos) { this(pos, false, false); }
        public Farm(BlockPos pos, boolean forage) { this(pos, forage, false); }
        @Override public TaskType type() { return TaskType.FARM; }
    }

    /** Mit Dorfbewohnern handeln: item = zu kaufen (null = Überzähliges verkaufen), count = Anzahl (0 = 16). */
    record Trade(String item, int count) implements TaskSpec {
        public Trade {
            item = ItemIds.canonicalOrOriginal(item);
        }

        @Override public TaskType type() { return TaskType.TRADE; }
    }

    // ------------------------------------------------------------------ Projekte

    /** Den Bauauftrag der Entity (mob.build) abarbeiten; Fortschritt liegt im BuildJob, nicht in der Aufgabe. */
    record Build() implements TaskSpec {
        @Override public TaskType type() { return TaskType.BUILD; }
    }

    /** Haus planen und bauen; pos = Bauplatz-Ecke (null = Platz suchen). */
    record House(BlockPos pos) implements TaskSpec {
        @Override public TaskType type() { return TaskType.HOUSE; }
    }

    /** Haus um einen Lagerraum verlängern; annex = Nummer des Anbaus (0 = der nächste). */
    record Extend(int annex) implements TaskSpec {
        @Override public TaskType type() { return TaskType.EXTEND; }
    }

    /** Netherportal bauen; pos = Bauplatz-Ecke (null = Platz suchen). */
    record Portal(BlockPos pos) implements TaskSpec {
        @Override public TaskType type() { return TaskType.PORTAL; }
    }

    /** Eimer mit Wasser füllen; pos = Quelle (null = suchen/erinnern/erkunden). */
    record Water(BlockPos pos) implements TaskSpec {
        @Override public TaskType type() { return TaskType.WATER; }
    }

    /** Wasser auf die Lava-Quelle an pos gießen und das Obsidian abbauen. */
    record Pour(BlockPos pos) implements TaskSpec {
        @Override public TaskType type() { return TaskType.POUR; }
    }

    /** Weite Strecke bis in 20 Blöcke Nähe von pos. */
    record Travel(BlockPos pos) implements TaskSpec {
        @Override public TaskType type() { return TaskType.TRAVEL; }
    }

    /** Festung finden und das Endportal öffnen; Raum und Portal stehen auf der Entity. */
    record Stronghold() implements TaskSpec {
        @Override public TaskType type() { return TaskType.STRONGHOLD; }
    }
}
