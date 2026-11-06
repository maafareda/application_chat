import json
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from flask import Flask, Response, jsonify, request

app = Flask(__name__)

OLLAMA_URL = os.getenv("REDA_APP_OLLAMA_URL", "http://localhost:11434/api/generate")
OLLAMA_MODEL = os.getenv("REDA_APP_OLLAMA_MODEL", "llama3.2:1b")
SYSTEM_PROMPT = (
    "Tu es l'assistant de Reda_App. Réponds simplement en français et aide uniquement "
    "sur l'utilisation du chat, les messages, les pièces jointes et les problèmes de connexion."
)
FALLBACKS = {
    "bonjour": "Bonjour ! Je peux vous aider à utiliser Reda_App.",
    "salut": "Salut ! Posez-moi une question sur Reda_App.",
    "aide": "Choisissez un utilisateur, ouvrez la discussion, puis écrivez votre message. "
    "Les boutons permettent aussi de joindre une image, un fichier ou un audio.",
    "fichier": "Utilisez le bouton Fichier, choisissez un fichier de 5 Mo maximum, "
    "puis sélectionnez une discussion.",
    "image": "Utilisez le bouton Image pour joindre une image de 5 Mo maximum.",
    "audio": "Utilisez le bouton Audio pour choisir un fichier audio de 5 Mo maximum.",
    "connexion": "Vérifiez que le serveur Java est lancé sur le port 6000 et que "
    "REDA_APP_HOST désigne l'adresse de cet ordinateur.",
    "serveur": "Le serveur accepte plusieurs connexions TCP simultanées, une socket "
    "et un thread étant dédiés à chaque client.",
    "socket": "Une socket TCP fournit une connexion fiable entre le client et le serveur.",
}


def fallback_answer(question):
    normalized = question.casefold()
    for keyword, answer in FALLBACKS.items():
        if keyword in normalized:
            return answer
    return (
        "Je ne trouve pas encore de réponse précise. Essayez de demander de l'aide sur "
        "les messages, les images, les fichiers ou la connexion au serveur."
    )


def ask_ollama(question):
    body = json.dumps({
        "model": OLLAMA_MODEL,
        "system": SYSTEM_PROMPT,
        "prompt": question,
        "stream": False,
        "options": {"temperature": 0.3},
    }).encode("utf-8")
    request_to_model = Request(
        OLLAMA_URL,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request_to_model, timeout=8) as response:
        result = json.loads(response.read().decode("utf-8"))
    answer = result.get("response", "").strip()
    if not answer:
        raise ValueError("Le modèle n'a renvoyé aucune réponse.")
    return answer


@app.get("/health")
def health():
    return jsonify({"service": "reda-app-assistant", "model": OLLAMA_MODEL})


@app.post("/ask")
def ask():
    question = (request.form.get("q") or "").strip()
    if not question and request.is_json:
        body = request.get_json(silent=True) or {}
        if isinstance(body, dict):
            question = str(body.get("q") or "").strip()
    if not question:
        return Response("La question ne peut pas être vide.", status=400, mimetype="text/plain")
    if len(question) > 2000:
        return Response("La question est limitée à 2 000 caractères.", status=400, mimetype="text/plain")

    try:
        answer = ask_ollama(question)
    except (HTTPError, URLError, TimeoutError, ValueError, json.JSONDecodeError) as error:
        app.logger.info("Ollama indisponible; réponse de secours utilisée : %s", error)
        answer = (
            "Le modèle Ollama local n'est pas disponible. Réponse de secours : "
            + fallback_answer(question)
        )
    return Response(answer, mimetype="text/plain; charset=utf-8")


if __name__ == "__main__":
    host = os.getenv("REDA_APP_AI_HOST", "0.0.0.0")
    port = int(os.getenv("REDA_APP_AI_PORT", "5000"))
    print(f"Assistant Reda_App prêt sur http://{host}:{port}")
    app.run(host=host, port=port, threaded=True)