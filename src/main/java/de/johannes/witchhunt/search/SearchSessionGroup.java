
package de.johannes.witchhunt.search;

import com.google.common.base.Stopwatch;
import de.johannes.witchhunt.search.group.Point2D;
import de.johannes.witchhunt.search.group.Point2DSet;
import de.johannes.witchhunt.search.group.PointGroupSet;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class SearchSessionGroup implements SearchSessionInterface {

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

    private final PointGroupSet WitchHuts = new PointGroupSet();

    // ---------------------------------------------------------
    // Witch Hut data
    // ---------------------------------------------------------


    // Alle bereits berechneten möglichen Sphere-Mittelpunkte.
    private int sphereCandidates = 0;


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
        var added = WitchHuts.Add(new Point2D(box.getMaxX(),box.getMaxZ()));

        if (added.isEmpty()){
            return;
        }

        checkedCandidates++;

        for (Point2DSet point2DSet : added) {
            if (point2DSet.Size()>bestGroupSize) {
                sphereCandidates = 0;
                bestGroupSize++;
                break;
            }
        }

        for (Point2DSet point2DSet : added) {
            if (point2DSet.Size() == bestGroupSize) {
                sphereCandidates++;
                ReportWitchHut(point2DSet);
            }
        }
    }

    private void ReportWitchHut(Point2DSet hut) {

        Point2D mid = hut.GetWelzl().center();
        int size = hut.Size();

        int x = (int) Math.round(mid.x());
        int z = (int) Math.round(mid.z());

        Text message = Text.empty()
                .append(Text.literal("§6Witch Hut gefunden!\n"))

                .append(Text.literal("§7Koordinaten: "))
                .append(Text.literal("§eX: " + x + " Z: " + z + "\n"))

                .append(Text.literal("§7Größe: "))
                .append(Text.literal("§e" + size + " Huts\n"))

                .append(
                        Text.literal("§a[Hierher teleportieren]")
                                .styled(style -> style
                                        .withClickEvent(
                                                new ClickEvent.RunCommand(
                                                        "/tp @s " + x + " ~ " + z
                                                )
                                        )
                                        .withHoverEvent(
                                                new HoverEvent.ShowText(
                                                        Text.literal(
                                                                "Zu dieser Witch Hut teleportieren"
                                                        )
                                                )
                                        )
                                )
                );

        source.sendFeedback(() -> message, false);
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

        for (Point2DSet group : WitchHuts.GetGroups()) {
            if (group.Size() == bestGroupSize) {
                ReportWitchHut(group);
            }
        }
    }


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
                        + sphereCandidates
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
        Stopwatch stopwatch = Stopwatch.createStarted();

        while (stopwatch.elapsed(TimeUnit.MILLISECONDS)<200){
            if(IsFinished())
                return;

            if (queue.isEmpty()) {
                return;
            }

            RegionNode node = queue.poll();

            addRegion(node.x() - 1, node.z());
            addRegion(node.x() + 1, node.z());
            addRegion(node.x(), node.z() - 1);
            addRegion(node.x(), node.z() + 1);

            checkedChunks++;

            ChunkPos start = placement.getStartChunk(seed, node.x(), node.z());
            Chunk chunk = world.getChunk(start.x, start.z, ChunkStatus.STRUCTURE_STARTS, true);

            if (chunk != null) {
                StructureStart structureStart = chunk.getStructureStart(hut);

                if (structureStart != null && structureStart.hasChildren()) {
                    addHut(structureStart.getBoundingBox());
                }
            }
        }
    }
}
