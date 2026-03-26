package me.purplertp.plugin.managers;

import me.purplertp.plugin.PurpleRTP;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Pre-generates safe RTP locations asynchronously and stores them in
 * per-world queues so that when a player requests an RTP, a location
 * is returned instantly from the pool with zero search delay.
 *
 * Since the world is pre-generated, chunk loading is extremely fast
 * (chunks are already on disk) and this leverages the server's powerful
 * CPU to keep the pool full at all times.
 */
public class LocationPoolManager {

    private final PurpleRTP plugin;

    // worldName -> queue of ready safe locations
    private final Map<String, ConcurrentLinkedQueue<Location>> pools = new ConcurrentHashMap<>();

    // worldName -> whether the pool is currently being filled
    private final Map<String, AtomicBoolean> filling = new ConcurrentHashMap<>();

    private BukkitTask refillTask;

    public LocationPoolManager(PurpleRTP plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the background refill loop.
     * Runs on a BukkitRunnable that fires periodically (async generation,
     * sync safety-check via chunk load on main thread only when needed).
     */
    public void startPoolFilling() {
        int intervalTicks = plugin.getConfig().getInt("SETTINGS.POOL-REFILL-INTERVAL", 40);

        refillTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            Set<String> worldKeys = plugin.getConfig().getConfigurationSection("WORLD-SETTINGS").getKeys(false);
            int poolSize = plugin.getConfig().getInt("SETTINGS.POOL-SIZE", 20);
            int batch    = plugin.getConfig().getInt("SETTINGS.POOL-FILL-BATCH", 5);

            for (String worldName : worldKeys) {
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;

                ConcurrentLinkedQueue<Location> pool = pools.computeIfAbsent(worldName, k -> new ConcurrentLinkedQueue<>());
                AtomicBoolean isFilling              = filling.computeIfAbsent(worldName, k -> new AtomicBoolean(false));

                int needed = poolSize - pool.size();
                if (needed <= 0) continue;
                if (!isFilling.compareAndSet(false, true)) continue; // another batch is still running

                String path      = "WORLD-SETTINGS." + worldName + ".";
                int maxRadius    = plugin.getConfig().getInt(path + "MAX-RADIUS", 20000);
                int minRadius    = plugin.getConfig().getInt(path + "MIN-RADIUS", 1000);
                int centerX      = plugin.getConfig().getInt(path + "CENTER-X", 0);
                int centerZ      = plugin.getConfig().getInt(path + "CENTER-Z", 0);
                int maxAttempts  = plugin.getConfig().getInt("SETTINGS.MAX-ATTEMPTS", 25);

                int toGenerate = Math.min(needed, batch);
                int generated  = 0;

                for (int i = 0; i < toGenerate * maxAttempts && generated < toGenerate; i++) {
                    Location loc = tryFindSafeLocation(world, centerX, centerZ, minRadius, maxRadius, maxAttempts);
                    if (loc != null) {
                        pool.add(loc);
                        generated++;
                    }
                }

                isFilling.set(false);
            }
        }, 60L, intervalTicks); // first fill starts 3 seconds after enable
    }

    /**
     * Returns a pre-generated safe location for the given world instantly,
     * or null if the pool is empty (very rare — pool refills constantly).
     */
    public Location pollLocation(String worldName) {
        ConcurrentLinkedQueue<Location> pool = pools.get(worldName);
        if (pool == null || pool.isEmpty()) return null;
        return pool.poll();
    }

    /**
     * How many locations are currently ready in the pool for a given world.
     */
    public int poolSize(String worldName) {
        ConcurrentLinkedQueue<Location> pool = pools.get(worldName);
        return pool == null ? 0 : pool.size();
    }

    public void shutdown() {
        if (refillTask != null) refillTask.cancel();
    }

    // -----------------------------------------------------------------------
    // Safe location search (runs fully async — chunks are pre-generated so
    // World#getChunkAt(...).load() is fast since data is already on disk)
    // -----------------------------------------------------------------------

    private Location tryFindSafeLocation(World world, int centerX, int centerZ,
                                         int minRadius, int maxRadius, int maxAttempts) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int offsetX, offsetZ;
            do {
                offsetX = rng.nextInt(-maxRadius, maxRadius + 1);
                offsetZ = rng.nextInt(-maxRadius, maxRadius + 1);
            } while (Math.sqrt((double) offsetX * offsetX + (double) offsetZ * offsetZ) < minRadius);

            int x = centerX + offsetX;
            int z = centerZ + offsetZ;

            // Load the chunk synchronously-ish via Paper async chunk API.
            // Since the world is pre-generated, the chunk is already on disk
            // and this completes very quickly.
            try {
                // We use the blocking form here because this entire method runs async.
                // Paper's chunk system handles this without blocking the main thread.
                Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
                if (!chunk.isLoaded()) {
                    chunk.load();
                }

                int y = world.getHighestBlockYAt(x, z);
                Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
                loc.setYaw(rng.nextFloat() * 360f);

                if (isSafe(loc)) {
                    return loc;
                }
            } catch (Exception e) {
                // Chunk failed to load — skip this coordinate
                plugin.getLogger().log(Level.FINE, "Pool: chunk load failed at " + x + "," + z, e);
            }
        }

        return null;
    }

    private boolean isSafe(Location loc) {
        Block feet   = loc.getBlock();
        Block head   = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);

        if (!feet.getType().isAir())  return false;
        if (!head.getType().isAir())  return false;
        if (!ground.getType().isSolid()) return false;

        Material groundType = ground.getType();
        if (groundType == Material.WATER) return false;
        if (groundType == Material.LAVA)  return false;
        if (groundType == Material.FIRE)  return false;
        if (groundType == Material.CACTUS) return false;

        // Don't spawn above the void
        if (loc.getY() <= loc.getWorld().getMinHeight() + 1) return false;

        return true;
    }
}
