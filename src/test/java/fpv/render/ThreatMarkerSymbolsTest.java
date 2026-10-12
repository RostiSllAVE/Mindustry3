package fpv.render;

/** Procedural atlas semantics, filtering guard bands and exact camera-distance fade behavior. */
public final class ThreatMarkerSymbolsTest {
    private static int checks;
    public static void main(String[] args){
        int cell=ThreatMarkerSymbols.CELL,width=cell*ThreatMarkerSymbols.COUNT;
        int[] pixels=ThreatMarkerSymbols.pixels();check(pixels.length==width*cell,"six complete atlas cells");
        for(int tier=0;tier<ThreatMarkerSymbols.COUNT;tier++){
            int fill=0,dark=0,white=0;
            for(int y=0;y<cell;y++)for(int x=0;x<cell;x++){
                int rgba=pixels[tier*cell+x+y*width],r=rgba>>>24,g=(rgba>>>16)&255,b=(rgba>>>8)&255;
                check(r<=g&&g<=b,"fill, dark outline and controlled outline remain nested");
                check((rgba&255)==255,"atlas channels use opaque data storage");
                if(x==0||y==0||x==cell-1||y==cell-1)check((rgba>>>8)==0,"empty edge prevents filtered atlas bleed");
                if(r>0)fill++;if(g>r)dark++;if(b>g)white++;
                check(r==(pixels[tier*cell+cell-1-x+y*width]>>>24),"symbol remains horizontally symmetric");
            }
            check(fill>25&&dark>0&&white>0,"recognizable fill and both independent outline bands");
            check(components(pixels,tier)==(tier==2?2:1),"T2 has two separate dots; other symbols have one silhouette");
        }
        check(!ThreatMarkerSymbols.fill(0,0,0)&&ThreatMarkerSymbols.fill(0,.3,0),"unknown marker has a neutral hollow center");
        check(!ThreatMarkerSymbols.fill(2,0,0)&&ThreatMarkerSymbols.fill(2,-.21,0)&&ThreatMarkerSymbols.fill(2,.21,0),"T2 cannot collapse into one centered dot");
        check(ThreatMarkerSymbols.fill(3,0,.32)&&ThreatMarkerSymbols.fill(3,-.28,-.25)&&!ThreatMarkerSymbols.fill(3,-.28,.25),"T3 triangle points upward");
        check(ThreatMarkerSymbols.fill(5,0,.28)&&!ThreatMarkerSymbols.fill(5,.15,.105)&&!ThreatMarkerSymbols.fill(5,-.15,.105),"T5 skull has forehead and two eye sockets");
        check(!ThreatMarkerSymbols.fill(5,.085,-.24)&&ThreatMarkerSymbols.fill(5,.16,-.24),"T5 jaw includes visible tooth gaps");
        check(UnitThreatMarkerRenderer.fade(0)==1&&UnitThreatMarkerRenderer.fade(80)==1,"full opacity through 80 tiles");
        check(UnitThreatMarkerRenderer.fade(90)==.5f,"half opacity halfway through fade band");
        check(UnitThreatMarkerRenderer.fade(100)==0&&UnitThreatMarkerRenderer.fade(101)==0,"no opacity at and past hard distance limit");
        for(double distance:new double[]{-Double.MAX_VALUE,-1,-0.0,Double.MIN_VALUE,Math.nextDown(80d)})
            check(UnitThreatMarkerRenderer.fade(distance)==1,"near/negative finite distances clamp to full opacity");
        for(double distance:new double[]{Math.nextUp(100d),Double.MAX_VALUE,Double.POSITIVE_INFINITY})
            check(UnitThreatMarkerRenderer.fade(distance)==0,"extreme far distances never regain opacity");
        check(UnitThreatMarkerRenderer.fade(Math.nextDown(100d))>=0,"last representable point before limit stays nonnegative");
        float previous=1;
        for(int i=0;i<=1100;i++){
            float alpha=UnitThreatMarkerRenderer.fade(i*.1);check(Float.isFinite(alpha)&&alpha>=0&&alpha<=1&&alpha<=previous,"distance fade stays bounded and monotonic");previous=alpha;
        }
        System.out.println("ThreatMarkerSymbols: "+checks+" assertions passed.");
    }
    private static int components(int[] pixels,int tier){
        int cell=ThreatMarkerSymbols.CELL,width=cell*ThreatMarkerSymbols.COUNT,count=0;boolean[] seen=new boolean[cell*cell];int[] queue=new int[seen.length];
        for(int start=0;start<seen.length;start++){
            if(seen[start]||(pixels[tier*cell+start%cell+start/cell*width]>>>24)==0)continue;
            int read=0,write=1;queue[0]=start;seen[start]=true;count++;
            while(read<write){int next=queue[read++],x=next%cell,y=next/cell;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=cell||ny>=cell)continue;int index=nx+ny*cell;
                    if(!seen[index]&&(pixels[tier*cell+nx+ny*width]>>>24)>0){seen[index]=true;queue[write++]=index;}
                }
            }
        }
        return count;
    }
    private static void check(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
}
