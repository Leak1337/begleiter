package dev.begleiter.task;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/** Handler je Aufgabentyp; jeder Typ hat einen (der JUnit-Test prüft die Vollständigkeit). */
public final class TaskHandlers {
    private static final Map<TaskType, Function<Task, TaskHandler>> FACTORIES = new EnumMap<>(TaskType.class);

    static {
        FACTORIES.put(TaskType.MOVE_TO, MoveToHandler::new);
        FACTORIES.put(TaskType.GIVE, GiveHandler::new);
        FACTORIES.put(TaskType.PLACE, PlaceHandler::new);
        // Lager
        FACTORIES.put(TaskType.BREAK, BreakHandler::new);
        FACTORIES.put(TaskType.COLLECT, CollectHandler::new);
        FACTORIES.put(TaskType.DELIVER, DeliverHandler::new);
        FACTORIES.put(TaskType.SORT, SortHandler::new);
        FACTORIES.put(TaskType.TAKE, TakeHandler::new);
        // Herstellung
        FACTORIES.put(TaskType.CRAFT, CraftHandler::new);
        FACTORIES.put(TaskType.PROCESS, ProcessHandler::new);
        FACTORIES.put(TaskType.REQUEST, RequestHandler::new);
        FACTORIES.put(TaskType.SMELT, SmeltHandler::new);
        FACTORIES.put(TaskType.STATION, StationHandler::new);
        FACTORIES.put(TaskType.REPAIR, RepairHandler::new);
        FACTORIES.put(TaskType.GEARUP, GearUpHandler::new);
        FACTORIES.put(TaskType.DOOR, DoorHandler::new);
        // Ressourcen
        FACTORIES.put(TaskType.GATHER, GatherHandler::new);
        FACTORIES.put(TaskType.MINE, MineHandler::new);
        FACTORIES.put(TaskType.TREE, TreeHandler::new);
        FACTORIES.put(TaskType.CLIMB, ClimbHandler::new);
        FACTORIES.put(TaskType.DESCEND, DescendHandler::new);
        FACTORIES.put(TaskType.PILLAR, PillarHandler::new);
        // Tiere/Kampf
        FACTORIES.put(TaskType.HUNT, HuntHandler::new);
        FACTORIES.put(TaskType.FETCH, FetchHandler::new);
        FACTORIES.put(TaskType.FIGHT, FightHandler::new);
        FACTORIES.put(TaskType.CRYSTALS, CrystalsHandler::new);
        FACTORIES.put(TaskType.BREED, BreedHandler::new);
        FACTORIES.put(TaskType.FARM, FarmHandler::new);
        FACTORIES.put(TaskType.TRADE, TradeHandler::new);
        // Projekte
        FACTORIES.put(TaskType.BUILD, BuildHandler::new);
        FACTORIES.put(TaskType.HOUSE, t -> new HouseHandler(t, false));
        FACTORIES.put(TaskType.EXTEND, t -> new HouseHandler(t, true));
        FACTORIES.put(TaskType.PORTAL, PortalHandler::new);
        FACTORIES.put(TaskType.WATER, WaterHandler::new);
        FACTORIES.put(TaskType.POUR, PourHandler::new);
        FACTORIES.put(TaskType.TRAVEL, TravelHandler::new);
        FACTORIES.put(TaskType.STRONGHOLD, StrongholdHandler::new);
    }

    private TaskHandlers() { }

    /** Neuer Handler für genau diese Aufgabe, null wenn der Typ (noch) keinen hat. */
    public static TaskHandler create(Task t) {
        Function<Task, TaskHandler> f = FACTORIES.get(t.type());
        return f == null ? null : f.apply(t);
    }

    public static boolean handles(TaskType type) {
        return FACTORIES.containsKey(type);
    }
}
