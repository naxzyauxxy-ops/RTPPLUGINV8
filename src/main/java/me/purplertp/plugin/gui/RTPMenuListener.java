package me.purplertp.plugin.gui;

import me.purplertp.plugin.PurpleRTP;
import me.purplertp.plugin.utils.MessageUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class RTPMenuListener implements Listener {

    private final PurpleRTP plugin;

    public RTPMenuListener(PurpleRTP plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = MessageUtils.format(plugin.getConfig().getString("RTP-MENU.TITLE", "&8Random Teleport"));
        if (!event.getView().getTitle().equals(title)) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;

        // Match clicked slot to a configured button
        for (String key : plugin.getConfig().getConfigurationSection("RTP-MENU.BUTTONS").getKeys(false)) {
            String base = "RTP-MENU.BUTTONS." + key + ".";
            if (!plugin.getConfig().getBoolean(base + "ENABLED", true)) continue;

            int slot = plugin.getConfig().getInt(base + "SLOT", -1);
            if (slot != event.getRawSlot()) continue;

            String worldName = plugin.getConfig().getString(base + "WORLD", "world");
            player.closeInventory();

            if (plugin.getRtpManager().isInRtp(player.getUniqueId())) {
                player.sendActionBar(MessageUtils.format("&cYou are already teleporting!"));
                return;
            }

            plugin.getRtpManager().randomTeleport(player, worldName);
            return;
        }
    }
}
