package fpv.construction;

import java.util.*;
import fpv.construction.LayerComponent.*;
import fpv.construction.LayerCoordinates.Bounds;
import fpv.construction.StructuralSolver.*;

public final class LayerConstructionTest {
    private static int checks;
    private static final SupportProfile TITANIUM=new SupportProfile(7,1000),COPPER=new SupportProfile(1,1000);
    private static LayerComponent piece(long id,int x,int y,int layer,Kind kind,Mode mode,double mass,SupportProfile profile){return new LayerComponent(id,kind,x,y,layer,kind==Kind.PLATFORM?"fpv-platform":"titanium-wall",1,0,1,mode,100,100,mass,1.4,profile,new byte[]{1,2},new byte[]{3,4});}
    private static LayerComponent platform(long id,int x,int y,int layer){return piece(id,x,y,layer,Kind.PLATFORM,Mode.STRUCTURAL,1,SupportProfile.NONE);}
    private static LayerComponent wall(long id,int x,int y,int layer){return piece(id,x,y,layer,Kind.BLOCK,Mode.STRUCTURAL,1,TITANIUM);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void close(double a,double b,String message){check(Math.abs(a-b)<1e-8,message+" "+a+" != "+b);}
    private static void rejects(Runnable operation,String message){boolean rejected=false;try{operation.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,message);}
    public static void main(String[] args){coordinatesAndVolumes();persistence();structural();dirtyAndPreview();largeBase();System.out.println("LayerConstructionTest: "+checks+" checks passed");}
    private static void coordinatesAndVolumes(){
        var coordinates=LayerCoordinates.DEFAULT;close(coordinates.plane(64),89.6,"64 independent floors");check(LayerCoordinates.minCell(10,2)==10,"native even-size anchor");close(LayerCoordinates.center(10,4),10.5,"native even-size center");check(LayerCoordinates.minCell(10,4)==9,"large footprint");
        rejects(()->new LayerCoordinates(Double.NaN,64),"nonfinite spacing");rejects(()->coordinates.plane(-1),"negative layer");rejects(()->new LayerCoordinates(1.4,513),"practical layer cap");
        var world=new LayerWorld(coordinates);var slab=platform(1,4,4,2);world.add(slab);world.add(wall(2,4,4,2));world.add(wall(3,4,4,1));
        close(coordinates.bounds(slab).minZ(),2.64,"slab below construction plane");check(world.at(4,4,2,Kind.PLATFORM)==slab,"separate slab cell");check(world.at(4,4,2,Kind.BLOCK).id()==2,"block above same floor");check(world.at(4,4,1,Kind.BLOCK).id()==3,"independent floor cell");
        rejects(()->world.add(wall(4,4,4,2)),"same floor occupancy rejected");check(world.columnCovered(4,4),"upper column covers shield");world.remove(1);check(world.columnCovered(4,4),"removing one of several objects preserves coverage");world.remove(2);world.remove(3);check(!world.columnCovered(4,4),"last removal restores column");
        world.add(platform(5,10,10,4));check(world.query(new Bounds(9.6,9.6,.5,10.4,10.4,4)).isEmpty(),"air beneath platform remains empty");check(world.sweep(10,10,1,10,10,4,.1,.1)==null,"flight under roof unobstructed");var hit=world.sweep(10,10,4,10,10,7,.1,.1);check(hit!=null&&hit.component().id()==5&&hit.nz()==-1,"roof underside continuous collision");
        LayerComponent wide=new LayerComponent(6,Kind.BLOCK,20,20,1,"titanium-wall-large",2,3,1,Mode.STRUCTURAL,200,200,4,1.4,new SupportProfile(7,2000),null,null);world.add(wide);check(world.at(21,21,1,Kind.BLOCK).id()==6,"multi-tile lookup");check(world.at(19,20,1,Kind.BLOCK)==null,"no wrong even-size offset");
        byte[] bytes=wide.state();check(bytes.length==0,"null state normalized");var immutable=wall(7,30,30,1);byte[] config=immutable.configuration();config[0]=9;check(immutable.configuration()[0]==1,"descriptor state defensive copy");
        rejects(()->world.add(new LayerComponent(8,Kind.BLOCK,10,10,3,"tall",1,0,1,Mode.STRUCTURAL,100,100,1,3,SupportProfile.NONE,null,null)),"ordinary occupied physical volumes rejected");
        check(world.query(new Bounds(-Double.MAX_VALUE,-Double.MAX_VALUE,-1,Double.MAX_VALUE,Double.MAX_VALUE,100)).size()==2,"huge queries bounded without integer loop overflow");
        check(world.query(new Bounds(Double.MAX_VALUE,Double.MAX_VALUE,0,Double.MAX_VALUE,Double.MAX_VALUE,100)).isEmpty(),"distant extreme query bounded");
        var snapshot=world.snapshot();long revision=world.revision();rejects(()->world.apply(new LayerWorld.Snapshot(coordinates,100,List.of(wall(100,0,0,1),wall(101,0,0,1)))),"bad replacement rejected atomically");check(world.snapshot().equals(snapshot)&&world.revision()==revision,"failed apply preserves live world");
        world.setMaximumLayers(128);check(world.coordinates().maxLayers()==128&&world.get(5)!=null,"host can raise layer cap without replacing world");rejects(()->world.setMaximumLayers(3),"host cannot hide occupied higher floor");world.advanceNextId(500);check(world.nextId()==500,"reconciled allocator advances past removed server IDs");world.advanceNextId(100);check(world.nextId()==501,"allocator cannot rewind");
    }
    private static void persistence(){
        var world=new LayerWorld(new LayerCoordinates(2,128));world.add(platform(12,8,7,70).withMode(Mode.FREE).withHealth(34));world.add(wall(50,10,10,2));world.remove(50);long allocated=world.nextId();
        byte[] bytes=LayerCodec.encode(world);var loaded=LayerCodec.decode(bytes);check(loaded.snapshot().equals(world.snapshot()),"all descriptor state/config/mode/profile and allocator roundtrip");check(loaded.nextId()>allocated,"deleted IDs never reused after load");
        for(int cut=0;cut<bytes.length;cut++) {final int length=cut;rejects(()->LayerCodec.decode(Arrays.copyOf(bytes,length)),"truncated payload "+cut);}
        var bad=bytes.clone();bad[7]=99;rejects(()->LayerCodec.decode(bad),"future version rejected");rejects(()->LayerCodec.decode(Arrays.copyOf(bytes,bytes.length+1)),"trailing payload rejected");
        var invalid=bytes.clone();Arrays.fill(invalid,24,32,(byte)0);rejects(()->LayerCodec.decode(invalid),"invalid saved ID allocator rejected");
        var empty=new LayerWorld(LayerCoordinates.DEFAULT);check(LayerCodec.decode(LayerCodec.encode(empty)).size()==0,"empty old-world extension supported");
    }
    private static void structural(){
        var world=new LayerWorld(LayerCoordinates.DEFAULT);for(int x=0;x<4;x++)world.add(platform(x+1,x,0,1));
        try(var solver=new StructuralSolver(world,(x,y,t)->x==0&&y==0?new Foundation(1,t,1.4,1,100):null)){
            check(solver.support(1).status()==Status.SUPPORTED,"physical native foundation");check(solver.support(2).status()==Status.SUPPORTED,"copper one-tile span");check(solver.support(3).status()==Status.UNSUPPORTED,"span cannot reset across platform graph");check(solver.unsupportedSections().equals(List.of(List.of(3L,4L))),"unsupported connected aggregate");
        }
        var vertical=new LayerWorld(LayerCoordinates.DEFAULT);vertical.add(wall(1,0,0,1));vertical.add(platform(2,0,0,2));vertical.add(wall(3,0,0,2));vertical.add(platform(4,0,0,3));vertical.add(platform(5,1,0,3));
        try(var solver=new StructuralSolver(vertical,(x,y,t)->x==0&&y==0?new Foundation(10,t,1.4,7,100):null)){
            for(var c:vertical.components())check(solver.support(c.id()).supported(),"connected vertical columns "+c.id());
            vertical.remove(1);solver.invalidateColumn(0,0);for(var c:vertical.components())check(!solver.support(c.id()).supported(),"removed column loses ground path "+c.id());
        }
        var free=new LayerWorld(LayerCoordinates.DEFAULT);free.add(platform(1,10,0,2).withMode(Mode.FREE));free.add(wall(2,10,0,2));
        try(var solver=new StructuralSolver(free,(x,y,t)->null)){check(solver.support(1).status()==Status.FREE,"free object remains floating");check(solver.support(2).status()==Status.UNSUPPORTED,"free object never grants structural foundation");}
        var profiles=new LayerWorld(LayerCoordinates.DEFAULT);profiles.add(piece(1,0,0,1,Kind.BLOCK,Mode.STRUCTURAL,1,COPPER));profiles.add(platform(2,0,0,2));profiles.add(platform(3,1,0,2));profiles.add(platform(4,2,0,2));
        try(var solver=new StructuralSolver(profiles,(x,y,t)->x==0&&y==0?new Foundation(1,t,1.4,7,100):null)){check(solver.support(3).supported(),"upper material permits one horizontal tile");check(!solver.support(4).supported(),"weak column span constrains inherited route");}
        var load=new LayerWorld(LayerCoordinates.DEFAULT);load.add(piece(1,0,0,1,Kind.PLATFORM,Mode.STRUCTURAL,2,SupportProfile.NONE));load.add(piece(2,1,0,1,Kind.PLATFORM,Mode.STRUCTURAL,8,SupportProfile.NONE));
        try(var solver=new StructuralSolver(load,(x,y,t)->x==0&&y==0?new Foundation(1,t,1.4,7,10):null)){check(solver.support(2).supported(),"capacity exact bound supported");check(!solver.supports(platform(3,2,0,1)),"capacity overload preview rejected");load.add(platform(3,2,0,1));check(!solver.support(3).supported(),"overload marked unsupported");}
        var noTouch=new LayerWorld(LayerCoordinates.DEFAULT);noTouch.add(platform(1,0,0,3));try(var solver=new StructuralSolver(noTouch,(x,y,t)->new Foundation(1,t,1.4,7,100))){check(!solver.support(1).supported(),"foundation cannot reach through air gap");}
        var enemy=new LayerWorld(LayerCoordinates.DEFAULT);enemy.add(platform(1,0,0,1));try(var solver=new StructuralSolver(enemy,(x,y,t)->new Foundation(1,2,1.4,7,100))){check(!solver.support(1).supported(),"enemy wall is not own-team foundation");}
        var largeJoint=new LayerWorld(LayerCoordinates.DEFAULT);largeJoint.add(platform(1,0,0,1));largeJoint.add(wall(2,0,0,1));try(var solver=new StructuralSolver(largeJoint,(x,y,t)->x==0&&y==0?new Foundation(1,t,1.65,7,100):null)){check(solver.support(1).supported()&&solver.support(2).supported(),"support direction and IDs do not reject a downward slab joint");}
        var shared=new LayerWorld(LayerCoordinates.DEFAULT);shared.add(new LayerComponent(1,Kind.BLOCK,1,0,1,"heavy-crafter",3,0,1,Mode.STRUCTURAL,100,100,10,1.24,SupportProfile.NONE,null,null));
        try(var solver=new StructuralSolver(shared,(x,y,t)->y==0&&(x==0||x==2)?new Foundation(x+1,t,1.4,7,6):null)){
            check(solver.support(1).supported(),"several physical columns can share one wide component load");
            shared.add(piece(2,1,0,2,Kind.PLATFORM,Mode.STRUCTURAL,3,SupportProfile.NONE));check(!solver.support(2).supported(),"shared root capacities are conserved for later loads");
        }
    }
    private static void dirtyAndPreview(){
        var world=new LayerWorld(LayerCoordinates.DEFAULT);world.add(platform(1,0,0,1));world.add(platform(2,100,0,1));var present=new HashSet<>(List.of(0,100));
        try(var solver=new StructuralSolver(world,(x,y,t)->present.contains(x)&&y==0?new Foundation(x+1000,t,1.4,7,100):null)){
            check(solver.solveDirty()==2,"initial regions solved");check(solver.solveDirty()==0,"no idle structural rescan");long revision=world.revision();var snapshot=world.snapshot();check(solver.supports(platform(3,1,0,1)),"preview supported extension");check(world.revision()==revision&&world.snapshot().equals(snapshot),"preview does not mutate live state or IDs");
            present.remove(0);solver.invalidateColumn(0,0);check(solver.solveDirty()==1,"local foundation event touches only connected island");check(!solver.support(1).supported()&&solver.support(2).supported(),"unrelated island retained");
            check(solver.solveDirty()==0,"cached result reused");
            var sections=solver.unsupportedSections();check(sections==solver.unsupportedSections(),"unsupported section collection cached between edits");world.replace(world.get(2).withHealth(50).withState(new byte[]{9}));check(solver.solveDirty()==0,"ordinary health/native-state updates do not solve structural graph");
        }
    }
    private static void largeBase(){
        var world=new LayerWorld(LayerCoordinates.DEFAULT);long id=1;
        for(int x=0;x<32;x++)for(int y=0;y<32;y++)world.add(platform(id++,x,y,1));
        try(var solver=new StructuralSolver(world,(x,y,t)->x%6==0&&y%6==0?new Foundation(((long)x<<32)|(y&0xffffffffL),t,1.4,7,1000):null)){
            long start=System.nanoTime();int solved=solver.solveDirty();double elapsed=(System.nanoTime()-start)/1e6;
            check(solved==1024,"1024 connected platform base processed");check(solver.unsupportedSections().isEmpty(),"1024 platforms span and load paths remain supported");
            start=System.nanoTime();for(int i=0;i<1000;i++)check(solver.solveDirty()==0,"unchanged large base does no graph work");double idle=(System.nanoTime()-start)/1e6;
            long rev=world.geometryRevision();world.replace(world.get(1).withHealth(50));check(world.geometryRevision()==rev,"health does not invalidate navigation geometry");
            byte[] saved=LayerCodec.encode(world);check(LayerCodec.decode(saved).snapshot().equals(world.snapshot()),"1024 component state roundtrip");
            System.out.printf(java.util.Locale.ROOT,"Layer core measured CPU: 1024 connected components %.3f ms; 1000 cached idle checks %.3f ms; save %d bytes%n",elapsed,idle,saved.length);
        }
    }
}
