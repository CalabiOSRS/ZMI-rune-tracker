package com.zmitracker;

import net.runelite.api.ItemID;
import net.runelite.api.MenuAction;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;
import net.runelite.client.util.QuantityFormatter;

import javax.inject.Inject;
import java.awt.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ZmiTrackerOverlay extends OverlayPanel
{
    private static final Color HEADER_COLOR  = new Color(255, 200,   0);
    private static final Color VALUE_COLOR   = new Color(  0, 220, 100);
    private static final Color LABEL_COLOR   = new Color(200, 200, 200);
    private static final Color RUNE_COLOR    = new Color(180, 220, 255);
    private static final Color CURRENT_COLOR = new Color(255, 165,   0);
    private static final Color DIM_COLOR     = new Color(120, 120, 120);

    private static final Set<Integer> ELEMENTAL_IDS = new HashSet<>(Arrays.asList(
        ItemID.AIR_RUNE, ItemID.WATER_RUNE, ItemID.EARTH_RUNE, ItemID.FIRE_RUNE
    ));

    private final ZmiTrackerPlugin plugin;
    private final ZmiTrackerConfig config;
    private final ItemManager itemManager;

    @Inject
    public ZmiTrackerOverlay(ZmiTrackerPlugin plugin, ZmiTrackerConfig config, ItemManager itemManager)
    {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;

        setPosition(OverlayPosition.TOP_LEFT);
        setPriority(OverlayPriority.LOW);
        panelComponent.setPreferredSize(new Dimension(230, 0));
        getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Reset", "ZMI Rune Tracker"));
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        boolean hasData = plugin.isLapInProgress() || plugin.getLapCount() > 0 || plugin.getCurrentLapValue() > 0;
        if (!hasData && !config.showOutsideZmi()) return null;

        panelComponent.setBackgroundColor(new Color(23, 23, 23, config.backgroundOpacity()));
        panelComponent.getChildren().clear();

        // ── Title ──────────────────────────────────────────────────────────────
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("ZMI Rune Tracker").color(HEADER_COLOR).build());

        // ── Current lap ────────────────────────────────────────────────────────
        if (plugin.isLapInProgress())
        {
            long elapsed = plugin.getCurrentLapElapsed();
            panelComponent.getChildren().add(LineComponent.builder().left("").build());
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Current lap (" + formatTime(elapsed) + ")")
                .leftColor(CURRENT_COLOR)
                .right(formatGp(plugin.getCurrentLapValue()))
                .rightColor(CURRENT_COLOR)
                .build());

            if (config.showCurrentLapBreakdown())
                renderBreakdown(plugin.getCurrentLapRunes());
        }

        // ── Last lap ───────────────────────────────────────────────────────────
        if (plugin.getLapCount() > 0)
        {
            panelComponent.getChildren().add(LineComponent.builder().left("").build());
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Last lap (" + formatTime(plugin.getLastLapSeconds()) + ")")
                .leftColor(LABEL_COLOR)
                .right(formatGp(plugin.getLastLapValue()))
                .rightColor(VALUE_COLOR)
                .build());
        }

        // ── Session ────────────────────────────────────────────────────────────
        panelComponent.getChildren().add(LineComponent.builder().left("").build());

        if (config.showLapCount())
        {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Laps").leftColor(LABEL_COLOR)
                .right(String.valueOf(plugin.getLapCount())).rightColor(Color.WHITE)
                .build());
        }

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Session total").leftColor(LABEL_COLOR)
            .right(formatGp(plugin.getSessionValue())).rightColor(VALUE_COLOR)
            .build());

        if (plugin.getLapCount() > 0)
        {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("  avg/lap").leftColor(DIM_COLOR)
                .right(formatGp(plugin.getSessionValue() / plugin.getLapCount())).rightColor(DIM_COLOR)
                .build());

            if (plugin.getAverageLapSeconds() > 0)
            {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("  avg trip").leftColor(DIM_COLOR)
                    .right(formatTime(plugin.getAverageLapSeconds())).rightColor(DIM_COLOR)
                    .build());
            }
        }

        if (config.showSessionBreakdown())
            renderBreakdown(plugin.getSessionRunes());

        // ── Rolling GP/hour (last 10 laps) ────────────────────────────────────
        if (config.showGpPerHour())
        {
            long gpPerHour = plugin.getRollingGpPerHour();
            if (gpPerHour > 0)
            {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("GP / hour").leftColor(LABEL_COLOR)
                    .right(formatGp(gpPerHour)).rightColor(VALUE_COLOR)
                    .build());
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("  (last 10 laps)").leftColor(DIM_COLOR)
                    .build());
            }
        }

        // ── Pouch warning ──────────────────────────────────────────────────────
        if (config.showPouchWarning())
        {
            panelComponent.getChildren().add(LineComponent.builder().left("").build());
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Reset session if you manually")
                .leftColor(new Color(255, 165, 0))
                .build());
            panelComponent.getChildren().add(LineComponent.builder()
                .left("add/remove runes from pouch")
                .leftColor(new Color(255, 165, 0))
                .build());
        }

        return super.render(graphics);
    }

    private void renderBreakdown(Map<Integer, Integer> runes)
    {
        if (runes.isEmpty()) return;

        runes.entrySet().stream()
            .filter(e -> !shouldHide(e.getKey()))
            .sorted((a, b) -> Long.compare(
                (long) itemManager.getItemPrice(b.getKey()) * b.getValue(),
                (long) itemManager.getItemPrice(a.getKey()) * a.getValue()))
            .forEach(e -> {
                long value = (long) itemManager.getItemPrice(e.getKey()) * e.getValue();
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("  " + QuantityFormatter.quantityToStackSize(e.getValue()) + "x " + runeName(e.getKey()))
                    .leftColor(RUNE_COLOR)
                    .right(formatGp(value)).rightColor(LABEL_COLOR)
                    .build());
            });
    }

    private boolean shouldHide(int itemId)
    {
        if (config.hideElementalRunes() && ELEMENTAL_IDS.contains(itemId)) return true;
        if (config.hideBodyRune() && itemId == ItemID.BODY_RUNE) return true;
        if (config.hideMindRune() && itemId == ItemID.MIND_RUNE) return true;
        return false;
    }

    private static String formatGp(long gp)
    {
        return gp <= 0 ? "-" : QuantityFormatter.quantityToStackSize(gp) + " gp";
    }

    private static String formatTime(long seconds)
    {
        if (seconds <= 0) return "0s";
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static String runeName(int id)
    {
        switch (id)
        {
            case ItemID.AIR_RUNE:    return "Air";
            case ItemID.MIND_RUNE:   return "Mind";
            case ItemID.WATER_RUNE:  return "Water";
            case ItemID.EARTH_RUNE:  return "Earth";
            case ItemID.FIRE_RUNE:   return "Fire";
            case ItemID.BODY_RUNE:   return "Body";
            case ItemID.COSMIC_RUNE: return "Cosmic";
            case ItemID.CHAOS_RUNE:  return "Chaos";
            case ItemID.NATURE_RUNE: return "Nature";
            case ItemID.LAW_RUNE:    return "Law";
            case ItemID.DEATH_RUNE:  return "Death";
            case ItemID.ASTRAL_RUNE: return "Astral";
            case ItemID.BLOOD_RUNE:  return "Blood";
            case ItemID.SOUL_RUNE:   return "Soul";
            case ItemID.WRATH_RUNE:  return "Wrath";
            default:                 return "Rune";
        }
    }
}
