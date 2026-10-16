package com.pvzce.client.gui.screens;

import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.registry.RegistryAccess;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.DialogueOverlay;

/** Plays an unsaved conversation using the game's own staging, bubbles, choices and voice. */
public final class DialoguePreviewScreen extends Screen {
    private final LevelDialogue dialogue;
    private final RegistryAccess content;
    private DialogueOverlay overlay;
    public DialoguePreviewScreen(PvzceClient client,LevelDialogue dialogue,RegistryAccess content){
        super(client);this.dialogue=dialogue;this.content=content;
    }
    protected void init(){
        overlay=DialogueOverlay.create(client,dialogue,()->client.popScreen(),content);
        addWidget(new Button(client.guiWidth()-146,12,130,28,"返回对话编辑",this::requestClose));
    }
    public void requestClose(){client.popScreen();}
    public void render(){
        renderBackground(.08F,.15F,.12F);
        client.drawSolid(0,0,client.guiWidth(),client.guiHeight(),-.5F,.02F,.05F,.03F,.6F);
        if(overlay!=null)overlay.render(client);
        client.drawSolid(0,0,client.guiWidth(),48,.1F,.02F,.04F,.03F,.88F);
        client.fonts().body().draw("对话预览 · 点击推进 · ESC 返回编辑",16,22,.8F,1,1,.8F,1);
        for(var widget:widgets)widget.render(client);
    }
    protected void onMouseClicked(double x,double y,int button){if(overlay!=null)overlay.mouseClicked(x,y,button);}
    protected void onMouseMoved(double x,double y){if(overlay!=null)overlay.mouseMoved(x,y);}
    protected void onMouseReleased(double x,double y,int button){if(overlay!=null)overlay.mouseReleased(x,y,button);}
    public void keyPressed(int key){
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE){requestClose();return;}
        if(overlay!=null)overlay.keyPressed(key);
    }
}
