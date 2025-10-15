package io.icker.factions;

import com.mojang.brigadier.CommandDispatcher;
import io.icker.factions.command.ClaimCommand;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.icker.factions.api.events.ClaimEvents;
import io.icker.factions.api.events.FactionEvents;
import io.icker.factions.api.events.MiscEvents;
import io.icker.factions.api.events.PlayerEvents;
import io.icker.factions.api.persistents.Claim;
import io.icker.factions.api.persistents.Faction;
import io.icker.factions.api.persistents.User;
import io.icker.factions.command.*;
import io.icker.factions.config.Config;
import io.icker.factions.core.*;
import io.icker.factions.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;

import java.util.*;


public class FactionsMod implements ModInitializer {

    public static Logger LOGGER = LoggerFactory.getLogger("Factions");
    public static final String MODID = "factions";

    public static Config CONFIG = Config.load();
    public static DynmapWrapper dynmap;
    public static BlueMapWrapper bluemap;
    public static SquareMapWrapper squaremap;

    private static int elytraCheckCounter = 0;
    private static final int ELYTRA_CHECK_INTERVAL = 10; // Check every 10 ticks (0.5 seconds)

    private static int sweepCounter = 0;
    @Override
    public void onInitialize() {

        AttackEntityCallback.EVENT.register((attackerRaw, worldRaw, hand, target, hitResult) -> {
            if (!(attackerRaw instanceof ServerPlayerEntity attacker)
                    || !(worldRaw instanceof ServerWorld world)
                    || hitResult == null
                    || hitResult.getType() != HitResult.Type.ENTITY) {
                return ActionResult.PASS;
            }

            BlockPos tpos = target.getBlockPos();
            ChunkPos cp   = new ChunkPos(tpos);
            String  dim   = world.getRegistryKey().getValue().toString();

            Claim claim = Claim.get(cp.x, cp.z, dim);
            if (claim == null) return ActionResult.PASS;
            Faction f = claim.getFaction();
            if (f == null || !f.hasSufficientClaimPower()) return ActionResult.PASS;

            // Only care about player vs player combat for friendly fire check
            if (target instanceof ServerPlayerEntity victim) {
                // Get both players' factions
                UUID attackerFaction = Optional.ofNullable(Command.getUser(attacker).getFaction())
                        .map(Faction::getID).orElse(null);
                UUID victimFaction = Optional.ofNullable(Command.getUser(victim).getFaction())
                        .map(Faction::getID).orElse(null);

                // If both are in the same faction (and not factionless), prevent friendly fire
                if (attackerFaction != null && attackerFaction.equals(victimFaction)) {
                    return ActionResult.FAIL;  // Block friendly fire
                }
            }

            // Allow all other attacks (different factions, factionless, or PvE)
            return PlayerEvents.ATTACK_ENTITY.invoker().onAttackEntity((ServerPlayerEntity) attackerRaw, target);
        });

// Persist whenever any faction is modified (name/desc/MOTD/color/open)
        FactionEvents.MODIFY.register(faction -> {
            Faction.save();
        });

        FactionEvents.POWER_CHANGE.register((faction, oldPower) -> {
            Faction.save();
        });
// Persist whenever *all* claims of a faction are removed (e.g. on disband or removeAllClaims)
        FactionEvents.REMOVE_ALL_CLAIMS.register(faction -> {
            Claim.save();
        });

        FactionEvents.DISBAND.register(faction -> {
            // The Faction.remove() method already handles:
            // - Ejecting all members via getUsers().forEach(User::leaveFaction)
            // - Removing all claims via removeAllClaims()
            // - Deleting the faction itself
            // So we only need to persist the changes here
            User.save();
            Claim.save();
            Faction.save();
        });
// Initialize legacy users (lastOnline ≤ 0) to now, but preserve existing timestamps
        long now = System.currentTimeMillis();
        for (User u : User.all()) {
            if (u.getLastOnline() <= 0) {
                u.setLastOnline(now);
            }
        }
        User.save();
        LOGGER.info("Initialized Factions Mod");

        // Register misc event handlers (join/disconnect + world-save)
        MiscEvents.register();
        MiscEvents.ON_SAVE.register(server -> {
            User.save();
            Faction.save();
            Claim.save();            // ← persist all per-player grants too
        });

        WorldUtils.register();

        dynmap = FabricLoader.getInstance().isModLoaded("dynmap") ? new DynmapWrapper() : null;
        bluemap = FabricLoader.getInstance().isModLoaded("bluemap") ? new BlueMapWrapper() : null;
        squaremap =
                FabricLoader.getInstance().isModLoaded("squaremap") ? new SquareMapWrapper() : null;

        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            PlaceholdersWrapper.init();
        }

        ChatManager.register();
        FactionsManager.register();
        InteractionManager.register();
        ServerManager.register();
        SoundManager.register();
        WorldManager.register();
        DecayManager.register();
        FactionDisbandManager.register();
        ClaimEvents.ADD.register(c -> Claim.save());
        ClaimEvents.MODIFY.register(c -> Claim.save());
        ClaimEvents.REMOVE.register((x,z,lvl,fac) -> Claim.save());

        PlayerEvents.PLACE_BLOCK.register(ctx -> {
            if (!(ctx.getPlayer() instanceof ServerPlayerEntity player)) return ActionResult.PASS;
            if (!(ctx.getWorld() instanceof ServerWorld world)) return ActionResult.PASS;

            ChunkPos cp = new ChunkPos(ctx.getBlockPos());
            String dim = world.getRegistryKey().getValue().toString();
            Claim claim = Claim.get(cp.x, cp.z, dim);

            // Unclaimed → allow
            if (claim == null) return ActionResult.PASS;

            // Disbanded / under-powered owners → allow
            Faction owner = claim.getFaction();
            if (owner == null || !owner.hasSufficientClaimPower()) return ActionResult.PASS;

            // Check if placing restricted blocks
            if (ctx.getStack().getItem() instanceof BlockItem bi) {
                String id = Registries.BLOCK.getId(bi.getBlock()).toString();
                boolean isRestrictedBlock = id.equals("polyfactory:placer")
                        || id.equals("polyfactory:miner")
                        || id.equals("minecraft:piston")
                        || id.equals("minecraft:sticky_piston");

                if (isRestrictedBlock) {
                    // Block in buffer zones (existing logic)
                    if (claim.isBuffer()) {
                        return ActionResult.FAIL;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue; // Skip current chunk

                            Claim adjacentClaim = Claim.get(cp.x + dx, cp.z + dz, dim);

                            // If adjacent chunk is another faction's PAID claim, block placement
                            if (adjacentClaim != null
                                    && !adjacentClaim.isBuffer()
                                    && adjacentClaim.getFaction() != null
                                    && !Objects.equals(adjacentClaim.getFaction(), owner)) {
                                return ActionResult.FAIL;
                            }
                        }
                    }
                }
            }

            // Everything else is decided by Claim.canPlayerBuild
            return claim.canPlayerBuild(player.getUuid()) ? ActionResult.PASS : ActionResult.FAIL;
        });

// Breaking protection
        PlayerEvents.BREAK_BLOCK.register((playerRaw, view, pos, state) -> {
            if (!(playerRaw instanceof ServerPlayerEntity player)) return ActionResult.PASS;
            if (!(view instanceof ServerWorld world))              return ActionResult.PASS;

            ChunkPos cp = new ChunkPos(pos);
            String dim  = world.getRegistryKey().getValue().toString();
            Claim claim = Claim.get(cp.x, cp.z, dim);

            // Unclaimed → allow
            if (claim == null) return ActionResult.PASS;

            // Disbanded / under-powered owners → allow
            Faction owner = claim.getFaction();
            if (owner == null || !owner.hasSufficientClaimPower()) return ActionResult.PASS;

            // Buffers & paid claims are handled uniformly by canPlayerBuild
            return claim.canPlayerBuild(player.getUuid()) ? ActionResult.PASS : ActionResult.FAIL;
        });
        //  ─────────────────────────────────────────────────────
        //  Elytra-dismount in enemy claims
        //  ─────────────────────────────────────────────────────
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++elytraCheckCounter < ELYTRA_CHECK_INTERVAL) return;
            elytraCheckCounter = 0;

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                // Skip non-gliding players immediately
                if (!player.isGliding()) continue;

                ChunkPos cp = new ChunkPos(player.getBlockPos());
                String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
                Claim claim = Claim.get(cp.x, cp.z, dim);

                // Skip if no claim or if it's a buffer zone
                if (claim == null || claim.isBuffer()) continue;

                Faction cf = claim.getFaction();

                // Skip if no faction or faction lacks power
                if (cf == null || !cf.hasSufficientClaimPower()) continue;

                // Get player's faction safely
                User playerUser = Command.getUser(player);
                Faction playerFaction = playerUser != null ? playerUser.getFaction() : null;
                UUID playerFactionId = playerFaction != null ? playerFaction.getID() : null;

                // Dismount if player is NOT in the same faction as the claim
                if (!cf.getID().equals(playerFactionId)) {
                    player.stopGliding();
                    player.sendMessage(
                            Text.translatable("factions.elytra.dismount.enemy_claim")
                                    .styled(s -> s.withColor(Formatting.RED)),
                            false
                    );
                }
            }
        });     // ← closes the register(...) lambda
        PlayerEvents.EXPLODE_BLOCK.register((expl, world, pos, state) -> {
            if (!(world instanceof ServerWorld sw)) return ActionResult.PASS;
            String dim = sw.getRegistryKey().getValue().toString();
            // allocate once and reuse:
            ChunkPos c = new ChunkPos(pos);
            return Claim.get(c.x, c.z, dim) != null
                    ? ActionResult.FAIL
                    : ActionResult.PASS;
        });

        PlayerEvents.EXPLODE_DAMAGE.register((expl, entity) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return ActionResult.PASS;
            ChunkPos c = new ChunkPos(p.getBlockPos());
            String dim = p.getEntityWorld().getRegistryKey().getValue().toString();
            return (Claim.get(c.x, c.z, dim) != null)
                    ? ActionResult.FAIL
                    : ActionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register(FactionsMod::registerCommands);
    }
    private static void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        LiteralCommandNode<ServerCommandSource> factions = CommandManager.literal("factions").build();
        LiteralCommandNode<ServerCommandSource> alias    = CommandManager.literal("f").build();

        dispatcher.getRoot().addChild(factions);
        dispatcher.getRoot().addChild(alias);

        Command[] commands = new Command[] {
                new AdminCommand(),
                new SettingsCommand(),
                new ClaimCommand(),
                new CreateCommand(),
                new DeclareCommand(),
                new DisbandCommand(),
                new HomeCommand(),
                new InfoCommand(),
                new InviteCommand(),
                new JoinCommand(),
                new KickCommand(),
                new LeaveCommand(),
                new ListCommand(),
                new MapCommand(),
                new MemberCommand(),
                new ModifyCommand(),
                new RankCommand(),
                new SafeCommand(),
                new PermissionCommand()
        };

        for (Command command : commands) {
            factions.addChild(command.getNode());
            alias.addChild(command.getNode());
        }
    }
}
