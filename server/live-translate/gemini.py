import asyncio
import base64
import json
import logging
import time

import websockets

log = logging.getLogger("inu.live")

FINAL_IDLE_S = 4.0

_ENDPOINT = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={key}"
)


class LiveTranslate:
    """One Gemini Live translation session, reconnecting on its own when the ten minute limit hits."""

    def __init__(
        self,
        api_key: str,
        model: str,
        target_lang: str,
        input_rate: int = 16000,
        echo: bool = False,
        session_limit: int = 540,
        silence_ms: int = 700,
        on_audio=None,
        on_text=None,
        on_state=None,
    ):
        self.api_key = api_key
        self.model = model
        self.target_lang = target_lang
        self.input_rate = input_rate
        self.echo = echo
        self.session_limit = session_limit
        self.silence_ms = silence_ms
        self.on_audio = on_audio or (lambda pcm: None)
        self.on_text = on_text or (lambda event: None)
        self.on_state = on_state or (lambda state, detail=None: None)

        self._queue: asyncio.Queue = asyncio.Queue(maxsize=20)
        self._task = None
        self._closed = False
        self._partial_in = ""
        self._partial_out = ""
        self._last_text = 0.0
        self._last_activity = 0.0
        self._last_emitted = ("", "")

    def setup_message(self) -> dict:
        return {
            "setup": {
                "model": f"models/{self.model}",
                "generationConfig": {
                    "responseModalities": ["AUDIO"],
                    "translationConfig": {
                        "targetLanguageCode": self.target_lang,
                        "echoTargetLanguage": self.echo,
                    },
                },
                "inputAudioTranscription": {},
                "outputAudioTranscription": {},
            }
        }

    async def start(self):
        self._closed = False
        self._task = asyncio.create_task(self._run())

    async def send(self, pcm: bytes):
        if self._queue.full():
            try:
                self._queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
        try:
            self._queue.put_nowait(pcm)
        except asyncio.QueueFull:
            pass

    def last_activity(self) -> float:
        return self._last_activity

    async def close(self):
        self._closed = True
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
            self._task = None

    async def _run(self):
        backoff = 1.0
        while not self._closed:
            try:
                await self._session()
                backoff = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as error:
                log.warning("gemini session failed: %s", error)
                self.on_state("error", str(error))
            if self._closed:
                break
            self.on_state("reconnecting")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 10.0)

    async def _session(self):
        url = _ENDPOINT.format(key=self.api_key)
        async with websockets.connect(url, max_size=1 << 24, ping_interval=20, ping_timeout=20) as ws:
            await ws.send(json.dumps(self.setup_message()))
            first = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
            if "setupComplete" not in first:
                raise RuntimeError(f"setup rejected: {first}")

            log.info("gemini session ready (target %s)", self.target_lang)
            self.on_state("ready")
            started = time.monotonic()
            tasks = [asyncio.create_task(self._pump(ws, started)), asyncio.create_task(self._read(ws))]
            try:
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
                for task in done:
                    if task.exception():
                        raise task.exception()
            finally:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                self._emit_text(True)

    async def _pump(self, ws, started):
        while True:
            try:
                pcm = await asyncio.wait_for(self._queue.get(), timeout=0.5)
            except asyncio.TimeoutError:
                if time.monotonic() - started >= self.session_limit:
                    log.info("gemini session limit reached, reopening")
                    return
                continue
            payload = base64.b64encode(pcm).decode("ascii")
            message = {"realtimeInput": {"audio": {"data": payload, "mimeType": f"audio/pcm;rate={self.input_rate}"}}}
            await ws.send(json.dumps(message))

    async def _read(self, ws):
        while True:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=0.25)
            except asyncio.TimeoutError:
                if self._text_ready():
                    self._emit_text(True)
                continue

            self._last_activity = time.monotonic()
            if isinstance(raw, bytes):
                raw = raw.decode("utf-8", "replace")
            message = json.loads(raw)
            if message.get("goAway"):
                log.info("gemini go away: %s", message["goAway"])
                return

            content = message.get("serverContent") or {}
            if content.get("interrupted"):
                self._partial_out = ""
                self._emit_text(True)

            changed = False
            input_text = (content.get("inputTranscription") or {}).get("text")
            if input_text:
                self._partial_in += input_text
                changed = True
            output_text = (content.get("outputTranscription") or {}).get("text")
            if output_text:
                self._partial_out += output_text
                changed = True
            if changed:
                self._last_text = time.monotonic()
                self._emit_text(False)

            turn = content.get("modelTurn") or {}
            for part in turn.get("parts") or []:
                data = (part.get("inlineData") or {}).get("data")
                if data:
                    self.on_audio(base64.b64decode(data))

            if content.get("turnComplete"):
                self._emit_text(True)

    def _text_ready(self) -> bool:
        if not self._partial_in and not self._partial_out:
            return False
        idle = time.monotonic() - self._last_text
        if idle >= FINAL_IDLE_S:
            return True
        if idle < self.silence_ms / 1000:
            return False
        text = self._partial_out.strip() or self._partial_in.strip()
        return text.endswith((".", "?", "!", "…"))

    def _emit_text(self, final: bool):
        source = self._partial_in.strip()
        target = self._partial_out.strip()
        if not target:
            if final:
                self._partial_in = ""
                self._partial_out = ""
                self._last_text = 0.0
            return
        if not final and (source, target) == self._last_emitted:
            return
        self._last_emitted = (source, target)
        self.on_text({"src": source, "dst": target, "final": final})
        if final:
            self._partial_in = ""
            self._partial_out = ""
            self._last_text = 0.0
