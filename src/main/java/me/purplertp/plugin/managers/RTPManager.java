package me.purplertp.plugin.managers;

import me.purplertp.plugin.PurpleRTP;
import me.purplertp.plugin.utils.MessageUtils;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the actual RTP flow.
 *
 * With pool support the flow is:
 *   1. Validate (enabled, cooldown, max players, world)
 *   2. Poll a pre-generated location from LocationPoolManager instantly
 *   3. If pool is empty (rare), fall back to on-demand async search
 *   4. Run the countdown + teleport on the main thread
 */
public class RTPManager {

    private final PurpleRTP plugin;
    private final Set<UUID> inRtp = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public RTPManager(PurpleRTP plugin) {
        this.plugin = plugin;
    }

    public Set<UUID> getPlayersInRtp() { return inRtp; }

    public boolean isInRtp(UUID uuid) { return inRtp.contains(uuid); }

    public void cancelRtp(UUID uuid) { inRtp.remove(uuid); }

    // -----------------------------------------------------------------------

    public void randomTeleport(Player player, String worldName) {
        // --- Guards ---
        if (!plugin.getConfig().getBoolean("ENABLED", true)) {
            actionbar(player, plugin.getConfig().getString("MESSAGES.DISABLED", "&cRTP is disabled."));
            return;
        }

        int maxPlayers = plugin.getConfig().getInt("SETTINGS.PLAYERS-IN-RTP", 150);
        if (inRtp.size() >= maxPlayers) {
            actionbar(player, plugin.getConfig().getString("MESSAGES.MAX-PLAYERS", "&cToo many players using RTP."));
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            actionbar(player, plugin.getConfig().getString("MESSAGES.WORLD-NOT-EXIST", "&cWorld not found."));
            return;
        }

        if (!player.hasPermission("purplertp.bypass.cooldown")) {
            CooldownManager cm = plugin.getCooldownManager();
            if (cm.isOnCooldown(player.getUniqueId(), worldName)) {
                long remaining = cm.getRemainingCooldown(player.getUniqueId(), worldName);
                String msg = plugin.getConfig().getString("MESSAGES.COOLDOWN", "&cWait {remaining}s.")
                        .replace("{remaining}", String.valueOf(remaining));
                actionbar(player, msg);
                return;
            }
        }

        String path     = "WORLD-SETTINGS." + worldName + ".";
        int maxRadius   = plugin.getConfig().getInt(path + "MAX-RADIUS", 20000);
        int minRadius   = plugin.getConfig().getInt(path + "MIN-RADIUS", 1000);
        int centerX     = plugin.getConfig().getInt(path + "CENTER-X", 0);
        int centerZ     = plugin.getConfig().getInt(path + "CENTER-Z", 0);
        int cooldown    = plugin.getConfig().getInt(path + "COOLDOWN", 0);
        int maxAttempts = plugin.getConfig().getInt("SETTINGS.MAX-ATTEMPTS", 25);
        int countdown   = plugin.getConfig().getInt("SETTINGS.COUNTDOWN", 5);

        inRtp.add(player.getUniqueId());

        // --- Try pool first ---
        Location poolLoc = plugin.getLocationPoolManager().pollLocation(worldName);

        if (poolLoc != null) {
            // Instant! Location was pre-generated — just run the countdown.
            startCountdown(player, poolLoc, worldName, cooldown, countdown);
        } else {
            // Pool empty (rare on startup) — fall back to async search.
            actionbar(player, plugin.getConfig().getString("MESSAGES.SEARCHING", "&dSearching..."));

            BukkitRunnable searchingTicker = new BukkitRunnable() {
                @Override public void run() {
                    if (!player.isOnline() || !inRtp.contains(player.getUniqueId())) { cancel(); return; }
                    actionbar(player, plugin.getConfig().getString("MESSAGES.SEARCHING", "&dSearching..."));
                }
            };
            searchingTicker.runTaskTimer(plugin, 0L, 20L);

            double startX = player.getLocation().getX();
            double startZ = player.getLocation().getZ();
            Location startLoc = player.getLocation().clone();

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Location found = findSafeLocationSync(world, centerX, centerZ, minRadius, maxRadius, maxAttempts);
                searchingTicker.cancel();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || !inRtp.contains(player.getUniqueId())) return;

                    if (found == null) {
                        inRtp.remove(player.getUniqueId());
                        String msg = plugin.getConfig().getString("MESSAGES.MAX-ATTEMPTS", "&cNo safe location found.")
                                .replace("{attempts}", String.valueOf(maxAttempts));
                        actionbar(player, msg);
                        return;
                    }

                    startCountdown(player, found, worldName, cooldown, countdown);
                });
            });
        }
    }

    /**
     * Runs the countdown timer then teleports the player.
     * Cancels if the player moves more than 0.5 blocks.
     */
    private void startCountdown(Player player, Location safeLoc, String worldName, int cooldown, int countdown) {
        double startX = player.getLocation().getX();
        double startZ = player.getLocation().getZ();
        Location startLoc = player.getLocation().clone();

        new BukkitRunnable() {
            int secondsLeft = countdown;

            @Override
            public void run() {
                if (!player.isOnline() || !inRtp.contains(player.getUniqueId())) {
                    cancel();
                    return;
                }

                // Movement check
                double dx = Math.abs(player.getLocation().getX() - startX);
                double dz = Math.abs(player.getLocation().getZ() - startZ);
                if (dx > 0.333333 || dz > 0.333333) {
                    inRtp.remove(player.getUniqueId());
                    cancel();
                    actionbar(player, "&cTeleport cancelled &7— &cdon't move!");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    return;
                }

                if (secondsLeft > 0) {
                    actionbar(player, "&fTeleporting in &b" + secondsLeft + "&f...");
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);

                    // Particles around the player
                    startLoc.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                            startLoc.clone().add(0, 1, 0), 8,
                            0.3, 0.5, 0.3, 0.05);
                    secondsLeft--;
                } else {
                    // Pre-load chunks around destination async, THEN teleport
                    cancel();

                    int chunkX = safeLoc.getBlockX() >> 4;
                    int chunkZ = safeLoc.getBlockZ() >> 4;
                    World dest = safeLoc.getWorld();

                    // Load a 3x3 grid of chunks around the destination
                    List<CompletableFuture<Chunk>> futures = new ArrayList<>();
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            futures.add(dest.getChunkAtAsync(chunkX + dx, chunkZ + dz));
                        }
                    }

                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline() || !inRtp.contains(player.getUniqueId())) return;
                            inRtp.remove(player.getUniqueId());
                            player.teleport(safeLoc);
                            player.playSound(safeLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                            player.playSound(safeLoc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                            if (cooldown > 0) {
                                plugin.getCooldownManager().setCooldown(player.getUniqueId(), worldName, cooldown);
                            }
                        }));
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    // -----------------------------------------------------------------------
    // Fallback synchronous-style search (runs async, same logic as pool fill)
    // -----------------------------------------------------------------------

    private Location findSafeLocationSync(World world, int centerX, int centerZ,
                                          int minRadius, int maxRadius, int maxAttempts) {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int offsetX, offsetZ;
            do {
                offsetX = rng.nextInt(-maxRadius, maxRadius + 1);
                offsetZ = rng.nextInt(-maxRadius, maxRadius + 1);
            } while (Math.sqrt((double) offsetX * offsetX + (double) offsetZ * offsetZ) < minRadius);

            int x = centerX + offsetX;
            int z = centerZ + offsetZ;

            try {
                Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
                if (!chunk.isLoaded()) chunk.load();

                int y = world.getHighestBlockYAt(x, z);
                Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
                loc.setYaw(rng.nextFloat() * 360f);

                if (isSafe(loc)) return loc;
            } catch (Exception ignored) {}
        }

        return null;
    }

    private boolean isSafe(Location loc) {
        org.bukkit.block.Block feet   = loc.getBlock();
        org.bukkit.block.Block head   = feet.getRelative(0, 1, 0);
        org.bukkit.block.Block ground = feet.getRelative(0, -1, 0);

        if (!feet.getType().isAir())        return false;
        if (!head.getType().isAir())        return false;
        if (!ground.getType().isSolid())    return false;

        Material g = ground.getType();
        if (g == Material.WATER)  return false;
        if (g == Material.LAVA)   return false;
        if (g == Material.FIRE)   return false;
        if (g == Material.CACTUS) return false;

        if (loc.getY() <= loc.getWorld().getMinHeight() + 1) return false;

        return true;
    }

    // -----------------------------------------------------------------------

    private void actionbar(Player player, String message) {
        player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                new TextComponent(MessageUtils.format(message))
        );
    }
}
