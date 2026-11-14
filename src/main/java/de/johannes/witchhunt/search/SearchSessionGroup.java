
package de.johannes.witchhunt.search;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockBox;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.util.*;

public class SearchSessionGroup implements SearchSessionInterface {

    private static final double DESPAWN_RADIUS = 128.0;
    private static final double DESPAWN_RADIUS_SQUARED =
            DESPAWN_RADIUS * DESPAWN_RADIUS;

    private static final double EPSILON = 0.0001;

    private final ServerCommandSource source;
    private final ServerWorld world;
    private final Structure hut;
    private final RandomSpreadStructurePlacement placement;

    private final long seed;
    private final int spacing;

    boolean finished = false;

    private final PriorityQueue<RegionNode> queue =
            new PriorityQueue<>();

    private final Set<RegionKey> queued =
            new HashSet<>();

    private final int originX;
    private final int originZ;

    private final int originChunkX;
    private final int originChunkZ;

    private long checkedChunks;
    private long checkedCandidates;

    // ---------------------------------------------------------
    // Witch Hut data
    // ---------------------------------------------------------

    private final List<HutPoint> huts = new ArrayList<>();

    // Alle bereits berechneten möglichen Sphere-Mittelpunkte.
    private final List<SphereCandidate> sphereCandidates =
            new ArrayList<>();

    // Nur Gruppen mit der aktuell größten Größe.
    // BitSet identifiziert eine Gruppe anhand ihrer Hut-Indizes.
    private final Map<BitSet, GroupResult> bestGroups =
            new HashMap<>();

    private int bestGroupSize = 0;

    // ---------------------------------------------------------
    // Data classes
    // ---------------------------------------------------------

    private record HutPoint(double x, double z) {}

    private static final class SphereCandidate {

        final double centerX;
        final double centerZ;

        final BitSet group = new BitSet();

        SphereCandidate(double centerX, double centerZ) {
            this.centerX = centerX;
            this.centerZ = centerZ;
        }
    }

    private record GroupResult(
            double centerX,
            double centerZ,
            List<Integer> hutIndices
    ) {}

    private record RegionKey(int x, int z) {}

    private record RegionNode(
            int x,
            int z,
            long lowerBound
    ) implements Comparable<RegionNode> {

        @Override
        public int compareTo(RegionNode other) {
            return Long.compare(lowerBound, other.lowerBound);
        }
    }

    // ---------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------

    protected SearchSessionGroup(
            ServerCommandSource source,
            ServerWorld world,
            Structure hut,
            RandomSpreadStructurePlacement placement,
            long seed,
            int originX,
            int originZ,
            int originChunkX,
            int originChunkZ
    ) {

        this.source = source;
        this.world = world;
        this.hut = hut;
        this.placement = placement;
        this.seed = seed;

        this.originX = originX;
        this.originZ = originZ;

        this.originChunkX = originChunkX;
        this.originChunkZ = originChunkZ;

        this.spacing = placement.getSpacing();

        int startRegionX = Math.floorDiv(originChunkX, spacing);
        int startRegionZ = Math.floorDiv(originChunkZ, spacing);

        addRegion(startRegionX, startRegionZ);
    }

    // ---------------------------------------------------------
    // Region handling
    // ---------------------------------------------------------

    private void addRegion(int regionX, int regionZ) {

        RegionKey key = new RegionKey(regionX, regionZ);

        if (!queued.add(key))
            return;

        long lowerBound =
                distanceToRegionSquared(regionX, regionZ);

        queue.add(new RegionNode(
                regionX,
                regionZ,
                lowerBound
        ));
    }

    private long distanceToRegionSquared(
            int regionX,
            int regionZ
    ) {

        long minX = (long) regionX * spacing * 16L;
        long minZ = (long) regionZ * spacing * 16L;

        long maxX = minX + (long) spacing * 16L - 1L;
        long maxZ = minZ + (long) spacing * 16L - 1L;

        long dx = originX < minX
                ? minX - originX
                : (originX > maxX ? originX - maxX : 0L);

        long dz = originZ < minZ
                ? minZ - originZ
                : (originZ > maxZ ? originZ - maxZ : 0L);

        return dx * dx + dz * dz;
    }

    // ---------------------------------------------------------
    // Witch Hut registration
    // ---------------------------------------------------------

    public void addHut(BlockBox box) {

        if (finished)
            return;

        double x = (box.getMinX() + box.getMaxX()) / 2.0;
        double z = (box.getMinZ() + box.getMaxZ()) / 2.0;

        HutPoint newHut = new HutPoint(x, z);

        int newIndex = huts.size();

        huts.add(newHut);

        checkedCandidates++;

        // 1. Alle bereits existierenden Sphere-Kandidaten
        //    um die neue Hut ergänzen.
        updateExistingCandidates(newHut, newIndex);

        // 2. Neuer Kandidat: Mittelpunkt auf der Hutposition.
        SphereCandidate singleCandidate =
                new SphereCandidate(x, z);

        addSphereCandidate(singleCandidate);

        // 3. Neue mögliche Mittelpunkte aus allen Paaren
        //    der neuen Hut mit den bisherigen Hütten.
        for (int i = 0; i < newIndex; i++) {

            HutPoint oldHut = huts.get(i);

            createPairCandidates(oldHut, newHut);
        }
    }

    // ---------------------------------------------------------
    // Incremental evaluation
    // ---------------------------------------------------------

    private void updateExistingCandidates(
            HutPoint newHut,
            int newIndex
    ) {

        for (SphereCandidate candidate : sphereCandidates) {

            if (isInsideSphere(
                    candidate.centerX,
                    candidate.centerZ,
                    newHut
            )) {

                candidate.group.set(newIndex);

                registerGroup(candidate);
            }
        }
    }

    // ---------------------------------------------------------
    // Generate new sphere centers from two huts
    // ---------------------------------------------------------

    private void createPairCandidates(
            HutPoint a,
            HutPoint b
    ) {

        double dx = b.x() - a.x();
        double dz = b.z() - a.z();

        double distanceSquared = dx * dx + dz * dz;

        // Zwei Hütten können nur gemeinsam in eine Sphere,
        // wenn ihr Abstand maximal 256 Blöcke beträgt.
        if (distanceSquared >
                4.0 * DESPAWN_RADIUS_SQUARED + EPSILON) {

            return;
        }

        double distance = Math.sqrt(distanceSquared);

        // Identische Positionen benötigen keine
        // zusätzlichen Schnittpunkte.
        if (distance < EPSILON)
            return;

        double halfDistance = distance / 2.0;

        double heightSquared =
                DESPAWN_RADIUS_SQUARED -
                        halfDistance * halfDistance;

        double height = Math.sqrt(
                Math.max(0.0, heightSquared)
        );

        double midX = (a.x() + b.x()) / 2.0;
        double midZ = (a.z() + b.z()) / 2.0;

        double offsetX = -dz / distance * height;
        double offsetZ = dx / distance * height;

        // Erster möglicher Mittelpunkt.
        SphereCandidate first = new SphereCandidate(
                midX + offsetX,
                midZ + offsetZ
        );

        addSphereCandidate(first);

        // Zweiter möglicher Mittelpunkt.
        SphereCandidate second = new SphereCandidate(
                midX - offsetX,
                midZ - offsetZ
        );

        addSphereCandidate(second);
    }

    // ---------------------------------------------------------
    // Add and evaluate sphere candidate
    // ---------------------------------------------------------

    private void addSphereCandidate(
            SphereCandidate candidate
    ) {

        int count = huts.size();

        // Alle bisher gefundenen Hütten überprüfen.
        for (int i = 0; i < count; i++) {

            HutPoint hut = huts.get(i);

            if (isInsideSphere(
                    candidate.centerX,
                    candidate.centerZ,
                    hut
            )) {

                candidate.group.set(i);
            }
        }

        sphereCandidates.add(candidate);

        registerGroup(candidate);
    }

    // ---------------------------------------------------------
    // Distance check
    // ---------------------------------------------------------

    private boolean isInsideSphere(
            double centerX,
            double centerZ,
            HutPoint hut
    ) {

        double dx = hut.x() - centerX;
        double dz = hut.z() - centerZ;

        double distanceSquared = dx * dx + dz * dz;

        return distanceSquared <=
                DESPAWN_RADIUS_SQUARED + EPSILON;
    }

    // ---------------------------------------------------------
    // Register group
    // ---------------------------------------------------------

    private void registerGroup(
            SphereCandidate candidate
    ) {

        int size = candidate.group.cardinality();

        if (size == 0)
            return;

        // Schlechter als das bisherige Ergebnis.
        if (size < bestGroupSize)
            return;

        // Neue Rekordgruppe.
        if (size > bestGroupSize) {

            bestGroupSize = size;

            bestGroups.clear();
        }

        // Unveränderlichen Schlüssel erzeugen.
        BitSet key = (BitSet) candidate.group.clone();

        // Gruppe wurde bereits gefunden.
        if (bestGroups.containsKey(key))
            return;

        List<Integer> indices = new ArrayList<>();

        for (int i = key.nextSetBit(0);
             i >= 0;
             i = key.nextSetBit(i + 1)) {

            indices.add(i);
        }

        GroupResult result = new GroupResult(
                candidate.centerX,
                candidate.centerZ,
                List.copyOf(indices)
        );

        bestGroups.put(key, result);

        reportGroup(result);
    }

    // ---------------------------------------------------------
    // Report new best or equal group
    // ---------------------------------------------------------

    private void reportGroup(GroupResult result) {

        source.sendFeedback(() -> Text.literal(
                "§aWitch-Hut-Gruppe gefunden! Größe: "
                        + result.hutIndices().size()
                        + " | Mittelpunkt: X "
                        + format(result.centerX())
                        + " Z "
                        + format(result.centerZ())
        ), false);
    }

    // ---------------------------------------------------------
    // Stop and report all best groups
    // ---------------------------------------------------------

    @Override
    public void Stop() {

        if (finished)
            return;

        finished = true;

        source.sendFeedback(() -> Text.literal(
                "§6Witch-Hunt-Suche beendet!"
        ), false);

        if (bestGroups.isEmpty()) {

            source.sendFeedback(() -> Text.literal(
                    "§cKeine Witch Huts gefunden."
            ), false);

            return;
        }

        source.sendFeedback(() -> Text.literal(
                "§aGrößte gefundene Gruppe: "
                        + bestGroupSize
                        + " Witch Huts."
                        + " Insgesamt "
                        + bestGroups.size()
                        + " unterschiedliche Gruppen."
        ), false);

        int groupNumber = 1;

        for (GroupResult result : bestGroups.values()) {

            int number = groupNumber++;

            source.sendFeedback(() -> Text.literal(
                    "§eGruppe " + number
                            + " | Mittelpunkt: X "
                            + format(result.centerX())
                            + " Z "
                            + format(result.centerZ())
            ), false);

            for (int index : result.hutIndices()) {

                HutPoint hut = huts.get(index);

                source.sendFeedback(() -> Text.literal(
                        "  §7Hut: X "
                                + format(hut.x())
                                + " Z "
                                + format(hut.z())
                ), false);
            }
        }
    }

    // ---------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // ---------------------------------------------------------
    // SearchSessionInterface
    // ---------------------------------------------------------

    @Override
    public boolean IsFinished() {
        return finished;
    }

    @Override
    public ServerCommandSource GetCommandSource() {
        return source;
    }

    @Override
    public void Status(ServerCommandSource statusSource) {

        if (finished ||
                source.getServer() != statusSource.getServer()) {

            statusSource.sendFeedback(() ->
                    Text.literal(
                            "Aktuell läuft keine Witch-Hunt-Suche."
                    ), false);

            return;
        }

        statusSource.sendFeedback(() -> Text.literal(
                "Witch Hunt aktiv. "
                        + "Untersuchte Startregionen: "
                        + checkedChunks
                        + ", bisherige Witch-Hut-Kandidaten: "
                        + checkedCandidates
                        + ", Sphere-Kandidaten: "
                        + sphereCandidates.size()
                        + ", größte Gruppe: "
                        + bestGroupSize
                        + ". Suchzentrum: X "
                        + originX
                        + ", Z "
                        + originZ
        ), false);
    }

    @Override
    public void Tick() {

        // Hier bleibt deine bestehende Region-/Chunk-Suche.
        // Sobald eine Witch Hut vollständig ermittelt wurde:
        //
        // addHut(start.getBoundingBox());
    }
}
