package dev.begleiter.task;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionTexts;
import dev.begleiter.ItemIds;
import dev.begleiter.RegistryIds;

/**
 * Versionierte Speicherung der Aufgabenwarteschlange. Gespeichert wird nur die stabile Queue, nie ein laufender
 * Handler: nach dem Laden startet die vorderste Aufgabe frisch und prüft die Welt selbst neu.
 *
 * V2 schreibt je Aufgabe den Typ ("T"), den Versuchszähler ("R") und die Felder des {@link TaskSpec}-Records unter
 * ihrem Namen (pos, item, count …); Item-IDs mit Namensraum; Nullwerte, 0 und false entfallen.
 * Alte V1-/V2-Kurz-IDs werden durch TaskSpec-Konstruktoren zu Minecraft-IDs normalisiert.
 * V1 (alte Welten: mehrdeutiges "A"/"N") bleibt
 * lesbar und wird über {@link #v1Spec} in die Records übersetzt.
 *
 * Laden ist alles-oder-nichts: Eine einzige unlesbare Aufgabe (unbekannter Typ oder Block, Wert mit falschem Typ,
 * fehlende Pflichtangabe, fremde Version, zu viele Einträge) verwirft die ganze Queue. Eine Kette mit Lücke (Werkzeug
 * fehlt, Abgabe bleibt) wäre gefährlicher als gar kein Auftrag. Aufgaben mit flüchtigem Laufzeitziel (FETCH:
 * angeleintes Tier) werden beim Laden einzeln ausgelassen, weil ihr Ziel nicht gespeichert wird.
 */
public final class TaskQueueCodec {
    public static final int VERSION = 2;
    /** Obergrenze für gespeicherte Aufgaben (ein Hausbau sind ~100–200 PLACE); größere Queues plant er neu. */
    public static final int MAX_TASKS = 1024;
    static final int MAX_ARG = 256, MAX_N = 1 << 20, MAX_TRIES = 1000;

    private static final Set<TaskType> NEEDS_POS = EnumSet.of(TaskType.BREAK, TaskType.PLACE, TaskType.MOVE_TO,
            TaskType.SORT, TaskType.TAKE, TaskType.POUR, TaskType.PILLAR, TaskType.CLIMB, TaskType.TRAVEL,
            TaskType.DOOR, TaskType.TREE);
    private static final Set<TaskType> NEEDS_BLOCK = EnumSet.of(TaskType.PLACE);
    /** Laufzeitziel nicht gespeichert: nach dem Laden nicht fortsetzen. */
    private static final Set<TaskType> VOLATILE = EnumSet.of(TaskType.FETCH);

    /** Record-Klasse samt Feldern je Aufgabentyp, aus den erlaubten Untertypen von TaskSpec. */
    private record Shape(RecordComponent[] fields, Constructor<?> make) { }
    private static final Map<TaskType, Shape> SHAPES = shapes();

    /** Ergebnis des Ladens; problem != null bedeutet: Queue verworfen (tasks leer). */
    public record Loaded(List<Task> tasks, boolean paused, int skipped, String problem, Component problemText) {
        public Loaded(List<Task> tasks, boolean paused, int skipped, String problem) {
            this(tasks, paused, skipped, problem, CompanionTexts.literal(problem));
        }
        public Loaded { problemText = problemText == null ? null : problemText.copy(); }
        @Override public Component problemText() { return problemText == null ? null : problemText.copy(); }
        static Loaded rejected(Component problem) { return new Loaded(List.of(), false, 0, problem.getString(), problem); }
    }

    /** Unlesbarer Eintrag; verwirft beim Laden die ganze Queue. */
    static final class Bad extends Exception {
        final Component problemText;
        Bad(Component problem) { super(problem.getString(), null, false, false); problemText = problem.copy(); }
    }

    private TaskQueueCodec() { }

    /** null, wenn die Queue zu groß zum Speichern ist (dann plant er nach dem Laden neu). */
    public static CompoundTag save(Collection<Task> tasks, boolean paused) {
        if (tasks.size() > MAX_TASKS) return null;
        ListTag list = new ListTag();
        for (Task t : tasks) {
            CompoundTag e = encode(t.spec());
            e.putString("T", t.type().name());
            if (t.tries() != 0) e.putInt("R", t.tries());
            list.add(e);
        }
        CompoundTag root = new CompoundTag();
        root.putInt("V", VERSION);
        root.putBoolean("Paused", paused);
        root.put("Tasks", list);
        return root;
    }

    public static Loaded load(CompoundTag root) {
        int version = root.getIntOr("V", -1);
        if (version != 1 && version != VERSION) return Loaded.rejected(CompanionTexts.tr("queue.error.version", version));
        ListTag list = root.getListOrEmpty("Tasks");
        if (list.size() > MAX_TASKS) return Loaded.rejected(CompanionTexts.tr("queue.error.too_many", list.size()));
        List<Task> out = new ArrayList<>(list.size());
        int skipped = 0;
        try {
            for (int i = 0; i < list.size(); i++) {
                Tag tag = list.get(i);
                if (!(tag instanceof CompoundTag e)) throw new Bad(CompanionTexts.tr("queue.error.entry", i));
                TaskType type;
                try {
                    type = TaskType.valueOf(e.getStringOr("T", ""));
                } catch (IllegalArgumentException ex) {
                    throw new Bad(CompanionTexts.tr("queue.error.type", CompanionTexts.literal(e.getStringOr("T", ""))));
                }
                TaskSpec spec = version == 1 ? readV1(type, e) : decode(type, e);
                Component missing = missing(spec);
                if (missing != null) throw new Bad(CompanionTexts.tr("queue.error.missing", CompanionTexts.taskLabel(type), missing));
                int tries = Math.clamp(e.getIntOr("R", 0), 0, MAX_TRIES);
                if (VOLATILE.contains(type)) { skipped++; continue; }
                out.add(new Task(spec, tries));
            }
        } catch (Bad bad) {
            return Loaded.rejected(bad.problemText);
        }
        return new Loaded(List.copyOf(out), root.getBooleanOr("Paused", false), skipped, null);
    }

    /** Pflichtangabe, die dem Record fehlt (Position, Block, Parameter); null = vollständig. */
    private static Component missing(TaskSpec spec) {
        if (NEEDS_POS.contains(spec.type()) && spec.pos() == null) return CompanionTexts.tr("queue.required.position");
        if (spec instanceof TaskSpec.MoveTo move && move.portal() != null
                && (move.stockReturn() || !Set.of("minecraft:nether_portal", "minecraft:end_portal").contains(move.portal())))
            return CompanionTexts.tr("queue.required.parameter");
        if (spec instanceof TaskSpec.Farm farm && ((farm.forage() || farm.plantBerries()) && farm.pos() == null
                || farm.forage() && farm.plantBerries()))
            return CompanionTexts.tr("queue.required.position");
        if (NEEDS_BLOCK.contains(spec.type()) && spec.block() == null) return CompanionTexts.tr("queue.required.block");
        String required = switch (spec) {
            case TaskSpec.ResourceGoal g -> g.resource();
            case TaskSpec.Craft c -> c.item();
            case TaskSpec.Process p -> p.item();
            case TaskSpec.Request r -> r.item();
            case TaskSpec.Take tk -> tk.item();
            case TaskSpec.Station s -> s.item();
            case TaskSpec.Fight f -> f.mob();
            default -> "-";
        };
        if (required == null || required.isEmpty()) return CompanionTexts.tr("queue.required.parameter");
        String entity = switch (spec) {
            case TaskSpec.Fight f -> f.mob();
            case TaskSpec.Breed b -> b.species();
            default -> null;
        };
        if (entity != null) {
            Identifier id = RegistryIds.parse(entity);
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return CompanionTexts.tr("queue.required.entity_id");
        }
        String exactItem = switch (spec) {
            case TaskSpec.Take t -> t.item();
            case TaskSpec.Request r -> r.item();
            case TaskSpec.Craft c -> "#planks_from_logs".equals(c.item()) ? null : c.item();
            case TaskSpec.Process p -> p.item();
            case TaskSpec.Smelt s -> s.item();
            case TaskSpec.Station s -> s.item();
            case TaskSpec.Trade t -> t.item();
            case TaskSpec.GearUp g -> g.lastPiece();
            case TaskSpec.Fight f -> f.loot();
            case TaskSpec.Gather g -> g.exactItem();
            case TaskSpec.Give g -> g.what() != null && g.what().startsWith("item:") ? g.what().substring(5) : null;
            default -> null;
        };
        if (exactItem != null) {
            Identifier id = ItemIds.parse(exactItem);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)
                    || BuiltInRegistries.ITEM.getValue(id) == net.minecraft.world.item.Items.AIR)
                return CompanionTexts.tr("queue.required.item_id");
        }
        if (spec instanceof TaskSpec.Request r && (r.count() <= 0 || r.phase() < 0 || r.phase() > 6
                || r.excludedSource() != null && r.phase() != 4
                || r.homeOrder() != (r.pos() != null)
                || r.homeOrder() && r.materials()
                || r.fuel() != null && (!r.homeOrder() || !Set.of("minecraft:coal", "minecraft:charcoal").contains(r.fuel()))
                || (r.phase() == 0 || r.homeOrder()) && r.count() > dev.begleiter.chat.ChatCount.MAX))
            return CompanionTexts.tr("queue.required.parameter");
        if (spec instanceof TaskSpec.Process p && (p.count() <= 0 || p.count() > dev.begleiter.CompanionRecipes.MAX_PROCESSING_BATCH
                || RegistryIds.parse(p.recipe()) == null)) return CompanionTexts.tr("queue.required.parameter");
        // Exaktes Holzziel: nur eine bekannte Stammsorte beim Holzsammeln
        if (spec instanceof TaskSpec.Gather g && g.exactItem() != null) {
            Identifier id = ItemIds.parse(g.exactItem());
            if (!"holz".equals(g.resource()) || id == null || !BuiltInRegistries.ITEM.containsKey(id)
                    || !new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(id)).is(net.minecraft.tags.ItemTags.LOGS))
                return CompanionTexts.tr("queue.required.wood_target");
        }
        return null;
    }

    // ------------------------------------------------------------------ V2: benannte Felder

    static CompoundTag encode(TaskSpec spec) {
        CompoundTag e = new CompoundTag();
        for (RecordComponent f : SHAPES.get(spec.type()).fields()) {
            String key = f.getName();
            switch (read(f, spec)) {
                case BlockPos p -> e.putLong(key, p.asLong());
                case Block b -> e.putString(key, BuiltInRegistries.BLOCK.getKey(b).toString());
                case String s -> e.putString(key, s);
                case Integer n -> { if (n != 0) e.putInt(key, n); }
                case Boolean b -> { if (b) e.putBoolean(key, true); }
                case null -> { }
                default -> throw new IllegalStateException("Feldtyp " + f.getType() + " in " + spec.getClass());
            }
        }
        return e;
    }

    static TaskSpec decode(TaskType type, CompoundTag e) throws Bad {
        Shape shape = SHAPES.get(type);
        Object[] args = new Object[shape.fields().length];
        for (int i = 0; i < args.length; i++) {
            RecordComponent f = shape.fields()[i];
            String key = f.getName();
            Class<?> c = f.getType();
            boolean has = e.contains(key);
            if (c == BlockPos.class) args[i] = has ? BlockPos.of(value(e.getLong(key), type, key)) : null;
            else if (c == Block.class) args[i] = has ? block(value(e.getString(key), type, key), type) : null;
            else if (c == String.class) args[i] = has ? text(value(e.getString(key), type, key), type) : null;
            else if (c == int.class) args[i] = has ? Math.clamp(value(e.getInt(key), type, key), 0, MAX_N) : 0;
            else if (c == boolean.class) args[i] = has && value(e.getBoolean(key), type, key);
            else throw new IllegalStateException("Feldtyp " + c + " bei " + type);
        }
        try {
            return (TaskSpec) shape.make().newInstance(args);
        } catch (InvocationTargetException ex) {
            throw new Bad(CompanionTexts.tr("queue.error.arguments", CompanionTexts.taskLabel(type), CompanionTexts.literal(String.valueOf(ex.getCause()))));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static <T> T value(Optional<T> v, TaskType type, String key) throws Bad {
        if (v.isEmpty()) throw new Bad(CompanionTexts.tr("queue.error.value", CompanionTexts.literal(key), CompanionTexts.taskLabel(type)));
        return v.get();
    }

    private static Object read(RecordComponent f, TaskSpec spec) {
        try {
            return f.getAccessor().invoke(spec);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<TaskType, Shape> shapes() {
        Map<TaskType, Shape> m = new EnumMap<>(TaskType.class);
        Deque<Class<?>> open = new ArrayDeque<>(List.of(TaskSpec.class));
        while (!open.isEmpty()) {
            Class<?> c = open.pop();
            if (c.isInterface()) { open.addAll(List.of(c.getPermittedSubclasses())); continue; }
            RecordComponent[] fields = c.getRecordComponents();
            Class<?>[] types = new Class<?>[fields.length];
            Object[] blank = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType();
                blank[i] = types[i] == int.class ? 0 : types[i] == boolean.class ? false : null;
            }
            try {
                Constructor<?> make = c.getDeclaredConstructor(types);
                TaskType type = ((TaskSpec) make.newInstance(blank)).type();
                if (m.put(type, new Shape(fields, make)) != null) throw new IllegalStateException("two records for " + type);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("TaskSpec " + c.getSimpleName(), ex);
            }
        }
        for (TaskType t : TaskType.values()) if (!m.containsKey(t)) throw new IllegalStateException("no record for " + t);
        return m;
    }

    // ------------------------------------------------------------------ V1: Position/Block/"A"/"N"

    private static TaskSpec readV1(TaskType type, CompoundTag e) throws Bad {
        BlockPos pos = e.contains("P") ? BlockPos.of(e.getLongOr("P", 0)) : null;
        Block block = e.contains("B") ? block(e.getStringOr("B", ""), type) : null;
        String arg = e.contains("A") ? text(e.getStringOr("A", ""), type) : null;
        int n = Math.clamp(e.getIntOr("N", 0), 0, MAX_N);
        return v1Spec(type, pos, block, arg, n);
    }

    /** Alte Form (TaskQueueV1) in den typisierten Record übersetzen. */
    static TaskSpec v1Spec(TaskType type, BlockPos pos, Block block, String arg, int n) {
        return switch (type) {
            case MOVE_TO -> new TaskSpec.MoveTo(pos, n);
            case GIVE -> new TaskSpec.Give(arg, n);
            case PLACE -> new TaskSpec.Place(pos, block);
            case BREAK -> new TaskSpec.Break(pos, block);
            case COLLECT -> new TaskSpec.Collect(n == 1);
            case DELIVER -> new TaskSpec.Deliver(n == 1);
            case SORT -> new TaskSpec.Sort(pos);
            case TAKE -> new TaskSpec.Take(pos, arg, n);
            case CRAFT -> new TaskSpec.Craft(arg, n);
            case PROCESS -> new TaskSpec.Process(arg, n, null);
            case REQUEST -> new TaskSpec.Request(arg, n, false, 0, 0);
            case SMELT -> new TaskSpec.Smelt(arg, n);
            case STATION -> new TaskSpec.Station(arg, n == 1);
            case REPAIR -> new TaskSpec.Repair(n);
            case BREW -> new TaskSpec.Brew(arg, n);
            case GEARUP -> TaskSpec.GearUp.parse(arg, n);
            case DOOR -> new TaskSpec.Door(pos, arg);
            case GATHER -> new TaskSpec.Gather(arg, n);
            case MINE -> new TaskSpec.Mine(arg, n);
            case TREE -> new TaskSpec.Tree(pos, n);
            case CLIMB -> new TaskSpec.Climb(pos);
            case DESCEND -> new TaskSpec.Descend();
            case PILLAR -> new TaskSpec.Pillar(pos);
            case HUNT -> new TaskSpec.Hunt(n);
            case FETCH -> new TaskSpec.Fetch(arg);
            case FIGHT -> TaskSpec.Fight.parse(pos, arg, n);
            case CRYSTALS -> new TaskSpec.Crystals();
            case BREED -> new TaskSpec.Breed(arg, n);
            case FISH -> new TaskSpec.Fish(pos, n);
            case FARM -> new TaskSpec.Farm(pos);
            case TRADE -> new TaskSpec.Trade(arg != null && arg.isEmpty() ? null : arg, n);
            case BUILD -> new TaskSpec.Build();
            case HOUSE -> new TaskSpec.House(pos);
            case EXTEND -> new TaskSpec.Extend(n);
            case PORTAL -> new TaskSpec.Portal(pos);
            case WATER -> new TaskSpec.Water(pos);
            case POUR -> new TaskSpec.Pour(pos);
            case TRAVEL -> new TaskSpec.Travel(pos);
            case STRONGHOLD -> new TaskSpec.Stronghold();
        };
    }

    // ------------------------------------------------------------------ gemeinsam

    private static Block block(String raw, TaskType type) throws Bad {
        Identifier id = Identifier.tryParse(raw);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
            throw new Bad(CompanionTexts.tr("queue.error.block", CompanionTexts.literal(raw), CompanionTexts.taskLabel(type)));
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    private static String text(String raw, TaskType type) throws Bad {
        if (raw.length() > MAX_ARG) throw new Bad(CompanionTexts.tr("queue.error.parameter_length", CompanionTexts.taskLabel(type)));
        return raw;
    }
}
