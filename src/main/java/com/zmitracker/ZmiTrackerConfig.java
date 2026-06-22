package com.zmitracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("zmitracker")
public interface ZmiTrackerConfig extends Config
{
    @ConfigItem(keyName = "showCurrentLapBreakdown", name = "Show current lap rune breakdown",
        description = "Show individual runes for the lap in progress", position = 0)
    default boolean showCurrentLapBreakdown() { return false; }

    @ConfigItem(keyName = "showLastLapBreakdown", name = "Show last lap rune breakdown",
        description = "Show individual runes for the last completed lap", position = 1)
    default boolean showLastLapBreakdown() { return true; }

    @ConfigItem(keyName = "showSessionBreakdown", name = "Show session rune breakdown",
        description = "Show individual runes for the entire session", position = 2)
    default boolean showSessionBreakdown() { return false; }

    @ConfigItem(keyName = "showLapCount", name = "Show lap count",
        description = "Show total laps completed this session", position = 3)
    default boolean showLapCount() { return true; }

    @ConfigItem(keyName = "showLapTime", name = "Show lap time",
        description = "Show crafting duration and average trip time per lap", position = 4)
    default boolean showLapTime() { return true; }

    @ConfigItem(keyName = "showGpPerHour", name = "Show GP/hour",
        description = "Show estimated GP per hour based on session total", position = 5)
    default boolean showGpPerHour() { return true; }

    @ConfigItem(keyName = "showOutsideZmi", name = "Always show overlay",
        description = "Keep the overlay visible even before your first crafting lap", position = 6)
    default boolean showOutsideZmi() { return false; }
}
