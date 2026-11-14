package de.johannes.witchhunt.search;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class SearchSessionHeight implements SearchSessionInterface {

    private final ServerCommandSource source;
    private final ServerWorld world;
    private final Structure hut;
    private final RandomSpreadStructurePlacement placement;
    private final long seed;
    private final int originX;
    private final int originZ;
    private final int originChunkX;
    private final int originChunkZ;
    private final int spacing;

    private final PriorityQueue<RegionNode> queue = new PriorityQueue<>();
    private final Set<RegionKey> queued = new HashSet<>();
    private final Map<ChunkPos, CompletableFuture<OptionalChunk<Chunk>>> pendingChunksFeature =new HashMap<>();
    private final Map<ChunkPos, CompletableFuture<OptionalChunk<Chunk>>> pendingChunksStructure =new HashMap<>();

    private long checkedChunks;
    private long checkedCandidates;
    private boolean finished;
    private long bestDistanceSquared = Long.MAX_VALUE;
    private SearchResult best;
    private Integer bestY = null;

    protected SearchSessionHeight(ServerCommandSource source, ServerWorld world, Structure hut,
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

    private void tick() {
        if (queue.isEmpty()) {
            finishWithoutResult();
            return;
        }

        RegionNode next = queue.peek();

        // Every unvisited region has a lower-bound distance at least this large.
        // Once that lower bound exceeds the best candidate's distance, the result is globally nearest.
        if (best != null && next.lowerBoundDistanceSquared() > bestDistanceSquared && best.centerY() <= -40) {
            finishWithResult();
            return;
        }
        processCompletedChunks();
        submitChunkRequests();
    }

    private void processCompletedChunks() {
        Iterator<Map.Entry<ChunkPos, CompletableFuture<OptionalChunk<Chunk>>>> iteratorStruct =
                pendingChunksStructure.entrySet().iterator();

        while (iteratorStruct.hasNext()) {
            var entry = iteratorStruct.next();

            CompletableFuture<OptionalChunk<Chunk>> future = entry.getValue();

            if (!future.isDone()) {
                continue;
            }

            checkedChunks++;

            if (checkedChunks % 1000000 == 0) {
                long count = checkedChunks;
                source.sendFeedback(() -> Text.literal("Witch Hunt: " + count
                        + " Startregionen untersucht; " + checkedCandidates + " Kandidaten geprüft."), false);
            }

            iteratorStruct.remove();

            try {
                OptionalChunk<Chunk> optional = future.join();

                optional.ifPresent(this::CheckChunk);

            } catch (CompletionException exception) {
                source.sendError(Text.literal(
                        "Fehler beim Laden eines Chunks: " + exception.getMessage()
                ));
            }
        }

        Iterator<Map.Entry<ChunkPos, CompletableFuture<OptionalChunk<Chunk>>>> iteratorFeature =
                pendingChunksFeature.entrySet().iterator();

        while (iteratorFeature.hasNext()) {
            var entry = iteratorFeature.next();

            CompletableFuture<OptionalChunk<Chunk>> future = entry.getValue();

            if (!future.isDone()) {
                continue;
            }

            iteratorFeature.remove();

            try {
                OptionalChunk<Chunk> optional = future.join();

                optional.ifPresent(this::CheckChunkHight);

            } catch (CompletionException exception) {
                source.sendError(Text.literal(
                        "Fehler beim Laden eines Chunks: " + exception.getMessage()
                ));
            }
        }
    }

    private static final int TARGET_PENDING_CHUNKS = 100;

    private void submitChunkRequests() {
        while (pendingChunksStructure.size() < TARGET_PENDING_CHUNKS && !queue.isEmpty()) {
            RegionNode node = queue.poll();

            addRegion(node.x() - 1, node.z());
            addRegion(node.x() + 1, node.z());
            addRegion(node.x(), node.z() - 1);
            addRegion(node.x(), node.z() + 1);

            ChunkPos start = placement.getStartChunk(
                    seed,
                    node.x(),
                    node.z()
            );

            requestChunkStructure(start);
        }
    }

    private void requestChunkFeature(ChunkPos pos) {
        if (pendingChunksFeature.containsKey(pos)) {
            return;
        }

        ServerChunkManager manager = world.getChunkManager();

        CompletableFuture<OptionalChunk<Chunk>> future =
                manager.getChunkFutureSyncOnMainThread(
                        pos.x,
                        pos.z,
                        ChunkStatus.FEATURES,
                        true

                );

        pendingChunksFeature.put(pos, future);
    }

    private void requestChunkStructure(ChunkPos pos) {
        if (pendingChunksStructure.containsKey(pos)) {
            return;
        }

        ServerChunkManager manager = world.getChunkManager();

        CompletableFuture<OptionalChunk<Chunk>> future =
                manager.getChunkFutureSyncOnMainThread(
                        pos.x,
                        pos.z,
                        ChunkStatus.STRUCTURE_STARTS,
                        true
                );

        pendingChunksStructure.put(pos, future);
    }

    private void CheckChunk(Chunk chunk) {
        if (chunk == null) {
            return;
        }

        StructureStart structureStart = chunk.getStructureStart(hut);


        if (structureStart == null || !structureStart.hasChildren()) {
            return;
        }

        requestChunkFeature(chunk.getPos());
    }

    private void CheckChunkHight(Chunk chunk) {
        if (chunk == null) {
            return;
        }

        StructureStart structureStart = chunk.getStructureStart(hut);

        if (structureStart == null || !structureStart.hasChildren()) {
            return;
        }

        checkedCandidates++;
        BlockBox box = structureStart.getBoundingBox();
        int centerY = box.getMaxY();

        if(bestY == null || centerY <= bestY){

            int centerX = Math.floorDiv(box.getMinX() + box.getMaxX(), 2);
            int centerZ = Math.floorDiv(box.getMinZ() + box.getMaxZ(), 2);

            long dx = (long) centerX - originX;
            long dz = (long) centerZ - originZ;
            long distanceSquared = dx * dx + dz * dz;

            if (bestY != null && centerY == bestY) {
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                    best = new SearchResult(centerX, centerY, centerZ,
                            box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY(),
                            box.getMinZ(), box.getMaxZ(), distanceSquared);

                    printResult(best);
                }
            }else {
                bestY = centerY;
                best = new SearchResult(centerX, centerY, centerZ,
                        box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY(),
                        box.getMinZ(), box.getMaxZ(), distanceSquared);

                printResult(best);
            }
        }
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

    private void finishWithResult() {
        finished = true;
        SearchResult r = best;
        printResult(r);
    }

    private void printResult(SearchResult r){
        source.sendFeedback(() -> Text.literal("Witch Hut gefunden! Mittelpunkt: X "
                + r.centerX() + ", Y " + r.centerY() + ", Z " + r.centerZ()
                + " | Bounding Box: X " + r.minX() + ".." + r.maxX()
                + ", Y " + r.minY() + ".." + r.maxY()
                + ", Z " + r.minZ() + ".." + r.maxZ()
                + " | Untersuchte Startregionen: " + checkedChunks), false);
    }

    private void finishWithoutResult() {
        finished = true;
        source.sendFeedback(() -> Text.literal("Keine weitere Witch Hut gefunden. Suche beendet."), false);
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
        tick();
    }
}
