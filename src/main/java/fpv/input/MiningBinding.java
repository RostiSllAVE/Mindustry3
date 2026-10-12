package fpv.input;

import arc.input.KeyBind;
import arc.input.KeyCode;

/** Flight-only hold action; native Build Mode RMB cancellation is preserved. */
public final class MiningBinding {
    public static final KeyBind mine=KeyBind.add("fpv-mine-hold",KeyCode.mouseRight,"FPV flight");
    private MiningBinding(){}
}
