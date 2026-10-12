package fpv.units.ground;

import mindustry.Vars;
import mindustry.type.UnitType;

/** Two supporting feet for explicitly extensible native whole-leg mechs. All lengths are tiles. */
public final class NativeMechLayout {
    private NativeMechLayout(){}
    public static double reach(UnitType type){return Math.max(.1,type.mechStride/Vars.tilesize);}
    public static double spread(UnitType type){return Math.max(.12,type.hitSize*.38/Vars.tilesize);}
    public static double footX(UnitType type,int index,double yaw){return -Math.sin(Math.toRadians(yaw))*spread(type)*(index==0?-1:1);}
    public static double footY(UnitType type,int index,double yaw){return Math.cos(Math.toRadians(yaw))*spread(type)*(index==0?-1:1);}
}
