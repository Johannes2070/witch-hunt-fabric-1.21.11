package de.johannes.witchhunt.search;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

public class SearchSessionGroup implements SearchSessionInterface {


    private final ServerCommandSource source;
    private final ServerWorld world;
    private final Structure hut;
    private final RandomSpreadStructurePlacement placement;
    private final long seed;
    private final int spacing;
    boolean finished = false;

    private final PriorityQueue<RegionNode> queue = new PriorityQueue<>();
    private final Set<RegionKey> queued = new HashSet<>();

    private final int originX;
    private final int originZ;
    private final int originChunkX;
    private final int originChunkZ;
    private long checkedChunks;
    private long checkedCandidates;

    protected SearchSessionGroup(ServerCommandSource source, ServerWorld world, Structure hut,
                                  RandomSpreadStructurePlacement placement, long seed,
                                  int originX, int originZ, int originChunkX, int originChunkZ) {
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

    private void addRegion(int regionX, int regionZ) {
        RegionKey key = new RegionKey(regionX, regionZ);
        if (!queued.add(key)) return;
        long lowerBound = distanceToRegionSquared(regionX, regionZ);
        queue.add(new RegionNode(regionX, regionZ, lowerBound));
    }

    private long distanceToRegionSquared(int regionX, int regionZ) {
        long minX = (long) regionX * spacing * 16L;
        long minZ = (long) regionZ * spacing * 16L;
        long maxX = minX + (long) spacing * 16L - 1L;
        long maxZ = minZ + (long) spacing * 16L - 1L;

        long dx = originX < minX ? minX - originX : (originX > maxX ? originX - maxX : 0L);
        long dz = originZ < minZ ? minZ - originZ : (originZ > maxZ ? originZ - maxZ : 0L);
        return dx * dx + dz * dz;
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
    public void Stop() {
        finished = true;
    }

    @Override
    public void Status(ServerCommandSource StatusSource) {
        if (finished || source.getServer() != StatusSource.getServer()) {
            StatusSource.sendFeedback(() -> Text.literal("Aktuell läuft keine Witch-Hunt-Suche."), false);
            return;
        }
        StatusSource.sendFeedback(() -> Text.literal("Witch Hunt aktiv. Untersuchte Startregionen: "
                + checkedChunks + ", bisherige Witch-Hut-Kandidaten: " + checkedCandidates
                + ". Suchzentrum: X " + originX + ", Z " + originZ + "."), false);
    }

    @Override
    public void Tick() {

    }
}
