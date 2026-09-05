# H10 Rhythm Lab — 4.0 bêta 3 expérimentale

**Cette branche compile une application laboratoire séparée (`com.local.polarh10monitor.lab`), sans alertes cardiaques physiques.** Elle ne remplace pas H10 Rhythm. Modèles entraînés et testés sur ECG publics, résultats encore insuffisants. Voir le [bilan expérimental complet](docs/EXPERIMENTAL_BETA2.md) et la [notice non commerciale du modèle Icentia](MODEL_NOTICE.md). Les données et rapports Lab sont isolés dans `PolarH10Lab`.

Le laboratoire ajoute un détecteur causal, un modèle supervisé compact, une référence personnelle et un affichage des indéterminés. La bêta 3 améliore la présentation et la distribution sans modifier les poids ni les décisions du moteur évalué en bêta 2.

[Télécharger l’APK expérimental](https://github.com/ZeroGravityYz/H10-Rhythm/releases/download/v4.0.0-beta3-lab/H10_Rhythm_Lab_v4.0.0_beta3.apk) · [Notes de version](CHANGELOG.md) · [Résultats et limites](docs/EXPERIMENTAL_BETA2.md)

Application Android native pour afficher et enregistrer l’ECG d’une Polar H10, retrouver les passages enregistrés et suivre ses mesures au repos. Développée par Mattéo Leroy.

**Version expérimentale, pas un dispositif de surveillance médicale fiable.** Les tests logiciels passent, mais les évaluations publiques MIT-BIH, Icentia et sous bruit montrent de nombreuses anomalies manquées. Une absence de passage enregistré n’exclut rien. Les résultats ne démontrent pas les performances sur une Polar H10 portée au quotidien.

## Ce qui change

- Quatre espaces : Aujourd’hui, ECG, Forme et Historique ; réglages accessibles séparément. Navigation adaptée à la largeur, marges système et formulaire de profil en trois étapes.
- Accueil : connexion, batterie, battements, RMSSD, SDNN, qualité estimée et résumé des passages. Les termes avancés sont expliqués ou regroupés dans des sections dépliables.
- ECG : tracé continu, marquage d’un ressenti ; relecture des captures brutes avec déplacement et vitesses 25/50/100 mm/s.
- Historique commun aux passages, bilans et séances : recherche différée, périodes, date, favoris, sélection et suppression. Chargement des résultats en arrière-plan.
- Forme : journal de séances avec durée réelle et effort perçu, journée explicitement complète ou inconnue, bilan au calme et tendances 7/30/90 jours. Aucun classement automatique « sportif/sédentaire ».
- Rapports silencieux : capture visée de 60 s avant et 30 s après le passage, PDF multipage, JSONL non écrêté, état d’échec et tentative de reconstruction depuis le brut conservé.
- Conservation du signal continu au choix : 24 h, 72 h ou 7 jours. Les événements restent jusqu’à leur suppression.

L’écran ne peut pas garantir une échelle physique exacte sur tous les téléphones. Le PDF utilise 25 mm/s et 10 mm/mV lorsqu’il est imprimé à 100 %.

## Deux apprentissages différents

### Morphologie ECG

Le module personnel n'est pas un réseau neuronal : une fenêtre de 130 points est normalisée puis résumée en 16 composantes fixes. Les 500 battements sélectionnés automatiquement comme stables constituent une référence métrique personnelle, pas 500 battements validés médicalement. Dans Lab, il est complété par une forêt expérimentale figée entraînée sur 22 MIT-BIH et 40 Icentia, évaluée sur des personnes séparées ; ses limites interdisent son emploi comme système d'alertes physiques.

Les annotations utilisateur alimentent des banques distinctes d’exemples inhabituels et d’artefacts, limitées à 64 prototypes par banque. Une annotation est réversible et indépendante de la présence du rapport. Deux étiquettes contradictoires proches entraînent une sortie incertaine. L’utilisateur ne confirme jamais médicalement une ESV en cliquant sur un bouton.

Les règles de rythme et les contrôles de bruit restent séparés. Le modèle peut manquer un problème ou confondre un artefact avec un battement.

### Suivi de forme

Les 30 premières secondes du bilan servent à la stabilisation ; les 150 suivantes sont analysées. Une interruption invalide le bilan. La VFC utilise des intervalles NN admissibles et des différences entre intervalles contigus, sans relier artificiellement deux séries séparées par un rejet.

Le modèle de réponse utilise des transitions entre matins comparables. Une journée non renseignée n’est pas assimilée à du repos. Les séances H10 notées et les séances manuelles alimentent le journal ; éviter de saisir deux fois une même séance.

Une prévision est enregistrée avant son résultat et ne peut pas être réécrite. Son affichage demande au moins 21 transitions d’apprentissage, 20 évaluations futures et une erreur inférieure d’au moins 10 % aux deux références simples. La comparaison d’une charge demande aussi cinq situations proches et refuse l’extrapolation hors des charges observées. Ces seuils sont des garde-fous expérimentaux, pas une preuve d’efficacité clinique.

L’âge intervient dans les zones estimées. Des seuils personnels peuvent être renseignés ; les traitements déclarés suspendent l’estimation automatique. Le sexe reste un contexte facultatif, sans coefficient inventé. Taille et poids permettent le calcul descriptif de l’IMC, pas une note de récupération.

Les [repères OMS](https://www.who.int/publications/i/item/9789240015128) sont généraux. L’application ne prédit pas une infection et ne prescrit pas une dose optimale d’exercice.

## Architecture

- `MonitorService`, `PmdClock`, `PolarAccDecoder` : BLE, horloge capteur, discontinuités, batterie, service de premier plan.
- `StreamingQrs`, `EcgEngine` : repérage causal, règles, SQI, VFC et abstention.
- `ExperimentalClassifier`, `ExperimentalWeights`, `ShapeReference`, `MorphologyModel` : modèle public figé et références personnelles distinctes.
- `ExperimentPolicy` : blocage des alertes cardiaques physiques dans Lab, couvert par un test de régression.
- `LocalRepository` : SQLite locale et migration transactionnelle des anciennes préférences.
- `EventStore`, `EventHistory`, `ReportFiles`, `ReplayView` : brut, rapports, reprise et partage explicite.
- `TrainingJournal`, `FitnessInsights`, `AdaptiveTwin`, `ForecastLedger` : journal, références, modèle et validation prospective.
- `MainActivity`, `ProfileWizard`, `TimelineView`, `SportsPanel`, `FitnessTrendView` : interface native.

Les anciennes préférences restent conservées après migration pour éviter une perte lors de l’import. Une suppression globale retire aussi les copies historiques héritées. Voir [PRIVACY.md](PRIVACY.md).

## Installer ou compiler

L’APK bêta s’installe directement sur Android 8 ou supérieur : Android Studio et une ancienne version ne sont pas nécessaires. Voir [INSTALLATION.txt](INSTALLATION.txt). Lab s’installe à côté de H10 Rhythm, sans importer son historique. Seules les mises à jour d’une installation Lab demandent une signature identique. Ne pas désinstaller sans sauvegarder ses données.

Développement : JDK 17, Android SDK 35.

```bash
./gradlew assembleDebug assembleDebugAndroidTest lintDebug selfTest
```

Huit suites JVM sont exécutées, dont 14 scénarios ECG synthétiques de comportement logiciel. La CI les relance. Compiler les tests Android ne les exécute pas : les tests de stockage demandent `connectedDebugAndroidTest` sur un appareil de test. Ces vérifications ne constituent pas une validation clinique.

La variante release n’utilise plus silencieusement la clé debug. Renseigner une vraie configuration `keystore.properties` pour signer une distribution ; ne jamais publier ce fichier ou la clé.

L’APK de cette bêta ne vaut pas validation du BLE sur 24 h, du rendu sur tous les écrans, ni du détecteur sur un H10 de référence.

## Licence et indépendance

Code sous [licence MIT](LICENSE), **hors poids expérimentaux du modèle**, soumis à la [notice de recherche non commerciale](MODEL_NOTICE.md) liée à Icentia (CC BY-NC-SA 4.0). Ne pas présenter l’ensemble comme libre d’usage commercial. Polar et Polar H10 appartiennent à Polar Electro Oy. Projet indépendant, non affilié à Polar. Les jeux de données ne sont pas inclus dans le dépôt ; aucun ECG personnel n’est publié.
