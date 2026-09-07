# WhatsApp originating-chat identity and group controls

## Findings

Display names were used as queue-coalescing and conversation-memory keys. Separate chats named Sam could therefore overwrite pending work or share context. Navigation also searched by display name despite WhatsApp already supplying a trusted originating-conversation launch intent.

## Changes

- Notification ingestion records a case-sensitive hashed conversation identity derived from WhatsApp's shortcut ID, with direct/group namespaces. Without a shortcut ID, notification key plus post time provides a conservative event-scoped identity rather than pretending a display name is stable.
- Keep WhatsApp-owned PendingIntents in a bounded-expiry memory registry. New inbound work opens its originating route, verifies the conversation header and exact inbound message, and does not fall back to name search if the route is missing or canceled. Active notifications can repopulate routes when the listener reconnects; a vanished route remains a recoverable blocker and then needs review.
- Queue supersession and conversation memory use origin identity. Legacy work without an origin is not compacted merely by equal names. Group participants share group conversation context; their personal facts are not learned as private-customer facts.
- Origin-scoped direct contacts retain reply-only permissions under the owner's existing always-on setting. Existing restrictions and revocations are checked; group permissions are never inferred from receiving a notification. Permissions are checked again immediately before dispatch.
- Reopen the originating route before dispatch; never resolve a duplicate-name reply through the media picker or a guessed phone number.

## Settings > WhatsApp groups

Each observed/directory group has independent Listen, Reply and Scheduled promotions controls, promotion interval (hourly through weekly), identity suffix, origin status and last promotion outcome. New observed groups start without reply/promotion grants. Listen-only groups retain observed messages locally without automatically replying.

Promotions use the existing grounded product/service rotation and require a prepared photo. Schedule waits a full interval after the previous outcome; ordinary 08:00–20:00 availability, global controls, daily caps and exact-destination checks remain in force. Duplicate-name groups whose media-picker destination cannot be selected uniquely are held, not guessed. The settings page explicitly reports this limitation. Existing directory entries and newly observed origins may need owner reconciliation if they represent the same real group; name equality alone does not authorize linking them.

## Limits

A shortcut/notification identifier is a routing identifier, not proof of a person's legal identity or phone number. Expired or missing launch tokens are not reconstructed by guessing. Old name-scoped memory is deliberately not copied into newly separated same-name conversations. End-to-end duplicate-name delivery still needs real notification evidence; unit and widget tests alone do not prove a live send.

## Validation

Final debug APK built successfully. All 81 targeted Android tests passed (WhatsApp, autonomous-work integration and side-effect transactions), Flutter analysis reported no issues, and the widget test verified that changing one of two identically named groups updates only the selected identity. The group permission test also covers disabling listening and re-enabling it through promotions without re-enabling replies.

The five-hour evaluation retains its original end time. Installation/inspection is recorded as an engineering intervention, and subsequent phone events carry `whatsapp-origin-groups-v4`; the entire window must not be described as an uninterrupted test of this build.

## Device verification

Installed on OPPO CPH1933 (`7aef1a4c`), version 0.10.0 (13). Installed APK checksum matches `77b165be765e48cf0fa5dd4b8114e164cd5d0982579f8ce79a7048568669dd22`. Certificate: Accessibility enabled/bound, not crashed; overlay, notification listener, battery exemption and foreground agent service PASS; 64 scheduler matches; zero recent fatal/ANR evidence. Live Settings > WhatsApp groups loads successfully and displays Listen, Reply and Scheduled promotions. No test message or advertisement was sent during installation checks. New-build evaluation events are being collected.
