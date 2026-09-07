# Business accountability and queue recovery

Latest logs confirmed WhatsApp exact-search failures and unverified deliveries. This pass makes failure recovery operational rather than relying on motivational language.

- Match full WhatsApp identities after NFC, whitespace and invisible-direction-mark normalization; preserve rejection of partial names and ambiguous rows. If a unique row reports click failure, allow the existing exact picker-label bounds-checked tap fallback.
- Reduce inbound owner-takeover grace from 30 seconds to five seconds. Owner activity detection and exact destination checks remain in effect.
- Bound queue execution, including device-lock acquisition: inbound 90 seconds, long publication/inventory work 240 seconds, other work 120 seconds. Cancellation propagates from WorkExecutor. Timed-out work is recorded and escalated without blindly retrying a potentially dispatched effect.
- A failing non-inbound kind is excluded for the rest of that session while unrelated ready work continues. Remove the two-failure global session exit. Existing durable delayed retries still apply to recoverable preparation failures.
- Increase TikTok allowance from three to twelve attempts per rolling day, with twenty-minute spacing and thirty-minute research pulses. Keep creator cooldown, unique post reservations, response deduplication and uncertain-send protection.
- Add BusinessOperatingBrief to inbound and TikTok decision prompts. Prioritize waiting customers, qualified inquiries and completed orders; distinguish paid orders from taps/followers; use evidence in subsequent decisions; record, defer and move past failures. Revenue pressure never expands authority or justifies invented sales or spam. This is an accountable software employee role, not a claim of consciousness or survival needs.

Validation: debug APK build passed; 73 focused WhatsApp matching, social persistence/policy, autonomous work and side-effect tests passed with zero failures/errors. `git diff --check` passed.

Installed on OPPO CPH1933 (`7aef1a4c`) with APK SHA-256 `f82c731178fd43637aa26f081f7e858e128b7f4ae90eaa798502653181f66009`. Deployment reported PID 10703, Accessibility enabled/bound and not crashed, overlay allowed, zero recent fatal/ANR entries.

Limits: these changes do not yet prove an unattended reliability rating, measured inbound reply latency, successful TikTok delivery, follower growth or revenue. Five seconds is the inbound eligibility grace, not a guaranteed delivery time. Existing uncertain sends remain protected against repeat dispatch. Soko account sign-in remains a separate prerequisite for listing writes.

Full device certificate passed: installed checksum matches the built APK, version 0.10.0 (13), Accessibility enabled/bound/not crashed, overlay, notification listener, battery exemption, foreground agent service; 62 scheduler matches and zero recent fatal/ANR entries.

Live read-only WhatsApp check at 10:52:07 device log time: exact navigation to one retained inbound chat returned `verified=true` with no failure stage. No test message was sent. Inspection pause cleared to resume normal operation. This single navigation check does not establish delivery latency or complete coverage of failed chats.
