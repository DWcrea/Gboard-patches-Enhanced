# Backspace swipe-up clear — V4

Real-device/video testing showed the V3 hook occasionally worked, but most normal upward
swipes were retargeted from Backspace to the neighbouring `l` key before Gboard resolved
`SLIDE_UP`; with the English QWERTY flick patch enabled that produced uppercase `L`.

V4 fixes the race at the retarget boundary:

- captures Backspace from the live `pvi.m` owner when Gboard is about to retarget, not only
  from the initial incoming key;
- keeps Backspace as pointer owner for the whole drag instead of allowing a switch to a
  letter key;
- leaves the existing V3 direct `SLIDE_UP -> clearAllText()` path in place;
- falls back to the captured Backspace anchor if the live owner is unavailable.

This specifically targets the `Backspace -> l -> L` behaviour observed on-device.
