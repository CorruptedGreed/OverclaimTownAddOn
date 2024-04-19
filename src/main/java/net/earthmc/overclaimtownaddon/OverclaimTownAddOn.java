package net.earthmc.overclaimtownaddon;

import com.palmergames.bukkit.towny.TownyCommandAddonAPI;
import net.earthmc.overclaimtownaddon.command.NewOverclaimTownCommand;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class OverclaimTownAddOn extends JavaPlugin {

    @Override
    public void onEnable() {
        TownyCommandAddonAPI.addSubCommand(TownyCommandAddonAPI.CommandType.TOWN,"newoverclaim",new NewOverclaimTownCommand());
    }

    @Override
    public void onDisable() {
    }

}
