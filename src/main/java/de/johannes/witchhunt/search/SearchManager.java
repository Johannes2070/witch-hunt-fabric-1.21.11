package de.johannes.witchhunt.search;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureSet;
import net.minecraft.structure.StructureSetKeys;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureKeys;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class SearchManager {
    private static SearchSession active;

    private SearchManager() {}

    public static int start(ServerCommandSource source) {
        if ( (active != null) && !active.finished) {
            source.sendError(Text.literal("Es läuft bereits eine Witch-Hunt-Suche. Nutze /witchhunt cancel."));
            return 0;
        }

        ServerWorld world = source.getWorld();
        Registry<StructureSet> structureSets = world.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE_SET);
        StructureSet hutSet = structureSets.get(StructureSetKeys.SWAMP_HUTS);

        if (hutSet == null) {
            source.sendError(Text.literal("Das Structure Set minecraft:swamp_huts wurde in dieser Welt nicht gefunden."));
            return 0;
        }

        if (!(hutSet.placement() instanceof RandomSpreadStructurePlacement placement)) {
            source.sendError(Text.literal("Die Witch-Hut-Platzierung ist kein RandomSpreadStructurePlacement. Suche abgebrochen."));
            return 0;
        }

        Registry<Structure> structures = world.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);
        Structure hut = structures.get(StructureKeys.SWAMP_HUT);
        if (hut == null) {
            source.sendError(Text.literal("Die Struktur minecraft:swamp_hut wurde nicht gefunden."));
            return 0;
        }

        Vec3d playerPos = source.getPosition();
        int playerBlockX = MathHelper.floor(playerPos.x);
        int playerBlockZ = MathHelper.floor(playerPos.z);
        int playerChunkX = Math.floorDiv(playerBlockX, 16);
        int playerChunkZ = Math.floorDiv(playerBlockZ, 16);

        active = new SearchSession(source, world, hut, placement,
                world.getSeed(), playerBlockX, playerBlockZ,
                playerChunkX, playerChunkZ);

        source.sendFeedback(() -> Text.literal(
                "Witch Hunt gestartet. Suche unbegrenzt nach der nächstgelegenen Witch Hut mit Mittelpunkt unter Y=0. "
                + "Abbruch: /witchhunt cancel"), false);
        return 1;
    }

    public static void tick(MinecraftServer server) {
        if (active == null || active.finished) return;
        if (active.source.getServer() != server) return;

        try {
            active.tick();
        } catch (Throwable error) {
            active.source.sendError(Text.literal("Witch Hunt wurde wegen eines Fehlers beendet: "
                    + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())));
            active.finished = true;
            active = null;
        }
    }

    public static int cancel(ServerCommandSource source) {
        if (active == null || active.finished || active.source.getServer() != source.getServer()) {
            source.sendError(Text.literal("Es läuft gerade keine Witch-Hunt-Suche."));
            return 0;
        }
        active.finished = true;
        active = null;
        source.sendFeedback(() -> Text.literal("Witch-Hunt-Suche abgebrochen."), false);
        return 1;
    }

    public static int status(ServerCommandSource source) {
        if (active == null || active.finished || active.source.getServer() != source.getServer()) {
            source.sendFeedback(() -> Text.literal("Aktuell läuft keine Witch-Hunt-Suche."), false);
            return 0;
        }
        SearchSession s = active;
        source.sendFeedback(() -> Text.literal("Witch Hunt aktiv. Untersuchte Startregionen: "
                + s.checkedChunks + ", bisherige Witch-Hut-Kandidaten: " + s.checkedCandidates
                + ". Suchzentrum: X " + s.originX + ", Z " + s.originZ + "."), false);
        return 1;
    }

    private static final class SearchSession {
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

        private static final int MAX_PENDING_CHUNKS = 16;

        private final PriorityQueue<RegionNode> queue = new PriorityQueue<>();
        private final Set<RegionKey> queued = new HashSet<>();
        private final Map<ChunkPos, CompletableFuture<OptionalChunk<Chunk>>> pendingChunks =new HashMap<>();

        private long checkedChunks;
        private long checkedCandidates;
        private boolean finished;
        private long bestDistanceSquared = Long.MAX_VALUE;
        private SearchResult best;
        private Integer bestY = null;

        private SearchSession(ServerCommandSource source, ServerWorld world, Structure hut,
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
            Iterator<Map.Entry<ChunkPos, CompletableFuture<OptionalChunk<Chunk>>>> iterator =
                    pendingChunks.entrySet().iterator();

            while (iterator.hasNext()) {
                var entry = iterator.next();

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

                iterator.remove();

                try {
                    OptionalChunk<Chunk> optional = future.join();

                    optional.ifPresent(this::CheckChunk);

                } catch (CompletionException exception) {
                    source.sendError(Text.literal(
                            "Fehler beim Laden eines Chunks: " + exception.getMessage()
                    ));
                }
            }
        }

        private void submitChunkRequests() {
            while (pendingChunks.size() < MAX_PENDING_CHUNKS && !queue.isEmpty()) {
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

                requestChunk(start);
            }
        }

        private void requestChunk(ChunkPos pos) {
            if (pendingChunks.containsKey(pos)) {
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

            pendingChunks.put(pos, future);
        }

        private void CheckChunk(Chunk chunk) {
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
            active = null;
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
            active = null;
        }
    }
}
