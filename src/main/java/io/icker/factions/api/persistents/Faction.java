package io.icker.factions.api.persistents;

import io.icker.factions.FactionsMod;
import io.icker.factions.api.events.FactionEvents;
import io.icker.factions.database.Database;
import io.icker.factions.database.Field;
import io.icker.factions.database.Name;
import io.icker.factions.util.Message;
import io.icker.factions.util.WorldUtils;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.collection.DefaultedList;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Name("Faction")
public class Faction {
    private static final HashMap<UUID, Faction> STORE =
            Database.load(Faction.class, Faction::getID);

    @Field("ID")            private UUID    id;
    @Field("Name")          private String  name;
    @Field("Description")   private String  description;
    @Field("MOTD")          private String  motd;
    @Field("Color")         private String  color;
    @Field("Open")          private boolean open;
    @Field("Power")         private int     power;
    @Field("AdminPower")    private int     adminPower;
    @Field("Home")          private Home    home;
    @Field("Safe")          private SimpleInventory safe   = new SimpleInventory(54);
    @Field("Invites")       public  ArrayList<UUID> invites = new ArrayList<>();
    @Field("Relationships") private ArrayList<Relationship> relationships =
            new ArrayList<>();
    @Field("GuestPermissions")
    public ArrayList<Relationship.Permissions> guest_permissions =
            new ArrayList<>(FactionsMod.CONFIG.RELATIONSHIPS.DEFAULT_GUEST_PERMISSIONS);

    public Faction(
            String name, String description, String motd,
            Formatting color, boolean open, int power
    ) {
        this.id          = UUID.randomUUID();
        this.name        = name;
        this.description = description;
        this.motd        = motd;
        this.color       = color.getName();
        this.open        = open;
        this.power       = power;
    }

    public Faction() {}

    public String getKey() {
        return id.toString();
    }

    @Nullable
    public static Faction get(UUID id) {
        return STORE.get(id);
    }

    @Nullable
    public static Faction getByName(String name) {
        return STORE.values().stream()
                .filter(f -> f.name.equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    public static void add(Faction faction) {
        STORE.put(faction.id, faction);
    }

    public static Collection<Faction> all() {
        return STORE.values();
    }

    public static List<Faction> allBut(UUID id) {
        return STORE.values().stream()
                .filter(f -> !f.id.equals(id))
                .toList();
    }

    public UUID getID() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Formatting getColor() {
        return Formatting.byName(color);
    }

    public String getDescription() {
        return description;
    }

    public String getMOTD() {
        return motd;
    }

    /**
     * Returns the faction's total power (base + admin),
     * never less than 0.
     */
    public int getPower() {
        return Math.max(0, power + adminPower);
    }

    public SimpleInventory getSafe() {
        return safe;
    }

    public DefaultedList<ItemStack> clearSafe() {
        DefaultedList<ItemStack> stacks = this.safe.heldStacks;
        this.safe = new SimpleInventory(54);
        return stacks;
    }
    public boolean hasSufficientClaimPower() {
        int required = this.getClaims().size() * FactionsMod.CONFIG.POWER.CLAIM_WEIGHT;
        return this.getPower() >= required;
    }
    public boolean isOpen() {
        return open;
    }

    public void setName(String name) {
        this.name = name;
        FactionEvents.MODIFY.invoker().onModify(this);
    }

    public void setDescription(String description) {
        this.description = description;
        FactionEvents.MODIFY.invoker().onModify(this);
    }

    public void setMOTD(String motd) {
        this.motd = motd;
        FactionEvents.MODIFY.invoker().onModify(this);
    }

    public void setColor(Formatting color) {
        this.color = color.getName();
        FactionEvents.MODIFY.invoker().onModify(this);
    }

    public void setOpen(boolean open) {
        this.open = open;
        FactionEvents.MODIFY.invoker().onModify(this);
    }

    /**
     * Adjusts the faction's base power by 'adjustment',
     * clamping between 0 and maxPower.
     * @return absolute amount of change applied.
     */
    public int adjustPower(int adjustment) {
        int maxPower = calculateMaxPower();
        int newBase  = Math.min(Math.max(0, power + adjustment), maxPower);
        int oldBase  = this.power;
        if (newBase == oldBase) return 0;

        this.power = newBase;
        FactionEvents.POWER_CHANGE.invoker().onPowerChange(this, oldBase);

        // Warn faction when power is critically low
        if (getPower() > 0 && getPower() <= 5 && oldBase > 5) {
            new Message(Text.translatable("factions.warning.low_power", getPower()))
                    .format(Formatting.RED, Formatting.BOLD)
                    .send(this);
        }

        // disband immediately if total power drops to zero
        if (getPower() == 0) {
            new Message(Text.translatable("factions.warning.disbanded_no_power"))
                    .format(Formatting.DARK_RED, Formatting.BOLD)
                    .send(this);
            remove();
            save();
        }

        return Math.abs(newBase - oldBase);
    }

    /**
     * Directly set the faction's base power (clamped).
     */
    public void setPower(int newBase) {
        int oldBase   = this.power;
        int maxPower  = calculateMaxPower();
        this.power    = Math.min(Math.max(0, newBase), maxPower);
        FactionEvents.POWER_CHANGE.invoker().onPowerChange(this, oldBase);

        // disband immediately if total power drops to zero
        if (getPower() == 0) {
            remove();
            save();
        }
    }

    public int getAdminPower() {
        return adminPower;
    }

    public void addAdminPower(int amount) {
        adminPower += amount;
    }

    public List<User> getUsers() {
        return User.getByFaction(id);
    }

    public List<Claim> getClaims() {
        return Claim.getPaidByFaction(id);
    }

    public void removeAllClaims() {
        Claim.getPaidByFaction(id).forEach(Claim::remove);
        FactionEvents.REMOVE_ALL_CLAIMS.invoker().onRemoveAllClaims(this);
    }

    public void addClaim(int x, int z, String level) {
        Claim.add(new Claim(x, z, level, id));
    }

    public boolean isInvited(UUID playerID) {
        return invites.contains(playerID);
    }

    public Home getHome() {
        return home;
    }

    public void setHome(Home home) {
        this.home = home;
        FactionEvents.SET_HOME.invoker().onSetHome(this, home);
    }

    public Relationship getRelationship(UUID target) {
        return relationships.stream()
                .filter(rel -> rel.target.equals(target))
                .findFirst()
                .orElse(new Relationship(target, Relationship.Status.NEUTRAL));
    }

    public Relationship getReverse(Relationship rel) {
        Faction other = Faction.get(rel.target);
        return (other != null)
                ? other.getRelationship(id)
                : new Relationship(id, Relationship.Status.NEUTRAL);
    }

    public boolean isMutualAllies(UUID target) {
        Relationship rel = getRelationship(target);
        return rel.status == Relationship.Status.ALLY
                && getReverse(rel).status == Relationship.Status.ALLY;
    }

    public List<Relationship> getMutualAllies() {
        return relationships.stream()
                .filter(rel -> isMutualAllies(rel.target))
                .toList();
    }

    public List<Relationship> getEnemiesWith() {
        return relationships.stream()
                .filter(rel -> rel.status == Relationship.Status.ENEMY)
                .toList();
    }

    public List<Relationship> getEnemiesOf() {
        return relationships.stream()
                .filter(rel -> getReverse(rel).status == Relationship.Status.ENEMY)
                .toList();
    }

    public void removeRelationship(UUID target) {
        relationships = new ArrayList<>(
                relationships.stream()
                        .filter(rel -> !rel.target.equals(target))
                        .toList()
        );
    }

    public void setRelationship(Relationship relationship) {
        relationships.removeIf(r -> r.target.equals(relationship.target));
        if (relationship.status != Relationship.Status.NEUTRAL
                || !relationship.permissions.isEmpty()) {
            relationships.add(relationship);
        }
    }

    /** Fully disband this faction: eject users, clear relationships, claims, then delete. */
    public void remove() {
        getUsers().forEach(User::leaveFaction);
        relationships.forEach(rel -> {
            Faction other = Faction.get(rel.target);
            if (other != null) other.removeRelationship(id);
        });
        removeAllClaims();
        STORE.remove(id);
        FactionEvents.DISBAND.invoker().onDisband(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Faction)) return false;
        return id.equals(((Faction) o).id);
    }

    public static void audit() {
        STORE.values().removeIf(f -> {
            if (f.home != null && !WorldUtils.isValid(f.home.level)) {
                f.setHome(null);
            }
            f.relationships.removeIf(rel -> Faction.get(rel.target) == null);
            return f.getUsers().stream().noneMatch(u -> u.rank == User.Rank.OWNER);
        });
    }

    /** Persist all factions (including any disbands). */
    public static void save() {
        Database.save(Faction.class, STORE.values().stream().toList());
    }

    /**
     * Base+member+ally maximum.
     */
    public int calculateMaxPower() {
        return FactionsMod.CONFIG.POWER.BASE
                + getUsers().size() * FactionsMod.CONFIG.POWER.MEMBER
                + getMutualAllies().size() * FactionsMod.CONFIG.POWER.POWER_PER_ALLY;
    }
}
