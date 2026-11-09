"""Validate the probe's local WAV files; does not play audio or access the network."""
from pathlib import Path
import hashlib
import json
import math
import struct
import wave

root = Path(__file__).resolve().parents[3]
output = root / "build" / "tts-multitts-audit"
rows = []
for path in sorted(output.glob("*.audio")):
    with wave.open(str(path), "rb") as audio:
        assert audio.getnchannels() == 1 and audio.getsampwidth() == 2, path.name
        frames = audio.readframes(audio.getnframes())
        samples = struct.unpack("<" + "h" * (len(frames) // 2), frames)
        row = {
            "sample": path.stem,
            "sampleRate": audio.getframerate(),
            "channels": audio.getnchannels(),
            "bits": audio.getsampwidth() * 8,
            "frames": audio.getnframes(),
            "seconds": round(audio.getnframes() / audio.getframerate(), 3),
            "bytes": path.stat().st_size,
            "peak": max(abs(value) for value in samples),
            "rms": round(math.sqrt(sum(value * value for value in samples) / len(samples)), 2),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        assert row["seconds"] > 1 and row["rms"] > 100, path.name
        rows.append(row)
if not rows:
    raise SystemExit("No local probe audio files found; run the live probe first.")
result = json.dumps(rows, ensure_ascii=False, indent=2)
(output / "audio-summary.json").write_text(result, encoding="utf-8")
print(result)
