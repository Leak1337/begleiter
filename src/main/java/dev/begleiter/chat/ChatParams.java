package dev.begleiter.chat;

/**
 * Sprachneutrale Angaben aus einem Satz. itemQuery ist ein noch aufzulösender Name;
 * alle übrigen Gegenstandsangaben sind stabile IDs.
 * {@code null} bzw. {@link ChatCount#NONE} bedeutet "nicht genannt".
 *
 * @param count      Menge im ganzen Satz
 * @param resource   Rohstoff-Schlüssel wie in {@code Resources} ("holz", "eisen", ...)
 * @param mob        Entity-Pfad ("sheep", ...) oder "any" für ein beliebiges Tier
 * @param item       Item-Pfad ohne Namensraum ("iron_pickaxe", "torch", ...)
 * @param stance     Kampfmodus: "AGGRESSIVE", "DEFENSIVE" oder "PASSIVE"
 * @param terraform  Terraforming-Art: "FLATTEN", "SMOOTH", "HILL", "PIT", "FILL" oder "CUT"
 * @param size       Baugröße: "small", "normal" oder "large"
 * @param style      Baustil: "stone" oder "birch" (sonst Holz)
 * @param tier       Ausrüstungsziel: "iron", "diamond" oder "netherite"
 * @param filler     Füllmaterial beim Terraforming: "cobblestone" (sonst Erde)
 * @param waypoint   Name nach "als"/"as" beim Merken eines Ortes
 * @param toOwner    Sammelergebnis soll zum Spieler ("bring mir", "bring me")
 * @param forOwner   Satz nennt den Spieler als Empfänger ("mir"/"uns", "me"/"us"), z.B. beim Handel
 * @param tradeItem  Gewünschtes Item beim Handel (nach "gegen"/"for", nie Smaragd)
 * @param tradeCount Menge beim Handel
 * @param sell       Verkaufen statt kaufen
 * @param schematic  Suchbegriff für eine Schematic-Datei (Satz ohne Bauverb, Füllwörter und Zahlen) oder null
 * @param withAir    Schematic mit Luftblöcken bauen ("mit Luft", "with air")
 */
public record ChatParams(int count, String resource, String mob, String item, String stance, String terraform,
        String size, String style, String tier, String filler, String waypoint, boolean toOwner, boolean forOwner,
        String tradeItem, int tradeCount, boolean sell, String schematic, boolean withAir, String itemQuery) {
    public ChatParams(int count, String resource, String mob, String item, String stance, String terraform,
            String size, String style, String tier, String filler, String waypoint, boolean toOwner, boolean forOwner,
            String tradeItem, int tradeCount, boolean sell, String schematic, boolean withAir) {
        this(count, resource, mob, item, stance, terraform, size, style, tier, filler, waypoint, toOwner, forOwner,
                tradeItem, tradeCount, sell, schematic, withAir, null);
    }
    public int countOr(int fallback) { return count == ChatCount.NONE ? fallback : count; }
    public int tradeCountOr(int fallback) { return tradeCount == ChatCount.NONE ? fallback : tradeCount; }
}
