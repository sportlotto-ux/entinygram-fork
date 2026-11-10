import ctypes
import ctypes.util


class OpusError(RuntimeError):
    pass


_lib = ctypes.CDLL(ctypes.util.find_library("opus") or "libopus.so.0")

_APPLICATION_VOIP = 2048
_CTL_SET_BITRATE = 4002
_CTL_SET_COMPLEXITY = 4010
_CTL_SET_SIGNAL = 4024
_SIGNAL_VOICE = 3001
_MAX_PACKET = 1276
_MAX_FRAME_MS = 120


def _check(result: int) -> int:
    if result < 0:
        raise OpusError(f"libopus error {result}")
    return result


class Encoder:
    def __init__(self, rate: int = 48000, channels: int = 1, bitrate: int = 32000, complexity: int = 5):
        error = ctypes.c_int()
        self._handle = _lib.opus_encoder_create(
            ctypes.c_int(rate), ctypes.c_int(channels), ctypes.c_int(_APPLICATION_VOIP), ctypes.byref(error)
        )
        if error.value != 0 or not self._handle:
            raise OpusError(f"opus_encoder_create failed: {error.value}")
        self.rate = rate
        self.channels = channels
        self.frame_size = rate // 50
        for request, value in (
            (_CTL_SET_BITRATE, bitrate),
            (_CTL_SET_COMPLEXITY, complexity),
            (_CTL_SET_SIGNAL, _SIGNAL_VOICE),
        ):
            _check(_lib.opus_encoder_ctl(self._handle, ctypes.c_int(request), ctypes.c_int(value)))
        self._out = ctypes.create_string_buffer(_MAX_PACKET)

    def encode(self, pcm: bytes) -> list[bytes]:
        step = self.frame_size * self.channels * 2
        packets = []
        for offset in range(0, len(pcm) - step + 1, step):
            size = _check(
                _lib.opus_encode(
                    self._handle,
                    pcm[offset:offset + step],
                    ctypes.c_int(self.frame_size),
                    self._out,
                    ctypes.c_int(_MAX_PACKET),
                )
            )
            packets.append(self._out.raw[:size])
        return packets


class Decoder:
    def __init__(self, rate: int = 48000, channels: int = 1):
        error = ctypes.c_int()
        self._handle = _lib.opus_decoder_create(ctypes.c_int(rate), ctypes.c_int(channels), ctypes.byref(error))
        if error.value != 0 or not self._handle:
            raise OpusError(f"opus_decoder_create failed: {error.value}")
        self.rate = rate
        self.channels = channels
        self._max_frame = rate * _MAX_FRAME_MS // 1000
        self._out = ctypes.create_string_buffer(self._max_frame * channels * 2)

    def decode(self, packet: bytes) -> bytes:
        samples = _check(
            _lib.opus_decode(
                self._handle,
                packet,
                ctypes.c_int(len(packet)),
                self._out,
                ctypes.c_int(self._max_frame),
                ctypes.c_int(0),
            )
        )
        return self._out.raw[:samples * self.channels * 2]
