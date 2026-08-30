package com.ninja6.antispeedrun;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * AntiSpeedrun - Unified anti-speedrun, dimension progression gates,
 * anti-cheese, and multi-dragon boss combat scaling for PaperMC & Folia.
 */
public final class AntiSpeedrunPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("AntiSpeedrun enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("AntiSpeedrun disabled.");
    }
}
