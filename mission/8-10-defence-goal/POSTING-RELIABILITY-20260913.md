# Posting reliability repair

Owner authorized fixing the multi-hour OPPO TikTok gap. Root causes: Terminal only exposed a cached 180-second signed identity without an on-demand refresh request; the shared 90-minute screen budget could be consumed by unrelated work; several executors reported estimated screen seconds even for background work.

## Implementation

Terminal 2.0.5 (2030) adds a caller-checked refresh_identity provider call. The native bridge asks the existing Flutter authenticated publisher to fetch/publish a fresh assertion, with a bounded 15-second wait and no credential export. Logged-out publishers reject refresh; generation guards reject late old-shop responses. Amara requests freshness before authenticated catalogue reads and side-effect scope checks. A posting task may reopen Terminal under its existing screen grant if the engine was stopped, waiting at most 30 seconds for authentication/freshness before reporting failure. Signatures, scope binding and expiry remain enforced.

Amara 0.10.5 (18) gives owner-enabled always-on TikTok posting a separate bounded allowance: four minutes per scheduled post, limited by the configured interval/daily post cap and capped at 240 minutes/day. Other work cannot consume this allowance. All post attempts consume it; low battery, thermal limits, quiet-hours policy, publication cap, governor and transaction checks remain active. Home shows remaining/total posting minutes. Existing shared time records are preserved.

Executor time accounting uses measured duration for screen-capable tasks, zero for background-only tasks, and charges timed-out screen work for its elapsed limit. Hardcoded per-module estimates no longer inflate shared screen use.

## Verification

Four Terminal publisher tests cover on-demand refresh and logout races. Amara scheduler regression adds posting admission after shared-budget exhaustion, exclusion of other kinds, battery/thermal holds and exhaustion by failed attempts. Signature/expiry rejection tests retained. Live deployment and post evidence follow.
