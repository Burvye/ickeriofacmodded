package io.icker.factions.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import io.icker.factions.api.persistents.Faction;

public class FactionDisbandManager {
    // 5 minutes @ 20 tps = 5 * 60 * 20 = 6000 ticks
    private static final long INTERVAL_TICKS = 5L * 60L * 20L;
    private static long tickCounter = 0;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(FactionDisbandManager::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        if (++tickCounter < INTERVAL_TICKS) return;
        tickCounter = 0;

        for (Faction f : Faction.all()) {
            if (f.getPower() == 0) {
                f.remove();
            }
        }
        Faction.save();
    }
}
