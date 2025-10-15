package io.icker.factions.core;

import io.icker.factions.api.persistents.User;
import io.icker.factions.api.persistents.Faction;
import io.icker.factions.api.persistents.Claim;
import io.icker.factions.config.Config;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class DecayManager {
    private static final Config.DecayConfig DECAY = Config.load().DECAY;

    // Clamp to at least 1 hour
    private static final int INTERVAL_HOURS = Math.max(1, DECAY.drainIntervalHours);
    private static final long TICKS_PER_INTERVAL =
            INTERVAL_HOURS * 20L /* tps */ * 60L /* sec */ * 60L /* min */;

    private static long tickCounter = 0;

    public static void register() {
        if (!DECAY.enabled) return;
        ServerTickEvents.END_SERVER_TICK.register(DecayManager::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        if (++tickCounter < TICKS_PER_INTERVAL) return;
        tickCounter = 0;
        performDecayPass(server);
    }

    // Packs (x,z) into a single long key
    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    // Build { dimension -> set of chunkKeys } for fast membership tests
    private static java.util.Map<String, java.util.Set<Long>> groupClaimsByDim(java.util.List<io.icker.factions.api.persistents.Claim> claims) {
        java.util.Map<String, java.util.Set<Long>> byDim = new java.util.HashMap<>();
        for (io.icker.factions.api.persistents.Claim c : claims) {
            byDim.computeIfAbsent(c.level, k -> new java.util.HashSet<>())
                    .add(chunkKey(c.x, c.z));
        }
        return byDim;
    }

    // Returns the subset of srcChunks that have ANY target chunk within Chebyshev distance <= radius
    private static java.util.Set<Long> touchedWithinRadius(java.util.Set<Long> srcChunks,
                                                           java.util.Set<Long> targetChunks,
                                                           int radius) {
        java.util.Set<Long> touched = new java.util.HashSet<>();
        if (srcChunks.isEmpty() || targetChunks.isEmpty() || radius < 0) return touched;

        // For each source chunk, scan its (2r+1)^2 neighborhood and see if any target exists
        for (long key : srcChunks) {
            int x = (int) (key >> 32);
            int z = (int) key;
            boolean hit = false;

            for (int dx = -radius; dx <= radius && !hit; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long neighbor = chunkKey(x + dx, z + dz);
                    if (targetChunks.contains(neighbor)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) touched.add(key); // count each source claim at most once
        }
        return touched;
    }

    // Send a single summary message to all online members of a faction
    private static void notifyFaction(MinecraftServer server, Faction faction, String message) {
        for (User u : User.getByFaction(faction.getID())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(u.getID());
            if (p != null) {
                p.sendMessage(Text.literal(message), false);
            }
        }
    }

    private static void performDecayPass(MinecraftServer server) {
        long now = System.currentTimeMillis();
        long threshold = DECAY.inactiveThresholdDays * 24L * 60L * 60L * 1000L;

        // Execute decay on main server thread to prevent race conditions
        // All faction/user modifications must happen on the same thread as commands
        if (!server.isOnThread()) {
            server.execute(() -> performDecayPass(server));
            return;
        }

        // 1) Per‑member inactivity drain (capped by their join‑power)
        for (Faction faction : Faction.all()) {
            for (User user : User.getByFaction(faction.getID())) {
                long inactive = now - user.getLastOnline();
                if (inactive < threshold) continue;

                int drainedSoFar = user.getPowerDrained();
                int maxDrain = DECAY.powerPerMember;
                if (drainedSoFar >= maxDrain) continue;

                int currPower = faction.getPower();
                int remainForUser = maxDrain - drainedSoFar;
                int actualDrain = Math.min(DECAY.drainAmount, Math.min(currPower, remainForUser));
                if (actualDrain <= 0) continue;

                faction.adjustPower(-actualDrain);
                user.setPowerDrained(drainedSoFar + actualDrain);

                // personal feedback
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(user.getID());
                String name = p != null ? p.getName().getString() : user.getID().toString();
                if (p != null) {
                    p.sendMessage(
                            Text.literal(String.format(
                                    "You have been inactive for %d days — %d power drained.",
                                    DECAY.inactiveThresholdDays,
                                    actualDrain
                            )),
                            false
                    );
                }
                // broadcast to other online faction members
                for (User member : User.getByFaction(faction.getID())) {
                    if (member.getID().equals(user.getID())) continue;
                    ServerPlayerEntity m = server.getPlayerManager().getPlayer(member.getID());
                    if (m != null) {
                        m.sendMessage(
                                Text.literal(String.format(
                                        "%s was inactive for %d days — %d power drained from your faction.",
                                        name,
                                        DECAY.inactiveThresholdDays,
                                        actualDrain
                                )),
                                false
                        );
                    }
                }
            }
        }
// Optimization: Skip pairs where factions have no claims in the same dimension
        java.util.List<Faction> factions = new java.util.ArrayList<>(Faction.all());
        int n = factions.size();

        for (int i = 0; i < n; i++) {
            Faction a = factions.get(i);
            java.util.Map<String, java.util.Set<Long>> aByDim =
                    groupClaimsByDim(io.icker.factions.api.persistents.Claim.getPaidByFaction(a.getID()));

            // Early skip if faction has no claims
            if (aByDim.isEmpty()) continue;

            for (int j = i + 1; j < n; j++) {
                Faction b = factions.get(j);
                java.util.Map<String, java.util.Set<Long>> bByDim =
                        groupClaimsByDim(io.icker.factions.api.persistents.Claim.getPaidByFaction(b.getID()));

                // Early skip if faction has no claims
                if (bByDim.isEmpty()) continue;

                // Early skip if factions share no common dimensions
                boolean sharesDimension = false;
                for (String dim : aByDim.keySet()) {
                    if (bByDim.containsKey(dim)) {
                        sharesDimension = true;
                        break;
                    }
                }
                if (!sharesDimension) continue;


                // 3) Persist
                User.save();
                Faction.save();
            }
        }
    }
}