# Baufortschritt, Fehlerursache und Recovery

Stand: 29. September 2026. Diese Prüfung betrifft `BuildAgent`, `PlaceTask`, `BuildPlan` und deren Regressionstests. Die Ergebnisse des vollständigen Client-Tests und der Prüfung der Originaldatei stehen im übergeordneten Testbericht. Dieser Bericht behauptet keinen erfolgreichen vollständigen Survival-Bau.

## Beleg für den gemeldeten Fehler

Das relevante Protokoll liegt in der LabyMod-Instanz unter `overlay/logs/latest.log`, nicht im leeren Repository-Protokoll oder in der alten Standardinstallation von Minecraft. Die Einträge um 10:49:22 bis 10:49:40 passen exakt zum Screenshot:

- Session 12 lädt 3.151 vom damaligen Leser ausgewählte Bauziele.
- Zunächst scheitert `minecraft:seagrass` bei `16, 8, 71`.
- Danach scheitern weitere Seegrasziele mit `no_support`, unter anderem `9, 9, 69`, `15, 9, 76`, `16, 9, 76` und `15, 9, 75`.
- Die Meldung über zehn aufeinanderfolgende Fehlschläge ist die abschließende Schutzfunktion, keine Java-Exception.

Der Fehlertext verschleiert die eigentliche Voraussetzung: Seegras benötigt Wasser am Platzierungsort. Die bisherige Lese-/Planungskette übernimmt solche Pflanzen als Blockziele, erzeugt jedoch die benötigten Flüssigkeiten nicht. Die Survival-Vorprüfung betrachtet den fertigen Schematic-Zustand einschließlich des Flüssigkeitszustands der Pflanze. Damit beweist sie nicht, dass das Wasser in der realen Welt bereits vorhanden ist oder vom Agenten hergestellt werden kann. Beliebig viele identische Platzierungsversuche lösen das nicht. Der Parent-Agent korrigiert die Vorprüfung und dokumentiert die zusätzlichen Originaldatei-Blocker.

Die historischen Logs enthalten keine vollständigen Welt- oder Fluidzustände. Die Blockart, Positionen und Fehlschlagsfolge sind belegt; die konkreten damaligen Wasserstände lassen sich daraus nicht nachträglich rekonstruieren.

## Behobene Probleme nach Priorität

| Priorität | Ursache / bisheriges Verhalten | Änderung |
| --- | --- | --- |
| Hoch | Eine leere Warteschlange galt auch mit `FAILED`-Zielen als abgeschlossen. Die Session konnte als Erfolg entfernt werden. | `BuildPlan.isComplete()` verlangt ausschließlich `DONE`. Ein erschöpfter Plan mit ungelösten Zielen bleibt als pausierte Session erhalten. |
| Hoch | Die Endprüfung ignorierte ungeladene Chunks. | Nicht geprüfte Regionen werden über die normale Navigation geladen. Erfolgreiche Einzelprüfungen bleiben über Chunkwechsel erhalten; sonst würden große Bauten zwischen entladenen Regionen pendeln. Beobachtete Änderungen invalidieren die betroffenen Nachweise. |
| Hoch | Nach zehn Fehlschlägen wurde sofort pausiert, obwohl andere Baufortschritte oder eine neue Route die Situation lösen können. | Bis zu zwei Wiederholungsrunden mit zurückgesetzter Wegfehlerhistorie. Eine erneute Freigabe benötigt mehr insgesamt bestätigte Blöcke als zuvor. Das bloße Wiederaufbauen wiederholt zurückgesetzter Blöcke erneuert das Budget nicht. |
| Hoch | Gescheiterte Hilfsblock-Platzierung hatte keinen Bezug zum abhängigen Ziel und verbrauchte dessen Retry-Budget nicht. | Der Hilfsblockauftrag merkt sich sein Bauziel. Ein Fehler wird diesem Ziel zugerechnet; nicht vorhandene Hilfsblöcke werden aus der Helferliste entfernt. |
| Hoch | Gescheiterter Hilfsblockabbau vergaß den Block vollständig. | Helfer bleiben gespeichert, werden mit Wartezeit bis zu dreimal abgebaut und führen bei dauerhaftem Fehler zu einer konkreten Pause. Entfernte Helfer invalidieren die Endprüfung. |
| Hoch | Ein öffnendes, aber vom Server nicht bedienbares Lager konnte wiederholt ohne Materialgewinn besucht werden. | Drei aufeinanderfolgende Entnahmeaufträge ohne bestätigten Materialtransfer führen zu `storage_access_failed`. Ein tatsächlich produktiver Transfer setzt das Budget zurück. |
| Mittel | Volles Inventar sah für den Entnahmeplan wie fehlender Lagerbestand aus. | Bei bekanntem Materialbestand werden bis zu zwei Einlagerungsversuche gestartet. Erst danach folgt eine konkrete Inventar-Pause. |
| Mittel | Zwingend benötigtes Material außerhalb der Vorschau auf die nächsten 4.096 Ziele erhielt Menge null und wurde entfernt. | Solche Materialien behalten eine Mindestanforderung von einem Stück; der Entnahmeplan rundet passend auf verfügbare Stapel auf. |
| Mittel | Vorübergehend ausgeschlossene Kisten wurden wie fehlendes Material behandelt. | Bekannter Bestand bleibt während der zeitlichen Sperre als ausstehend behandelt. Navigationsfehler können über die gespeicherte Lager-Home-Zuordnung eskalieren. |
| Mittel | Jeder ausstehende Nachbar unterband Hilfsblöcke. Gegenseitige Abhängigkeiten konnten bis zur Fehlergrenze warten. | Nach zwei Zurückstellungen darf die bestehende Hilfsblockstrategie die Abhängigkeit lösen. |
| Mittel | Ein dauerhaft im Ziel stehendes Lebewesen konnte das Retry-Budget unbegrenzt erweitern. | Zusätzliche Warteversuche für belegte Ziele sind begrenzt. |
| Mittel | `MoveOnlyTask` betrachtete einen unterbrochenen/ruhenden Controller als Erfolg und wiederholte Reisen unbegrenzt. | Nur `ARRIVED` gilt als Erfolg. Reiseprobleme werden dreimal mit Wartezeit und erneuter Planung versucht, dann konkret gemeldet. |
| Mittel | Platzierungsbestätigung prüfte nur die Blockart. | Die tatsächlich beobachtete Platzierung muss auch ein gültiger Zustand bzw. ein zulässiger Zwischenschritt zum Ziel sein. |
| Mittel | Ebenenfortschritt zählte fehlgeschlagene Ziele als fertig. | Separater Zähler für wirklich abgeschlossene Ziele pro Ebene; Statusänderungen aktualisieren ihn direkt. |
| Niedrig | Debug-Ausgabe erzeugte eine Liste aller ausstehenden Ziele und zeigte danach nur zwölf. | Die Abfrage fordert direkt höchstens zwölf Ziele an. |

## Wiederaufnahme und Grenzen

- Explizites Fortsetzen erneuert Retry-Budgets und zeitlich gesperrte Kisten. Gespeicherte Hilfsblöcke bleiben erhalten.
- Bewusst übersprungene Ziele, fehlendes Material und fehlende Werkzeuge werden nicht durch automatische Fehlerwiederholung ignoriert. Ein Auftrag mit solchen ungelösten Zielen wird nicht als vollständiger Erfolg gemeldet.
- Ein fehlgeschlagener optionaler Kistenbesuch unterbricht einen ansonsten produktiven Entnahmeauftrag nicht. Der konkret fehlende Bestand wird bei einem späteren Materiallauf erneut behandelt.
- Eine echte fehlende Materialquelle, fehlende Wasser-/Überlebensvoraussetzungen, serverseitig verweigerte Aktionen und ein nach mehreren Versuchen unerreichbares Lager benötigen weiterhin einen konkreten Eingriff.
- Die vorhandene Stützblocksuche bleibt auf kurze Ketten begrenzt. Es wurde keine allgemeine Planung langer Brücken oder Baugerüste ergänzt. Für große freischwebende Bauteile ist eine explizite Gerüstplanung sinnvoll.
- Der normale Laufweg bleibt bevorzugt. Lager-Homes sind ein persistenter Fallback; die allgemein nicht zugängliche vollständige Home-Liste eines beliebigen Servers kann nicht automatisch bewiesen werden.

## Performance und Langzeitbeobachtung

Die neuen Datenstrukturen sind begrenzt: ein Bit pro Bauziel für Endprüfungen, Zähler pro Hilfsblock, ein konstanter Recovery-Zähler. Hilfsblockzähler und zugehörige Fehlermeldungen werden beim Entfernen gelöscht. Die vorhandene Hilfsblockliste bleibt bewusst bestehen, wenn ein Block tatsächlich noch abgebaut werden muss.

Die Endprüfung arbeitet weiterhin in begrenzten Abschnitten; reguläre Weltvergleiche und Materialvorschauen verwenden die vorhandenen Grenzen. Häufige Fehlentscheidungen werden reduziert, nicht durch zusätzliche unbeschränkte Scans ersetzt. Das ist eine Codeanalyse, kein Nachweis für stabilen Speicherverbrauch über mehrere Stunden. Für einen solchen Nachweis braucht es einen längeren realen Test mit GC-/Heap-Messung, wiederholten Materialläufen und beobachtetem Client-/Servertakt.

## Regressionstests

Ergänzt bzw. verschärft:

- Leere Warteschlange mit einem Fehler ist kein vollständiger Bau.
- Gezieltes Wiederöffnen retrybarer Fehler erhält absichtliche Ausnahmen und korrigiert Ebenen- und Materialstatus.
- Fehlgeschlagene Ziele erhöhen den Ebenenfortschritt nicht.
- Zwei Recovery-Runden sind auch nach 100.000 erneuten Abfragen ohne Fortschritt erschöpft.
- Ein Rückschritt und anschließendes Wiedererreichen desselben Fortschritts erneuert das Budget nicht.
- Explizites Fortsetzen erlaubt eine neue begrenzte Recovery.

Die Ausführung von Gradle und Client-Tests erfolgt zentral durch den Parent-Agenten, damit keine konkurrierenden Gradle-/Minecraft-Prozesse die Ergebnisse verfälschen. Die tatsächlichen Testergebnisse sind im Abschlussbericht festzuhalten.
