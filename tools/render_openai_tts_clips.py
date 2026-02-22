#!/usr/bin/env python3
"""Pre-render short timer clips using OpenAI TTS for local runtime playback.

Runtime requirement for the app: local clip playback only.
This script generates MP3 clips into `audio-assets/audio/<locale>/`.

Usage:
  python tools/render_openai_tts_clips.py --dry-run
  python tools/render_openai_tts_clips.py --voice alloy
  python tools/render_openai_tts_clips.py --env-file ~/.env --include-commented-key
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.request
from pathlib import Path
from typing import Iterable

API_URL = "https://api.openai.com/v1/audio/speech"
DEFAULT_MODEL = "gpt-4o-mini-tts"
DEFAULT_VOICE = "alloy"
DEFAULT_FORMAT = "mp3"

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "audio-assets" / "audio"


def token_manifest() -> dict[str, str]:
    manifest: dict[str, str] = {
        "started": "started",
        "timer_started": "timer started",
        "go": "go",
        "timer": "timer",
        "listening": "listening",
        "listening_stopped": "listening stopped",
        "hour": "hour",
        "hours": "hours",
        "minute": "minute",
        "minutes": "minutes",
        "second": "second",
        "seconds": "seconds",
        "n_99_plus": "ninety nine plus",
    }
    for n in range(0, 100):
        manifest[f"n_{n}"] = str(n)
    return manifest


def parse_env_file(env_file: Path, include_commented_key: bool) -> dict[str, str]:
    values: dict[str, str] = {}
    if not env_file.exists():
        return values
    for raw in env_file.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            if include_commented_key and line.startswith("#") and "=" in line:
                key, value = line[1:].split("=", 1)
                key = key.strip().replace("export ", "")
                if key == "OPENAI_API_KEY" and value.strip():
                    values[key] = value.strip().strip('"').strip("'")
            continue
        if line.startswith("export "):
            line = line[len("export "):]
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def resolve_api_key(env_file: Path | None, include_commented_key: bool) -> str:
    for candidate in ("OPENAI_API_KEY", "OPENAI_KEY"):
        value = os.getenv(candidate)
        if value:
            return value
    if env_file:
        parsed = parse_env_file(env_file, include_commented_key)
        for candidate in ("OPENAI_API_KEY", "OPENAI_KEY"):
            value = parsed.get(candidate)
            if value:
                return value
    raise SystemExit(
        "Missing OpenAI API key. Set OPENAI_API_KEY in env or provide --env-file. "
        "(Your ~/.env appears to have the key commented out; use --include-commented-key or uncomment/export it.)"
    )


def synthesize(api_key: str, model: str, voice: str, text: str, fmt: str) -> bytes:
    payload = {
        "model": model,
        "voice": voice,
        "input": text,
        "response_format": fmt,
    }
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


def iter_targets(
    manifest: dict[str, str],
    only_missing: bool,
    extension: str,
    locale_out_dir: Path,
) -> Iterable[tuple[str, str, Path]]:
    for token, text in manifest.items():
        path = locale_out_dir / f"{token}.{extension}"
        if only_missing and path.exists() and path.stat().st_size > 0:
            continue
        yield token, text, path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=Path.home() / ".env")
    parser.add_argument("--include-commented-key", action="store_true")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--voice", default=DEFAULT_VOICE)
    parser.add_argument("--locale", default="en-US", help="BCP-47-ish locale tag, e.g. en-US")
    parser.add_argument("--format", default=DEFAULT_FORMAT, choices=["mp3", "wav"])
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only-missing", action="store_true", default=True)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--sleep-ms", type=int, default=250)
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    locale_tag = args.locale.replace("_", "-")
    locale_out_dir = OUT_DIR / locale_tag
    locale_out_dir.mkdir(parents=True, exist_ok=True)
    manifest = token_manifest()
    (locale_out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    only_missing = False if args.overwrite else args.only_missing
    targets = list(
        iter_targets(
            manifest,
            only_missing=only_missing,
            extension=args.format,
            locale_out_dir=locale_out_dir,
        )
    )
    if args.limit > 0:
        targets = targets[: args.limit]

    print(f"Manifest tokens: {len(manifest)}")
    print(f"Locale: {locale_tag}")
    print(f"Targets to render: {len(targets)} -> {locale_out_dir}")
    if args.dry_run:
        for token, text, path in targets[:20]:
            print(f"DRY {token} -> {path.name} :: {text}")
        if len(targets) > 20:
            print(f"... and {len(targets)-20} more")
        return 0

    api_key = resolve_api_key(args.env_file, include_commented_key=args.include_commented_key)

    failures = 0
    for index, (token, text, path) in enumerate(targets, start=1):
        try:
            audio = synthesize(api_key=api_key, model=args.model, voice=args.voice, text=text, fmt=args.format)
            path.write_bytes(audio)
            print(f"[{index}/{len(targets)}] OK {path.name} ({len(audio)} bytes)")
            time.sleep(max(0, args.sleep_ms) / 1000.0)
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"[{index}/{len(targets)}] FAIL {token}: {exc}", file=sys.stderr)
    if failures:
        print(f"Completed with {failures} failures", file=sys.stderr)
        return 1
    print("Completed successfully")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
