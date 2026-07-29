"""Small stdlib Gemini generateContent client shared by research scripts."""
import json
import os
import time
import urllib.error
import urllib.request


ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


def load_api_key(env_path):
    key = os.environ.get("GEMINI_API_KEY")
    if key:
        return key
    with open(env_path) as stream:
        for line in stream:
            line = line.strip()
            if line.startswith("GEMINI_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("GEMINI_API_KEY를 .env에서 찾지 못했습니다")


def call_gemini(model, api_key, system_prompt, user_text, max_tokens, retries=4, schema=None):
    generation = {"temperature": 0, "maxOutputTokens": max_tokens,
                  "thinkingConfig": {"thinkingBudget": 0}}
    if schema is not None:
        generation["responseMimeType"] = "application/json"
        generation["responseSchema"] = schema
    body = json.dumps({
        "systemInstruction": {"parts": [{"text": system_prompt}]},
        "contents": [{"role": "user", "parts": [{"text": user_text}]}],
        "generationConfig": generation,
    }).encode()
    request = urllib.request.Request(
        ENDPOINT.format(model=model), data=body,
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                payload = json.load(response)
            candidates = payload.get("candidates") or []
            if not candidates:
                return None, "no_candidate"
            parts = candidates[0].get("content", {}).get("parts") or []
            text = "".join(part.get("text", "") for part in parts)
            return (text, None) if text else (None, f"empty:{candidates[0].get('finishReason')}")
        except urllib.error.HTTPError as error:
            if error.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                time.sleep(2 ** attempt)
                continue
            return None, f"http_{error.code}"
        except Exception as error:
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
                continue
            return None, f"error:{type(error).__name__}"
    return None, "retries_exhausted"
