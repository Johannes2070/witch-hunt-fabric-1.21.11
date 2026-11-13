# Witch Hunt – Fabric 1.21.11

Ein Singleplayer-Mod, der die nächstgelegene Witch Hut (`minecraft:swamp_hut`) sucht,
deren Mittelpunkt der vollständigen Structure Bounding Box unter Y=0 liegt.

## Anforderungen

- Minecraft Java 1.21.11
- Fabric Loader
- Fabric API
- Java 21
- Gradle (Wrapper kann in der IDE erzeugt werden)

Der Mod benötigt keine direkte Abhängigkeit zu Tectonic oder C2ME. Er liest die aktive
Structure-Set-Konfiguration der Welt und verwendet Minecrafts Chunk-Generierung bis
`ChunkStatus.STRUCTURE_STARTS`.

## Befehle

- `/witchhunt` – Suche starten
- `/witchhunt status` – Status anzeigen
- `/witchhunt cancel` – Suche abbrechen

Die Suche ist auf Singleplayer beschränkt.

## Suchverfahren

Die Vanilla-Struktur `minecraft:swamp_hut` wird über das Structure Set
`minecraft:swamp_huts` und dessen `RandomSpreadStructurePlacement` gesucht.
Die Regionen werden in einer Prioritätswarteschlange nach ihrer minimal möglichen
Entfernung zum Spieler abgearbeitet. Pro Server-Tick wird höchstens ein
Startchunk bis `STRUCTURE_STARTS` angefordert. Die Bounding Box wird über
`StructureStart#getBoundingBox()` ausgewertet.

Der Suchalgorithmus hält den besten gültigen Treffer zurück und beendet die Suche,
sobald die minimale mögliche Entfernung jeder noch nicht untersuchten Region größer
als die Entfernung des besten Treffers ist. Damit ist der Treffer unter den
registrierten Witch-Hut-Startpositionen der nächstgelegene.

## Wichtige Hinweise

- `getChunk(..., STRUCTURE_STARTS, true)` kann je nach Welt und Hardware spürbar
  Arbeit auslösen. Die Suche ist deshalb auf einen Kandidaten pro Tick begrenzt.
- C2ME bleibt für das Chunk-Management zuständig; der Mod startet keine eigenen
  Worldgen-Threads und greift nicht auf interne C2ME-Klassen zu.
- Tectonic wird über die aktive Weltkonfiguration berücksichtigt. Die Suche hängt
  nicht von Vanilla-Terrainhöhen ab.
- Der erste Prototyp erwartet, dass das aktive Witch-Hut-Structure-Set weiterhin
  unter `minecraft:swamp_huts` registriert ist und `RandomSpreadStructurePlacement`
  verwendet. Ein Datapack, das dieses Set entfernt oder vollständig anders registriert,
  benötigt eine Erweiterung der Structure-Set-Ermittlung.
- Es wurde in dieser Umgebung kein Gradle-Build gegen die Minecraft-Abhängigkeiten
  ausgeführt. Bitte zunächst in einer IDE oder mit `gradle build` kompilieren und
  die genannten Tests durchführen.

## Build

Wenn kein Gradle-Wrapper enthalten ist, öffne das Projekt in IntelliJ IDEA mit
Gradle-Unterstützung oder installiere Gradle und führe aus:

```bash
gradle build
```

Die Mod-JAR liegt anschließend unter `build/libs/`.

## Tests

1. Neue Fabric-1.21.11-Welt ohne Datapacks starten.
2. `/witchhunt` ausführen und Fortschrittsmeldungen beobachten.
3. `/witchhunt cancel` testen.
4. In einer Testwelt mit Tectonic testen.
5. In einer Testwelt mit Tectonic und C2ME testen.
6. Trefferposition mit `/tp` besuchen und Bounding-Box-Mittelpunkt überprüfen.
7. Welt neu laden und sicherstellen, dass die Suche weiterhin funktioniert.
