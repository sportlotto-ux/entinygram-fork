"""Offline check of the whole audio path: WAV -> opus -> PCM 16 kHz -> Gemini -> SRT + WAV."""

import argparse
import asyncio
import os
import sys
import time
import wave

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import config  # noqa: E402
from audio import Chunker, downmix, resample, to_array, to_pcm  # noqa: E402
from opus import Decoder, Encoder  # noqa: E402


TRAILING_PAUSE_MS = 1500


def read_wav(path: str) -> tuple[np.ndarray, int, int]:
    with wave.open(path, "rb") as handle:
        channels = handle.getnchannels()
        width = handle.getsampwidth()
        rate = handle.getframerate()
        frames = handle.readframes(handle.getnframes())
    if width != 2:
        raise SystemExit("only 16-bit PCM wav is supported")
    return np.frombuffer(frames, dtype=np.int16), rate, channels


def write_wav(path: str, samples: np.ndarray, rate: int):
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(samples.astype(np.int16).tobytes())


def srt_timestamp(seconds: float) -> str:
    millis = int(round(seconds * 1000))
    hours, millis = divmod(millis, 3_600_000)
    minutes, millis = divmod(millis, 60_000)
    secs, millis = divmod(millis, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"


def write_srt(path: str, segments: list[tuple[float, float, str]]):
    lines = []
    for index, (start, end, text) in enumerate(segments, start=1):
        lines.append(str(index))
        lines.append(f"{srt_timestamp(start)} --> {srt_timestamp(max(start + 0.2, end))}")
        lines.append(text)
        lines.append("")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


class Segments:
    def __init__(self):
        self.items: list[tuple[float, float, str]] = []
        self.started: float | None = None
        self.text = ""

    def feed(self, event: dict, now: float):
        if event["dst"]:
            if self.started is None:
                self.started = now
            self.text = event["dst"]
        if event["final"] and self.text:
            self.items.append((self.started or now, now, self.text))
            self.started = None
            self.text = ""


def encode_opus(samples48: np.ndarray, bitrate: int) -> list[bytes]:
    encoder = Encoder(config.CLIENT_RATE, bitrate=bitrate)
    return encoder.encode(to_pcm(samples48))


def decode_opus(packets: list[bytes]) -> np.ndarray:
    decoder = Decoder(config.CLIENT_RATE)
    return np.concatenate([to_array(decoder.decode(packet)) for packet in packets])


async def run_gemini(chunks: list[np.ndarray], target: str, out_dir: str, silence_ms: int, quiet: float = 1.5):
    from gemini import LiveTranslate

    os.makedirs(out_dir, exist_ok=True)
    audio: list[np.ndarray] = []
    segments = Segments()
    ready_at: list[float] = []
    first_audio: list[float] = []
    spawned = time.monotonic()

    def on_audio(pcm: bytes):
        if not first_audio:
            first_audio.append(time.monotonic() - spawned)
        audio.append(to_array(pcm))

    def on_text(event: dict):
        segments.feed(event, time.monotonic() - spawned)

    def on_state(state: str, detail=None):
        print(f"  gemini {state}{': ' + detail if detail else ''}")
        if state == "ready" and not ready_at:
            ready_at.append(time.monotonic())

    session = LiveTranslate(
        api_key=config.API_KEY,
        model=config.MODEL,
        target_lang=target,
        input_rate=config.GEMINI_IN_RATE,
        echo=config.ECHO_TARGET,
        session_limit=config.SESSION_LIMIT_S,
        silence_ms=silence_ms,
        on_audio=on_audio,
        on_text=on_text,
        on_state=on_state,
    )
    if not config.API_KEY:
        raise SystemExit("INU_GEMINI_KEY is not set")

    await session.start()
    while not ready_at and time.monotonic() - spawned < 20:
        await asyncio.sleep(0.05)
    setup = (ready_at[0] - spawned) if ready_at else None
    spawned = time.monotonic()
    first_audio.clear()

    for chunk in chunks:
        await session.send(to_pcm(chunk))
        await asyncio.sleep(config.CHUNK_MS / 1000)
    # a pause after speech is what lets the model finish the last utterance
    for _ in range(TRAILING_PAUSE_MS // config.CHUNK_MS):
        await session.send(to_pcm(np.zeros(config.GEMINI_IN_RATE * config.CHUNK_MS // 1000, dtype=np.int16)))
        await asyncio.sleep(config.CHUNK_MS / 1000)

    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        last = session.last_activity()
        if last and time.monotonic() - last > quiet and not segments.text:
            break
        await asyncio.sleep(0.1)
    await session.close()

    if audio:
        write_wav(os.path.join(out_dir, "translated.wav"), np.concatenate(audio), config.GEMINI_OUT_RATE)
    write_srt(os.path.join(out_dir, "subtitles.srt"), segments.items)
    return setup, (first_audio[0] if first_audio else None), len(audio), segments.items


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--wav", required=True)
    parser.add_argument("--target", default=config.TARGET_IN)
    parser.add_argument("--out", default="out")
    parser.add_argument("--bitrate", type=int, default=config.OPUS_BITRATE)
    parser.add_argument("--quiet", type=float, default=1.5, help="seconds of silence that end the check")
    parser.add_argument("--no-gemini", action="store_true")
    args = parser.parse_args()

    samples, rate, channels = read_wav(args.wav)
    samples = downmix(samples, channels)
    samples48 = resample(samples, rate, config.CLIENT_RATE) if rate != config.CLIENT_RATE else samples
    duration = samples48.size / config.CLIENT_RATE
    print(f"input {duration:.2f} s, {rate} Hz, {channels} ch")

    packets = encode_opus(samples48, args.bitrate)
    opus_bytes = sum(len(packet) for packet in packets)
    raw_bytes = samples48.size * 2
    print(f"opus {len(packets)} packets, {opus_bytes / 1024:.1f} KiB, ratio {raw_bytes / max(opus_bytes, 1):.1f}x")

    decoded = decode_opus(packets)
    print(f"opus roundtrip {decoded.size / config.CLIENT_RATE:.2f} s (lossy, expected)")

    chunker = Chunker(config.GEMINI_IN_RATE, config.CHUNK_MS)
    chunks = chunker.push(resample(decoded, config.CLIENT_RATE, config.GEMINI_IN_RATE))
    print(f"chunks to gemini {len(chunks)} x {config.CHUNK_MS} ms @ {config.GEMINI_IN_RATE} Hz")

    if args.no_gemini:
        write_wav(os.path.join(make_dir(args.out), "decode_check.wav"), decoded, config.CLIENT_RATE)
        print("codec path ok (--no-gemini, gemini skipped)")
        return 0

    setup, first_audio, audio_chunks, items = asyncio.run(run_gemini(chunks, args.target, args.out, config.SILENCE_MS, args.quiet))
    print(f"session setup {(setup or 0) * 1000:.0f} ms")
    print(f"first audio {first_audio * 1000:.0f} ms" if first_audio else "no audio back")
    print(f"out chunks {audio_chunks}, subtitles {len(items)}")
    print(f"written {args.out}/translated.wav and {args.out}/subtitles.srt")
    return 0


def make_dir(path: str) -> str:
    os.makedirs(path, exist_ok=True)
    return path


if __name__ == "__main__":
    raise SystemExit(main())
