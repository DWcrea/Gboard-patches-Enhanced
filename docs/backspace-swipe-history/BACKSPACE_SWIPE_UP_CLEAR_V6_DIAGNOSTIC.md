# V6 diagnostic build

This build is intentionally instrumented to diagnose why Backspace swipe-up is not being
reliably intercepted on the target Gboard 18.0.3 build.

It writes a persistent trace to the app-specific external-files directory:

`/sdcard/Android/data/<patched-gboard-package>/files/gboard-backspace-v6.log`

The trace records:

- whether the extension runtime is initialized and the feature setting is enabled;
- whether the delete key is bound;
- entry into `pvi.t(MotionEvent,int)` and `pvi.F(MotionEvent,int)`;
- current pointer owner and whether it is recognized as `key_pos_del`;
- DOWN/session creation;
- movement deltas and thresholds;
- horizontal/vertical locking;
- retarget-suppression decisions;
- clear attempts and result;
- exceptions.

This avoids relying on Android logcat, which may be filtered/encrypted on some OEM devices.
