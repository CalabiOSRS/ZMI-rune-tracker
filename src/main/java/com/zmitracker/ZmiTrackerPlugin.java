package com.zmitracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@PluginDescriptor(
    name = "ZMI Rune Tracker",
    description = "Tracks rune values per lap and total session at the Ourania (ZMI) Altar",
    tags = {"zmi", "ourania", "runecrafting", "runes", "money", "tracker"}
)
public class ZmiTrackerPlugin extends Plugin
{
    private static final int RUNECRAFT_ANIMATION = 791;
    private static final int CRAFT_ANIM_WINDOW_TICKS = 20;
    private static final int LAP_TIMEOUT_TICKS = 50;

    private static final Set<Integer> RUNE_IDS = new HashSet<>(Arrays.asList(
        ItemID.AIR_RUNE, ItemID.MIND_RUNE, ItemID.WATER_RUNE, ItemID.EARTH_RUNE,
        ItemID.FIRE_RUNE, ItemID.BODY_RUNE, ItemID.COSMIC_RUNE, ItemID.CHAOS_RUNE,
        ItemID.NATURE_RUNE, ItemID.LAW_RUNE, ItemID.DEATH_RUNE, ItemID.ASTRAL_RUNE,
        ItemID.BLOOD_RUNE, ItemID.SOUL_RUNE, ItemID.WRATH_RUNE
    ));

    @Inject private Client client;
    @Inject private ZmiTrackerConfig config;
    @Inject private ItemManager itemManager;
    @Inject private OverlayManager overlayManager;
    @Inject private ZmiTrackerOverlay overlay;

    private final Map<Integer, Integer> prevInventory   = new HashMap<>();
    private final Map<Integer, Integer> currentLapRunes = new HashMap<>();
    private final Map<Integer, Integer> lastLapRunes    = new HashMap<>();
    private final Map<Integer, Integer> sessionRunes    = new HashMap<>();

    private long lastLapValue      = 0;
    private long currentLapValue   = 0;
    private long sessionValue      = 0;
    private int  lapCount          = 0;

    private Instant currentLapStart     = null;
    private Instant lastGainTime        = null;
    private Instant prevLapStart        = null;
    private long    lastLapTripSeconds  = 0;
    private long    lastLapCraftSeconds = 0;
    private long    totalTripSeconds    = 0;

    private int     ticksSinceLastGain  = 0;
    private boolean lapInProgress       = false;
    private int     lastCraftAnimTick   = -100;

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);
        reset();
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
    }

    @Provides
    ZmiTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ZmiTrackerConfig.class);
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        Actor actor = event.getActor();
        if (!(actor instanceof Player)) return;
        if (actor != client.getLocalPlayer()) return;
        if (((Player) actor).getAnimation() == RUNECRAFT_ANIMATION)
            lastCraftAnimTick = client.getTickCount();
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (lapInProgress)
        {
            ticksSinceLastGain++;
            if (ticksSinceLastGain >= LAP_TIMEOUT_TICKS)
                finalizeLap();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) return;

        Map<Integer, Integer> current = new HashMap<>();
        for (Item item : event.getItemContainer().getItems())
        {
            if (item == null || item.getId() < 0) continue;
            if (RUNE_IDS.contains(item.getId()))
                current.merge(item.getId(), item.getQuantity(), Integer::sum);
        }

        boolean inCraftWindow = (client.getTickCount() - lastCraftAnimTick) <= CRAFT_ANIM_WINDOW_TICKS;

        boolean anyGain = false;
        for (int runeId : RUNE_IDS)
        {
            int gained = current.getOrDefault(runeId, 0) - prevInventory.getOrDefault(runeId, 0);
            if (gained > 0 && inCraftWindow)
            {
                currentLapRunes.merge(runeId, gained, Integer::sum);
                sessionRunes.merge(runeId, gained, Integer::sum);
                anyGain = true;
            }
        }

        prevInventory.clear();
        prevInventory.putAll(current);

        if (anyGain)
        {
            Instant now = Instant.now();
            if (!lapInProgress)
            {
                if (prevLapStart != null)
                {
                    lastLapTripSeconds = Duration.between(prevLapStart, now).getSeconds();
                    totalTripSeconds += lastLapTripSeconds;
                }
                lapInProgress = true;
                currentLapStart = now;
            }
            lastGainTime = now;
            ticksSinceLastGain = 0;
            currentLapValue = computeValue(currentLapRunes);
            sessionValue    = computeValue(sessionRunes);
        }
    }

    private void finalizeLap()
    {
        if (currentLapRunes.isEmpty()) return;

        lastLapRunes.clear();
        lastLapRunes.putAll(currentLapRunes);
        lastLapValue = currentLapValue;

        if (currentLapStart != null && lastGainTime != null)
            lastLapCraftSeconds = Math.max(1, Duration.between(currentLapStart, lastGainTime).getSeconds());

        prevLapStart = currentLapStart;
        lapCount++;

        currentLapRunes.clear();
        currentLapValue    = 0;
        currentLapStart    = null;
        lapInProgress      = false;
        ticksSinceLastGain = 0;
    }

    public Map<Integer, Integer> getCurrentLapRunes()  { return Collections.unmodifiableMap(currentLapRunes); }
    public Map<Integer, Integer> getLastLapRunes()     { return Collections.unmodifiableMap(lastLapRunes); }
    public Map<Integer, Integer> getSessionRunes()     { return Collections.unmodifiableMap(sessionRunes); }
    public long    getCurrentLapValue()      { return currentLapValue; }
    public long    getLastLapValue()         { return lastLapValue; }
    public long    getSessionValue()         { return sessionValue; }
    public int     getLapCount()             { return lapCount; }
    public long    getLastLapCraftSeconds()  { return lastLapCraftSeconds; }
    public long    getLastLapTripSeconds()   { return lastLapTripSeconds; }
    public long    getAverageTripSeconds()   { return lapCount > 1 ? totalTripSeconds / (lapCount - 1) : 0; }
    public boolean isLapInProgress()         { return lapInProgress; }
    public Instant getCurrentLapStart()      { return currentLapStart; }

    private long computeValue(Map<Integer, Integer> runes)
    {
        long total = 0;
        for (Map.Entry<Integer, Integer> e : runes.entrySet())
            total += (long) itemManager.getItemPrice(e.getKey()) * e.getValue();
        return total;
    }

    public void reset()
    {
        prevInventory.clear();
        currentLapRunes.clear();
        lastLapRunes.clear();
        sessionRunes.clear();
        lastLapValue = currentLapValue = sessionValue = 0;
        lapCount = 0;
        lastLapCraftSeconds = lastLapTripSeconds = totalTripSeconds = 0;
        currentLapStart = lastGainTime = prevLapStart = null;
        lapInProgress = false;
        ticksSinceLastGain = 0;
        lastCraftAnimTick = -100;
    }
}
