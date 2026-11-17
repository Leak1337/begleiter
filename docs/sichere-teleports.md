# Sichere Aufhol- und Rettungspositionen

## Verhalten

Normales Folgen, Rückholung aus großer Entfernung, der direkte Ersatzweg beim
Dimensionswechsel und die Lava-/Feuerrettung verwenden dieselbe serverseitige
Zielprüfung. Die Zentrierung vor dem Bau eines Schutzraums wird ebenfalls geprüft.

Ein Ziel benötigt trockenen, tragfähigen Boden unter der gesamten Körperfläche,
ausreichenden Kollisionsraum und Platz innerhalb der Welt- und Baugrenzen.
Flüssigkeiten, die vorhandenen Navigationsgefahren und Portale im unmittelbaren
Umfeld schließen eine Landung aus. Ein anderer lebender Mob oder Spieler darf
den Körperraum nicht belegen. Eine bestätigte Landung setzt Fallstrecke und
Bewegungsimpuls zurück und beendet die alte Navigation.

Das Aufholen sucht höchstens drei Blöcke waagerecht, 24 Blöcke unterhalb und vier
Blöcke oberhalb der Besitzerposition. Dadurch kann der Begleiter beispielsweise
auf dem Boden unter einem 20 Blöcke hoch fliegenden Besitzer landen. Ohne
bestätigten Platz wartet der Folgen-Modus; er versucht es höchstens einmal pro
20 Ticks erneut. Die periodische Fernrückholung löscht ihren bisherigen Auftrag
erst nach erfolgreichem Versetzen. Bei einem unsicheren Dimensionsziel bleibt
der Begleiter in seiner bisherigen Dimension.

Die lokale Rettungs-/Landsuche behält ihre bisherigen Suchhöhen von drei Blöcken
nach oben und unten; der horizontale Radius ist auf zwölf Blöcke begrenzt.
Das ist eine konservative Momentaufnahme: Sehr enge, teilweise geflutete oder
nicht vollständig geladene Plätze können abgelehnt werden.

## Kein Nachladen durch die Zielprüfung

Vor Weltblock- oder Kollisionsabfragen werden die beteiligten Chunks über
`getChunkNow` geprüft, einschließlich des Nachbarrands, den Kollisionsformen
lesen können. Fehlende Chunks sind unbekannt und werden abgelehnt. Die Suche
verändert weder Blöcke noch Inventare. Diese Aussage betrifft die Zielprüfung;
normales Portalreisen und andere Planungssysteme sind davon nicht betroffen.
Eine allgemeine Anbindung an Schutzgebiete anderer Mods gibt es noch nicht.

## Prüfungen

`SafeTeleportGameTests` deckt fliegenden Besitzer, Meer, Lava, niedrige Decke,
belegten Körperraum, konkrete Block-/Flüssigkeitsgefahren, Weltgrenze,
Schutzraumzentrierung, unbekannte Ziele und unbekannte Kollisionsnachbarn,
Auftragserhalt, Feuerrettung und tatsächlichen Dimensionswechsel ab.

Die Teststruktur trennt hohe Besitzer-/Landeplattformen von anderen Szenarien.
Zielchunks für den erfolgreichen Dimensionswechsel werden ausdrücklich vom
Testaufbau vorbereitet. Die Prüfung unbekannter Ziele und Chunkränder verlangt
dagegen unveränderte geladene Chunkzahlen.

Der Randtest findet den tatsächlich geladenen Chunkrand, weil die ausdrückliche
Weltgenerierung im Testaufbau bereits mehrere Nachbarchunks vorbereiten kann.
Der Aufbau vermeidet nachladende Nachbarform-Updates; erst danach werden
unveränderte Chunkzahlen durch die echte Zielprüfung verlangt.
