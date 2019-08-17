package net.porillo.effect.negative;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.Getter;
import lombok.Setter;
import net.porillo.GlobalWarming;
import net.porillo.effect.ClimateData;
import net.porillo.effect.api.ClimateEffectType;
import net.porillo.effect.api.ListenerClimateEffect;
import net.porillo.engine.ClimateEngine;
import net.porillo.engine.api.Distribution;
import net.porillo.engine.api.WorldClimateEngine;
import net.porillo.util.ChunkSorter;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.bukkit.Material.*;

/**
 * Sea-level rise
 * - Two asynchronous, repeating tasks
 * 1) Add jobs to the stack (once the stack is empty)
 * 2) Apply any required changes
 * <p>
 * - Sea level will rise with the temperature
 * - Raised blocks are tagged with meta data
 * - When sea levels lower, the tagged blocks are reset
 * - Will not dry out lakes, rivers, irrigation, machines, etc.
 * - Considerations made for growing kelp, player changes, and
 * other events: blocks that drop, etc.
 */
@ClimateData(type = ClimateEffectType.SEA_LEVEL_RISE)
public class SeaLevelRise extends ListenerClimateEffect {

    private static final MetadataValue BLOCK_TAG = new FixedMetadataValue(GlobalWarming.getInstance(), true);
    private static final Set<Biome> unimpactedBiomes = new HashSet<>();
    private static final Set<Material> replaceOnRise = new HashSet<>();
    private static final Set<Material> replaceOnFall = new HashSet<>();
    private static final String SEALEVEL_BLOCK = "S";

    private final ConcurrentLinkedQueue<ChunkSnapshot> requestQueue;
    @Getter private Distribution seaMap;
    @Getter @Setter private boolean isOverride;
    private int baseSeaLevel, chunkTicks, chunksPerPeriod, queueTicks, maxTemperature;

    static {
        for (Biome biome : Biome.values()) {
            String name = biome.name().toLowerCase();
            if (name.contains("mountains") || name.contains("hills")) {
                unimpactedBiomes.add(biome);
            }
        }

        replaceOnRise.add(AIR);
        replaceOnRise.add(TALL_GRASS);
        replaceOnRise.add(GRASS);
        replaceOnRise.add(LILY_PAD);
        replaceOnRise.add(LILAC);
        replaceOnRise.add(SUGAR_CANE);
        replaceOnRise.add(FERN);
        replaceOnRise.add(ALLIUM);
        replaceOnRise.add(VINE);
        
        for (Material material : values()) {
            String mat = material.name().toLowerCase();
            if (mat.contains("flower") || mat.contains("sapling") || mat.contains("seed")) {
                replaceOnRise.add(material);
            }
        }

        replaceOnFall.add(WATER);
        replaceOnFall.add(SEAGRASS);
        replaceOnFall.add(TALL_SEAGRASS);
        replaceOnFall.add(KELP_PLANT);
        replaceOnFall.add(KELP);
    }

    public SeaLevelRise() {
        isOverride = false;
        requestQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * Update the queue with loaded-chunks one the queue is empty
     */
    private void startQueueLoader() {
        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(
                GlobalWarming.getInstance(),
                () -> {
                    synchronized (requestQueue) {
                        if (requestQueue.isEmpty()) {
                            for (World world : Bukkit.getWorlds()) {
                                if (world.getEnvironment() != World.Environment.NORMAL) {
                                    continue;
                                }
                                final WorldClimateEngine wce = ClimateEngine.getInstance().getClimateEngine(world.getUID());

                                if (wce != null && wce.isEffectEnabled(ClimateEffectType.SEA_LEVEL_RISE)) {
                                    for (Chunk chunk : ChunkSorter.sortByDistance(world.getLoadedChunks(), world.getPlayers())) {
                                        requestQueue.add(chunk.getChunkSnapshot(false, true, false));
                                    }
                                }
                            }
                        }
                    }
                }, 0L, queueTicks);
    }

    /**
     * Update the chunks when requests are available
     */
    private void debounceChunkUpdates() {
        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(
                GlobalWarming.getInstance(),
                () -> {
                    //Make a copy of the items removed from the queue:
                    // - Synchronized to temporarily prevent threaded additions
                    Queue<ChunkSnapshot> chunkSnapshots = null;
                    synchronized (this) {
                        if (!requestQueue.isEmpty()) {
                            chunkSnapshots = new ConcurrentLinkedQueue<>();
                            while (chunkSnapshots.size() < chunksPerPeriod && !requestQueue.isEmpty()) {
                                chunkSnapshots.add(requestQueue.poll());
                            }
                        }
                    }

                    //Process blocks in each chunk:
                    if (chunkSnapshots != null) {
                        for (ChunkSnapshot chunkSnapshot : chunkSnapshots) {
                            updateChunk(chunkSnapshot);
                        }
                    }
                }, 0L, chunkTicks);
    }

    /**
     * Updates the sea level for the given chunk (up or down)
     * - BlockFromToEvent is the key to making this work:
     * - RISING SEAS: helps identify which blocks were created and which were pre-existing lakes, rivers, irrigation, etc.
     * - EBBING SEAS: prevents pending chunks from spilling into cleared chunks
     */
    private void updateChunk(ChunkSnapshot snapshot) {
        //Setup:
        World world = Bukkit.getWorld(snapshot.getWorldName());
        WorldClimateEngine climateEngine = ClimateEngine.getInstance().getClimateEngine(world.getUID());
        final int minSeaLevel = Math.max(world.getSeaLevel() - 1, baseSeaLevel);
        final int deltaSeaLevel = (int) seaMap.getValue(climateEngine.getTemperature());
        final int customSeaLevel = minSeaLevel + deltaSeaLevel;
        final int maxHeight = minSeaLevel + (int) seaMap.getValue(maxTemperature);

        //Scan chunk-blocks within the sea-level's range:
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (unimpactedBiomes.contains(snapshot.getBiome(x, z))) {
                    continue;
                }
                for (int y = minSeaLevel; y < maxHeight; y++) {
                    //--------------------------------------------------------------------------------------------------
                    //  TYPE  |  SEALEVEL  |  REPAIR  | TASK
                    //--------------------------------------------------------------------------------------------------
                    //    W   |   (ABOVE)  |     T    | [1] Set to AIR, clear tag
                    //    W   |   (ABOVE)  |     F    | [2] If owner, set to air, clear tag
                    //    W   |   [BELOW]  |     T    | [3] If not base-sea-level, set to air, clear tag
                    //    W   |   [BELOW]  |     F    | [4] If owner and sea-level == 0, set to air, clear tag
                    //    A   |   (ABOVE)  |     T    | Ignore
                    //    A   |   (ABOVE)  |     F    | Ignore
                    //    A   |   [BELOW]  |     T    | Ignore
                    //    A   |   [BELOW]  |     F    | [5] If sea-level > 0, set to water, add tag
                    //--------------------------------------------------------------------------------------------------
                    Block block = world.getChunkAt(snapshot.getX(), snapshot.getZ()).getBlock(x, y, z);
                    if (replaceOnRise.contains(block.getType())) {
                        if (deltaSeaLevel > 0 && y <= customSeaLevel && !isOverride) {
                            //Set any air-blocks below-and-at sea-level to water
                            //as long as the sea-level is above normal [5]
                            block.setType(WATER, true);
                            block.setMetadata(SEALEVEL_BLOCK, BLOCK_TAG);
                        }
                    } else if (replaceOnFall.contains(block.getType())) {
                        if ((isOverride && y != minSeaLevel) ||
                                (block.hasMetadata(SEALEVEL_BLOCK) && (y > customSeaLevel || deltaSeaLevel == 0))) {
                            //Set water-to-air when:
                            // - Repairing, except the base-sea-level [1, 3]
                            // - Owner of block above sea-level [2]
                            // - Owner of block below sea-level when sea-level is normal [4]
                            block.setType(AIR, true);
                            block.removeMetadata(SEALEVEL_BLOCK, GlobalWarming.getInstance());
                        }
                    } else {
                        //Release ownership of altered blocks:
                        block.removeMetadata(SEALEVEL_BLOCK, GlobalWarming.getInstance());
                    }
                }
            }
        }
    }

    /**
     * Replacing sea-level-blocks will remove them from the tracked set
     */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        event.getBlock().removeMetadata(SEALEVEL_BLOCK, GlobalWarming.getInstance());
    }

    /**
     * Emptying a bucket (water or lava) will remove the adjacent block
     * from the tracked set
     */
    @EventHandler
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        Block adjacent = event.getBlockClicked().getRelative(event.getBlockFace());
        adjacent.removeMetadata(SEALEVEL_BLOCK, GlobalWarming.getInstance());
    }

    /**
     * Only allow sea-level blocks to flow if they are below the custom sea-level
     * - Track any new blocks originating from sea-level blocks
     */
    @EventHandler
    public void onBlockFromToEvent(BlockFromToEvent event) {
        if (event.getBlock().hasMetadata(SEALEVEL_BLOCK)) {
            boolean isWaterFixed = isOverride;
            if (!isWaterFixed) {
                final World world = event.getBlock().getWorld();
                final WorldClimateEngine climateEngine = ClimateEngine.getInstance().getClimateEngine(world.getUID());
                final int baseLevel = Math.max(baseSeaLevel, world.getSeaLevel()) - 1;
                final int deltaSeaLevel = (int) seaMap.getValue(climateEngine.getTemperature());
                final int customSeaLevel = baseLevel + deltaSeaLevel;
                final int maxHeight = baseLevel + (int) seaMap.getValue(maxTemperature);
                isWaterFixed = event.getBlock().getY() == event.getToBlock().getY() &&
                        event.getBlock().getY() > customSeaLevel &&
                        event.getBlock().getY() < maxHeight;
            }

            if (!isWaterFixed) {
                isWaterFixed = !isSameChunk(event.getBlock().getChunk(), event.getToBlock().getChunk());
            }

            if (isWaterFixed) {
                event.setCancelled(true);
            } else {
                event.getToBlock().setMetadata(SEALEVEL_BLOCK, BLOCK_TAG);
            }
        }
    }

    private boolean isSameChunk(Chunk one, Chunk two) {
        return one.getX() == two.getX() && one.getZ() == two.getZ();
    }

    /**
     * Load the sea-level distribution model
     */
    @Override
    public void setJsonModel(JsonObject jsonModel) {
        super.setJsonModel(jsonModel);
        seaMap = GlobalWarming.getInstance().getGson().fromJson(
                jsonModel.get("distribution"),
                new TypeToken<Distribution>() {
                }.getType());
        // Compatibility
        try {
            seaMap = new Distribution(seaMap.temp, seaMap.fitness);
            maxTemperature = Collections.max(seaMap.getX()).intValue();
            chunkTicks = jsonModel.get("chunk-ticks").getAsInt();
            chunksPerPeriod = jsonModel.get("chunks-per-period").getAsInt();
            queueTicks = jsonModel.get("queue-ticks").getAsInt();

            if (jsonModel.has("base-sea-level")) {
                baseSeaLevel = jsonModel.get("base-sea-level").getAsInt();
            }
            startQueueLoader();
            debounceChunkUpdates();
        } catch (Exception ex) {
            unregister();
        }
    }
}
