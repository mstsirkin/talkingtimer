# TalkingTimer Regression Checklist

Run this after each feature step.

## Step 0
- App installs and launches on phone.
- Baseline screenshot captured.

## Step 1
- Start/Stop works.
- Timer display increments correctly for 2 minutes.
- Restart resets to configured offset.

## Step 2
- Timer continues in background.
- Persistent notification shows Stop action.
- Stop action halts timer.

## Step 3
- `started`, `3`, `2`, `1`, `go` clips play locally.
- Playback still works in background.

## Step 4
- Negative countdown crosses 0 with `3..2..1..go` once.
- Timer continues positive after `go`.

## Step 5
- Cadence callouts fire at 10s/30s/1m/5m.
- No duplicate callouts after background/resume.

## Step 6
- Scheduled start triggers at selected time.
- Cancel/Stop works while waiting.

## Step 7 (Wear)
- Round-screen layout looks correct.
- Background timer + notification works.

## Step 8 (Voice)
- Listening starts/stops.
- Saying "go" starts timer once.
- Offline/on-device preference enabled.
