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
- menu Adaptive Twin avec bilan matinal guidé, référence personnelle FC/VFC et recommandation de charge explicable ;
- modèle dose-réponse local apprenant le lien entre état du matin, charge observée, effort perçu et réponse du lendemain ;
- passeport de fiabilité comparant l’erreur du modèle à la prévision naïve avant d’autoriser les prévisions ML ;
- expériences personnelles facultatives et associations affichées avec leurs nombres de journées comparables ;
- contexte local sommeil, stress, fatigue, symptômes, alcool et entraînement récent ;
- courbes de tendance sur 30 bilans et niveau de confiance dépendant de la quantité de données comparables ;
- navigation supérieure compatible avec les boutons et gestes système Android ;
- barrière anti-contact, qualité locale et décision retardée de 1,2 seconde avant toute alerte ;
- profil morphologique personnel avec prototypes séparés pour les anomalies et les artefacts confirmés ;
- rapports PDF silencieux et export JSONL comprenant 60 secondes avant et 30 secondes après un événement ;
- tableau de bord sur 7 jours, 30 jours ou toute la période, recherche, filtres et VFC (RMSSD/SDNN) ;
- indicateurs de session directement sur l’accueil et suppression globale des rapports ;
- remise à zéro complète des rapports, valeurs brutes, signaux continus et statistiques d’historique ;
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

## Adaptive Twin

Le bilan Adaptive Twin analyse une fenêtre dédiée de trois minutes au calme. Les trente premières secondes sont réservées à la stabilisation. Les intervalles associés à des battements non normaux, au mouvement ou à un signal insuffisant sont exclus ; un bilan contenant moins de 90 intervalles propres ou moins de 80 % de signal exploitable n’est pas enregistré.

Après plusieurs bilans comparables, l’application construit une référence personnelle robuste puis apprend la relation entre l’état du matin, la charge enregistrée jusqu’au bilan suivant et la réponse physiologique observée. L’effort perçu renseigné après une séance ajuste la charge lorsque la fréquence cardiaque seule ne décrit pas correctement l’effort.

Le petit modèle régularisé est réentraîné localement à partir de l’historique. Ses prévisions sont testées dans l’ordre chronologique : elles restent masquées tant qu’au moins 21 transitions propres ne sont pas disponibles et que leur erreur n’est pas inférieure à celle de la règle « demain ressemble à aujourd’hui ». Dans le cas contraire, une fourchette prudente issue des règles explicables est affichée. Le passeport du modèle indique toujours les données disponibles, les erreurs comparées et l’incertitude.

Les associations liées au sommeil, au stress, à la fatigue, à l’alcool ou aux séances difficiles ne sont affichées qu’avec leurs effectifs. Une expérience personnelle facultative peut comparer des nuits où un objectif a été respecté ou non. Ces résultats décrivent des associations individuelles ; ils ne prouvent pas une causalité, ne prédisent pas une maladie et ne constituent pas un diagnostic.

Les minutes en zones sont estimées seulement pendant le port de la H10, à partir de la réserve cardiaque et d’un signal propre. Elles ne mesurent donc pas la sédentarité sur toute la journée. Les repères affichés suivent les recommandations générales de l’OMS pour les adultes : 150 à 300 minutes d’activité modérée, ou 75 à 150 minutes soutenues, avec du renforcement au moins deux jours par semaine.

## Compilation

Prérequis : Android SDK 35 et JDK 17.

```bash
./gradlew assembleDebug
```

Pour une publication, créez votre propre clé de signature, copiez `keystore.properties.example` vers `keystore.properties` et renseignez vos valeurs. Ne publiez jamais ce fichier ni la clé privée. Sans ce fichier, la variante locale `release` utilise uniquement la clé de développement afin de faciliter les essais.

## Données

Les rapports sont enregistrés dans `Documents/PolarH10Monitor`. L’historique continu brut est conservé dans l’espace privé de l’application pendant 24 heures. Adaptive Twin, ses bilans, ses retours et ses coefficients restent dans l’espace privé de l’application et disposent d’une remise à zéro indépendante. Aucune donnée n’est transférée automatiquement.

Consultez [PRIVACY.md](PRIVACY.md) pour le détail.

## Avertissement

H10 Rhythm est un projet expérimental mono-dérivation. Il ne constitue pas un dispositif médical, ne fournit pas de diagnostic et ne peut pas exclure un trouble du rythme. Une détection doit être relue sur le tracé et discutée avec un professionnel de santé lorsque le contexte le justifie.

Polar et Polar H10 sont des marques de Polar Electro Oy. Ce projet indépendant n’est ni affilié ni approuvé par Polar Electro.

## Licence

Code distribué sous licence MIT. Voir [LICENSE](LICENSE).

Les textes préparés pour une fiche de distribution sont disponibles dans [STORE_LISTING.md](STORE_LISTING.md).
