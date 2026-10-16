package com.pvzce.client.gui.workshop;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.PackIcons;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import com.pvzce.common.resource.PvzcePack;
import com.pvzce.common.workshop.Workspace;
import java.nio.file.Files;
import java.util.function.Consumer;

/** Pack identity is workspace metadata; the icon is kept at the pack root through every build. */
final class WorkshopPackScreen extends Screen {
    private final Workspace workspace;
    private final Consumer<Workspace> changed;
    private final PackIcons icons;
    private EditBox name;
    private String notice="图标保存在草稿中，构建后生效";
    WorkshopPackScreen(PvzceClient client,Workspace workspace,Consumer<Workspace> changed){super(client);this.workspace=workspace;this.changed=changed;icons=new PackIcons(client);}
    protected void init(){
        String previous=name==null?workspace.name():name.value();
        int width=Math.min(480,client.guiWidth()-32),x=centerX(width),h=client.guiHeight();
        name=new EditBox(x,h-100,width,28,256,null);name.setValue(previous,false);addWidget(name);
        int y=Math.max(66,h/2-60);
        addWidget(new Button(x,y,width/2-4,28,"选择图标…",()->WorkshopAssets.chooseFile(this,"PNG 图标",source->{try{WorkshopAssets.importPackIcon(workspace,source);icons.refresh();notice="图标已保存到草稿";}catch(Exception e){notice=e.getMessage();}})));
        addWidget(new Button(x+width/2+4,y,width/2-4,28,"移除图标",()->{try{Files.deleteIfExists(workspace.file(PvzcePack.ICON_FILE));icons.refresh();notice="已恢复默认图标";}catch(Exception e){notice=e.getMessage();}}));
        addWidget(new Button(x,24,width/2-4,28,"保存并返回",()->{try{changed.accept(workspace.rename(name.value()));client.popScreen();}catch(Exception e){notice=e.getMessage();}}));
        addWidget(new Button(x+width/2+4,24,width/2-4,28,"返回",this::requestClose));
    }
    public void render(){
        renderBackground(.08F,.12F,.1F);WorkshopUi.backdrop(client);
        client.fonts().body().draw("工作区设置 · "+workspace.namespace(),16,client.guiHeight()-30,1,1,1,.8F,1);
        int side=Math.max(28,Math.min(108,client.guiHeight()-230));
        icons.draw(workspace.root(),(client.guiWidth()-side)/2F,client.guiHeight()/2F-16,side);
        WorkshopUi.fit(client,notice,16,8,client.guiWidth()-32,.7F,1,.9F,.7F);
        for(var widget:widgets)widget.render(client);
    }
    protected void onRemoved(){icons.close();}
}
