# Lager, Navigation und Home-Fallback

Stand: 29.09.2026. Technische Prüfung und Implementierung; Ergebnisse des tatsächlichen Survival-Tests stehen im übergeordneten Testbericht. Ein erfolgreicher Langzeittest wird hier nicht vorweggenommen.

## Behobene Ursachen

| Priorität | Ursache / bisheriges Verhalten | Änderung |
| --- | --- | --- |
| Hoch | Geschlossene Holztüren galten im Pfadmodell als Wand; Bewegung konnte sie nicht bedienen. | Von Hand bedienbare Türen und Zauntore gelten als geplante Durchgänge. Vor dem Eintritt hält die Bewegung an, richtet den Blick auf die Tür, wartet auf die an den Server übermittelte Blickrichtung und klickt. Der beobachtete Offen-Zustand ermöglicht die Weiterbewegung. |
| Hoch | Wiederholtes Zielen und erneutes Annähern an Kisten konnte unbegrenzt wechseln. | Maximal vier Zielanläufe, drei Öffnungsversuche, 35 Sekunden pro Annäherungsphase und 90 Sekunden pro Kistenbesuch. Danach andere Kiste oder definierter Fallback. |
| Hoch | Entnahmen wurden mit dem gesamten Quellstapel verrechnet, auch bei Teilübertragung. Ein volles Inventar verhinderte sogar das Auffüllen vorhandener Stapel. | Entnahmen und Einlagerungen werden anhand der beobachteten Änderung im Spielerinventar verrechnet. Kompatible angebrochene Stapel dürfen auch ohne leeren Platz aufgefüllt werden. Drei nicht wirksame Übertragungen sperren die Kiste vorübergehend. |
| Hoch | Ein globales Lager-Home konnte für verschiedene Lager benutzt werden. | Persistente Home-Zuordnung nach Dimension und Lagerposition. Kisten bis 48 Blöcke Entfernung teilen einen Lageranker; entfernte Lager haben eigene Zuordnungen. Alte globale Einstellungen bleiben bis zur ersten konkreten Zuordnung kompatibel. |
| Mittel | Fehlende Lagerwege führten nur zu Wiederholungen bzw. Materialmangel. | Zweimal begrenzt laufen, danach vorhandenes Home verwenden. Bei erfolglosem Zugang konkrete Aufforderung mit Lagerkoordinate, nummeriertem Home und Registrierungsschritt. Produktive Materialläufe mit einer optionalen fehlgeschlagenen Kiste pausieren nicht allein deswegen. |
| Mittel | Home-Reisen konnten aktive Bewegung fortsetzen und kurze Teleports nicht erkennen. | Vor dem Home-Befehl Bewegung stoppen. Lagerreise erkennt Orts- oder Dimensionswechsel, prüft Nähe zum Lager sowie geladenen Zielchunk; maximal zwei Befehlsversuche und anschließende Beruhigungszeit. |
| Mittel | Abgelaufene Navigationssperren wurden nie aus ihren Maps entfernt. | Abgelaufene Sperren für Pillar- und Kollisionsfehler werden bei Pfadplanung maximal alle zehn Sekunden entfernt. |

## Grenzen und Folgearbeit

- **Home-Nummern:** Ohne serverspezifische Schnittstelle kennt ein Client die privaten Homes des Servers nicht. Der Vorschlag vermeidet alle bekannten konfigurierten und bereits vorgeschlagenen Nummern; die Meldung benennt diese Grenze. Der Benutzer setzt das Home direkt am Lager und registriert es dort mit `/agent home storage home N`. Danach wird die Zuordnung gespeichert und wiederverwendet. Die Mod setzt keine unbekannten vorhandenen Homes eigenmächtig neu.
- **Geschützte Durchgänge:** Eisentüren werden nicht als von Hand bedienbar behandelt. Wenn eine bedienbare Tür trotz wiederholtem Klick geschlossen bleibt, wird der Schritt nach vier Sekunden vorübergehend gemieden und ein anderer Weg gesucht. Druckplatten, Schalterkombinationen und komplexe Redstone-Zugänge sind nicht allgemein gelöst.
- **Türgeometrie:** Eine geöffnete Tür behält seitlich eine Kollisionsfläche. Gerade Durchgänge sind vorgesehen; ungünstige diagonale Anläufe oder ungewöhnliche Tür-/Blockkombinationen müssen im Welt-Test beobachtet werden. Die vorhandene Kollisions-Recovery bleibt aktiv.
- **Home-Ankunft:** Zielkoordinate bezeichnet eine Lagergruppe, nicht die genaue serverseitige Home-Position. Nach Ankunft ist daher noch eine begrenzte normale Annäherung an die konkrete Kiste nötig. Die Rückreise zur Baustelle bleibt Aufgabe der Bauplanung beziehungsweise des bereits konfigurierten Bau-Homes.
- **Lagergruppen:** Der Radius von 48 Blöcken ist eine praktische Heuristik, kein Gebäude- oder Berechtigungsmodell. Zwei unmittelbar benachbarte, getrennt zugängliche Lager können derzeit denselben Anker erhalten. Explizite Lager-IDs und mehrere Zugangspunkte wären die saubere Erweiterung.
- **Übertragungsbestätigung:** Inventaränderungen werden erst nach mindestens vier Ticks ausgewertet. Das berücksichtigt normale Synchronisation und Teilübertragungen, ist jedoch keine serverspezifische Transaktionsbestätigung. Sehr späte Serverkorrekturen müssen im Langzeittest überprüft werden; die nächste tatsächliche Inventarprüfung bleibt maßgeblich.
- **Pfadplanung:** A* hat bereits einen Knotengrenzwert und einen auf einen Suchlauf begrenzten Blockcache. Weltübergreifende Cache-Nutzung wäre ohne Blockänderungs-Invalidierung fehleranfällig. Die neue Ablaufbereinigung verhindert das bekannte unbeschränkte Wachstum zeitlich befristeter Sperren, beweist aber keine generelle Freiheit von Memory Leaks.

## Verifikation

Neue `StorageHomesTest`-Tests decken Nummernkollisionen, Wiederverwendung einer Lageranfrage, Dimensions-/Lagertrennung, JSON-Roundtrip und alte/nullhaltige Persistenzdaten ab. Ausführung erfolgt gesammelt durch den Hauptagenten; in dieser Teilaufgabe wurde kein paralleler Gradle-Prozess gestartet.

Für den realen Vorabtest sind beide Laufrichtungen durch eine anfangs geschlossene Holztür, mehrere zusammenliegende Kisten, tatsächliche Entnahme, Rückkehr zur Baustelle und mindestens ein erneuter Nachfülllauf zu prüfen. Negative Fälle: verschlossene/geschützte Tür, unerreichbare Kiste, abgelehnte Home-Reise, teilweise volle Stapel und volle Ablagekiste. Erst danach darf der vollständige Bau der `schildkröte.litematic` starten.
