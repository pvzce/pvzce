package com.pvzce.client.gui.workshop;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import java.util.*;
import java.util.function.Consumer;

/** A small, paged picker shared by content kinds, templates, capabilities and references. */
public final class WorkshopListScreen extends Screen {
    private final String title;
    private final List<String> entries;
    private final Consumer<String> choose;
    private int page;
    private WorkshopPresentation presentation;
    private final com.pvzce.common.workshop.Workspace workspace;
    private final String type;
    private String query="";
    private boolean searching;
    public WorkshopListScreen(PvzceClient client, String title, Collection<String> entries, Consumer<String> choose) {
        this(client, title, entries, null, "", choose);
    }
    public WorkshopListScreen(PvzceClient client, String title, Collection<String> entries,
                              com.pvzce.common.workshop.Workspace workspace, String type, Consumer<String> choose) {
        super(client); this.title=title; this.entries=List.copyOf(entries); this.choose=choose;
        this.workspace=workspace; this.type=type;
    }
    protected void init() {
        if(presentation==null)presentation=new WorkshopPresentation(client,workspace);
        var search=new com.pvzce.client.gui.components.EditBox(client.guiWidth()/2,client.guiHeight()-34,client.guiWidth()/2-16,25,256,null);
        search.setValue(query,false); addWidget(search);
        search.setValueChangedListener(()->{query=search.value();page=0;searching=true;onResize();});
        if(searching)search.setFocused(true);
        List<String> filtered=entries.stream().filter(e->(e+" "+presentation.item(e,type).name()).toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))).toList();
        int columns=Math.max(1,(client.guiWidth()-24)/160), rows=Math.max(1,(client.guiHeight()-104)/140);
        int count=columns*rows, width=(client.guiWidth()-32-(columns-1)*8)/columns;
        int cardHeight=Math.max(76,Math.min(152,(client.guiHeight()-104)/rows-8));
        page=Math.min(page,Math.max(0,(filtered.size()-1)/count));
        for(int i=page*count;i<Math.min(filtered.size(),(page+1)*count);i++) {
            String item=filtered.get(i); int slot=i-page*count;
            addWidget(presentation.card(presentation.item(item,type),16+(slot%columns)*(width+8),
                    client.guiHeight()-66-cardHeight-(slot/columns)*(cardHeight+8),width,cardHeight,()->{
                client.popScreen(); choose.accept(item);
            }));
        }
        addWidget(new Button(16,12,80,26,"←",()->{ page=Math.max(0,page-1);onResize(); }));
        addWidget(new Button(104,12,80,26,"→",()->{ if((page+1)*count<filtered.size())page++;onResize(); }));
        addWidget(new Button(client.guiWidth()-116,12,100,26,WorkshopUi.text("back","返回"),this::requestClose));
    }
    protected void onRemoved(){if(presentation!=null)presentation.close();}
    public void render() {
        renderBackground(0.08F,0.12F,0.1F);WorkshopUi.backdrop(client);
        client.fonts().body().draw(WorkshopScreen.shorten(title,client.guiWidth()/2-24),16,client.guiHeight()-28,1,1,1,0.8F,1);
        if(entries.isEmpty())WorkshopUi.fit(client,"暂无内容",20,client.guiHeight()/2,client.guiWidth()-40,1,1,1,1);
        for(var widget:widgets)widget.render(client);
    }
}
