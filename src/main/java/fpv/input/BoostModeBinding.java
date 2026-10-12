package fpv.input;

import arc.input.KeyBind;
import arc.input.KeyCode;

/** KeyBind is persisted/rebindable by the native Controls dialog; no build/command modifier overload. */
public final class BoostModeBinding {
    public static final KeyBind toggle=KeyBind.add("fpv-native-boost-toggle",KeyCode.f2,"FPV flight");
    private BoostModeBinding(){}
}
