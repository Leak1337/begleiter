package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import dev.begleiter.CompanionEntity.Task;

/**
 * Bauen nach Plan: eine Liste "Position -> genauer Blockzustand" (gedrehte Treppen, Türen, Redstone ...).
 * Reihenfolge wie ein guter Spieler: erst wegräumen (von oben), dann tragende Blöcke, dann Angehängtes
 * (Fackeln, Staub, Knöpfe), Energiequellen zuletzt. Blöcke werden ohne Nachbar-Updates gesetzt (nichts löst
 * während des Bauens aus) und am Ende einmal aktualisiert. Im Survival wird Material verbraucht und selbst
 * beschafft; im Kreativmodus des Besitzers ist alles da.
 */
public final class CompanionBuilder {
    private CompanionBuilder() {}

    /** Setzen ohne Nachbar-Updates und ohne onPlace (Kolben/Beobachter/Redstone reagieren erst am Ende). */
    public static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    public record Placement(BlockPos pos, BlockState state) {}

    /** Zustände, die sich im Betrieb ändern (Strom, an/aus ...): zählen beim Vergleich nicht als Fehler. */
    private static final Set<net.minecraft.world.level.block.state.properties.Property<?>> DYNAMIC = Set.of(
            BlockStateProperties.POWERED, BlockStateProperties.POWER, BlockStateProperties.LIT,
            BlockStateProperties.TRIGGERED, BlockStateProperties.EXTENDED, BlockStateProperties.ENABLED,
            BlockStateProperties.LOCKED, BlockStateProperties.OCCUPIED, BlockStateProperties.OPEN,
            BlockStateProperties.ATTACHED, BlockStateProperties.DISARMED, BlockStateProperties.IN_WALL,
            BlockStateProperties.NORTH_REDSTONE, BlockStateProperties.EAST_REDSTONE,
            BlockStateProperties.SOUTH_REDSTONE, BlockStateProperties.WEST_REDSTONE,
            // wächst/trocknet von selbst
            BlockStateProperties.MOISTURE, BlockStateProperties.STAGE, BlockStateProperties.AGE_1, BlockStateProperties.AGE_2,
            BlockStateProperties.AGE_3, BlockStateProperties.AGE_4, BlockStateProperties.AGE_5, BlockStateProperties.AGE_7,
            BlockStateProperties.AGE_15, BlockStateProperties.AGE_25, BlockStateProperties.DISTANCE,
            BlockStateProperties.BAMBOO_LEAVES);

    /** Verbindungen, die sich aus den Nachbarn ergeben – nur bei Zaun/Scheibe/Mauer/Treppe/Staub ignorieren. */
    private static final Set<net.minecraft.world.level.block.state.properties.Property<?>> SHAPE = Set.of(
            BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST,
            BlockStateProperties.UP, BlockStateProperties.NORTH_WALL, BlockStateProperties.EAST_WALL,
            BlockStateProperties.SOUTH_WALL, BlockStateProperties.WEST_WALL, BlockStateProperties.STAIRS_SHAPE);

    /** Steht dort, was der Plan will? Betriebszustände (Strom, Licht, Wachstum ...) werden ignoriert. */
    public static boolean matches(BlockState world, BlockState target) {
        if (world.equals(target)) return true;
        // Beobachtet: beim Hausflicken Birkentür und Werkbänke abgerissen. Tür ist Tür, und Einrichtung
        // (Werkbank, Ofen, Kiste, Fackel, Bett) steht dort, wo der Plan nur Luft will, zu Recht
        var wb = world.getBlock();
        if (wb instanceof net.minecraft.world.level.block.DoorBlock && target.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
            return true;
        if (target.isAir() && (wb == net.minecraft.world.level.block.Blocks.CRAFTING_TABLE
                || wb instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                || wb instanceof net.minecraft.world.level.block.ChestBlock
                || wb instanceof net.minecraft.world.level.block.BedBlock
                || wb instanceof net.minecraft.world.level.block.BaseTorchBlock)) return true;
        if (world.getBlock() != target.getBlock()) return false;
        boolean shaped = shapeFromNeighbours(target);
        for (var prop : target.getProperties()) {
            if (DYNAMIC.contains(prop) || (shaped && SHAPE.contains(prop))) continue;
            if (!world.hasProperty(prop) || !world.getValue(prop).equals(target.getValue(prop))) return false;
        }
        return true;
    }

    /** Blöcke, deren Form von den Nachbarn abhängt (Staub, Zäune, Scheiben, Mauern, Treppen). */
    static boolean shapeFromNeighbours(BlockState s) {
        Block b = s.getBlock();
        return b instanceof RedstoneWireBlock || b instanceof CrossCollisionBlock || b instanceof WallBlock
                || b instanceof StairBlock;
    }

    public static final class BuildJob {
        public final String name;
        public final List<Placement> order;
        public int index;
        public final List<BlockPos> placed = new ArrayList<>();
        public final Map<Item, Integer> attempts = new HashMap<>();
        public int lastReported;
        public int repairRounds;
        /** Vom Spieler angehalten (Menü "Pause"): bleibt erhalten, wird aber nicht weitergebaut. */
        public boolean paused;
        /**
         * Terraforming: nur natürliches Gelände abtragen oder überbauen. Was zwischen Planung und Ausführung gebaut
         * wurde (Kiste, Fass, Schild, Baublock), bleibt stehen und die Stelle wird übersprungen.
         */
        public boolean terrainOnly;
        /**
         * Seit wann er die aktuelle Stelle ansteuert (nicht gespeichert). Liegt im Auftrag statt in der Aufgabe, weil
         * BUILD bei "unerreichbar" neu eingereiht wird – sonst begänne die Wartezeit bis zum Ausweichen immer von vorn.
         */
        public BlockPos approachFor;
        public int approachSince;
        /** Wie oft der Abbau an der aktuellen Stelle schon angestoßen wurde (nicht gespeichert), gegen Endlosschleifen. */
        public int breakIndex = -1, breakTries;
        /** Block-Daten aus dem Schematic (Inhalte, Crafter-Slots, Schildtext), beim Setzen übernommen. */
        public final Map<BlockPos, net.minecraft.nbt.CompoundTag> data = new HashMap<>();
        /** Lebewesen aus dem Schematic (Welt-Positionen); nach Fertigstellung nur im Kreativ-Bau gesetzt. */
        public final List<CompanionSchematics.EntityPlan> entities = new ArrayList<>();
        private String dimension;
        /** Zuletzt gezähltes Restmaterial (nicht gespeichert), siehe {@link CompanionBuilder#remaining}. */
        MaterialCount materials;

        public boolean inDimension(ServerLevel level) {
            String current = level.dimension().identifier().toString();
            if (dimension == null) dimension = current; // ältere Spielstände an ihre geladene Welt binden
            return dimension.equals(current);
        }

        BuildJob(String name, List<Placement> order) {
            this.name = name;
            this.order = order;
        }

        public int total() { return order.size(); }

        /** Positionen des Plans, einmal aufgebaut: große Bauten (Hunderttausende Blöcke) nicht linear durchsuchen. */
        private Set<BlockPos> positions;
        public boolean contains(BlockPos pos) {
            if (positions == null) {
                positions = new HashSet<>(order.size() * 4 / 3 + 16);
                for (Placement p : order) positions.add(p.pos());
            }
            return positions.contains(pos);
        }
        public int percent() { return order.isEmpty() ? 100 : index * 100 / order.size(); }
    }

    // ------------------------------------------------------------------ Speichern

    private static final com.mojang.serialization.Codec<long[]> POSITIONS_CODEC =
            com.mojang.serialization.Codec.LONG_STREAM.xmap(java.util.stream.LongStream::toArray, java.util.Arrays::stream);

    /** Blockzustände als Palette, vollständige Weltpositionen als gepackte BlockPos-longs. */
    public static void save(net.minecraft.world.level.storage.ValueOutput out, BuildJob job) {
        if (job.order.isEmpty()) return;
        if (job.order.size() > MAX_SAVED_PLACEMENTS) { invalidSavedBuild("too many placements to save"); return; }
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> ids = new HashMap<>();
        long[] pos = new long[job.order.size()];
        int[] state = new int[job.order.size()];
        for (int i = 0; i < job.order.size(); i++) {
            Placement p = job.order.get(i);
            pos[i] = p.pos().asLong();
            if (!BlockPos.of(pos[i]).equals(p.pos())) { invalidSavedBuild("position outside BlockPos range"); return; }
            state[i] = ids.computeIfAbsent(p.state(), s -> { palette.add(s); return palette.size() - 1; });
        }
        net.minecraft.world.level.storage.ValueOutput o = out.child("Build");
        o.putString("Name", job.name);
        o.store("Palette", BlockState.CODEC.listOf(), palette);
        o.store("PosL", POSITIONS_CODEC, pos);
        o.putIntArray("State", state);
        o.putInt("Index", job.index);
        o.putInt("Repair", job.repairRounds);
        o.putBoolean("Paused", job.paused);
        o.putBoolean("TerrainOnly", job.terrainOnly);
        if (job.dimension != null) o.putString("Dimension", job.dimension);
        if (!job.data.isEmpty()) {
            List<net.minecraft.nbt.CompoundTag> entries = new ArrayList<>();
            for (var en : job.data.entrySet()) {
                net.minecraft.nbt.CompoundTag e = new net.minecraft.nbt.CompoundTag();
                e.putLong("P", en.getKey().asLong());
                e.put("T", en.getValue().copy());
                entries.add(e);
            }
            o.store("Data", net.minecraft.nbt.CompoundTag.CODEC.listOf(), entries);
        }
        if (!job.entities.isEmpty()) {
            List<net.minecraft.nbt.CompoundTag> list = new ArrayList<>();
            for (var e : job.entities) {
                net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
                t.putDouble("X", e.pos().x); t.putDouble("Y", e.pos().y); t.putDouble("Z", e.pos().z);
                t.put("T", e.tag().copy());
                list.add(t);
            }
            o.store("Entities", net.minecraft.nbt.CompoundTag.CODEC.listOf(), list);
        }
    }

    /** Größter gespeicherter Auftrag: größtes Schematic plus bis zu sechs Fundamentschichten unter der Grundfläche. */
    static final int MAX_SAVED_PLACEMENTS = CompanionSchematics.MAX_BLOCKS + 6 * CompanionSchematics.MAX_SIDE * CompanionSchematics.MAX_SIDE;

    public static BuildJob load(net.minecraft.world.level.storage.ValueInput in) {
        var o = in.child("Build").orElse(null);
        if (o == null) return null;
        List<BlockState> palette = o.read("Palette", BlockState.CODEC.listOf()).orElse(List.of());
        int[] state = o.getIntArray("State").orElse(new int[0]);
        boolean hasLongPositions = o.read("PosL", com.mojang.serialization.Codec.PASSTHROUGH).isPresent();
        long[] pos = hasLongPositions ? o.read("PosL", POSITIONS_CODEC).orElse(new long[0]) : null;
        int[] legacy = hasLongPositions ? null : o.getIntArray("Pos").orElse(new int[0]);
        BlockPos origin = hasLongPositions ? null : o.read("Origin", BlockPos.CODEC).orElse(null);
        int length = hasLongPositions ? pos.length : legacy.length;
        if ((!hasLongPositions && origin == null) || length == 0 || length != state.length
                || length > MAX_SAVED_PLACEMENTS || palette.isEmpty() || palette.size() > 65_536)
            return invalidSavedBuild("invalid positions, palette or placement count");
        List<Placement> order = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            if (state[i] < 0 || state[i] >= palette.size()) return invalidSavedBuild("invalid palette index");
            int v = hasLongPositions ? 0 : legacy[i];
            BlockPos p = hasLongPositions ? BlockPos.of(pos[i])
                    : origin.offset((v >>> 21) - 1024, ((v >>> 11) & 1023) - 512, (v & 2047) - 1024);
            order.add(new Placement(p, palette.get(state[i])));
        }
        BuildJob job = new BuildJob(o.getStringOr("Name", "Bau"), order);
        job.index = Math.clamp(o.getIntOr("Index", 0), 0, order.size());
        job.repairRounds = Math.clamp(o.getIntOr("Repair", 0), 0, 100);
        job.paused = o.getBooleanOr("Paused", false);
        // "NaturalOnly": kurzzeitiger Name desselben Schalters auf dem Entwicklungsbranch
        job.terrainOnly = o.getBooleanOr("TerrainOnly", false) || o.getBooleanOr("NaturalOnly", false);
        job.dimension = o.getStringOr("Dimension", "");
        if (job.dimension.isEmpty()) job.dimension = null;
        job.lastReported = job.percent() - job.percent() % 25;
        Set<BlockPos> inPlan = new HashSet<>();
        for (Placement p : order) inPlan.add(p.pos());
        var entries = o.read("Data", net.minecraft.nbt.CompoundTag.CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < entries.size() && i < CompanionSchematics.MAX_DATA_ENTRIES; i++) {
            var e = entries.get(i);
            BlockPos p = BlockPos.of(e.getLongOr("P", 0L));
            // erneut filtern: der Spielstand könnte bearbeitet worden sein
            var safe = e.getCompound("T").map(CompanionSchematics::blockData).orElse(null);
            if (inPlan.contains(p) && safe != null && !safe.isEmpty()) job.data.put(p, safe);
        }
        var ents = o.read("Entities", net.minecraft.nbt.CompoundTag.CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < ents.size() && i < CompanionSchematics.MAX_ENTITIES; i++) {
            var e = ents.get(i);
            double x = e.getDoubleOr("X", Double.NaN), y = e.getDoubleOr("Y", Double.NaN), z = e.getDoubleOr("Z", Double.NaN);
            var tag = e.getCompound("T").map(CompanionSchematics::safeEntityTag).orElse(null);
            if (tag == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;
            job.entities.add(new CompanionSchematics.EntityPlan(new net.minecraft.world.phys.Vec3(x, y, z), tag));
        }
        // Bereits Gebautes für die Abschluss-Aktualisierung (Redstone-Formen) wieder vormerken
        for (int i = 0; i < job.index; i++) if (!order.get(i).state().isAir()) job.placed.add(order.get(i).pos());
        return job;
    }

    private static BuildJob invalidSavedBuild(String reason) {
        com.mojang.logging.LogUtils.getLogger().warn("Cannot save or restore build job: {}", reason);
        return null;
    }

    /**
     * Block-Daten aus dem Schematic (Inhalte, Crafter-Slots, Schildtext ...) auf den eben gesetzten Block
     * übertragen. Kreativ: alles außer Befehlen (CompanionSchematics.blockData). Survival: nur Einstellungen,
     * die nichts erschaffen (gesperrte Crafter-Slots, Schildtext) – sonst wären Kisteninhalte gratis.
     */
    public static void applyData(ServerLevel sl, BuildJob job, BlockPos pos, boolean creative) {
        applyData(sl, job, pos, creative, true);
    }

    /**
     * contents = false: Behälterinhalte (Filter-Trichter, Werfer ...) noch nicht einfüllen. Während des Baus fehlen
     * Fackeln/Redstone, die Trichter sperren; ein Nachbar-Update (Abbau im Weg) entsperrt sie, und der Inhalt
     * liefe in den nächsten Trichter ab. Eingefüllt wird erst im Abschluss ({@link #applyContents}).
     */
    public static void applyData(ServerLevel sl, BuildJob job, BlockPos pos, boolean creative, boolean contents) {
        var data = job.data.get(pos);
        if (data == null) return;
        var be = sl.getBlockEntity(pos);
        if (be == null) return;
        var safe = creative ? CompanionSchematics.blockData(data) : CompanionSchematics.safeBlockData(data);
        if (!contents) safe.remove("Items");
        if (safe.isEmpty()) return;
        var current = be.saveWithoutMetadata(sl.registryAccess());
        current.merge(safe);
        be.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, sl.registryAccess(), current));
        be.setChanged();
        BlockState state = sl.getBlockState(pos);
        sl.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    /**
     * Abschluss (kreativ): zurückgehaltene Behälterinhalte einfüllen – nach dem Update-Durchlauf, wenn Fackeln und
     * Redstone die Filter-Trichter wieder sperren. Danach aus dem Auftrag nehmen (Ausbesserungsrunden und ein
     * gespeicherter Stand füllen nicht doppelt). Gibt die Anzahl befüllter Behälter zurück.
     */
    public static int applyContents(ServerLevel sl, BuildJob job) {
        int filled = 0;
        for (var en : job.data.entrySet()) {
            if (en.getValue().getListOrEmpty("Items").isEmpty() || !sl.hasChunkAt(en.getKey())) continue;
            if (sl.getBlockEntity(en.getKey()) == null) continue;
            applyData(sl, job, en.getKey(), true, true);
            en.getValue().remove("Items");
            filled++;
        }
        return filled;
    }

    /** Behälter mit Inhalt im Plan, den der Survival-Bau nicht aus dem Nichts erschafft (Filter-Trichter ...). */
    public static int pendingContents(BuildJob job) {
        int n = 0;
        for (var tag : job.data.values()) if (!CompanionSchematics.blockData(tag).getListOrEmpty("Items").isEmpty()) n++;
        return n;
    }

    /** Ergebnis von {@link #spawnEntities}: gesetzt, nicht gesetzt (Survival: Lebewesen/Material fehlt), Rahmen ohne Inhalt. */
    public record EntityResult(int spawned, int missing, int emptyFrames) {}

    /** Kreativ: alles setzen, nichts verbrauchen. */
    public static int spawnEntities(ServerLevel sl, BuildJob job) {
        return spawnEntities(sl, job, null).spawned();
    }

    /**
     * Bau fertig: Entities aus dem Plan setzen. Lebewesen (Dorfbewohner, Tiere), Item-Rahmen samt Inhalt, Gemälde,
     * Rüstungsständer, Loren. supplier == null: Kreativ-Bau, alles wie im Plan. Sonst Survival: Lebewesen werden
     * nicht erschaffen; Rahmen/Gemälde/Ständer/Loren kosten ihr Item aus dem Inventar des Begleiters, der
     * Rahmeninhalt ebenso (fehlt er, hängt der Rahmen leer), Ausrüstung und Lorenfracht bleiben weg.
     * Hängende Entities, deren Halteblock schon einen gleichen Rahmen trägt, werden nicht doppelt gesetzt.
     */
    public static EntityResult spawnEntities(ServerLevel sl, BuildJob job, CompanionEntity supplier) {
        boolean creative = supplier == null;
        int spawned = 0, missing = 0, emptyFrames = 0;
        for (var plan : job.entities) {
            var pos = plan.pos();
            BlockPos at = BlockPos.containing(pos);
            if (!sl.hasChunkAt(at) || !sl.getWorldBorder().isWithinBounds(at)) { missing++; continue; }
            var tag = CompanionSchematics.safeEntityTag(plan.tag());
            if (tag == null) continue;
            float yaw = tag.getFloatOr("BegleiterYaw", 0f);
            tag.remove("BegleiterYaw");
            var anchor = tag.getListOrEmpty(CompanionSchematics.ANCHOR);
            tag.remove(CompanionSchematics.ANCHOR);
            var type = net.minecraft.world.entity.EntityType.by(net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, sl.registryAccess(), tag)).orElse(null);
            if (type == null || !allowedEntity(type)) continue;
            boolean hanging = hanging(type);
            BlockPos block = null;
            if (hanging) {
                // Position + Versatz = Mitte des Halteblocks (siehe CompanionSchematics.ANCHOR)
                block = anchor.size() == 3 ? BlockPos.containing(pos.x + anchor.getDoubleOr(0, 0),
                        pos.y + anchor.getDoubleOr(1, 0), pos.z + anchor.getDoubleOr(2, 0)) : at;
                if (!sl.hasChunkAt(block)) { missing++; continue; }
                tag.putIntArray("block_pos", new int[] {block.getX(), block.getY(), block.getZ()});
                if (alreadyHanging(sl, type, block, tag)) continue;
            }
            var p = new net.minecraft.nbt.ListTag();
            p.add(net.minecraft.nbt.DoubleTag.valueOf(pos.x));
            p.add(net.minecraft.nbt.DoubleTag.valueOf(pos.y));
            p.add(net.minecraft.nbt.DoubleTag.valueOf(pos.z));
            tag.put("Pos", p);
            ItemStack content = ItemStack.EMPTY;
            Item cost = null;
            if (!creative) {
                cost = costItem(type);
                if (cost == null || supplier.findItem(cost) < 0) { missing++; continue; }
                for (String k : new String[] {"equipment", "ArmorItems", "HandItems", "body_armor_item", "Items"}) tag.remove(k);
                if (type == net.minecraft.world.entity.EntityTypes.ITEM_FRAME || type == net.minecraft.world.entity.EntityTypes.GLOW_ITEM_FRAME) {
                    var wanted = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(
                            tag.getCompoundOrEmpty("Item").getStringOr("id", ""))).orElse(null);
                    tag.remove("Item");
                    // Ist der Inhalt selbst ein Rahmen, braucht er zwei: einen zum Aufhängen, einen als Inhalt
                    boolean wantsContent = wanted != null && wanted != Items.AIR;
                    if (wantsContent && supplier.getInventory().countItem(wanted) >= (wanted == cost ? 2 : 1))
                        content = supplier.getInventory().getItem(supplier.findItem(wanted)).copyWithCount(1);
                    else if (wantsContent) emptyFrames++;
                }
            }
            if (!creative) tag.remove("Passengers");
            var entity = createPlannedEntity(sl, type, tag, pos, yaw);
            if (entity != null && hanging) {
                // Gemälde mit gerader Breite/Höhe: ihr Mittelpunkt liegt zwischen Blöcken, und welcher Block als
                // Anker gilt, hat sich zwischen Versionen geändert. Maßgeblich ist die gespeicherte Position (die
                // sichtbare Mitte) – liegt das neu berechnete Bild daneben, Anker um diesen Versatz verschieben.
                var d = pos.subtract(entity.position());
                long dx = Math.round(d.x), dy = Math.round(d.y), dz = Math.round(d.z);
                if ((dx | dy | dz) != 0 && Math.abs(dx) <= 2 && Math.abs(dy) <= 2 && Math.abs(dz) <= 2) {
                    block = block.offset((int) dx, (int) dy, (int) dz);
                    if (!sl.hasChunkAt(block)) { missing++; continue; }
                    tag.putIntArray("block_pos", new int[] {block.getX(), block.getY(), block.getZ()});
                    if (alreadyHanging(sl, type, block, tag)) continue;
                    entity = createPlannedEntity(sl, type, tag, pos, yaw);
                }
            }
            if (entity == null) { missing++; auditMissing("cannot create entity", tag); continue; }
            if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity h) {
                // ohne Halt fiele er gleich herunter
                if (!h.survives()) { missing++; auditMissing("no support at " + block.toShortString(), tag); continue; }
            } else {
                entity.snapTo(pos.x, pos.y, pos.z, yaw, entity.getXRot());
                entity.setYHeadRot(yaw);
            }
            if (entity instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
            boolean filled = !creative && !content.isEmpty()
                    && entity instanceof net.minecraft.world.entity.decoration.ItemFrame;
            if (filled) ((net.minecraft.world.entity.decoration.ItemFrame) entity).setItem(content, false);
            if (!sl.tryAddFreshEntityWithPassengers(entity)) { missing++; auditMissing("world rejected entity tree", tag); continue; }
            spawned += (int) entity.getPassengersAndSelf().count();
            // erst abbuchen, wenn die Welt die Entity angenommen hat – sonst wären die Items verloren
            if (!creative) {
                take(supplier, cost);
                if (filled) take(supplier, content.getItem());
            }
        }
        job.entities.clear(); // nicht doppelt setzen (Ausbesserungsrunden, erneutes Fertigmelden)
        return new EntityResult(spawned, missing, emptyFrames);
    }

    private static net.minecraft.world.entity.Entity createPlannedEntity(ServerLevel sl,
            net.minecraft.world.entity.EntityType<?> type, net.minecraft.nbt.CompoundTag tag,
            net.minecraft.world.phys.Vec3 pos, float yaw) {
        float originalYaw = tag.getListOrEmpty("Rotation").getFloatOr(0, 0);
        float turn = yaw - originalYaw;
        return net.minecraft.world.entity.EntityType.loadEntityRecursive(type, tag, sl,
                net.minecraft.world.entity.EntitySpawnReason.STRUCTURE, entity -> {
                    if (!(entity instanceof net.minecraft.world.entity.decoration.HangingEntity)) {
                        float rotatedYaw = entity.getYRot() + turn;
                        entity.snapTo(pos.x, pos.y, pos.z, rotatedYaw, entity.getXRot());
                        entity.setYHeadRot(rotatedYaw);
                    }
                    if (entity instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
                    return entity;
                });
    }

    private static void auditMissing(String why, net.minecraft.nbt.CompoundTag tag) {
        if (CompanionSchematics.DEBUG_AUDIT) {
            String t = tag.toString();
            com.mojang.logging.LogUtils.getLogger().info("AUDIT-DETAIL Entity fehlt ({}): {}", why, t.length() > 300 ? t.substring(0, 300) : t);
        }
    }

    /** Hängt an diesem Halteblock schon ein Rahmen/Gemälde derselben Art in dieselbe Richtung? */
    private static boolean alreadyHanging(ServerLevel sl, net.minecraft.world.entity.EntityType<?> type, BlockPos block,
                                          net.minecraft.nbt.CompoundTag tag) {
        for (var e : sl.getEntitiesOfClass(net.minecraft.world.entity.decoration.HangingEntity.class,
                new net.minecraft.world.phys.AABB(block).inflate(0.5), e -> e.getType() == type)) {
            if (!e.getPos().equals(block)) continue;
            int frame = tag.getByteOr("Facing", (byte) -1), painting = tag.getByteOr("facing", (byte) -1);
            if (frame >= 0 && e.getDirection().get3DDataValue() == frame) return true;
            if (painting >= 0 && e.getDirection().get2DDataValue() == painting) return true;
        }
        return false;
    }

    private static void take(CompanionEntity supplier, Item item) {
        int slot = supplier.findItem(item);
        if (slot >= 0) supplier.getInventory().getItem(slot).shrink(1);
    }

    /** Was eine nicht lebende Entity im Survival kostet; null = nicht im Survival setzbar (Lebewesen). */
    static Item costItem(net.minecraft.world.entity.EntityType<?> type) {
        if (type == net.minecraft.world.entity.EntityTypes.ITEM_FRAME) return Items.ITEM_FRAME;
        if (type == net.minecraft.world.entity.EntityTypes.GLOW_ITEM_FRAME) return Items.GLOW_ITEM_FRAME;
        if (type == net.minecraft.world.entity.EntityTypes.PAINTING) return Items.PAINTING;
        if (type == net.minecraft.world.entity.EntityTypes.ARMOR_STAND) return Items.ARMOR_STAND;
        if (type == net.minecraft.world.entity.EntityTypes.MINECART) return Items.MINECART;
        if (type == net.minecraft.world.entity.EntityTypes.HOPPER_MINECART) return Items.HOPPER_MINECART;
        if (type == net.minecraft.world.entity.EntityTypes.CHEST_MINECART) return Items.CHEST_MINECART;
        if (type == net.minecraft.world.entity.EntityTypes.FURNACE_MINECART) return Items.FURNACE_MINECART;
        return null;
    }

    /** Rahmen und Gemälde: hängen an einem Block (block_pos + Richtung) statt frei an einer Position. */
    static boolean hanging(net.minecraft.world.entity.EntityType<?> type) {
        return type == net.minecraft.world.entity.EntityTypes.ITEM_FRAME || type == net.minecraft.world.entity.EntityTypes.GLOW_ITEM_FRAME
                || type == net.minecraft.world.entity.EntityTypes.PAINTING;
    }

    /**
     * Gespeicherte Lebewesen und Bauzubehör, auch Boote samt Insassen. Lebewesen erschafft nur der Kreativ-Bau;
     * im Survival braucht jede Entity ein costItem. Herumliegende Items, XP und Geschosse gehören nicht zum Bau.
     */
    static boolean allowedEntity(net.minecraft.world.entity.EntityType<?> type) {
        if (costItem(type) != null) return true;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        if (id.endsWith("_boat") || id.endsWith("_raft") || id.endsWith("minecart")) return true;
        return type != net.minecraft.world.entity.EntityTypes.PLAYER && type != CompanionMod.COMPANION
                && type.getCategory() != net.minecraft.world.entity.MobCategory.MISC
                || type == net.minecraft.world.entity.EntityTypes.VILLAGER || type == net.minecraft.world.entity.EntityTypes.IRON_GOLEM
                || type == net.minecraft.world.entity.EntityTypes.SNOW_GOLEM || type == net.minecraft.world.entity.EntityTypes.WANDERING_TRADER;
    }

    /** Bauauftrag aus Positionen -> Zuständen (Luft = dort wegräumen). */
    public static BuildJob create(ServerLevel level, String name, Map<BlockPos, BlockState> blocks) {
        CompanionSchematics.validatePlacement(level, blocks);
        BuildJob job = create(name, blocks);
        job.inDimension(level);
        return job;
    }

    public static BuildJob createTerrain(ServerLevel level, String name, Map<BlockPos, BlockState> blocks) {
        BuildJob job = create(level, name, blocks);
        job.terrainOnly = true;
        return job;
    }

    public static BuildJob create(String name, Map<BlockPos, BlockState> blocks) {
        List<Placement> clear = new ArrayList<>(), build = new ArrayList<>();
        for (var en : blocks.entrySet()) {
            Placement p = new Placement(en.getKey().immutable(), en.getValue());
            (en.getValue().isAir() ? clear : build).add(p);
        }
        clear.sort(Comparator.comparingInt((Placement p) -> -p.pos().getY())); // von oben wegräumen
        build.sort(Comparator.comparingInt((Placement p) -> order(p, blocks))
                .thenComparingInt(p -> p.pos().getY())
                .thenComparingInt(p -> p.pos().getX())
                .thenComparingInt(p -> p.pos().getZ()));
        List<Placement> all = new ArrayList<>(clear);
        all.addAll(build);
        return new BuildJob(name, all);
    }

    /**
     * Reihenfolge-Stufe: {@link #priority} verdoppelt; ein Block, der Halt braucht, kommt aber erst nach dem Block
     * unter ihm. Sonst stünde z. B. ein Comparator auf einem (als Energiequelle spät gesetzten) Beobachter vor dem
     * Beobachter in der Luft und fiele beim ersten Nachbar-Update (Nachbar-Comparator, Trichter) als Item ab.
     */
    static int order(Placement p, Map<BlockPos, BlockState> blocks) {
        int own = priority(p.state()) * 2;
        if (!needsSupport(p.state().getBlock())) return own;
        BlockState below = blocks.get(p.pos().below());
        if (below == null || below.isAir()) return own;
        int support = priority(below) * 2;
        return support >= own ? support + 1 : own;
    }

    /** 0 = tragend, 1 = sonstige, 2 = Angehängtes, 3 = Energiequellen, 4 = Ackerboden, 5 = Pflanzen, 6 = Tore. */
    static int priority(BlockState s) {
        Block b = s.getBlock();
        // Nutzpflanzen erst setzen, wenn Laufwege, Wasser und Technik fertig sind.
        if (b == Blocks.FARMLAND) return 4;
        if (b instanceof CropBlock || b instanceof StemBlock || b instanceof SugarCaneBlock || b instanceof BambooStalkBlock) return 5;
        // Gehege erst schließen, wenn auch hintere Pfosten und Pflanzen fertig sind.
        if (b instanceof FenceGateBlock) return 6;
        if (b instanceof RedstoneTorchBlock || b == Blocks.REDSTONE_BLOCK || b instanceof LeverBlock
                || b instanceof ObserverBlock || b instanceof DaylightDetectorBlock || b instanceof TargetBlock) return 3;
        // Zugänge vor Scheiben und Dach: sonst schließt er sich in einem Stockwerk ohne Tür und Leiter ein
        if (b instanceof DoorBlock || b instanceof LadderBlock) return 1;
        if (needsSupport(b)) return 2;
        if (Block.isShapeFullBlock(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) return 0;
        return 1;
    }

    static boolean needsSupport(Block b) {
        return b instanceof TorchBlock || b instanceof WallTorchBlock || b instanceof RedstoneWireBlock
                || b instanceof DiodeBlock || b instanceof BaseRailBlock || b instanceof CarpetBlock
                || b instanceof ButtonBlock || b instanceof BasePressurePlateBlock || b instanceof DoorBlock
                || b instanceof BedBlock || b instanceof LadderBlock || b instanceof VineBlock
                || b instanceof TripWireHookBlock || b instanceof TripWireBlock || b instanceof BushBlock
                || b instanceof FlowerPotBlock || b instanceof SignBlock || b instanceof BannerBlock
                || b instanceof LanternBlock || b instanceof SnowLayerBlock || b instanceof ScaffoldingBlock
                || b instanceof FallingBlock;
    }

    /** Welches Item kostet dieser Zustand? null = nichts (obere Türhälfte, Kopfteil Bett, Luft ...). */
    public static Item itemFor(BlockState s) {
        if (s.isAir()) return null;
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
            return null;
        if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return null;
        if (s.getBlock() instanceof net.minecraft.world.level.block.piston.PistonHeadBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.piston.MovingPistonBlock) return null;
        if (s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH)) return Items.DIRT; // mit der Hacke aus Erde
        if (s.getBlock() instanceof LiquidBlock) {
            return s.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER) ? Items.WATER_BUCKET
                    : s.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.LAVA) ? Items.LAVA_BUCKET : null;
        }
        Item it = s.getBlock().asItem();
        return it == Items.AIR ? null : it;
    }

    public static int itemCount(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE) && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
        if (s.getBlock() instanceof CandleBlock) return s.getValue(BlockStateProperties.CANDLES);
        if (s.getBlock() instanceof SeaPickleBlock) return s.getValue(BlockStateProperties.PICKLES);
        if (s.getBlock() instanceof SnowLayerBlock) return s.getValue(BlockStateProperties.LAYERS);
        return 1;
    }

    /** So lange gilt eine Zählung höchstens, danach wird neu gezählt (Spieler, Mobs, Wachstum ändern die Welt). */
    static final int MATERIAL_CACHE_TICKS = 200;
    /** Für Tests: Anzahl vollständiger Zählungen über den Bauplan. */
    static long materialRecounts;

    /** Gezähltes Restmaterial eines Auftrags: Welt, Zeitpunkt und Bauindex der Zählung. */
    static final class MaterialCount {
        final ServerLevel level;
        final long at;
        int index;
        final Map<Item, Integer> counts;

        MaterialCount(ServerLevel level, long at, int index, Map<Item, Integer> counts) {
            this.level = level;
            this.at = at;
            this.index = index;
            this.counts = counts;
        }
    }

    /**
     * Material, das für den Rest noch gesetzt werden muss (was schon richtig steht, zählt nicht). Eine Zählung gilt
     * höchstens {@link #MATERIAL_CACHE_TICKS} Ticks; selbst gesetzte Blöcke zieht {@link #notePlaced} sofort ab. Neu
     * gezählt wird nach Ablauf, bei Rücksprung des Index (Ausbessern), Weltwechsel und für jeden neuen Auftrag.
     * Liefert eine eigene, veränderbare Kopie.
     */
    public static Map<Item, Integer> remaining(ServerLevel sl, BuildJob job) {
        long now = sl.getGameTime();
        MaterialCount cached = job.materials;
        if (cached == null || cached.level != sl || job.index < cached.index || now - cached.at >= MATERIAL_CACHE_TICKS) {
            cached = new MaterialCount(sl, now, job.index, count(sl, job));
            job.materials = cached;
        }
        cached.index = job.index;
        return new LinkedHashMap<>(cached.counts);
    }

    /** Auftrag selbst gesetzt: aus der gemerkten Zählung abziehen, statt den ganzen Plan neu zu zählen. */
    public static void notePlaced(BuildJob job, Placement p) {
        if (job.materials == null) return;
        Item it = itemFor(p.state());
        if (it == null) return;
        job.materials.counts.computeIfPresent(it, (item, n) -> n > itemCount(p.state()) ? n - itemCount(p.state()) : null);
    }

    /** Gemerkte Zählung verwerfen (z. B. nach Änderungen am Plan). */
    static void forgetMaterials(BuildJob job) {
        job.materials = null;
    }

    private static Map<Item, Integer> count(ServerLevel sl, BuildJob job) {
        materialRecounts++;
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = job.index; i < job.order.size(); i++) {
            Placement p = job.order.get(i);
            // ungeladen: unbekannt, also vorsichtshalber als noch zu setzen zählen (ohne den Chunk zu laden)
            if (sl.hasChunkAt(p.pos()) && matches(sl.getBlockState(p.pos()), p.state())) continue;
            Item it = itemFor(p.state());
            if (it != null) m.merge(it, itemCount(p.state()), Integer::sum);
        }
        return m;
    }

    /**
     * Abschluss: Formen nach den Nachbarn berechnen (Staub verbindet sich, Zäune/Scheiben schließen an), dann ein
     * Update-Durchlauf (Redstone berechnet Strom, Kolben/Lampen reagieren). Gibt die Anzahl falscher Blöcke zurück
     * (bei Bauten mit Kolben wird nicht nachkontrolliert – die verschieben absichtlich Blöcke).
     */
    public static int finish(ServerLevel sl, BuildJob job) {
        return finishChecked(sl, job).mismatches();
    }

    public record Verification(int mismatches, int unknown, int dynamic, int preserved) {
        public boolean complete() { return mismatches == 0 && unknown == 0; }
    }

    /** Fertigen Bau aktualisieren, dann feste Blöcke und geplante Freiräume prüfen – ohne Chunks zu laden. */
    public static Verification finishChecked(ServerLevel sl, BuildJob job) {
        // Sehr große Bauten: was inzwischen ungeladen ist, bleibt unberührt (kein Nachladen)
        for (BlockPos p : job.placed) {
            if (!sl.hasChunkAt(p)) continue;
            BlockState s = sl.getBlockState(p);
            if (!shapeFromNeighbours(s)) continue;
            BlockState shaped = Block.updateFromNeighbourShapes(s, sl, p);
            if (!shaped.equals(s) && !shaped.isAir()) sl.setBlock(p, shaped, PLACE_FLAGS);
        }
        for (BlockPos p : job.placed) {
            if (!sl.hasChunkAt(p)) continue;
            BlockState state = sl.getBlockState(p);
            sl.updateNeighborsAt(p, state.getBlock(), null);
            // onPlace wird beim Bau übersprungen. Wasser/Lava brauchen nach Fertigstellung ihren ersten Tick,
            // damit Sammelrinnen und Bewässerung ebenso wie normal gesetzte Quellen fließen.
            if (state.getBlock() instanceof LiquidBlock && !state.getFluidState().isEmpty())
                sl.scheduleTick(p, state.getFluidState().getType(), state.getFluidState().getType().getTickDelay(sl));
            // Schematics enthalten Blockzustände, aber keine geplanten Ticks. Eine Beobachter-Uhr (zwei Beobachter
            // Gesicht an Gesicht) bliebe sonst stehen, weil das Setzen onPlace und Form-Updates bewusst überspringt.
            if (state.is(Blocks.OBSERVER)) {
                var facing = state.getValue(ObserverBlock.FACING);
                var other = p.relative(facing);
                var neighbour = dev.begleiter.world.SafeLevel.state(sl, other); // Nachbar kann im ungeladenen Chunk liegen
                if (neighbour != null && p.compareTo(other) < 0 && neighbour.is(Blocks.OBSERVER)
                        && neighbour.getValue(ObserverBlock.FACING) == facing.getOpposite())
                    sl.scheduleTick(p, state.getBlock(), 2);
            }
        }
        Set<BlockPos> movable = pistonMovementPositions(job);
        int wrong = 0, unknown = 0, dynamic = 0, preserved = 0;
        for (Placement p : job.order) {
            if (movable.contains(p.pos())) { dynamic++; continue; }
            if (!sl.hasChunkAt(p.pos())) { unknown++; continue; }
            BlockState actual = sl.getBlockState(p.pos());
            if (matches(actual, p.state())) continue;
            // Bambus, Zuckerrohr, Kaktus, Seetang wachsen nach dem Setzen in die geplante Luft darüber: Betrieb, kein Fehler
            if (p.state().isAir() && grownColumn(sl, p.pos(), actual)) { dynamic++; continue; }
            // Fließendes Wasser/Lava aus einer gebauten Quelle in geplanter Luft (Farm-Rinnen): ebenfalls Betrieb
            if (p.state().isAir() && actual.getBlock() instanceof LiquidBlock && !actual.getFluidState().isSource()) { dynamic++; continue; }
            // Gras/Myzel wächst über Erde, Weichen schalten Schienen um: Welt, kein Baufehler
            if (naturalChange(p.state(), actual)) { dynamic++; continue; }
            // Terraforming lässt nach der Planung Gebautes bewusst stehen: erlaubte Ausnahme, kein fehlender Block.
            if (job.terrainOnly && (sl.getBlockEntity(p.pos()) != null || !CompanionTerraform.natural(sl, p.pos(), actual))) {
                preserved++;
                continue;
            }
            wrong++;
        }
        return new Verification(wrong, unknown, dynamic, preserved);
    }

    /**
     * Änderungen, die die Welt selbst am fertigen Bau vornimmt und die eine Ausbesserung sofort wieder rückgängig
     * machen würde: Gras/Myzel breitet sich auf Erde aus, bedecktes Gras wird zu Erde,
     * eine einfache Schiene ändert ihre Form (Weiche per Redstone, Anschluss an Nachbarschienen).
     */
    static boolean naturalChange(BlockState expected, BlockState actual) {
        Block want = expected.getBlock(), have = actual.getBlock();
        if (want == Blocks.DIRT) return have == Blocks.GRASS_BLOCK || have == Blocks.MYCELIUM;
        // Ausgetrockneter Ackerboden zählt nicht: ist inzwischen Wasser da, hält die Ausbesserung, sonst meldet er es
        if (want == Blocks.GRASS_BLOCK || want == Blocks.MYCELIUM) return have == Blocks.DIRT;
        return want == Blocks.RAIL && have == Blocks.RAIL;
    }

    /** Säulenpflanze, die aus derselben Pflanze darunter nachgewachsen ist (Bambus, Zuckerrohr, Kaktus, Seetang). */
    static boolean grownColumn(ServerLevel sl, BlockPos pos, BlockState actual) {
        Block b = actual.getBlock();
        boolean column = b instanceof BambooStalkBlock || b instanceof SugarCaneBlock || b instanceof CactusBlock
                || b instanceof KelpPlantBlock || b instanceof KelpBlock;
        if (!column) return false;
        Block below = sl.getBlockState(pos.below()).getBlock();
        return below == b || b instanceof KelpBlock && below instanceof KelpPlantBlock
                || b instanceof KelpPlantBlock && below instanceof KelpPlantBlock;
    }

    /**
     * Redstone-Abschlussdiagnose: prüft statische Bauteile nach dem Aktivieren.
     * Dynamische Betriebszustände (Lampe an, Repeater-Power, Kolben ausgefahren)
     * sind keine Baufehler. Bewegliche Schubpositionen werden gesondert behandelt.
     */
    public static List<BlockPos> redstoneMismatches(ServerLevel sl, BuildJob job) {
        Set<BlockPos> movable = pistonMovementPositions(job);
        List<BlockPos> wrong = new ArrayList<>();
        for (Placement p : job.order) {
            BlockState expected = p.state();
            if (expected.isAir() || movable.contains(p.pos()) || !sl.hasChunkAt(p.pos())) continue;
            Block b = expected.getBlock();
            if (!(b instanceof RedstoneWireBlock || b instanceof DiodeBlock
                    || b instanceof LeverBlock || b instanceof RedstoneTorchBlock
                    || b instanceof ObserverBlock || b instanceof RedstoneLampBlock
                    || b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                    || b instanceof ComparatorBlock)) continue;
            if (!matches(sl.getBlockState(p.pos()), expected)) wrong.add(p.pos());
        }
        return wrong;
    }

    /** Kolben-Schubrichtung: potenziell verschobene Positionen, aber nie der Kolbenblock selbst. */
    static Set<BlockPos> pistonMovementPositions(BuildJob job) {
        Set<BlockPos> moved = new HashSet<>();
        Set<BlockPos> pistons = new HashSet<>();
        for (Placement p : job.order) {
            if (!(p.state().getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)) continue;
            pistons.add(p.pos());
            net.minecraft.core.Direction dir = p.state().getValue(BlockStateProperties.FACING);
            for (int n = 1; n <= 13; n++) moved.add(p.pos().relative(dir, n));
        }
        moved.removeAll(pistons);
        return moved;
    }

    static boolean mayBeMovedByPiston(BuildJob job, BlockPos pos) {
        return pistonMovementPositions(job).contains(pos);
    }


    // ------------------------------------------------------------------ Material beschaffen (Survival)

    /** When already at home, collect surplus for the next group of placements in one visit. */
    public static void stageBuildMaterials(CompanionEntity mob, ServerLevel world, BuildJob job) {
        BlockPos home = mob.getHome();
        Container box = CompanionTripPreparation.reachableHomeStorage(mob, world, home);
        if (box == null) return;
        int lookAhead = mob.work.is(CompanionWork.Kind.BUILD) ? mob.work.remaining() : 24;
        int packed = 0;
        for (var need : upcomingMaterials(world, job, lookAhead).entrySet()) {
            Item item = need.getKey();
            int missing = Math.min(need.getValue(), mob.work.inputAllowance(item)) - mob.getInventory().countItem(item);
            int available = box.countItem(item) - mob.stockReserveAt(world, home, item);
            int amount = Math.min(missing, available);
            if (amount <= 0) continue;
            var preview = new CompanionPrerequisites.Inventory(mob, true);
            if (preview.addFrom(box, item, amount) != 0 || preview.freeSlots() < 2) continue;
            packed += takeFrom(mob, box, item, amount);
        }
        if (packed > 0) {
            CompanionTripPreparation.recordStorageUse(mob, world, home, box);
            mob.logInfo(CompanionTexts.tr("order.build_packed", packed));
        }
    }

    /** Forecast the next positions from loaded world state, including material counts for multi-item placements. */
    static Map<Item, Integer> upcomingMaterials(ServerLevel world, BuildJob job, int lookAhead) {
        Map<Item, Integer> upcoming = new java.util.LinkedHashMap<>();
        for (int i = job.index; i < Math.min(job.index + lookAhead, job.order.size()); i++) {
            var block = job.order.get(i);
            if (!world.hasChunkAt(block.pos()) || matches(world.getBlockState(block.pos()), block.state())) continue;
            Item item = itemFor(block.state());
            if (item != null && item != Items.AIR) upcoming.merge(item, itemCount(block.state()), Integer::sum);
        }
        return upcoming;
    }

    /** Rohstoffe, die er abbauen kann. */
    static Resources.Res rawSource(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (new ItemStack(item).is(net.minecraft.tags.ItemTags.LOGS)) return Resources.WOOD;
        return switch (id) {
            case "minecraft:cobblestone", "minecraft:cobbled_deepslate" -> Resources.STONE;
            case "minecraft:dirt" -> Resources.DIRT;
            case "minecraft:sand", "minecraft:red_sand" -> Resources.SAND;
            case "minecraft:gravel", "minecraft:flint" -> Resources.GRAVEL;
            case "minecraft:coal" -> Resources.COAL;
            case "minecraft:raw_iron" -> Resources.IRON;
            case "minecraft:raw_gold" -> Resources.GOLD;
            case "minecraft:raw_copper" -> Resources.COPPER;
            case "minecraft:diamond" -> Resources.DIAMOND;
            case "minecraft:redstone" -> Resources.REDSTONE;
            case "minecraft:lapis_lazuli" -> Resources.LAPIS;
            case "minecraft:emerald" -> Resources.EMERALD;
            case "minecraft:obsidian" -> Resources.OBSIDIAN;
            default -> null;
        };
    }

    /**
     * Aufgaben, um insgesamt count Stück im Inventar zu haben: aus Kisten nehmen, abbauen, schmelzen oder craften
     * (rekursiv). null = geht nicht selbst (Spieler fragen).
     */
    public static List<Task> acquire(CompanionEntity e, ServerLevel sl, Item item, int count) {
        // Zielplaner: günstigster Weg mit gedachtem Inventar; sonst der einfache rekursive Weg
        CompanionPlanner.Plan plan = CompanionPlanner.obtain(e, item, count);
        if (plan != null) {
            e.logInfo(CompanionTexts.tr("log.material.plan", count, new ItemStack(item).getHoverName(), CompanionTexts.plan(plan)));
            return new ArrayList<>(plan.tasks());
        }
        return acquire(e, sl, item, count, new HashSet<>(), 0);
    }

    private static List<Task> acquire(CompanionEntity e, ServerLevel sl, Item item, int count, Set<Item> visiting, int depth) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        int need = count - CompanionCraft.count(e, id);
        if (need <= 0) return new ArrayList<>();
        if (depth > 4 || !visiting.add(item)) return null;
        try {
            BlockPos box = e.findContainerWith(item, 16);
            if (box != null) return new ArrayList<>(List.of(Task.take(box, id, need)));

            // Wassereimer: Eimer besorgen und an einer Quelle füllen
            if (item == Items.WATER_BUCKET) {
                List<Task> sub = acquire(e, sl, Items.BUCKET, 1, visiting, depth + 1);
                if (sub == null) return null;
                sub.add(Task.water(null));
                return sub;
            }
            // Samen: Gras abbauen, nur etwa jedes 8. gibt Samen
            if (item == Items.WHEAT_SEEDS)
                return new ArrayList<>(List.of(Task.gather(Resources.SEEDS.key(), need)));

            Resources.Res raw = rawSource(item);
            if (raw == Resources.WOOD) {
                if (Block.byItem(item) == Blocks.AIR) return null;
                return new ArrayList<>(List.of(new Task(new dev.begleiter.task.TaskSpec.Gather(raw.key(), need, 0, id))));
            }
            if (raw != null) return new ArrayList<>(List.of(Task.gather(raw.key(), need)));

            CompanionRecipes.Smelt smelt = CompanionRecipes.smeltFor(sl, item);
            if (smelt != null) {
                Item in = CompanionRecipes.preferred(e, smelt.input());
                List<Task> sub = acquire(e, sl, in, CompanionCraft.count(e, BuiltInRegistries.ITEM.getKey(in).toString()) + need,
                        visiting, depth + 1);
                if (sub != null) {
                    sub.add(Task.smelt(id, count));
                    return sub;
                }
            }

            for (CompanionRecipes.Craft c : CompanionRecipes.craftsFor(sl, item)) {
                int times = (need + c.count() - 1) / c.count();
                List<Task> plan = new ArrayList<>();
                boolean ok = true;
                for (var en : CompanionRecipes.grouped(c).entrySet()) {
                    Item in = CompanionRecipes.preferred(e, en.getKey());
                    List<Task> sub = acquire(e, sl, in, en.getValue() * times, visiting, depth + 1);
                    if (sub == null) { ok = false; break; }
                    plan.addAll(sub);
                }
                if (!ok) continue;
                plan.add(Task.craft(id, need));
                return plan;
            }
            return null;
        } finally {
            visiting.remove(item);
        }
    }

    /** Aus einer Kiste nehmen; gibt die genommene Anzahl zurück. */
    public static int takeFrom(CompanionEntity e, Container box, Item item, int max) {
        int taken = 0;
        for (int i = 0; i < box.getContainerSize() && taken < max; i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            int n = Math.min(max - taken, s.getCount());
            ItemStack part = s.copyWithCount(n);
            ItemStack rest = e.giveToInventory(part);
            int moved = n - rest.getCount();
            s.shrink(moved);
            taken += moved;
            if (!rest.isEmpty()) break; // Inventar voll
        }
        box.setChanged();
        return taken;
    }
}
