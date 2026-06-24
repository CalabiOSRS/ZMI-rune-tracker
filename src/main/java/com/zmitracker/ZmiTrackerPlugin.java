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
    private static final int RUNECRAFT_ANIMATION     = 791;
    private static final int CRAFT_ANIM_WINDOW_TICKS = 20;
    private static final int BANK_GROUP_ID           = 12;
    private static final int ROLLING_LAP_COUNT       = 10;

    private static final int[] POUCH_TYPE_VARBITS   = {29, 30, 31, 1911};
    private static final int[] POUCH_AMOUNT_VARBITS = {1019, 1020, 1021, 1912};

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
    private final Map<Integer, Integer> pendingLapRunes = new HashMap<>();
    private final Map<Integer, Integer> sessionRunes    = new HashMap<>();

    private final int[] pouchAmounts = new int[4];

    // Rolling GP/hour: last 10 laps
    private final long[] rollingLapValues  = new long[ROLLING_LAP_COUNT];
    private final long[] rollingLapSeconds = new long[ROLLING_LAP_COUNT];
    private int rollingIndex = 0;
    private int rollingCount = 0;

    private long lastLapValue    = 0;
    private long currentLapValue = 0;
    private long sessionValue    = 0;
    private int  lapCount        = 0;

    // Timing
    private Instant  lapStart        = null;
    private Duration pausedDuration  = Duration.ZERO; // paused time in current lap
    private Instant  logoutTime      = null;          // when we logged out
    private long     lastLapSeconds  = 0;
    private long     totalLapSeconds = 0;

    private boolean wasBankOpen      = false;
    private int     lastCraftAnimTick = -100;

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);
        reset();
        snapshotPouch();
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

    // ── Login/logout tracking ──────────────────────────────────────────────────

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            // Logged out: record when
            if (logoutTime == null)
                logoutTime = Instant.now();
        }
        else if (state == GameState.LOGGED_IN)
        {
            // Logged back in: accumulate paused duration
            if (logoutTime != null)
            {
                pausedDuration = pausedDuration.plus(Duration.between(logoutTime, Instant.now()));
                logoutTime = null;
            }
        }
    }

    // ── Bank detection ─────────────────────────────────────────────────────────

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        Widget bankWidget = client.getWidget(BANK_GROUP_ID, 0);
        boolean isBankOpen = bankWidget != null && !bankWidget.isHidden();

        if (isBankOpen && !wasBankOpen)
            onBankOpened();
        else if (!isBankOpen && wasBankOpen)
            onBankClosed();

        wasBankOpen = isBankOpen;
    }

    private void onBankOpened()
    {
        if (!currentLapRunes.isEmpty())
        {
            pendingLapRunes.clear();
            pendingLapRunes.putAll(currentLapRunes);
            lastLapRunes.clear();
            lastLapRunes.putAll(currentLapRunes);
            lastLapValue = currentLapValue;
            currentLapRunes.clear();
            currentLapValue = 0;
        }
    }

    private void onBankClosed()
    {
        if (lapStart != null && !pendingLapRunes.isEmpty())
        {
            // Elapsed wall time minus any paused time
            long elapsed = Duration.between(lapStart, Instant.now())
                .minus(pausedDuration).getSeconds();
            elapsed = Math.max(1, elapsed);

            lastLapSeconds = elapsed;
            totalLapSeconds += elapsed;

            // Rolling GP/hour
            rollingLapValues [rollingIndex] = lastLapValue;
            rollingLapSeconds[rollingIndex] = elapsed;
            rollingIndex = (rollingIndex + 1) % ROLLING_LAP_COUNT;
            if (rollingCount < ROLLING_LAP_COUNT) rollingCount++;

            lapCount++;
            pendingLapRunes.clear();
        }

        lapStart       = Instant.now();
        pausedDuration = Duration.ZERO;
        snapshotPouch();
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

    // ── Inventory rune tracking ────────────────────────────────────────────────

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

    // ── Rune pouch tracking ────────────────────────────────────────────────────

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        int varbitId = event.getVarbitId();
        for (int slot = 0; slot < POUCH_AMOUNT_VARBITS.length; slot++)
        {
            if (varbitId == POUCH_AMOUNT_VARBITS[slot])
            {
                int newAmount = event.getValue();
                int oldAmount = pouchAmounts[slot];
                int gained    = newAmount - oldAmount;
                pouchAmounts[slot] = newAmount;

                if (gained > 0)
                {
                    boolean inCraftWindow = (client.getTickCount() - lastCraftAnimTick) <= CRAFT_ANIM_WINDOW_TICKS;
                    if (inCraftWindow)
                    {
                        int type   = client.getVarbitValue(POUCH_TYPE_VARBITS[slot]);
                        int itemId = pouchTypeToItemId(type);
                        if (itemId >= 0)
                        {
                            currentLapRunes.merge(itemId, gained, Integer::sum);
                            sessionRunes.merge(itemId, gained, Integer::sum);
                            currentLapValue = computeValue(currentLapRunes);
                            sessionValue    = computeValue(sessionRunes);
                        }
                    }
                }
                break;
            }
        }
    }

    private void snapshotPouch()
    {
        for (int slot = 0; slot < POUCH_AMOUNT_VARBITS.length; slot++)
            pouchAmounts[slot] = client.getVarbitValue(POUCH_AMOUNT_VARBITS[slot]);
    }

    private static int pouchTypeToItemId(int type)
    {
        switch (type)
        {
            case 1:  return ItemID.AIR_RUNE;
            case 2:  return ItemID.WATER_RUNE;
            case 3:  return ItemID.EARTH_RUNE;
            case 4:  return ItemID.FIRE_RUNE;
            case 5:  return ItemID.MIND_RUNE;
            case 6:  return ItemID.BODY_RUNE;
            case 7:  return ItemID.DEATH_RUNE;
            case 8:  return ItemID.NATURE_RUNE;
            case 9:  return ItemID.CHAOS_RUNE;
            case 10: return ItemID.LAW_RUNE;
            case 11: return ItemID.COSMIC_RUNE;
            case 12: return ItemID.BLOOD_RUNE;
            case 13: return ItemID.ASTRAL_RUNE;
            case 14: return ItemID.SOUL_RUNE;
            case 15: return ItemID.WRATH_RUNE;
            default: return -1;
        }
    }

    // ── Right-click reset ──────────────────────────────────────────────────────

    @Subscribe
    public void onOverlayMenuClicked(OverlayMenuClicked event)
    {
        if (event.getOverlay() == overlay && "Reset".equals(event.getEntry().getOption()))
            reset();
    }

    // ── Internals ──────────────────────────────────────────────────────────────

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
        pendingLapRunes.clear();
        sessionRunes.clear();
        lastLapValue = currentLapValue = sessionValue = 0;
        lapCount = 0;
        lastLapSeconds = totalLapSeconds = 0;
        lapStart = null;
        pausedDuration = Duration.ZERO;
        logoutTime = null;
        wasBankOpen = false;
        lastCraftAnimTick = -100;
        Arrays.fill(pouchAmounts, 0);
        Arrays.fill(rollingLapValues, 0);
        Arrays.fill(rollingLapSeconds, 0);
        rollingIndex = 0;
        rollingCount = 0;
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
    public ItemManager getItemManager()    { return itemManager; }

    public long getCurrentLapElapsed()
    {
        if (lapStart == null) return 0;
        Duration elapsed = Duration.between(lapStart, Instant.now()).minus(pausedDuration);
        return Math.max(0, elapsed.getSeconds());
    }

    public boolean isLapInProgress()
    {
        return lapStart != null;
    }

    public long getRollingGpPerHour()
    {
        if (rollingCount == 0) return 0;
        long totalValue   = 0;
        long totalSeconds = 0;
        for (int i = 0; i < rollingCount; i++)
        {
            totalValue   += rollingLapValues[i];
            totalSeconds += rollingLapSeconds[i];
        }
        if (totalSeconds == 0) return 0;
        return totalValue * 3600L / totalSeconds;
    }
}
