package com.zmitracker;

import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.events.*;
import net.runelite.api.Skill;
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

    private static final int[] POUCH_TYPE_VARBITS   = {29, 1622, 1623, 14285};
    private static final int[] POUCH_AMOUNT_VARBITS = {1624, 1625, 1626, 14286};

    static final Set<Integer> RUNE_IDS = new HashSet<>(Arrays.asList(
        ItemID.AIR_RUNE, ItemID.MIND_RUNE, ItemID.WATER_RUNE, ItemID.EARTH_RUNE,
        ItemID.FIRE_RUNE, ItemID.BODY_RUNE, ItemID.COSMIC_RUNE, ItemID.CHAOS_RUNE,
        ItemID.NATURE_RUNE, ItemID.LAW_RUNE, ItemID.DEATH_RUNE, ItemID.ASTRAL_RUNE,
        ItemID.BLOOD_RUNE, ItemID.SOUL_RUNE, ItemID.WRATH_RUNE
    ));

    @Inject private Client client;
    @Inject private net.runelite.client.callback.ClientThread clientThread;
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
    private final int[] pendingGains = new int[4];
    private boolean pouchReady      = false;

    // Rolling GP/hour: last 10 laps
    private final long[] rollingLapValues  = new long[ROLLING_LAP_COUNT];
    private final long[] rollingLapSeconds = new long[ROLLING_LAP_COUNT];
    private int rollingIndex = 0;
    private int rollingCount = 0;

    private long lastLapValue    = 0;
    private long currentLapValue = 0;
    private long sessionValue    = 0;
    private int  lapCount        = 0;

    // XP tracking
    private long lastLapXp   = 0;
    private long sessionXp   = 0;
    private long currentLapXp = 0;
    private long prevRcXp    = -1;

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
        // pouchReady will be set on first bank open
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
            pouchReady = false;
            Arrays.fill(pouchAmounts, 0);
            Arrays.fill(pendingGains, 0);
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
        boolean bankOpen = bankWidget != null && !bankWidget.isHidden();

        // First bank open after login: initialize pouch baseline
        if (bankOpen && !pouchReady)
        {
            for (int slot = 0; slot < POUCH_AMOUNT_VARBITS.length; slot++)
                pouchAmounts[slot] = client.getVarbitValue(POUCH_AMOUNT_VARBITS[slot]);
            Arrays.fill(pendingGains, 0);
            pouchReady = true;
        }

        // Resolve pending gains on next tick after varbit fires
        if (pouchReady && !bankOpen)
        {
            for (int slot = 0; slot < POUCH_TYPE_VARBITS.length; slot++)
            {
                if (pendingGains[slot] > 0)
                {
                    int itemId = pouchTypeToItemId(client.getVarbitValue(POUCH_TYPE_VARBITS[slot]));
                    if (itemId >= 0)
                    {
                        currentLapRunes.merge(itemId, pendingGains[slot], Integer::sum);
                        sessionRunes.merge(itemId, pendingGains[slot], Integer::sum);
                        currentLapValue = computeValue(currentLapRunes);
                        sessionValue    = computeValue(sessionRunes);
                    }
                    pendingGains[slot] = 0;
                }
            }
        }



        if (bankOpen && !wasBankOpen)
            onBankOpened();
        else if (!bankOpen && wasBankOpen)
            onBankClosed();

        wasBankOpen = bankOpen;
    }

    private void onBankOpened()
    {
        lastLapXp = currentLapXp;
        sessionXp += currentLapXp;
        currentLapXp = 0;

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

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (event.getSkill() != Skill.RUNECRAFT) return;
        long xp = event.getXp();
        if (prevRcXp < 0)
        {
            prevRcXp = xp;
            return;
        }
        long gained = xp - prevRcXp;
        prevRcXp = xp;
        if (gained > 0)
            currentLapXp += gained;
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
        int id = event.getVarbitId();
        for (int slot = 0; slot < POUCH_AMOUNT_VARBITS.length; slot++)
        {
            if (id == POUCH_AMOUNT_VARBITS[slot])
            {
                int newAmount = event.getValue();
                int gained    = newAmount - pouchAmounts[slot];
                pouchAmounts[slot] = newAmount;
                // Only track gains when ready (first bank visited) and bank NOT open
                if (pouchReady && !wasBankOpen && gained > 0)
                    pendingGains[slot] += gained;
                break;
            }
        }
    }

    private void snapshotPouch()
    {
        for (int slot = 0; slot < POUCH_AMOUNT_VARBITS.length; slot++)
            pouchAmounts[slot] = client.getVarbitValue(POUCH_AMOUNT_VARBITS[slot]);
    }

    private int pouchTypeToItemId(int type)
    {
        if (type == 0) return -1;
        EnumComposition e = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        return e.getIntValue(type);
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
        Arrays.fill(pendingGains, 0);
        pouchReady = false;
        lastLapXp    = 0;
        sessionXp    = 0;
        currentLapXp = 0;
        prevRcXp     = -1;
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
    public long getLastLapXp()   { return lastLapXp; }
    public long getSessionXp()   { return sessionXp; }
    public long getTotalSeconds() { return totalLapSeconds; }

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
