package com.zmitracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.OverlayMenuClicked;
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
    private static final int RUNECRAFT_ANIMATION   = 791;
    private static final int CRAFT_ANIM_WINDOW_TICKS = 20;
    private static final int BANK_GROUP_ID         = 12;

    static final Set<Integer> RUNE_IDS = new HashSet<>(Arrays.asList(
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

    private long lastLapValue    = 0;
    private long currentLapValue = 0;
    private long sessionValue    = 0;
    private int  lapCount        = 0;

    // Timing
    private Instant lapStart           = null; // when bank was last closed
    private long    lastLapSeconds     = 0;
    private long    totalLapSeconds    = 0;

    // State
    private boolean wasBankOpen      = false;
    private int     lastCraftAnimTick = -100;

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

    // ── Bank detection ─────────────────────────────────────────────────────────

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        Widget bankWidget = client.getWidget(BANK_GROUP_ID, 0);
        boolean isBankOpen = bankWidget != null && !bankWidget.isHidden();

        if (isBankOpen && !wasBankOpen)
        {
            onBankOpened();
        }
        else if (!isBankOpen && wasBankOpen)
        {
            onBankClosed();
        }

        wasBankOpen = isBankOpen;
    }

    private void onBankOpened()
    {
        // End of lap: bank reached
        if (lapStart != null && !currentLapRunes.isEmpty())
        {
            lastLapSeconds = Duration.between(lapStart, Instant.now()).getSeconds();
            totalLapSeconds += lastLapSeconds;
            finalizeLap();
        }
        lapStart = null;
    }

    private void onBankClosed()
    {
        // Start of new lap: leaving bank
        lapStart = Instant.now();
        currentLapRunes.clear();
        currentLapValue = 0;
    }

    // ── Craft animation gate ───────────────────────────────────────────────────

    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        Actor actor = event.getActor();
        if (!(actor instanceof Player)) return;
        if (actor != client.getLocalPlayer()) return;
        if (((Player) actor).getAnimation() == RUNECRAFT_ANIMATION)
            lastCraftAnimTick = client.getTickCount();
    }

    // ── Rune gain tracking ─────────────────────────────────────────────────────

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

        for (int runeId : RUNE_IDS)
        {
            int gained = current.getOrDefault(runeId, 0) - prevInventory.getOrDefault(runeId, 0);
            if (gained > 0 && inCraftWindow)
            {
                currentLapRunes.merge(runeId, gained, Integer::sum);
                sessionRunes.merge(runeId, gained, Integer::sum);
            }
        }

        prevInventory.clear();
        prevInventory.putAll(current);

        currentLapValue = computeValue(currentLapRunes);
        sessionValue    = computeValue(sessionRunes);
    }

    // ── Right-click reset ──────────────────────────────────────────────────────

    @Subscribe
    public void onOverlayMenuClicked(OverlayMenuClicked event)
    {
        if (event.getOverlay() == overlay && "Reset".equals(event.getEntry().getOption()))
        {
            reset();
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private void finalizeLap()
    {
        lastLapRunes.clear();
        lastLapRunes.putAll(currentLapRunes);
        lastLapValue = currentLapValue;
        lapCount++;
        currentLapRunes.clear();
        currentLapValue = 0;
    }

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
        lastLapSeconds = totalLapSeconds = 0;
        lapStart = null;
        wasBankOpen = false;
        lastCraftAnimTick = -100;
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public Map<Integer, Integer> getCurrentLapRunes()  { return Collections.unmodifiableMap(currentLapRunes); }
    public Map<Integer, Integer> getLastLapRunes()     { return Collections.unmodifiableMap(lastLapRunes); }
    public Map<Integer, Integer> getSessionRunes()     { return Collections.unmodifiableMap(sessionRunes); }
    public long    getCurrentLapValue()    { return currentLapValue; }
    public long    getLastLapValue()       { return lastLapValue; }
    public long    getSessionValue()       { return sessionValue; }
    public int     getLapCount()           { return lapCount; }
    public long    getLastLapSeconds()     { return lastLapSeconds; }
    public long    getAverageLapSeconds()  { return lapCount > 0 ? totalLapSeconds / lapCount : 0; }
    public Instant getLapStart()           { return lapStart; }
    public ItemManager getItemManager()    { return itemManager; }
}
