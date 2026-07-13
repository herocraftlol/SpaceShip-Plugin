# 🚀 SpaceShip Plugin

![Paper 1.21.1](https://img.shields.io/badge/Paper-1.21.1-blue)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Maven](https://img.shields.io/badge/Maven-3.9.6-green)

Plugin de minijeu SpaceShip pour Minecraft Paper - Un jeu multi-zones **Noir vs Blanc** en ligne façon vaisseau spatial. Percez le vaisseau ennemi zone par zone jusqu'à son cœur pour gagner!

---

## 🎮 Description

SpaceShip est un plugin de minijeu compétitif inspiré de HikaBrain mais en mode multi-zones en ligne. Deux équipes (**Noir vs Blanc**) s'affrontent dans un vaisseau spatial avec plusieurs zones à capturer. L'objectif est de progresser zone par zone jusqu'à atteindre le cœur du vaisseau ennemi pour remporter la victoire!

## ✨ Fonctionnalités

### 🏟️ Système d'Arènes
- Création et gestion de multiples arènes de jeu
- Configuration flexible du nombre de zones par arène
- Systèmes de points de spawn personnalisés
- Zone de lobby configurable

### ⚔️ Mode Équipe
- Système d'équipes **Noir vs Blanc** avec captures de zones
- Progression linéaire à travers les zones
- Protection des zones capturées
- Objectif final: atteindre le cœur ennemi

### 🎯 Système de Goals (Mid Goals)
- **Buts séparés** : Mid Goal pour entrer, Base Goals pour pousser
- **Pushback progressif** : Repousser l'ennemi le fait reculer d'une zone
- Commandes : `/ss setgoal <map> <roomId> <black|white> <pos1|pos2>` (ex: `mid`, `base1black`, `base1white`)

### 📊 Stats & Classements
- Suivi détaillé des statistiques par joueur
- Hologrammes de leaderboards en temps réel
- Classements par kills, victoires, et plus
- Catégories multiples de stats

### 🖥️ Interface GUI
- Menus interactifs pour la sélection d'arène
- Interface de visualisation des stats
- Sélection d'équipe intuitive

### 🎯 Commandes Complètes
- `/ss` - Commande principale avec de nombreuses sous-commandes
- `/ssarenas` - Ouverture du GUI de sélection d'arène

## 📋 Prérequis

- **Serveur:** Paper ou Paper-based (Folia, Purpur, etc.)
- **Version:** Minecraft 1.21.1
- **Java:** JDK 21 ou supérieur
- **Dépendances:** Aucune (100% autonome avec shading)

## 📥 Installation

1. Téléchargez la dernière version depuis la [page des releases](https://github.com/herocraftlol/SpaceShip-Plugin/releases)
2. Placez le fichier `SpaceShip-X.X.X.jar` dans le dossier `plugins` de votre serveur
3. Redémarrez le serveur
4. Configurez les arènes avec les commandes détaillées ci-dessous

## 🔧 Configuration

### Permissions

| Permission | Description | Défaut |
|------------|-------------|--------|
| `spaceship.admin` | Permission pour administrer le jeu (setup, hologrammes, reset) | Op |
| `spaceship.play` | Permission pour jouer à SpaceShip | Tous les joueurs |

### Commandes

#### Commande Principale `/ss`

| Sous-commande | Description |
|---------------|-------------|
| `create <nom>` | Créer une nouvelle arène |
| `delete <nom>` | Supprimer une arène |
| `list` | Lister toutes les arènes |
| `arenas` | Ouvrir le GUI de sélection d'arène |
| `setlobby <x> <y> <z>` | Définir le point de lobby |
| `setzonecount <nombre>` | Définir le nombre de zones |
| `setspawn <roomId> <black|white> <index>` | Définir un point de spawn (ex: `mid black 1`) |
| `delspawn <roomId> <black|white> <index>` | Supprimer un spawn |
| `setgoal <roomId> <black|white> <pos1|pos2>` | Définir la zone but (ex: `mid black pos1`) |
| `setgamezone <zone>` | Définir la zone de jeu |
| `start` | Démarrer la partie |
| `stop` | Arrêter la partie |
| `join <arène>` | Rejoindre une arène |
| `joinrandom` | Rejoindre une arène aléatoire |
| `leave` | Quitter la partie en cours |
| `info` | Afficher les infos de l'arène |
| `stats` | Voir ses statistiques |

#### Format RoomId

Les commandes utilisent un **roomId** pour identifier la salle :
- `mid` - Le Mid (neutre)
- `base1black` / `base1white` - Base1 de l'équipe Noir / Blanc
- `base2black` / `base2white` - Base2 de l'équipe Noir / Blanc

Exemples :
```bash
/ss setspawn arena1 mid black 1     # Spawn Noir au Mid
/ss setspawn arena1 base1white 1     # Spawn Blanc en Base1
/ss setgoal arena1 base1black black pos1  # Goal Noir en Base1
```

#### Commande `/ssarenas`
Ouvre le GUI interactif de sélection d'arène.

## 🏗️ Architecture

```
com.spaceship.plugin/
├── commands/          # Gestion des commandes
├── game/             # Logique de jeu (Arena, GameManager, Teams...)
├── gui/              # Interfaces graphiques
├── hologram/        # Gestion des hologrammes de leaderboards
├── listeners/        # Event listeners (dégâts, mouvements, connexion...)
├── scoreboard/       # Gestion des scoreboards
├── stats/            # Système de statistiques
└── util/            # Utilitaires
```

## 🔨 Compilation

Pour compiler le plugin depuis les sources:

```bash
# Installer JDK 21 et Maven si nécessaire
# Clonez le dépôt
git clone https://github.com/herocraftlol/SpaceShip-Plugin.git
cd SpaceShip-Plugin/spaceship

# Compilez avec Maven
mvn clean package -DskipTests
```

Le JAR compilé sera dans `target/SpaceShip.jar`.

## 📝 Structure des Zones (avec Mid Goals)

```
Vaisseau spatial (exemple avec 5 zones):

Équipe NOIR                            Équipe BLANC
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│  [Goal: Base2]   [Goal: Base1]   [MID]   [Goal: Base1]  [Goal: Base2]  │
│                                                               │
│     Base2 ← NOIR          NOIR → ← BLANC          BLANC →     │
│    (spawn)            Entry  Exit  Exit  Entry    (spawn)      │
│                                                               │
│                                    NOIR doit atteindre:       │
│                                    • Goal MID Blanc pour      │
│                                      entrer en Base1 Blanc   │
│                                    • Goal Base1 Blanc pour    │
│                                      pousser vers MID        │
└───────────────────────────────────────────────────────────────┘

Légende:
• Goal MID (ex: white mid) = Zone d'ENTRY pour NOIR (entrer en Base1)
• Goal Base1 (ex: white base1) = Zone d'EXIT pour BLANC (repousser au MID)
```

## 📜 Licence

Ce projet est privé et appartient à son auteur.

## 👤 Auteur

**herocraftlol** - [GitHub](https://github.com/herocraftlol)

---

⭐ N'hésitez pas à ouvrir des issues pour rapporter des bugs ou suggérer des fonctionnalités!
