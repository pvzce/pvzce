package com.pvzce.client.gui.workshop;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import java.util.*;
/** Build/test diagnostics remain readable, including long paths and all validation issues. */
public final class WorkshopMessageScreen extends Screen {
    private final String message;
    private int page;
    public WorkshopMessageScreen(PvzceClient client,String message){super(client);this.message=Objects.toString(message,"");}
    protected void init(){
        addWidget(new Button(16,16,70,28,"←",()->{page=Math.max(0,page-1);}).style(Button.Style.SEED_CHOOSER));
        addWidget(new Button(94,16,70,28,"→",()->{page++;}).style(Button.Style.SEED_CHOOSER));
        addWidget(new Button(client.guiWidth()-116,16,100,28,WorkshopUi.text("back","返回"),this::requestClose).style(Button.Style.SEED_CHOOSER));
    }
    public void render(){WorkshopUi.backdrop(client, this);
        List<String> lines=new ArrayList<>();StringBuilder current=new StringBuilder();
        for(char c:message.toCharArray()){
            if(c=='\n'||client.fonts().body().width(current.toString()+c,0.9F)>client.guiWidth()-40){lines.add(current.toString());current.setLength(0);if(c=='\n')continue;}current.append(c);
        }lines.add(current.toString());int count=Math.max(1,(client.guiHeight()-90)/22);page=Math.min(page,Math.max(0,(lines.size()-1)/count));
        for(int i=page*count;i<Math.min(lines.size(),(page+1)*count);i++)client.fonts().body().draw(lines.get(i),20,client.guiHeight()-38-(i-page*count)*22,0.9F,1,0.9F,0.65F,1);
        for(var widget:widgets)widget.render(client);
    }
}
