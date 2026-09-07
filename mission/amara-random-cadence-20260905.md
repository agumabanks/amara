# Random cadence and owner-control completion

- Supplied Soko credential entered through the masked Settings PIN dialog. Live UI
  confirmed “Stored securely on this phone.” No credential included in this report.
- Live settings confirmed failure cooldown cap 0 and daily phone time 1,440 minutes.
- Added durable randomized TikTok cadence: a 30-minute base yields 20–42 minutes.
  Due time survives recreation, is not rerolled on heartbeat, and advances once for
  its matching outcome. No missed-interval catch-up burst. Failed opportunities also
  advance the next opportunity; existing per-item recovery and ledger checks remain.
- Interval changes create a newly randomized due time. Quiet-hours and owner settings
  still gate proposals. This is not an exact real-time posting guarantee.
- Final 28 targeted Android tests passed, build succeeded, diff whitespace check passed.
- Installed APK SHA-256:
  `d1220d5a33c646ceb230d75eac1ff8802fca15afa50fd8e27f79ae0aedd847d2`.
- Device certificate passed: PID 18954, accessibility enabled/bound/not crashed,
  overlay, notification access, battery exemption, foreground service; 71 scheduler
  text matches; recent sampled fatal/ANR count zero. Installed checksum matches local.
- Live cadence preferences: interval 30, next due 2026-09-05 08:54:42 UTC / 11:54:42 EAT.
- Live WhatsApp attempts now run, but multiple exact-chat navigation failures remain.
  One DONE inbound record is not by itself proof of a new send (already-answered work
  can complete without sending). Group permission denials remain honored.
- Still unverified: successful Soko login with newly stored credential, scheduled
  TikTok publication, full WhatsApp takeover. Prior uncertain publication still needs
  reconciliation. Do not claim all features/revenue production certified.
