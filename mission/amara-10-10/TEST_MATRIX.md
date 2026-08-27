# Test Matrix

## Automated gates

| Gate | Command | Requirement |
|---|---|---|
| Flutter format | `dart format --output=none --set-exit-if-changed lib test` | no diff |
| Flutter static analysis | `flutter analyze` | no issues |
| Flutter widgets | `flutter test` | all pass |
| Native unit tests | `./gradlew testDebugUnitTest` | all pass |
| Signed release | load deployment signing env, then `./gradlew assembleRelease` | success |
| Mission consistency | `bash mission/amara-10-10/scripts/check_progress.sh` | success |

## Oppo gates

| ID | Scenario | Required result |
|---|---|---|
| O-01 | Accessibility health | enabled, bound, crash-free |
| O-02 | Terminal authentication | account session restored; staff PIN works |
| O-03 | Booking question ×3 cold starts | exact live bookings or verified empty state |
| O-04 | Full Terminal product/service scan | complete coverage or explicit limit |
| O-05 | Buyer comparison | exact buyer-visible listing matched to Terminal |
| O-06 | Visual mismatch audit | screenshot evidence and correct classification |
| O-07 | Approved listing edit | correct listing saved and reopened fields match |
| O-08 | WhatsApp direct/group/inbound | target/context/send all verified |
| O-09 | Attachment and Status | correct media/caption and visible result |
| O-10 | Scheduled exactly-once work | no duplicates across restart/retry |
| O-11 | TikTok draft and publish | approval respected and post verified |
| O-12 | Recovery matrix | dialogs, keyboard, wrong screen, interruption, slow network |
| O-13 | 24-hour soak | no crash, duplicate, false claim, or missed enabled occurrence |

Every Oppo gate produces a field report and evidence entries.
