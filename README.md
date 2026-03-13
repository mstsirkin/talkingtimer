talking timer for android/android wear:
- once started runs and calls out every 10s/30s/1min/5min (a setting controls) how often
- can be set to negative time (schedule) and count down to 0 (3..2..1..go) then go on
- alternatively can listen and when you say "go" start and confirm with ("started")
- alternatively can be scheduled at a specific time, again confirming saying "timer started"
- supports round watch faces
- can run in background on watch/phone with a persistent notification
  to stop or stop listening

## Exact Alarm Permission (Android 12+)

This app uses exact alarms for reliable scheduled/start-boundary timing when the device sleeps.

### Recommended: helper script

```bash
tools/exact_alarm_appops.sh allow wear
tools/exact_alarm_appops.sh check wear
```

Targets: `app`, `wear`, `both`.

### Manual ADB commands

Wear (replace serial if needed):

```bash
adb -s 192.168.1.139:39665 shell appops set com.vibe.talkingtimer.wear SCHEDULE_EXACT_ALARM allow
adb -s 192.168.1.139:39665 shell appops get com.vibe.talkingtimer.wear SCHEDULE_EXACT_ALARM
```

Phone:

```bash
adb -s <phone-serial> shell appops set com.vibe.talkingtimer.app SCHEDULE_EXACT_ALARM allow
adb -s <phone-serial> shell appops get com.vibe.talkingtimer.app SCHEDULE_EXACT_ALARM
```

Open settings page via ADB:

```bash
adb shell am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d package:com.vibe.talkingtimer.wear
```
