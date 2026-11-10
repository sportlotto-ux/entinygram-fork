from math import gcd

import numpy as np


def _lowpass(taps: int, cutoff: float) -> np.ndarray:
    n = np.arange(taps) - (taps - 1) / 2.0
    window = np.hamming(taps)
    kernel = np.sinc(cutoff * n) * window
    return kernel / kernel.sum()


def _convolve(x: np.ndarray, kernel: np.ndarray) -> np.ndarray:
    return np.convolve(x, kernel, mode="same")


def resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst or x.size == 0:
        return x.astype(np.int16)
    divisor = gcd(src, dst)
    up, down = dst // divisor, src // divisor
    y = x.astype(np.float32)
    if up > 1:
        padded = np.zeros(y.size * up, dtype=np.float32)
        padded[::up] = y
        y = _convolve(padded, _lowpass(63, 0.5)) * up
    if down > 1:
        y = _convolve(y, _lowpass(63, 1.0 / down))[::down]
    return np.clip(np.rint(y), -32768, 32767).astype(np.int16)


def to_array(pcm: bytes) -> np.ndarray:
    return np.frombuffer(pcm, dtype=np.int16)


def to_pcm(samples: np.ndarray) -> bytes:
    return samples.astype(np.int16).tobytes()


def downmix(samples: np.ndarray, channels: int) -> np.ndarray:
    if channels == 1:
        return samples
    return samples.reshape(-1, channels).mean(axis=1).astype(np.int16)


class Chunker:
    """Accumulates PCM so the model always receives whole chunks."""

    def __init__(self, rate: int, ms: int):
        self.size = rate * ms // 1000
        self._pending = np.zeros(0, dtype=np.int16)

    def push(self, samples: np.ndarray) -> list[np.ndarray]:
        self._pending = np.concatenate((self._pending, samples))
        chunks = []
        while self._pending.size >= self.size:
            chunks.append(self._pending[:self.size])
            self._pending = self._pending[self.size:]
        return chunks

    def drain(self) -> np.ndarray | None:
        if self._pending.size == 0:
            return None
        rest, self._pending = self._pending, np.zeros(0, dtype=np.int16)
        return rest
