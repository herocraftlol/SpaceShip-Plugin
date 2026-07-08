package com.spaceship.plugin.gui;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Écoute les clics dans le GUI d'arènes et effectue les actions correspondantes :
 * - Clic sur une arène → /ss join <nom>
 * - Clic sur le bouton aléatoire → /ss joinrandom
 */
public class ArenaGUIListener implements Listener {

    private final SpaceShipPlugin plugin;
    private final ArenaGUI arenaGUI;

    public ArenaGUIListener(SpaceShipPlugin plugin, ArenaGUI arenaGUI) {
        this.plugin = plugin;
        this.arenaGUI = arenaGUI;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Vérifier que c'est bien notre GUI (par le titre)
        if (!ArenaGUI.GUI_TITLE.equals(event.getView().getTitle())) return;

        // Annuler toujours le clic pour éviter de prendre des items
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        int slot = event.getRawSlot();

        // Clic sur le bouton "arène aléatoire" (ligne 6)
        if (ArenaGUI.isRandomButton(slot)) {
            player.closeInventory();
            GameManager best = plugin.getArenaManager().findBestArenaForRandomJoin();
            if (best == null) {
                MessageUtil.send(player, "&cAucune arène disponible pour le moment.");
                return;
            }
            tryJoin(player, best);
            return;
        }

        // Clic sur une arène spécifique (lignes 1-5)
        String arenaName = arenaGUI.getArenaNameAt(slot);
        if (arenaName == null) return; // Slot vide / filler

        GameManager gm = plugin.getArenaManager().get(arenaName);
        if (gm == null) {
            MessageUtil.send(player, "&cCette arène n'existe plus.");
            player.closeInventory();
            return;
        }

        player.closeInventory();
        tryJoin(player, gm);
    }

    /**
     * Tente de faire rejoindre le joueur dans l'arène donnée,
     * en déléguant à la logique existante de GameManager.
     */
    private void tryJoin(Player player, GameManager gm) {
        if (!gm.getArena().isFullyConfigured()) {
            MessageUtil.send(player, "&cCette arène n'est pas encore configurée.");
            return;
        }

        // Vérifier si le joueur est déjà dans une arène
        GameManager current = plugin.getArenaManager().findArenaOf(player);
        if (current != null) {
            if (current.getName().equals(gm.getName())) {
                MessageUtil.send(player, "&cTu es déjà dans cette arène !");
            } else {
                MessageUtil.send(player, "&cTu es déjà dans une arène (&7" + current.getName() + "&c). Fais &7/ss leave &cpour en partir.");
            }
            return;
        }

        // Déléguer au GameManager
        gm.addPlayer(player);
    }
}
