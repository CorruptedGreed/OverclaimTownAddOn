package net.earthmc.overclaimtownaddon.command;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.confirmations.Confirmation;
import com.palmergames.bukkit.towny.confirmations.ConfirmationTransaction;
import com.palmergames.bukkit.towny.event.PreNewTownEvent;
import com.palmergames.bukkit.towny.exceptions.CancelledEventException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.*;
import com.palmergames.bukkit.towny.tasks.CooldownTimerTask;
import com.palmergames.bukkit.util.BukkitTools;
import com.palmergames.bukkit.util.NameValidation;
import com.palmergames.util.StringMgmt;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Level;

import static com.palmergames.bukkit.towny.command.BaseCommand.prettyMoney;
import static com.palmergames.bukkit.towny.command.TownCommand.newTown;

public class NewOverclaimTownCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this command.");
            return false;
        }

        Player player = (Player) sender;
        Resident resident = TownyAPI.getInstance().getResident(player);

        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /t newoverclaim <townName>");
            return false;
        }

        String name = args[0];

        if (TownySettings.hasTownLimit() && TownyUniverse.getInstance().getTowns().size() >= TownySettings.getTownLimit()) {
            sender.sendMessage(ChatColor.RED + Translatable.of("msg_err_universe_limit").toString());
            return false;
        }

        // Check if the player has a cooldown since deleting their town.
        if (!resident.isAdmin() && CooldownTimerTask.hasCooldown(player.getName(), CooldownTimerTask.CooldownType.TOWN_DELETE)) {
            sender.sendMessage(ChatColor.RED + Translatable.of("msg_err_cannot_create_new_town_x_seconds_remaining",
                    CooldownTimerTask.getCooldownRemaining(player.getName(), CooldownTimerTask.CooldownType.TOWN_DELETE)).toString());
            return false;
        }

        if (TownySettings.getTownAutomaticCapitalisationEnabled())
            name = StringMgmt.capitalizeStrings(name);

        try {
            name = NameValidation.checkAndFilterTownNameOrThrow(name);
        } catch (TownyException e) {
            sender.sendMessage(ChatColor.RED + e.getMessage());
            return false;
        }

        if (TownyUniverse.getInstance().hasTown(name)) {
            sender.sendMessage(ChatColor.RED + Translatable.of("msg_err_name_validation_name_already_in_use", name).toString());
            return false;
        }

        if (resident.hasTown()) {
            sender.sendMessage(ChatColor.RED + Translatable.of("msg_err_already_res", resident.getName()).toString());
            return false;
        }

        final TownyWorld world = TownyAPI.getInstance().getTownyWorld(player.getWorld());

        if (world == null || !world.isUsingTowny()) {
            sender.sendMessage(ChatColor.RED + Translatable.of("msg_set_use_towny_off").toString());
            return false;
        }

        if (!world.isClaimable()) {
            sender.sendMessage(ChatColor.RED + Translatable.of("msg_not_claimable").toString());
            return false;
        }

        Location spawnLocation = player.getLocation();
        Coord key = Coord.parseCoord(player);
        WorldCoord wc = WorldCoord.parseWorldCoord(player);

        if (TownyAPI.getInstance().isWilderness(spawnLocation)) {
            sender.sendMessage(ChatColor.RED + Translatable.of("msg_already_claimed_1", key).toString());
            return false;
        }

        if (newTownWouldBeFoundedOnBorder(WorldCoord.parseWorldCoord(spawnLocation))) {
            sender.sendMessage(ChatColor.RED + Translatable.of("msg_err_you_cannot_over_claim_would_cut_into_two").toString());
            return false;
        }

        // If all checks passed, continue with the command execution

        // Fire a cancellable event that allows plugins to alter the price of a town.
        PreNewTownEvent pnte = new PreNewTownEvent(player, name, spawnLocation, TownySettings.getNewTownPrice());
        try {
            BukkitTools.ifCancelledThenThrow(pnte);
        } catch (CancelledEventException e) {
            throw new RuntimeException(e);
        }

        // Test if the resident can afford the town.
        double cost = pnte.getPrice();
        if(!resident.getAccount().

                canPayFromHoldings(cost))
            try {
                throw new

                        TownyException(Translatable.of("msg_no_funds_new_town2", (resident.getName().

                        equals(player.getName())?Translatable.of("msg_you"):resident.getName()),cost));
            } catch (TownyException e) {
                throw new RuntimeException(e);
            }

        // Send a confirmation before taking their money and throwing the PreNewTownEvent.
        final String finalName = name; // TODO: The confirmation runs twice, only actually making the town on the 2nd one
        Confirmation.runOnAccept(()-> { // TODO: On confirmation accepted, remove existing town block before creating new town
                    try {
                        // Make town.
                        newTown(player, finalName, resident, false); // TODO: Make sure this works with/without world
                        TownyMessaging.sendGlobalMessage(Translatable.of("msg_new_town", player.getName(), StringMgmt.remUnderscore(finalName)));
                    } catch (TownyException e) {
                        TownyMessaging.sendErrorMsg(player, e.getMessage(player));
                    }
                })
                .setTitle(Translatable.of("msg_confirm_purchase", prettyMoney(cost)))
                .setCost(new ConfirmationTransaction(() ->cost,resident,"New Town Cost",
                        Translatable.of("msg_no_funds_new_town2",(resident.getName().equals(player.getName())?Translatable.of("msg_you"):resident.getName()), prettyMoney(cost))))
                .sendTo(player);
        return true;
    }

    private static boolean newTownWouldBeFoundedOnBorder(WorldCoord worldCoord) { // TODO: Remove print debugs
        Town currentTown = worldCoord.getTownOrNull();
        List<WorldCoord> surroundingClaims = worldCoord.getCardinallyAdjacentWorldCoords(true);

        // Log the list of surrounding claims
        System.out.println("Surrounding Claims: " + surroundingClaims);

        // Check if all four cardinal chunks belong to the same town
        long sameTownChunks = surroundingClaims.stream().filter(wc -> {
            // Log each individual chunk being checked by the stream
            System.out.println("Checking chunk: " + wc);
            return wc.hasTown(currentTown);
        }).count();

        // Log the final conclusion on true or false
        if (sameTownChunks == 8) {
            System.out.println("Final Conclusion: true");
            return true;
        } else {
            System.out.println("Final Conclusion: false");
            return false;
        }
    }
}
