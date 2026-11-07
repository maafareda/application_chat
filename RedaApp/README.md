# Reda_App

Application JavaFX de discussion inspirée de WhatsApp : conversations entre
plusieurs utilisateurs, messages persistants, emojis et messages vocaux.

## Démarrer sans ouvrir de terminal

Double-cliquez sur `Start-RedaApp.bat`. Le lanceur démarre le serveur en arrière-plan
si nécessaire, puis ouvre la fenêtre de discussion. Fermez la fenêtre pour quitter
un utilisateur.

Pour ouvrir un autre utilisateur sur le même ordinateur, double-cliquez une deuxième
fois sur le même fichier. Depuis IntelliJ, lancez deux fois `RedaApp` avec des noms
d'utilisateur différents : le premier lancement démarre automatiquement le serveur
local et le deuxième s'y connecte. Le serveur utilise le port `6000` par défaut.
Dans la fenêtre de chaque client, attendez le statut `En ligne`; les autres noms
d'utilisateur apparaissent ensuite dans la liste des discussions.

Ne lancez pas `ChatServer` séparément sur le même port lorsque le démarrage
automatique local est utilisé. Si le serveur et les clients fonctionnent sur des
ordinateurs différents, démarrez `ChatServer` une fois sur l'ordinateur serveur,
puis configurez `REDA_APP_HOST` avec son adresse IP pour les clients.

Un nom peut être choisi à l'avance depuis PowerShell :

```powershell
.\Start-RedaApp.bat Alice
```

Il faut installer/configurer le JDK 21 dans IntelliJ. Le serveur écoute sur le port
`6000` et enregistre les données dans `reda_app.db`, dans le répertoire de travail
du serveur. `REDA_APP_PORT`, `REDA_APP_DB` et
`REDA_APP_HOST` permettent de personnaliser le port, la base et l'adresse du serveur.
Pour choisir un autre port au lancement, indiquez le nom puis le port, par exemple
`.\Start-RedaApp.bat Alice 6100`. En relançant le même fichier avec le même port,
le serveur n'est pas redémarré : une nouvelle fenêtre utilisateur est ouverte.
Pour une connexion entre plusieurs ordinateurs, configurez `REDA_APP_HOST` avec
l'adresse IP de l'ordinateur qui héberge le serveur.

## Discussions, emojis et messages vocaux

- Sélectionnez un utilisateur pour afficher sa conversation et son historique.
- Le bouton `😊 Emojis` ouvre un choix de plus de 80 emojis.
- Le bouton `🎙️ Enregistrer` démarre l'enregistrement avec le microphone. Appuyez
  une deuxième fois sur `⏹ Arrêter` pour envoyer le message vocal. La personne
  destinataire peut l'écouter ou l'enregistrer. Les messages vocaux sont au format
  WAV et limités en taille.
- Les boutons Image et Fichier permettent aussi d'envoyer des pièces jointes de
  5 Mo maximum.

L'accès au microphone dépend des autorisations Windows et de la disponibilité d'un
microphone. L'enregistrement et la lecture audio sont prévus pour l'application
JavaFX de bureau.

## Conversation avec l'assistant

La conversation `Assistant IA` se trouve dans son propre onglet et son historique
reste séparé des conversations entre utilisateurs.

L'assistant fonctionne même sans Python : en premier, il contacte un modèle local
Ollama, puis l'API Flask si elle est lancée, puis utilise ses réponses Reda_App hors
ligne si aucun service LLM n'est disponible. Le nom du mode utilisé est affiché
au-dessus de la conversation. Le mode hors ligne fournit de l'aide sur les messages,
les emojis, les fichiers et l'enregistrement audio ; ce n'est pas un modèle LLM.

Pour activer les réponses générées par un vrai modèle LLM local, installez
[Ollama](https://ollama.com/) et son petit modèle :

```powershell
ollama pull llama3.2:1b
```

Gardez Ollama actif, puis ouvrez `Assistant IA`. L'application utilise Ollama à
`http://localhost:11434` par défaut. Les variables `REDA_APP_OLLAMA_MODEL` et
`REDA_APP_OLLAMA_URL` permettent de choisir un autre modèle ou une autre adresse.

L'API Flask facultative peut également être démarrée dans un terminal si Python est
installé :

```powershell
py -m pip install -r requirements.txt
py llm_api.py
```

## Tests

```powershell
.\mvnw.cmd clean test
```

Les tests vérifient la connexion de deux utilisateurs, l'envoi d'un message, son
historique et les réponses disponibles hors ligne.
