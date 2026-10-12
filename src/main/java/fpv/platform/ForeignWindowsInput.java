package fpv.platform;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

/** Java 22+ java.base-only Win32 binding. Reflection keeps the mod Java 17 loadable.
 * Uses the host's existing native-access permission, never changes it or loads JNA.
 * No additional DLL is shipped; only Windows system libraries are opened. */
public final class ForeignWindowsInput extends WindowsMouseCapture {
    public ForeignWindowsInput(){super(new ForeignApi());}

    private static final class ForeignApi implements Api {
        private Object arena,rect,point,pid,nil;
        private Class<?> segment,layout,descriptor,option;
        private Object linker,ints,address,user32,kernel32;
        private MethodHandle readInt,writeInt,pointer,addressValue;
        private MethodHandle foreground,process,isWindow,isIconic,client,toScreen,cursor,warp,clip,getClip,error;

        ForeignApi(){
            try{
                if(!System.getProperty("os.name","").startsWith("Windows")||!"64".equals(System.getProperty("sun.arch.data.model")))
                    throw new UnsupportedOperationException("FFM mouse capture requires 64-bit Windows");
                Class<?> arenaClass=Class.forName("java.lang.foreign.Arena");
                segment=Class.forName("java.lang.foreign.MemorySegment");
                layout=Class.forName("java.lang.foreign.MemoryLayout");
                descriptor=Class.forName("java.lang.foreign.FunctionDescriptor");
                Class<?> valueLayout=Class.forName("java.lang.foreign.ValueLayout");
                Class<?> intLayout=Class.forName("java.lang.foreign.ValueLayout$OfInt");
                Class<?> linkerClass=Class.forName("java.lang.foreign.Linker");
                option=Class.forName("java.lang.foreign.Linker$Option");
                arena=arenaClass.getMethod("ofConfined").invoke(null);
                linker=linkerClass.getMethod("nativeLinker").invoke(null);
                ints=valueLayout.getField("JAVA_INT").get(null);
                address=valueLayout.getField("ADDRESS").get(null);
                nil=segment.getField("NULL").get(null);
                Class<?> lookup=Class.forName("java.lang.foreign.SymbolLookup");
                user32=lookup.getMethod("libraryLookup",String.class,arenaClass).invoke(null,"user32.dll",arena);
                kernel32=lookup.getMethod("libraryLookup",String.class,arenaClass).invoke(null,"kernel32.dll",arena);
                var handles=java.lang.invoke.MethodHandles.publicLookup();
                readInt=java.lang.invoke.MethodHandles.insertArguments(handles.unreflect(segment.getMethod("get",intLayout,long.class)),1,ints)
                    .asType(MethodType.methodType(int.class,Object.class,long.class));
                writeInt=java.lang.invoke.MethodHandles.insertArguments(handles.unreflect(segment.getMethod("set",intLayout,long.class,int.class)),1,ints)
                    .asType(MethodType.methodType(void.class,Object.class,long.class,int.class));
                pointer=handles.unreflect(segment.getMethod("ofAddress",long.class)).asType(MethodType.methodType(Object.class,long.class));
                addressValue=handles.unreflect(segment.getMethod("address")).asType(MethodType.methodType(long.class,Object.class));
                // Fixed native buffers are allocated once; no arena/mesh/buffer generation per frame.
                var allocate=Class.forName("java.lang.foreign.SegmentAllocator").getMethod("allocate",long.class,long.class);
                rect=allocate.invoke(arena,16L,4L);point=allocate.invoke(arena,8L,4L);pid=allocate.invoke(arena,4L,4L);
                foreground=bind(user32,"GetForegroundWindow",address).asType(MethodType.methodType(Object.class));
                process=bind(user32,"GetWindowThreadProcessId",ints,address,address).asType(MethodType.methodType(int.class,Object.class,Object.class));
                isWindow=bind(user32,"IsWindow",ints,address).asType(MethodType.methodType(int.class,Object.class));
                isIconic=bind(user32,"IsIconic",ints,address).asType(MethodType.methodType(int.class,Object.class));
                client=bind(user32,"GetClientRect",ints,address,address).asType(MethodType.methodType(int.class,Object.class,Object.class));
                toScreen=bind(user32,"ClientToScreen",ints,address,address).asType(MethodType.methodType(int.class,Object.class,Object.class));
                cursor=bind(user32,"GetCursorPos",ints,address).asType(MethodType.methodType(int.class,Object.class));
                warp=bind(user32,"SetCursorPos",ints,ints,ints).asType(MethodType.methodType(int.class,int.class,int.class));
                clip=bind(user32,"ClipCursor",ints,address).asType(MethodType.methodType(int.class,Object.class));
                getClip=bind(user32,"GetClipCursor",ints,address).asType(MethodType.methodType(int.class,Object.class));
                error=bind(kernel32,"GetLastError",ints).asType(MethodType.methodType(int.class));
            }catch(Throwable failure){
                close();throw unavailable(failure);
            }
        }

        private RuntimeException unavailable(Throwable failure){
            if(failure instanceof InvocationTargetException wrapped)failure=wrapped.getCause();
            return new IllegalStateException("java.base native mouse access unavailable: "+failure,failure);
        }
        private MethodHandle bind(Object library,String name,Object result,Object... parameters)throws ReflectiveOperationException{
            Class<?> lookup=Class.forName("java.lang.foreign.SymbolLookup");
            Object symbol=((Optional<?>)lookup.getMethod("find",String.class).invoke(library,name)).orElseThrow(()->new IllegalStateException("Missing Win32 symbol "+name));
            Object layouts=Array.newInstance(layout,parameters.length);
            for(int i=0;i<parameters.length;i++)Array.set(layouts,i,parameters[i]);
            Object desc=descriptor.getMethod("of",layout,layouts.getClass()).invoke(null,result,layouts);
            Object options=Array.newInstance(option,0);
            return (MethodHandle)Class.forName("java.lang.foreign.Linker").getMethod("downcallHandle",segment,descriptor,options.getClass()).invoke(linker,symbol,desc,options);
        }
        private int get(Object memory,long offset){try{return (int)readInt.invokeExact(memory,offset);}catch(Throwable t){throw unavailable(t);}}
        private void put(Object memory,long offset,int value){try{writeInt.invokeExact(memory,offset,value);}catch(Throwable t){throw unavailable(t);}}
        private Object window(long value){try{return (Object)pointer.invokeExact(value);}catch(Throwable t){throw unavailable(t);}}
        private int call(MethodHandle fn,Object p){try{return (int)fn.invokeExact(p);}catch(Throwable t){throw unavailable(t);}}
        private int call(MethodHandle fn,Object p,Object q){try{return (int)fn.invokeExact(p,q);}catch(Throwable t){throw unavailable(t);}}
        public long GetForegroundWindow(){try{return (long)addressValue.invokeExact((Object)foreground.invokeExact());}catch(Throwable t){throw unavailable(t);}}
        public long processId(long w){return call(process,window(w),pid)==0?0:Integer.toUnsignedLong(get(pid,0));}
        public boolean IsWindow(long w){return call(isWindow,window(w))!=0;}
        public boolean IsIconic(long w){return call(isIconic,window(w))!=0;}
        private void from(Rect r){r.left=get(rect,0);r.top=get(rect,4);r.right=get(rect,8);r.bottom=get(rect,12);}
        public boolean GetClientRect(long w,Rect r){boolean ok=call(client,window(w),rect)!=0;from(r);return ok;}
        public boolean GetClipCursor(Rect r){boolean ok=call(getClip,rect)!=0;from(r);return ok;}
        public boolean ClipCursor(Rect r){if(r==null)return call(clip,nil)!=0;put(rect,0,r.left);put(rect,4,r.top);put(rect,8,r.right);put(rect,12,r.bottom);return call(clip,rect)!=0;}
        public boolean ClientToScreen(long w,Point p){put(point,0,p.x);put(point,4,p.y);boolean ok=call(toScreen,window(w),point)!=0;p.x=get(point,0);p.y=get(point,4);return ok;}
        public boolean GetCursorPos(Point p){boolean ok=call(cursor,point)!=0;p.x=get(point,0);p.y=get(point,4);return ok;}
        public boolean SetCursorPos(int x,int y){try{return (int)warp.invokeExact(x,y)!=0;}catch(Throwable t){throw unavailable(t);}}
        public int lastError(){try{return (int)error.invokeExact();}catch(Throwable t){return 0;}}
        public void close(){if(arena!=null){try{Class.forName("java.lang.foreign.Arena").getMethod("close").invoke(arena);}catch(ReflectiveOperationException ignored){}arena=null;}}
    }
}
