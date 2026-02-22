# Pre-rendered Audio Assets

These clips are generated ahead of time and packaged into both Android apps for local runtime playback.
Store clips under locale folders so future localization is straightforward (for example `audio/en-US/` and later `audio/es-ES/`).

## Generate with OpenAI TTS

```bash
python tools/render_openai_tts_clips.py --dry-run --locale en-US
python tools/render_openai_tts_clips.py --include-commented-key --locale en-US
```

Notes:
- Runtime playback is local only (no TTS at runtime).
- The app loads clips with locale fallback: `en-US` -> `en` -> root.
- `~/.env` currently appears to contain a commented `OPENAI_API_KEY`; the script supports `--include-commented-key` for that case.
- Generated `.mp3` files are ignored by git.
