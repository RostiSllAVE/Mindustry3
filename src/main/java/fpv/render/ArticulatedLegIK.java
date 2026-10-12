package fpv.render;

/** Stable pole-vector two-link solution with explicit telescoping at tall stances. Pure canonical XYZ. */
public final class ArticulatedLegIK {
    private ArticulatedLegIK(){}
    public static void knee(double hx,double hy,double hz,double fx,double fy,double fz,double nativeLength,
                            double outwardX,double outwardY,int side,double[] out){
        if(out==null||out.length<3||nativeLength<=0||!Double.isFinite(hx+hy+hz+fx+fy+fz+nativeLength+outwardX+outwardY))throw new IllegalArgumentException("Finite leg endpoints required");
        double dx=fx-hx,dy=fy-hy,dz=fz-hz,d=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(d<1e-8){out[0]=hx+outwardX*nativeLength*.3;out[1]=hy+outwardY*nativeLength*.3;out[2]=hz;return;}
        double ux=dx/d,uy=dy/d,uz=dz/d;
        // Native left/right bend remains stable; upward/outward pole avoids a vertical table-leg pose.
        double px=outwardX*.65-outwardY*side*.6,py=outwardY*.65+outwardX*side*.6,pz=.45;
        double dot=px*ux+py*uy+pz*uz;px-=dot*ux;py-=dot*uy;pz-=dot*uz;
        double pole=Math.sqrt(px*px+py*py+pz*pz);
        if(pole<1e-7){px=-uy;py=ux;pz=0;pole=Math.sqrt(px*px+py*py);if(pole<1e-7){px=1;py=0;pz=0;pole=1;}}
        double link=Math.max(nativeLength*.5,d/(2*.86)),bend=Math.sqrt(Math.max(0,link*link-d*d*.25));
        out[0]=(hx+fx)*.5+px/pole*bend;out[1]=(hy+fy)*.5+py/pole*bend;out[2]=(hz+fz)*.5+pz/pole*bend;
    }
}
