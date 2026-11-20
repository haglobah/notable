# Spike: device owner on the Boox

Question: can an app that is **device owner** take OS clutter away on the Boox without root?
Mainly: disable the status bar (notification shade), and also hide or suspend apps and pin a home
app. If yes, the OS-wide agent overlay can use these powers directly, with no adb after setup.

This is a separate throwaway APK (`tools.humane.spike.deviceowner`), not Notable. It is
`testOnly`, so device owner can be removed again over adb with no factory reset.

## Run it

```sh
nix develop
./gradlew :spike-device-owner:assembleDebug
adb install -t spikes/device-owner/build/outputs/apk/debug/spike-device-owner-debug.apk

# Preconditions: device owner can only be set with no accounts and one user on the device.
adb shell dumpsys account | grep -A5 '^Accounts'
adb shell pm list users

adb shell dpm set-device-owner tools.humane.spike.deviceowner/.AdminReceiver
```

If `set-device-owner` says there are already accounts, note which ones (an Onyx account? Google?)
in the results below before removing anything; that answer decides whether this route is
practical at all.

Then open "Device owner spike" from the app list and try the buttons. Every result is shown on
screen and logged: `adb logcat -s DeviceOwnerSpike`.

## Undo

In the app: **Release device owner** (re-enables the status bar and clears Home first). Or over
adb:

```sh
adb shell dpm remove-active-admin tools.humane.spike.deviceowner/.AdminReceiver
adb uninstall tools.humane.spike.deviceowner
```

Hidden apps come back with **Unhide**, or `adb shell pm unhide <pkg>`; suspended ones with
`adb shell pm unsuspend <pkg>`.

## Checklist

| # | Check | Result |
|---|---|---|
| 1 | `dpm set-device-owner` succeeds (accounts/users in the way?) | |
| 2 | **Disable** status bar: swipe down from the top no longer opens the shade | |
| 3 | Boox's own shade/control centre (its gesture, the Navi ball's entry) also gone? | |
| 4 | Still disabled after a reboot | |
| 5 | **Enable** brings it back | |
| 6 | **Hide** an Onyx app (e.g. the app store): gone from the Boox launcher | |
| 7 | **Suspend** an app: greyed out / refuses to open | |
| 8 | **Make spike Home**: the home button and Boox gestures open the spike, not the Boox launcher | |
| 9 | Does Boox firmware complain, reset, or undo any of these (also after reboot) | |
| 10 | **Release device owner** leaves the device as it was | |

## Results

Device, firmware, date: Note Air 5C, Android 15, `2026-08-03_16-13_4.2.1-rel_0803_eaa32b54f`,
2026-11-20.

1. **Blocked by the Google account.** One user (0), one account (`com.google`, added 2026-08-29).
   `dpm set-device-owner` fails with `IllegalStateException: Not allowed to set the device owner
   because there are already some accounts on the device.` The failed call leaves no active admin
   behind (`dumpsys device_policy`: no enabled admins). The package is installed with `TEST_ONLY`.
   Next: remove the Google account, set device owner, add the account back (device owner doesn't
   stop adding accounts later).

2–10: not run yet.

Also set on this device (not part of the spike): `settings put global stay_on_while_plugged_in 7`
(was `0`): the screen stays on while charging. `system auto_poweroff_timeout` was already
`2147483647` (never).
