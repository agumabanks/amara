# Two-hour WhatsApp group stall — 10 September

The phone retained the owner's two-hour intervals. Seven of eight configured two-hour groups were paused after recipient selection or uncertain delivery; KIYODA had two conflicting directory identities and never passed unique-target selection. KCU-CRIC and Lending a HAPPY HAND last had recorded success on 9 September at about 20:23 EAT, then failed verification on 10 September at about 06:33 and 06:31 EAT. These holds explain the missed schedules; setting an interval did not clear them.

Live KCU-CRIC inspection exposed a full outgoing caption and Sent status in `main_layout` inside `conversation_row_image`, with the thumbnail offscreen. The old verifier required a visible thumbnail child and could fail despite this photo-message structure. Its accumulated evidence could also span separate matching rows.

Changes:

- Accept the observed photo-message row structure while still requiring the full bound caption and sent/delivered/read marker within that same row. Do not combine status and caption evidence from different rows.
- Select a unique visible WhatsApp recipient name by its on-screen bounds rather than relying on a broad clickable ancestor.
- Paused groups become eligible for a read-only recovery check every 30 minutes. Pre-dispatch recipient failures require exact chat navigation. Uncertain deliveries require verification of the previously bound captioned photo. Successful checks resume the next scheduled opportunity without replaying the old transaction. Unresolved/ambiguous cases remain held.
- Settings explain automatic verification of held ads. Per-group permissions, quiet hours, cadence and exact recipient checks remain enforced.

Pre-update evidence: `artifacts/defence-migration-20260909/group-stall-before.tar`.

Validation: 29 WhatsApp/group tests passed; Flutter group screen analysis and diff checks passed. Installed APK SHA-256 `703950138cd03325e2b241b8a85e241d515487d92633823928ad488277d45be5` matches the device. ColorOS temporarily dropped binding after installation; final certificate shows process 22858, Accessibility enabled/bound/not crashed, overlay, notification permission, battery exemption and foreground service PASS, 58 scheduler matches and zero recent fatal/ANR matches.

Post-install database evidence records successful scheduled ads on 10 September EAT: KCU-CRIC 10:53 → next 12:53; Lending a HAPPY HAND 11:27 → next 13:27; BandaChristianFellowship 11:34 → next 13:34. All three are unpaused with zero consecutive failures. Read-only chat inspection exposed the KCU full Floor Stickers caption with Sent status and Lending ID Card Holder caption with Sent status. Four other two-hour groups remain held; KIYODA remains ambiguous. No blanket claim that every destination is repaired.

Evidence: `group-stall-after.tar`, `group-stall-install.txt`, `group-stall-certificate.txt` in the same private artifacts directory. Inspection pause cleared and normal loop woken after checks.
