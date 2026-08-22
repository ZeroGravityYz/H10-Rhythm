# H10 Rhythm

Application Android native de suivi ECG en temps réel pour la ceinture Polar H10. Elle affiche le signal à 130 Hz, poursuit l’enregistrement écran éteint, repère des passages inhabituels et conserve leur contexte dans un rapport PDF accompagné des données brutes.

Développé par **Mattéo Leroy**.

## Fonctionnalités

- connexion directe au service BLE PMD du Polar H10 ;
- fusion de l’ECG à 130 Hz et de l’accéléromètre H10 à 50 Hz pour repérer les manipulations de ceinture ;
- tracé ECG fluide calibré à 25 mm/s et 10 mm/mV ;
- surveillance en service de premier plan avec reconnexion automatique ;
- détection de battements prématurés, pauses et rythmes durablement rapides, lents ou irréguliers ;
- historique local des passages détectés ;
- navigation supérieure compatible avec les boutons et gestes système Android ;
- barrière anti-contact, qualité locale et décision retardée de 1,2 seconde avant toute alerte ;
- profil morphologique personnel avec prototypes séparés pour les anomalies et les artefacts confirmés ;
- rapports PDF silencieux et export JSONL comprenant 60 secondes avant et 30 secondes après un événement ;
- tableau de bord sur 7 jours, 30 jours ou toute la période, recherche, filtres et VFC (RMSSD/SDNN) ;
- indicateurs de session directement sur l’accueil et suppression globale des rapports ;
- banc de test synthétique continu couvrant quatorze rythmes, anomalies et artefacts avant chaque publication ;
- modèle morphologique personnel et apprentissage à partir des corrections de l’utilisateur ;
- fonctionnement sans compte, publicité, serveur ou dépendance externe.

## Analyse personnalisée

Le moteur combine deux approches indépendantes :

1. des règles temporelles analysent les intervalles RR, les pauses et la qualité du signal ;
2. un modèle métrique personnel analyse uniquement la forme du complexe ECG.

Chaque battement propre est représenté par une fenêtre d’une seconde (130 points), normalisée puis projetée sur 16 composantes. Les 500 premiers battements jugés stables forment le prototype personnel. La distance au prototype fournit un score d’anomalie morphologique. Les confirmations données depuis l’historique servent d’exemples supplémentaires. Elles sont uniques et réversibles : changer l’étiquette d’un passage remplace son influence précédente au lieu de l’ajouter une seconde fois.

Avant qu’un événement ne soit enregistré, le moteur attend 1,2 seconde puis combine le mouvement mesuré par la ceinture avec un contrôle local du tracé. Une secousse, un plateau, une saturation, un déplacement brutal de la ligne de base ou une énergie anormale hors du QRS mettent le passage en quarantaine. Si l’accéléromètre n’est pas disponible, l’ECG continue de fonctionner avec les contrôles de signal seuls.

Ce modèle est un détecteur statistique adaptatif compact, pas un réseau neuronal générique. Son état reste stocké sur le téléphone et peut être réinitialisé depuis les réglages.

## Compilation

Prérequis : Android SDK 35 et JDK 17.

```bash
./gradlew assembleDebug
```

Pour une publication, créez votre propre clé de signature, copiez `keystore.properties.example` vers `keystore.properties` et renseignez vos valeurs. Ne publiez jamais ce fichier ni la clé privée. Sans ce fichier, la variante locale `release` utilise uniquement la clé de développement afin de faciliter les essais.

## Données

Les rapports sont enregistrés dans `Documents/PolarH10Monitor`. L’historique continu brut est conservé dans l’espace privé de l’application pendant 24 heures. Aucune donnée n’est transférée automatiquement.

Consultez [PRIVACY.md](PRIVACY.md) pour le détail.

## Avertissement

H10 Rhythm est un projet expérimental mono-dérivation. Il ne constitue pas un dispositif médical, ne fournit pas de diagnostic et ne peut pas exclure un trouble du rythme. Une détection doit être relue sur le tracé et discutée avec un professionnel de santé lorsque le contexte le justifie.

Polar et Polar H10 sont des marques de Polar Electro Oy. Ce projet indépendant n’est ni affilié ni approuvé par Polar Electro.

## Licence

Code distribué sous licence MIT. Voir [LICENSE](LICENSE).

Les textes préparés pour une fiche de distribution sont disponibles dans [STORE_LISTING.md](STORE_LISTING.md).
