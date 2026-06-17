package com.zmitracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.*;

@Slf4j
@PluginDescriptor(
    name = "ZMI Rune Tracker",
    description = "Tracks rune values per lap and total session at the Ourania (ZMI) Altar",
    tags = {"zmi", "ourania", "runecrafting", "runes", "money", "tracker"}
)
public class ZmiTrackerPlugin extends Plugin
{
    // Ourania Altar region ID
    private static final int OURANIA_ALTAR_REGION = 11855;
    // Animation ID for crafting runes at the altar
    private static final int RUNECRAFT_ANIMATION = 791;

    // All rune item IDs that can be received from ZMI
    private static final Set<Integer> RUNE_IDS = new HashSet<>(Arrays.asList(
        ItemID.AIR_RUNE,
        ItemID.MIND_RUNE,
        ItemID.WATER_RUNE,
        ItemID.EARTH_RUNE,
        ItemID.FIRE_RUNE,
        ItemID.BODY_RUNE,
        ItemID.COSMIC_RUNE,
        ItemID.CHAOS_RUNE,
        ItemID.NATURE_RUNE,
        ItemID.LAW_RUNE,
        ItemID.DEATH_RUNE,
        ItemID.ASTRAL_RUNE,
        ItemID.BLOOD_RUNE,
        ItemID.SOUL_RUNE,
        ItemID.WRATH_RUNE
    ));

    @Inject
    private Client client;

    @Inject
    private ZmiTrackerConfig config;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ZmiTrackerOverlay overlay;

    // Tracks rune quantities before crafting
    private final Map<Integer, Integer> runesBeforeCraft = new HashMap<>();

    // Tracks runes gained in the last lap
    private final Map<Integer, Integer> lastLapRunes = new HashMap<>();

    // Tracks runes gained in the entire session
    private final Map<Integer, Integer> sessionRunes = new HashMap<>();

    private long lastLapValue = 0;
    private long sessionValue = 0;
    private int lapCount = 0;

    private boolean inOuraniaRegion = false;
    private boolean pendingCraftSnapshot = false;

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);
        lastLapRunes.clear();
        sessionRunes.clear();
        runesBeforeCraft.clear();
        lastLapValue = 0;
        sessionValue = 0;
        lapCount = 0;
        log.info("ZMI Rune Tracker started");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        log.info("ZMI Rune Tracker stopped");
    }

    @Provides
    ZmiTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ZmiTrackerConfig.class);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            checkRegion();
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        checkRegion();

        // After animation triggers a snapshot, wait one tick for inventory to update
        if (pendingCraftSnapshot)
        {
            pendingCraftSnapshot = false;
            recordCraftGains();
        }
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        if (!inOuraniaRegion)
        {
            return;
        }

        Actor actor = event.getActor();
        if (!(actor instanceof Player))
        {
            return;
        }

        Player player = (Player) actor;
        if (player != client.getLocalPlayer())
        {
            return;
        }

        if (player.getAnimation() == RUNECRAFT_ANIMATION)
        {
            // Snapshot inventory BEFORE the runes land (current tick)
            snapshotInventoryRunes();
            // We'll read gains on the next tick once inventory has updated
            pendingCraftSnapshot = true;
        }
    }

    private void checkRegion()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            inOuraniaRegion = false;
            return;
        }

        WorldPoint location = client.getLocalPlayer().getWorldLocation();
        int region = location.getRegionID();
        inOuraniaRegion = (region == OURANIA_ALTAR_REGION);
    }

    /**
     * Snapshot current rune quantities from inventory before crafting animation resolves.
     */
    private void snapshotInventoryRunes()
    {
        runesBeforeCraft.clear();
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null)
        {
            return;
        }

        for (Item item : inventory.getItems())
        {
            if (item == null || item.getId() < 0)
            {
                continue;
            }
            if (RUNE_IDS.contains(item.getId()))
            {
                runesBeforeCraft.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }
    }

    /**
     * Called one tick after the craft animation. Compares current inventory vs snapshot
     * to determine what runes were gained this lap.
     */
    private void recordCraftGains()
    {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null)
        {
            return;
        }

        Map<Integer, Integer> runesAfter = new HashMap<>();
        for (Item item : inventory.getItems())
        {
            if (item == null || item.getId() < 0)
            {
                continue;
            }
            if (RUNE_IDS.contains(item.getId()))
            {
                runesAfter.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }

        // Compute gains for this lap
        Map<Integer, Integer> lapGains = new HashMap<>();
        for (int runeId : RUNE_IDS)
        {
            int before = runesBeforeCraft.getOrDefault(runeId, 0);
            int after = runesAfter.getOrDefault(runeId, 0);
            int gained = after - before;
            if (gained > 0)
            {
                lapGains.put(runeId, gained);
            }
        }

        if (lapGains.isEmpty())
        {
            // No runes detected — likely not a ZMI craft (e.g. clicked wrong altar)
            return;
        }

        // Update last-lap map
        lastLapRunes.clear();
        lastLapRunes.putAll(lapGains);

        // Accumulate into session
        lapGains.forEach((id, qty) -> sessionRunes.merge(id, qty, Integer::sum));

        // Compute GP values
        lastLapValue = computeValue(lastLapRunes);
        sessionValue = computeValue(sessionRunes);
        lapCount++;

        log.debug("ZMI lap {} recorded — lap value: {} gp, session value: {} gp",
            lapCount, lastLapValue, sessionValue);
    }

    private long computeValue(Map<Integer, Integer> runes)
    {
        long total = 0;
        for (Map.Entry<Integer, Integer> entry : runes.entrySet())
        {
            int price = itemManager.getItemPrice(entry.getKey());
            total += (long) price * entry.getValue();
        }
        return total;
    }

    // ---- Getters for the overlay ----

    public Map<Integer, Integer> getLastLapRunes()
    {
        return Collections.unmodifiableMap(lastLapRunes);
    }

    public Map<Integer, Integer> getSessionRunes()
    {
        return Collections.unmodifiableMap(sessionRunes);
    }

    public long getLastLapValue()
    {
        return lastLapValue;
    }

    public long getSessionValue()
    {
        return sessionValue;
    }

    public int getLapCount()
    {
        return lapCount;
    }

    public boolean isInOuraniaRegion()
    {
        return inOuraniaRegion;
    }

    public void resetSession()
    {
        lastLapRunes.clear();
        sessionRunes.clear();
        runesBeforeCraft.clear();
        lastLapValue = 0;
        sessionValue = 0;
        lapCount = 0;
    }
}
