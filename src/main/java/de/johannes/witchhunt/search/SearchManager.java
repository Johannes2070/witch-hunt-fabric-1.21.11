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
    private static SearchSessionInterface active;

    private SearchManager() {}

    public static int start(ServerCommandSource source, SearchMode mode) {
        if ( (active != null) && !active.IsFinished()) {
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

        switch (mode) {
            case height ->  active = new SearchSessionHeight(source, world, hut, placement,
                    world.getSeed(), playerBlockX, playerBlockZ,
                    playerChunkX, playerChunkZ);
            case group -> active = new SearchSessionGroup(source, world, hut, placement,
                    world.getSeed(), playerBlockX, playerBlockZ,
                    playerChunkX, playerChunkZ);
        }


        source.sendFeedback(() -> Text.literal(
                "Witch Hunt gestartet. Suche unbegrenzt nach der nächstgelegenen Witch Hut mit Mittelpunkt unter Y=0. "
                + "Abbruch: /witchhunt cancel"), false);
        return 1;
    }

    public static void tick(MinecraftServer server) {
        if (active == null || active.IsFinished()) return;
        if (active.GetCommandSource().getServer() != server) return;

        try {
            active.Tick();
        } catch (Throwable error) {
            active.GetCommandSource().sendError(Text.literal("Witch Hunt wurde wegen eines Fehlers beendet: "
                    + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())));
            active.Stop();
            active = null;
        }
    }

    public static int cancel(ServerCommandSource source) {
        if (active == null || active.IsFinished() || active.GetCommandSource().getServer() != source.getServer()) {
            source.sendError(Text.literal("Es läuft gerade keine Witch-Hunt-Suche."));
            return 0;
        }
        active.Stop();
        active = null;
        source.sendFeedback(() -> Text.literal("Witch-Hunt-Suche abgebrochen."), false);
        return 1;
    }

    public static int status(ServerCommandSource source) {
        active.Status(source);
        return 1;
    }

    public enum SearchMode{
        height,
        group
    }
}
