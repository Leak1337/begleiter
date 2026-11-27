package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/**
 * Alltags-Arbeiten wie ein zweiter Spieler: Feld ernten und neu pflanzen, Kisten nach Sorten ordnen, Tiere züchten,
 * Werkzeug pflegen, mit Dorfbewohnern handeln, Wegpunkte und Ausrüstungsziele. Hier steckt die Logik ohne Laufen;
 * das Hinlaufen übernimmt {@link CompanionTaskGoal}.
 */
public final class CompanionJobs {
    private CompanionJobs() {}

    static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }
    static String id(Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }

    // ================================================================== 1. Farm

    public static final int FARM_RADIUS = 16;
    /** Feldfrucht-Block -> Saatgut-Item. */
    static final Map<String, String> SEED_FOR = Map.of("minecraft:wheat", "minecraft:wheat_seeds", "minecraft:carrots", "minecraft:carrot",
            "minecraft:potatoes", "minecraft:potato", "minecraft:beetroots", "minecraft:beetroot_seeds");

    public static boolean isMatureCrop(BlockState s) {
        return SEED_FOR.containsKey(id(s.getBlock())) && s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s);
    }

    /** Freies Ackerland (Luft darüber), auf das er etwas pflanzen könnte. */
    static boolean isEmptyFarmland(ServerLevel sl, BlockPos farmland) {
        return sl.getBlockState(farmland).is(Blocks.FARMLAND) && sl.getBlockState(farmland.above()).isAir();
    }

    /** Saatgut, das er dabei hat (Slot), bevorzugt die Sorte des Feldes; -1 = keins. */
    public static int seedSlot(CompanionEntity e, String prefer) {
        if (prefer != null) {
            Item item = CompanionCraft.item(prefer);
            int s = e.findItem(item);
            if (s >= 0 && e.work.permitsInput(item, 1)) return s;
            if (e.work.is(CompanionWork.Kind.FARM)) return -1;
        }
        for (String seed : List.of("wheat_seeds", "carrot", "potato", "beetroot_seeds")) {
            Item item = CompanionCraft.item(seed);
            int s = e.findItem(item);
            if (s >= 0 && e.work.permitsInput(item, 1)) return s;
        }
        return -1;
    }

    /** Nächste Stelle zum Ernten (reife Pflanze) oder Pflanzen (Luft über Ackerland, Saatgut dabei). */
    public static BlockPos nextFarmTarget(ServerLevel sl, CompanionEntity e, BlockPos center, Set<BlockPos> skip) {
        boolean seeds = seedSlot(e, null) >= 0;
        BlockPos me = e.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        var zone = CompanionZones.work(e, CompanionZones.Kind.FIELDS);
        for (BlockPos p : BlockPos.betweenClosed(zone == null ? center.offset(-FARM_RADIUS, -3, -FARM_RADIUS) : zone.min(),
                zone == null ? center.offset(FARM_RADIUS, 3, FARM_RADIUS) : zone.max())) {
            if (!sl.hasChunkAt(p) || skip.contains(p)) continue;
            BlockState s = sl.getBlockState(p);
            boolean target = isMatureCrop(s) || (seeds && s.isAir() && sl.getBlockState(p.below()).is(Blocks.FARMLAND));
            if (!target || e.work.farmCandidate(sl, p) == null) continue;
            if (isMatureCrop(s) && !e.work.permitsInput(CompanionCraft.item(SEED_FOR.get(id(s.getBlock()))), 1)) continue;
            double d = p.distSqr(me) * (e.work.is(CompanionWork.Kind.FARM) ? e.routes.sourcePreference(sl, p) : 1);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    /** Reife Pflanzen um die Stelle (für die Bedürfnisse). */
    public static int countMature(ServerLevel sl, BlockPos center, int r) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -3, -r), center.offset(r, 3, r)))
            if (sl.hasChunkAt(p) && isMatureCrop(sl.getBlockState(p))) n++;
        return n;
    }

    /**
     * An der Stelle arbeiten: reife Pflanze ernten (Ertrag ins Inventar) und gleich die gleiche Sorte nachpflanzen,
     * oder auf leeres Ackerland säen. Ergebnis: 0 = nichts, 1 = geerntet (+ neu gepflanzt), 2 = gepflanzt.
     */
    public static int workFarmSpot(ServerLevel sl, CompanionEntity e, BlockPos p) {
        if (!sl.hasChunkAt(p) || !CompanionZones.allows(e, CompanionZones.Kind.FIELDS, p)) return 0;
        BlockState s = sl.getBlockState(p);
        int result = 0;
        String prefer = null;
        if (isMatureCrop(s)) {
            prefer = SEED_FOR.get(id(s.getBlock()));
            if (!e.work.permitsInput(CompanionCraft.item(prefer), 1)) return 0;
            if (!e.mineBlock(sl, p, s)) return 0;
            result = 1;
        }
        if (sl.getBlockState(p).isAir() && sl.getBlockState(p.below()).is(Blocks.FARMLAND)) {
            int slot = seedSlot(e, prefer);
            if (slot >= 0) {
                Block crop = Block.byItem(e.getInventory().getItem(slot).getItem());
                if (crop instanceof CropBlock && e.placeConfirmed(sl, p, crop.defaultBlockState())) {
                    sl.playSound(null, p, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1f, 1f);
                    var seed = e.getInventory().getItem(slot);
                    e.work.spent(seed.getItem(), 1);
                    seed.shrink(1);
                    if (result == 0) result = 2;
                }
            }
        }
        return result;
    }

    /** Gibt es Ackerland im Umkreis? */
    public static boolean hasFarmland(ServerLevel sl, BlockPos center, int r) {
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -3, -r), center.offset(r, 3, r)))
            if (sl.hasChunkAt(p) && sl.getBlockState(p).is(Blocks.FARMLAND)) return true;
        return false;
    }

    /** Wo er sein Feld sucht: am Spieler, sonst am Lager/Haus, sonst bei sich. */
    public static BlockPos farmCenter(CompanionEntity e) {
        if (e.getHome() != null) return e.getHome();
        if (e.getHouseOrigin() != null) return e.getHouseOrigin();
        return e.blockPosition();
    }

    // ================================================================== 2. Kisten nach Sorten

    public enum Category {
        TOOLS, FOOD, ORES, WOOD, FARM, STONE, MOB, OTHER;

        public Component labelText() { return CompanionTexts.tr("jobs.category." + name().toLowerCase(java.util.Locale.ROOT)); }
    }

    private static final String[] ARMOR_SUFFIX = {"_helmet", "_chestplate", "_leggings", "_boots"};

    public static Category category(ItemStack s) {
        var key = BuiltInRegistries.ITEM.getKey(s.getItem());
        if (!"minecraft".equals(key.getNamespace())) {
            if (s.isDamageableItem() || s.has(DataComponents.TOOL) || s.has(DataComponents.EQUIPPABLE) || s.is(ItemTags.SWORDS)) return Category.TOOLS;
            if (s.has(DataComponents.FOOD)) return Category.FOOD;
            if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) return Category.WOOD;
            return Category.OTHER;
        }
        String id = key.getPath(); // Benennungsregeln ausschließlich innerhalb des bestätigten Minecraft-Namensraums.
        if (Set.of("rotten_flesh", "spider_eye", "poisonous_potato").contains(id)) return Category.MOB;
        if (s.isDamageableItem() || s.has(DataComponents.TOOL) || id.equals("arrow") || id.endsWith("_arrow")
                || id.equals("shield") || id.equals("bow") || id.equals("crossbow")) return Category.TOOLS;
        for (String a : ARMOR_SUFFIX) if (id.endsWith(a)) return Category.TOOLS;
        if (s.has(DataComponents.FOOD)) return Category.FOOD;
        if (id.endsWith("_ore") || id.startsWith("raw_") || id.endsWith("_ingot") || id.endsWith("_nugget")
                || Set.of("diamond", "emerald", "coal", "charcoal", "lapis_lazuli", "redstone", "quartz", "netherite_scrap",
                "ancient_debris", "amethyst_shard", "obsidian", "flint").contains(id)) return Category.ORES;
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || id.equals("stick") || id.endsWith("_wood")) return Category.WOOD;
        if (id.endsWith("_seeds") || id.endsWith("sapling") || id.endsWith("_propagule") || Set.of("wheat", "sugar_cane",
                "bone_meal", "pumpkin", "melon", "cactus", "bamboo", "kelp", "cocoa_beans", "nether_wart", "hay_block").contains(id))
            return Category.FARM;
        if (id.endsWith("stone") || id.contains("deepslate") || id.contains("dirt") || Set.of("gravel", "sand", "red_sand",
                "andesite", "diorite", "granite", "tuff", "netherrack", "basalt", "calcite", "clay_ball", "clay", "mud",
                "stone_bricks", "moss_block").contains(id)) return Category.STONE;
        if (id.endsWith("_wool") || Set.of("rotten_flesh", "bone", "string", "spider_eye", "gunpowder", "ender_pearl",
                "slime_ball", "leather", "feather", "blaze_rod", "blaze_powder", "ink_sac", "egg", "phantom_membrane",
                "rabbit_hide", "ghast_tear", "magma_cream").contains(id)) return Category.MOB;
        return Category.OTHER;
    }

    /** @param failure null = sortiert (oder nichts zu tun); sonst die Meldung, warum nichts bzw. nicht alles geklappt hat */
    public record SortResult(int boxes, List<Component> lineTexts, Component failure) {
        public SortResult(int boxes, List<Component> lineTexts) { this(boxes, lineTexts, null); }
        public List<String> lines() { return lineTexts.stream().map(Component::getString).toList(); }
        public boolean failed() { return failure != null; }
    }

    /**
     * Alle Kisten im Umkreis zusammen aufräumen, nach Sorten: jede Sorte bekommt eigene Kisten (genug Kisten) bzw.
     * die Sorten werden gleichmäßig auf die Kisten verteilt (wenige Kisten). Stapel werden zusammengelegt.
     * Das Zielbild entsteht nur aus Kopien; geschrieben wird über {@link ContainerRewrite}, das bei einem Fehler den
     * alten Inhalt wiederherstellt. Passt die Sortenaufteilung nicht, werden die Sorten verdichtet; passt auch das
     * nicht, bleibt alles unverändert.
     */
    public static SortResult sortByCategory(CompanionEntity e, ServerLevel sl, BlockPos center) {
        List<BlockPos> where = new ArrayList<>();
        List<Container> boxes = new ArrayList<>();
        for (BlockPos p : e.storageBoxes(sl, center)) {
            if (e.storage.protectedStockStorage(sl, p)) continue;
            Container c = e.containerAt(sl, p);
            if (c == null) continue;
            where.add(p);
            boxes.add(c);
        }
        if (boxes.isEmpty()) return new SortResult(0, List.of());
        List<ItemStack[]> before = ContainerRewrite.snapshot(boxes);
        Map<Category, List<ItemStack>> byCat = new EnumMap<>(Category.class);
        for (ItemStack[] box : before)
            for (ItemStack s : box)
                if (!s.isEmpty()) byCat.computeIfAbsent(category(s), k -> new ArrayList<>()).add(s.copy());
        // je Sorte zusammenlegen und nach Item sortieren
        Map<Category, List<ItemStack>> merged = new EnumMap<>(Category.class);
        byCat.forEach((cat, list) -> {
            list.sort(Comparator.comparing((ItemStack s) -> id(s.getItem())));
            merged.put(cat, mergeStacks(list));
        });
        Map<Integer, Set<Category>> labels = new LinkedHashMap<>();
        ItemStack[][] target = layoutByCategory(boxes, merged, labels);
        if (target == null) {
            labels.clear();
            target = layoutDense(boxes, merged, labels);
        }
        if (target == null) return new SortResult(boxes.size(), List.of(), CompanionTexts.tr("jobs.sort.no_room"));
        Component failure = reportRewrite(e, sl, ContainerRewrite.apply(boxes, before, target));
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            e.memory.noteChest(sl, where.get(i), boxes.get(i));
            Set<Category> l = labels.get(i);
            lines.add(CompanionTexts.tr("jobs.sort.line", where.get(i).toShortString(), l == null
                    ? CompanionTexts.tr("jobs.sort.empty") : CompanionTexts.join(l.stream().map(Category::labelText).toList(), ", ")));
        }
        return failure == null ? new SortResult(boxes.size(), lines) : new SortResult(boxes.size(), List.of(), failure);
    }

    /** Zielbild mit Sortengrenzen; null, wenn nicht alles hineinpasst. */
    private static ItemStack[][] layoutByCategory(List<Container> boxes, Map<Category, List<ItemStack>> merged,
                                                  Map<Integer, Set<Category>> labels) {
        ItemStack[][] target = ContainerRewrite.emptyLayout(boxes);
        int cats = merged.size();
        int perBox = cats == 0 ? 1 : Math.max(1, (cats + boxes.size() - 1) / boxes.size());
        // Was hinter der letzten Kiste nicht mehr passt, weil Sorten Kistenreste übersprungen haben
        List<Map.Entry<Category, ItemStack>> overflow = new ArrayList<>();
        int box = 0, slot = 0, catsInBox = 0;
        int remainingStacks = merged.values().stream().mapToInt(List::size).sum();
        boolean firstCat = true;
        for (Map.Entry<Category, List<ItemStack>> en : merged.entrySet()) {
            // neue Sorte: in die nächste Kiste, wenn diese schon genug Sorten hat (und es noch eine gibt)
            if (!firstCat && (catsInBox >= perBox || slot >= target[box].length) && box + 1 < boxes.size()) {
                int laterCapacity = 0;
                for (int i = box + 1; i < boxes.size(); i++) laterCapacity += target[i].length;
                if (remainingStacks <= laterCapacity) {
                    box++;
                    slot = 0;
                    catsInBox = 0;
                }
            }
            firstCat = false;
            catsInBox++;
            for (ItemStack original : en.getValue()) {
                ItemStack s = original.copy();
                remainingStacks--;
                while (!s.isEmpty() && box < boxes.size()) {
                    if (slot >= target[box].length) { box++; slot = 0; continue; }
                    int count = s.getCount();
                    slot = ContainerRewrite.place(boxes.get(box), target[box], slot, s);
                    if (s.getCount() != count) labels.computeIfAbsent(box, k -> new LinkedHashSet<>()).add(en.getKey());
                }
                if (!s.isEmpty()) overflow.add(Map.entry(en.getKey(), s));
            }
            if (box >= boxes.size()) box = boxes.size() - 1;
        }
        // Sortengrenzen aufweichen: Überlauf in die übersprungenen freien Plätze
        for (var en : overflow) {
            ItemStack s = en.getValue();
            for (int b = 0; b < boxes.size() && !s.isEmpty(); b++) {
                int count = s.getCount();
                ContainerRewrite.place(boxes.get(b), target[b], 0, s);
                if (s.getCount() != count) labels.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(en.getKey());
            }
            if (!s.isEmpty()) return null;
        }
        return target;
    }

    /** Zielbild ohne Sortengrenzen (Sorten verdichtet, Reihenfolge bleibt); null, wenn nicht alles hineinpasst. */
    private static ItemStack[][] layoutDense(List<Container> boxes, Map<Category, List<ItemStack>> merged,
                                             Map<Integer, Set<Category>> labels) {
        ItemStack[][] target = ContainerRewrite.emptyLayout(boxes);
        for (var en : merged.entrySet()) {
            for (ItemStack original : en.getValue()) {
                ItemStack s = original.copy();
                for (int b = 0; b < boxes.size() && !s.isEmpty(); b++) {
                    int count = s.getCount();
                    ContainerRewrite.place(boxes.get(b), target[b], 0, s);
                    if (s.getCount() != count) labels.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(en.getKey());
                }
                if (!s.isEmpty()) return null;
            }
        }
        return target;
    }

    /**
     * Meldet ein fehlgeschlagenes Schreiben im Diagnose-Log und wirft Items ab, die weder geschrieben noch
     * wiederhergestellt werden konnten (nie vernichten).
     *
     * @return null bei Erfolg, sonst die Meldung für den Spieler
     */
    static Component reportRewrite(CompanionEntity e, ServerLevel sl, ContainerRewrite.Result r) {
        Component msg = switch (r.outcome()) {
            case WRITTEN, UNCHANGED -> null;
            case STALE, MISMATCH -> CompanionTexts.tr("jobs.sort.aborted");
            case RESTORED -> CompanionTexts.tr("jobs.sort.restored");
            case RESTORE_FAILED -> CompanionTexts.tr("jobs.sort.restore_failed", r.missing().size());
        };
        if (msg == null) return null;
        e.logError(msg);
        for (ItemStack s : r.missing()) {
            e.logError(CompanionTexts.tr("jobs.sort.restore_failed_item", s.getCount(), s.getHoverName()));
            e.spawnAtLocation(sl, s);
        }
        return msg;
    }

    static List<ItemStack> mergeStacks(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) {
            ItemStack rest = s.copy();
            for (ItemStack m : out) {
                if (rest.isEmpty()) break;
                if (ItemStack.isSameItemSameComponents(m, rest) && m.getCount() < m.getMaxStackSize()) {
                    int move = Math.min(rest.getCount(), m.getMaxStackSize() - m.getCount());
                    m.grow(move);
                    rest.shrink(move);
                }
            }
            if (!rest.isEmpty()) out.add(rest);
        }
        return out;
    }

    // ================================================================== 3. Tiere züchten

    /** Ab so vielen erwachsenen Tieren einer Art in der Nähe hört er mit dem Züchten auf. */
    public static final int HERD_MAX = 12;
    /** So viele erwachsene Tiere einer Art lässt er bei der Jagd immer stehen (Herde halten). */
    public static final int HERD_KEEP = 2;

    public static String type(net.minecraft.world.entity.Entity a) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString();
    }

    /** Slot mit Futter, das dieses Tier frisst; -1 = keins. */
    public static int feedSlot(CompanionEntity e, Animal a) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty() && a.isFood(s) && e.work.permitsInput(s.getItem(), 1)) return i;
        }
        return -1;
    }

    /** Erwachsen, nicht in der Abklingzeit, noch nicht verliebt. */
    public static boolean breedable(Animal a) {
        return a.isAlive() && !a.isBaby() && a.getAge() == 0 && a.canFallInLove() && !a.isInLove();
    }

    public static List<Animal> animals(ServerLevel sl, BlockPos center, int r, String species) {
        String wanted = RegistryIds.canonicalOrOriginal(species);
        return sl.getEntitiesOfClass(Animal.class, new net.minecraft.world.phys.AABB(center).inflate(r),
                a -> a.isAlive() && (wanted == null || wanted.equals(type(a))));
    }

    public static int adults(ServerLevel sl, BlockPos center, int r, String species) {
        int n = 0;
        for (Animal a : animals(sl, center, r, species)) if (!a.isBaby()) n++;
        return n;
    }

    /**
     * Nächstes Tier zum Füttern: bevorzugt eins, dessen Artgenosse schon verliebt ist (Paar vollmachen), sonst das
     * nächste mit Partner in der Nähe. Nur Arten, die nicht schon zu viele sind.
     */
    public static Animal nextToFeed(ServerLevel sl, CompanionEntity e, String species) {
        var zone = CompanionZones.work(e, CompanionZones.Kind.ANIMALS);
        List<Animal> all = animals(sl, zone == null ? e.blockPosition() : zone.center(), zone == null ? 24 : 48, species)
                .stream().filter(a -> zone == null || zone.contains(a.blockPosition())).toList();
        Animal best = null;
        double bestD = Double.MAX_VALUE;
        for (Animal a : all) {
            if (!breedable(a) || feedSlot(e, a) < 0) continue;
            String t = type(a);
            if (adults(sl, a.blockPosition(), 16, t) >= HERD_MAX) continue;
            boolean partnerInLove = false;
            int partners = 0;
            for (Animal b : all) {
                if (b == a || !type(b).equals(t) || b.isBaby()) continue;
                if (b.distanceToSqr(a) > 12 * 12) continue;
                if (b.isInLove()) partnerInLove = true;
                if (breedable(b) || b.isInLove()) partners++;
            }
            if (partners == 0) continue;
            double d = e.distanceToSqr(a) - (partnerInLove ? 10_000 : 0);
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    public static void feed(CompanionEntity e, Animal a) {
        int slot = feedSlot(e, a);
        if (slot < 0) return;
        ItemStack food = e.getInventory().getItem(slot).copyWithCount(1);
        e.getLookControl().setLookAt(a);
        e.getInventory().getItem(slot).shrink(1);
        e.swingArm();
        a.setInLove(null);
        e.work.spent(food.getItem(), 1);
        // Futterkrümel am Kopf des Tiers und Fressgeräusch, wie beim Füttern durch einen Spieler
        if (e.level() instanceof ServerLevel sl) {
            sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,
                    food.getItem()),
                    a.getX(), a.getEyeY(), a.getZ(), 6, 0.15, 0.1, 0.15, 0.05);
            sl.playSound(null, a.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6f, 1.2f);
        }
    }

    /** Holen: kein schon angeleintes Tier (Zaun, anderer Spieler) und kein gezähmtes Tier eines anderen Spielers. */
    public static boolean mayFetch(net.minecraft.world.entity.Mob m, net.minecraft.world.entity.player.Player owner) {
        if (m instanceof net.minecraft.world.entity.Leashable l && l.isLeashed()) return false;
        if (m instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwnerReference() != null)
            return o.getOwnerReference().getUUID().equals(owner.getUUID());
        return true;
    }

    /** Darf er dieses Tier jagen? Keine Jungtiere; beim Herdehalten bleiben immer {@link #HERD_KEEP} Erwachsene stehen. */
    public static boolean mayHunt(CompanionEntity e, net.minecraft.world.entity.Mob m) {
        if (m instanceof net.minecraft.world.entity.AgeableMob am && am.isBaby()) return false;
        // Benannte, angeleinte und gezähmte Tiere gehören jemandem
        if (m.hasCustomName() || (m instanceof net.minecraft.world.entity.Leashable l && l.isLeashed())) return false;
        if (m instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwnerReference() != null) return false;
        if (!e.herdKeeping || !(m.level() instanceof ServerLevel sl)) return true;
        return adults(sl, m.blockPosition(), 16, type(m)) > HERD_KEEP;
    }

    /** Futter-Item für eine Art, um es dem Spieler zu nennen. */
    public static String foodName(String species) { return foodText(species).getString(); }

    public static Component foodText(String species) {
        return switch (species == null ? "" : RegistryIds.canonicalOrOriginal(species)) {
            case "minecraft:cow", "minecraft:sheep", "minecraft:goat" -> CompanionTexts.item("minecraft:wheat");
            case "minecraft:pig" -> foodItems("carrot", "potato", "beetroot");
            case "minecraft:chicken" -> CompanionTexts.tr("jobs.food.seeds");
            case "minecraft:rabbit" -> foodItems("carrot", "dandelion");
            case "minecraft:horse", "minecraft:donkey" -> foodItems("golden_apple", "golden_carrot");
            case "minecraft:wolf" -> CompanionTexts.tr("jobs.food.meat");
            case "minecraft:cat" -> CompanionTexts.tr("jobs.food.raw_fish");
            default -> CompanionTexts.tr("jobs.food.appropriate");
        };
    }

    private static Component foodItems(String... ids) {
        return CompanionTexts.join(java.util.Arrays.stream(ids).map(id -> CompanionTexts.item("minecraft:" + id)).toList(), "/");
    }

    // ================================================================== 4. Werkzeug-Pflege

    /** Haltbarkeit 0..1 (1 = neu). */
    public static double health(ItemStack s) {
        if (!s.isDamageableItem() || s.getMaxDamage() <= 0) return 1;
        return (s.getMaxDamage() - s.getDamageValue()) / (double) s.getMaxDamage();
    }

    /** Alle beschädigten Sachen, die er hat (Inventar, Hände, Rüstung) unter der Schwelle. */
    public static List<ItemStack> damaged(CompanionEntity e, double below) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty() && s.isDamageableItem() && s.getDamageValue() > 0 && health(s) < below) out.add(s);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = e.getItemBySlot(slot);
            if (!s.isEmpty() && s.isDamageableItem() && s.getDamageValue() > 0 && health(s) < below) out.add(s);
        }
        return out;
    }

    /**
     * Zwei beschädigte gleiche Werkzeuge ohne Verzauberung zu einem zusammenlegen (wie im Crafting-Feld: Haltbarkeit
     * beider + 5 % Bonus). Braucht keine Station. Ergebnis: Anzahl zusammengelegter Paare.
     */
    public static int mergeDuplicates(CompanionEntity e) {
        int merged = 0;
        var inv = e.getInventory();
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) all.add(inv.getItem(i));
        for (EquipmentSlot slot : EquipmentSlot.values()) all.add(e.getItemBySlot(slot));
        for (int a = 0; a < all.size(); a++) {
            ItemStack x = all.get(a);
            if (x.isEmpty() || !x.isDamageableItem() || x.getDamageValue() == 0 || x.isEnchanted()) continue;
            for (int b = a + 1; b < all.size(); b++) {
                ItemStack y = all.get(b);
                if (y.isEmpty() || y.getItem() != x.getItem() || y.getDamageValue() == 0 || y.isEnchanted()) continue;
                int max = x.getMaxDamage();
                int left = (max - x.getDamageValue()) + (max - y.getDamageValue()) + max * 5 / 100;
                // nur wenn es sich lohnt (sonst zwei fast neue Werkzeuge zu einem)
                if (left > max + max / 4) continue;
                x.setDamageValue(Math.max(0, max - left));
                y.shrink(1);
                merged++;
                break;
            }
        }
        return merged;
    }

    /** Reparaturmaterial für dieses Item im Inventar (Slot), -1 = keins. */
    public static int repairMaterialSlot(CompanionEntity e, ItemStack tool) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack m = e.getInventory().getItem(i);
            if (!m.isEmpty() && m != tool && tool.isValidRepairItem(m)) return i;
        }
        return -1;
    }

    /** Hat er etwas Beschädigtes, für das er Material dabei hat? */
    public static boolean hasRepairable(CompanionEntity e, double below) {
        for (ItemStack s : damaged(e, below)) if (repairMaterialSlot(e, s) >= 0) return true;
        return false;
    }

    /** Amboss in der Nähe (auch angeschlagen). */
    /** Nächster Amboss in jedem Zustand (eine gemeinsame Suche statt einer je Zustand). */
    public static BlockPos findAnvil(CompanionEntity e, int r) {
        return e.findStation(List.of("anvil", "chipped_anvil", "damaged_anvil"), r);
    }

    /**
     * Am Amboss reparieren: je Material-Einheit ein Viertel der Haltbarkeit (wie im Spiel). Ergebnis: Beschreibung der
     * reparierten Sachen.
     */
    public static List<String> repairAtAnvil(ServerLevel sl, CompanionEntity e, BlockPos anvil, double below) {
        return repairAtAnvilTexts(sl, e, anvil, below).stream().map(Component::getString).toList();
    }

    public static List<Component> repairAtAnvilTexts(ServerLevel sl, CompanionEntity e, BlockPos anvil, double below) {
        List<Component> done = new ArrayList<>();
        for (ItemStack s : damaged(e, below)) {
            int before = (int) Math.round(health(s) * 100);
            int units = 0;
            while (s.getDamageValue() > 0 && units < 4) {
                int slot = repairMaterialSlot(e, s);
                if (slot < 0) break;
                int fix = Math.min(s.getDamageValue(), s.getMaxDamage() / 4);
                s.setDamageValue(s.getDamageValue() - fix);
                e.getInventory().getItem(slot).shrink(1);
                units++;
            }
            if (units > 0) done.add(CompanionTexts.tr("jobs.repair.item", s.getHoverName(), before, Math.round(health(s) * 100)));
        }
        if (!done.isEmpty()) sl.playSound(null, anvil, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1f);
        return done;
    }

    /** Lohnt sich jetzt Pflege statt neu bauen? (zwei gleiche zum Zusammenlegen oder Amboss + Material) */
    public static boolean canRepairNow(CompanionEntity e, double below) {
        if (e.craftBlocked("#repair")) return false;
        List<ItemStack> worn = damaged(e, below);
        if (worn.isEmpty()) return false;
        for (ItemStack s : worn) {
            if (s.isEnchanted()) continue;
            for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
                ItemStack o = e.getInventory().getItem(i);
                if (o != s && o.getItem() == s.getItem() && o.getDamageValue() > 0 && !o.isEnchanted()) return true;
            }
        }
        return findAnvil(e, 24) != null && hasRepairable(e, below);
    }

    // ================================================================== 5. Handel

    public static List<Villager> villagers(ServerLevel sl, CompanionEntity e, int r) {
        List<Villager> l = sl.getEntitiesOfClass(Villager.class, e.getBoundingBox().inflate(r),
                v -> v.isAlive() && !v.isBaby());
        l.sort(Comparator.comparingDouble(e::distanceToSqr));
        return l;
    }

    public static int countOf(CompanionEntity e, Item item) {
        int n = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    static boolean canPay(CompanionEntity e, ItemStack costA, ItemStack costB) {
        if (costA.isEmpty()) return false;
        if (!costB.isEmpty() && costB.getItem() == costA.getItem())
            return countOf(e, costA.getItem()) >= costA.getCount() + costB.getCount();
        return countOf(e, costA.getItem()) >= costA.getCount()
                && (costB.isEmpty() || countOf(e, costB.getItem()) >= costB.getCount());
    }

    static void pay(CompanionEntity e, ItemStack cost) {
        int left = cost.getCount();
        for (int i = 0; i < e.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.is(cost.getItem())) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            left -= take;
        }
    }

    /** So viel will er von einer Sache immer behalten, wenn er verkauft. */
    static int reserve(Item item) {
        ItemStack s = new ItemStack(item);
        if (s.has(DataComponents.FOOD)) return 8;
        String id = id(item);
        if (id.equals("minecraft:stick")) return 8;
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) return 16;
        return 0;
    }

    /** Verkaufbar: kein Werkzeug/Rüstung, nichts Wertvolles, über der Reserve. */
    static boolean sellable(CompanionEntity e, ItemStack cost) {
        if (cost.isEmpty() || cost.isDamageableItem() || cost.is(Items.EMERALD)) return false;
        // Für unbekannte Fremditems gibt es keine automatische Wert-/Reservepolitik.
        if (!"minecraft".equals(BuiltInRegistries.ITEM.getKey(cost.getItem()).getNamespace())) return false;
        if (CompanionEntity.isPrecious(cost.getItem())) return false;
        return countOf(e, cost.getItem()) - cost.getCount() >= reserve(cost.getItem())
                && e.work.permitsInput(cost.getItem(), cost.getCount());
    }

    /** Ein Angebot dieses Dorfbewohners: kaufen (want != null) oder überzähliges Zeug gegen Smaragde verkaufen. */
    public static MerchantOffer findOffer(CompanionEntity e, Villager v, Item want) {
        for (MerchantOffer o : v.getOffers()) {
            if (o.isOutOfStock()) continue;
            ItemStack a = o.getCostA(), b = o.getCostB(), result = o.getResult();
            if (want != null) {
                if (result.is(want) && canPay(e, a, b)) return o;
            } else if (result.is(Items.EMERALD) && b.isEmpty() && sellable(e, a) && canPay(e, a, b)) {
                return o;
            }
        }
        return null;
    }

    /** Hat irgendein Dorfbewohner die Ware überhaupt im Angebot (egal ob bezahlbar)? */
    public static boolean anyoneSells(List<Villager> vs, Item want) {
        for (Villager v : vs) for (MerchantOffer o : v.getOffers()) if (o.getResult().is(want)) return true;
        return false;
    }

    /** Einen Handel ausführen (bezahlen, Ware nehmen, dem Dorfbewohner melden). */
    public static ItemStack trade(CompanionEntity e, Villager v, MerchantOffer o) {
        ItemStack a = o.getCostA().copy(), b = o.getCostB().copy(), result = o.getResult().copy();
        pay(e, a);
        if (!b.isEmpty()) pay(e, b);
        v.notifyTrade(o);
        e.work.spent(a.getItem(), a.getCount());
        e.work.spent(b.getItem(), b.getCount());
        ItemStack rest = e.giveToInventory(result.copy());
        if (!rest.isEmpty() && v.level() instanceof ServerLevel sl) e.spawnAtLocation(sl, rest);
        e.swingArm();
        return result;
    }

    // ================================================================== 6. Wegpunkte -> CompanionMemory

    /** Name aus "merk dir das als mine" / "geh zur mine": die Wörter nach als/zur/zum/zu/nach. */
    static String waypointName(List<String> tok, String... after) {
        for (int i = 0; i < tok.size() - 1; i++)
            for (String a : after)
                if (tok.get(i).equals(a)) {
                    List<String> rest = new ArrayList<>(tok.subList(i + 1, tok.size()));
                    rest.removeIf(w -> Set.of("der", "die", "das", "dem", "den", "meiner", "meinem", "meine", "unserer",
                            "unserem", "unsere", "bitte", "begleiter", "wegpunkt", "hin", "gehen", "laufen",
                            "zurueck").contains(w));
                    if (!rest.isEmpty()) return String.join(" ", rest);
                }
        return null;
    }

    // ================================================================== 7. Ausrüstungsziele

    static final String[] ARMOR_PREFIX = {"leather_", "golden_", "chainmail_", "copper_", "iron_", "diamond_", "netherite_"};
    static final int[] ARMOR_TIER = {1, 1, 2, 2, 3, 4, 5};
    static final String[] TOOL_PREFIX = {"wooden_", "golden_", "stone_", "copper_", "iron_", "diamond_", "netherite_"};
    static final int[] TOOL_TIER = {1, 1, 2, 2, 3, 4, 5};
    /** Reihenfolge: erst Waffe und Spitzhacke, dann Rüstung (Brust zuerst – schützt am meisten), zuletzt Schild. */
    public static final String[] GEAR = {"sword", "pickaxe", "chestplate", "leggings", "helmet", "boots", "shield"};

    static boolean isArmor(String kind) {
        return kind.equals("helmet") || kind.equals("chestplate") || kind.equals("leggings") || kind.equals("boots");
    }

    /** Bestes Exemplar dieser Art, das er hat (0 = keins). */
    public static int pieceTier(CompanionEntity e, String kind) {
        if (kind.equals("shield")) return CompanionCraft.owns(e, "shield") ? 9 : 0;
        String[] prefix = isArmor(kind) ? ARMOR_PREFIX : TOOL_PREFIX;
        int[] tiers = isArmor(kind) ? ARMOR_TIER : TOOL_TIER;
        int best = 0;
        for (int k = 0; k < prefix.length; k++) if (CompanionCraft.owns(e, prefix[k] + kind)) best = Math.max(best, tiers[k]);
        return best;
    }

    public static String tierName(int tier) {
        return switch (tier) { case 3 -> "iron"; case 4 -> "diamond"; case 5 -> "netherite"; default -> "stone"; };
    }

    public static String tierLabel(int tier) {
        return CompanionTexts.tier(tier).getString();
    }

    /** Item-ID des nächsten fehlenden Stücks für das Ziel; skip = Bitmaske übersprungener Stücke. null = komplett. */
    public static String nextGearPiece(CompanionEntity e, int tier, int skip) {
        for (int i = 0; i < GEAR.length; i++) {
            if ((skip & (1 << i)) != 0) continue;
            String kind = GEAR[i];
            if (kind.equals("shield")) {
                if (!CompanionCraft.owns(e, "shield")) return "shield";
                continue;
            }
            if (kind.equals("pickaxe") && tier > 4) continue; // Netherit-Spitzhacke braucht Schmiedetisch – Ziel ist Rüstung
            if (pieceTier(e, kind) < tier) {
                if (tier == 2 && isArmor(kind)) continue; // Stein-Rüstung gibt es nicht
                return (isArmor(kind) || tier != 2 ? tierName(tier) : "stone") + "_" + kind;
            }
        }
        return null;
    }

    public static int gearIndex(String itemId) {
        for (int i = 0; i < GEAR.length; i++) if (itemId.endsWith(GEAR[i])) return i;
        return -1;
    }

    /** "3/7": wie viele Stücke des Ziels er schon hat. */
    public static String gearProgress(CompanionEntity e, int tier) {
        int have = 0;
        for (String kind : GEAR) {
            if (kind.equals("shield") ? CompanionCraft.owns(e, "shield") : pieceTier(e, kind) >= tier) have++;
        }
        return have + "/" + GEAR.length;
    }

    /** Ausrüstungsziel aus dem Satz: Diamant/Netherit/Eisen, Standard Eisen (bzw. Diamant, wenn Eisen schon komplett). */
    public static int gearTarget(CompanionEntity e, List<String> tok) {
        for (String t : tok) {
            if (t.startsWith("netherit")) return 5;
            if (t.startsWith("diamant")) return 4;
            if (t.startsWith("eisen") || t.startsWith("eiser")) return 3;
        }
        return nextGearPiece(e, 3, 0) == null ? 4 : 3;
    }

    public static Task gearTask(int tier, int skip, String last, int attempts, int round) {
        return new Task(new dev.begleiter.task.TaskSpec.GearUp(tier, last, attempts, skip), round);
    }
}
