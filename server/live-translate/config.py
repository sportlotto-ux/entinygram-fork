import os
from pathlib import Path


def _api_key() -> str:
    path = os.environ.get("INU_GEMINI_KEY_FILE")
    if path:
        try:
            return Path(path).read_text().strip()
        except OSError:
            pass
    return os.environ.get("INU_GEMINI_KEY", "")


API_KEY = _api_key()
MODEL = os.environ.get("INU_MODEL", "gemini-3.5-live-translate-preview")
TARGET_IN = os.environ.get("INU_TARGET_IN", "ru")
TARGET_OUT = os.environ.get("INU_TARGET_OUT", "en")
ECHO_TARGET = os.environ.get("INU_ECHO_TARGET", "0") == "1"
TOKENS_FILE = os.environ.get("INU_TOKENS", "/etc/inu-live/tokens.json")

OPUS_BITRATE = int(os.environ.get("INU_OPUS_BITRATE", "32000"))
CLIENT_RATE = 48000
GEMINI_IN_RATE = 16000
GEMINI_OUT_RATE = 24000
CHUNK_MS = 100
SESSION_LIMIT_S = int(os.environ.get("INU_SESSION_LIMIT_S", "540"))
SILENCE_MS = int(os.environ.get("INU_SILENCE_MS", "900"))
