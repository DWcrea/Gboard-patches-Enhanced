# Backspace swipe-up clear — V5

V5 moves interception to Gboard 18.0.3 `pvi#t(MotionEvent,int)` and `pvi#F(MotionEvent,int)`, verified from the user-supplied base DEX. Upward-dominant Backspace motion is consumed before the pointer can retarget to `L`; horizontal-left movement is left to stock gesture-delete.
