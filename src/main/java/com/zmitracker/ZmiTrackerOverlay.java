package com.zmitracker;

import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;
import net.runelite.client.util.QuantityFormatter;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class ZmiTrackerOverlay extends OverlayPanel
{
    private static final Color HEADER_COLOR  = new Color(255, 200,   0);
    private static final Color VALUE_COLOR   = new Color(  0, 220, 100);
    private static final Color LABEL_COLOR   = new Color(200, 200, 200);
    private static final Color RUNE_COLOR    = new Color(180, 220, 255);
    private static final Color CURRENT_COLOR = new Color(255, 165,   0); // orange = in progress
    private static final Color DIM_COLOR     = new Color(120, 120, 120);

    private final ZmiTrackerPlugin plugin;
    private final ZmiTrackerConfig config;
    private final ItemManager itemManager;
    private final Instant sessionStart = Instant.now();

    @Inject
    public ZmiTrackerOverlay(ZmiTrackerPlugin plugin, ZmiTrackerConfig config, ItemManager itemManager)
    {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        setPosition(OverlayPosition.TOP_LEFT);
        setPriority(OverlayPriority.LOW);
        panelComponent.setPreferredSize(new Dimension(230, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!plugin.isLapInProgress() && plugin.getLapCount() == 0 && !config.showOutsideZmi())
            return null;

        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(TitleComponent.builder()
            .text("⚗ ZMI Rune Tracker").color(HEADER_COLOR).build());
        panelComponent.getChildren().add(LineComponent.builder().left("").build());

        // ── Current lap (in progress) ──────────────────────────────────────
        if (plugin.isLapInProgress())
        {
            String elapsed = formatDuration(Duration.between(plugin.getCurrentLapStart(), Instant.now()).getSeconds());
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Current lap (" + elapsed + ")")
                .leftColor(CURRENT_COLOR)
                .right(formatGp(plugin.getCurrentLapValue()))
                .rightColor(CURRENT_COLOR)
                .build());

            if (config.showCurrentLapBreakdown())
                renderRuneBreakdown(plugin.getCurrentLapRunes());

            panelComponent.getChildren().add(LineComponent.builder().left("").build());
        }

        // ── Last completed lap ─────────────────────────────────────────────
        if (plugin.getLapCount() > 0)
        {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Last lap")
                .leftColor(LABEL_COLOR)
                .right(formatGp(plugin.getLastLapValue()))
                .rightColor(VALUE_COLOR)
                .build());

            if (config.showLapTime() && plugin.getLastLapCraftSeconds() > 0)
            {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("  craft time")
                    .leftColor(DIM_COLOR)
                    .right(formatDuration(plugin.getLastLapCraftSeconds()))
                    .rightColor(DIM_COLOR)
                    .build());
            }

            if (config.showLastLapBreakdown())
                renderRuneBreakdown(plugin.getLastLapRunes());

            panelComponent.getChildren().add(LineComponent.builder().left("").build());
        }

        // ── Session ───────────────────────────────────────────────────────
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
        }

        if (config.showLapTime() && plugin.getAverageTripSeconds() > 0)
        {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("  avg trip").leftColor(DIM_COLOR)
                .right(formatDuration(plugin.getAverageTripSeconds())).rightColor(DIM_COLOR)
                .build());
        }

        if (config.showSessionBreakdown())
            renderRuneBreakdown(plugin.getSessionRunes());

        if (config.showGpPerHour() && plugin.getLapCount() > 0)
        {
            long elapsed = Duration.between(sessionStart, Instant.now()).getSeconds();
            if (elapsed > 0)
            {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("GP / hour").leftColor(LABEL_COLOR)
                    .right(formatGp(plugin.getSessionValue() * 3600L / elapsed)).rightColor(VALUE_COLOR)
                    .build());
            }
        }

        panelComponent.getChildren().add(LineComponent.builder().left("").build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Right-click to reset").leftColor(DIM_COLOR).build());

        return super.render(graphics);
    }

    private void renderRuneBreakdown(Map<Integer, Integer> runes)
    {
        if (runes.isEmpty())
        {
            panelComponent.getChildren().add(LineComponent.builder().left("  —").leftColor(DIM_COLOR).build());
            return;
        }
        runes.entrySet().stream()
            .sorted((a, b) -> {
                long valA = (long) itemManager.getItemPrice(a.getKey()) * a.getValue();
                long valB = (long) itemManager.getItemPrice(b.getKey()) * b.getValue();
                return Long.compare(valB, valA);
            })
            .forEach(e -> {
                int price = itemManager.getItemPrice(e.getKey());
                long value = (long) price * e.getValue();
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("  " + QuantityFormatter.quantityToStackSize(e.getValue()) + "× " + runeName(e.getKey()))
                    .leftColor(RUNE_COLOR)
                    .right(formatGp(value)).rightColor(LABEL_COLOR)
                    .build());
            });
    }

    private static String formatGp(long gp)
    {
        return gp == 0 ? "—" : QuantityFormatter.quantityToStackSize(gp) + " gp";
    }

    private static String formatDuration(long seconds)
    {
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
