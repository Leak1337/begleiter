package dev.begleiter.chat;

/**
 * Sprachneutrale Absicht eines Chatbefehls. Die Reihenfolge der Konstanten ist bedeutungslos;
 * die Priorität ergibt sich aus der Liste, die ein {@link Lexicon} liefert.
 */
public enum ChatIntent {
    TALK_LESS, TALK_MORE,
    WAYPOINT_SAVE, WAYPOINT_FORGET, WAYPOINT_LIST, WAYPOINT_GO,
    PROJECT_PREVIEW,
    TERRAFORM,
    STANCE, PROTECT,
    MEMORY_CLEAR, MEMORY_SUMMARY, WHERE_IS,
    CANCEL, PAUSE, RESUME, EXPLAIN, STATUS,
    SET_HOME, FARM, BREED, FISH, REPAIR, TRADE, GEAR_UP, KEEPER, SORT, DOOR,
    LIST_SCHEMATICS, BUILD_SCHEMATIC, BUILD_STRUCTURE,
    FETCH_ANIMAL, GIVE, MATERIALS_FOR, CRAFT_FOR_OWNER, OBTAIN, GATHER,
    FREE_ROAM, FOLLOW, STAY
}
