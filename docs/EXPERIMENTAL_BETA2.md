# H10 Rhythm Lab — bilan expérimental du 5 septembre 2026

## Décision : laboratoire séparé, pas de promotion des modèles en surveillance

Cette version installe **H10 Rhythm Lab**, identifiant `com.local.polarh10monitor.lab`. Elle ne remplace pas H10 Rhythm et ne migre ni ne supprime son historique. Les rapports Lab utilisent `Documents/PolarH10Lab/`. Ne pas lancer simultanément les deux acquisitions sur la ceinture.

**Les alertes cardiaques physiques sont désactivées par le code, indépendamment des réglages.** Les notifications techniques de connexion/enregistrement restent possibles. Les hypothèses enregistrées ne sont pas des diagnostics et peuvent être fausses. La classe automatique ESA est désactivée ; une prématurité peut être conservée pour relecture sans origine attribuée. L'ancienne application n'est pas médicalement validée non plus.

Ce choix est motivé par les échecs observés, et non par l'absence de certification seulement. Aucun « 99,9 % de fiabilité » ni supériorité médicale n'est revendiqué.

## Travail réalisé

- Détecteur QRS causal natif : FIR 5–20 Hz, enveloppe d'énergie, seuils adaptatifs et recherche de battements faibles. Position ramenée à l'horloge des échantillons. Mémoire bornée. Ce n'est pas un port de XQRS et les résultats XQRS hors ligne ne lui sont pas attribués.
- Correction d'un décalage continu d'acquisition avant écrêtage/SQI. Le service conserve toujours les valeurs brutes originales avant leur passage au moteur.
- Correction de la temporisation des pauses : le retard de confirmation d'un QRS ne doit pas créer de pause fictive, notamment vers 35 bpm.
- Décision par défaut « indéterminé », non « normal ». Compteur d'indéterminés visible. Les NN utilisés pour la VFC exigent un accord normal et une continuité suffisante.
- Séparation de la forme et du contexte de rythme ; estimation de largeur sur le signal localement redressé de sa ligne de base, sans mesurer la traîne du filtre comme un QRS. Cette largeur reste approximative.
- L'accéléromètre seul ne rejette plus une vraie ectopie synthétique sur ECG propre. Les chocs et le signal dégradé restent des motifs d'abstention.
- Petit modèle supervisé de 16 arbres, 48 caractéristiques (forme + RR + contexte). Les votes ne sont pas des probabilités médicales calibrées.
- Modèle personnel distinct : prototypes annotés persistants et référence de forme de session. Aucun entraînement supervisé sur des diagnostics inventés par l'assistant ou sur les ECG privés de l'utilisateur.

## Entraînements et partitions

Premier candidat : 22 enregistrements MIT-BIH de développement, 21 d'évaluation. Les 44 enregistrements non principalement stimulés avaient déjà été inspectés avec la bêta 1 : cette comparaison MIT-BIH n'est donc pas un test aveugle. Le 202 est exclu de l'évaluation par précaution de séparation des personnes avec le 201.

Ce premier candidat avait une sensibilité V/E de 61,23 % et une VPP de 98,72 % sur son lot MIT-BIH séparé, mais a échoué sur les huit premiers enregistrements Icentia testés : 0/7 V retrouvés et 695 prédictions V non confirmées. Les bons chiffres MIT-BIH ne se transféraient donc pas à ce domaine.

Deuxième candidat, embarqué **en observation dans Lab** : 22 MIT-BIH + les 40 premiers enregistrements du sous-ensemble Icentia local trié par chemin ; évaluation sur les 21 MIT-BIH séparés et les 20 Icentia suivants. Les identifiants Icentia des personnes d'entraînement et d'évaluation sont disjoints. Les listes complètes et empreintes des données sont dans `validation/lab-beta2-training.json`. Le seuil 0,85, la marge 0,25, les 16 arbres de profondeur maximale 7 et la graine 20260905 sont fixés avant l'évaluation ; pas de réglage sur les 20 Icentia finaux.

Le modèle embarqué représente 60 658 octets décodés, 38 510 octets gzip (la chaîne Base64 dans le code a un surcoût). Le modèle général est figé ; les annotations sur téléphone ne réentraînent pas cette forêt. Elles modifient uniquement les exemples du module personnel.

## Mesures du moteur intégré final

Protocole : mêmes signaux physiques convertis en µV, rééchantillonnage polyphasé à 130 Hz, marge initiale 30 s/finale 2 s, appariement individuel à ±150 ms. Les scores portent sur les étiquettes de battements avant regroupement/temporisation, **pas sur le nombre de notifications**. Les abstentions comptent comme événements manqués lorsqu'une référence V existe.

| Lot | Durée | QRS retrouvés | VPP QRS | V/E retrouvés | VPP V/E conservative | Indéterminés parmi les QRS détectés |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| MIT-BIH, 21 enregistrements hors entraînement | 10,35 h | 99,83 % | 98,55 % | 749/3 149 — 23,79 % | 70,53 % | 39,64 % |
| Icentia, 20 personnes hors entraînement | 23,12 h | 96,40 % | 83,23 % | 395/1 014 — 38,95 % | 93,16 % | 53,53 % |
| Bruit d'électrodes NSTDB, 8 enregistrements | 3,94 h | 97,63 % | 78,96 % | 2/1 808 — 0,11 % | 66,67 % | 69,30 % |

Sur les 44 MIT-BIH (développement inclus), le QRS passe de 93,14 % de sensibilité / 85,16 % de VPP en bêta 1 à 99,73 % / 98,00 %. Ce gain de repérage **ne signifie pas** que l'application détecte autant d'arythmies.

Les faux V conservatifs sont de 30,25/h sur le lot MIT-BIH séparé et 1,25/h sur le lot Icentia. Une référence Q « non classifiable » n'est pas transformée en normal : dans ce protocole conservatif, une prédiction V sans référence V appariée est comptée défavorablement, même face à Q. Il ne s'agit donc pas d'une spécificité clinique établie. Les labels V/E incluent les échappements ventriculaires, pas seulement les extrasystoles.

Les intervalles par bootstrap de 2 000 rééchantillonnages d'enregistrements sont dans `validation/lab-beta2-summary.json`. Ils sont larges : par exemple, sensibilité V Icentia 20,0–43,9 %, VPP 6,1–97,1 %. Ils ne mesurent pas l'incertitude du transfert vers H10. Les transformations NSTDB sont corrélées entre elles et à 118/119 utilisés en développement : ne pas interpréter leur bootstrap comme une validation indépendante.

**Très peu de faux positifs sous bruit ne constitue pas un succès si presque toutes les anomalies sont également rejetées.** Le SQI reste notamment trop restrictif sur certaines amplitudes et formes. Les doubles détections sur certains ECG Icentia restent à résoudre.

## Vérifications logicielles

- Huit suites JVM passent, dont 14 scénarios continus de politique d'abstention : rythme variable, ESV/ESA synthétiques, choc, bruit, plateau de ceinture, ECG propre avec mouvement ACC, effort progressif, tachycardie, bradycardie sans fausse pause de latence, irrégularité, pause et modèle personnel prêt.
- Le test ESA attend un passage à relire, pas une ESA confirmée. Ces 14 scénarios ne représentent pas 14 classifications médicales correctes. La politique de mouvement a explicitement changé : ne plus masquer un vrai signal injecté uniquement parce que l'ACC bouge.
- Parité Python/Java sur 200 vecteurs d'entraînement : erreur absolue maximale des votes `3.3412238498176094e-6`, tolérance `1e-4`.
- Tests de causalité, ordre des indices, retard de confirmation, valeurs non finies, remise à zéro après coupure et blocage des alertes physiques.
- Compilation APK et instrumentation Android : réussite. Lint : zéro erreur, 27 avertissements.
- Pas de téléphone connecté : installation, rendu, PDF, autonomie, Bluetooth réel et écran éteint 24 h **non vérifiés ici**. Les tests d'instrumentation ont été compilés, pas exécutés.

## Reproduction

Python : numpy, scipy, wfdb, scikit-learn. Java 17 ; SDK Android 35 pour compiler l'application.

```text
./gradlew selfTest assembleDebug lintDebug
python tools/train_experimental.py --data ../ecg-ml/data/mitdb --icentia ../ecg-ml/data/icentia11k_subset --java /chemin/java --classpath "app/build/self-tests;app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes" --output build/experimental/multidomain
python tools/benchmark_mitdb.py --data ../ecg-ml/data/mitdb --java /chemin/java --output build/experimental/lab-final-mitdb
```

Sur Unix, remplacer le séparateur de classpath `;` par `:`. Le script d'entraînement exporte les poids, une fixture de parité et les partitions ; il ne remplace pas automatiquement le modèle embarqué. Une nouvelle intégration nécessite de vérifier la parité et de rejouer le pipeline complet. Éviter `--reuse` après toute modification de l'extracteur. Les chemins Icentia exacts d'évaluation sont enregistrés dans le manifeste ; les fournir via `--records` au benchmark. Le bruit utilise `118e00/06/12/24` et `119e00/06/12/24`.

## Sources, usage et prochaine étape

- [MIT-BIH](https://physionet.org/content/mitdb/1.0.0/) : annotations publiques, licence ODC Attribution 1.0.
- [Noise Stress Test](https://physionet.org/content/nstdb/1.0.0/) : bruit de déplacement d'électrodes ajouté aux références 118/119, licence ODC Attribution 1.0.
- [Icentia11k](https://physionet.org/content/icentia11k-continuous-ecg/1.0/) : Tan et al., PhysioNet 2022, DOI 10.13026/kk0v-r952. **CC BY-NC-SA 4.0 : usage non commercial**. Cette version de recherche ne doit pas être présentée comme entièrement couverte par la licence MIT du code. Voir `MODEL_NOTICE.md` avant toute publication ou commercialisation.
- [WFDB/XQRS](https://wfdb.readthedocs.io/en/latest/processing.html#qrs-detectors) : comparateur hors ligne, non embarqué.

Prochaine étape expérimentale utile : corriger les erreurs QRS Icentia à partir des motifs de rejet, découpler le SQI de la morphologie pathologique et comparer plusieurs modèles sur de nouveaux lots réservés. Ne pas réutiliser les résultats finaux ci-dessus comme tests aveugles après réglage. Une relecture qualifiée de données H10 restera nécessaire pour mesurer le transfert vers ce capteur, même sans revendiquer un dispositif médical.
