package com.pvzce.client.gui.workshop;

import com.google.gson.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.common.workshop.*;
import java.nio.file.*;
import java.util.*;

/** A lossless, recursive property editor; JSON remains an optional advanced view. */
public final class WorkshopJsonScreen extends Screen {
    private final Workspace workspace;
    private final String file;
    private JsonObject root;
    private final List<String> location=new ArrayList<>();
    private int page;
    private String notice="";
    public WorkshopJsonScreen(PvzceClient client,Workspace workspace,String file)throws java.io.IOException {
        super(client);this.workspace=workspace;this.file=file;
        root=JsonParser.parseString(Files.readString(workspace.file(file))).getAsJsonObject();
    }
    private JsonElement node(){JsonElement value=root;for(String key:location)value=value.isJsonArray()?value.getAsJsonArray().get(Integer.parseInt(key)):value.getAsJsonObject().get(key);return value;}
    private String currentKey(){return location.isEmpty()?"":location.get(location.size()-1);}
    private void attempt(Runnable action){try{action.run();}catch(Exception e){notice=e.getMessage();}onResize();}
    protected void init(){
        int width=client.guiWidth(),height=client.guiHeight(),bw=(width-50)/5;
        addWidget(new Button(12,height-62,bw,26,WorkshopUi.text("save","保存草稿"),()->save()));
        addWidget(new Button(18+bw,height-62,bw,26,WorkshopUi.text("add","添加"),()->attempt(this::add)));
        addWidget(new Button(24+bw*2,height-62,bw,26,"JSON",()->client.openScreen(new WorkshopRawScreen(client,root,changed->{root=changed;location.clear();onResize();}))));
        if(file.contains("/animations/"))addWidget(new Button(30+bw*3,height-62,bw,26,WorkshopUi.text("preview","预览"),()->{if(save())client.openScreen(new WorkshopPreviewScreen(client,workspace,file));}));
        else addWidget(new Button(30+bw*3,height-62,bw,26,WorkshopUi.text("validate","校验"),()->attempt(()->{if(save()){WorkshopAssets.validate(workspace);notice=WorkshopUi.text("valid","校验通过");}})));
        addWidget(new Button(36+bw*4,height-62,bw,26,WorkshopUi.text("parent","上一级"),()->{if(!location.isEmpty()){location.remove(location.size()-1);page=0;onResize();}}));
        JsonElement node=node();List<String> keys=node.isJsonObject()?new ArrayList<>(node.getAsJsonObject().keySet()):new ArrayList<>();
        if(node.isJsonArray())for(int i=0;i<node.getAsJsonArray().size();i++)keys.add(Integer.toString(i));
        int count=Math.max(1,(height-142)/30);page=Math.min(page,Math.max(0,(keys.size()-1)/count));
        for(int i=page*count;i<Math.min(keys.size(),(page+1)*count);i++){
            String key=keys.get(i);JsonElement value=get(node,key);int y=height-98-(i-page*count)*30;
            String label=WorkshopUi.label(key)+" : "+(value.isJsonObject()?"{…}":value.isJsonArray()?"["+value.getAsJsonArray().size()+"]":value.toString());
            addWidget(new Button(12,y,width-122,26,WorkshopScreen.shorten(label,width-135),()->edit(node,key,value)));
            addWidget(new Button(width-104,y,40,26,"×",()->{remove(node,key);onResize();}));
            if(node.isJsonArray())addWidget(new Button(width-58,y,44,26,"↑",()->{
                int index=Integer.parseInt(key);if(index>0){JsonArray array=node.getAsJsonArray();JsonElement previous=array.get(index-1);array.set(index-1,array.get(index));array.set(index,previous);}onResize();
            }));
        }
        addWidget(new Button(12,32,60,26,"←",()->{page=Math.max(0,page-1);onResize();}));
        addWidget(new Button(78,32,60,26,"→",()->{if((page+1)*count<keys.size())page++;onResize();}));
        addWidget(new Button(width-112,32,100,26,WorkshopUi.text("save_back","保存并返回"),()->{if(save())client.popScreen();}));
    }
    private static JsonElement get(JsonElement node,String key){return node.isJsonObject()?node.getAsJsonObject().get(key):node.getAsJsonArray().get(Integer.parseInt(key));}
    private static void put(JsonElement node,String key,JsonElement value){if(node.isJsonObject())node.getAsJsonObject().add(key,value);else node.getAsJsonArray().set(Integer.parseInt(key),value);}
    private static void remove(JsonElement node,String key){if(node.isJsonObject())node.getAsJsonObject().remove(key);else node.getAsJsonArray().remove(Integer.parseInt(key));}
    private void edit(JsonElement node,String key,JsonElement value){
        if(currentKey().equals("animation_clips") && value.isJsonPrimitive()) {
            String selected=root.has("animations") && root.getAsJsonObject("animations").has(key)?root.getAsJsonObject("animations").get(key).getAsString():root.has("animation")?root.get("animation").getAsString():null;
            if(selected!=null) {
                try {
                    String path="assets/"+Identifier.parse(selected).namespace()+"/animations/"+Identifier.parse(selected).path()+".json";
                    JsonObject animationJson=java.nio.file.Files.isRegularFile(workspace.file(path))?JsonParser.parseString(java.nio.file.Files.readString(workspace.file(path))).getAsJsonObject():JsonParser.parseString(client.resources().getAsset(Identifier.of(Identifier.parse(selected).namespace(),"animations/"+Identifier.parse(selected).path()+".json")).orElseThrow().readString()).getAsJsonObject();
                    var parsed=com.pvzce.client.animation.AnimationResourceLoader.parse(animationJson,Identifier.parse(selected));
                    client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("clip_binding","选择对应的动画片段"),parsed.clipNames(),clip->{put(node,key,new JsonPrimitive(clip));onResize();}));return;
                }catch(Exception e){notice=e.getMessage();}
            }
        }
        boolean animation=key.equals("animation") || (!file.contains("/animations/") && currentKey().equals("animations"));
        if(value.isJsonPrimitive() && (animation || key.equals("texture") || key.equals("icon"))) {
            String type=animation?"animations":"textures";Map<String,String> choices=new TreeMap<>();
            try {
                for(String path:client.resources().listResources("assets").keySet())if(path.contains("/"+type+"/")&&path.endsWith(animation?".json":".png"))choices.put(referenceId(path,animation),path);
                for(String path:workspace.files())if(path.contains("/"+type+"/")&&path.endsWith(animation?".json":".png"))choices.put(referenceId(path,animation),path);
            }catch(Exception e){notice=e.getMessage();}
            client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("binding","选择素材绑定"),choices.keySet(),id->{put(node,key,new JsonPrimitive(id));onResize();}));
            return;
        }
        if(value.isJsonObject()||value.isJsonArray()){location.add(key);page=0;onResize();return;}
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()){put(node,key,new JsonPrimitive(!value.getAsBoolean()));onResize();return;}
        WorkshopUi.ask(this,WorkshopUi.label(key),value.isJsonNull()?"":value.getAsString(),text->attempt(()->{
            JsonElement replacement=value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()?JsonParser.parseString(text):new JsonPrimitive(text);
            if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()&&(!replacement.isJsonPrimitive()||!replacement.getAsJsonPrimitive().isNumber()))throw new IllegalArgumentException("请输入数字");
            put(node,key,replacement);
        }));
    }
    private void add(){
        JsonElement node=node();
        if(node.isJsonArray()) {
            if(currentKey().equals("capabilities")){addCapability(node.getAsJsonArray());return;}
            if(currentKey().equals("frames")){
                List<String> textures=new ArrayList<>();
                try {for(String path:client.resources().listResources("assets").keySet())if(path.contains("/textures/")&&path.endsWith(".png"))textures.add(assetId(path));
                    for(String path:workspace.files())if(path.contains("/textures/")&&path.endsWith(".png"))textures.add(assetId(path));
                }catch(Exception e){throw new IllegalArgumentException(e);}
                client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("frame","添加图片帧（时长单位：秒）"),new TreeSet<>(textures),id->{node.getAsJsonArray().add(id);onResize();}));return;
            }
            JsonArray array=node.getAsJsonArray();
            if(!array.isEmpty()&&(array.get(array.size()-1).isJsonObject()||array.get(array.size()-1).isJsonArray())){
                array.add(array.get(array.size()-1).deepCopy());onResize();return;
            }
            WorkshopUi.ask(this,WorkshopUi.text("value","新成员（ID、标签或 JSON）"),"",value->attempt(()->{
                array.add(value.startsWith("{")||value.startsWith("[")||value.matches("-?[0-9]+(\\.[0-9]+)?")?JsonParser.parseString(value):new JsonPrimitive(value));
            }));return;
        }
        Map<String,JsonElement> defaults=defaults();defaults.keySet().removeAll(node.getAsJsonObject().keySet());
        List<String> choices=new ArrayList<>(defaults.keySet());choices.add(WorkshopUi.text("custom_field","自定义字段"));
        client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("field","添加属性"),choices,key->{
            if(defaults.containsKey(key)){node.getAsJsonObject().add(key,defaults.get(key).deepCopy());onResize();}
            else WorkshopUi.ask(this,WorkshopUi.text("field_name","字段名 / 动画动作名"),"",name->attempt(()->{
                if(name.isBlank()||node.getAsJsonObject().has(name))throw new IllegalArgumentException("字段名为空或已存在");
                if(currentKey().equals("animations")&&file.contains("/animations/")){
                    JsonObject clip=new JsonObject();clip.add("frames",new JsonArray());clip.addProperty("delay",0.1F);clip.addProperty("loop",true);node.getAsJsonObject().add(name,clip);
                }else WorkshopUi.ask(this,WorkshopUi.text("field_value","JSON 值（字符串需要双引号）"),"\"\"",value->attempt(()->node.getAsJsonObject().add(name,JsonParser.parseString(value))));
            }));
        }));
    }
    private Map<String,JsonElement> defaults(){
        Map<String,JsonElement> result=new LinkedHashMap<>();
        String[] parts=file.split("/",4);
        try {
            List<JsonObject> templates=new ArrayList<>(WorkshopContent.templates(client.resources(),parts[2]).values());
            if(location.contains("capabilities"))for(String kind:List.of("plants","zombies"))templates.addAll(WorkshopContent.templates(client.resources(),kind).values());
            for(JsonObject template:templates){
                JsonElement cursor=template;
                for(String key:location){
                    if(cursor==null)break;
                    if(cursor.isJsonObject())cursor=cursor.getAsJsonObject().get(key);
                    else if(cursor.isJsonArray()&&!cursor.getAsJsonArray().isEmpty()) {
                        JsonElement desired=node();
                        if(desired.isJsonObject()&&desired.getAsJsonObject().has("type")) {
                            String type=desired.getAsJsonObject().get("type").getAsString();
                            cursor=java.util.stream.StreamSupport.stream(cursor.getAsJsonArray().spliterator(),false)
                                    .filter(e->e.isJsonObject()&&e.getAsJsonObject().has("type")&&e.getAsJsonObject().get("type").getAsString().equals(type)).findFirst().orElse(null);
                        }else cursor=cursor.getAsJsonArray().get(0);
                    }else cursor=null;
                }
                if(cursor!=null&&cursor.isJsonObject())for(var entry:cursor.getAsJsonObject().entrySet())result.putIfAbsent(entry.getKey(),entry.getValue());
            }
        }catch(Exception e){notice=e.getMessage();}
        if(location.isEmpty()&&(parts[2].equals("plants")||parts[2].equals("zombies"))){
            result.putIfAbsent("health",new JsonPrimitive(300));result.putIfAbsent("capabilities",new JsonArray());
            result.putIfAbsent("texture",new JsonPrimitive("pvzce:textures/mission"));result.putIfAbsent("animation",new JsonPrimitive("pvzce:plant/attacker/pea_shooter"));
            result.putIfAbsent("animations",new JsonObject());result.putIfAbsent("animation_clips",new JsonObject());result.putIfAbsent("sounds",new JsonObject());result.putIfAbsent("render_scale",new JsonPrimitive(1));
        }
        if(!file.contains("/animations/") && (currentKey().equals("animations") || currentKey().equals("animation_clips"))) {
            String animation=root.has("animation")?root.get("animation").getAsString():"pvzce:plant/attacker/pea_shooter";
            for(String state:List.of("idle","shoot","walk","eat","produce","grow","sleep","death","death_burned","arm_raise","explode"))result.putIfAbsent(state,new JsonPrimitive(currentKey().equals("animation_clips")?state:animation));
        }
        if(file.contains("/animations/")){
            if(location.isEmpty()){JsonArray size=new JsonArray();size.add(1);size.add(1);result.put("size",size);JsonArray anchor=new JsonArray();anchor.add(0.5);anchor.add(0);result.put("anchor",anchor);}
            else if(location.size()==2&&location.get(0).equals("animations")){
                result.put("frames",new JsonArray());result.put("delays",new JsonArray());result.put("delay",new JsonPrimitive(0.1));result.put("loop",new JsonPrimitive(true));result.put("on_end",new JsonPrimitive("idle"));result.put("rate",new JsonPrimitive(1));
            }
        }
        return result;
    }
    private void addCapability(JsonArray destination){
        Map<String,JsonObject> options=new TreeMap<>();
        try{for(String kind:List.of("plants","zombies"))for(JsonObject template:WorkshopContent.templates(client.resources(),kind).values()){
            JsonArray caps=template.getAsJsonArray("capabilities");if(caps!=null)for(JsonElement cap:caps)options.putIfAbsent(cap.getAsJsonObject().get("type").getAsString(),cap.getAsJsonObject());
        }}catch(Exception e){throw new IllegalArgumentException(e);}
        boolean plant=file.contains("/plants/");
        var registry=plant?com.pvzce.common.core.BuiltInRegistries.PLANT_CAPABILITIES:com.pvzce.common.core.BuiltInRegistries.ZOMBIE_CAPABILITIES;
        options.keySet().removeIf(id->!registry.containsKey(Identifier.parse(id)));
        for(Identifier id:registry.keySet())if(!options.containsKey(id.toString())){JsonObject cap=new JsonObject();cap.addProperty("type",id.toString());options.put(id.toString(),cap);}
        client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("capability","添加能力"),options.keySet(),id->{destination.add(options.get(id).deepCopy());onResize();}));
    }
    private static String referenceId(String path,boolean animation){
        String id=assetId(path);
        return animation?id.replace(":animations/",":"):id;
    }
    static String assetId(String path){String[] parts=path.split("/",3);return parts[1]+":"+parts[2].substring(0,parts[2].lastIndexOf('.'));}
    private boolean save(){try{workspace.write(file,root);notice=WorkshopUi.text("saved","草稿已保存，尚未构建");return true;}catch(Exception e){notice=e.getMessage();return false;}}
    public void requestClose(){if(save())super.requestClose();}
    public void render(){renderBackground(0.08F,0.12F,0.1F);
        client.fonts().body().draw(WorkshopScreen.shorten(file+" / "+String.join("/",location),client.guiWidth()-24),12,client.guiHeight()-28,0.9F,1,1,0.8F,1);
        client.fonts().body().draw(WorkshopScreen.shorten(notice,client.guiWidth()-24),12,10,0.8F,1,0.85F,0.6F,1);
        for(var widget:widgets)widget.render(client);
    }
}
