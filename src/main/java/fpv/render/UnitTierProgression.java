package fpv.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure production graph. A tier is a progression step, never a unit-stat estimate. */
final class UnitTierProgression {
    private record Edge(int to,int change){}
    private record Assembly(int[] outputs,int[][] inputs){}
    private record Snapshot(int[] tiers,int[] components,int[] maximum){}
    private final List<Edge>[] edges;
    private final int[] seeds;
    private final List<Assembly> assemblies=new ArrayList<>();

    @SuppressWarnings("unchecked") UnitTierProgression(int count){
        if(count<0||count>65536)throw new IllegalArgumentException("bounded native content count required");
        edges=(List<Edge>[])new List<?>[count];seeds=new int[count];
        for(int i=0;i<count;i++)edges[i]=new ArrayList<>();
    }
    void seed(int unit,int tier){
        if(!valid(unit))return;
        int value=tier>=1&&tier<=5?tier:-1;
        seeds[unit]=seeds[unit]==0||seeds[unit]==value?value:-1;
    }
    void upgrade(int from,int to){
        if(!valid(from)||!valid(to))return;
        edges[from].add(new Edge(to,1));edges[to].add(new Edge(from,-1));
    }
    void assembly(int[] outputs,int[][] unitPayloads){
        int[][] inputs=new int[unitPayloads.length][];
        for(int i=0;i<inputs.length;i++)inputs[i]=unitPayloads[i].clone();
        assemblies.add(new Assembly(outputs.clone(),inputs));
    }
    int[] resolve(){
        int[] anchors=seeds.clone();Snapshot nativeChains=solve(anchors);
        for(Assembly assembly:assemblies){
            int family=-1;boolean known=assembly.outputs.length==assembly.inputs.length&&assembly.outputs.length>0;
            for(int[] inputs:assembly.inputs){
                if(inputs.length==0)known=false;
                for(int input:inputs){
                    if(!valid(input)||nativeChains.tiers[input]==0){known=false;continue;}
                    int current=nativeChains.components[input];
                    if(family!=-1&&family!=current)known=false;
                    family=current;
                }
            }
            // The assembler starts after its payload family's native reconstructor chain.
            // Its plan index is the native assembly-module stage, not the payload's own tier.
            if(!known||family<0)continue;
            int base=nativeChains.maximum[family];
            for(int i=0;i<assembly.outputs.length;i++){
                int output=assembly.outputs[i],tier=base+i+1;
                if(!valid(output))continue;
                int value=tier<=5?tier:-1;
                anchors[output]=anchors[output]==0||anchors[output]==value?value:-1;
            }
        }
        return solve(anchors).tiers;
    }
    private Snapshot solve(int[] anchors){
        int n=edges.length;int[] component=new int[n],relative=new int[n],tiers=new int[n],maximum=new int[n],queue=new int[n];
        Arrays.fill(component,-1);int group=0;
        for(int start=0;start<n;start++){
            if(component[start]>=0)continue;
            int read=0,write=1,offset=0;boolean anchored=false,consistent=true;queue[0]=start;component[start]=group;
            while(read<write){
                int unit=queue[read++];
                if(anchors[unit]<0)consistent=false;
                else if(anchors[unit]>0){
                    int proposed=anchors[unit]-relative[unit];
                    if(anchored&&offset!=proposed)consistent=false;
                    offset=proposed;anchored=true;
                }
                for(Edge edge:edges[unit]){
                    int next=relative[unit]+edge.change;
                    if(component[edge.to]<0){component[edge.to]=group;relative[edge.to]=next;queue[write++]=edge.to;}
                    else if(relative[edge.to]!=next)consistent=false;
                }
            }
            if(consistent&&anchored)for(int i=0;i<write;i++){
                int unit=queue[i],tier=relative[unit]+offset;
                if(tier>=1&&tier<=5)tiers[unit]=tier;
                maximum[group]=Math.max(maximum[group],tier);
            }
            group++;
        }
        return new Snapshot(tiers,component,maximum);
    }
    private boolean valid(int unit){return unit>=0&&unit<edges.length;}
}
