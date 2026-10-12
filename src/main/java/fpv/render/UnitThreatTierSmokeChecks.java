package fpv.render;

import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.type.UnitType;
import mindustry.type.unit.MissileUnitType;

/** Read-only verification against the installed game's real loaded content. */
public final class UnitThreatTierSmokeChecks {
    private UnitThreatTierSmokeChecks(){}
    public static String verify(){
        long before=fingerprint();var cache=new UnitThreatTiers(Vars.content);int checks=0;
        UnitType[][] families={
            {UnitTypes.dagger,UnitTypes.mace,UnitTypes.fortress,UnitTypes.scepter,UnitTypes.reign},
            {UnitTypes.crawler,UnitTypes.atrax,UnitTypes.spiroct,UnitTypes.arkyid,UnitTypes.toxopid},
            {UnitTypes.nova,UnitTypes.pulsar,UnitTypes.quasar,UnitTypes.vela,UnitTypes.corvus},
            {UnitTypes.flare,UnitTypes.horizon,UnitTypes.zenith,UnitTypes.antumbra,UnitTypes.eclipse},
            {UnitTypes.mono,UnitTypes.poly,UnitTypes.mega,UnitTypes.quad,UnitTypes.oct},
            {UnitTypes.risso,UnitTypes.minke,UnitTypes.bryde,UnitTypes.sei,UnitTypes.omura},
            {UnitTypes.retusa,UnitTypes.oxynoe,UnitTypes.cyerce,UnitTypes.aegires,UnitTypes.navanax},
            {UnitTypes.stell,UnitTypes.locus,UnitTypes.precept,UnitTypes.vanquish,UnitTypes.conquer},
            {UnitTypes.elude,UnitTypes.avert,UnitTypes.obviate,UnitTypes.quell,UnitTypes.disrupt},
            {UnitTypes.merui,UnitTypes.cleroi,UnitTypes.anthicus,UnitTypes.tecta,UnitTypes.collaris},
            {UnitTypes.alpha,UnitTypes.beta,UnitTypes.gamma},
            {UnitTypes.evoke,UnitTypes.incite,UnitTypes.emanate}
        };
        for(UnitType[] family:families)for(int i=0;i<family.length;i++){
            require(cache.tier(family[i])==i+1,"actual native tier for "+family[i].name+": "+cache.tier(family[i]));checks++;
        }
        require(cache.tier(UnitTypes.assemblyDrone)==0&&cache.tier(UnitTypes.manifold)==0,"non-progression construction/cargo drones remain unranked");checks++;
        for(UnitType unit:Vars.content.units()){
            if(unit instanceof MissileUnitType){require(cache.tier(unit)==0,"summoned missile remains unranked: "+unit.name);checks++;}
            var marker=cache.marker(unit);for(int i=0;i<64;i++)require(cache.marker(unit)==marker,"lookup reuses the immutable enum");checks++;
        }
        require(cache.marker(null)==UnitThreatTiers.Marker.UNKNOWN,"null/unavailable type uses unknown marker");checks++;
        require(fingerprint()==before,"tier discovery leaves native types, weapons, production plans and research metadata untouched");checks++;
        return "PASS "+checks+" native threat-tier checks: all 50 combat tiers, six core stages, unranked auxiliaries, immutable metadata/cache";
    }
    private static long fingerprint(){
        long hash=1;
        for(UnitType unit:Vars.content.units()){
            hash=31*hash+System.identityHashCode(unit);hash=31*hash+unit.id;hash=31*hash+Float.floatToIntBits(unit.health);
            hash=31*hash+Float.floatToIntBits(unit.hitSize);hash=31*hash+Float.floatToIntBits(unit.speed);
            hash=31*hash+System.identityHashCode(unit.techNode);hash=31*hash+unit.techNodes.size;
            for(var weapon:unit.weapons){hash=31*hash+System.identityHashCode(weapon);hash=31*hash+System.identityHashCode(weapon.bullet);hash=31*hash+Float.floatToIntBits(weapon.bullet.damage);}
        }
        for(var block:Vars.content.blocks()){
            hash=31*hash+System.identityHashCode(block.techNode);hash=31*hash+block.techNodes.size;
            if(block instanceof mindustry.world.blocks.units.UnitFactory factory)for(var plan:factory.plans){hash=31*hash+System.identityHashCode(plan);hash=31*hash+System.identityHashCode(plan.unit);}
            if(block instanceof mindustry.world.blocks.units.Reconstructor reconstructor)for(UnitType[] pair:reconstructor.upgrades)for(UnitType unit:pair)hash=31*hash+System.identityHashCode(unit);
            if(block instanceof mindustry.world.blocks.units.UnitAssembler assembler)for(var plan:assembler.plans){hash=31*hash+System.identityHashCode(plan);hash=31*hash+System.identityHashCode(plan.unit);}
        }
        return hash;
    }
    private static void require(boolean pass,String message){if(!pass)throw new IllegalStateException(message);}
}
