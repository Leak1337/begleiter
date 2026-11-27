package dev.begleiter.chat;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Wem gilt eine Chatzeile? Reine Logik ohne Welt- oder Registry-Zugriff.
 * <ul>
 *   <li>"Leak" und "Begleiter" sprechen den nächsten eigenen Begleiter an, auch mitten im Satz.</li>
 *   <li>"Bot" und "Companion" nur am Satzanfang oder als {@code @Bot}, weil "Bot" in normalen Chats häufig vorkommt.</li>
 *   <li>Ein eigener Name ("Max") wählt genau diesen Begleiter; {@code @Name} ist eine gezielte Anrede.</li>
 *   <li>Nur ganze Wörter zählen: "Leak1337", "leakage", "robot", "Botschaft" sprechen niemanden an.</li>
 * </ul>
 */
public final class ChatAddress {
    private ChatAddress() {}

    public record Candidate(UUID id, String name, double distanceSquared) {}
    public record Command(UUID target, String text, String sourceText) {
        public Command(UUID target, String text) { this(target, text, text); }
    }
    private record Named(Candidate candidate, String name) {}

    /** Anredewörter, die im ganzen Satz gelten. */
    private static final List<String> ANYWHERE = List.of("leak", "begleiter");
    /** Anredewörter, die nur am Satzanfang (oder als @Wort) gelten. */
    private static final List<String> LEADING = List.of("bot", "companion");
    private static final Set<String> GENERIC = Set.of("leak", "begleiter", "bot", "companion");

    /** null bedeutet: keine eindeutige Ansprache oder kein Befehl; dann bleibt alles unverändert. */
    public static Command parse(String raw, List<Candidate> candidates) {
        return parse(raw, candidates, "@");
    }

    /** Wie {@link #parse(String, List)} mit eigenem Anrede-Präfix (Konfiguration). */
    public static Command parse(String raw, List<Candidate> candidates, String prefixMark) {
        if (candidates.isEmpty() || raw == null) return null;
        if (prefixMark == null || prefixMark.isEmpty()) prefixMark = "@";
        String leading = raw.stripLeading();
        boolean prefix = leading.startsWith(prefixMark);
        if (prefix && !prefixMark.equals("@")) {
            // Eigenes Präfix wie "@" behandeln: danach greift dieselbe Logik für Name/Anrede
            raw = "@" + leading.substring(prefixMark.length());
            leading = raw;
        }
        boolean attached = prefix && leading.length() > 1 && !Character.isWhitespace(leading.charAt(1));
        String text = ChatText.normalize(raw);
        if (text.isEmpty()) return null;
        String addressText = ChatText.normalize(raw.replaceAll("[a-z0-9_.-]+:[a-z0-9_./-]+", ""));

        String leadingWord = GENERIC.stream().filter(w -> ChatText.startsWith(addressText, w)).findFirst().orElse(null);
        // Eine ausdrückliche allgemeine Anrede wählt den nächsten NPC. Wörter im
        // eigentlichen Befehl (z.B. "Holz" oder "Folge") sind dann keine Namen.
        boolean genericAddress = (prefix && !attached) || leadingWord != null;
        List<Named> matches = genericAddress ? List.of() : candidates.stream()
                .map(c -> new Named(c, ChatText.normalize(c.name())))
                .filter(c -> !c.name().isEmpty() && !GENERIC.contains(c.name()) && ChatText.contains(addressText, c.name()))
                .toList();
        // "Max Mustermann" bezeichnet nicht zugleich einen zweiten Begleiter namens "Max".
        List<Named> specific = matches.stream().filter(c -> matches.stream().noneMatch(other ->
                other.name().length() > c.name().length() && ChatText.contains(other.name(), c.name()))).toList();
        if (specific.stream().map(Named::name).distinct().count() > 1) return null;

        Comparator<Candidate> nearest = Comparator.comparingDouble(Candidate::distanceSquared).thenComparing(Candidate::id);
        Named named = specific.stream().min(Comparator.comparing(Named::candidate, nearest)).orElse(null);
        String generic = leadingWord != null ? leadingWord
                : ANYWHERE.stream().filter(w -> ChatText.contains(addressText, w)).findFirst().orElse(null);
        if (!prefix && generic == null && named == null) return null;
        // @Name ist eine gezielte Anrede; ein unbekannter Name darf keinen anderen NPC steuern.
        if (attached && leadingWord == null && (named == null || !ChatText.startsWith(text, named.name()))) return null;

        Candidate target = named == null ? candidates.stream().min(nearest).orElseThrow() : named.candidate();
        String command = named == null ? text : ChatText.removeFirst(text, named.name());
        if (generic != null) command = ChatText.removeFirst(command, generic);
        String source = raw.stripLeading().replaceFirst("^@\\s*", "");
        if (named != null) source = removeSourceAddress(source, named.name());
        if (generic != null) source = removeSourceAddress(source, generic);
        return command.isBlank() ? null : new Command(target.id(), command, source.strip());
    }

    /** Ohne Ansprache (Konfiguration NEVER/AUTO): ganze Zeile an den nächsten eigenen Begleiter. */
    public static Command unaddressed(String raw, List<Candidate> candidates) {
        if (candidates.isEmpty() || raw == null) return null;
        String text = ChatText.normalize(raw);
        if (text.isBlank()) return null;
        Candidate target = candidates.stream()
                .min(Comparator.comparingDouble(Candidate::distanceSquared).thenComparing(Candidate::id)).orElseThrow();
        return new Command(target.id(), text, raw.strip());
    }

    /** Satzzeichen in Item-IDs behalten und nur die oben schon geprüfte Anrede entfernen. */
    private static String removeSourceAddress(String raw, String address) {
        var ids = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(raw);
        java.util.List<int[]> identifiers = new java.util.ArrayList<>();
        while (ids.find()) identifiers.add(new int[]{ids.start(), ids.end()});
        var matcher = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+").matcher(raw);
        java.util.List<Integer> starts = new java.util.ArrayList<>(), ends = new java.util.ArrayList<>();
        java.util.List<String> words = new java.util.ArrayList<>();
        while (matcher.find()) {
            if (identifiers.stream().anyMatch(span -> matcher.start() >= span[0] && matcher.end() <= span[1])) continue;
            starts.add(matcher.start()); ends.add(matcher.end()); words.add(ChatText.normalize(matcher.group()));
        }
        List<String> name = ChatText.tokens(address);
        for (int i = 0; i + name.size() <= words.size(); i++) {
            if (!words.subList(i, i + name.size()).equals(name)) continue;
            return raw.substring(0, starts.get(i)) + " " + raw.substring(ends.get(i + name.size() - 1)).replaceFirst("^[,:]+\\s*", "");
        }
        return raw;
    }

    /** Für Tests und Hilfetexte: Anredewörter, die unabhängig vom Namen des Begleiters wirken. */
    public static List<String> addressWords() {
        return java.util.stream.Stream.concat(ANYWHERE.stream(), LEADING.stream()).toList();
    }
}
