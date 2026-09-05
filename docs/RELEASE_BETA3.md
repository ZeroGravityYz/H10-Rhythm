# H10 Rhythm Lab 4.0 bêta 3 — EXPÉRIMENTAL

APK Android installable directement, sans Android Studio et sans ancienne version.
**Lab s’installe séparément de H10 Rhythm**, avec son propre historique. Ne pas utiliser les deux acquisitions Bluetooth simultanément.

## Nouveautés depuis 3.1

- Navigation revue : Aujourd’hui, ECG, Forme, Historique ; profil guidé et détails expliqués.
- Historique unifié : recherche, dates, favoris, sélection et nettoyage ; relecture des captures ECG.
- Rapports silencieux, contexte avant/après événement, PDF multipage et données brutes conservées pour export.
- Horodatage capteur et interruptions contrôlés ; VFC sur intervalles admissibles contigus.
- Référence morphologique personnelle et exemples d’artefacts persistants, avec abstention en cas de doute.
- Détecteur causal et modèle supervisé compact évalués sur ECG publics ; indéterminés visibles.
- Journal sportif, tendances et prévisions locales comparées à des références simples avant affichage.
- Documentation et libellés clarifiés : enregistrement expérimental, pas surveillance médicale.

## Limites importantes

Les faux positifs et anomalies manquées restent nombreux. Le modèle n’a pas démontré une fiabilité médicale, notamment sous bruit ; ses votes ne sont pas des probabilités de maladie.
**Les alertes cardiaques sonores et vibrantes sont désactivées dans Lab.** Les passages restent enregistrés pour relecture ; les notifications techniques restent actives.
L’absence de passage enregistré ne permet pas de conclure à l’absence d’anomalie.
Les poids et décisions sont inchangés depuis l’évaluation bêta 2.

[Résultats détaillés et protocole](https://github.com/ZeroGravityYz/H10-Rhythm/blob/v4.0.0-beta3-lab/docs/EXPERIMENTAL_BETA2.md)
· [Installation](https://github.com/ZeroGravityYz/H10-Rhythm/blob/v4.0.0-beta3-lab/INSTALLATION.txt)
· [Changelog](https://github.com/ZeroGravityYz/H10-Rhythm/blob/v4.0.0-beta3-lab/CHANGELOG.md)

## Vérifications de cette compilation

Huit suites JVM et 14 scénarios de comportement passent. Compilation de l’application et des tests Android réussie ; analyse statique : zéro erreur, 27 avertissements.
Parité Python/Java : 200 vecteurs, écart maximal 0,00000335.
Les tests Android sur appareil, le BLE prolongé et le rendu PDF sur téléphone n’ont pas été exécutés ici.
APK signé avec le certificat debug du laboratoire : préversion de test, pas signature de production.

Fichier : `H10_Rhythm_Lab_v4.0.0_beta3.apk`

SHA-256 :
`c7fcc01ddb5e55270bdd6847166e59669cbb25309c9ee4da57407e8b199fba58`

Code MIT hors poids du modèle ; modèle de recherche non commercial lié à Icentia CC BY-NC-SA 4.0 : [notice](https://github.com/ZeroGravityYz/H10-Rhythm/blob/v4.0.0-beta3-lab/MODEL_NOTICE.md).
Aucun ECG personnel ni clé de signature n’est publié.
