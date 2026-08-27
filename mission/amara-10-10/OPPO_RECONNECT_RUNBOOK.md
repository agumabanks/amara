# Oppo Reconnection Runbook

## Connect

1. Put the server and Oppo on the same trusted network.
2. Enable Wireless debugging and note the current host/port.
3. Run `adb connect HOST:PORT`, then `adb devices -l`.
4. Confirm the device model is the dedicated Oppo before installing or testing.

## Preflight

1. Install the signed release with `adb install -r`; never uninstall because that destroys Amara’s memory and permissions.
2. **Never run `adb shell am force-stop co.sanaa.agent` on ColorOS.** On the Oppo CPH1933 this is not a neutral cold-start operation: ColorOS removes the accessibility service from the enabled-services setting and records it as crashed. Use a normal activity launch, process signal only in a dedicated interruption gate, or reboot for lifecycle tests.
3. Open Amara → Phone access.
4. Confirm Accessibility permission and live binding separately: `enabled_accessibility_services` contains the component, `dumpsys accessibility` lists it under both Enabled and Bound, and Crashed services is empty.
5. Confirm battery, notification access, notifications, overlay, Contacts, Groq, both Soko apps, and PIN status.
6. Open Terminal manually and restore the account session if the phone-number/OTP screen appears.
7. Confirm the staff PIN screen accepts the stored PIN.

If ColorOS already shows Sanaa under **Crashed services**, open its Accessibility row, turn it off and confirm **Stop**, then enable it again. Merely toggling it on once may leave the stale crashed classification in place. Re-run all three checks in step 4 before testing.

## Ordered device tests

1. O-03 booking question three times with cold starts.
2. O-04 read-only products/services coverage.
3. O-05 Buyer comparison.
4. O-06 visual listing audit.
5. Only after the read gates pass: O-07 approved edit.
6. WhatsApp tests O-08 and O-09.
7. Scheduling O-10.
8. TikTok O-11.
9. Recovery O-12.
10. Soak O-13.

## Evidence capture

- Capture pre-screen, action screen, result screen, UI hierarchy, log excerpt, task receipt, APK version, time, and device state.
- Use `EVIDENCE_INDEX.md` naming rules.
- Fill one `FIELD_REPORT_TEMPLATE.md` copy per gate.
