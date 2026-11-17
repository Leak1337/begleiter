"""Write the delivery guide only when all operating-test evidence is present."""
import hashlib
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
work=root/'build/request-storage'
checks=[('0 Grad, vier Zellen, endgültige Beschriftung','complete-mini-final-layout.log','REQUEST_COMPLETE_PASS types=4'),
        ('90 Grad, alle 128 Materialien','complete128-r90-final.log','REQUEST_COMPLETE_PASS types=128'),
        ('180 Grad, vier Zellen','complete-mini-r180.log','REQUEST_COMPLETE_PASS types=4'),
        ('270 Grad, vier Zellen','complete-mini-r270.log','REQUEST_COMPLETE_PASS types=4')]
for label,file,marker in checks:
    log=(work/file).read_text(encoding='utf-8',errors='replace')
    if marker not in log or 'BUILD SUCCESSFUL' not in log:raise SystemExit('Operating check incomplete: '+label)
meta=json.loads((work/'complete.json').read_text())
digest=hashlib.sha256((work/'complete.nbt').read_bytes()).hexdigest()
layout=json.loads((work/'layout-diff.json').read_text())
assert layout['mechanics_identical'] and layout['delivered_sha256']==digest
materials=json.loads((root/'tools/request_storage_materials.json').read_text(encoding='utf-8'))
guide='''# Zentrallager 128 mit Sortierung und Itemabruf

Minecraft Java 26.3. Die Anlage arbeitet mit Vanilla-Redstone, Trichtern, Wasser und Shulkerboxen.
Sie benötigt keine Befehlsblöcke und kein laufendes Inventar-Skript.

## Bedienung

1. **Lose Items:** die untere Eingangstreppe benutzen und das Eingabefass befüllen.
2. **Gefüllte oder leere Shulkerboxen:** die obere Eingangstreppe benutzen. Die Anlage entlädt Boxen automatisch.
3. **Abrufen:** den beschrifteten Materialknopf in der Galerie drücken. Ein Abruf liefert die vorhandene Box,
   bei kleinen Beständen auch eine teilweise gefüllte Box. Wiederholte Leerabrufe geben keine Leerbox aus.
4. **Zentrale Ausgabe:** Hebel AUS entlädt die bestellten Boxen in die Ausgabedoppelkiste.
   Hebel EIN hält ganze Boxen in der oberen Ausgabekiste bereit. Nach dem Ausschalten läuft die Entladung weiter.
5. **Leerboxen:** automatisch entleerte Boxen laufen zurück zu den Beladern.
   Mitgenommene Boxen nach Gebrauch über den Boxeneingang zurückgeben.
6. **Eingangslampe:** Items ohne passenden Filter oder bei überfülltem Fach gelangen ins Restefass am Kanalende.
   Der Eingang pausiert, solange dort Items liegen. Restefass leeren, dann läuft die Anlage weiter.

Es sind genau die unten aufgelisteten 128 Materialtypen konfiguriert. Umbenannte Items, andere Datenkomponenten
und nicht konfigurierte Items sind keine passenden Filtertreffer. Der Abruf bestellt Boxen; er dosiert keine
exakte Wunschmenge wie „64 Eisen“.

## Aufstellung und Betriebsfüllung

Ein freier, flacher Bauplatz ist nötig. Der gesamte Bau muss während des Betriebs geladen bleiben.
Die Vorlage ist für Creative vollständig vorgefüllt. Im Survival-Modus setzt der Begleiter die Betriebsfüllung
nicht kostenlos ein: beide Filter je Material, Filterfüller und der Leerboxkreislauf müssen manuell gefüllt werden.
Die Anlage ist groß und materialintensiv; sie ist kein günstiges Anfangslager.

Jedes Material verwendet zwei Filter mit insgesamt 84 reservierten Materialitems. Dazu kommen 1.024 benannte
Papier-Filterfüller. Die Creative-Vorlage enthält 13.642 leere Boxen als Betriebsbestand in Beladern und
Transportpuffern sowie 128 bereits aufgestellte Ladeboxen. Die Reserven nicht als normale Lagerbeute entnehmen.
Eine Ladebox fasst bis zu 27 Stacks. Weitere Items warten im Fass und in Transportpuffern.

Der gemeinsame Eingang wird durch einen Trichter dosiert: nominell höchstens 2,5 Items/s bei 20 TPS.
Die tatsächliche Rate und Abrufzeit hängen vom Takt, Transportweg und der Serverleistung ab.
Dies ist ein vollständig geprüftes Abruflager, kein Versprechen eines 16-fach-Hochgeschwindigkeitssortierers.

## Funktionsnachweis

Alle 128 Materialtypen wurden nach dem tatsächlichen Bot-Import einsortiert und zentral wieder ausgegeben.
Zusätzlich geprüft: beide Ausgabemodi, gefüllte und leere Eingangsboxen, Wiederanlauf nach dem Leeren des
Restefasses, Leerabrufe, automatisch ersetzte Ladeboxen und erhaltene Itemrahmen.
Die Abschlussbilanz prüft jedes Material, die unveränderten Filterreserven und sämtliche Leerboxen;
es dürfen keine losen Item-Entities übrig bleiben. Die Tests verschieben keine Items zwischen Lagerzellen.
Sie simulieren lediglich das Befüllen des Eingangs, Knopf-/Hebelbetätigung und die Abholung an der Ausgabe.

'''
for label,file,_ in checks:guide+=f'- {label}: bestanden; `build/request-storage/{file}`.\n'
guide+=f'\nAusgelieferte Schematic-SHA-256: `{digest}`.\n'
guide+=f'\nDer vollständige 128er-Funktionslauf verwendet den Technikstand `{layout["mechanics_sha256"]}`. '
guide+='Danach wurden ausschließlich die 128 Hinweisschilder und ihre nicht benötigten Träger versetzt, damit die Itemrahmen frei sichtbar bleiben. '
guide+='Ein Vergleich aller Blöcke und Inventare bestätigt unveränderte Betriebstechnik; der endgültige kleine Aufbau wurde nochmals vollständig geprüft.\n'
guide+='''
Nachbau: `python tools/build_request_storage.py` oder Gradle-Task `generateRequestStorage`.
Die Materialbeschriftungen sind in `tools/request_storage_materials.json` gespeichert.

Der Boxentlader basiert auf [BU003 von misterkartoffel und sergyd](https://storagecatalog.org/archives/BU003-Small-1x-Box-Unloader/).
Sortierung, Abruf, Leitungen, Eingangstakt, Rückführung und Gesamtaufbau sind für diese Vorlage zusammengesetzt.

## Materialbelegung

Die Galerie hat acht Spalten. Die Nummern entsprechen den Beschriftungen an den Knöpfen.

| Nummer | Material | Reihe | Spalte |
| --- | --- | --- | --- |
'''
for i,row in enumerate(materials):guide+=f'| {i+1:03d} | {row["name_de"]} | {i//8+1} | {i%8+1} |\n'
(root/'docs/zentrallager-128.md').write_text(guide,encoding='utf-8')
print('Wrote verified storage guide:',digest)
