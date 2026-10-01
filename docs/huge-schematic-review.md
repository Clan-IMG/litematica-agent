# Riesige Schematics: Analyse "test versuch Bauprojekt Firma" (2026-09-30)

Bugreport: Bei einer Schematic mit 28,5 Mio. Blöcken legt der Agent nicht los, sondern "berechnet irgendwas";
falsche oder schon vorhandene Blöcke sollen ohne Meckern akzeptiert werden.

## Die Datei

| | |
|---|---|
| Größe | 700 × 226 × 626, 99 Mio. Volumen, 28.498.051 Blöcke (29 %) |
| Blocktypen | 336 (1310 Zustände), 5824 Tile-Entities |
| Inhalt | Geländeausschnitt: Ebenen 0–50 je ~430.000 Blöcke, fast nur Stein (25 Mio. von 28,5 Mio.) |
| Nicht baubar (Vorprüfung) | 2197: 1237 Blumentöpfe (mehrere Items), 746 ohne Halt, 184 Barrier/Bedrock/Command-Blocks, 26 halbe Türen/Betten |

Datei liegt unter `build/fulltest/firma.litematic`, Ebenenanalyse in `build/fulltest/firma-analysis.txt`.

## Was im Log des Spielers stand (29.09., 20:09–21:26)

76 Minuten, nichts gebaut: **125.941** Blöcke einzeln wegen fehlenden Materials aufgegeben (Granit, Andesit, Diorit),
**166.024** Aufschübe "kein Block zum Anbauen" (Ebenen ▼ wollte das schwebende Dach zuerst). Gegen 22:16 dann
alle 5 s dieselbe Pause-Meldung wiederholt und bei jedem Fortsetzen erneut der Hinweis "Oben ist noch nichts".

## Testaufbau (`HugeSchematicGameTest`)

Survival, Flachwelt, Platzierung westlich des Spielers; 35×41 der ersten und 20×21 der zweiten Ebene stehen schon
(Stein, wo die Datei überwiegend Stein will - an einigen Stellen dadurch falsch), 66 + 14 Holzplanken sind
absichtlich falsch; Lager: zwei bis drei Doppeltruhen Stein (plus eine gemischte), Modus "Alle überspringen".
Fünf Minuten beobachtet, Bericht neben der Datei (`huge-schematic-report.txt`). Aufruf:
`build\fulltest\run-firma.ps1 [-Strategy LAYERS_TOP_DOWN] [-Selection minecraft:stone] [-StoneOnly]`.

## Ergebnisse

| Lauf | Einstellung | Heap | Einlesen | erster Block | Blöcke/5 min | Entscheidung Ø/max | Pausen |
|---|---|---|---|---|---|---|---|
| 1 | Ebenen ▲, gemischtes Lager | 12 GB | 18,8 s | sofort* | 809 | – | 0 |
| 2 | Ebenen ▼, nur Stein gewählt, nur Stein im Lager | 12 GB | 17,9 s | 18 s | 884 | 6,4 / 44 ms | 0 |
| 3 | wie 2, Chunk-lokale Suche | 12 GB | 18,8 s | 19 s | 839 | 0,7 / 302 ms** | 0 |
| 4 | Ebenen ▲, kompakter Plan | **4 GB** | 11,9 s | 19 s | 780 | 0,4 / 24 ms | 0 |

\* Messfehler des ersten Laufs (vorhandene Blöcke zählten als "gebaut"); danach korrigiert.
\*\* einmaliger Aufbau des Chunk-Index, seit Lauf 4 im Hintergrund beim Einlesen.

In allen Läufen: die 66 falschen Planken wurden übersprungen und stehen unberührt, die vorhandenen Steine zählten
als fertig, 0 Rotationsverstöße (Anti-Cheat), Heap nach dem Einlesen 5,8–6,2 GB (Läufe 1–3) bzw. 3,2 GB (Lauf 4).
Tempo 10 (Standard) ergibt ~180 Blöcke/min inklusive Laufwegen.

## Änderungen

1. **Fehlendes Material / Auswahl:** Zieltypen ohne Material und außerhalb der Auswahl werden per Prädikat
   ausgeschlossen statt Block für Block aufgegeben; Ebenen, in denen nichts Baubares mehr offen ist, werden über
   Zähler pro Item und Ebene übersprungen (`SessionRuntime.layerHasBuildable`).
2. **Blöcke ohne Halt** mit noch offenem Nachbarn werden geparkt (6 Blicke statt Platzierungssimulation) und beim
   Setzen des Nachbarn wieder freigegeben; erst wenn sonst nichts mehr geht, bekommt die volle Suche samt Gerüst
   eine Runde.
3. **Ebenen ▼** prüft alle 2 s, ob oben etwas hält; sonst wird von unten gebaut (Hinweis einmal pro Session).
4. **Alle überspringen** pausiert nicht mehr wegen Blöcken, nur noch wenn der Agent selbst nicht weiterkommt
   (Wegfehler in Serie ohne einen gesetzten Block); unerreichbare Hilfsblöcke bleiben mit Hinweis stehen.
5. **Auto-Weiter:** Wiederholung derselben Pause bleibt stumm, auch wenn sie erst nach einem Laufweg kommt;
   Abstand 5 s → 30 s.
6. **Entscheidungskosten:** Kandidaten der untersten offenen Ebene nur aus den Chunks um den Spieler
   (`BuildPlan.candidatesNearInLayer`), Chunk-Index in zwei Durchgängen ohne Zwischenlisten, im Hintergrund gebaut.
7. **Speicher pro Block:** `BuildTarget` ohne eigenes `BlockPos`-Objekt (gepackter long), Plan-Arrays als
   byte/int, Index-Maps dicht gefüllt und vorab dimensioniert. 28,5 Mio. Blöcke passen damit in 4 GB Heap;
   LabyMod startet das Spiel des Nutzers mit 12 GB (`launcher-config.json`, "ram": 12288).
8. **Außerhalb der Welthöhe** liegende Blöcke werden beim Einlesen aussortiert (`OUTSIDE_WORLD`).
9. **Gescheiterte Klickposition** (Fund aus der Regressions-Suite, Kerze auf dem Hüttendach): Nach einem
   Fehlklick war nur der exakte Block gesperrt, der nächste Standplatz lag direkt daneben und galt schon als
   erreicht - sechsmal derselbe Fehlklick ohne einen Schritt, dann Pause. Dazu brach `PlaceTask.tickMove` den
   Weg zum neuen Standplatz sofort ab, sobald der Block "von hier" platzierbar schien - also von genau der Stelle.
   Jetzt ist die ganze Nachbarschaft der Position gesperrt (`BuildAgent.failedFromHere`), auch für diese Abkürzung.

## Nachtrag 30.09. nachmittags: "der Agent macht gar nichts" (Session #4)

Log des Spielers: 7.632 × "kein Block zum Anbauen", alle auf Y=32 - der untersten Ebene der Datei. Die Datei ist
ein Geländeausschnitt mit ~30 Ebenen *unter* dem sichtbaren Gebäude (Blumentöpfe bei Y=62-66, Datei ab Y=32);
unter dem Grundstück ist Leere. "Ebene für Ebene" begann dort, jedes Ziel dort hing in der Luft; "Um den Spieler
herum" nahm die Ebenen auf Höhe des Spielers und lief deshalb sofort los.

Änderungen:

10. **Ebenen ohne Halt werden übersprungen:** Die Ebenen-Strategien prüfen die Ziele in den Chunks um den Spieler
    Ebene für Ebene (bis 64 Ebenen); Ziele ohne Anschlag, die einen noch offenen Nachbarn haben, werden geparkt, und
    die nächste Ebene kommt dran. Gebaut wird dort, wo etwas hält (Plattform, vorhandene Blöcke), die Leere-Ebenen
    bleiben stehen, bis ein Nachbar gesetzt ist (oder ganz am Ende die Gerüstphase kommt).
11. **Reise zu fernen Zielen** peilt die Säule (X/Z, Radius 12) an statt die exakte Höhe (`Goal.column`): die Höhe
    eines Ziels kann Leere oder Fels sein, die Chunks laden ohnehin spaltenweise.
12. **Luft-Platzierung** (`airPlacement`, Einstellung "Luft-Platzierung", Standard an - ausdrücklicher Wunsch):
    Wenn kein normaler Klick existiert, wird wie bei Litematicas Easy Place in die leere Zelle des Ziels geklickt
    (`PlacementSolver.findAirPlacement`, `Aiming.airHit`). Die Blickrichtung ist echt (Fadenkreuz auf der Zelle),
    aber Anti-Cheats, die den geklickten Block prüfen, können es melden - Tooltip warnt; auf Grim-Servern aus.
    Mit Luft-Platzierung baut "Ebenen ▼" wirklich von oben.

Test (`run-firma.ps1 -Strategy LAYERS_TOP_DOWN -Selection minecraft:stone -StoneOnly`, dazu
`LITEMATICA_TEST_VOID=true` (Leere unter der Platzierung, nur eine Plattform), `LITEMATICA_TEST_FLY=true`
(Survival-Flug wie /fly), `LITEMATICA_TEST_AIR_PLACEMENT`):

Läufe 6 und 7 haben drei Fehler gezeigt, die auf einem großen Grundstück mit Flug zusammenkommen:

13. **Flug "steckt fest" (Lauf 6/7):** `advanceIndex` sah nur 4 Knoten voraus, mit Laufgenauigkeit (0,45 Blöcke);
    im Flug überholt der Spieler die Wegpunkte, verliert den Pfad, plant viermal neu und gibt auf. Jetzt 12 Knoten
    voraus mit 0,9 Blöcken Toleranz; Fortschritt setzt den Neuplanungszähler zurück. Zweite Ursache: Ein schräger Pfad
    fällt einen Block pro Block, der Flug sinkt langsamer als er vorwärts rauscht - der Spieler hing 3 Blöcke über dem
    Pfad, "neben dem Pfad", Neuplanung, "steckt fest". Jetzt hält der Flug erst die Höhe, wenn sie um mehr als 2
    Blöcke abweicht (`MovementController.tickFlying`).
14. **Lager unerreichbar aus der Ferne (Lauf 7):** Nach der Reise zum obersten offenen Bereich (440 Blöcke von der
    Truhe) wollte der Agent Stein holen; die Standplatzsuche um die Truhe fand in nicht geladenen Chunks nichts →
    `container_unreachable` → Pause "Lager nicht erreichbar", automatisch alle 5-30 s wiederholt, 300-mal. Jetzt reist
    `Approach` zuerst zur Säule des Blocks (`Goal.column`), wenn er weit weg oder nicht geladen ist, und sucht den
    Standplatz erst dort; die Zeitbudgets des Truhenbesuchs zählen die Reise nicht mit (`ContainerTask.travelTicks`).
15. **Material vor der Reise:** Ein fernes Ziel bringt sein Item in die Bedarfsliste, wenn es nicht im Inventar ist:
    Erst zur Truhe, dann die 400 Blöcke fliegen - nicht umgekehrt.
16. **Test-Messfehler:** Der Plan verglich mit der Client-Welt, bevor die Leere dort angekommen war; 1848 Ziele galten
    als fertig und wurden nach dem Chunk-Update wieder geöffnet (`done=-1848`). Der Test wartet jetzt auf die Leere
    und zählt `placedBlocks` (vom Agenten gesetzte Blöcke) statt der Fertig-Differenz.

| Lauf | Luft-Platzierung | erster Block | Blöcke/5 min | Fehlschläge | Pausen |
|---|---|---|---|---|---|
| 5 | aus | 20 s | 747 (auf der Plattform, 2300 Leere-Ziele geparkt) | 2167 × falscher Block (Erde/Gras der Plattform) | 0 |
| 6 | an | keiner | 0 | – | – |
| 7 | an | keiner | 0 | – | 300 (Lager unerreichbar) |
| 8 | aus (nach 13-16) | 19,5 s | 118 | 1280 × falscher Block (Erde unter der Plattform), 69 × kein Weg | 53 (Bereich unerreichbar) |
| 9 | aus (nach 17) | 19,5 s | 688 | 1883 × falscher Block | 0 |
| 10 | an (nach 13-17) | 119 s* | 609 (oben am fernen Hügel, Y=134-138) | 0, aber zwei Abstürze (je ~45 s) | 0 |
| 11 | an (nach 18, Schwebehöhe 0,35) | 115 s* | 425 | 0, aber Umschalt-Schleifen (Flug aus/an, ~3 min Stillstand) | 0 |
| 12 | an (nach 19-20) | 103 s* | 613 | 0, kein Absturz; 2 × "Weg steckt" (je ~35 s, Ursache offen, Diagnose eingebaut) | 0 |
| 13 | an (nach 21) | 107 s* | 447 | 0, kein Absturz; 4 × "Weg steckt" (Pendeln/Stillstand vor einem freien Wegpunkt) | 0 |
| 14 | an (nach 22) | 105 s* | 551 | 0; ein Decken-Stillstand (~60 s), Diagnose: W gedrückt, Geschwindigkeit 0, y+0,33 | 0 |
| 15 | an (nach 23) | 108 s* | 627 | 0; **kein "steckt" mehr**, kein Absturz; dafür zwei Sprünge zwischen Hügelkuppen (je ~40 s Flug) | 0 |
| 16 | an (nach 24) | 127 s* | 728 | 0; kein Hüpfen mehr; ein Kanten-Hänger (~30 s, siehe 25) | 0 |
| **17** | **an (Endstand)** | **106 s*** | **810** | **0; kein Hänger, kein Absturz, 0 Rotationsverstöße** | **0** |

\* Lager (Stein holen) + 530 Blöcke Anreise + 200 Blöcke Aufstieg; gebaut wird dann mit ~2,5 Blöcken/s.

17. **Kein Weg aus der Schwebeposition (Lauf 8):** Nach 60 s stand der fliegende Agent in der Ebene, die er gerade
    baute, und jede Wegsuche kam ohne einen einzigen Schritt zurück (`no_path`, 3004-mal, dann Pause "Bereich
    unerreichbar"). Die Ursache war im Log nicht zu sehen; eingebaut sind ein Logeintrag mit den Nav-Flags rund um die
    Startzelle (`MovementController.explainEmptyPath`, höchstens alle 2 s) und drei Sicherheitsnetze: Wegoptionen mit
    Flug, solange der Spieler fliegt (auch wenn die Abilities kurz widersprechen), kein No-Fly-Vermerk im Flug, und
    bei leerer Suche ein zweiter Versuch von der Zelle darüber mit vorangestelltem Aufstieg. Lauf 9 (gleiche
    Einstellung) lief ohne Wiederholung durch.
18. **Absturz im Survival-Flug (Lauf 10):** Survival-Flug endet, sobald die Füße einen Block berühren; beim Bauen
    der obersten Ebene am fernen Hügel (Y=134) landete der Agent auf eigenen Blöcken, trat über die Kante und fiel
    150 Blöcke bis Y=-13 (Flug erst beim Neuplanen wieder an, 45 s verloren; auf dem Server wäre es der Tod). Jetzt
    schaltet der Agent bei einem Fall mit erlaubtem Flug sofort wieder ein (`MovementController.recoverFlight`,
    Doppeltipp Leertaste), egal was die Aufgabe gerade tut.
19. **Die eigentliche Absturzursache (Lauf 11):** Der Vanilla-Client schaltet den Flug bei einem zweiten
    Leertastendruck innerhalb von 7 Ticks **um** - auch aus. Die Höhenregelung im Flug drückt Sprung an und aus,
    sobald sie um ihre Schwelle pendelt, und schaltete so den Flug ab; der Spieler fiel, bis der Neuplan ihn wieder
    einschaltete. `InputController.getOverride` hält einen erneuten Sprungdruck im Flug zurück, bis das
    Doppeltipp-Fenster vorbei ist (der Doppeltipp zum Einschalten bleibt möglich, weil er nur ohne Flug gebraucht
    wird). Dazu schwebt der Flug 0,25 statt 0,1 Blöcke über dem Wegpunkt, damit die Füße keinen Block berühren
    (Survival: Bodenkontakt beendet den Flug).
20. **Rescan in nicht empfangenen Chunks:** Auf dem Weg zum fernen Hügel meldete die Client-Welt Chunks als geladen,
    deren Blöcke noch `void_air` waren; der Rescan öffnete dort fertige Ziele wieder. `void_air` gilt jetzt überall
    (Rescan, Endprüfung, Abgleich beim Start) als "unbekannt".
21. **"Weg steckt" im Flug (Lauf 12, Diagnose aus der Regressionssuite):** Die Steckt-fest-Erholung drückte als
    Erstes Sprung (über das Hindernis) - im Flug ist das ein Aufstieg von drei Blöcken, der Spieler war "neben dem
    Pfad", Neuplanung, nach fünf Runden "steckt". Im Flug weicht die Erholung jetzt seitwärts/rückwärts aus, nur
    fünf Ticks lang.

22. **Pendeln um den Standplatz (Lauf 13, "Stuck before FLY … alles Luft"):** Flug gleitet - bei 0,3 Blöcken pro
    Tick noch drei Blöcke weiter (Reibung 0,91/Tick); die Bremse (nur Schub weglassen) reichte nicht, der Spieler
    schwang über den Standplatz hinaus, zurück, wieder hinaus, und nach 20 Ticks ohne Annäherung galt das als
    "steckt". Jetzt Gegenschub (Rückwärts), sobald der Gleitweg den letzten Knoten überschießen würde, und 0,45
    statt 0,3 Blöcke Ankunftstoleranz im Flug.

23. **Stillstand mit gedrückter W-Taste (Lauf 14, Diagnose):** `velocity (0,0,0)`, Blick korrekt, W gedrückt, Spieler
    bei y+0,33. Der Körper ist 1,8 hoch: bei mehr als 0,2 über dem Knoten ragt der Kopf in die Decke zwei Blöcke
    darüber - beim Bau von oben nach unten liegt die fertige Ebene genau dort. Die Schwebehöhe ist jetzt 0,12, unter
    einer Decke 0,08 mit engerer Sinkschwelle, und Vortrieb erst, wenn der Körper tief genug ist.

24. **Springen zwischen Hügelkuppen (Lauf 15):** "Ebenen ▼" mit Luft-Platzierung nahm die Kandidaten der global
    obersten offenen Ebene, egal wo; sobald die hiesige Kuppe bis zu dieser Ebene fertig war, flog der Agent 700
    Blöcke zur nächsten Kuppe für deren letzte Blöcke dieser Ebene. Jetzt zuerst die höchste offene Ebene in den
    Chunks um den Spieler (`BuildAgent.topDownCandidatesNearby`, bis 64 Ebenen tief), erst dann die Reise.

25. **Ein Haar unter der Kante (Lauf 16):** Mit der niedrigen Schwebehöhe lag der Spieler bei y+0,98 statt y+1,0
    - 0,02 Blöcke tiefer als der Wegpunkt; die Hebeschwelle (0,25) griff nicht, und der Körper hing mit dem untersten
    Zentimeter an jedem Block der Reihe darunter fest (W gedrückt, Geschwindigkeit 0). Jetzt hebt der Flug immer,
    sobald die Füße unter dem Niveau des Knotens sind; in einem zwei Blöcke hohen Gang über Boden landet der Agent
    und läuft, weil dort keine Schwebehöhe passt.

26. **Ertrink-Falle (Regressionssuite, FloatingIsland):** Der Laufpfad nahm die Abkürzung durch den drei Blöcke tiefen
    Teich, über dem die Insel inzwischen stand - unter Wasser ohne Luft darüber, Ertrinkschaden, Pause. Der Pfadfinder
    betritt Wasserzellen mit Wasser am Kopf nur noch, wenn beim Aufschwimmen innerhalb von vier Blöcken Luft kommt
    (`AStarPathfinder.airAbove`).

## Was bleibt

- 28,5 Mio. Blöcke bei ~180/min sind Wochen Dauerbau; die Anzeige (~57.000 Minuten) ist realistisch. Sinnvoll:
  Auswahl oder Teilbereiche.
- Die Mess-Sonde "Slow decision" (ab 10 ms, nur mit ausführlichem Log) und `decide=Ø/max` im Debug-Summary
  bleiben drin, um Rückfälle zu sehen.
