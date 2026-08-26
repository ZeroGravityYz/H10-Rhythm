# Confidentialité

H10 Rhythm traite les données ECG localement sur l’appareil Android.

## Données traitées

- signal ECG brut reçu du Polar H10 ;
- fréquence cardiaque, qualité du signal et événements calculés ;
- modèle morphologique personnel ;
- choix de l’utilisateur sur les passages enregistrés.
- contexte Adaptive Twin renseigné par l’utilisateur : âge, taille, poids, habitudes et contexte sportif ;
- retours d’effort perçu, expériences personnelles et coefficients du modèle dose-réponse ;
- bilans matinaux locaux : FC de repos, RMSSD, SDNN, qualité du signal et contexte déclaré.

## Stockage

- l’historique brut continu est conservé jusqu’à 24 heures dans l’espace privé de l’application ;
- les événements sélectionnés sont exportés dans `Documents/PolarH10Monitor` sous forme de PDF, JSON et JSONL ;
- le modèle personnel et l’historique affiché sont conservés dans le stockage privé de l’application.
- le contexte Adaptive Twin et les bilans matinaux sont conservés dans le stockage privé de l’application, avec un maximum de 180 bilans.

## Transfert

L’application n’intègre aucun compte, outil publicitaire, outil d’analyse d’audience ou transfert réseau. Un fichier ne quitte le téléphone que si l’utilisateur le partage lui-même depuis Android.

## Suppression

Le modèle morphologique et Adaptive Twin disposent de remises à zéro indépendantes. Les rapports, ECG bruts et résumés de sessions peuvent être effacés ensemble depuis l’application. La désinstallation supprime le stockage privé de l’application.

Auteur et responsable du projet : Mattéo Leroy.
