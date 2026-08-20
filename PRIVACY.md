# Confidentialité

H10 Rhythm traite les données ECG localement sur l’appareil Android.

## Données traitées

- signal ECG brut reçu du Polar H10 ;
- fréquence cardiaque, qualité du signal et événements calculés ;
- modèle morphologique personnel ;
- choix de l’utilisateur sur les passages enregistrés.

## Stockage

- l’historique brut continu est conservé jusqu’à 24 heures dans l’espace privé de l’application ;
- les événements sélectionnés sont exportés dans `Documents/PolarH10Monitor` sous forme de PDF, JSON et JSONL ;
- le modèle personnel et l’historique affiché sont conservés dans le stockage privé de l’application.

## Transfert

L’application n’intègre aucun compte, outil publicitaire, outil d’analyse d’audience ou transfert réseau. Un fichier ne quitte le téléphone que si l’utilisateur le partage lui-même depuis Android.

## Suppression

Le modèle personnel peut être réinitialisé depuis les réglages. Les rapports exportés peuvent être supprimés avec l’application Fichiers. La désinstallation supprime le stockage privé de l’application.

Auteur et responsable du projet : Mattéo Leroy.
