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

    @ConfigItem(keyName = "gpHourMode", name = "GP/hour mode",
        description = "Show GP/hour based on last 10 laps, full session, or hide it", position = 6)
    default GpHourMode gpHourMode() { return GpHourMode.LAST_10_LAPS; }

    @ConfigItem(keyName = "showXpLastLap", name = "Show XP last lap",
        description = "Show XP gained in the last lap", position = 7)
    default boolean showXpLastLap() { return false; }

    @ConfigItem(keyName = "showXpPerHour", name = "Show XP/hour",
        description = "Show estimated XP per hour based on session", position = 8)
    default boolean showXpPerHour() { return false; }

    @ConfigItem(keyName = "backgroundOpacity", name = "Background opacity",
        description = "Opacity of the overlay background (0 = transparent, 255 = opaque)", position = 9)
    @Range(min = 0, max = 255)
    default int backgroundOpacity() { return 150; }

    @ConfigItem(keyName = "showOutsideZmi", name = "Always show overlay",
        description = "Keep the overlay visible even before your first crafting lap", position = 10)
    default boolean showOutsideZmi() { return false; }

    @ConfigItem(keyName = "showPouchWarning", name = "Show pouch warning",
        description = "Show a reminder to reset session after manually adding/removing runes from the pouch", position = 11)
    default boolean showPouchWarning() { return true; }
}
