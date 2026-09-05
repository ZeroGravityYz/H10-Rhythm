# Validation de la 4.0 bêta 1 — 5 septembre 2026

## Conclusion

La refonte logicielle est une bêta d’essai. **La fiabilité médicale demandée n’est pas atteinte.** Les scénarios synthétiques passent, mais le moteur manque de nombreux battements anormaux sur une base externe. Aucun objectif de 99,9 %, aucune supériorité sur les concurrents et aucune validation clinique H10 ne sont revendiqués.

## Vérifications exécutées

- Compilation APK debug, APK des tests Android et analyse Android Lint : réussite, zéro erreur et 27 avertissements non bloquants au contrôle final (notamment internationalisation, constructeurs de vues et persistance synchrone volontaire).
- Sept suites JVM : moteur, morphologie, ACC, régression du modèle de forme, 14 scénarios continus, horloge PMD et garde-fous de fiabilité.
- Cas ajoutés : retard variable des paquets, doublons, ordre inversé, alignement ECG/ACC, trou du flux, reconnexion ; conservation de deux artefacts opposés ; annotations contradictoires ; remise à zéro ; RMSSD sans raccord entre intervalles rejetés ; absence de pause créée par une discontinuité ; seuil de publication prospective.
- Évaluation exploratoire sur 44 enregistrements MIT-BIH non principalement stimulés, soit 21,68 h après retrait des marges de début/fin.

## Évaluation externe : résultats défavorables

[MIT-BIH v1.0.0](https://physionet.org/content/mitdb/1.0.0/) est une base publique annotée, différente de la dérivation pectorale H10. Elle sert ici de test de résistance hors domaine, **pas** de preuve clinique sur ce capteur.

Protocole fixé dans tools/benchmark_mitdb.py : canal MLII si présent, sinon premier canal ; amplitudes mV converties en µV ; rééchantillonnage polyphasé à 130 Hz ; moteur Java réel ; aucune annotation fournie à l’apprentissage ; référence personnelle automatiquement sélectionnée ; 30 s de stabilisation retirées, 2 s de marge finale ; appariement individuel à ±150 ms. Les étiquettes des battements sont évaluées avant regroupement et temporisation des notifications. Les rejets ne sont pas comptés comme des vrais négatifs réussis.

| Mesure | Résultat |
| --- | ---: |
| Sensibilité de repérage QRS | 93,14 % |
| Valeur prédictive positive QRS | 85,16 % |
| Sensibilité ventriculaire V/E | 0,42 % — 29 détectés sur 6 900 |
| VPP ventriculaire | 29,29 % — 70 faux positifs |
| Faux positifs ventriculaires / heure | 3,23 |
| Sensibilité supraventriculaire A/a/J/S | 1,45 % — 40 détectés sur 2 754 |
| VPP supraventriculaire | 26,85 % — 109 faux positifs |
| Faux positifs supraventriculaires / heure | 5,03 |

Ces résultats interdisent de présenter le modèle comme un classifieur fiable et exhaustif. Les règles visent certains motifs temporels seulement ; cette limitation n’efface pas les événements manqués. Les formes et les seuils nécessitent une nouvelle phase de développement, puis un véritable jeu de validation indépendant. Aucun seuil n’a été ajusté sur ces résultats pour annoncer artificiellement une amélioration.

Le [résultat complet par enregistrement](validation/mitdb-beta1.json) inclut les effectifs et les SHA-256 des signaux sources. Il n’inclut aucun ECG privé de l’utilisateur.

Reproduction après compilation :

```bash
python tools/benchmark_mitdb.py --data /chemin/mitdb --java /chemin/jdk17/bin/java
```

Dépendances d’analyse utilisées : Python 3.12, numpy 2.5.2, scipy 1.18.0, wfdb 4.3.1. Les données ne sont pas incorporées à l’APK. Source : Moody GB, Mark RG, The impact of the MIT-BIH Arrhythmia Database, IEEE Engineering in Medicine and Biology 20(3):45–50, 2001. Licence des fichiers : Open Data Commons Attribution 1.0, distincte de la licence MIT du code.

## Contrôles préparés, mais non exécutés sur Android ici

Le test StorageInstrumentation utilise une base et des préférences isolées : migration et idempotence, conservation des annotations après suppression d’un événement, filtre sur 10 000 entrées, prévision non réécrivable, journée inconnue distincte du repos, conservation des extrêmes signés 24 bits.

Aucun téléphone n’était connecté via ADB et aucun émulateur n’était configuré. Les éléments suivants restent donc à vérifier :

- installation en mise à jour et migration sur une copie d’historique réel ;
- rendu visuel de l’interface, clavier, grandes polices, orientation et navigation système ;
- rendu réel des PDF multipages et mesure de la durée de création ;
- lecture et partage des fichiers sur Android 8–9 et versions récentes ;
- latence de recherche sur 10 000 entrées : objectif 200 ms, non mesuré sur S24 Ultra ;
- objectif PDF en moins de 5 s : non mesuré sur S24 Ultra ;
- BLE avec capteur réel, batterie, ACC, arrêt/reprise, pertes de signal et écran éteint sur 24 h ;
- comportement sous stockage plein et arrêt forcé du processus.

La reprise de PDF requiert un JSONL déjà conservé. Elle ne recrée pas des échantillons perdus avant leur écriture. Les captures en mémoire peuvent être interrompues par un arrêt forcé.

## Reste à faire avant une version dite fiable

Validation sur H10 face à un ECG de référence annoté en aveugle, quantification des faux positifs et événements manqués, revue du détecteur QRS et des règles d’ectopie, essais prospectifs du suivi sportif, vérification visuelle et matérielle des points ci-dessus. La conformité d’une distribution en tant que dispositif médical n’a pas été évaluée.

Les repères généraux d’activité proviennent des [recommandations OMS 2020](https://www.who.int/publications/i/item/9789240015128). Les sorties personnelles du modèle n’héritent pas d’une validation médicale parce qu’elles citent ces recommandations.
