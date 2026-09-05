# Notice des données et du modèle expérimental

Le code applicatif sous `LICENSE` reste sous licence MIT. Les poids de laboratoire dans `ExperimentalWeights.java` ne sont pas proposés comme des poids librement commercialisables sous cette licence MIT.

Leur entraînement utilise MIT-BIH (ODC Attribution 1.0) et Icentia11k (Creative Commons Attribution-NonCommercial-ShareAlike 4.0). Conserver cette attribution et les conditions de la source ; usage de recherche non commercial uniquement pour le paquet de modèle fourni ici. Toute distribution commerciale de ce paquet nécessite une clarification/autorisation adaptée auprès des titulaires des droits.

Icentia11k : Shawn Tan, Satya Ortiz-Gagné, Nicolas Beaudoin-Gagnon, Pierre Fecteau, Aaron Courville, Yoshua Bengio, Joseph Paul Cohen. Icentia11k Single Lead Continuous Raw Electrocardiogram Dataset (version 1.0), PhysioNet, 2022. DOI https://doi.org/10.13026/kk0v-r952. Source : https://physionet.org/content/icentia11k-continuous-ecg/1.0/. Licence : https://creativecommons.org/licenses/by-nc-sa/4.0/.

MIT-BIH : George B. Moody, Roger G. Mark. The impact of the MIT-BIH Arrhythmia Database. IEEE Engineering in Medicine and Biology, 20(3):45–50, 2001. Source : https://physionet.org/content/mitdb/1.0.0/.

NSTDB (évaluation, pas entraînement de la forêt) : Moody GB, Muldrow WE, Mark RG. A noise stress test for arrhythmia detectors. Computers in Cardiology 1984;11:381–384. Source : https://physionet.org/content/nstdb/1.0.0/.

Transformations réalisées : rééchantillonnage 130 Hz, caractéristiques temporelles et morphologiques, forêt supervisée, quantification des votes, compression gzip et export Java. Il ne s'agit pas d'un modèle fourni, approuvé ou validé par les auteurs des bases. Les ECG sources et les ECG privés de l'utilisateur ne sont pas inclus dans l'APK. Aucun diagnostic ni validation médicale n'est revendiqué.
