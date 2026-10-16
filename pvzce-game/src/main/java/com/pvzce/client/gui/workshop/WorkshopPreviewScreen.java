package com.pvzce.client.gui.workshop;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.animation.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.workshop.Workspace;

/** Draft art uses the normal renderers; only client asset lookup is temporarily overlaid. */
public final class WorkshopPreviewScreen extends Screen {
    private final Workspace workspace;
    private final String path;
    private ArtTarget target;
    private com.pvzce.client.sound.PreviewChannel.Session audition;
    private boolean attached;
    private java.nio.file.Path previousOverlay;
    private String error="";
    public WorkshopPreviewScreen(PvzceClient client,Workspace workspace,String path){super(client);this.workspace=workspace;this.path=path;}
    protected void init(){
        try {
            if(!attached){previousOverlay=client.resources().workspaceOverlay();attached=true;client.previewWorkspaceAssets(workspace.root());}
            if(path.contains("/animations/")) {
                if(target!=null)target.stopAnimation();
                Identifier id=id();target=new ArtTarget(id);target.attach(client.animations());
                var file=client.animations().file(id).orElseThrow(()->new IllegalArgumentException("无法读取动画"));
                target.play(file.clipNames().contains("idle")?"idle":file.clipNames().iterator().next());
                addWidget(new Button(16,60,150,28,WorkshopUi.text("clip","选择动画动作"),()->client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("clip","动画动作"),file.clipNames(),state->{target.stopAnimation();target.play(state);} ))));
                addWidget(new Button(174,60,90,28,WorkshopUi.text("replay","重播"),()->{var playback=client.animations().playback(target);if(playback!=null)playback.restart();}));
            }else if(path.endsWith(".ogg"))addWidget(new Button(centerX(180),client.guiHeight()/2,180,32,WorkshopUi.text("listen","试听"),()->{if(client.sound()!=null){if(audition==null)audition=client.sound().preview();audition.play(id().toString());}}));
        }catch(Exception e){error=e.getMessage();}
        addWidget(new Button(client.guiWidth()-116,16,100,28,WorkshopUi.text("back","返回"),this::requestClose));
    }
    static Identifier assetId(String path){String[] p=path.split("/",4);return Identifier.parse(p[1]+":"+(p[2].equals("textures")?"textures/":"")+p[3].substring(0,p[3].lastIndexOf('.')));}
    private Identifier id(){return assetId(path);}
    static int[] previewArea(int width,int height) {
        int side=Math.max(40,Math.min(width-40,height-160));
        return new int[]{(width-side)/2,Math.max(10,Math.min(110,height-side-40)),side,side};
    }
    public void render(){
        renderBackground(0.08F,0.12F,0.1F);WorkshopUi.backdrop(client);
        client.fonts().body().draw(WorkshopScreen.shorten(error.isEmpty()?path:error,client.guiWidth()-32),16,client.guiHeight()-30,0.9F,1,1,0.8F,1);
        if(target!=null){
            var playback=client.animations().playback(target);
            if(playback!=null){
                int[] area=previewArea(client.guiWidth(),client.guiHeight());
                client.beginOverlayWorldView(area[0],area[1],area[2],area[3],-1.2F,1.2F,-0.25F,2.15F);
                playback.render(client,0,0,0,1,1);client.beginGuiView();
            }
        }else if(path.endsWith(".png"))client.drawTexture(id(),client.guiWidth()/2F-64,client.guiHeight()/2F-48,128,128,0,1,1,1,1);
        for(var widget:widgets)widget.render(client);
    }
    protected void onHidden(){if(audition!=null)audition.stop();}
    protected void onRemoved(){if(audition!=null)audition.close();if(target!=null)target.stopAnimation();if(attached){attached=false;client.previewWorkspaceAssets(previousOverlay);}}
}
