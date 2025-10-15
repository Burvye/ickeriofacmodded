package io.icker.factions.api.persistents;

import io.icker.factions.FactionsMod;
import io.icker.factions.api.events.FactionEvents;
import io.icker.factions.config.Config;
import io.icker.factions.database.Database;
import io.icker.factions.database.Field;
import io.icker.factions.database.Name;
import io.icker.factions.util.WorldUtils;

import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a player’s persistent state in Factions.
 */
@Name("User")
public class User {
    // Thread-safe map of all loaded users
    private static final ConcurrentHashMap<UUID, User> STORE =
            new ConcurrentHashMap<>(Database.load(User.class, User::getID));

    // Field definitions for Database serialization
    @Field("ID")            private UUID     id;
    @Field("FactionID")     private UUID     factionID;
    @Field("Rank")          public  Rank     rank;
    @Field("Radar")         public  boolean  radar       = false;
    @Field("Chat")          public  ChatMode chat        = ChatMode.GLOBAL;
    @Field("Sounds")        public  SoundMode sounds    = SoundMode.ALL;
    @Field("HomeCooldown")  public  long     homeCooldown= -1;
    @Field("Autoclaim")     public  boolean  autoclaim   = false;
    @Field("Bypass")        public  boolean  bypass      = false;

    // --- Decay & death‐penalty tracking fields ---
    // total milliseconds since epoch of last login
    @Field("LastOnline")    private long     lastOnline;
    // how much power this user has removed so far (decay + death)
    @Field("PowerDrained")  private int      powerDrained;
    // --------------------------------

    private User spoof;

    /** Constructor for new users. */
    public User(UUID id) {
        this.id           = id;
        this.lastOnline   = System.currentTimeMillis();
        this.powerDrained = 0;
    }

    /** Empty constructor for Database loader. */
    public User() {}

    // ----------------------------------------------------
    //  Static helpers
    // ----------------------------------------------------

    /** Returns (and if needed creates) the User for this UUID. */
    @NotNull
    public static User get(UUID id) {
        return STORE.computeIfAbsent(id, User::new);
    }

    /** All loaded users. */
    public static Collection<User> all() {
        return STORE.values();
    }

    /**
     * Retrieve all users in the given faction.
     */
    public static List<User> getByFaction(UUID factionID) {
        return STORE.values().stream()
                .filter(u -> u.isInFaction() && u.factionID.equals(factionID))
                .toList();
    }

    /**
     * Remove references to non‐existent factions and clear rank if no faction.
     */
    public static void audit() {
        STORE.values().forEach(u -> {
            if (u.factionID != null && Faction.get(u.factionID) == null) {
                u.factionID = null;
            }
            if (u.factionID == null) {
                u.rank = null;
            }
        });
    }

    /**
     * Initialize legacy users (lastOnline ≤ 0) to now.
     * Call once on server-start before decay.
     */
    public static void migrateLastOnline() {
        long now = System.currentTimeMillis();
        STORE.values().forEach(u -> {
            if (u.lastOnline <= 0) {
                u.lastOnline = now;
            }
        });
    }

    /** Persist all users to disk. */
    public static void saveAll() {
        synchronized (STORE) {
            Database.save(User.class, List.copyOf(STORE.values()));
        }
    }

    /** Alias for saveAll(). */
    public static void save() {
        saveAll();
    }

    // ----------------------------------------------------
    //  Instance methods & business logic
    // ----------------------------------------------------

    public UUID getID() {
        return id;
    }

    public String getKey() {
        return id.toString();
    }

    public boolean isInFaction() {
        return factionID != null;
    }

    @Nullable
    public Faction getFaction() {
        return Faction.get(factionID);  // may be null if the faction was disbanded
    }

    /**
     * Joins this user to a new faction.
     * Also resets their personal drain counter.
     */
    public void joinFaction(UUID factionID, Rank rank) {
        this.factionID    = factionID;
        this.rank         = rank;
        this.powerDrained = 0;
        FactionEvents.MEMBER_JOIN.invoker()
                .onMemberJoin(Faction.get(factionID), this);
    }

    public void leaveFaction() {
        if (this.factionID == null) return;
        // 1) grab their current faction ID
        UUID oldFactionId = this.factionID;
        // 2) clear out their faction membership
        this.factionID = null;

        // 3) revoke any per-chunk grants this user had on that faction’s claims
        Claim.getPaidByFaction(oldFactionId).forEach(claim -> {
            claim.revoke(this.getID());
        });
        Claim.save();

        // 4) fire the leave event on the old faction
        FactionEvents.MEMBER_LEAVE.invoker()
                .onMemberLeave(Faction.get(oldFactionId), this);
    }

    @Nullable
    public String getLanguage() {
        ServerPlayerEntity player =
                WorldUtils.server.getPlayerManager().getPlayer(this.id);
        return (player == null) ? null
                : player.getClientOptions().language();
    }

    // ----------------------------------------------------
    //  Getters / setters for decay & death-tracking
    // ----------------------------------------------------

    /** Last time this player was seen online (ms since epoch). */
    public long getLastOnline() {
        return lastOnline;
    }

    /**
     * Update the last-online timestamp.
     * @throws IllegalArgumentException if timestamp is negative or in the future.
     */
    public void setLastOnline(long ts) {
        long now = System.currentTimeMillis();
        if (ts < 0 || ts > now) {
            throw new IllegalArgumentException("Invalid lastOnline timestamp: " + ts);
        }
        this.lastOnline = ts;
    }

    /**
     * How much power this user has removed so far (decay + death).
     * Guaranteed to never exceed the configured per-member cap.
     */
    public int getPowerDrained() {
        return powerDrained;
    }

    /**
     * Record additional power removed by this user.
     * Automatically clamps to [0, powerPerMember].
     * @param drained new total drained (non-negative)
     * @throws IllegalArgumentException if drained is negative or decreases.
     */
    public void setPowerDrained(int drained) {
        if (drained < 0) {
            throw new IllegalArgumentException("powerDrained cannot be negative: " + drained);
        }
        if (drained < this.powerDrained) {
            throw new IllegalArgumentException(
                    "powerDrained cannot decrease: was=" + this.powerDrained + " now=" + drained);
        }
        // Cache config value instead of reloading entire config
        int max = FactionsMod.CONFIG.DECAY.powerPerMember;
        this.powerDrained = Math.min(drained, max);
    }

    // ----------------------------------------------------
    //  Rank / chat / sound helpers
    // ----------------------------------------------------

    public String getRankName() {
        return (rank != null) ? rank.name().toLowerCase() : "";
    }

    public String getChatName() {
        return chat.name().toLowerCase();
    }

    public String getSoundName() {
        return sounds.name().toLowerCase();
    }

    // ----------------------------------------------------
    //  Spoofing helper
    // ----------------------------------------------------

    @Nullable
    public User getSpoof() {
        return spoof;
    }

    public void setSpoof(@Nullable User spoof) {
        this.spoof = spoof;
    }

    // ----------------------------------------------------
    //  Enums
    // ----------------------------------------------------

    public enum ChatMode {
        FOCUS, FACTION, GLOBAL
    }

    public enum Rank {
        OWNER, LEADER, COMMANDER, MEMBER, GUEST
    }

    public enum SoundMode {
        NONE, WARNINGS, FACTION, ALL
    }
}
