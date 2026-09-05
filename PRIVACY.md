# Confidentialité — H10 Rhythm 4.0 bêta

La bêta 2 Lab est une installation distincte. Son modèle général est entraîné hors téléphone uniquement sur des ECG publics ; aucun ECG personnel n'a été utilisé pour cet entraînement. Sur téléphone, les poids restent figés, les annotations alimentent le module personnel local et les rapports Lab utilisent un dossier séparé. Aucune transmission automatique ni alerte cardiaque physique n'est activée dans Lab.

L’analyse ECG et les calculs de suivi restent sur le téléphone. L’application n’a pas de permission Internet, compte, publicité ou outil d’audience.

## Données conservées

- ECG brut, horodatages et résumés de sessions ;
- événements, annotations, prototypes morphologiques ;
- profil renseigné, séances, effort perçu, bilans matinaux et contexte ;
- coefficients du modèle de forme et prévisions enregistrées à l’avance.

Les données structurées sont dans une base SQLite privée. Le profil, certains paramètres et les coefficients restent dans les préférences privées Android. Les anciennes préférences historiques sont conservées pendant la migration ; elles sont retirées lors des suppressions correspondantes.

Le brut continu est conservé 24 h par défaut, réglable à 72 h ou 7 jours. Les nouveaux fichiers utilisent des entiers 32 bits pour ne pas tronquer les valeurs signées 24 bits de la H10. Les anciennes captures ne sont pas réécrites.

Les rapports et leur JSONL sont dans Documents/PolarH10Monitor sur Android 10 et plus ; sur Android 8–9, dans le dossier Documents privé externe de l’application. Un accès en lecture limité à un fichier peut être accordé à l’application choisie pour ouvrir ou partager ce fichier.

## Partage

Pas d’envoi automatique vers une IA ou un serveur. Le partage Android exige une action utilisateur ; le destinataire choisi peut conserver sa propre copie. Les exports contiennent des données de santé et des horodatages.

## Suppression

- Suppression d’un rapport : retire ses fichiers et son entrée ; conserve l’exemple annoté utilisé par le modèle.
- Effacement global de l’historique, surveillance arrêtée : retire rapports, signaux continus, sessions, bilans et journal. Le profil personnel et les exemples morphologiques sont conservés.
- Réinitialisation du modèle ECG : retire sa référence et ses annotations d’apprentissage.
- Réinitialisation du suivi de forme : retire profil, bilans, journal, coefficients et prévisions ; conserve les ECG et rapports.
- Désinstallation : retire les données privées, mais pas nécessairement les fichiers déjà exportés dans Documents ou partagés à un tiers.

Les suppressions logiques ne constituent pas un effacement cryptographique du support. Android gère la protection du stockage et les accès système ; aucune garantie d’inviolabilité n’est revendiquée.

Auteur et responsable du projet : Mattéo Leroy.
