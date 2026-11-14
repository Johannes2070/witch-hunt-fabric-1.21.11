package de.johannes.witchhunt.search;

import net.minecraft.server.command.ServerCommandSource;

public interface  SearchSessionInterface {
    boolean IsFinished();
    ServerCommandSource GetCommandSource();
    void Stop();
    void Status(ServerCommandSource source);
    void Tick();
}
