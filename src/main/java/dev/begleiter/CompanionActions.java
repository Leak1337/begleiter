package dev.begleiter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Die Menübefehle: Metadaten für die Oberfläche plus Handler, die an Entity/Brain delegieren. */
public final class CompanionActions {
    @FunctionalInterface public interface Handler {
        net.minecraft.network.chat.Component executeComponent(CompanionEntity companion, net.minecraft.server.level.ServerPlayer player, boolean strong);
        default String execute(CompanionEntity companion, net.minecraft.server.level.ServerPlayer player, boolean strong) {
            var reply = executeComponent(companion, player, strong);
            return reply == null ? null : reply.getString();
        }
    }
    public record Action(int button, String id, String title, String icon, int mode, Handler handler) {}
    private static final Map<Integer, Action> ACTIONS = new LinkedHashMap<>();
    static {
        register(0, "follow", "Follow", "lead", 0, (c, p, strong) -> { var held = c.holdOrdersComponent(); c.follow(); return CompanionTexts.tr("reply.follow").append(held); });
        register(1, "stay", "Stay", "oak_sign", 1, (c, p, strong) -> { var held = c.holdOrdersComponent(); c.stay(); return CompanionTexts.tr("reply.stay").append(held); });
        register(2, "free", "Play freely", "iron_pickaxe", 2,
                (c, p, strong) -> { var held = c.holdOrdersComponent(); c.freeRoam(); return CompanionTexts.tr("reply.free").append(held); });
        register(3, "keeper", "Storage keeper", "chest", 3, (c, p, strong) -> {
            var held = c.holdOrdersComponent();
            return CompanionTexts.tr(c.makeKeeper() ? "reply.keeper" : "reply.no_home").append(held);
        });
        register(4, "resume", "Resume", "lime_dye", -1, (c, p, strong) -> CompanionBrain.resumeReply(c));
        register(5, "home", "Set storage", "ender_chest", -1, (c, p, strong) -> CompanionBrain.orderSetHomeReply(p, c));
        register(6, "cancel", "Cancel", "barrier", -1, (c, p, strong) -> { c.cancelOrders(); return CompanionTexts.tr("reply.cancel"); });
        register(7, "give", "Hand over all", "dropper", -1, (c, p, strong) -> CompanionBrain.orderGiveAllReply(c));

        Resources.Res[] res = {Resources.WOOD, Resources.STONE, Resources.COAL, Resources.IRON, Resources.GOLD,
                Resources.DIAMOND, Resources.REDSTONE, Resources.SAND, Resources.DIRT};
        String[] ids = {"wood", "stone", "coal", "iron", "gold", "diamond", "redstone", "sand", "dirt"};
        String[] names = {"wood", "stone", "coal", "iron", "gold", "diamonds", "redstone", "sand", "dirt"};
        String[] icons = {"oak_log", "cobblestone", "coal", "raw_iron", "raw_gold", "diamond", "redstone", "sand", "dirt"};
        for (int i = 0; i < ids.length; i++) {
            Resources.Res r = res[i];
            register(8 + i, "gather_" + ids[i], "Get " + names[i], icons[i], -1,
                    (c, p, strong) -> CompanionBrain.orderGatherReply(c, r, strong ? 64 : 16, true));
        }

        register(17, "house", "Build house", "oak_planks", -1,
                (c, p, strong) -> CompanionBrain.orderStructureReply(p, c, List.of("haus")));
        register(18, "sort", "Sort chests", "hopper", -1, (c, p, strong) -> CompanionBrain.orderSortReply(p, c));
        register(19, "tidy", "Store items", "barrel", -1, (c, p, strong) -> CompanionBrain.orderTidyReply(c));
        register(20, "hunt", "Hunt", "cooked_beef", -1, (c, p, strong) -> CompanionBrain.orderHuntReply(c));
        register(21, "smelt", "Smelt", "furnace", -1, (c, p, strong) -> CompanionBrain.orderSmeltReply(c));
        register(22, "sheep", "Fetch sheep", "white_wool", -1, (c, p, strong) -> CompanionBrain.orderFetchReply(c, "sheep"));
        register(23, "cow", "Fetch cow", "beef", -1, (c, p, strong) -> CompanionBrain.orderFetchReply(c, "cow"));
        register(24, "pig", "Fetch pig", "porkchop", -1, (c, p, strong) -> CompanionBrain.orderFetchReply(c, "pig"));
        register(25, "animal", "Fetch animal", "lead", -1, (c, p, strong) -> CompanionBrain.orderFetchReply(c, "any"));

        // Alltag: Feld, Zucht, Pflege, Handel, Wegpunkte, Ausrüstung
        register(26, "farm", "Harvest field", "wheat", -1, (c, p, strong) -> CompanionBrain.orderFarmReply(p, c));
        register(27, "breed", "Breed animals", "hay_block", -1, (c, p, strong) -> CompanionBrain.orderBreedReply(c, null, 0));
        register(28, "repair", "Repair", "anvil", -1, (c, p, strong) -> CompanionBrain.orderRepairReply(c));
        register(29, "trade", "Sell surplus", "emerald", -1, (c, p, strong) -> CompanionBrain.orderTradeReply(c, null, 0, false));
        register(30, "mark", "Mark spot", "compass", -1, (c, p, strong) -> {
            // Der Name ist danach Spielerdaten, deshalb in der Sprache des Spielers.
            String prefix = spotPrefix(p.clientInformation().language());
            int n = 1;
            while (hasWaypoint(c, prefix + " " + n)) n++;
            List<String> command = List.of("merk", "dir", "das", "als", prefix, String.valueOf(n));
            return CompanionBrain.waypointCommandReply(p, c, command, String.join(" ", command));
        });
        register(31, "waypoints", "Waypoints", "map", -1, (c, p, strong) ->
                CompanionBrain.waypointCommandReply(p, c, List.of("welche", "wegpunkte", "kennst", "du"), "welche wegpunkte kennst du"));
        register(32, "gear", "Gear up", "iron_chestplate", -1,
                (c, p, strong) -> CompanionBrain.orderGearUpReply(c, List.of(strong ? "diamant" : "eisen")));

        register(36, "flatten", "Flatten", "iron_shovel", -1, (c, p, strong) -> CompanionBrain.orderTerraformReply(p, c,
                CompanionTerraform.Mode.FLATTEN, List.of(strong ? "21" : "11")));

        // Daueraufgaben (Seite "Aufgaben"): bleibt am Ort und erledigt die Arbeit immer wieder von selbst
        register(37, "duty_farm", "Tend the fields", "wheat", CompanionDuties.Duty.FARM.mode,
                (c, p, strong) -> CompanionDuties.startFarm(p, c));
        register(38, "duty_animals", "Look after the animals", "hay_block", CompanionDuties.Duty.ANIMALS.mode,
                (c, p, strong) -> CompanionDuties.startAnimals(p, c));
        register(39, "duty_trade", "Trade with villagers", "emerald", CompanionDuties.Duty.TRADE.mode,
                (c, p, strong) -> CompanionDuties.startTrade(p, c));

        register(40, "duty_explore", "Explore the area", "compass", CompanionDuties.Duty.EXPLORE.mode,
                (c, p, strong) -> CompanionDuties.startZoned(c, CompanionDuties.Duty.EXPLORE));
        register(41, "duty_base", "Look after the base", "oak_door", CompanionDuties.Duty.BASE.mode,
                (c, p, strong) -> CompanionDuties.startZoned(c, CompanionDuties.Duty.BASE));
        register(42, "duty_storage", "Look after chests", "chest", CompanionDuties.Duty.STORAGE.mode,
                (c, p, strong) -> CompanionDuties.startZoned(c, CompanionDuties.Duty.STORAGE));
        register(43, "duty_fetch", "Bring animals to the pen", "lead", CompanionDuties.Duty.FETCH.mode,
                (c, p, strong) -> CompanionDuties.startZoned(c, CompanionDuties.Duty.FETCH));
        register(44, "zones", "Map and zones", "filled_map", -1, (c, p, strong) -> CompanionZoneEditor.begin(p, c));

        // Kampfmodus
        for (CompanionCombat.Stance st : CompanionCombat.Stance.values()) {
            String title = st.name().charAt(0) + st.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            String icon = switch (st) { case AGGRESSIVE -> "iron_sword"; case DEFENSIVE -> "shield"; case PASSIVE -> "poppy"; };
            register(33 + st.ordinal(), "stance_" + st.label, title, icon, -1, (c, p, strong) -> { c.stance = st; return CompanionTexts.tr("reply.stance", CompanionTexts.stance(st)); });
        }
    }
    private CompanionActions() {}
    /** Namensstamm für Orte aus dem Menüknopf: "ort" für Deutsch, sonst "spot". */
    static String spotPrefix(String language) {
        return language != null && language.toLowerCase(java.util.Locale.ROOT).startsWith("de") ? "ort" : "spot";
    }
    private static boolean hasWaypoint(CompanionEntity c, String name) {
        return c.memory.waypoints().stream().anyMatch(w -> w.key.equals(name));
    }
    public static void register(int button, String id, String title, String icon, int mode, Handler handler) {
        register(new Action(button, id, title, icon, mode, handler));
    }
    public static void register(Action action) {
        if (action.button() < 0 || action.button() >= 100 || ACTIONS.containsKey(action.button())
                || action.handler() == null || ACTIONS.values().stream().anyMatch(a -> a.id().equals(action.id())))
            throw new IllegalArgumentException("Duplicate or invalid companion action: " + action.id());
        ACTIONS.put(action.button(), action);
    }
    public static Action get(int button) { return ACTIONS.get(button); }
    public static List<Action> all() { return List.copyOf(ACTIONS.values()); }
}
