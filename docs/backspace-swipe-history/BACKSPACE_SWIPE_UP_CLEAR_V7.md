# Backspace swipe-up clear — V7

V7 is based on the V6 on-device diagnostic log.

## Root causes confirmed by V6

1. `pvi.t(...)` is first observed on `ACTION_MOVE`, not on `ACTION_DOWN`, for the primary pointer.
2. V6 recovered the gesture origin from `ownerView.getLeft()/getTop()`, but those view coordinates are in the key-row parent coordinate space while `MotionEvent.getX()/getY()` are in the keyboard/root event space. This produced bogus initial deltas of roughly 300–400 px.
3. That bogus delta caused the first recovered Backspace gesture to be classified as horizontal (`T_LOCK_HORIZONTAL`).
4. `pvi.t(...)` did not deliver the matching UP/CANCEL event, so the horizontal lock leaked into later gestures. `pvi.F(...)` does receive ACTION_DOWN, so V7 uses it as the authoritative gesture boundary.

## V7 changes

- On every `pvi.F(... ACTION_DOWN ...)`, discard any stale Backspace swipe session.
- When `pvi.t(...)` first observes Backspace on MOVE, use that exact event `(x, y)` as the gesture baseline instead of the key view center.
- Apply the same coordinate-space rule to the F-side fallback recovery.
- End/clear a session in F on UP/CANCEL when those events are delivered there.
- Keep the original tap, long-press repeat delete, and left-swipe behavior unchanged; vertical suppression/clear only begins after real upward deltas are observed.

## Expected diagnostic sequence

A successful upward gesture should now look approximately like:

```
F_RESET_ON_DOWN ...
T_RECOVER_SESSION ... baseline=current ...
T_MOVE dx=... dy=-... up=...
T_LOCK_VERTICAL ...
T_CLEAR attempted cleared=true ...
```

The key regression to watch for is `T_LOCK_HORIZONTAL` immediately after recovery. That should no longer happen for a mostly upward swipe.
