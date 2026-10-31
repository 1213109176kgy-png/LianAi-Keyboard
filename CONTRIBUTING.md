# Contributing

Use JDK 17, Android SDK, and NDK 26.1. Keep credentials in the app's local
configuration screen, never in source, tests, fixtures, commits, or issues.

Before opening a pull request:

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Test changes against at least one normal editor and one sensitive/password
field. Changes to Rime, Room, native JNI, cloud protocol, or privacy behavior
must include focused tests and update `THIRD_PARTY_NOTICES.md` or `PRIVACY.md`
when applicable.

For keyboard touch regressions on a connected Android device:

```powershell
gradle :app:connectedDebugAndroidTest -PtouchRegression=true --no-daemon
```

The property installs the app and its instrumentation under a separate
`com.lianai.keyboard.touchtest` application ID, preserving the signed app and
its account/model settings. The tests send real Android MotionEvents to the
drawn keyboard, including drift, gutters, overlapping pointers and cancellation.
They cover event dispatch and editor callbacks; also check the installed IME in
a real editor and assess fast typing on the physical screen before release.
