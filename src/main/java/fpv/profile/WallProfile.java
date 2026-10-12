package fpv.profile;

import arc.util.serialization.Jval;
import java.lang.management.ManagementFactory;

/** Opt-in measurements only; all counters are owned by the native game/render thread. */
public final class WallProfile {
    private WallProfile() {}
    private static final boolean enabled = Boolean.getBoolean("fpv.wallProfile");
    private static final com.sun.management.ThreadMXBean bean = allocationBean();
    private static boolean measuring;
    private static long frames, frameNanos, frameCpuNanos, frameBytes, frameStarted, frameCpuStarted, frameBytesStarted;
    private static long updateCalls, updateNanos, updateBytes, queryCalls, queryNanos, queryBytes, candidates;
    private static long renderCalls, renderNanos, renderBytes, faces, segments, walls, draws, vertices, uploadBytes;
    private static long fullScans, volumeRecords, cacheBuilds, cacheBytes, logicalObjects, chunks, drawObjects;
    private static double frameMaximumMs;
    private static long epoch,gpuSamples,gpuNanos,gpuMaxNanos,compositions,depthCopies;
    private static boolean gpuAvailable;
    private static String gpuStatus="not initialized";

    private static com.sun.management.ThreadMXBean allocationBean() {
        if (!enabled) return null;
        try {
            var candidate = ManagementFactory.getThreadMXBean();
            if (!(candidate instanceof com.sun.management.ThreadMXBean value)) return null;
            if (value.isThreadAllocatedMemorySupported() && !value.isThreadAllocatedMemoryEnabled()) value.setThreadAllocatedMemoryEnabled(true);
            if (value.isThreadCpuTimeSupported() && !value.isThreadCpuTimeEnabled()) value.setThreadCpuTimeEnabled(true);
            return value;
        } catch (Throwable ignored) { return null; }
    }
    public static boolean active() { return enabled && measuring; }
    public static long epoch(){return epoch;}
    public static void gpuStatus(boolean available,String status){gpuAvailable=available;gpuStatus=status;}
    public static void gpuSample(long nanos){if(active()){gpuSamples++;gpuNanos+=nanos;gpuMaxNanos=Math.max(gpuMaxNanos,nanos);}}
    public static void composition(){if(active())compositions++;}
    public static void depthCopy(){if(active())depthCopies++;}
    public static long start() { return active() ? System.nanoTime() : 0; }
    public static long allocated() {
        if (!active() || bean == null || !bean.isThreadAllocatedMemorySupported()) return -1;
        return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    private static long allocatedSince(long before) { return before < 0 ? 0 : Math.max(0, allocated() - before); }
    public static void reset() {
        frames = frameNanos = frameCpuNanos = frameBytes = 0;
        updateCalls = updateNanos = updateBytes = queryCalls = queryNanos = queryBytes = candidates = 0;
        renderCalls = renderNanos = renderBytes = faces = segments = walls = draws = vertices = uploadBytes = 0;
        fullScans = volumeRecords = cacheBuilds = cacheBytes = logicalObjects = chunks = drawObjects = 0;
        frameMaximumMs = 0;gpuSamples=gpuNanos=gpuMaxNanos=compositions=depthCopies=0;epoch++; measuring = enabled;
    }
    public static void stop() { measuring = false; }
    public static void frameBegin() {
        if (!active()) return;
        frameStarted = System.nanoTime(); frameBytesStarted = allocated();
        frameCpuStarted = bean != null && bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : -1;
    }
    public static void frameEnd() {
        if (!active()) return;
        long elapsed = System.nanoTime() - frameStarted;
        frames++; frameNanos += elapsed; frameMaximumMs = Math.max(frameMaximumMs, elapsed / 1e6);
        frameBytes += allocatedSince(frameBytesStarted);
        if (frameCpuStarted >= 0) frameCpuNanos += Math.max(0, bean.getCurrentThreadCpuTime() - frameCpuStarted);
    }
    public static void update(long start, long bytes) { if(active()){ updateCalls++; updateNanos += System.nanoTime()-start; updateBytes += allocatedSince(bytes); } }
    public static void query(long start, long bytes, long tested) { if(active()){ queryCalls++; queryNanos += System.nanoTime()-start; queryBytes += allocatedSince(bytes); candidates += tested; } }
    public static void render(long start, long bytes, int wallCount, int segmentCount, int faceCount) {
        if(active()){ renderCalls++; renderNanos += System.nanoTime()-start; renderBytes += allocatedSince(bytes); walls += wallCount; segments += segmentCount; faces += faceCount; }
    }
    public static void draw(int vertexCount, int floatCount) { if(active()){ draws++; vertices += vertexCount; uploadBytes += (long)floatCount*Float.BYTES; } }
    public static void fullScan() { if(active()) fullScans++; }
    public static void volumeRecord() { if(active()) volumeRecords++; }
    public static void cacheBuild(long bytes) { if(active()){ cacheBuilds++; cacheBytes += bytes; } }
    public static void objects(int states, int chunkCount, int gpuMeshes) { if(active()){ logicalObjects = Math.max(logicalObjects,states); chunks = Math.max(chunks,chunkCount); drawObjects = Math.max(drawObjects,gpuMeshes); } }
    public static Jval report() {
        Jval out=Jval.newObject();
        out.add("gpuTimerStatus",gpuStatus);put(out,"gpuTimerAvailable",gpuAvailable?1:0);put(out,"gpuSamples",gpuSamples);put(out,"wallGpuMeanMs",gpuSamples>0?mean(gpuNanos,gpuSamples)/1e6:-1);put(out,"wallGpuMaxMs",gpuSamples>0?gpuMaxNanos/1e6:-1);put(out,"compositionDrawCallsPerRender",mean(compositions,renderCalls));put(out,"depthCopiesPerRender",mean(depthCopies,renderCalls));
        put(out,"enabled",enabled?1:0); put(out,"frames",frames);
        put(out,"frameMeanMs",mean(frameNanos,frames)/1e6); put(out,"frameMaxMs",frameMaximumMs);
        put(out,"frameCpuMeanMs",bean==null?-1:mean(frameCpuNanos,frames)/1e6);
        put(out,"fpsFromMeasuredFrameWork",frameNanos>0?frames*1e9/frameNanos:0);
        put(out,"threadAllocatedBytesPerFrame",bean==null?-1:mean(frameBytes,frames));
        put(out,"threadAllocatedBytesPerSecond",bean==null?-1:(frameNanos>0?frameBytes*1e9/frameNanos:0));
        put(out,"projectedWallLogicalObjects",logicalObjects); put(out,"indexedChunks",chunks); put(out,"gpuMeshObjects",drawObjects);
        put(out,"updateCalls",updateCalls); put(out,"updateMeanMs",mean(updateNanos,updateCalls)/1e6); put(out,"updateAllocatedBytesPerCall",bean==null?-1:mean(updateBytes,updateCalls));
        put(out,"queryCalls",queryCalls); put(out,"queryMeanMicroseconds",mean(queryNanos,queryCalls)/1e3); put(out,"queryCandidatesPerCall",mean(candidates,queryCalls)); put(out,"queryAllocatedBytesPerCall",bean==null?-1:mean(queryBytes,queryCalls));
        put(out,"renderCalls",renderCalls); put(out,"wallRenderMeanMs",mean(renderNanos,renderCalls)/1e6); put(out,"wallRenderAllocatedBytesPerCall",bean==null?-1:mean(renderBytes,renderCalls));
        put(out,"visibleLogicalWallsPerRender",mean(walls,renderCalls)); put(out,"visibleLogicalSegmentsPerRender",mean(segments,renderCalls)); put(out,"renderedFacesPerRender",mean(faces,renderCalls));
        put(out,"drawCallsPerRender",mean(draws,renderCalls)); put(out,"verticesPerRender",mean(vertices,renderCalls)); put(out,"trianglesPerRender",mean(vertices,renderCalls)/3); put(out,"uploadedBytesPerRender",mean(uploadBytes,renderCalls));
        put(out,"fullMapScans",fullScans); put(out,"materializedVolumeRecords",volumeRecords); put(out,"chunkCacheBuilds",cacheBuilds); put(out,"rebuiltCacheBytes",cacheBytes);
        return out;
    }
    private static double mean(long value,long count) { return count==0?0:(double)value/count; }
    private static void put(Jval out,String key,double value) { out.add(key,Jval.valueOf(value)); }
}
