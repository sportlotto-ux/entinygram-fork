# inu-live

Live call translation proxy: the phone sends Opus, the server turns it into the PCM the Gemini Live
translation model wants and sends the translation back as Opus, plus subtitles as JSON.

## Layout

| File | Role |
| --- | --- |
| `app.py` | FastAPI: `/health` and the `/live` WebSocket |
| `gemini.py` | one Live session per direction, reconnects before the ten minute limit |
| `opus.py` | libopus through ctypes, 48 kHz mono |
| `audio.py` | windowed-sinc resampler and chunker |
| `tokens.py` | token whitelist, `python tokens.py <file> add <name>` |
| `tools/wav_check.py` | offline check on a WAV, no phone needed |

## Protocol

| Frame | Meaning |
| --- | --- |
| binary `0x10` + opus | call audio of the other side |
| binary `0x11` + opus | my own microphone |
| binary `0x20` + opus | translated audio of the other side |
| binary `0x21` + opus | translated audio of my speech |
| text | `{"t":"sub","dir":"in","src":..,"dst":..,"final":..}`, `{"t":"state",..}` |

Everything is 48 kHz mono Opus at 32 kbit/s; the resampling to 16 kHz and back is done here.

## Deploy

The service listens on the amnezia docker bridge, not on the public address, so nothing has to be
opened to the internet. On the VPS with `amnezia-awg2` the bridge is `amn0` and its host address is
`172.29.172.1` (check with `ip -4 addr show amn0`).

```bash
apt install -y python3-venv libopus0
useradd -r -s /usr/sbin/nologin -d /opt/inu-live inu-live
install -d -o inu-live -g inu-live /opt/inu-live
install -d -m 750 -o root -g inu-live /etc/inu-live
tar xzf inu-live.tar.gz -C /opt/inu-live && chown -R inu-live:inu-live /opt/inu-live
sudo -u inu-live python3 -m venv /opt/inu-live/.venv
sudo -u inu-live /opt/inu-live/.venv/bin/pip install -r /opt/inu-live/requirements.txt

install -m 640 -o root -g inu-live /dev/null /etc/inu-live/gemini.key   # вписать ключ
/opt/inu-live/.venv/bin/python /opt/inu-live/tokens.py /etc/inu-live/tokens.json add phone
chown root:inu-live /etc/inu-live/tokens.json && chmod 640 /etc/inu-live/tokens.json

ufw allow in on amn0 to any port 8000 proto tcp
install -m 644 deploy/inu-live.service /etc/systemd/system/
systemctl enable --now inu-live
```

The unit waits for the bridge address for up to a minute, because the amnezia container creates it
after boot, and restarts forever otherwise.

Client URL: `ws://172.29.172.1:8000/live` with the token in `X-Proxy-Token`. Languages can be
picked per connection (`/live?in=ru&out=en`), otherwise the `INU_TARGET_*` defaults apply.

The client sends 20 ms Opus frames with a one byte tag: `0x10` for the remote party, `0x11` for the
local microphone. The server answers with `0x20`/`0x21` Opus plus JSON subtitles
(`{"t":"sub","dir":"in","src":...,"dst":...,"final":true}`).
Rotating the Gemini key: rewrite `/etc/inu-live/gemini.key`, `systemctl restart inu-live`, no APK. 
 
## Run locally

```bash
.venv/bin/uvicorn app:app --host 127.0.0.1 --port 8000
```

`INU_TARGET_IN` is the language the other side is translated into, `INU_TARGET_OUT` mine.
With `INU_ECHO_TARGET=0` the model stays silent when it hears speech already in the target language,
so a Russian phrase on the `in` session is not echoed back.

## Offline check

```bash
INU_GEMINI_KEY=... .venv/bin/python tools/wav_check.py --wav english.wav --target ru --out out
```

Writes `out/translated.wav`, `out/subtitles.srt` and prints the first audio latency and the Opus ratio.
`--no-gemini` runs the codec path only.

Measured on a 7.5 s English phrase, target `ru`:

| Metric | Value |
| --- | --- |
| session setup | 0.7 s |
| first translated audio | 3.6 s after the stream starts |
| Opus traffic | 30 KiB for 7.5 s (23x smaller than raw PCM) |
| subtitles | one clean sentence |

Two things the model needs to be told, learned the hard way: a pause after speech, otherwise the last
word is dropped, and no `audioStreamEnd`, which cuts the utterance one word earlier. After a phrase the
model keeps sending silent frames, so the server drops anything below -54 dBFS.
