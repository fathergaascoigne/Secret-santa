# Secret Santa Android 🎅

Application Android native en Kotlin pour organiser un Secret Santa et envoyer les attributions par SMS via la SIM du téléphone, sans serveur.

## APK automatique
Ouvrir **Actions → Build Android APK**, sélectionner **Run workflow**, puis télécharger l'artefact **SecretSanta-debug-apk** après compilation. Extraire l'APK et l'installer sur Android. Le workflow se lance aussi lors d'un push sur main.

## Utilisation
Ajouter au moins deux participants et leurs numéros, configurer les exclusions mutuelles, le budget et le message, puis confirmer le tirage et l'envoi. Accorder l'autorisation SMS sur Android. Tester d'abord avec des numéros consentants.

## Confidentialité et limites
Les attributions ne sont ni affichées ni sauvegardées par l'application. Les SMS ne sont pas volontairement ajoutés à l'historique Messages, mais l'opérateur ou le système peuvent conserver des traces. Le succès d'envoi réseau ne prouve pas la réception. En cas d'échec partiel, **ne pas relancer un nouveau tirage**, car certains participants pourraient avoir reçu leur résultat. L'application doit rester ouverte pendant l'envoi. Les SMS peuvent être facturés. Maximum 30 participants. Les tirages avec plus de 10 personnes utilisent un algorithme aléatoire non uniforme.

La compilation et les SMS réels doivent être validés avant usage.
