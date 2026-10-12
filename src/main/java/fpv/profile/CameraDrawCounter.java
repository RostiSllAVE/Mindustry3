package fpv.profile;

import arc.Core;
import arc.graphics.*;
import java.lang.reflect.*;
import java.util.IdentityHashMap;

/** Separate count-only run. Reflection overhead is explicitly excluded from performance comparisons. */
public final class CameraDrawCounter {
    private static long draws,triangles;
    private CameraDrawCounter(){}
    public static void install(){
        if(!CameraPerformanceProfile.ENABLED||!Boolean.getBoolean("fpv.cameraProfile.countDraws"))return;
        var proxies=new IdentityHashMap<GL20,GL20>();
        Core.gl=wrap(Core.gl,proxies);Core.gl20=wrap(Core.gl20,proxies);if(Core.gl30!=null)Core.gl30=(GL30)wrap(Core.gl30,proxies);
    }
    private static GL20 wrap(GL20 nativeGL,IdentityHashMap<GL20,GL20> proxies){
        if(nativeGL==null)return null;return proxies.computeIfAbsent(nativeGL,key->(GL20)Proxy.newProxyInstance(GL20.class.getClassLoader(),new Class<?>[]{key instanceof GL30?GL30.class:GL20.class},(proxy,method,args)->{
            String name=method.getName();if(name.startsWith("glDrawArrays")||name.startsWith("glDrawElements")){
                draws++;int mode=(Integer)args[0],count=(Integer)args[name.startsWith("glDrawArrays")?2:1],instances=1;
                if(name.contains("Instanced"))instances=(Integer)args[name.startsWith("glDrawArrays")?3:4];
                if(mode==Gl.triangles)triangles+=(long)count/3*instances;else if(mode==Gl.triangleStrip||mode==Gl.triangleFan)triangles+=(long)Math.max(0,count-2)*instances;
            }
            try{return method.invoke(key,args);}catch(InvocationTargetException error){throw error.getCause();}
        }));
    }
    public static void reset(){draws=triangles=0;}
    public static long draws(){return draws;}public static long triangles(){return triangles;}
}
