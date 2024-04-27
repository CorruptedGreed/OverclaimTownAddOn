package net.earthmc.overclaimtownaddon;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyCommandAddonAPI;
import net.earthmc.overclaimtownaddon.command.NewOverclaimTownCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class OverclaimTownAddOn extends JavaPlugin {
    private static Towny plugin;

    @Override
    public void onEnable() {
        initCommands();
    }

    private void initCommands() {
        TownyCommandAddonAPI.addSubCommand(TownyCommandAddonAPI.CommandType.TOWN,"newoverclaim",new NewOverclaimTownCommand());
    }

    @Override
    public void onDisable() {
    }

}
