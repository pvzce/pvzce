package com.pvzce.client.gui.workshop;

import com.google.gson.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.screens.EditorScreen;
import com.pvzce.common.workshop.*;
import java.nio.file.*;
import java.util.*;

/** The main-menu workshop and one workspace's unpublished source browser. */
public final class WorkshopScreen extends Screen {
    private final Workspace workspace;
    private String notice="";
    private int page;
    private String filter="";
    private List<Workspace> workspaces=List.of();
    private List<String> files=List.of();
    public WorkshopScreen(PvzceClient client) { this(client,null); }
    public WorkshopScreen(PvzceClient client, Workspace workspace) { super(client);this.workspace=workspace; }
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
            addWidget(new Button(16+(bw+gap)*3,height-64,bw,26,WorkshopUi.text("filter","筛选"),()->client.openScreen(new WorkshopListScreen(client,
                    WorkshopUi.text("type","内容类型"),WorkshopContent.types(),type->{filter="/"+type+"/";page=0;onResize();}))));
            try {files=workspace.files().stream().filter(p->filter.isEmpty()||p.contains(filter)).toList();}catch(Exception e){notice=e.getMessage();}
        }
        int count=Math.max(1,(height-146)/30);
        int total=workspace==null?workspaces.size():files.size();
        page=Math.min(page,Math.max(0,(total-1)/count));
        for(int i=page*count;i<Math.min(total,(page+1)*count);i++) {
            int index=i;
            String label=workspace==null?workspaces.get(i).name()+" · "+workspaces.get(i).namespace():files.get(i);
            addWidget(new Button(16,height-100-(i-page*count)*30,width-32,26,shorten(label,width-45),()->{
                if(workspace==null)client.openScreen(new WorkshopScreen(client,workspaces.get(index)));
                else attempt(()->open(files.get(index)));
            }));
        }
        addWidget(new Button(16,32,64,26,"←",()->{page=Math.max(0,page-1);onResize();}));
        addWidget(new Button(86,32,64,26,"→",()->{if((page+1)*count<total)page++;onResize();}));
        if(workspace!=null) {
            addWidget(new Button(156,32,86,26,WorkshopUi.text("all","全部"),()->{filter="";page=0;onResize();}));
            addWidget(new Button(248,32,70,26,WorkshopUi.text("rename","改名称"),()->WorkshopUi.ask(this,WorkshopUi.text("name","工作区名称"),workspace.name(),name->attempt(()->{
                Workspace updated=workspace.rename(name);client.popScreen();client.openScreen(new WorkshopScreen(client,updated));
            }))));
        }
        addWidget(new Button(width-96,32,80,26,WorkshopUi.text("back","返回"),this::requestClose));
    }
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
                client.openScreen(new WorkshopListScreen(client,title,choices,choice->attempt(()->{
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
    private long nextRefresh;
    public void tick(){
        if(System.nanoTime()<nextRefresh)return;nextRefresh=System.nanoTime()+500_000_000L;
        try {
            if(workspace==null){var current=Workspace.list(client.gameDir());if(!current.equals(workspaces))onResize();}
            else {var current=workspace.files().stream().filter(p->filter.isEmpty()||p.contains(filter)).toList();if(!current.equals(files))onResize();}
        }catch(Exception e){notice=e.getMessage();}
    }
    public void render(){
        renderBackground(0.08F,0.12F,0.1F);
        client.fonts().body().draw(shorten(workspace==null?WorkshopUi.text("title","创意工坊"):workspace.name()+" · "+workspace.namespace(),client.guiWidth()-30),16,client.guiHeight()-28,1.1F,1,1,0.8F,1);
        client.fonts().body().draw(shorten(notice.isEmpty()?WorkshopUi.text("draft_notice","草稿仅在构建或临时测试时加载"):notice,client.guiWidth()-30),16,10,0.8F,1,0.9F,0.6F,1);
        for(var widget:widgets)widget.render(client);
    }
}
