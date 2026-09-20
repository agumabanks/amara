# YouTube Shorts follow-up

Status: design and device inspection only; no Shorts publisher or settings module has been shipped. TikTok-first acceptance remains blocked by backend DNS, not waived.

## Owner choices

- Updated owner request: reuse the finished TikTok ad with its TikTok-added soundtrack. This supersedes the earlier original-ad/YouTube-music choice. Cross-platform track permission remains to be confirmed. Preserve soundtrack provenance and hold tracks without confirmed YouTube permission.
- Separate Shorts enable switch, settings, and progress/status reporting.
- Preserve the ad's factual description and links.
- Use semantic UI evidence and progress-sensitive timing across devices.

The OPPO currently displays YouTube channel Sanaa 24 / @sanaasanaa1774. Target channel confirmation was requested and remains outstanding. A module must default off until an exact channel is configured and matched on the device.

## Observed device surface

YouTube version 21.36.45. ACTION_SEND with a read-granted MP4 content URI opens a trim screen with Next. Next opens the native Shorts editor. The captured editor has accessible labels Add sound, Volume, Exit editor, Edit, Next, Text, Effects, Filters, Stickers, and Captions. Their observed IDs are in the gitignored `youtube-editor.xml`; labels and role/geometry relationships should be primary evidence.

Music selection and final upload/account/metadata verification are not captured or certified. The phone returned to its account page during inspection. No upload action was dispatched. A uniquely named temporary preview was added to MediaStore for inspection and deleted afterward.

## Implementation boundaries

1. Enqueue only after the source TikTok transaction is VERIFIED. Capture the verified finished TikTok video through its supported save/share flow, and bind its bytes, digest, exact caption, source key, listing ID, track identity and shop scope. Original silent creative is not proof of the requested soundtrack. An UNCERTAIN TikTok result is not a verified source.
2. Persist a bounded queue with a separate dedupe key per source/channel. Pin a copy of the original before TikTok media cleanup can reclaim it. Convert photo ads to a short video from the exact rendered image, without redownloading a mutable URL or copying TikTok audio.
3. Add a distinct work kind and source. Proposing work must read local state only. Honor owner master power, module enable, quiet hours, cadence, daily cap and existing screen lease. Shorts failure must not halt TikTok work.
4. Bind publication to the same fresh signed Terminal shop and the configured YouTube handle. Re-check enable/channel/media integrity immediately before dispatch.
5. Prepare through native share, verify import, preserve the already mixed TikTok soundtrack and verify the imported file has the expected audio track; do not add a second soundtrack. Fill exact title and description in the actual supported fields. Do not truncate the full description to fit a title and then report it as preserved.
6. Route the one Upload Short action through the existing side-effect transaction authority. Never replay uncertain uploads. Require the exact own-channel published item and metadata for a verified receipt; a returned feed or accepted tap is insufficient.
7. Expose enable, exact channel, interval, daily limit, music policy and queue/last-outcome status in Flutter settings. Keep held work reviewable without a blind retry button.
8. Use idle and hard deadlines with new observed progress extending the idle window. Validate each action against the current package/window and semantic control. Unknown or ambiguous final publication controls remain held rather than guessed.

## Acceptance evidence still required

- Actual native music picker, selection and attached-track fixtures.
- Actual details, channel identity, title/description and upload confirmation fixtures.
- Drift/scaled-screen tests, wrong-account tests, disabled-module tests, source-content mutation tests, crash/restart/dedupe tests and uncertain-upload tests.
- Device preparation check, then an authorized real upload to the confirmed channel, with original media and metadata evidence.

## Updated finished-video inspection

TikTok's own-post share sheet exposes `Download`. Saving the Thermal Mini Printer ad created a new MediaStore video (only one new row). Its exported file is retained privately as `tiktok-finished-with-sound.mp4`. ffprobe reports H.264 720x1280 video, 12.000 seconds, and an MP3 audio track of 10.213878 seconds. This proves an audio track is present, not cross-platform licensing or perceptual identity. YouTube's native import selected 10.2 seconds and displayed a 0:10 thumbnail, so importing this exact file currently truncates its video tail. Duration-preserving audio padding/transcoding needs implementation and device validation.

Native Shorts details exposes Caption your Short, Show more and Upload Short to Accessibility, but the visibly rendered signed-in handle, visibility, Select audience and Add description rows have no corresponding text labels in the captured hierarchy. Screenshots show the exact channel and an Add description option after Show more. A local visual-text fallback is required; hardcoded OPPO coordinates are not an acceptable production substitute. Google's bundled on-device ML Kit text recognition is a candidate (https://developers.google.com/ml-kit/vision/text-recognition/v2/android), not yet integrated. No YouTube upload was tapped.

`ShortsMediaPolicy` and `ShortsMediaInspector` are preliminary, unconnected source utilities with tests; there is no owner-facing Shorts module yet. The installed 0.10.16 release is the TikTok repair only.
