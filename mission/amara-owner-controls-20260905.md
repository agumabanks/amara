# Owner controls: Soko credentials and failure cooldowns

- Settings now exposes Soko Terminal PIN through the existing dedicated credential
  channel and encrypted Android Keystore vault, not ordinary settings/chat/model memory.
  The PIN is obscured and cleared after submission; settings returns only vault health.
- Explicit owner PIN save may reset Amara's local credential rejection counter.
  Ordinary credential rotation cannot reset it. This does not bypass Soko's own lockout.
- Owner can cap task-kind failure cooldowns from 0 to 1,440 minutes. Zero disables
  these circuit-breaker waits. Changes apply to existing records using their last-trip
  timestamp without deleting failure history.
- Quiet hours, consent, duplicate/uncertain-effect protection, secure device locks,
  network backoff, bounded retries, and battery/heat stops remain separate.
- Owner-adjustable daily phone budget range now reaches 1,440 minutes consistently
  in UI, settings, storage, and governor. TikTok daily cap range reaches 144 consistently
  in UI, settings, and storage. Existing daily values are not raised automatically.
- Added regression coverage for cooldown changes on existing breakers without
  bypassing quiet hours, secure PIN UI routing, and explicit owner-only lockout reset.
- No supplied credential is included in this report or source files.
