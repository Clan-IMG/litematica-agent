# Lock-Screen: Scrollen und lange Meldungen

## Ursache

`AgentLockScreen.drawWrapped` brach am unteren Rand des Textbereichs ab. Die restlichen Zeilen wurden verworfen. Fortschrittsanzeige und Aktionsüberschrift wurden danach trotzdem weitergezeichnet und konnten mit den fest positionierten Steuerelementen überlappen. Auch Materialeinträge verschwanden am unteren Rand.

## Änderungen

- Der gesamte Inhalt unter dem Titel erhält eine gemessene, vertikal scrollbare Ansicht. Fehlertexte, Sessionname, Status, Fortschritt, aktuelle Aktion und sämtliche Materialzeilen bleiben im Layout erhalten.
- Titel und Bedienelemente bleiben an ihrer Position. Eine Clipping-Grenze umfasst Text, Fortschrittsbalken und Gegenstandssymbole gemeinsam.
- Bedienung: Mausrad über dem Panel, Ziehen oder Anklicken des Scrollbalkens, Bild hoch/runter, Strg+Pos1 und Strg+Ende. Außerhalb des Panels bleibt das Mausrad beim Chat. Pos1/Ende ohne Strg behalten die Texteingabefunktion.
- Neue Pausenmeldungen setzen die Scrollposition zurück. Nach kürzerem Inhalt oder einer Größenänderung wird die Position begrenzt. Der Chat-Eingabebereich bleibt frei.
- Verdeckte Chat-Links erhalten keine Klicks durch das Panel. Die sichtbaren Panel- und Dropdown-Schaltflächen werden zuerst bedient.
- Das Inhaltslayout wird pro Spieltick aktualisiert, statt bei jedem Renderframe. Textumbrüche werden in einem auf 32 Einträge begrenzten Cache wiederverwendet. Nur sichtbare Zeilen werden gezeichnet.

## Validierung

`AgentLockScreenGameTest` erstellt eine pausierte Session mit 200 langen Anweisungszeilen und einem eindeutigen Schlussmarker. Er prüft die Vollständigkeit des Layouts, Mausrad, Bild runter, Strg+Pos1/Ende, Scrollbalken-Ziehen, die Begrenzung am Dokumentende, erreichbare Steuerelemente bei 320 × 240 GUI-Pixeln sowie den Rücksprung bei einer neuen Meldung. Screenshots werden am Anfang, am Ende und bei der kleinen Ansicht erstellt.

Die Ausführung und Sichtprüfung erfolgen zusammen mit dem zentralen Client-Testlauf. Diese Datei allein bescheinigt keinen erfolgreichen Testlauf.

## Grenzen und nächste Schritte

- Der Test deckt den Bau-Lock-Screen ab. `StockLockScreen` verwendet weiterhin sein eigenes bisheriges Layout.
- Die kleinste geprüfte GUI-Größe beträgt 320 × 240 Pixel. Künstlich erzwungene kleinere Größen gehören nicht zum Testumfang.
- Der Cache ist begrenzt; bei extrem langen Texten bleibt der Aufwand für die Texterstellung und die Liste aller Layoutzeilen proportional zur Textlänge. Ein einzelner 200-Zeilen-Fehler wird nicht abgeschnitten.
