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
                .executes(context -> start(context.getSource()))
                .then(CommandManager.literal("cancel")
                    .executes(context -> SearchManager.cancel(context.getSource())))
                .then(CommandManager.literal("status")
                    .executes(context -> SearchManager.status(context.getSource())))
            )
        );

        ServerTickEvents.END_SERVER_TICK.register(SearchManager::tick);
    }

    private static int start(ServerCommandSource source) {
        MinecraftServer server = source.getServer();

        if (server.isDedicated()) {
            source.sendError(Text.literal("Witch Hunt ist für Singleplayer-Welten vorgesehen."));
            return 0;
        }

        if (source.getEntity() == null) {
            source.sendError(Text.literal("Der Befehl muss von einem Spieler ausgeführt werden."));
            return 0;
        }

        return SearchManager.start(source);
    }
}
