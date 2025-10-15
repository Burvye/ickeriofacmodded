package io.icker.factions.api.persistents;

import io.icker.factions.api.events.ClaimEvents;
import io.icker.factions.api.persistents.User.Rank;
import io.icker.factions.database.Database;
import io.icker.factions.database.Field;
import io.icker.factions.database.Name;
import io.icker.factions.util.WorldUtils;
import net.minecraft.util.ActionResult;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A single chunk claim.  Either a “real” claim (in STORE) or a one‑chunk buffer
 * around a real claim (buffer==true, never in STORE).
 */
@Name("Claim")
public class Claim {

    /** when this claim was created (ms since epoch). */
    @Field("Created")
    public long created;
    /** Only the “paid” chunks that the faction explicitly claimed. */
    public static List<Claim> getPaidByFaction(UUID factionID) {
        return STORE.values().stream()
                .filter(c -> c.factionID.equals(factionID))
                .toList();
    }

    /**
     * The 1‑chunk “buffer zone” around every paid chunk.
     * Generates all neighbouring chunks at Chebyshev distance = 1,
     * removes any that are themselves paid,
     * and only includes those not claimed by another faction.
     */
    public static List<Claim> getBufferByFaction(UUID factionID) {
        Set<String> paidKeys = getPaidByFaction(factionID).stream()
                .map(Claim::getKey)
                .collect(Collectors.toSet());
        Set<Claim> buffers = new HashSet<>();

        for (Claim paid : getPaidByFaction(factionID)) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    String key = String.format("%s-%d-%d", paid.level, paid.x + dx, paid.z + dz);
                    Claim neighbor = STORE.get(key);
                    if (neighbor == null && !paidKeys.contains(key)) {
                        buffers.add(new Claim(paid.x + dx, paid.z + dz, paid.level, factionID, true));
                    }
                }
            }
        }
        return List.copyOf(buffers);
    }
    // only real claims live here
    public static final HashMap<String, Claim> STORE =
            Database.load(Claim.class, Claim::getKey);

    /** chunk X coordinate */
    @Field("X") public int x;
    /** chunk Z coordinate */
    @Field("Z") public int z;
    /** dimension ID */
    @Field("Level") public String level;
    /** owning faction */
    @Field("FactionID") public UUID factionID;
    /** which rank may build here */
    @Field("AccessLevel") public Rank accessLevel;

    @Field("PermittedPlayers")
    public List<UUID> permittedPlayers = new ArrayList<>();

    // not persisted – true for our synthetic one-chunk buffers
    private final boolean buffer;

    /** grant someone access to this chunk */
    public boolean grant(UUID playerId, UUID grantedBy) {
        User granter = User.get(grantedBy);
        User target = User.get(playerId);

        // Only faction owners/leaders can grant
        if (granter.rank == null || granter.rank.ordinal() > Rank.LEADER.ordinal()) {
            return false;
        }

// Target must be in same faction
        Faction targetFaction = target.getFaction();
        if (targetFaction == null || !targetFaction.getID().equals(this.factionID)) {
            return false;
        }

        // Can't grant on buffer claims
        if (this.isBuffer()) {
            return false;
        }

        if (!permittedPlayers.contains(playerId)) {
            permittedPlayers.add(playerId);
            ClaimEvents.MODIFY.invoker().onModify(this);
            return true;
        }
        return false;
    }
    /** revoke their access */
    public void revoke(UUID playerId) {
        if (permittedPlayers.remove(playerId)) {
            ClaimEvents.MODIFY.invoker().onModify(this);
        }
    }

    /**
     * Can this player build in this chunk?
     * <p>
     * - Admins bypass everything.<br>
     * - Must be in the same faction.<br>
     * - Buffer chunks: only MEMBER+ may build (no grants or access levels).<br>
     * - Paid chunks: explicit grants → owners → rank ≥ accessLevel.
     *
     * @param playerId the UUID of the player to check
     * @return true if they may build here
     */
// Claim.java
    public boolean canPlayerBuild(UUID playerId) {
        User user = User.get(playerId);
        if (user == null) return false;  // Add null check FIRST!

        // 0) global/claim-bypass → always allow
        if (user.bypass) return true;
        // 1) must be in the same faction as the claim owner
        Faction uf = user.getFaction();
        if (uf == null || !uf.getID().equals(this.factionID)) return false;

        // 2) buffer chunks → MEMBER and up (OWNER/LEADER/COMMANDER/MEMBER)
        if (this.isBuffer()) {
            return user.rank.ordinal() <= User.Rank.MEMBER.ordinal();
        }

        // 3) paid chunks:
        //    - explicit per-player grant
        if (permittedPlayers.contains(playerId)) return true;

        //    - owners always allowed
        if (user.rank == User.Rank.OWNER) return true;

        //    - otherwise respect the chunk's access level
        //      (lower ordinal means higher rank, so use <=)
        return user.rank.ordinal() <= this.accessLevel.ordinal();
    }


    /**
     * Real-claim constructor: creates a paid chunk claim and timestamps it.
     */
    public Claim(int x, int z, String level, UUID factionID) {
        this(x, z, level, factionID, false);
        this.created = System.currentTimeMillis();
    }

    /** internal ctor for buffer==true */
    private Claim(int x, int z, String level, UUID factionID, boolean buffer) {
        this.x           = x;
        this.z           = z;
        this.level       = level;
        this.factionID   = factionID;
        this.accessLevel = Rank.MEMBER;
        this.buffer      = buffer;
    }
    // Add to Claim class
    private static final Map<String, Claim> BUFFER_CACHE = new ConcurrentHashMap<>();

    /** no‑arg for Database deserialization (always real, buffer==false) */
    public Claim() {
        this.buffer = false;
    }

    /** unique key for real claims */
    public String getKey() {
        return String.format("%s-%d-%d", level, x, z);
    }

    /**
     * Looks up either a real claim or, if none exists, a one‑chunk buffer
     * around exactly one neighboring real claim.  Returns null otherwise.
     */
    public static Claim get(int cx, int cz, String level) {
        String key = String.format("%s-%d-%d", level, cx, cz);

        // 1) Check real claims first
        Claim real = STORE.get(key);
        if (real != null) return real;

        // 2) Check buffer cache
        Claim cached = BUFFER_CACHE.get(key);
        if (cached != null) return cached;

        // 3) Compute buffer zone (only if not cached)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                String nkey = String.format("%s-%d-%d", level, cx + dx, cz + dz);
                Claim neighbor = STORE.get(nkey);
                if (neighbor != null) {
                    Claim buffer = new Claim(cx, cz, level, neighbor.factionID, true);
                    BUFFER_CACHE.put(key, buffer);
                    return buffer;
                }
            }
        }

        return null;
    }

    /** remove any real claim whose faction no longer exists or invalid dim */
    public static void audit() {
        STORE.values().removeIf(c ->
                Faction.get(c.factionID) == null
                        || !WorldUtils.isValid(c.level)
        );
    }

    /** add a new real claim */
// Clear cache when claims change
    public static void add(Claim claim) {
        STORE.put(claim.getKey(), claim);
        BUFFER_CACHE.clear(); // Invalidate buffer cache
        ClaimEvents.ADD.invoker().onAdd(claim);
    }

    public void remove() {
        if (!buffer) {
            STORE.remove(getKey());
            BUFFER_CACHE.clear(); // Invalidate buffer cache
            ClaimEvents.REMOVE.invoker().onRemove(x, z, level, getFaction());
        }
    }

    /** true if this is just a one‑chunk buffer, not a paid claim */
    public boolean isBuffer() {
        return buffer;
    }

    public @org.jetbrains.annotations.Nullable Faction getFaction() {
        return Faction.get(factionID);   // may be null after disband
    }
    public int getChunkX() {
        return x;
    }
    public int getChunkZ() {
        return z;
    }
    public String getLevel() {
        return level;
    }

    /** persist only the real claims */
    public static void save() {
        Database.save(Claim.class, STORE.values().stream().toList());
    }
}
