package fpv.combat;

import mindustry.game.Team;
import mindustry.gen.Bullet;

public final class CompositeDefenseTest {
    private static int checks;
    private static final class Stub implements ProjectedDefense {
        double fraction;int hits;boolean laserWall;Stub(double f){fraction=f;}
        public Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius){return fraction<0?null:new Contact(fraction,ax+(bx-ax)*fraction,0,0,this);}
        public Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean absorbLaserWalls){return laserWall&&!absorbLaserWalls?null:trace(team,ax,ay,az,bx,by,bz,radius);}
        public boolean hit(Contact contact,Bullet bullet,float amount){if(contact.token()!=this)throw new AssertionError("Wrong contact owner");hits++;return true;}
    }
    public static void main(String[] args){
        var wall=new Stub(.7);var force=new Stub(.2);var both=new CompositeDefense(wall,force);
        var first=both.trace(null,0,0,0,10,0,0,0);yes(first.fraction()==.2&&both.hit(first,null,10),"nearest force wins regardless of registration order");
        yes(force.hits==1&&wall.hits==0,"one pool receives damage");
        force.fraction=-1;var next=both.trace(null,0,0,0,10,0,0,0);yes(next.fraction()==.7&&both.hit(next,null,20),"broken force exposes wall");
        yes(wall.hits==1,"wall token correctly restored");
        wall.laserWall=true;wall.fraction=.1;force.fraction=.2;
        var laser=both.traceLaser(null,0,0,0,10,0,0,0,true);yes(laser.fraction()==.1,"ordinary laser retains nearest absorbing wall");
        var rail=both.traceLaser(null,0,0,0,10,0,0,0,false);yes(rail.fraction()==.2&&both.hit(rail,null,3),"native nonabsorbing beam bypasses wall but retains Force interception");
        yes(force.hits==2&&wall.hits==1,"nonabsorbing beam routes damage only to Force pool");
        force.fraction=-1;yes(both.traceLaser(null,0,0,0,10,0,0,0,false)==null,"nonabsorbing beam crosses lone wall");
        var nested=new CompositeDefense(both);force.fraction=.3;yes(nested.traceLaser(null,0,0,0,10,0,0,0,false).fraction()==.3,"native family flag survives nested defense routing");force.fraction=-1;
        wall.fraction=Double.NaN;yes(both.trace(null,0,0,0,10,0,0,0)==null,"invalid contact rejected");
        yes(!both.hit(new ProjectedDefense.Contact(0,0,0,0,wall),null,10),"foreign unrouted token rejected");
        System.out.println("CompositeDefense: "+checks+" assertions passed.");
    }
    private static void yes(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
}
