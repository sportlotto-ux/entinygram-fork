"""Emulates the phone over the real WebSocket: opus in, opus + subtitles out."""

import argparse
import asyncio
import json
import os
import sys
import time
import wave

import numpy as np
import websockets

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import config  # noqa: E402
from audio import downmix, resample, to_array, to_pcm  # noqa: E402
from opus import Decoder, Encoder  # noqa: E402

TAG_IN = 0x10
TAG_OUT = 0x11
TAG_AUDIO_IN = 0x20
TAG_AUDIO_OUT = 0x21
FRAME_MS = 20
TRAILING_PAUSE_MS = 1500


def read_wav(path: str) -> np.ndarray:
    with wave.open(path, "rb") as handle:
        channels = handle.getnchannels()
        rate = handle.getframerate()
        frames = handle.readframes(handle.getnframes())
    samples = downmix(np.frombuffer(frames, dtype=np.int16), channels)
    return resample(samples, rate, config.CLIENT_RATE) if rate != config.CLIENT_RATE else samples


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="ws://127.0.0.1:8000/live")
    parser.add_argument("--token", required=True)
    parser.add_argument("--wav", required=True)
    parser.add_argument("--out", default="out")
    parser.add_argument("--tag", choices=["in", "out"], default="in")
    args = parser.parse_args()

    samples = read_wav(args.wav)
    packets = Encoder(config.CLIENT_RATE, bitrate=config.OPUS_BITRATE).encode(to_pcm(samples))
    silence = bytes(config.CLIENT_RATE // 1000 * FRAME_MS * 2)
    tag = TAG_IN if args.tag == "in" else TAG_OUT
    audio_tag = TAG_AUDIO_IN if args.tag == "in" else TAG_AUDIO_OUT
    decoder = Decoder(config.CLIENT_RATE)
    audio: list[np.ndarray] = []
    subtitles: list[dict] = []
    done = asyncio.Event()

    async def receive(ws):
        try:
            async for message in ws:
                if isinstance(message, bytes):
                    if message[0] == audio_tag:
                        audio.append(to_array(decoder.decode(message[1:])))
                else:
                    event = json.loads(message)
                    if event.get("t") == "sub":
                        subtitles.append(event)
                        mark = "*" if event["final"] else " "
                        print(f"{mark} [{event['dir']}] {event['dst']}")
        finally:
            done.set()

    async with websockets.connect(args.url, additional_headers={"x-proxy-token": args.token}) as ws:
        started = time.monotonic()
        task = asyncio.create_task(receive(ws))
        for packet in packets:
            await ws.send(bytes([tag]) + packet)
            await asyncio.sleep(FRAME_MS / 1000)
        for _ in range(TRAILING_PAUSE_MS // FRAME_MS):
            await ws.send(bytes([tag]) + silence[:960 * 2])
            await asyncio.sleep(FRAME_MS / 1000)
        try:
            await asyncio.wait_for(done.wait(), timeout=20)
        except asyncio.TimeoutError:
            pass
        task.cancel()

    os.makedirs(args.out, exist_ok=True)
    if audio:
        merged = np.concatenate(audio)
        with wave.open(os.path.join(args.out, "received.wav"), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(config.CLIENT_RATE)
            handle.writeframes(merged.tobytes())
        print(f"received {merged.size / config.CLIENT_RATE:.2f} s of translated audio")
    print(f"subtitles {len(subtitles)}, wall {time.monotonic() - started:.1f} s")


if __name__ == "__main__":
    asyncio.run(main())
