package fpv.construction;

import java.util.Arrays;

/** Immutable authoritative upper-grid descriptor; it never owns or mutates a native Tile. */
public record LayerComponent(long id,Kind kind,int x,int y,int layer,String blockName,int size,int rotation,int team,Mode mode,
    double health,double maxHealth,double mass,double height,SupportProfile support,byte[] configuration,byte[] state){
    public enum Kind { PLATFORM,BLOCK }
    public enum Mode { STRUCTURAL,FREE }
    public static final int MAX_STATE_BYTES=65536,MAX_CONFIG_BYTES=16384,MAX_COORDINATE=32760;
    public record SupportProfile(int span,double capacity){
        public static final SupportProfile NONE=new SupportProfile(0,0);
        public SupportProfile {if(span<0||span>1024||!Double.isFinite(capacity)||capacity<0||capacity>1e12||capacity==0&&span!=0)throw new IllegalArgumentException("Invalid structural profile");}
    }
    public LayerComponent {
        if(id<=0||id>=Long.MAX_VALUE||kind==null||mode==null||Math.abs((long)x)>MAX_COORDINATE||Math.abs((long)y)>MAX_COORDINATE||layer<1||layer>LayerCoordinates.MAX_LAYERS||
            blockName==null||blockName.isBlank()||blockName.length()>128||size<1||size>32||rotation<0||rotation>3||team<0||team>255||support==null||
            !Double.isFinite(health)||!Double.isFinite(maxHealth)||health<0||maxHealth<=0||health>maxHealth||maxHealth>1e12||!Double.isFinite(mass)||mass<=0||mass>1e9||!Double.isFinite(height)||height<=0||height>256)
            throw new IllegalArgumentException("Invalid elevated component");
        configuration=configuration==null?new byte[0]:configuration.clone();state=state==null?new byte[0]:state.clone();
        if(configuration.length>MAX_CONFIG_BYTES||state.length>MAX_STATE_BYTES)throw new IllegalArgumentException("Elevated component state exceeds bound");
    }
    @Override public byte[] configuration(){return configuration.clone();}
    @Override public byte[] state(){return state.clone();}
    public boolean column(){return kind==Kind.BLOCK&&support.capacity()>0;}
    public LayerComponent withId(long value){return copy(value,mode,health,configuration,state);}
    public LayerComponent withHealth(double value){return copy(id,mode,value,configuration,state);}
    public LayerComponent withMode(Mode value){return copy(id,value,health,configuration,state);}
    public LayerComponent withConfiguration(byte[] value){return copy(id,mode,health,value,state);}
    public LayerComponent withState(byte[] value){return copy(id,mode,health,configuration,value);}
    private LayerComponent copy(long newId,Mode newMode,double hp,byte[] config,byte[] data){return new LayerComponent(newId,kind,x,y,layer,blockName,size,rotation,team,newMode,hp,maxHealth,mass,height,support,config,data);}
    @Override public boolean equals(Object value){
        if(this==value)return true;if(!(value instanceof LayerComponent c))return false;
        return id==c.id&&kind==c.kind&&x==c.x&&y==c.y&&layer==c.layer&&blockName.equals(c.blockName)&&size==c.size&&rotation==c.rotation&&team==c.team&&mode==c.mode&&
            Double.compare(health,c.health)==0&&Double.compare(maxHealth,c.maxHealth)==0&&Double.compare(mass,c.mass)==0&&Double.compare(height,c.height)==0&&support.equals(c.support)&&Arrays.equals(configuration,c.configuration)&&Arrays.equals(state,c.state);
    }
    @Override public int hashCode(){return Long.hashCode(id);}
}
