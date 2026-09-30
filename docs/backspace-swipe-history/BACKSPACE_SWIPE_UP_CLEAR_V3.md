# Backspace swipe-up clear — v3 direct-pointer fix

V1/V2 attempted to replace Backspace's SoftKey `SLIDE_UP` metadata action. Real-device
testing showed that Gboard 18.0.3 does not route Backspace's vertical drag through that
action dispatcher; the key stays in its stock repeat-delete path.

V3 therefore intercepts the **pointer owner** immediately before Gboard retargets a drag.
It reads the current owner (`pvi.m`), uses Gboard's own gesture resolver
(`pvi.h(float,float,pmy)`) and, only when the owner is `key_pos_del` and the resolved
gesture is `SLIDE_UP`, clears the current editor through `InputConnection`.  The retarget
for that move is suppressed after a successful clear.

This means ordinary tap Backspace and stock horizontal gesture-delete are not replaced.
The older synthetic `SLIDE_UP` metadata injection has been removed in V3.
