package com.pvzce.client.gui.workshop;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import com.pvzce.client.input.ScrollRegion;
import java.util.*;
import java.util.function.Consumer;

/** Searchable content picker; filtering only rebuilds the result area. */
public final class WorkshopListScreen extends Screen {
    private final String title;
    private final List<String> entries;
    private final Consumer<String> choose;
    private int page;
    private int pages;
    private int matches;
    private WorkshopPresentation presentation;
    private final com.pvzce.common.workshop.Workspace workspace;
    private final String type;
    private String query="";
    private EditBox search;
    private final List<AbstractWidget> results=new ArrayList<>();
    public WorkshopListScreen(PvzceClient client, String title, Collection<String> entries, Consumer<String> choose) {
        this(client, title, entries, null, "", choose);
    }
    public WorkshopListScreen(PvzceClient client, String title, Collection<String> entries,
                              com.pvzce.common.workshop.Workspace workspace, String type, Consumer<String> choose) {
        super(client); this.title=title; this.entries=List.copyOf(entries); this.choose=choose;
        this.workspace=workspace; this.type=type;
    }
    protected void init() {
        boolean first=search==null;
        if(presentation==null)presentation=new WorkshopPresentation(client,workspace);
        search=new EditBox(62,client.guiHeight()-66,client.guiWidth()-150,27,256,this::chooseOnlyResult).style(EditBox.Style.PARCHMENT);
        search.setValue(query,false);addWidget(search);
        search.setValueChangedListener(()->{query=search.value();page=0;updateResults();});
        addWidget(new Button(client.guiWidth()-80,client.guiHeight()-66,64,27,"清空",()->{
            search.setValue("");search.setFocused(true);
        }).style(Button.Style.SEED_CHOOSER));
        updateResults();
        if(first)search.setFocused(true);
    }
    private List<String> filtered(){
        String needle=query.strip().toLowerCase(Locale.ROOT);
        return entries.stream().filter(e->(e+" "+presentation.item(e,type).name()).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
    private void chooseOnlyResult(){
        var filtered=filtered();
        if(filtered.size()==1)select(filtered.get(0));
    }
    private void select(String item){client.popScreen();choose.accept(item);}
    private void result(AbstractWidget widget){results.add(widget);addWidget(widget);}
    private void updateResults(){
        widgets.removeAll(results);results.clear();
        var filtered=filtered();matches=filtered.size();
        int columns=Math.max(1,(client.guiWidth()-24)/160),rows=Math.max(1,(client.guiHeight()-144)/140);
        int count=columns*rows,width=(client.guiWidth()-32-(columns-1)*8)/columns;
        int cardHeight=Math.max(68,Math.min(152,(client.guiHeight()-144)/rows-8));
        pages=Math.max(1,(matches+count-1)/count);page=Math.min(page,pages-1);
        for(int i=page*count;i<Math.min(matches,(page+1)*count);i++){
            String item=filtered.get(i);int slot=i-page*count;
            result(presentation.card(presentation.item(item,type),16+(slot%columns)*(width+8),
                    client.guiHeight()-80-cardHeight-(slot/columns)*(cardHeight+8),width,cardHeight,()->select(item)));
        }
        var previous=new Button(16,12,80,26,"上一页",()->changePage(-1)).style(Button.Style.SEED_CHOOSER);previous.setActive(page>0);result(previous);
        var next=new Button(104,12,80,26,"下一页",()->changePage(1)).style(Button.Style.SEED_CHOOSER);next.setActive(page+1<pages);result(next);
        result(new Button(client.guiWidth()-116,12,100,26,WorkshopUi.text("back","返回"),this::requestClose).style(Button.Style.SEED_CHOOSER));
    }
    private void changePage(int delta){
        int next=Math.max(0,Math.min(pages-1,page+delta));
        if(next!=page){page=next;updateResults();}
    }
    protected ScrollRegion onScrollRegionAt(double x,double y){
        return pages>1&&x>=16&&x<client.guiWidth()-16&&y>=48&&y<client.guiHeight()-76
                ?ScrollRegion.dragsContent(ScrollRegion.Axis.VERTICAL,100):null;
    }
    protected void onMouseScrolled(double x,double y,double amount){
        if(onScrollRegionAt(x,y)!=null&&amount!=0)changePage(amount<0?1:-1);
    }
    public void onResize(){boolean focused=search!=null&&search.isFocused();super.onResize();if(focused)search.setFocused(true);}
    protected void onHidden(){if(presentation!=null)presentation.stopAudition();}
    protected void onRemoved(){if(presentation!=null)presentation.close();}
    public void render() {
        WorkshopUi.backdrop(client, this);
        WorkshopUi.fit(client,title,16,client.guiHeight()-28,client.guiWidth()-32,1,1,1,.8F);
        WorkshopUi.fit(client,"搜索",16,client.guiHeight()-58,44,.8F,.36F,.23F,.10F);
        WorkshopUi.fit(client,(page+1)+" / "+pages+" 页 · "+matches+" 项",196,20,client.guiWidth()-324,.7F,.36F,.23F,.10F);
        if(matches==0)WorkshopUi.fit(client,entries.isEmpty()?"暂无内容":"没有匹配内容，请修改搜索或清空",20,client.guiHeight()/2,client.guiWidth()-40,.9F,1,1,.8F);
        for(var widget:widgets)widget.render(client);
    }
}
