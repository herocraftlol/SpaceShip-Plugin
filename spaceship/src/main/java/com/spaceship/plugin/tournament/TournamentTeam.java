package com.spaceship.plugin.tournament;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Une équipe inscrite à un tournoi SpaceShip : un groupe de 1 à N joueurs (selon la
 * taille d'équipe du tournoi) identifié par un tag choisi par les joueurs (ou par le
 * pseudo du joueur seul, en solo).
 */
public class TournamentTeam {

    private final String tag;
    private final List<UUID> members = new ArrayList<>();
    private final List<String> memberNames = new ArrayList<>();

    public TournamentTeam(String tag) {
        this.tag = tag;
    }

    public static TournamentTeam soloOf(UUID uuid, String name) {
        TournamentTeam team = new TournamentTeam(name);
        team.members.add(uuid);
        team.memberNames.add(name);
        return team;
    }

    public String getTag() {
        return tag;
    }

    public List<UUID> getMembers() {
        return members;
    }

    public List<String> getMemberNames() {
        return memberNames;
    }

    public void addMember(UUID uuid, String name) {
        members.add(uuid);
        memberNames.add(name);
    }

    public boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    public String getDisplayName() {
        if (members.size() <= 1) {
            return ChatColor.AQUA + tag;
        }
        return ChatColor.AQUA + tag + ChatColor.GRAY + " (" + String.join(", ", memberNames) + ")";
    }

    @Override
    public String toString() {
        return tag;
    }
}
