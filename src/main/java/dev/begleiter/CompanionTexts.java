package dev.begleiter;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonParser;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server-safe component factories. Translation is resolved by each receiving client's language. */
public final class CompanionTexts {
    private static final String PREFIX = "server.begleiter.";
    private static final Map<String, String> FALLBACK = loadFallback();
    private CompanionTexts() {}

    private static Map<String, String> loadFallback() {
        try (var stream = CompanionTexts.class.getResourceAsStream("/assets/begleiter/lang/de_de.json")) {
            if (stream == null) throw new IllegalStateException("Missing German language catalog");
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> result = new HashMap<>();
            json.entrySet().forEach(entry -> { if (entry.getKey().startsWith(PREFIX)) result.put(entry.getKey(), entry.getValue().getAsString()); });
            return Map.copyOf(result);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot read server text fallbacks", ex); }
    }

    public static MutableComponent tr(String key, Object... args) {
        String full = PREFIX + key;
        String fallback = FALLBACK.get(full);
        if (fallback == null) throw new IllegalArgumentException("Missing server text: " + full);
        return Component.translatableWithFallback(full, fallback, args);
    }

    /** Chat-Meldung an einen Spieler mit dem übersetzten Präfix „[Begleiter] “ / „[Companion] “. */
    public static MutableComponent prefixed(Component message) {
        return tr("prefix").append(message);
    }

    public static Component literal(String value) {
        if (value == null) return null;
        return Component.literal(value.length() <= 4096 ? value : value.substring(0, 4096));
    }

    public static Component logLine(char level, int seconds, int repetitions, Component message) {
        return tr("debug.log.line", String.format(Locale.ROOT, "%02d", seconds / 60),
                String.format(Locale.ROOT, "%02d", seconds % 60), tr("debug.log." + level), message,
                repetitions > 1 ? Component.literal(" (x" + repetitions + ")") : Component.empty());
    }

    public static Component mode(CompanionEntity c) {
        if (c.duty() != null) return tr("mode." + c.duty().name().toLowerCase(Locale.ROOT));
        return tr("mode." + (c.isKeeper() ? "keeper" : c.isStaying() ? "stay" : c.isAutonomous() ? "free" : "follow"));
    }

    public static Component buildMovement(CompanionEntity.BuildMovement movement) {
        return tr("build.movement." + movement.name().toLowerCase(Locale.ROOT));
    }

    public static Component stance(CompanionCombat.Stance stance) {
        return tr("stance." + stance.name().toLowerCase(Locale.ROOT));
    }

    public static Component taskLabel(CompanionEntity.TaskType type) {
        return tr("task." + type.name().toLowerCase(Locale.ROOT));
    }

    public static Component resource(String key) {
        if (key == null) return Component.empty();
        return Resources.byKey(key) == null ? literal(key) : tr("resource." + key);
    }

    public static Component item(String id) {
        if (id == null) return Component.empty();
        if (id.startsWith("item:")) id = id.substring(5);
        if (Resources.byKey(id) != null) return resource(id);
        if (id.equals("#planks_from_logs")) return tr("task.planks");
        var item = ItemIds.resolve(id);
        return item == null || item == Items.AIR ? literal(id) : new ItemStack(item).getHoverName();
    }

    /** The groups recognized by CompanionCraft.matches, without changing ingredient selection. */
    public static Component craftIngredient(String id) {
        return switch (id) {
            case "#planks" -> tr("craft.ingredient.planks");
            case "#logs" -> tr("craft.ingredient.logs");
            case "#stone" -> tr("craft.ingredient.stone");
            case "#coals" -> tr("craft.ingredient.coals");
            default -> item(id);
        };
    }

    public static Component animal(String id) {
        if (id == null || id.equals("any")) return tr("animal.any");
        var key = RegistryIds.parse(id);
        var type = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getValue(key);
        return type == null ? literal(id) : Component.translatable(type.getDescriptionId());
    }

    public static Component animals(String id) {
        String key = RegistryIds.canonical(id);
        if (key == null || "any".equals(id)) return tr("animal.all");
        return switch (key) {
            case "minecraft:cow" -> tr("animal.cows"); case "minecraft:sheep" -> tr("animal.sheep");
            case "minecraft:pig" -> tr("animal.pigs"); case "minecraft:chicken" -> tr("animal.chickens");
            case "minecraft:rabbit" -> tr("animal.rabbits"); case "minecraft:goat" -> tr("animal.goats");
            case "minecraft:horse" -> tr("animal.horses"); case "minecraft:wolf" -> tr("animal.wolves");
            case "minecraft:donkey" -> tr("animal.donkeys"); case "minecraft:cat" -> tr("animal.cats");
            default -> animal(id);
        };
    }

    public static Component activity(CompanionEntity c) {
        Task task = c.currentTask();
        if (task == null) return tr(c.isPaused() ? "activity.paused" : "activity.idle");
        return task(task);
    }

    public static Component task(Task task) {
        MutableComponent text = taskLabel(task.type()).copy();
        if (task.spec() instanceof TaskSpec.ResourceGoal goal) {
            text = tr("activity.search", text, resource(goal.resource()), goal.count());
        } else {
            Component detail = switch (task.spec()) {
                case TaskSpec.Give spec -> item(spec.what());
                case TaskSpec.Take spec -> item(spec.item());
                case TaskSpec.Craft spec -> item(spec.item());
                case TaskSpec.Process spec -> item(spec.item());
                case TaskSpec.Request spec -> item(spec.item());
                case TaskSpec.Smelt spec -> item(spec.item());
                case TaskSpec.Station spec -> item(spec.item());
                case TaskSpec.Fetch spec -> animal(spec.animal());
                case TaskSpec.Breed spec -> animal(spec.species());
                case TaskSpec.Trade spec -> spec.item() == null ? tr("trade.surplus") : item(spec.item());
                case TaskSpec.Fight spec -> animal(spec.mob());
                default -> Component.empty();
            };
            if (!detail.getString().isEmpty()) text.append(" · ").append(detail);
            int amount = switch (task.spec()) {
                case TaskSpec.Give spec -> spec.max(); case TaskSpec.Take spec -> spec.count();
                case TaskSpec.Craft spec -> spec.count(); case TaskSpec.Smelt spec -> spec.count();
                case TaskSpec.Process spec -> spec.count();
                case TaskSpec.Request spec -> spec.count();
                case TaskSpec.Breed spec -> spec.pairs(); case TaskSpec.Trade spec -> spec.count();
                default -> 0;
            };
            if (amount > 0) text.append(tr("activity.amount", amount));
        }
        if (task.pos() != null) text.append(" · " + task.pos().toShortString());
        return text;
    }

    public static Component hazards(CompanionEntity c) {
        if (c.nearestLava == null && c.nearestWater == null) return tr("hazard.none");
        MutableComponent text = Component.empty();
        if (c.nearestLava != null) text.append(tr("hazard.lava", (int) Math.sqrt(c.nearestLava.distSqr(c.blockPosition())),
                c.nearestLava.toShortString()));
        if (c.nearestWater != null) {
            if (c.nearestLava != null) text.append(", ");
            text.append(tr("hazard.water", (int) Math.sqrt(c.nearestWater.distSqr(c.blockPosition()))));
        }
        return text;
    }

    public static Component needs(CompanionEntity c) {
        if (c.goal == null) return tr(c.hasTasks() ? "needs.player_order" : "needs.idle");
        return tr("needs.goal", tr("need." + c.goal.name().toLowerCase(Locale.ROOT)), c.goalScore, c.goalReasonComponent());
    }

    public static Component target(CompanionEntity c) {
        return tr("combat.target", c.getTarget() == null ? tr("combat.none") : c.getTarget().getDisplayName(), stance(c.stance));
    }

    public static Component status(CompanionEntity c) {
        return tr("status", mode(c), c.isPaused() ? tr("status.paused") : Component.empty(), activity(c), c.taskCount(),
                c.usedSlots(), (int) c.getHealth(), (int) c.getMaxHealth(),
                c.getHome() == null ? tr("status.no_home") : literal(c.getHome().toShortString()),
                tr(c.isHouseBuilt() ? "status.yes" : "status.no"), stance(c.stance));
    }

    public static Component join(java.util.List<? extends Component> parts, String separator) {
        MutableComponent result = Component.empty();
        int length = 0;
        for (int i = 0; i < parts.size(); i++) {
            int next = parts.get(i).getString().length() + (i == 0 ? 0 : separator.length());
            if (i >= 32 || length + next > 4000) {
                result.append(tr("list.more", parts.size() - i));
                break;
            }
            if (i > 0) result.append(separator);
            result.append(parts.get(i));
            length += next;
        }
        return result;
    }

    public static Component plan(CompanionPlanner.Plan plan) {
        return join(plan.tasks().stream().map(CompanionTexts::task).toList(), " → ");
    }

    public static Component materials(Map<net.minecraft.world.item.Item, Integer> materials) {
        if (materials.isEmpty()) return tr("materials.none");
        return join(materials.entrySet().stream().map(entry ->
                tr("materials.entry", entry.getValue(), new ItemStack(entry.getKey()).getHoverName())).toList(), ", ");
    }

    public static Component buildingMaterials(CompanionEntity c) {
        return c.creativeHelper() ? tr("materials.creative") : tr("materials.required",
                materials(CompanionBrain.top(CompanionBuilder.remaining((net.minecraft.server.level.ServerLevel) c.level(), c.build), 8)));
    }

    public static Component tier(int tier) {
        return tr("tier." + switch (tier) { case 3 -> "iron"; case 4 -> "diamond"; case 5 -> "netherite"; default -> "stone"; });
    }

    public static Component location(CompanionMemory.Place place, net.minecraft.core.BlockPos from) {
        int dx = place.pos.getX() - from.getX(), dz = place.pos.getZ() - from.getZ();
        String direction;
        if (Math.abs(dx) < 4 && Math.abs(dz) < 4) direction = "here";
        else {
            String ns = dz < -Math.abs(dx) / 2 ? "north" : dz > Math.abs(dx) / 2 ? "south" : "";
            String ew = dx > Math.abs(dz) / 2 ? "east" : dx < -Math.abs(dz) / 2 ? "west" : "";
            direction = ns + ew;
        }
        return tr("location", place.pos.toShortString(), (int) Math.sqrt(place.pos.distSqr(from)), tr("direction." + direction));
    }

    public static Component terrain(CompanionTerraform.Plan plan) {
        MutableComponent result = tr("terrain.summary", tr("terrain." + plan.mode().name().toLowerCase(Locale.ROOT)), plan.sizeX(), plan.sizeZ());
        if (plan.cut() > 0) result.append(tr("terrain.cut_count", plan.cut()));
        if (plan.fill() > 0) result.append(tr("terrain.fill_count", plan.fill()));
        if (plan.skipped() > 0) result.append(tr("terrain.skipped", plan.skipped()));
        return result;
    }

    /** Gespeicherter Name eines Terraform-Auftrags: sprachneutral, erst die Anzeige übersetzt ihn. */
    public static String terrainJobName(CompanionTerraform.Mode mode, int sizeX, int sizeZ) {
        return TERRAIN_JOB + mode.name().toLowerCase(Locale.ROOT) + ":" + sizeX + "x" + sizeZ;
    }

    private static final String TERRAIN_JOB = "terraform:";
    /** Präfix der eingebauten Häuser und der Weizenfarm ({@code structure:wood}, {@code structure:farm}). */
    public static final String STRUCTURE_JOB = "structure:";
    private static final java.util.regex.Pattern TERRAIN_NAME =
            java.util.regex.Pattern.compile("terraform:(flatten|fill|cut|smooth|pit|hill):(\\d{1,4})x(\\d{1,4})");

    /** Name eines Bauauftrags für die Anzeige; Schematic- und Spielernamen bleiben wörtlich. */
    public static Component buildName(String name) {
        if (name == null) return null;
        var catalogue = CompanionSchematicCatalogue.find(name);
        if (catalogue != null) return catalogue.title();
        for (var blueprint : CompanionMachines.ALL) if (blueprint.id().equals(name)) return blueprint.title();
        if (name.equals(STRUCTURE_JOB + "farm")) return tr("structure.farm");
        for (String style : new String[] {"wood", "stone", "birch"})
            if (name.equals(STRUCTURE_JOB + style)) return tr("panel.style." + style);
        var m = TERRAIN_NAME.matcher(name);
        if (!m.matches()) return literal(name);
        return tr("terrain.job", tr("terrain.summary", tr("terrain." + m.group(1)),
                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
    }

    public static Component memorySummary(CompanionEntity c) {
        record Group(CompanionMemory.Kind kind, String key) {}
        Map<Group, Integer> counts = new java.util.LinkedHashMap<>();
        String dimension = CompanionMemory.dim((net.minecraft.server.level.ServerLevel) c.level());
        for (var place : c.memory.all()) {
            if (!place.dim.equals(dimension)) continue;
            String key = place.kind == CompanionMemory.Kind.ORE || place.kind == CompanionMemory.Kind.WAYPOINT ? place.key : "";
            counts.merge(new Group(place.kind, key), 1, Integer::sum);
        }
        if (counts.isEmpty()) return tr("memory.empty");
        java.util.List<Component> parts = new java.util.ArrayList<>();
        counts.forEach((group, count) -> parts.add(tr("materials.entry", count,
                group.kind == CompanionMemory.Kind.ORE ? resource(group.key)
                : group.kind == CompanionMemory.Kind.WAYPOINT ? tr("memory.waypoint", literal(group.key))
                : tr("memory.kind." + group.kind.name().toLowerCase(Locale.ROOT)))));
        return tr("memory.summary", join(parts, ", "));
    }
}
