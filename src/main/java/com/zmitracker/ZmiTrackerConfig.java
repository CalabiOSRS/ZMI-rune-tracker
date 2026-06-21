package com.zmitracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("zmitracker")
public interface ZmiTrackerConfig extends Config
{
    @ConfigItem(
        keyName = "showLastLapBreakdown",
        name = "Show last lap rune breakdown",
        description = "Show the individual rune quantities and their values for the last lap",
        position = 0
    )
    default boolean showLastLapBreakdown()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showSessionBreakdown",
        name = "Show session rune breakdown",
        description = "Show the individual rune quantities and their values for the entire session",
        position = 1
    )
    default boolean showSessionBreakdown()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showOutsideZmi",
        name = "Always show overlay",
        description = "Keep the overlay visible even before your first crafting lap",
        position = 2
    )
    default boolean showOutsideZmi()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showLapCount",
        name = "Show lap count",
        description = "Show the total number of altar uses this session",
        position = 3
    )
    default boolean showLapCount()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showGpPerHour",
        name = "Show GP/hour estimate",
        description = "Show an estimated GP per hour based on session average and time elapsed",
        position = 4
    )
    default boolean showGpPerHour()
    {
        return true;
    }
}
