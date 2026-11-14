package de.johannes.witchhunt;

import de.johannes.witchhunt.search.SearchManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class WitchHunt implements ModInitializer {
    public static final String MOD_ID = "witch_hunt";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("witchhunt")
                .then(CommandManager.literal("cancel")
                    .executes(context -> SearchManager.cancel(context.getSource())))
                .then(CommandManager.literal("status")
                    .executes(context -> SearchManager.status(context.getSource())))
                .then(CommandManager.literal("start")
                    .then(CommandManager.literal("height")
                        .executes(context -> start(context.getSource(), SearchManager.SearchMode.height)))
                    .then(CommandManager.literal("group")
                        .executes(context -> start(context.getSource(), SearchManager.SearchMode.group))))
            )
        );

        ServerTickEvents.END_SERVER_TICK.register(SearchManager::tick);
    }

    private static int start(ServerCommandSource source, SearchManager.SearchMode mode) {
        MinecraftServer server = source.getServer();

        if (source.getEntity() == null) {
            source.sendError(Text.literal("Der Befehl muss von einem Spieler ausgeführt werden."));
            return 0;
        }

        return SearchManager.start(source,mode);
    }
}
