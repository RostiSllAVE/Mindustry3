package fpv.construction;

import fpv.net.NetProtocol;
import java.util.Arrays;

/** Wire commands cannot provide owner, price, HP, support or arbitrary configuration payloads. */
public final class LayerActionsTest {
    private static int checks;
    public static void main(String[] args){
        for(var mode:LayerComponent.Mode.values())for(boolean platform:new boolean[]{false,true}){
            var a=LayerActions.Action.place(22,31,512,"titanium-wall-large",platform,3,mode);
            byte[] encoded=NetProtocol.bytes(out->LayerActions.write(out,a));
            require(NetProtocol.read(encoded,LayerActions::read).equals(a),"all placement fields roundtrip");
            require(encoded.length+21<=256,"request fits bounded authenticated transport");
            for(int i=0;i<encoded.length;i++){byte[] truncated=Arrays.copyOf(encoded,i);reject(()->NetProtocol.read(truncated,LayerActions::read));}
            byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);reject(()->NetProtocol.read(trailing,LayerActions::read));
            byte[] opcode=encoded.clone();opcode[0]=99;reject(()->NetProtocol.read(opcode,LayerActions::read));
        }
        for(var a:new LayerActions.Action[]{LayerActions.Action.remove(181),LayerActions.Action.rotate(182,3)})require(NetProtocol.read(NetProtocol.bytes(out->LayerActions.write(out,a)),LayerActions::read).equals(a),"stable component identity roundtrip");
        for(var r:new LayerActions.Result[]{LayerActions.Result.ok(188),LayerActions.Result.rejected("Insufficient copper"),LayerActions.Result.queued()})require(NetProtocol.read(NetProtocol.bytes(out->LayerActions.writeResult(out,r)),LayerActions::readResult).equals(r),"server result roundtrip");
        reject(()->LayerActions.Action.place(3,4,0,"copper-wall",false,0,LayerComponent.Mode.STRUCTURAL));
        reject(()->LayerActions.Action.place(3,4,513,"copper-wall",false,0,LayerComponent.Mode.STRUCTURAL));
        reject(()->LayerActions.Action.place(-1,4,1,"copper-wall",false,0,LayerComponent.Mode.STRUCTURAL));
        reject(()->LayerActions.Action.place(1,4,1,"bad/path",false,0,LayerComponent.Mode.STRUCTURAL));
        reject(()->LayerActions.Action.remove(0));
        System.out.println("LayerActionsTest PASS "+checks+" checks");
    }
    private static void require(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Invalid wire value accepted");}catch(IllegalArgumentException expected){checks++;}}
}
