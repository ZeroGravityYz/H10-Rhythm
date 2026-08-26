# Historique des versions

## 3.1.0-test

- remplacement du menu Forme par **Adaptive Twin**, centré sur une fourchette de charge, une prévision du lendemain et l’explication de chaque facteur plutôt que sur un score opaque ;
- nouveau modèle local dose-réponse entraîné sur les transitions entre deux bilans matinaux propres et les charges H10 réellement observées ;
- validation chronologique obligatoire : l’indication « ML actif » reste masquée jusqu’à au moins 21 transitions et jusqu’à ce que le modèle batte la référence naïve « demain ressemble à aujourd’hui » ;
- passeport du modèle avec nombre d’exemples, prévisions testées, erreur du modèle, erreur de référence, confiance et état d’apprentissage ;
- estimation d’incertitude et repli automatique vers une recommandation prudente lorsque l’historique ne permet pas une prévision fiable ;
- traduction de la charge en proposition compréhensible selon l’activité principale, avec limites explicites pour la musculation ;
- retour d’effort perçu de 1 à 10 après une séance afin de corriger ce que la fréquence cardiaque seule ne mesure pas ;
- apprentissage des associations personnelles avec le sommeil, le stress, la fatigue musculaire, l’alcool et les séances difficiles, avec effectifs visibles ;
- expérience personnelle facultative « coucher 30 minutes plus tôt » sur 12 nuits, dont le résultat reste masqué avant trois journées dans chaque groupe ;
- coefficients, retours, expériences et bilans stockés exclusivement sur le téléphone et effaçables avec Adaptive Twin.

## 3.0.1-test

- formulaire de profil remplacé par une fenêtre redimensionnable avec contenu défilant et boutons Annuler / Enregistrer toujours visibles au-dessus du clavier ;
- chaque champ explique désormais son rôle réel dans les calculs ;
- choix du sexe transformé en sélecteur : il reste un contexte facultatif et n’influence pas artificiellement la récupération personnalisée ;
- activité, ancienneté et renforcement contribuent au profil de condition ; âge et traitement influençant la FC sont appliqués seulement là où ils sont pertinents ;
- objectif utilisé pour contextualiser les conseils de reprise, endurance, gestion du poids ou force ;
- graphique de tendance redessiné avec vraies légendes pleines, points lisibles et états dédiés avant un ou deux bilans.

## 3.0.0-test

- nouveau menu **Forme** séparé de la surveillance ECG et de l’historique des alertes ;
- profil local comprenant âge, taille, poids, habitudes d’activité, renforcement, expérience sportive, objectif et traitements influençant la fréquence cardiaque ;
- bilan matinal guidé de trois minutes, avec stabilisation initiale, exclusion des battements non normaux et rejet automatique des mesures trop bruitées ;
- référence physiologique personnelle robuste sur 60 jours, fondée sur la médiane et l’écart absolu médian de la FC de repos et du lnRMSSD ;
- cinq états de récupération expliqués point par point, avec niveau de confiance dépendant du nombre de bilans comparables ;
- profil de condition progressif qui combine activité habituelle, temps cardiaque observé, ancienneté d’entraînement et historique propre sans conclure à un « cœur d’athlète » ;
- estimation locale des minutes en zones modérée et soutenue, tendances FC/VFC sur 30 bilans et repères d’activité de l’OMS ;
- contexte quotidien sommeil, stress, fatigue musculaire, symptômes, alcool et séance difficile pour éviter de confondre un signal non spécifique avec une maladie ;
- aperçu de l’état de forme sur l’accueil, explication « Pourquoi ce résultat ? », conseils prudents et remise à zéro indépendante des données Forme ;
- aucune donnée de profil ou de récupération ne quitte le téléphone.

## 2.6.1-test

- correction de la lecture de batterie H10 : elle est désormais retardée, retentée automatiquement et vérifiée périodiquement sans bloquer le flux ECG ou le capteur de mouvement ;
- nouvelle remise à zéro complète depuis Historique ou Réglages : rapports PDF, ECG bruts exportés, signal continu privé des dernières 24 h, passages et résumés de sessions ;
- verrouillage du nettoyage avec les créations de rapports afin qu’un fichier en attente ne puisse pas réapparaître après la suppression ;
- le profil morphologique personnel reste volontairement conservé lors du nettoyage de l’historique ;
- hiérarchie visuelle renforcée : cartes en dégradé, indicateurs colorés, cœur d’état plus lisible, badge direct et zone de suppression clairement séparée.

## 2.6.0-test

- activation du flux accéléromètre du Polar H10 à 50 Hz pour mettre en quarantaine les secousses réelles de la ceinture avant toute alerte cardiaque ;
- décision morphologique retardée de 1,2 seconde afin d’observer la récupération du signal et les données de mouvement qui suivent le complexe ;
- nouveau contrôle de qualité local autour de chaque événement : déplacement de ligne de base, saturation, énergie hors QRS et retour au calme ;
- validation des QRS par deux indices indépendants, amplitude filtrée et concentration de l’énergie dérivée ;
- les battements prématurés larges sans pause compensatrice ne sont plus signalés sauf si le profil personnel est prêt et si les contrôles locaux concordent fortement ;
- annotations Normal / Anomalie / Artefact reconstruites depuis l’historique : un passage ne compte qu’une fois et un changement d’étiquette annule l’ancienne influence ;
- banc de régression porté à quatorze scénarios, avec plateau lent de ceinture, anomalie pendant une secousse et anomalie après retour au calme.

## 2.5.0-test

- suppression complète de la seconde lecture IA et nettoyage automatique de ses anciens fichiers de partage, sans toucher aux PDF ni aux ECG bruts ;
- retrait de la pastille « Calcul local » ;
- distinction plus claire entre RMSSD (variations rapides) et SDNN (dispersion globale) ;
- nouvelle identité visuelle avec cœur, surfaces en dégradé et transitions discrètes ;
- animation légère du rythme et des changements de page, active uniquement lorsque l’application est visible.

## 2.4.1-test

- regroupement de la consigne, des métadonnées et de toutes les valeurs ECG brutes dans `analyse_ecg.txt` afin d’éviter le rejet des formats JSON/JSONL par Gemini ;
- partage limité à deux pièces jointes largement reconnues : `analyse_ecg.txt` et `rapport.pdf` ;
- consigne courte copiée automatiquement dans le presse-papiers si Gemini ne remplit pas son champ ;
- suppression de l’ouverture directe fragile qui pouvait réutiliser une ancienne conversation Gemini.

## 2.4.0-test

- bouton de seconde lecture IA sur chaque rapport prêt ;
- consentement explicite avant tout transfert de données de santé ;
- génération locale de `prompt_ia.txt` avec contexte, mesures, limites et consignes anti-surinterprétation ;
- partage simultané du PDF, du JSONL brut, des métadonnées et du prompt ;
- tentative d’ouverture directe d’un récepteur Gemini, avec feuille de partage Android en solution de repli.

## 2.3.0

- le classifieur morphologique attend désormais la seconde complète autour du QRS, même lorsque deux battements sont rapprochés ;
- rejet des complexes mesurés sur moins de quatre échantillons, typiques d’un double comptage ou d’un contact ;
- suppression des fausses TSV autour de 120-130 bpm : une alerte spécifique exige maintenant au moins 155 bpm et un début brutal ;
- banc de test continu avec onze scénarios : normal, ESV, ESA, contact, mouvement, effort progressif, tachycardie, bradycardie, rythme irrégulier, pause et modèle personnel prêt ;
- ajout des battements, RMSSD, SDNN, qualité du signal, battements en avance et pauses sur l’accueil ;
- suppression globale des rapports et de leurs données brutes après confirmation ;
- filtres visuellement sélectionnés et simplification du bandeau ECG.

## 2.2.0

- ajout d’une barrière anti-choc de contact avant la détection des QRS ;
- exclusion des morphologies physiquement invraisemblables et quarantaine avant les alertes de pause ;
- apprentissage local d’un prototype d’artefacts distinct de la baseline normale ;
- rapports prêts en environ 30 secondes, sans notification de fin ;
- tracé PDF contenu dans chaque cadre, centré et limité visuellement à ±2 mV, tout en conservant le brut intact ;
- historique transformé en tableau de bord : périodes, recherche, filtres, partage et indicateurs VFC RMSSD/SDNN ;
- résumés de sessions conservés localement pour le suivi à long terme.

## 2.1.0

- navigation déplacée en haut, hors de la zone des commandes système Android ;
- nouvelle barre d’application et navigation segmentée ;
- guide de première ouverture et accès permanent depuis les réglages ;
- icône adaptative et écran de lancement Android 12+ ;
- amélioration des interactions, reliefs et retours visuels.

## 2.0.0

- nouvelle interface grand public avec navigation Aujourd’hui, Historique et Réglages ;
- historique local et ouverture directe des rapports PDF ;
- retours utilisateur Normal, Anomalie réelle et Artefact ;
- modèle morphologique personnel persistant sur 16 dimensions ;
- paramètres de notifications, batterie et réinitialisation du modèle ;
- documentation de compilation, confidentialité et licence.

## 1.1.0

- rendu progressif et fluide du tracé.

## 1.0.0

- connexion BLE Polar H10, surveillance en arrière-plan et rapports d’événements.
