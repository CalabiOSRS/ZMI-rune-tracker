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
    private static final Color HEADER_COLOR = new Color(255, 200, 0);      // gold
    private static final Color VALUE_COLOR  = new Color(0, 220, 100);      // green
    private static final Color LABEL_COLOR  = new Color(200, 200, 200);    // light grey
    private static final Color RUNE_COLOR   = new Color(180, 220, 255);    // light blue

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
        panelComponent.setPreferredSize(new Dimension(220, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Show once at least one lap has been recorded, or if "always show" is on
        if (plugin.getLapCount() == 0 && !config.showOutsideZmi())
        {
            return null;
        }

        panelComponent.getChildren().clear();

        // ── Title ───────────────────────────────────────────────
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("⚗ ZMI Rune Tracker")
            .color(HEADER_COLOR)
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("")
            .build());

        // ── Lap count ────────────────────────────────────────────
        if (config.showLapCount())
        {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Laps")
                .leftColor(LABEL_COLOR)
                .right(String.valueOf(plugin.getLapCount()))
                .rightColor(Color.WHITE)
                .build());
        }

        // ── Last lap ─────────────────────────────────────────────
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Last lap")
            .leftColor(LABEL_COLOR)
            .right(formatGp(plugin.getLastLapValue()))
            .rightColor(VALUE_COLOR)
            .build());

        if (config.showLastLapBreakdown())
        {
            renderRuneBreakdown(plugin.getLastLapRunes());
        }

        panelComponent.getChildren().add(LineComponent.builder()
            .left("")
            .build());

        // ── Session total ─────────────────────────────────────────
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Session total")
            .leftColor(LABEL_COLOR)
            .right(formatGp(plugin.getSessionValue()))
            .rightColor(VALUE_COLOR)
            .build());

        if (config.showSessionBreakdown())
        {
            renderRuneBreakdown(plugin.getSessionRunes());
        }

        // ── GP / hour ─────────────────────────────────────────────
        if (config.showGpPerHour() && plugin.getLapCount() > 0)
        {
            long gpPerHour = calcGpPerHour();
            panelComponent.getChildren().add(LineComponent.builder()
                .left("GP / hour")
                .leftColor(LABEL_COLOR)
                .right(formatGp(gpPerHour))
                .rightColor(VALUE_COLOR)
                .build());
        }

        // ── Reset hint ────────────────────────────────────────────
        panelComponent.getChildren().add(LineComponent.builder()
            .left("")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Right-click to reset")
            .leftColor(new Color(120, 120, 120))
            .build());

        return super.render(graphics);
    }

    private void renderRuneBreakdown(Map<Integer, Integer> runes)
    {
        if (runes.isEmpty())
        {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("  —")
                .leftColor(new Color(120, 120, 120))
                .build());
            return;
        }

        for (Map.Entry<Integer, Integer> entry : runes.entrySet())
        {
            int itemId = entry.getKey();
            int qty = entry.getValue();
            int price = itemManager.getItemPrice(itemId);
            long value = (long) price * qty;

            String name = getRuneShortName(itemId);
            String left  = "  " + QuantityFormatter.quantityToStackSize(qty) + "× " + name;
            String right = formatGp(value);

            panelComponent.getChildren().add(LineComponent.builder()
                .left(left)
                .leftColor(RUNE_COLOR)
                .right(right)
                .rightColor(LABEL_COLOR)
                .build());
        }
    }

    private long calcGpPerHour()
    {
        long elapsedSeconds = Duration.between(sessionStart, Instant.now()).getSeconds();
        if (elapsedSeconds < 1)
        {
            return 0;
        }
        return (plugin.getSessionValue() * 3600L) / elapsedSeconds;
    }

    private static String formatGp(long gp)
    {
        if (gp == 0)
        {
            return "—";
        }
        return QuantityFormatter.quantityToStackSize(gp) + " gp";
    }

    private static String getRuneShortName(int itemId)
    {
        switch (itemId)
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
            default:                 return "Rune #" + itemId;
        }
    }
}
