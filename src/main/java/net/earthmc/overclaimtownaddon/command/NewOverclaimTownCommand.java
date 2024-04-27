package net.earthmc.overclaimtownaddon.command;

import com.palmergames.bukkit.towny.*;
import com.palmergames.bukkit.towny.command.BaseCommand;
import com.palmergames.bukkit.towny.confirmations.Confirmation;
import com.palmergames.bukkit.towny.confirmations.ConfirmationTransaction;
import com.palmergames.bukkit.towny.event.NewTownEvent;
import com.palmergames.bukkit.towny.event.PreNewTownEvent;
import com.palmergames.bukkit.towny.event.TownPreClaimEvent;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.*;
import com.palmergames.bukkit.towny.permissions.PermissionNodes;
import com.palmergames.bukkit.towny.regen.PlotBlockData;
import com.palmergames.bukkit.towny.regen.TownyRegenAPI;
import com.palmergames.bukkit.towny.tasks.CooldownTimerTask;
import com.palmergames.bukkit.towny.utils.MapUtil;
import com.palmergames.bukkit.towny.utils.NameUtil;
import com.palmergames.bukkit.util.BukkitTools;
import com.palmergames.bukkit.util.NameValidation;
import com.palmergames.util.StringMgmt;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

import static com.palmergames.bukkit.towny.utils.PlayerCacheUtil.getCache;

public class NewOverclaimTownCommand extends BaseCommand implements CommandExecutor {

    private static Towny plugin;

    @VisibleForTesting
    public static final List<String> townTabCompletes = List.of(
            "newoverclaim"
    );

    public static void setPlugin(Towny plugin) {
        NewOverclaimTownCommand.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (sender instanceof Player player) {
            try {
                parseTownNewOverclaimCommand(player, args);
            } catch (TownyException te) {
                TownyMessaging.sendErrorMsg(player, te.getMessage(player));
            } catch (Exception e) {
                TownyMessaging.sendErrorMsg(player, e.getMessage());
            }
        }
        return true;
    }

    private void parseTownNewOverclaimCommand(final Player player, String[] split) throws TownyException {
        System.out.println(Arrays.toString(split));
        checkPermOrThrow(player, PermissionNodes.TOWNY_COMMAND_TOWN_NEW.getNode());
        if (split.length < 1) {
            throw new TownyException(Translatable.of("msg_specify_name"));
        } else {
            String townName = split[0]; // Accessing the first (and only) word as the town name
            boolean noCharge = TownySettings.getNewTownPrice() == 0.0 || !TownyEconomyHandler.isActive();
            newOverclaimTown(player, townName, getResidentOrThrow(player), noCharge);
        }
    }

    /**
     * Create a new town. Command: /town new [town]
     *
     * @param player - Player.
     * @param name - name of town
     * @param resident - The resident in charge of the town.
     * @param noCharge - charging for creation - /ta town new NAME MAYOR has no charge.
     * @throws TownyException when a new town isn't allowed.
     */
    public static void newOverclaimTown(Player player, String name, Resident resident, boolean noCharge) throws TownyException {
        newOverclaimTown(player, name, resident, noCharge, false);
    }

    /**
     * Create a new town. Command: /town new [townname] or /ta town new [townname]
     *
     * @param player Player using the command.
     * @param name Name of town
     * @param resident The resident in charge of the town.
     * @param noCharge Charging for creation - /ta town new NAME MAYOR has no charge.
     * @param adminCreated true when an admin has used /ta town new [NAME].
     * @throws TownyException when a new town isn't allowed.
     */
    public static void newOverclaimTown(Player player, String name, Resident resident, boolean noCharge, boolean adminCreated) throws TownyException {
        if (!TownySettings.isOverClaimingAllowingStolenLand())
            throw new TownyException(Translatable.of("msg_err_taking_over_claims_is_not_enabled"));

        if (TownySettings.hasTownLimit() && TownyUniverse.getInstance().getTowns().size() >= TownySettings.getTownLimit())
            throw new TownyException(Translatable.of("msg_err_universe_limit"));

        // Check if the player has a cooldown since deleting their town.
        if (!resident.isAdmin() && CooldownTimerTask.hasCooldown(player.getName(), CooldownTimerTask.CooldownType.TOWN_DELETE))
            throw new TownyException(Translatable.of("msg_err_cannot_create_new_town_x_seconds_remaining",
                    CooldownTimerTask.getCooldownRemaining(player.getName(), CooldownTimerTask.CooldownType.TOWN_DELETE)));

        if (TownySettings.getTownAutomaticCapitalisationEnabled())
            name = StringMgmt.capitalizeStrings(name);

        name = NameValidation.checkAndFilterTownNameOrThrow(name);
        if (TownyUniverse.getInstance().hasTown(name))
            throw new TownyException(Translatable.of("msg_err_name_validation_name_already_in_use", name));

        if (resident.hasTown())
            throw new TownyException(Translatable.of("msg_err_already_res", resident.getName()));

        final TownyWorld world = TownyAPI.getInstance().getTownyWorld(player.getWorld());

        if (world == null || !world.isUsingTowny())
            throw new TownyException(Translatable.of("msg_set_use_towny_off"));

        Location spawnLocation = player.getLocation();
        Coord key = Coord.parseCoord(player);

        if (TownyAPI.getInstance().isWilderness(spawnLocation))
            throw new TownyException("Use the /t new command to found a new town in wilderness!");

        WorldCoord wc = WorldCoord.parseWorldCoord(player);

        // Make sure this is in a town which is overclaimed, allowing for stealing land.
        if (!wc.canBeStolen())
            throw new TownyException(Translatable.of("msg_err_this_townblock_cannot_be_taken_over"));

        if (!newTownWouldBeFoundedOnBorder(WorldCoord.parseWorldCoord(spawnLocation)))
            throw new TownyException("A town cannot be founded here due to not being on the border of the existing overclaimable town!");

        // If the town doesn't cost money to create, just make the Town.
        if (noCharge || !TownyEconomyHandler.isActive()) {
            BukkitTools.ifCancelledThenThrow(new PreNewTownEvent(player, name, spawnLocation, 0));
            newTown(world, name, resident, key, spawnLocation, player);
            TownyMessaging.sendGlobalMessage(Translatable.of("msg_new_town", player.getName(), StringMgmt.remUnderscore(name)));
            return;
        }

        // Fire a cancellable event that allows plugins to alter the price of a town.
        PreNewTownEvent pnte = new PreNewTownEvent(player, name, spawnLocation, TownySettings.getNewTownPrice());
        BukkitTools.ifCancelledThenThrow(pnte);

        // Test if the resident can afford the town.
        double cost = pnte.getPrice();
        if (!resident.getAccount().canPayFromHoldings(cost))
            throw new TownyException(Translatable.of("msg_no_funds_new_town2", (resident.getName().equals(player.getName()) ? Translatable.of("msg_you") : resident.getName()), cost));

        // Send a confirmation before taking their money and throwing the PreNewTownEvent.
        final String finalName = name;
        Confirmation.runOnAccept(() -> {
                    try {
                        // Make town.
                        newTown(world, finalName, resident, key, spawnLocation, player);

                        // Get townblock and town.
                        TownBlock townblock = Objects.requireNonNull(TownyAPI.getInstance().getTown(player.getLocation())).getTownBlock(wc);
                        Town town = TownyAPI.getInstance().getTown(player);

                        // This should never happen.
                        if (town == null)
                            throw new TownyException(String.format("Error fetching new town from name '%s'", finalName));

                        // Assign townblock to new town and set it as homeblock.
                        townblock.setTown(town);
                        town.setHomeBlock(townblock);

                        resident.save();
                        townblock.save();
                        town.save();
                        world.save();

                        TownyMessaging.sendGlobalMessage(Translatable.of("msg_new_town", player.getName(), StringMgmt.remUnderscore(finalName)));
                    } catch (TownyException e) {
                        TownyMessaging.sendErrorMsg(player, e.getMessage(player));
                        plugin.getLogger().log(Level.WARNING, "An exception occurred while creating a new town", e);
                    }
                })
                .setTitle(Translatable.of("msg_confirm_purchase", prettyMoney(cost)))
                .setCost(new ConfirmationTransaction(() -> cost, resident, "New Town Cost",
                        Translatable.of("msg_no_funds_new_town2", (resident.getName().equals(player.getName()) ? Translatable.of("msg_you") : resident.getName()), prettyMoney(cost))))
                .sendTo(player);
    }

    private static boolean newTownWouldBeFoundedOnBorder(WorldCoord worldCoord) {
        Town currentTown = worldCoord.getTownOrNull();
        List<WorldCoord> surroundingClaims = worldCoord.getCardinallyAdjacentWorldCoords(true);

        // Check if all four cardinal chunks belong to the same town
        long sameTownChunks = surroundingClaims.stream().filter(wc -> {
            // Log each individual chunk being checked by the stream
            return wc.hasTown(currentTown);
        }).count();

        // Log the final conclusion on true or false
        if (sameTownChunks == 8) {
            return false;
        } else {
            return true;
        }
    }

    public static Town newTown(TownyWorld world, String name, Resident resident, Coord key, Location spawn, Player player) throws TownyException {

        TownyUniverse.getInstance().newTown(name);
        Town town = TownyUniverse.getInstance().getTown(name);

        // This should never happen
        if (town == null)
            throw new TownyException(String.format("Error fetching new town from name '%s'", name));

        TownBlock townBlock = new TownBlock(key.getX(), key.getZ(), world);
        townBlock.setTown(town);
        TownPreClaimEvent preClaimEvent = new TownPreClaimEvent(town, townBlock, player, false, true, false);
        preClaimEvent.setCancelMessage(Translation.of("msg_claim_error", 1, 1));

        if (BukkitTools.isEventCancelled(preClaimEvent)) {
            TownyUniverse.getInstance().removeTownBlock(townBlock);
            TownyUniverse.getInstance().unregisterTown(town);
            town = null;
            townBlock = null;
            throw new TownyException(preClaimEvent.getCancelMessage());
        }

        town.setRegistered(System.currentTimeMillis());
        town.setMapColorHexCode(MapUtil.generateRandomTownColourAsHexCode());
        resident.setTown(town);
        town.setMayor(resident, false);
        town.setFounder(resident.getName());

        // Set the plot permissions to mirror the towns.
        townBlock.setType(townBlock.getType());
        town.setSpawn(spawn);

        // Disable upkeep if the mayor is an npc
        if (resident.isNPC())
            town.setHasUpkeep(false);

        if (world.isUsingPlotManagementRevert()) {
            PlotBlockData plotChunk = TownyRegenAPI.getPlotChunk(townBlock);
            if (plotChunk != null && TownyRegenAPI.getRegenQueueList().contains(townBlock.getWorldCoord())) {
                // This plot is in the regeneration queue.
                TownyRegenAPI.removeFromActiveRegeneration(plotChunk); // just claimed so stop regeneration.
                TownyRegenAPI.removeFromRegenQueueList(townBlock.getWorldCoord()); // Remove the WorldCoord from the regenqueue.
                TownyRegenAPI.addPlotChunkSnapshot(plotChunk); // Save a snapshot.
            } else {
                TownyRegenAPI.handleNewSnapshot(townBlock);
            }
        }

        if (TownyEconomyHandler.isActive()) {
            TownyMessaging.sendDebugMsg("Creating new Town account: " + TownySettings.getTownAccountPrefix() + name);
            try {
                town.getAccount().setBalance(0, "Setting 0 balance for Town");
            } catch (NullPointerException e1) {
                throw new TownyException("The server economy plugin " + TownyEconomyHandler.getVersion() + " could not return the Town account!");
            }
        }

        if (TownySettings.isTownTagSetAutomatically())
            town.setTag(NameUtil.getTagFromName(name));

        resident.save();
        townBlock.save();
        town.save();
        world.save();

        // Reset cache permissions for anyone in this TownBlock
        for (Player players : BukkitTools.getOnlinePlayers())
            if (players != null)
                if (WorldCoord.parseWorldCoord(players).equals(townBlock.getWorldCoord()))
                    getCache(players).resetAndUpdate(townBlock.getWorldCoord()); // Automatically resets permissions.

        BukkitTools.fireEvent(new NewTownEvent(town));

        return town;
    }
}

