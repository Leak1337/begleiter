package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.chat.ChatAddress;
import dev.begleiter.chat.ChatText;

/**
 * Das "Gehirn": versteht deutsche und englische Sätze im Chat und plant, was der Begleiter tut,
 * wenn er nicht für den Spieler arbeitet (Überlebens-Fortschritt wie ein echter Spieler).
 */
public final class CompanionBrain {
    private CompanionBrain() {}

    // ================================================================== Sprache

    static String normalize(String s) {
        return ChatText.normalize(s);
    }

    private static boolean any(List<String> tok, String... words) {
        for (String t : tok) for (String w : words) if (t.equals(w)) return true;
        return false;
    }

    private static boolean anyStart(List<String> tok, String... prefixes) {
        for (String t : tok) for (String p : prefixes) if (t.startsWith(p)) return true;
        return false;
    }

    public static void onChat(ServerPlayer p, String raw) {
        List<CompanionEntity> mine = p.level().getEntitiesOfClass(CompanionEntity.class,
                p.getBoundingBox().inflate(64), e -> e.isOwner(p));
        if (mine.isEmpty()) return;
        ChatAddress.Command command = ChatAddress.parse(raw, mine.stream()
                .map(c -> new ChatAddress.Candidate(c.getUUID(),
                        c.getCustomName() == null ? "" : c.getCustomName().getString(), p.distanceToSqr(c)))
                .toList());
        if (command == null) return;
        CompanionEntity target = mine.stream().filter(c -> c.getUUID().equals(command.target())).findFirst().orElseThrow();
        String text = command.text();
        target.chatSerial++;

        net.minecraft.network.chat.Component reply;
        try {
            reply = CompanionChatActions.interpretReply(p, target, command.sourceText(), p.clientInformation().language());
        } catch (RuntimeException ex) {
            target.logError(CompanionTexts.tr("log.chat.error", CompanionTexts.literal(raw), CompanionTexts.literal(String.valueOf(ex))));
            target.tell(CompanionTexts.tr("reply.error"));
            return;
        }
        target.logInfo(CompanionTexts.tr("log.chat", CompanionTexts.literal(raw), reply == null ? CompanionTexts.tr("log.chat.unknown") : reply));
        if (reply != null) {
            target.tell(reply);
        } else if (!CompanionLlmChat.start(p, target, text, p.clientInformation().language())) {
            target.tell(CompanionTexts.tr("reply.unknown"));
        }
    }

    private static int countItems(CompanionEntity c, Resources.Res res) {
        int n = 0;
        for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
            ItemStack s = c.getInventory().getItem(i);
            if (!s.isEmpty() && res.item().test(s)) n += s.getCount();
        }
        return n;
    }

    // ================================================================== Aufträge (Chat + Menü)

    public static Component orderGatherReply(CompanionEntity c, Resources.Res res, int want, boolean toOwner) {
        int need = want - countItems(c, res);
        c.cancelAll();
        if (need > 0) c.addTask(Task.gather(res.key(), need));
        if (toOwner) {
            c.addTask(Task.give(res.key(), 0));
            return CompanionTexts.tr("order.gather.owner", want, CompanionTexts.resource(res.key()));
        }
        if (c.getHome() != null) c.addTask(Task.deliver(false));
        return CompanionTexts.tr("order.gather.store", want, CompanionTexts.resource(res.key()));
    }

    public static Component orderFetchReply(CompanionEntity c, String mob) {
        c.cancelAll();
        c.addTask(Task.fetch(mob));
        return CompanionTexts.tr("order.fetch", CompanionTexts.animal(mob));
    }

    /**
     * Eigener Bau vor dem Spieler: Haus (Stil "holz/stein/birke", Größe "klein/groß") oder Weizenfarm.
     * Unterste Schicht ersetzt die Bodenschicht, darunter wird bis zum festen Boden aufgefüllt.
     */
    public static Component orderStructureReply(ServerPlayer p, CompanionEntity c, List<String> tok) {
        var machine = CompanionMachines.find(String.join(" ", tok));
        if (machine != null) return startMachineReply(p, c, machine, net.minecraft.world.level.block.Rotation.NONE);
        boolean farm = anyStart(tok, "farm", "feld", "weizen", "acker");
        return orderStructureReply(p, c, farm, CompanionStructures.styleFor(tok), CompanionStructures.sizeFor(tok),
                anyStart(tok, "gross"));
    }

    static Component orderStructureReply(ServerPlayer p, CompanionEntity c, boolean farm, CompanionStructures.Style style,
                                 int[] size, boolean large) {
        CompanionSchematics.Schematic s;
        if (farm) {
            s = CompanionStructures.wheatFarm(large ? 9 : 7);
        } else {
            s = CompanionStructures.house(style, size[0], size[1]);
        }
        ServerLevel sl = (ServerLevel) c.level();
        var blocks = CompanionSchematics.place(s, p.blockPosition().below(), p.getDirection(),
                net.minecraft.world.level.block.Rotation.NONE, true);
        CompanionStructures.addFoundation(sl, blocks, farm ? net.minecraft.world.level.block.Blocks.DIRT
                : net.minecraft.world.level.block.Blocks.COBBLESTONE);
        CompanionBuilder.BuildJob job = buildJob(sl, s.name(), blocks, false);
        if (job == null) return CompanionTexts.tr("build.out_of_world");
        c.cancelAll();
        c.build = job;
        c.addTask(Task.build());
        Component mats = CompanionTexts.buildingMaterials(c);
        return CompanionTexts.tr("build.structure", farm ? CompanionTexts.tr("build.farm") : CompanionTexts.tr("build.house." + (style == CompanionStructures.STONE ? "stone" : style == CompanionStructures.BIRCH ? "birch" : "wood")), s.sizeX(), s.sizeZ(), mats);
    }


    /** Bauauftrag anlegen; null, wenn der Plan über Bauhöhe oder Weltgrenze hinausreicht. */
    static CompanionBuilder.BuildJob buildJob(ServerLevel sl, String name, Map<BlockPos, BlockState> blocks, boolean terrain) {
        try {
            return terrain ? CompanionBuilder.createTerrain(sl, name, blocks) : CompanionBuilder.create(sl, name, blocks);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static Component listSchematicsReply() {
        var files = CompanionSchematics.list();
        var names = java.util.stream.Stream.concat(CompanionMachines.ALL.stream().map(CompanionMachines.Blueprint::id),
                files.stream().map(CompanionSchematics::baseName)).limit(30).toList();
        return CompanionTexts.tr("schematic.list", CompanionTexts.literal(String.join(", ", names)));
    }

    public static Component startMachineReply(ServerPlayer p, CompanionEntity c, CompanionMachines.Blueprint blueprint,
                                               net.minecraft.world.level.block.Rotation extra) {
        var s = blueprint.plan().get();
        ServerLevel sl = (ServerLevel) c.level();
        var blocks = CompanionSchematics.place(s, p.blockPosition().below(), p.getDirection(), extra, true);
        // Vor Weltzugriffen Grenzen prüfen; ein abgelehnter Bau lässt den bisherigen Auftrag bestehen.
        try { CompanionSchematics.validatePlacement(sl, blocks); }
        catch (IllegalArgumentException ex) { return CompanionTexts.tr("build.out_of_world"); }
        for (BlockPos pos : blocks.keySet())
            if (sl.getBlockEntity(pos) != null)
                return CompanionTexts.tr("build.machine_occupied", CompanionTexts.literal(pos.toShortString()));
        CompanionStructures.addFoundation(sl, blocks, net.minecraft.world.level.block.Blocks.COBBLESTONE);
        var job = buildJob(sl, s.name(), blocks, false);
        if (job == null) return CompanionTexts.tr("build.out_of_world");
        c.cancelAll();
        c.build = job;
        c.addTask(Task.build());
        return CompanionTexts.tr("build.machine", blueprint.title(), s.sizeX(), s.sizeY(), s.sizeZ(),
                CompanionTexts.buildingMaterials(c), blueprint.help());
    }

    private static final java.util.Set<String> FILLER = java.util.Set.of("bau", "baue", "bauen", "errichte", "errichten",
            "das", "die", "den", "ein", "eine", "mir", "uns", "hier", "bitte", "schematic", "schematics", "mit", "luft",
            "begleiter", "dort", "da", "gedreht", "drehung");

    /** "Bau <name>": passendes Schematic suchen und als Bauauftrag vor dem Spieler platzieren. null = kein Schematic gemeint. */
    static Component orderSchematicReply(ServerPlayer p, CompanionEntity c, List<String> tok) {
        List<String> words = tok.stream().filter(t -> !FILLER.contains(t) && !t.matches("\\d+")).toList();
        return orderSchematicReply(p, c, words.isEmpty() ? null : String.join(" ", words), tok.contains("luft"), tok);
    }

    static Component orderSchematicReply(ServerPlayer p, CompanionEntity c, String query, boolean withAir, List<String> tok) {
        if (query == null) return null;
        net.minecraft.world.level.block.Rotation extra = tok.contains("180") ? net.minecraft.world.level.block.Rotation.CLOCKWISE_180
                : tok.contains("90") ? net.minecraft.world.level.block.Rotation.CLOCKWISE_90
                : tok.contains("270") ? net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90
                : net.minecraft.world.level.block.Rotation.NONE;
        var machine = CompanionMachines.find(query);
        if (machine != null) return startMachineReply(p, c, machine, extra);
        java.nio.file.Path file = CompanionSchematics.find(query);
        if (file == null) return null; // vielleicht "Bau ein Haus" o.ä.
        return startSchematicReply(p, c, file, withAir, extra);
    }

    public static Component startSchematicReply(ServerPlayer p, CompanionEntity c, java.nio.file.Path file, boolean withAir,
                                        net.minecraft.world.level.block.Rotation extra) {
        CompanionSchematics.Schematic s;
        try {
            s = CompanionSchematics.load((ServerLevel) c.level(), file);
        } catch (Exception ex) {
            c.logError(CompanionTexts.tr("log.schematic.error", CompanionTexts.literal(file.getFileName().toString()), CompanionTexts.literal(String.valueOf(ex))));
            return CompanionTexts.tr("schematic.unreadable", CompanionTexts.literal(file.getFileName().toString()), CompanionTexts.literal(String.valueOf(ex.getMessage())));
        }
        return startSchematicReply(p, c, s, withAir, extra);
    }

    static Component startSchematicReply(ServerPlayer p, CompanionEntity c, CompanionSchematics.Schematic s,
                                         boolean withAir, net.minecraft.world.level.block.Rotation extra) {
        if (s.blocks().isEmpty()) return CompanionTexts.tr("schematic.empty", CompanionTexts.buildName(s.name()));
        if (s.blocks().size() > CompanionSchematics.MAX_BLOCKS)
            return CompanionTexts.tr("schematic.large", CompanionTexts.buildName(s.name()), s.blocks().size());
        var placed = CompanionSchematics.placeFull(s, p.blockPosition(), p.getDirection(), extra, withAir);
        CompanionBuilder.BuildJob job = buildJob((ServerLevel) c.level(), s.name(), placed.blocks(), false);
        if (job == null) return CompanionTexts.tr("build.out_of_world");
        job.data.putAll(placed.data());
        job.entities.addAll(placed.entities());
        c.cancelAll();
        c.build = job;
        c.addTask(Task.build());
        Component mats = CompanionTexts.buildingMaterials(c);
        Component reply = CompanionTexts.tr("build.schematic", CompanionTexts.buildName(s.name()), s.sizeX(), s.sizeY(), s.sizeZ(), c.build.total(), mats);
        return withAirHint(reply, s, withAir);
    }

    /** Große Schematics speichern keine Luft: "mit Luft" kann ihren Umriss nicht freiräumen – das offen sagen. */
    static Component withAirHint(Component reply, CompanionSchematics.Schematic s, boolean withAir) {
        if (!withAir || !CompanionSchematics.sparse(s)) return reply;
        return Component.empty().append(reply).append(" ").append(CompanionTexts.tr("schematic.air_skipped"));
    }

    /** "Warum machst du das?" – aktuelles Ziel und die dringendsten Bedürfnisse. */
    static Component explainNeedsReply(CompanionEntity c) {
        var top = CompanionNeeds.evaluate(c);
        List<Component> scores = top.stream().limit(3).map(score -> (Component) CompanionTexts.tr("planner.explain.score",
                CompanionTexts.tr("need." + score.need().name().toLowerCase(java.util.Locale.ROOT)),
                score.value(), score.reasonText())).toList();
        return scores.isEmpty() ? CompanionTexts.tr("planner.explain.idle", CompanionTexts.needs(c))
                : CompanionTexts.tr("planner.explain", CompanionTexts.needs(c), CompanionTexts.join(scores, ", "));
    }

    /** "Wo ist Eisen / Wasser / ein Dorf / meine Kiste mit Brot / wo bist du gestorben?" aus dem Gedächtnis. */
    static String whereIs(ServerPlayer p, CompanionEntity c, List<String> tok) {
        return whereIs(p, c, tok, Resources.byNoun(tok), itemWord(tok));
    }

    static String whereIs(ServerPlayer p, CompanionEntity c, List<String> tok, Resources.Res res, Item wanted) {
        Component reply = whereIsReply(p, c, tok, res, wanted);
        return reply == null ? null : reply.getString();
    }

    static Component whereIsReply(ServerPlayer p, CompanionEntity c, List<String> tok) {
        return whereIsReply(p, c, tok, Resources.byNoun(tok), itemWord(tok));
    }

    static Component whereIsReply(ServerPlayer p, CompanionEntity c, List<String> tok, Resources.Res res, Item wanted) {
        ServerLevel sl = (ServerLevel) c.level();
        BlockPos from = p.blockPosition();
        CompanionMemory mem = c.memory;
        CompanionMemory.Place named = mem.waypointIn(normalize(String.join(" ", tok)), sl);
        if (named != null) return CompanionTexts.tr("memory.where.waypoint", CompanionTexts.literal(named.key), CompanionTexts.location(named, from));
        CompanionMemory.Kind kind;
        String key = null;
        Component what;
        if (res != null && CompanionMemory.isOre(res)) { kind = CompanionMemory.Kind.ORE; key = res.key(); what = CompanionTexts.resource(key); }
        else if (res == Resources.WOOD || anyStart(tok, "wald", "forest")) { kind = CompanionMemory.Kind.TREE; what = CompanionTexts.tr("memory.query.tree"); }
        else if (anyStart(tok, "wasser", "meer", "water", "lake", "ocean") || any(tok, "see", "seen")) { kind = CompanionMemory.Kind.WATER; what = CompanionTexts.tr("memory.query.water"); }
        else if (anyStart(tok, "lava")) { kind = CompanionMemory.Kind.LAVA; what = CompanionTexts.tr("memory.query.lava"); }
        else if (anyStart(tok, "dorf", "doerf", "villager", "dorfbewohner", "village")) { kind = CompanionMemory.Kind.VILLAGE; what = CompanionTexts.tr("memory.query.village"); }
        else if (anyStart(tok, "spawner")) { kind = CompanionMemory.Kind.SPAWNER; what = CompanionTexts.tr("memory.query.spawner"); }
        else if (anyStart(tok, "portal")) { kind = CompanionMemory.Kind.PORTAL; what = CompanionTexts.tr("memory.query.portal"); }
        else if (anyStart(tok, "gestorben", "tod", "gestorb", "sachen", "died", "death", "stuff")) { kind = CompanionMemory.Kind.DEATH; what = CompanionTexts.tr("memory.query.death"); }
        else if (anyStart(tok, "gefahr", "gefaehrlich", "danger")) { kind = CompanionMemory.Kind.DANGER; what = CompanionTexts.tr("memory.query.danger"); }
        else {
            if (wanted != null) {
                CompanionMemory.Place chest = mem.chestWith(wanted, sl, from, 30_000_000);
                Component name = new ItemStack(wanted).getHoverName();
                if (chest == null) return CompanionTexts.tr("memory.where.no_chest", name);
                return CompanionTexts.tr("memory.where.chest", chest.itemCount(wanted), name, CompanionTexts.location(chest, from));
            }
            return null;
        }
        List<CompanionMemory.Place> found = mem.find(kind, key, sl, from);
        if (found.isEmpty()) return CompanionTexts.tr("memory.where.unknown", what);
        CompanionMemory.Place first = found.getFirst();
        Component more = found.size() > 1 ? CompanionTexts.tr("memory.where.more", found.size()) : Component.empty();
        Component amount = kind == CompanionMemory.Kind.ORE && first.count > 1 ? CompanionTexts.tr("memory.where.amount", first.count) : Component.empty();
        return CompanionTexts.tr("memory.where.known", what, amount, CompanionTexts.location(first, from), more);
    }

    /**
     * Zielplaner: count Stück von item beschaffen (Kiste, abbauen, schmelzen, craften, Mobs – der günstigste Weg)
     * und dem Spieler bringen.
     */
    public static String orderObtain(CompanionEntity c, net.minecraft.world.item.Item item, int count, boolean toOwner) {
        Component reply = orderObtainReply(c, item, count, toOwner);
        return reply == null ? null : reply.getString();
    }

    public static Component orderObtainReply(CompanionEntity c, net.minecraft.world.item.Item item, int count, boolean toOwner) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        Component name = new ItemStack(item).getHoverName();
        CompanionPlanner.Plan plan = CompanionPlanner.obtain(c, item, count);
        if (plan == null) return CompanionTexts.tr("obtain.unknown", name);
        c.cancelAll();
        for (Task t : plan.tasks()) c.addTask(t);
        if (toOwner) c.addTask(Task.give("item:" + id, count));
        if (plan.tasks().isEmpty()) return CompanionTexts.tr("obtain.owned", count, name);
        Component how = CompanionTexts.join(plan.tasks().stream().limit(5).map(CompanionTexts::task).toList(), " → ")
                .copy().append(plan.tasks().size() > 5 ? " …" : "");
        int sec = (int) Math.round(plan.cost());
        Component time = CompanionTexts.tr(sec < 90 ? "time.seconds" : "time.minutes", sec < 90 ? sec : (sec + 30) / 60);
        c.logInfo(CompanionTexts.tr("log.obtain.plan", count, name, how, sec));
        return CompanionTexts.tr("obtain.plan", count, name, how, time);
    }

    private static final Map<String, String> MATERIALS = Map.of("holz", "wooden", "stein", "stone", "eisen", "iron",
            "eiser", "iron", "gold", "golden", "diamant", "diamond", "netherit", "netherite", "leder", "leather");
    private static final Map<String, String> TOOLS = Map.ofEntries(Map.entry("spitzhacke", "pickaxe"),
            Map.entry("axt", "axe"), Map.entry("schwert", "sword"), Map.entry("schaufel", "shovel"),
            Map.entry("feldhacke", "hoe"), Map.entry("helm", "helmet"), Map.entry("brustpanzer", "chestplate"),
            Map.entry("harnisch", "chestplate"), Map.entry("hose", "leggings"), Map.entry("beinschutz", "leggings"),
            Map.entry("stiefel", "boots"));

    static boolean isTool(net.minecraft.world.item.Item item) {
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if (!"minecraft".equals(key.getNamespace())) return false;
        String id = key.getPath();
        for (String t : TOOLS.values()) if (id.endsWith("_" + t)) return true;
        return false;
    }

    /** Item aus dem Satz: Werkzeug/Rüstung mit Material ("Eisenspitzhacke", "eiserne Axt") oder ein bekanntes Wort. */
    static net.minecraft.world.item.Item itemWord(List<String> tok) {
        for (int i = 0; i < tok.size(); i++) {
            String t = tok.get(i);
            for (var tool : TOOLS.entrySet()) {
                int at = t.indexOf(tool.getKey());
                if (at < 0 || at + tool.getKey().length() < t.length() - 1) continue; // Plural "-n"/"-e" erlaubt
                String mat = null;
                String prefix = t.substring(0, at);
                for (var m : MATERIALS.entrySet()) if (!prefix.isEmpty() && prefix.startsWith(m.getKey())) mat = m.getValue();
                if (mat == null && i > 0) for (var m : MATERIALS.entrySet()) if (tok.get(i - 1).startsWith(m.getKey())) mat = m.getValue();
                if (mat == null) mat = tool.getValue().equals("helmet") || tool.getValue().equals("chestplate")
                        || tool.getValue().equals("leggings") || tool.getValue().equals("boots") ? "iron" : "stone";
                if (mat.equals("leather") && !(tool.getValue().equals("helmet") || tool.getValue().equals("chestplate")
                        || tool.getValue().equals("leggings") || tool.getValue().equals("boots"))) mat = "wooden";
                net.minecraft.world.item.Item it = CompanionCraft.item(mat + "_" + tool.getValue());
                if (it != net.minecraft.world.item.Items.AIR) return it;
            }
        }
        for (String t : tok) {
            if (t.length() < 3) continue;
            net.minecraft.world.item.Item it = itemFromWord(t);
            if (it != null) return it;
        }
        return null;
    }

    /** Deutsches oder englisches Wort -> Item (z.B. "brot", "diamanten", "iron_ingot"). */
    private static net.minecraft.world.item.Item itemFromWord(String w) {
        Map<String, String> de = Map.ofEntries(Map.entry("brot", "bread"), Map.entry("diamant", "diamond"),
                Map.entry("eisenbarren", "iron_ingot"), Map.entry("goldbarren", "gold_ingot"), Map.entry("fackel", "torch"),
                Map.entry("kohle", "coal"), Map.entry("apfel", "apple"), Map.entry("aepfel", "apple"),
                Map.entry("steak", "cooked_beef"), Map.entry("pfeil", "arrow"), Map.entry("bogen", "bow"),
                Map.entry("perle", "ender_pearl"), Map.entry("lohenrute", "blaze_rod"), Map.entry("smaragd", "emerald"),
                Map.entry("weizen", "wheat"), Map.entry("samen", "wheat_seeds"), Map.entry("glas", "glass"),
                Map.entry("eimer", "bucket"), Map.entry("redstone", "redstone"), Map.entry("obsidian", "obsidian"),
                Map.entry("kiste", "chest"), Map.entry("ofen", "furnace"), Map.entry("werkbank", "crafting_table"),
                Map.entry("leiter", "ladder"), Map.entry("zaun", "oak_fence"), Map.entry("tuer", "oak_door"),
                Map.entry("schild", "shield"), Map.entry("stock", "stick"), Map.entry("stoeck", "stick"),
                Map.entry("brett", "oak_planks"), Map.entry("kompass", "compass"), Map.entry("schere", "shears"),
                Map.entry("angel", "fishing_rod"), Map.entry("feuerzeug", "flint_and_steel"), Map.entry("kolben", "piston"),
                Map.entry("verstaerker", "repeater"), Map.entry("hebel", "lever"), Map.entry("trichter", "hopper"),
                Map.entry("schiene", "rail"), Map.entry("enderauge", "ender_eye"), Map.entry("papier", "paper"),
                Map.entry("buch", "book"), Map.entry("buech", "book"), Map.entry("karotte", "carrot"),
                Map.entry("kartoffel", "potato"), Map.entry("smaragde", "emerald"), Map.entry("fass", "barrel"), Map.entry("steinziegel", "stone_bricks"),
                Map.entry("faden", "string"), Map.entry("feder", "feather"), Map.entry("leder", "leather"),
                Map.entry("feuerstein", "flint"), Map.entry("goldapfel", "golden_apple"), Map.entry("knochen", "bone"));
        for (var en : de.entrySet()) if (w.startsWith(en.getKey())) return CompanionCraft.item(en.getValue());
        net.minecraft.world.item.Item it = CompanionCraft.item(w);
        return it == net.minecraft.world.item.Items.AIR ? null : it;
    }

    /** Bauauftrag anhalten oder fortsetzen (Chat "Pause"/"weiter", Menü-Knopf). */
    public static String pauseBuild(CompanionEntity c, boolean pause) {
        Component reply = pauseBuildReply(c, pause);
        return reply == null ? null : reply.getString();
    }

    public static Component pauseBuildReply(CompanionEntity c, boolean pause) {
        if (c.build == null) return CompanionTexts.tr("build.none");
        c.build.paused = pause;
        if (pause) {
            c.cancelAll();
            return CompanionTexts.tr("build.paused", CompanionTexts.buildName(c.build.name), c.build.percent());
        }
        c.resume();
        c.buildRetryAt = 0;
        return CompanionTexts.tr("reply.build.resume", CompanionTexts.buildName(c.build.name), c.build.percent());
    }

    /** "Weiter" aus Chat, Menü und Befehl: angehaltene Arbeit und einen pausierten Bau fortsetzen. */
    public static net.minecraft.network.chat.Component resumeReply(CompanionEntity c) {
        c.resume();
        if (c.build != null && c.build.paused) {
            c.build.paused = false;
            c.buildRetryAt = 0;
            return CompanionTexts.tr("reply.build.resume", CompanionTexts.buildName(c.build.name), c.build.percent());
        }
        return CompanionTexts.tr("reply.resume");
    }

    public static Component cancelBuildReply(CompanionEntity c) {
        if (c.build == null) return CompanionTexts.tr("build.none");
        String name = c.build.name;
        c.cancelOrders();
        return CompanionTexts.tr("build.cancelled", CompanionTexts.buildName(name));
    }

    static Map<net.minecraft.world.item.Item, Integer> top(Map<net.minecraft.world.item.Item, Integer> m, int n) {
        Map<net.minecraft.world.item.Item, Integer> out = new java.util.LinkedHashMap<>();
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public static Component orderHuntReply(CompanionEntity c) {
        c.cancelAll();
        c.addTask(Task.hunt(0));
        return CompanionTexts.tr("reply.hunt");
    }

    public static Component orderSmeltReply(CompanionEntity c) {
        c.cancelAll();
        c.addTask(Task.smeltAnything());
        return CompanionTexts.tr("reply.smelt");
    }

    public static Component orderGiveAllReply(CompanionEntity c) {
        c.cancelAll();
        c.addTask(Task.give(null, 0));
        return CompanionTexts.tr("reply.give");
    }

    public static Component orderTidyReply(CompanionEntity c) {
        c.cancelAll();
        c.queueTidy();
        return CompanionTexts.tr(c.getHome() == null ? "order.tidy.owner" : "order.tidy.store");
    }

    // ------------------------------------------------------------------ Alltag: Feld, Zucht, Pflege, Handel, Wegpunkte, Ausrüstung

    /** Feld ernten und neu pflanzen: am Feld beim Spieler, sonst am eigenen Lager/Haus. */
    public static Component orderFarmReply(ServerPlayer p, CompanionEntity c) {
        ServerLevel sl = (ServerLevel) c.level();
        BlockPos center = p != null && CompanionJobs.hasFarmland(sl, p.blockPosition(), 12) ? p.blockPosition()
                : CompanionJobs.farmCenter(c);
        if (!CompanionJobs.hasFarmland(sl, center, CompanionJobs.FARM_RADIUS)) return CompanionTexts.tr("order.farm.none");
        int ripe = CompanionJobs.countMature(sl, center, CompanionJobs.FARM_RADIUS);
        c.cancelAll();
        c.addTask(Task.farm(center));
        c.addTask(Task.collect(false));
        if (c.getHome() != null) c.addTask(Task.deliver(true));
        return ripe > 0 ? CompanionTexts.tr("order.farm.harvest", ripe) : CompanionTexts.tr("order.farm.sow");
    }

    /** Tiere paarweise füttern; ab jetzt hält er die Herde (lässt beim Jagen immer ein Zuchtpaar übrig). */
    public static Component orderBreedReply(CompanionEntity c, String species, int pairs) {
        c.cancelAll();
        c.herdKeeping = true;
        c.addTask(Task.breed(species, pairs));
        return CompanionTexts.tr("order.breed", CompanionTexts.animals(species), pairs > 0 ? CompanionTexts.tr("order.breed.pairs", pairs) : Component.empty());
    }

    public static Component orderRepairReply(CompanionEntity c) {
        c.cancelAll();
        c.craftFailedUntil.remove("#repair");
        c.addTask(Task.repair(90));
        return CompanionTexts.tr("order.repair");
    }

    /** Kaufen (item != null, n Stück, 0 = so viele wie es geht) oder überzähliges Zeug gegen Smaragde verkaufen. */
    public static Component orderTradeReply(CompanionEntity c, net.minecraft.world.item.Item item, int count, boolean toOwner) {
        c.cancelAll();
        String id = item == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        c.addTask(Task.trade(id, count));
        if (item == null) return CompanionTexts.tr("order.trade.sell");
        Component name = new ItemStack(item).getHoverName();
        if (toOwner) c.addTask(Task.give("item:" + id, 0));
        return CompanionTexts.tr("order.trade.buy", count > 0 ? CompanionTexts.tr("materials.entry", count, name) : name);
    }

    public static Component orderGearUpReply(CompanionEntity c, List<String> tok) {
        int tier = CompanionJobs.gearTarget(c, tok);
        return orderGearUpReply(c, tier);
    }

    static Component orderGearUpReply(CompanionEntity c, int tier) {
        Component label = CompanionTexts.tier(tier);
        if (CompanionJobs.nextGearPiece(c, tier, 0) == null) return CompanionTexts.tr("order.gear.done", label);
        c.cancelAll();
        c.addTask(CompanionJobs.gearTask(tier, 0, null, 0, 0));
        return CompanionTexts.tr("order.gear.start", label, CompanionJobs.gearProgress(c, tier));
    }

    /** Wegpunkte setzen, auflisten, löschen und hingehen. null = kein Wegpunkt-Befehl. */
    static String waypointCommand(ServerPlayer p, CompanionEntity c, List<String> tok, String text) {
        Component reply = waypointCommandReply(p, c, tok, text);
        return reply == null ? null : reply.getString();
    }

    static Component waypointCommandReply(ServerPlayer p, CompanionEntity c, List<String> tok, String text) {
        var parsed = dev.begleiter.chat.ChatParser.parse(text, "de_de");
        if (parsed == null) return null;
        for (var intent : parsed.intents()) {
            switch (intent) {
                case WAYPOINT_SAVE, WAYPOINT_FORGET, WAYPOINT_LIST, WAYPOINT_GO -> {
                    Component reply = CompanionChatActions.waypoint(p, c, intent, dev.begleiter.chat.Sentence.of(text), parsed.params());
                    if (reply != null) return reply;
                }
                default -> { }
            }
        }
        return null;
    }

    /** Terraforming am Spieler als Bauauftrag (abtragen/auffüllen über den Bauplaner). */
    public static Component orderTerraformReply(ServerPlayer p, CompanionEntity c, CompanionTerraform.Mode mode, List<String> tok) {
        Block filler = tok.stream().anyMatch(t -> t.startsWith("stein") || t.startsWith("bruchstein"))
                ? net.minecraft.world.level.block.Blocks.COBBLESTONE : net.minecraft.world.level.block.Blocks.DIRT;
        return orderTerraformReply(p, c, mode, tok, filler);
    }

    static Component orderTerraformReply(ServerPlayer p, CompanionEntity c, CompanionTerraform.Mode mode, List<String> tok, Block filler) {
        ServerLevel sl = (ServerLevel) c.level();
        CompanionTerraform.Plan plan = CompanionTerraform.planFor(sl, mode, p.blockPosition(), p.getDirection(), tok, filler);
        if (plan.blocks().isEmpty())
            return CompanionTexts.tr(plan.skipped() > 0 ? "terrain.built_only" : "terrain.nothing");
        CompanionBuilder.BuildJob job = buildJob(sl, CompanionTexts.terrainJobName(mode, plan.sizeX(), plan.sizeZ()),
                plan.blocks(), true);
        if (job == null) return CompanionTexts.tr("build.out_of_world");
        c.cancelAll();
        c.build = job;
        c.addTask(Task.build());
        Component mats = Component.empty();
        if (plan.fill() > 0 && !c.creativeHelper()) {
            var need = CompanionBuilder.remaining(sl, c.build);
            mats = CompanionTexts.tr("terrain.materials", CompanionTexts.materials(need));
        }
        return CompanionTexts.tr("order.terrain", CompanionTexts.terrain(plan), mats);
    }

    /** Tür einsetzen: in die Öffnung des eigenen Hauses, sonst dort, wo der Spieler hinschaut. */
    public static Component orderDoorReply(ServerPlayer p, CompanionEntity c) {
        ServerLevel sl = (ServerLevel) c.level();
        BlockPos at = null;
        net.minecraft.core.Direction facing = p.getDirection();
        HitResult hit = p.pick(6.0, 0f, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos b = bhr.getBlockPos();
            BlockPos cand = bhr.getDirection() == net.minecraft.core.Direction.UP ? b.above() : b.relative(bhr.getDirection());
            if (CompanionDoors.canPlace(sl, cand)) at = cand;
            else if (CompanionDoors.canPlace(sl, cand.below())) at = cand.below();
        }
        if (at == null && c.houseNeedsDoor()) { at = c.houseDoor().getFirst(); facing = net.minecraft.core.Direction.NORTH; }
        if (at == null) return CompanionTexts.tr("order.door.look");
        c.cancelAll();
        c.addTask(Task.door(at, facing.getSerializedName()));
        return CompanionTexts.tr(CompanionDoors.doorSlot(c) >= 0 ? "order.door.place" : "order.door.craft", at.toShortString());
    }

    public static Component orderSortReply(ServerPlayer p, CompanionEntity c) {
        BlockPos center = c.getHome() != null ? c.getHome() : c.findNearestContainer(c.blockPosition(), 24);
        if (center == null) center = c.findNearestContainer(p.blockPosition(), 24);
        if (center == null) return CompanionTexts.tr("order.sort.none");
        c.cancelAll();
        if (c.getHome() != null) {
            c.addTask(Task.collect(true));
            c.addTask(Task.deliver(false));
        }
        c.addTask(Task.sort(center));
        return CompanionTexts.tr("order.sort");
    }

    public static Component orderSetHomeReply(ServerPlayer p, CompanionEntity c) {
        BlockPos pos = null;
        HitResult hit = p.pick(6.0, 0f, false);
        ServerLevel sl = p.level();
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                && c.containerAt(sl, bhr.getBlockPos()) != null) pos = bhr.getBlockPos();
        if (pos == null) pos = c.findNearestContainer(p.blockPosition(), 8);
        if (pos == null) return CompanionTexts.tr("order.home.look");
        c.setHome(pos);
        return CompanionTexts.tr("order.home.set", pos.toShortString());
    }

    // ================================================================== Überlebens-Planer

    static Task gather(Resources.Res r, int n) {
        return Task.gather(r.key(), n);
    }

    static Task craft(String id) {
        return Task.craft(id, 1);
    }

    static Task smelt() {
        return Task.smeltAnything();
    }

    static void stage(CompanionEntity e, String msg) { stage(e, Component.literal(msg)); }

    static void stage(CompanionEntity e, Component msg) {
        e.noteStage(msg);
        if (!msg.equals(e.stageComponent())) {
            e.setStage(msg);
            e.tell(msg);
            e.logInfo(CompanionTexts.tr("log.planner", msg));
        }
    }

    static final int RODS = 7;    // 14 Lohenstaub -> 14 Enderaugen
    static final int PEARLS = 14; // 12 Rahmen + Augen zum Werfen/Reserve

    /** Fehlt Lohenstaub für die noch fehlenden Enderaugen (12 Rahmen)? */
    static boolean needsBlaze(CompanionEntity e) {
        if (e.endPortalPos != null) return false;
        int needEyes = Math.max(0, 12 - CompanionCraft.count(e, "ender_eye"));
        int powder = CompanionCraft.count(e, "blaze_rod") * 2 + CompanionCraft.count(e, "blaze_powder");
        return powder < needEyes;
    }

    static final int ARROWS = 32;

    static boolean dragonDefeated(CompanionEntity e) {
        var server = e.level().getServer();
        if (server == null) return false;
        ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
        return end != null && end.getDragonFight() != null && end.getDragonFight().hasPreviouslyKilledDragon();
    }

    /** Im End: erst die sichtbaren Kristalle abschießen, dann den Drachen bekämpfen (Nahkampf am Brunnen, sonst Pfeile). */
    static void endPlan(CompanionEntity e) {
        if (dragonDefeated(e)) {
            if (!e.isStage("planner.stage.dragon_defeated")) {
                stage(e, CompanionTexts.tr("planner.stage.dragon_defeated"));
            }
            e.plannerCooldownUntil = e.tickCount + 400;
            return;
        }
        boolean bow = CompanionCraft.owns(e, "bow") && CompanionCraft.count(e, "arrow") > 0;
        if (bow && !e.endCrystals(160).isEmpty() && e.crystalRounds < 3) {
            e.crystalRounds++;
            stage(e, CompanionTexts.tr("planner.stage.crystals"));
            e.addTask(Task.crystals());
            return;
        }
        stage(e, CompanionTexts.tr("planner.stage.dragon_fight"));
        e.addTask(Task.fight(null, "ender_dragon", null, 1));
    }

    static int pearlsAndEyes(CompanionEntity e) {
        return CompanionCraft.count(e, "ender_pearl") + CompanionCraft.count(e, "ender_eye");
    }

    static int blazeRods(CompanionEntity e) {
        return CompanionCraft.count(e, "blaze_rod") + CompanionCraft.count(e, "blaze_powder") / 2;
    }

    /** Bereit für die Enderman-Jagd: Portal steht, Lohenruten da, Rüstung an, noch zu wenig Perlen. */
    static boolean wantsPearlHunt(CompanionEntity e) {
        return e.getPortalPos() != null && e.endPortalPos == null && !needsBlaze(e) && pearlsAndEyes(e) < PEARLS
                && CompanionCraft.owns(e, "iron_chestplate");
    }

    /**
     * Im Nether. Auf dem Ausflug: Festung finden, an einem Lohen-Spawner Lohen bekämpfen, dann zurück durchs Portal.
     * Ohne Ausflug: beim Besitzer bleiben.
     */
    static void netherPlan(CompanionEntity e) {
        if (e.netherPortal == null) e.netherPortal = e.nearestPortal(8); // Ankunftsportal merken
        if (!e.netherTrip) {
            stage(e, CompanionTexts.tr("planner.stage.stay_owner"));
            e.plannerCooldownUntil = e.tickCount + 200;
            return;
        }
        if (blazeRods(e) >= RODS) {
            stage(e, CompanionTexts.tr("planner.stage.return_portal", blazeRods(e)));
            if (e.netherPortal == null) {
                e.problem(dev.begleiter.debug.BotDebug.EventKind.PORTAL, CompanionTexts.tr("log.problem.portal_lost"));
                e.netherTrip = false; // dann folgt er dem Besitzer wieder (notfalls direkt)
                return;
            }
            e.addTask(Task.travel(e.netherPortal));
            e.addTask(Task.moveTo(e.netherPortal, 1));
            return;
        }
        if (e.fortressPos == null) {
            e.fortressPos = e.locateFortress();
            if (e.fortressPos == null) {
                e.problem(CompanionTexts.tr("log.problem.no_fortress"));
                e.netherTrip = false;
                return;
            }
            e.tell(CompanionTexts.tr("planner.stage.fortress_found", e.fortressPos.toShortString()));
        }
        BlockPos spawner = e.nearestSpawner(24);
        if (spawner == null) spawner = e.nearestLoadedSpawner(96);
        double dx = e.fortressPos.getX() - e.getX(), dz = e.fortressPos.getZ() - e.getZ();
        if (spawner == null && dx * dx + dz * dz > 40 * 40) {
            stage(e, CompanionTexts.tr("planner.stage.travel_fortress"));
            e.addTask(Task.travel(e.fortressPos));
            return;
        }
        stage(e, CompanionTexts.tr("planner.stage.fight_blazes", blazeRods(e), RODS));
        e.addTask(Task.fight(spawner, "blaze", null, RODS));
    }

    /**
     * Nachts an der Oberfläche: ins Haus gehen und die Türöffnung zumauern; morgens wieder öffnen.
     * true = Planer ist für diesen Durchlauf fertig.
     */
    static boolean nightStep(CompanionEntity e, int pick) {
        BlockPos inside = e.houseInside();
        if (inside == null || !(e.level() instanceof ServerLevel sl)) return false;
        if (wantsPearlHunt(e) && sl.isDarkOutside()) return false; // nachts kommen die Endermen
        boolean night = sl.isDarkOutside();
        BlockPos doorAt = e.houseDoor().getFirst();
        boolean realDoor = CompanionDoors.isDoor(sl, doorAt);
        if (!night) {
            if (e.doorClosed) {
                e.doorClosed = false;
                if (realDoor) { stage(e, CompanionTexts.tr("planner.stage.good_morning")); return false; } // die Tür öffnet er einfach beim Rausgehen
                stage(e, CompanionTexts.tr("planner.stage.good_morning_open"));
                for (BlockPos p : e.houseDoor()) if (!sl.getBlockState(p).isAir()) e.addTask(Task.breakAt(p, null));
                // statt Loch: gleich eine richtige Tür einsetzen
                e.addTask(Task.door(doorAt, "north"));
                return true;
            }
            return false;
        }
        boolean underground = !sl.canSeeSky(e.blockPosition()) && e.blockPosition().getY() < inside.getY() - 6;
        if (underground) return false;
        if (!e.isInsideHouse()) {
            stage(e, CompanionTexts.tr("planner.stage.night_home"));
            e.addTask(Task.moveTo(inside, 1));
            return true;
        }
        if (!e.doorClosed && realDoor) {
            stage(e, CompanionTexts.tr("planner.stage.close_door"));
            CompanionDoors.setOpen(sl, e, doorAt, false);
            e.openedDoors.remove(doorAt);
            e.doorClosed = true;
            return true;
        }
        if (!e.doorClosed && CompanionDoors.doorSlot(e) >= 0 && CompanionDoors.canPlace(sl, doorAt)) {
            stage(e, CompanionTexts.tr("planner.stage.place_door"));
            e.addTask(Task.door(doorAt, "north"));
            return true;
        }
        Item block = e.scaffoldItem();
        if (!e.doorClosed && block != null) {
            stage(e, CompanionTexts.tr("planner.stage.close_door"));
            for (BlockPos p : e.houseDoor())
                if (sl.getBlockState(p).isAir()) e.addTask(Task.place(p, Block.byItem(block)));
            e.doorClosed = true;
            return true;
        }
        stage(e, CompanionTexts.tr("planner.stage.wait_day"));
        e.noteWaiting();
        e.plannerCooldownUntil = e.tickCount + 200;
        return true;
    }

    /**
     * Wird aufgerufen, wenn der Begleiter nichts zu tun hat. Im End/Nether eigene Abläufe, in der Oberwelt entscheiden
     * die Bedürfnisse ({@link CompanionNeeds}), was als Nächstes dran ist.
     */
    public static void plan(CompanionEntity e) {
        if (e.getOwnerAnywhere() == null) return; // auch allein im Nether weiterplanen
        e.plannerCooldownUntil = e.tickCount + 20;

        // Altes Gerüst wegräumen
        if (e.hasScaffold()) {
            stage(e, CompanionTexts.tr("planner.stage.clear_scaffold"));
            e.addTask(Task.descend());
            return;
        }

        // End: Kristalle und Drache. Nether: eigener Ausflug (Lohenruten) – sonst beim Besitzer bleiben
        if (e.level().dimension() == net.minecraft.world.level.Level.END) {
            endPlan(e);
            return;
        }
        if (!e.inOverworld()) {
            netherPlan(e);
            return;
        }
        if (e.netherTrip && blazeRods(e) >= RODS) {
            e.netherTrip = false;
            stage(e, CompanionTexts.tr("planner.stage.return_nether", blazeRods(e)));
        }

        // Bedürfnisse bewerten und das dringendste umsetzen (Überleben, Hunger, Ordnung, Ausrüstung, Basis, Fortschritt, Vorräte)
        CompanionNeeds.decide(e);
    }
}
