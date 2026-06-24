package com.zmitracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("zmitracker")
public interface ZmiTrackerConfig extends Config
{
    @ConfigItem(keyName = "hideElementalRunes", name = "Hide elemental runes",
        description = "Hide Air, Water, Earth and Fire runes from the breakdown (value still counted)", position = 0)
    default boolean hideElementalRunes() { return false; }

    @ConfigItem(keyName = "hideBodyRune", name = "Hide body runes",
        description = "Hide Body runes from the breakdown (value still counted)", position = 1)
    default boolean hideBodyRune() { return false; }

    @ConfigItem(keyName = "hideMindRune", name = "Hide mind runes",
        description = "Hide Mind runes from the breakdown (value still counted)", position = 2)
    default boolean hideMindRune() { return false; }

    @ConfigItem(keyName = "showCurrentLapBreakdown", name = "Show current lap breakdown",
        description = "Show individual runes for the lap in progress", position = 3)
    default boolean showCurrentLapBreakdown() { return false; }

    @ConfigItem(keyName = "showSessionBreakdown", name = "Show session breakdown",
        description = "Show individual runes for the entire session", position = 4)
    default boolean showSessionBreakdown() { return false; }

    @ConfigItem(keyName = "showLapCount", name = "Show lap count",
        description = "Show total laps completed this session", position = 5)
    default boolean showLapCount() { return true; }

    @ConfigItem(keyName = "showGpPerHour", name = "Show GP/hour",
        description = "Show estimated GP per hour based on session total", position = 6)
    default boolean showGpPerHour() { return true; }

    @ConfigItem(keyName = "backgroundOpacity", name = "Background opacity",
        description = "Opacity of the overlay background (0 = transparent, 255 = opaque)", position = 7)
    @Range(min = 0, max = 255)
    default int backgroundOpacity() { return 150; }

    @ConfigItem(keyName = "showOutsideZmi", name = "Always show overlay",
        description = "Keep the overlay visible even before your first crafting lap", position = 8)
    default boolean showOutsideZmi() { return false; }
}
