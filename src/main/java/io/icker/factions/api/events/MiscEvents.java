package io.icker.factions.api.events;

import io.icker.factions.FactionsMod;
import io.icker.factions.api.persistents.Faction;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

import io.icker.factions.api.persistents.User;
import net.minecraft.server.network.ServerPlayerEntity;

/** Events related to miscellaneous actions */
public final class MiscEvents {
    /**
     * Called when the Factions database is saved (which is also when the server saves world and
     * player files)
     */
    public static final Event<Save> ON_SAVE =
            EventFactory.createArrayBacked(
                    Save.class,
                    callbacks -> server -> {
                        for (Save callback : callbacks) {
                            callback.onSave(server);
                        }
                    });

    /** Called when the game attempts to spawn in mobs (UNIMPLEMENTED) */
    public static final Event<MobSpawnAttempt> ON_MOB_SPAWN_ATTEMPT =
            EventFactory.createArrayBacked(
                    MobSpawnAttempt.class,
                    callbacks -> () -> {
                        for (MobSpawnAttempt callback : callbacks) {
                            callback.onMobSpawnAttempt();
                        }
                    });

    /** Register connection event handlers for tracking user activity */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();

            User u = User.get(p.getUuid());
            Faction f = (u != null) ? u.getFaction() : null;

            int claimCount = (f == null) ? 0 : f.getClaims().size();
            int power      = (f == null) ? 0 : f.getPower();
            int required   = claimCount * FactionsMod.CONFIG.POWER.CLAIM_WEIGHT;
            User user = User.get(handler.getPlayer().getUuid());
            // mark them as “just seen”
            user.setLastOnline(System.currentTimeMillis());
            // NOTE: we no longer reset their drain counter here,
            // so we never try to decrease `powerDrained`
            User.save();
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            User user = User.get(handler.getPlayer().getUuid());
            // record their last-online timestamp
            user.setLastOnline(System.currentTimeMillis());
            User.save();
        });
    }

    @FunctionalInterface
    public interface Save {
        void onSave(MinecraftServer server);
    }

    @FunctionalInterface
    public interface MobSpawnAttempt {
        void onMobSpawnAttempt();
    }
}
