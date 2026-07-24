package com.spaceship.plugin.game;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Fabrique et applique le kit de départ SpaceShip :
 * Pendant la partie :
 * - Slot 0 : épée en fer, incassable
 * - Slot 1 : pioche en fer, incassable
 * - Slot 2 : pomme dorée (regivée à chaque mort / point marqué)
 * - Slot 8 : blocs (grès lisse) en offhand
 * Pendant le lobby :
 * - Slot 0 : diamant admin (pour forcer le démarrage)
 * - Slot 2 : sel d'équipe (terracotta coloré pour changer d'équipe)
 * - Offhand : 64 grès lisse
 * Armure en cuir complète teintée selon l'équipe, incassable, avec le nom de l'équipe gravé
 */
public final class KitManager {

    // Slots pour le lobby
    public static final int FORCESTART_SLOT = 0;
    public static final int TEAM_SELECT_SLOT = 2;
    public static final int LEAVE_SLOT = 4;

    // Slot pour le mode spectateur
    public static final int SPECTATOR_LEAVE_SLOT = 8;
    
    // Slots pour le jeu (pas de décalage car le lobby utilise des slots différents)
    private static final int SWORD_SLOT = 0;
    private static final int PICKAXE_SLOT = 1;
    private static final int GAPPLE_SLOT = 2;

    public static final Material OFFHAND_BLOCK_MATERIAL = Material.SMOOTH_SANDSTONE;
    public static final int OFFHAND_BLOCK_AMOUNT = 64;

    /**
     * Clé persistante utilisée pour identifier de façon fiable l'item "forcer le démarrage"
     * dans un clic, plutôt que de comparer un nom affiché (fragile face aux traductions/styles).
     */
    private static NamespacedKey forceStartKey;
    
    /**
     * Clé persistante pour identifier l'item de sélection d'équipe.
     */
    private static NamespacedKey teamSelectorKey;

    /**
     * Clé persistante pour identifier l'item "quitter la partie".
     */
    private static NamespacedKey leaveKey;

    /** Clé persistante pour identifier l'item "quitter le mode spectateur". */
    private static NamespacedKey spectatorLeaveKey;

    private KitManager() {
    }

    public static void init(org.bukkit.plugin.Plugin plugin) {
        forceStartKey = new NamespacedKey(plugin, "force_start_item");
        teamSelectorKey = new NamespacedKey(plugin, "team_selector_item");
        leaveKey = new NamespacedKey(plugin, "leave_item");
        spectatorLeaveKey = new NamespacedKey(plugin, "spectator_leave_item");
    }

    /**
     * Crée l'item diamant donné uniquement aux admins dans le lobby, qui permet de
     * forcer le démarrage immédiat de la partie en un clic (raccourci visuel pour /ss start).
     */
    public static ItemStack createForceStartItem() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Forcer le démarrage");
            meta.setLore(java.util.List.of(ChatColor.GRAY + "Clique pour lancer la partie immédiatement"));
            meta.getPersistentDataContainer().set(forceStartKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Crée l'item de sélection d'équipe (terracotta coloré).
     * Utilisé au lobby pour permettre aux joueurs de changer d'équipe.
     */
    public static ItemStack createTeamSelectorItem(Team team) {
        Material material = team == Team.BLACK ? Material.BLACK_TERRACOTTA : Material.WHITE_TERRACOTTA;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String teamName = team.getColoredName();
            meta.setDisplayName(teamName + ChatColor.WHITE + " - Sélection d'équipe");
            meta.setLore(java.util.List.of(
                ChatColor.GRAY + "Clique pour rejoindre cette équipe",
                ChatColor.GRAY + "Équipe actuelle: " + teamName
            ));
            meta.getPersistentDataContainer().set(teamSelectorKey, PersistentDataType.STRING, team.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Met à jour la couleur de l'item de sélection d'équipe selon la nouvelle équipe.
     */
    public static void updateTeamSelectorItem(ItemStack item, Team team) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        
        Material material = team == Team.BLACK ? Material.BLACK_TERRACOTTA : Material.WHITE_TERRACOTTA;
        item.setType(material);
        
        String teamName = team.getColoredName();
        meta.setDisplayName(teamName + ChatColor.WHITE + " - Sélection d'équipe");
        meta.setLore(java.util.List.of(
            ChatColor.GRAY + "Clique pour rejoindre cette équipe",
            ChatColor.GRAY + "Équipe actuelle: " + teamName
        ));
        meta.getPersistentDataContainer().set(teamSelectorKey, PersistentDataType.STRING, team.name());
        item.setItemMeta(meta);
    }

    /**
     * Détermine si l'ItemStack donné est bien l'item "forcer le démarrage" (et non un diamant
     * normal que le joueur aurait par ailleurs).
     */
    public static boolean isForceStartItem(ItemStack item) {
        if (item == null || item.getType() != Material.DIAMOND || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(forceStartKey, PersistentDataType.BYTE);
    }

    /**
     * Détermine si l'ItemStack donné est l'item de sélection d'équipe.
     */
    public static boolean isTeamSelectorItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(teamSelectorKey, PersistentDataType.STRING);
    }

    /**
     * Récupère l'équipe sélectionnée par l'item de sélection d'équipe.
     */
    public static Team getTeamFromSelectorItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        
        String teamName = meta.getPersistentDataContainer().get(teamSelectorKey, PersistentDataType.STRING);
        if (teamName == null) return null;
        
        try {
            return Team.valueOf(teamName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Crée l'item "quitter la partie" (barrier block) placé en slot 8 du lobby.
     * Non droppable et non déplaçable.
     */
    public static ItemStack createLeaveItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Quitter la partie");
            meta.setLore(java.util.List.of(ChatColor.GRAY + "Clique pour faire /ss leave"));
            meta.getPersistentDataContainer().set(leaveKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Détermine si l'ItemStack donné est l'item "quitter la partie".
     */
    public static boolean isLeaveItem(ItemStack item) {
        if (item == null || item.getType() != Material.BARRIER || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(leaveKey, PersistentDataType.BYTE);
    }

    /**
     * Crée l'item "quitter le mode spectateur" (barrier block) placé en slot 8 de l'inventaire
     * spectateur. Un clic déclenche directement /ss unspectate, comme raccourci visuel.
     */
    public static ItemStack createSpectatorLeaveItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Quitter le mode spectateur");
            meta.setLore(java.util.List.of(ChatColor.GRAY + "Clique pour faire /ss unspectate"));
            meta.getPersistentDataContainer().set(spectatorLeaveKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Détermine si l'ItemStack donné est l'item "quitter le mode spectateur". */
    public static boolean isSpectatorLeaveItem(ItemStack item) {
        if (item == null || item.getType() != Material.BARRIER || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(spectatorLeaveKey, PersistentDataType.BYTE);
    }

    /**
     * Équipe entièrement un joueur avec le kit de départ complet (armure + outils + pomme + grès).
     * Utilisé au début de partie et à chaque round reset.
     */
    public static void giveFullKit(org.bukkit.entity.Player player, Team team) {
        PlayerInventory inv = player.getInventory();
        inv.clear();

        inv.setItem(SWORD_SLOT, makeUnbreakable(new ItemStack(Material.IRON_SWORD)));
        inv.setItem(PICKAXE_SLOT, makeUnbreakable(new ItemStack(Material.IRON_PICKAXE)));
        inv.setItem(GAPPLE_SLOT, new ItemStack(Material.GOLDEN_APPLE, 1));

        inv.setItemInOffHand(new ItemStack(OFFHAND_BLOCK_MATERIAL, OFFHAND_BLOCK_AMOUNT));

        equipArmor(player, team);
    }

    /**
     * Redonne uniquement la pomme dorée (slot 3), sans toucher au reste de l'équipement.
     * Utilisé à chaque mort et à chaque point marqué.
     */
    public static void regiveGoldenApple(org.bukkit.entity.Player player) {
        player.getInventory().setItem(GAPPLE_SLOT, new ItemStack(Material.GOLDEN_APPLE, 1));
    }

    /**
     * Vérifie que le joueur a bien 64 grès lisse en offhand, et complète si besoin.
     * Le joueur peut poser/casser ce bloc normalement ; on le réapprovisionne juste à 64.
     */
    public static void replenishOffhandBlocks(org.bukkit.entity.Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand.getType() != OFFHAND_BLOCK_MATERIAL) {
            player.getInventory().setItemInOffHand(new ItemStack(OFFHAND_BLOCK_MATERIAL, OFFHAND_BLOCK_AMOUNT));
        } else if (offhand.getAmount() < OFFHAND_BLOCK_AMOUNT) {
            offhand.setAmount(OFFHAND_BLOCK_AMOUNT);
            player.getInventory().setItemInOffHand(offhand);
        }
    }

    public static void equipArmor(org.bukkit.entity.Player player, Team team) {
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        ItemStack chestplate = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemStack leggings = new ItemStack(Material.LEATHER_LEGGINGS);
        ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);

        helmet = dyeAndLock(helmet, team);
        chestplate = dyeAndLock(chestplate, team);
        leggings = dyeAndLock(leggings, team);
        boots = dyeAndLock(boots, team);

        PlayerInventory inv = player.getInventory();
        inv.setHelmet(helmet);
        inv.setChestplate(chestplate);
        inv.setLeggings(leggings);
        inv.setBoots(boots);
    }

    private static ItemStack dyeAndLock(ItemStack armorPiece, Team team) {
        if (armorPiece.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(team.getArmorColor());
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            armorPiece.setItemMeta(meta);
        }
        return armorPiece;
    }

    private static ItemStack makeUnbreakable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }
        return item;
    }
}
