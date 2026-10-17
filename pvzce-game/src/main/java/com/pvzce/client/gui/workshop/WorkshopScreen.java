package com.pvzce.client.gui.workshop;

import com.google.gson.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.client.gui.screens.EditorScreen;
import com.pvzce.common.workshop.*;
import java.nio.file.*;
import java.util.*;

/** The main-menu workshop and one workspace's unpublished source browser. */
public final class WorkshopScreen extends Screen {
    private Workspace workspace;
    private final com.pvzce.client.gui.PackIcons icons;
    private String notice="";
    private int page;
    private int pages;
    private int matches;
    private String query="";
    private EditBox search;
    private final List<AbstractWidget> results=new ArrayList<>();
    private WorkshopPresentation presentation;
    private long workspaceStamp;
    private String filter="";
    private List<Workspace> workspaces=List.of();
    private List<String> files=List.of();
    public WorkshopScreen(PvzceClient client) { this(client,null); }
    public WorkshopScreen(PvzceClient client, Workspace workspace) { super(client);this.workspace=workspace;this.icons=new com.pvzce.client.gui.PackIcons(client); }
    public static void validateWorkspace(Workspace workspace) { WorkshopAssets.validate(workspace); }
    public static void editLevel(PvzceClient client,Identifier id,String name,int width,int height) {
        WorkshopScreen home=new WorkshopScreen(client);client.openScreen(home);
        home.attempt(()->{
            List<Workspace> workspaces=Workspace.list(client.gameDir());
            if(workspaces.isEmpty()) { home.notice=WorkshopUi.text("create_first","先创建工作区，再从工作区复制或创建关卡");return; }
            Map<String,Workspace> choices=new LinkedHashMap<>();for(Workspace workspace:workspaces)choices.put(workspace.name()+" · "+workspace.namespace(),workspace);
            client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("choose_workspace","选择关卡工作区"),choices.keySet(),choice->home.attempt(()->{
                Workspace workspace=choices.get(choice);
                Identifier target=Identifier.of(workspace.namespace(),id.path());
                String path=WorkshopContent.path(workspace,"levels",target.toString());
                if(!Files.exists(workspace.file(path))) {
                    JsonObject json=WorkshopContent.templates(client.resources(),"levels").get(id.toString());
                    json=json==null?WorkshopContent.blank("levels",target):json.deepCopy();json.addProperty("id",target.toString());
                    if(name!=null)json.addProperty("name",name);if(width>0)json.addProperty("width",width);if(height>0)json.addProperty("height",height);
                    workspace.write(path,json);
                }
                client.openScreen(new WorkshopScreen(client,workspace));
                JsonObject json=JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
                client.openScreen(new EditorScreen(client,workspace,target,workspace.file(path),json));
            })));
        });
    }

    public Workspace workspace() { return workspace; }
    private void attempt(Action action) { try {action.run();}catch(Exception e){notice=e.getMessage();client.openScreen(new WorkshopMessageScreen(client,notice));} onResize(); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    protected void init() {
        int width=client.guiWidth(),height=client.guiHeight(),gap=6,bw=(width-32-gap*3)/4;
        if(workspace==null) {
            addWidget(new Button(16,height-64,bw,26,WorkshopUi.text("create","创建工作区"),this::create));
            addWidget(new Button(16+bw+gap,height-64,bw,26,WorkshopUi.text("import_old","导入旧关卡"),this::importLegacy));
            attemptRead();
        } else {
            addWidget(new Button(16,height-64,bw,26,WorkshopUi.text("new","新建内容"),this::newContent));
            addWidget(new Button(16+bw+gap,height-64,bw,26,WorkshopUi.text("build","构建数据包"),()->attempt(()->{
                workspace.build(client.gameDir(),WorkshopAssets::validate);client.requestPackReload();notice=WorkshopUi.text("built","构建成功，正在重载");
            })));
            addWidget(new Button(16+(bw+gap)*2,height-64,bw,26,WorkshopUi.text("import","导入素材"),this::importAsset));
            addWidget(new Button(16+(bw+gap)*3,height-64,bw,26,(filter.isEmpty()?"筛选：全部":"筛选："+WorkshopUi.label(filter.substring(1,filter.length()-1))),()->client.openScreen(new WorkshopListScreen(client,
                    WorkshopUi.text("type","内容类型"),WorkshopContent.types(),type->{filter="/"+type+"/";page=0;onResize();}))));
            try {workspaceStamp=stamp();files=workspace.files();}catch(Exception e){notice=e.getMessage();}
        }
        icons.refresh();
        if(presentation==null)presentation=new WorkshopPresentation(client,workspace);
        else presentation.refresh();
        search=new EditBox(62,height-100,width-150,27,256,null);
        search.setValue(query,false);addWidget(search);
        search.setValueChangedListener(()->{query=search.value();page=0;updateResults();});
        addWidget(new Button(width-80,height-100,64,27,"清空",()->{search.setValue("");search.setFocused(true);}));
        updateResults();
    }
    private void result(AbstractWidget widget){results.add(widget);addWidget(widget);}
    private void updateResults(){
        widgets.removeAll(results);results.clear();
        int width=client.guiWidth(),height=client.guiHeight();
        String needle=query.strip().toLowerCase(Locale.ROOT);
        var shownWorkspaces=workspaces.stream().filter(w->(w.name()+" "+w.namespace()).toLowerCase(Locale.ROOT).contains(needle)).toList();
        var shownFiles=files.stream().filter(p->(filter.isEmpty()||p.contains(filter))
                &&(p+" "+presentation.item(p,"").name()).toLowerCase(Locale.ROOT).contains(needle)).toList();
        int columns=workspace==null?1:Math.max(1,(width-32)/170);
        int rows=Math.max(1,(height-184)/145);
        int count=workspace==null?Math.max(1,(height-180)/52):columns*rows;
        int cardWidth=(width-32-(columns-1)*8)/columns;
        int cardHeight=Math.max(52,Math.min(152,(height-184)/rows-8));
        matches=workspace==null?shownWorkspaces.size():shownFiles.size();
        pages=Math.max(1,(matches+count-1)/count);page=Math.min(page,pages-1);
        for(int i=page*count;i<Math.min(matches,(page+1)*count);i++) {
            int slot=i-page*count;
            if(workspace==null){
                Workspace item=shownWorkspaces.get(i);
                result(new Button(16,height-164-slot*52,width-32,46,item.name()+" · "+item.namespace(),()->client.openScreen(new WorkshopScreen(client,item))){
                    public void render(PvzceClient client){
                        client.drawSolid(x,y,this.width,this.height,0,hovered?.22F:.12F,.24F,.18F,1);
                        icons.draw(item.root(),x+6,y+5,36);
                        WorkshopUi.fit(client,item.name()+" · "+item.namespace(),x+52,y+16,this.width-62,.95F,1,1,.8F);
                    }
                });
            } else {
                String file=shownFiles.get(i);
                result(presentation.card(presentation.item(file,""),16+(slot%columns)*(cardWidth+8),
                        height-116-cardHeight-(slot/columns)*(cardHeight+8),cardWidth,cardHeight,()->attempt(()->open(file))));
            }
        }
        var previous=new Button(16,32,64,26,"上一页",()->changePage(-1));previous.setActive(page>0);result(previous);
        var next=new Button(86,32,64,26,"下一页",()->changePage(1));next.setActive(page+1<pages);result(next);
        if(workspace!=null) {
            var all=new Button(156,32,86,26,WorkshopUi.text("all","全部"),()->{filter="";query="";search.setValue("",false);page=0;onResize();});
            all.setActive(!filter.isEmpty()||!query.isEmpty());result(all);
            result(new Button(248,32,70,26,"设置",()->client.openScreen(new WorkshopPackScreen(client,workspace,updated->{workspace=updated;onResize();}))));
        }
        result(new Button(width-96,32,80,26,WorkshopUi.text("back","返回"),this::requestClose));
    }
    private void changePage(int delta){int next=Math.max(0,Math.min(pages-1,page+delta));if(next!=page){page=next;updateResults();}}
    protected ScrollRegion onScrollRegionAt(double x,double y){
        return pages>1&&x>=16&&x<client.guiWidth()-16&&y>=72&&y<client.guiHeight()-112
                ?ScrollRegion.dragsContent(ScrollRegion.Axis.VERTICAL,100):null;
    }
    protected void onMouseScrolled(double x,double y,double amount){if(onScrollRegionAt(x,y)!=null&&amount!=0)changePage(amount<0?1:-1);}
    public void onResize(){boolean focused=search!=null&&search.isFocused();super.onResize();if(focused)search.setFocused(true);}
    private void attemptRead(){try{workspaces=Workspace.list(client.gameDir());}catch(Exception e){notice=e.getMessage();}}
    private void create() {
        WorkshopUi.ask(this,WorkshopUi.text("name","工作区名称"),"",name->WorkshopUi.ask(this,WorkshopUi.text("namespace","工作区命名空间（小写英文）"),"",ns->attempt(()->{
            Workspace created=Workspace.create(client.gameDir(),name,ns);client.openScreen(new WorkshopScreen(client,created));
        })));
    }
    private void importLegacy() {
        WorkshopUi.ask(this,WorkshopUi.text("legacy_namespace","导入工作区的命名空间"),"user_levels",ns->attempt(()->{
            Path source=client.gameDir().resolve("datapacks/user_levels");
            if(!Files.isDirectory(source))throw new IllegalArgumentException("没有找到旧的 user_levels 数据包");
            Workspace imported=Workspace.create(client.gameDir(),"User levels",ns);
            Workspace.copyPack(source,imported.root());
            notice=WorkshopUi.text("imported_legacy","已复制到工作区；原数据包保持原状，可在数据包页停用");
            client.openScreen(new WorkshopScreen(client,imported));
        }));
    }
    private void newContent() {
        client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("type","内容类型"),WorkshopContent.types(),type->{
            if(type.equals("textures")||type.equals("sounds")){importAsset(type);return;}
            WorkshopUi.ask(this,WorkshopUi.text("content_id","内容路径（标签填写 plant/名称 等）"),"",id->attempt(()->{
                String path=WorkshopContent.path(workspace,type,id);
                if(Files.exists(workspace.file(path)))throw new IllegalArgumentException("内容已存在");
                Map<String,JsonObject> templates=WorkshopContent.templates(client.resources(),type);
                List<String> choices=new ArrayList<>();choices.add(WorkshopUi.text("blank","空白创建"));choices.addAll(templates.keySet());
                String keyText=id.contains(":")?id:workspace.namespace()+":"+id;
                String title=templates.containsKey(keyText)?WorkshopUi.text("override","将覆盖已有内容：")+keyText
                        :!Identifier.parse(keyText).namespace().equals(workspace.namespace())?WorkshopUi.text("foreign_namespace","使用其它命名空间：")+keyText:WorkshopUi.text("template","选择模板");
                client.openScreen(new WorkshopListScreen(client,title,choices,workspace,type,choice->attempt(()->{
                    Identifier key=Identifier.parse(id.contains(":")?id:workspace.namespace()+":"+id);
                    JsonObject json=templates.containsKey(choice)?templates.get(choice).deepCopy():WorkshopContent.blank(type,key);
                    if(templates.containsKey(choice)&&(type.equals("plants")||type.equals("zombies")||type.equals("projectiles")||type.equals("tools"))) {
                        Identifier original=Identifier.parse(choice);
                        String kind=type.equals("plants")?"plant":type.equals("zombies")?"zombie":type.equals("projectiles")?"projectile":"tool";
                        var bindings=com.pvzce.common.core.EntityArt.bindings(original,kind);
                        var animation=bindings==null?null:bindings.resolve("idle").orElseGet(()->bindings.fileId(original));
                        if(animation!=null)json.addProperty("animation",animation.toString());
                        json.addProperty("texture",com.pvzce.common.core.EntityArt.sprite(original,kind).toString());
                    }
                    if(json.has("id"))json.addProperty("id",key.toString());
                    if(type.equals("plants"))copyPlantTags(templates.containsKey(choice)?Identifier.parse(choice):null,key);
                    workspace.write(path,json);
                    if(type.equals("tools")||type.equals("zombies")) {
                        JsonObject slot=new JsonObject();slot.addProperty("id",key.toString());slot.addProperty("content",key.toString());
                        slot.addProperty("kind",type.equals("tools")?"tool":"zombie");
                        String slotPath="data/"+key.namespace()+"/slots/"+key.path()+".json";
                        if(!Files.exists(workspace.file(slotPath)))workspace.write(slotPath,slot);
                    }
                    if(!List.of("tags","animations","lang").contains(type)) {
                        WorkshopUi.ask(this,WorkshopUi.text("display_name","显示名称"),key.path(),name->attempt(()->{
                            String language="assets/"+key.namespace()+"/lang/"+com.pvzce.client.gui.GuiLang.locale()+".json";
                            JsonObject translations=Files.exists(workspace.file(language))?JsonParser.parseString(Files.readString(workspace.file(language))).getAsJsonObject():new JsonObject();
                            String category=com.pvzce.common.resource.PvzceDataLoader.CONTENT_REGISTRIES.stream().filter(d->d.contentPath().equals(type)).findFirst().orElseThrow().idPath();
                            translations.addProperty(com.pvzce.common.core.RegistryCategories.key(category,key),name);
                            workspace.write(language,translations);open(path);
                        }));
                    }else open(path);
                })));
            }));
        }));
    }
    private void copyPlantTags(Identifier original,Identifier created)throws Exception {
        for(var tag:com.pvzce.common.tag.PvzceTags.MANAGER.keys()) {
            if(!tag.registry().equals(com.pvzce.common.core.PvzceRegistries.PLANTS))continue;
            if(original==null? !tag.id().toString().equals("c:requires_ground") : !com.pvzce.common.tag.PvzceTags.MANAGER.contains(tag,original))continue;
            String path="data/"+tag.id().namespace()+"/tags/plant/"+tag.id().path()+".json";
            JsonObject json=Files.exists(workspace.file(path))?JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject():new JsonObject();
            JsonArray values=json.has("values")?json.getAsJsonArray("values"):new JsonArray();
            if(!values.contains(new JsonPrimitive(created.toString())))values.add(created.toString());
            json.add("values",values);workspace.write(path,json);
        }
    }

    private void importAsset(){client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("asset_type","素材类型"),List.of("textures","sounds","animations"),this::importAsset));}
    private void importAsset(String type){WorkshopAssets.chooseFile(this,type,source->WorkshopUi.ask(this,WorkshopUi.text("asset_id","素材路径 / ID"),source.getFileName().toString().replaceFirst("\\.[^.]+$",""),id->attempt(()->{
        String path=WorkshopContent.path(workspace,type,id);WorkshopAssets.importFile(workspace,path,source);open(path);
    })));}
    private void open(String path)throws Exception {
        if(path.startsWith("data/")&&path.contains("/levels/")&&path.endsWith(".json")) {
            String[] parts=path.split("/",4);JsonObject json=JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
            Identifier id=json.has("id")?Identifier.parse(json.get("id").getAsString()):Identifier.parse(parts[1]+":"+parts[3].substring(0,parts[3].length()-5));
            client.openScreen(new EditorScreen(client,workspace,id,workspace.file(path),json));
        }else if(path.endsWith(".json"))client.openScreen(new WorkshopJsonScreen(client,workspace,path));
        else client.openScreen(new WorkshopPreviewScreen(client,workspace,path));
    }
    static String shorten(String value,int width){return value.length()>Math.max(15,width/8)?value.substring(0,Math.max(12,width/8)-1)+"…":value;}
    private long stamp()throws java.io.IOException{
        var icon=workspace.file(com.pvzce.common.resource.PvzcePack.ICON_FILE);long value=Files.isRegularFile(icon)?java.util.Objects.hash(Files.size(icon),Files.getLastModifiedTime(icon)):1;for(String path:workspace.files()){var file=workspace.file(path);value=31*value+java.util.Objects.hash(path,Files.size(file),Files.getLastModifiedTime(file));}return value;
    }
    private long nextRefresh;
    public void tick(){
        if(System.nanoTime()<nextRefresh)return;nextRefresh=System.nanoTime()+500_000_000L;
        try {
            if(workspace==null){var current=Workspace.list(client.gameDir());if(!current.equals(workspaces))onResize();}
            else {var current=workspace.files();if(!current.equals(files)||stamp()!=workspaceStamp)onResize();}
        }catch(Exception e){notice=e.getMessage();}
    }
    protected void onHidden(){if(presentation!=null)presentation.stopAudition();}
    protected void onRemoved(){if(presentation!=null)presentation.close();icons.close();}
    public void render(){
        renderBackground(0.08F,0.12F,0.1F);WorkshopUi.backdrop(client);
        if(workspace!=null)icons.draw(workspace.root(),16,client.guiHeight()-38,28);
        client.fonts().body().draw(shorten(workspace==null?WorkshopUi.text("title","创意工坊"):workspace.name()+" · "+workspace.namespace(),client.guiWidth()-64),workspace==null?16:52,client.guiHeight()-28,1.1F,1,1,0.8F,1);
        WorkshopUi.fit(client,"搜索",16,client.guiHeight()-92,44,.8F,.8F,.9F,.8F);
        WorkshopUi.fit(client,(page+1)+" / "+pages+" 页 · "+matches+" 项",16,65,client.guiWidth()-32,.72F,.8F,.9F,.8F);
        if(matches==0)WorkshopUi.fit(client,query.isEmpty()&&filter.isEmpty()?"从上方创建内容或导入素材开始":"没有匹配内容，可清空搜索或切回全部",20,client.guiHeight()/2,client.guiWidth()-40,.8F,1,1,.8F);
        client.fonts().body().draw(shorten(notice.isEmpty()?WorkshopUi.text("draft_notice","草稿仅在构建或临时测试时加载"):notice,client.guiWidth()-30),16,10,0.8F,1,0.9F,0.6F,1);
        for(var widget:widgets)widget.render(client);
    }
}
