import asyncio
import logging
import os
import re

import numpy as np

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

import config
import tokens
from audio import Chunker, downmix, resample, to_array, to_pcm
from gemini import LiveTranslate
from opus import Decoder, Encoder

TAG_IN = 0x10
TAG_OUT = 0x11
TAG_AUDIO_IN = 0x20
TAG_AUDIO_OUT = 0x21
SILENCE_RMS = 0.002
LANG_RE = re.compile(r"^[a-z]{2,3}(-[a-zA-Z]{2,4})?$")


def lang_param(value: str, fallback: str) -> str:
    value = (value or "").strip()
    return value if LANG_RE.match(value) else fallback

logging.basicConfig(
    level=os.environ.get("INU_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("inu.live")
app = FastAPI()


@app.get("/health")
async def health():
    return {"ok": True, "model": config.MODEL, "targets": [config.TARGET_IN, config.TARGET_OUT]}


class Client:
    def __init__(self, ws: WebSocket, target_in: str, target_out: str):
        self.ws = ws
        self.target_in = target_in
        self.target_out = target_out
        self.lock = asyncio.Lock()
        self.decoder = Decoder(config.CLIENT_RATE)
        self.encoders = {}
        self.chunkers = {}
        self.sessions = {}

    async def handle(self, data: bytes):
        if not data:
            return
        tag, payload = data[0], data[1:]
        if tag not in (TAG_IN, TAG_OUT) or not payload:
            return
        direction = "in" if tag == TAG_IN else "out"
        try:
            pcm = downmix(to_array(self.decoder.decode(payload)), 1)
        except Exception as error:
            log.debug("drop undecodable packet: %s", error)
            return
        session = await self.session(direction)
        for chunk in self.chunker(direction).push(resample(pcm, config.CLIENT_RATE, config.GEMINI_IN_RATE)):
            await session.send(to_pcm(chunk))

    async def session(self, direction: str) -> LiveTranslate:
        existing = self.sessions.get(direction)
        if existing is not None:
            return existing
        tag = TAG_AUDIO_IN if direction == "in" else TAG_AUDIO_OUT
        session = LiveTranslate(
            api_key=config.API_KEY,
            model=config.MODEL,
            target_lang=self.target_in if direction == "in" else self.target_out,
            input_rate=config.GEMINI_IN_RATE,
            echo=config.ECHO_TARGET,
            session_limit=config.SESSION_LIMIT_S,
            silence_ms=config.SILENCE_MS,
            on_audio=lambda pcm: asyncio.create_task(self.on_audio(tag, pcm)),
            on_text=lambda event: asyncio.create_task(self.on_text(direction, event)),
            on_state=lambda state, detail=None: asyncio.create_task(self.on_state(direction, state, detail)),
        )
        await session.start()
        self.sessions[direction] = session
        return session

    def chunker(self, direction: str) -> Chunker:
        chunker = self.chunkers.get(direction)
        if chunker is None:
            chunker = Chunker(config.GEMINI_IN_RATE, config.CHUNK_MS)
            self.chunkers[direction] = chunker
        return chunker

    def encoder(self, tag: int) -> Encoder:
        encoder = self.encoders.get(tag)
        if encoder is None:
            encoder = Encoder(config.CLIENT_RATE, bitrate=config.OPUS_BITRATE)
            self.encoders[tag] = encoder
        return encoder

    async def on_audio(self, tag: int, pcm: bytes):
        raw = to_array(pcm)
        if raw.size and float(np.sqrt(np.mean((raw.astype(np.float32) / 32768) ** 2))) < SILENCE_RMS:
            return
        samples = resample(raw, config.GEMINI_OUT_RATE, config.CLIENT_RATE)
        for packet in self.encoder(tag).encode(to_pcm(samples)):
            await self.send(bytes([tag]) + packet)

    async def on_text(self, direction: str, event: dict):
        await self.send_json({
            "t": "sub",
            "dir": direction,
            "src": event["src"],
            "dst": event["dst"],
            "final": event["final"],
        })

    async def on_state(self, direction: str, state: str, detail: str | None):
        message = {"t": "state", "dir": direction, "gemini": state}
        if detail:
            message["detail"] = detail
        await self.send_json(message)

    async def send(self, data: bytes):
        async with self.lock:
            await self.ws.send_bytes(data)

    async def send_json(self, payload: dict):
        async with self.lock:
            await self.ws.send_json(payload)

    async def close(self):
        for session in self.sessions.values():
            await session.close()
        self.sessions.clear()


@app.websocket("/live")
async def live(ws: WebSocket):
    name = tokens.resolve(config.TOKENS_FILE, ws.headers.get("x-proxy-token", ""))
    if not name:
        await ws.accept()
        await ws.close(code=4401)
        return
    await ws.accept()
    query = ws.query_params
    client = Client(
        ws,
        lang_param(query.get("in", ""), config.TARGET_IN),
        lang_param(query.get("out", ""), config.TARGET_OUT),
    )
    log.info("client %s connected", name)
    try:
        while True:
            message = await ws.receive()
            if message["type"] == "websocket.disconnect":
                break
            data = message.get("bytes")
            if data:
                await client.handle(data)
    except WebSocketDisconnect:
        pass
    finally:
        await client.close()
        log.info("client %s disconnected", name)
