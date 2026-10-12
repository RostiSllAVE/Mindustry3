package fpv.construction;

import fpv.physics.SweptBodies;
import java.util.*;
import fpv.construction.LayerCoordinates.Bounds;

/** Sparse upper grids plus a physical chunk index. Empty space below platforms remains empty. */
public final class LayerWorld {
    public static final int MAX_COMPONENTS=65536,MAX_OCCUPIED_CELLS=1048576,MAX_INDEX_REFERENCES=1048576;
    public record Cell(int x,int y,int layer,LayerComponent.Kind kind){}
    public record ChunkKey(int x,int y,int layer){}
    public record Snapshot(LayerCoordinates coordinates,long nextId,List<LayerComponent> components){
        public Snapshot{if(coordinates==null||nextId<=0||nextId>=Long.MAX_VALUE||components==null||components.size()>MAX_COMPONENTS)throw new IllegalArgumentException("Invalid layer snapshot");components=List.copyOf(components);}
    }
    @FunctionalInterface public interface ChangeListener {void changed(LayerComponent before,LayerComponent after);}
    public record SweepHit(LayerComponent component,double fraction,double nx,double ny,double nz){}
    private LayerCoordinates coordinates;
    private final TreeMap<Long,LayerComponent> entries=new TreeMap<>();
    private final Map<Cell,Long> cells=new HashMap<>();
    private final Map<ChunkKey,LinkedHashSet<Long>> chunks=new HashMap<>();
    private final Map<Long,Integer> coveredColumns=new HashMap<>();
    private final ArrayList<ChangeListener> listeners=new ArrayList<>();
    private long nextId=1,revision,geometryRevision,indexReferences;
    public LayerWorld(LayerCoordinates coordinates){this.coordinates=Objects.requireNonNull(coordinates);}
    public LayerCoordinates coordinates(){return coordinates;}
    public void setMaximumLayers(int value){
        LayerCoordinates next=new LayerCoordinates(coordinates.spacing(),value);
        for(var component:entries.values())if(component.layer()>value)throw new IllegalArgumentException("Cannot hide occupied construction floors");
        if(value!=coordinates.maxLayers()){coordinates=next;revision++;geometryRevision++;}
    }
    public long revision(){return revision;}
    public long geometryRevision(){return geometryRevision;}
    public int size(){return entries.size();}
    public long nextId(){if(nextId>=Long.MAX_VALUE-1)throw new IllegalStateException("Construction IDs exhausted");return nextId++;}
    public void advanceNextId(long minimum){if(minimum<=0||minimum>=Long.MAX_VALUE)throw new IllegalArgumentException("Valid construction allocator required");nextId=Math.max(nextId,minimum);}
    public Collection<LayerComponent> components(){return Collections.unmodifiableCollection(entries.values());}
    public Set<ChunkKey> chunkKeys(){return Collections.unmodifiableSet(chunks.keySet());}
    public LayerComponent get(long id){return entries.get(id);}
    public LayerComponent at(int x,int y,int layer,LayerComponent.Kind kind){Long id=cells.get(new Cell(x,y,layer,kind));return id==null?null:entries.get(id);}
    public boolean columnCovered(int x,int y){return coveredColumns.containsKey(columnKey(x,y));}
    public boolean columnsCovered(int x,int y,int size){int minX=LayerCoordinates.minCell(x,size),minY=LayerCoordinates.minCell(y,size);for(int tx=minX;tx<minX+size;tx++)for(int ty=minY;ty<minY+size;ty++)if(columnCovered(tx,ty))return true;return false;}
    public void addListener(ChangeListener listener){listeners.add(Objects.requireNonNull(listener));}
    public void removeListener(ChangeListener listener){listeners.remove(listener);}
    public void add(LayerComponent component){
        validate(component,0);entries.put(component.id(),component);index(component,true);nextId=Math.max(nextId,component.id()+1);revision++;geometryRevision++;notifyChanged(null,component);
    }
    public LayerComponent remove(long id){LayerComponent old=entries.remove(id);if(old!=null){index(old,false);revision++;geometryRevision++;notifyChanged(old,null);}return old;}
    public void replace(LayerComponent component){
        LayerComponent old=entries.get(component.id());if(old==null)throw new IllegalArgumentException("Missing component to replace");if(old.equals(component))return;
        boolean sameVolume=sameVolume(old,component);
        if(!sameVolume){validate(component,old.id());index(old,false);}entries.put(component.id(),component);if(!sameVolume)index(component,true);
        revision++;if(!sameVolume||old.rotation()!=component.rotation()||old.team()!=component.team()||(old.health()>0)!=(component.health()>0))geometryRevision++;notifyChanged(old,component);
    }
    private static boolean sameVolume(LayerComponent a,LayerComponent b){return a.kind()==b.kind()&&a.x()==b.x()&&a.y()==b.y()&&a.layer()==b.layer()&&a.size()==b.size()&&a.height()==b.height();}
    public boolean canAdd(LayerComponent component){try{validate(component,0);return true;}catch(IllegalArgumentException invalid){return false;}}
    private void validate(LayerComponent c,long ignored){
        if(c==null||c.layer()>coordinates.maxLayers()||c.id()!=ignored&&entries.containsKey(c.id())||ignored==0&&entries.size()>=MAX_COMPONENTS)throw new IllegalArgumentException("Duplicate or out-of-bounds elevated component");
        Bounds box=coordinates.bounds(c);
        LayerComponent old=entries.get(ignored);long oldCells=old==null?0:(long)old.size()*old.size(),oldReferences=old==null?0:referenceCount(coordinates.bounds(old));
        if(cells.size()-oldCells+(long)c.size()*c.size()>MAX_OCCUPIED_CELLS||indexReferences-oldReferences+referenceCount(box)>MAX_INDEX_REFERENCES)throw new IllegalArgumentException("Construction spatial index exceeds practical bound");
        int minX=LayerCoordinates.minCell(c.x(),c.size()),minY=LayerCoordinates.minCell(c.y(),c.size());
        for(int x=minX;x<minX+c.size();x++)for(int y=minY;y<minY+c.size();y++){Long other=cells.get(new Cell(x,y,c.layer(),c.kind()));if(other!=null&&other!=ignored)throw new IllegalArgumentException("Construction footprint occupied");}
        for(LayerComponent other:query(box))if(other.id()!=ignored&&box.overlaps(coordinates.bounds(other))&&!columnJoint(c,other))throw new IllegalArgumentException("Construction physical volume occupied");
    }
    /** The last slab thickness of a continuous supporting column is an intentional material joint. */
    private boolean columnJoint(LayerComponent a,LayerComponent b){
        LayerComponent wall=a.layer()<b.layer()?a:b,upper=wall==a?b:a;
        if(!wall.column()||wall.team()!=upper.team())return false;
        double cap=coordinates.bounds(wall).maxZ()-coordinates.plane(upper.layer());
        // Only a slab seated at the solid column top may overlap its final material thickness.
        if(upper.kind()==LayerComponent.Kind.PLATFORM&&Math.abs(cap)<1e-7)return true;
        // Decode old 0.14.0-2 decorative caps. Runtime placement rejects these overlaps;
        // local-save migration repairs them before exposing the loaded world.
        if(wall.layer()+1!=upper.layer()||Math.abs(wall.height()/coordinates.spacing()-Math.rint(wall.height()/coordinates.spacing()))<1e-7)return false;
        // A bounded structural cap embeds into the next floor; unrelated volumes still cannot overlap.
        if(cap< -1e-7||cap>Math.min(.4,coordinates.spacing()*.3)+1e-7)return false;
        if(upper.kind()==LayerComponent.Kind.PLATFORM)return true;
        var lowerBox=coordinates.bounds(wall);var upperBox=coordinates.bounds(upper);
        return upperBox.minX()<=lowerBox.minX()+1e-7&&upperBox.maxX()>=lowerBox.maxX()-1e-7&&upperBox.minY()<=lowerBox.minY()+1e-7&&upperBox.maxY()>=lowerBox.maxY()-1e-7;
    }
    public List<LayerComponent> query(Bounds box){
        Objects.requireNonNull(box);ArrayList<LayerComponent> result=new ArrayList<>();HashSet<Long> seen=new HashSet<>();
        if(entries.isEmpty())return result;
        int minX=chunk(box.minX()),maxX=chunk(box.maxX()),minY=chunk(box.minY()),maxY=chunk(box.maxY());
        int minZ=physicalLayer(box.minZ()),maxZ=physicalLayer(box.maxZ());
        long nx=(long)maxX-minX+1,ny=(long)maxY-minY+1,nz=(long)maxZ-minZ+1,limit=Math.max(64,chunks.size()*2L);
        if(minX< -4096||maxX>4096||minY< -4096||maxY>4096||nx>limit||ny>limit/nx||nz>limit/(nx*ny)){
            for(LayerComponent c:entries.values())if(box.intersects(coordinates.bounds(c)))result.add(c);
        }else for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++){
            var ids=chunks.get(new ChunkKey(x,y,z));if(ids==null)continue;
            for(long id:ids)if(seen.add(id)){LayerComponent c=entries.get(id);if(c!=null&&box.intersects(coordinates.bounds(c)))result.add(c);}
        }
        result.sort(Comparator.comparingLong(LayerComponent::id));return result;
    }
    public SweepHit sweep(double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){
        if(!Double.isFinite(ax+ay+az+bx+by+bz+radius+halfHeight)||radius<0||halfHeight<0)throw new IllegalArgumentException("Finite sweep required");
        Bounds broad=new Bounds(Math.min(ax,bx)-radius,Math.min(ay,by)-radius,Math.min(az,bz)-halfHeight,Math.max(ax,bx)+radius,Math.max(ay,by)+radius,Math.max(az,bz)+halfHeight);
        SweepHit best=null;for(var c:query(broad)){Bounds box=coordinates.bounds(c);var hit=SweptBodies.box(ax,ay,az,bx-ax,by-ay,bz-az,box.minX()-radius,box.minY()-radius,box.minZ()-halfHeight,box.maxX()+radius,box.maxY()+radius,box.maxZ()+halfHeight);
            if(hit!=null&&(best==null||hit.time()<best.fraction()))best=new SweepHit(c,hit.time(),hit.nx(),hit.ny(),hit.nz());}
        return best;
    }
    public Snapshot snapshot(){return new Snapshot(coordinates,nextId,new ArrayList<>(entries.values()));}
    /** Validate a replacement completely before changing the live grid. */
    public void apply(Snapshot snapshot){
        LayerWorld checked=new LayerWorld(snapshot.coordinates());for(var c:snapshot.components())checked.add(c);
        if(snapshot.nextId()<checked.nextId)throw new IllegalArgumentException("Saved construction ID allocator precedes entries");
        ArrayList<LayerComponent> old=new ArrayList<>(entries.values());coordinates=checked.coordinates;entries.clear();entries.putAll(checked.entries);cells.clear();cells.putAll(checked.cells);chunks.clear();chunks.putAll(checked.chunks);coveredColumns.clear();coveredColumns.putAll(checked.coveredColumns);indexReferences=checked.indexReferences;nextId=snapshot.nextId();revision++;geometryRevision++;
        for(var c:old)notifyChanged(c,null);for(var c:entries.values())notifyChanged(null,c);
    }
    public void clear(){if(entries.isEmpty())return;var old=new ArrayList<>(entries.values());entries.clear();cells.clear();chunks.clear();coveredColumns.clear();indexReferences=0;revision++;geometryRevision++;for(var c:old)notifyChanged(c,null);}
    private void notifyChanged(LayerComponent before,LayerComponent after){for(var listener:listeners)listener.changed(before,after);}
    private void index(LayerComponent c,boolean add){
        int minX=LayerCoordinates.minCell(c.x(),c.size()),minY=LayerCoordinates.minCell(c.y(),c.size());
        for(int x=minX;x<minX+c.size();x++)for(int y=minY;y<minY+c.size();y++){
            Cell key=new Cell(x,y,c.layer(),c.kind());long column=columnKey(x,y);
            if(add){cells.put(key,c.id());coveredColumns.merge(column,1,Integer::sum);}else{cells.remove(key,c.id());coveredColumns.computeIfPresent(column,(k,n)->n<=1?null:n-1);}
        }
        Bounds b=coordinates.bounds(c);indexReferences+=(add?1:-1)*referenceCount(b);for(int z=physicalLayer(b.minZ());z<=physicalLayer(b.maxZ());z++)for(int x=chunk(b.minX());x<=chunk(b.maxX());x++)for(int y=chunk(b.minY());y<=chunk(b.maxY());y++){
            ChunkKey key=new ChunkKey(x,y,z);if(add)chunks.computeIfAbsent(key,k->new LinkedHashSet<>()).add(c.id());else{var ids=chunks.get(key);if(ids!=null){ids.remove(c.id());if(ids.isEmpty())chunks.remove(key);}}
        }
    }
    private long referenceCount(Bounds b){return ((long)physicalLayer(b.maxZ())-physicalLayer(b.minZ())+1)*((long)chunk(b.maxX())-chunk(b.minX())+1)*((long)chunk(b.maxY())-chunk(b.minY())+1);}
    private static int chunk(double n){return (int)Math.floor(n/LayerCoordinates.CHUNK_SIZE);}
    private int physicalLayer(double z){return Math.max(0,Math.min(LayerCoordinates.MAX_LAYERS+1280,(int)Math.floor(z/coordinates.spacing())));}
    private static long columnKey(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
}
