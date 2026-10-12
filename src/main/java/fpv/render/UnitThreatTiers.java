package fpv.render;

import java.util.ArrayList;
import mindustry.content.TechTree;
import mindustry.core.ContentLoader;
import mindustry.type.UnitType;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.UnitAssembler;
import mindustry.world.blocks.units.UnitFactory;

/** Immutable visual-only snapshot of native production and core research progression.
 * Construct once after content initialization. It never edits UnitType, production plans,
 * weapons, research nodes or save/network state; render-time lookup allocates nothing.
 */
public final class UnitThreatTiers {
    public enum Marker {
        UNKNOWN(0,"?"),T1(1,"●"),T2(2,"●●"),T3(3,"▲"),T4(4,"■"),T5(5,"☠");
        public final int tier;
        public final String symbol;
        Marker(int tier,String symbol){this.tier=tier;this.symbol=symbol;}
    }
    private static final Marker[] markers=Marker.values();
    private final UnitType[] identities;
    private final Marker[] byId;

    public UnitThreatTiers(ContentLoader content){
        if(content==null)throw new IllegalArgumentException("initialized content is required");
        identities=new UnitType[content.units().size];byId=new Marker[identities.length];
        var graph=new UnitTierProgression(identities.length);
        for(UnitType unit:content.units())if(unit.id>=0&&unit.id<identities.length)identities[unit.id]=unit;
        for(var block:content.blocks()){
            if(block instanceof UnitFactory factory){
                for(var plan:factory.plans)graph.seed(id(plan.unit),1);
            }else if(block instanceof Reconstructor reconstructor){
                for(UnitType[] pair:reconstructor.upgrades)if(pair!=null&&pair.length>=2)graph.upgrade(id(pair[0]),id(pair[1]));
            }
            if(block instanceof CoreBlock core){
                // Ignore ordinary intervening research nodes: only core upgrade ancestry is a rank.
                for(var node:core.techNodes){
                    int depth=0,budget=TechTree.all.size+1;var ancestor=node;
                    while(ancestor!=null&&budget-->0){
                        if(ancestor.content instanceof CoreBlock)depth++;
                        ancestor=ancestor.parent;
                    }
                    graph.seed(id(core.unitType),ancestor==null?depth:0);
                }
            }
            if(block instanceof UnitAssembler assembler){
                int[] outputs=new int[assembler.plans.size];int[][] inputs=new int[outputs.length][];
                for(int i=0;i<outputs.length;i++){
                    var plan=assembler.plans.get(i);outputs[i]=id(plan.unit);
                    var payloads=new ArrayList<Integer>();
                    if(plan.requirements!=null)for(var requirement:plan.requirements){
                        if(requirement.item instanceof UnitType input&&requirement.amount>0)payloads.add(id(input));
                    }
                    inputs[i]=new int[payloads.size()];for(int j=0;j<inputs[i].length;j++)inputs[i][j]=payloads.get(j);
                }
                graph.assembly(outputs,inputs);
            }
        }
        int[] tiers=graph.resolve();
        for(int i=0;i<byId.length;i++)byId[i]=markers[tiers[i]];
    }
    public int tier(UnitType unit){return marker(unit).tier;}
    public Marker marker(UnitType unit){int id=id(unit);return id<0?Marker.UNKNOWN:byId[id];}
    private int id(UnitType unit){return unit!=null&&unit.id>=0&&unit.id<identities.length&&identities[unit.id]==unit?unit.id:-1;}
}
