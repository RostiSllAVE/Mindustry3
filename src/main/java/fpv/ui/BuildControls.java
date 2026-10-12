package fpv.ui;

import arc.Core;
import arc.func.Boolp;
import arc.scene.ui.Slider;
import arc.scene.ui.layout.Table;
import arc.scene.event.Touchable;
import fpv.generated.NavigationConfig;
import fpv.generated.RuntimeConfig;
import mindustry.gen.Tex;
import java.util.function.IntFunction;

/** Native scene controls around the existing builder; no mouse capture or world writes. */
public final class BuildControls implements AutoCloseable {
    private final Table root=new Table();
    private final Table hoverInfo=new Table();
    private boolean cameraExpanded;
    private boolean updatingEyeSlider;
    private boolean removeConnector;
    public BuildControls(Boolp visible){this(visible,null,null);}
    public BuildControls(Boolp visible,fpv.construction.LayerBuildController layers,fpv.construction.LayerRuntime runtime){
        root.name="fpv-build-controls";root.setFillParent(true);root.top().left().marginTop(130).marginLeft(8);
        root.touchable=Touchable.childrenOnly;
        root.visible(()->visible.get()&&!Core.scene.hasDialog());
        var pane=root.pane(panel->{
            panel.background(Tex.pane);
            panel.defaults().width(260).pad(4);
            panel.add("[accent]BUILD / precision flight").left().row();
            if(layers!=null){
                panel.label(layers::label).left().row();
                panel.table(buttons->{buttons.button("Lower floor",()->layers.setLayer(layers.layer()-1)).width(124);buttons.button("Higher floor",()->layers.setLayer(layers.layer()+1)).width(124);}).row();
                panel.check("Lock placement floor",false,layers::setFloorLocked).update(box->box.setChecked(layers.floorLocked())).row();
                panel.button(layers.riserChoice(),layers::cycleRiser).update(button->button.setText(layers.riserChoice())).height(28).row();
                panel.check("Place platform",false,layers::setPlatform).update(box->box.setChecked(layers.platform())).row();
                panel.check("FREE placement (cheat)",false,layers::setFree).update(box->{box.setDisabled(!runtime.allowedFree()||layers.layer()==0);box.setChecked(layers.free());}).row();
                panel.check("Host: permit Free construction",Core.settings.getBool("fpv-layer-free-permission",false),value->{if(!mindustry.Vars.net.client())Core.settings.put("fpv-layer-free-permission",value);})
                    .update(box->{box.setDisabled(mindustry.Vars.net.client());box.setChecked(runtime.allowedFree());}).row();
                panel.add("PgUp / PgDn: lock construction floor\nSurface under cursor: automatic placement\nRMB clears selected block before dismantling").fontScale(.7f).left().row();
                panel.label(layers::dismantleBindingLabel).wrap().fontScale(.7f).left().row();
                panel.label(layers::status).wrap().fontScale(.72f).left().row();
                panel.label(layers::selectionLabel).wrap().fontScale(.75f).left().row();
                panel.label(layers::inventoryLabel).wrap().fontScale(.72f).left().row();
                panel.label(layers::cargoLabel).wrap().fontScale(.72f).left().row();
                panel.table(buttons->{buttons.button("Deposit cargo",layers::deposit).width(124);buttons.button("Withdraw",layers::withdraw).width(124);}).row();
                panel.button("Rotate selected structure",layers::rotateSelected).height(28).row();
                panel.button("Configure selected block",layers::configureSelected).height(28).row();
                panel.label(layers::configurationLinkLabel).wrap().fontScale(.72f).left().row();
                panel.table(buttons->{buttons.button("Remember source",layers::rememberConfigurationSource).width(124);buttons.button("Link target",layers::linkConfigurationTarget).width(124);}).row();
                panel.add("RMB clears placement / cancels links.\nClick visible source, then destination on any floor.\nShift-click starts the chosen vertical riser.").fontScale(.7f).left().row();
                panel.label(layers::bridgeLabel).wrap().fontScale(.72f).left().row();
                panel.button("Remember bridge source",layers::rememberBridge).height(28).row();
                panel.button("Link source to selected bridge",()->layers.connectBridge(false)).height(28).row();
                panel.button("Unlink selected bridge pair",()->layers.connectBridge(true)).height(28).row();
                panel.label(layers::connectorTarget).fontScale(.72f).left().row();
                panel.check("Remove connector",false,value->removeConnector=value).row();
                panel.table(buttons->{buttons.button("Items up",()->layers.connect(fpv.construction.LayerVerticalTransfers.Kind.ITEMS,true,removeConnector)).width(124);buttons.button("Items down",()->layers.connect(fpv.construction.LayerVerticalTransfers.Kind.ITEMS,false,removeConnector)).width(124);}).row();
                panel.table(buttons->{buttons.button("Liquid up",()->layers.connect(fpv.construction.LayerVerticalTransfers.Kind.LIQUIDS,true,removeConnector)).width(124);buttons.button("Liquid down",()->layers.connect(fpv.construction.LayerVerticalTransfers.Kind.LIQUIDS,false,removeConnector)).width(124);}).row();
                panel.button("Power riser",()->layers.connect(fpv.construction.LayerVerticalTransfers.Kind.POWER,true,removeConnector)).height(28).row();
            }
            slider(panel,"Build movement speed","fpv-build-speed",(int)NavigationConfig.DEFAULT_SPEED_MPS,0,(int)NavigationConfig.MAX_SPEED_MPS,i->i+" m/s");
            slider(panel,"Flight speed (outside Build)","fpv-flight-speed",100,25,300,i->i+"%");
            slider(panel,"World speed","fpv-time",2,0,5,i->RuntimeConfig.TIME_PERCENT[i]+"%");
            panel.add("WASD move  |  E / Q climb\nHome / End turn  |  TAB fly").fontScale(.75f).left().row();
            panel.button("Flight / camera",()->cameraExpanded=!cameraExpanded).height(30).row();
            panel.collapser(camera->{
                camera.defaults().width(260).pad(4);
                camera.button("Flight mode",()->Core.settings.put("fpv-flight-mode",Core.settings.getInt("fpv-flight-mode",0)==0?1:0))
                    .update(b->b.setText(Core.settings.getInt("fpv-flight-mode",0)==0?"F9: Acro flight":"F9: Stabilized flight")).height(30).row();
                camera.button("Camera view",CameraView::cycle)
                    .update(b->b.setText("F7: "+CameraView.label(CameraView.selected()))).height(30).row();
                camera.button("Camera smoothing",CameraView::toggleSmoothing)
                    .update(b->b.setText("Camera Smoothing: "+(CameraView.smoothing()?"ON":"OFF"))).height(30).row();
                camera.add("Default: Alt + F7; rebind in Controls > FPV camera.\nModifier: FPV settings. OFF = immediate follow.").fontScale(.7f).left().row();
                camera.add("Stabilized: WASD follows pilot heading\nE / Q or PgUp / PgDn altitude\nLooking up/down does not cause climb\nF7: FPV / front / rear; pilot aim stays unchanged\nAlt + wheel: third-person distance").fontScale(.72f).left().row();
                slider(camera,"Third-person distance","fpv-camera-distance",100,25,400,i->i+"% (unit size)");
                slider(camera,"Build tilt","fpv-build-tilt",(int)RuntimeConfig.BUILD_CAMERA_TILT,-75,-10,i->i+" deg");
                slider(camera,"FPV angle","fpv-pov-angle",105,0,180,i->i+" deg");
                camera.add("0 down / 90 forward / 180 up").fontScale(.75f).left().row();
                slider(camera,"FPV + Build FOV","fpv-fov",(int)RuntimeConfig.FOV,0,180,i->i+" deg");
                camera.label(()->{
                    var unit=mindustry.Vars.player==null?null:mindustry.Vars.player.unit();
                    if(!fpv.flight.GroundEyeHeight.ordinary(unit))return "Ground eye: ordinary ground units only";
                    int value=Core.settings.getInt(fpv.flight.GroundEyeHeight.settingKey(unit),0);
                    return unit.type.localizedName+" eye lift: "+(value==0?"AUTO":String.format(java.util.Locale.ROOT,"%.2f tiles",value/100.0));
                }).fontScale(.75f).left().row();
                Slider eye=new Slider(0,1200,5,false);
                eye.changed(()->{var unit=mindustry.Vars.player==null?null:mindustry.Vars.player.unit();
                    if(!updatingEyeSlider&&fpv.flight.GroundEyeHeight.ordinary(unit))Core.settings.put(fpv.flight.GroundEyeHeight.settingKey(unit),(int)eye.getValue());});
                eye.update(()->{var unit=mindustry.Vars.player==null?null:mindustry.Vars.player.unit();boolean ordinary=fpv.flight.GroundEyeHeight.ordinary(unit);
                    eye.setDisabled(!ordinary);updatingEyeSlider=true;
                    try{eye.setValue(ordinary?Core.settings.getInt(fpv.flight.GroundEyeHeight.settingKey(unit),0):0);}finally{updatingEyeSlider=false;}});
                camera.add(eye).height(24).row();
                camera.add("0 = automatic; override is saved for this unit type.\nOwn hull and ceilings still limit the eye.").fontScale(.7f).left().row();
            },false,()->cameraExpanded).row();
        }).maxHeight(Math.max(150,Core.graphics.getHeight()/arc.scene.ui.layout.Scl.scl()-170));
        pane.get().setScrollingDisabled(true,false);
        root.update(()->{
            float height=Math.max(150,Core.graphics.getHeight()/arc.scene.ui.layout.Scl.scl()-170);
            if(Math.abs(pane.maxHeight()-arc.scene.ui.layout.Scl.scl(height))>1){pane.maxHeight(height);root.invalidateHierarchy();}
        });
        Core.scene.add(root);
        if(layers!=null){hoverInfo.setFillParent(true);hoverInfo.top().marginTop(74);hoverInfo.touchable=Touchable.disabled;
            hoverInfo.visible(()->visible.get()&&!Core.scene.hasDialog()&&!layers.hoverLabel().isBlank());
            hoverInfo.table(Tex.pane,table->table.label(layers::hoverLabel).wrap().fontScale(.8f).width(400).pad(5));Core.scene.add(hoverInfo);}
    }
    private static void slider(Table table,String label,String key,int fallback,int low,int high,IntFunction<String> format){
        table.label(()->label+": "+(mindustry.Vars.net.active()&&key.equals("fpv-time")?"100% / live server":
            serverPreference(key)?"server controlled":format.apply(Math.max(low,Math.min(high,Core.settings.getInt(key,fallback)))))).fontScale(.82f).left().row();
        Slider slider=new Slider(low,high,1,false);
        slider.setValue(Core.settings.getInt(key,fallback));
        slider.moved(value->Core.settings.put(key,Math.round(value)));
        slider.update(()->{slider.setDisabled(serverPreference(key)||(mindustry.Vars.net.active()&&key.equals("fpv-time")));if(!slider.isDragging())slider.setValue(Core.settings.getInt(key,fallback));});
        table.add(slider).height(24).row();
    }
    private static boolean serverPreference(String key){return mindustry.Vars.net.client()&&(key.equals("fpv-flight-speed")||key.equals("fpv-build-speed"));}
    public boolean visible(){root.updateVisibility();return root.visible;}
    @Override public void close(){root.remove();hoverInfo.remove();}
}
