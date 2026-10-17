package com.pvzce.client.gui.workshop;

import com.google.gson.*;
import com.pvzce.api.registry.RegistryAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EntityPreview;
import com.pvzce.common.core.*;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.workshop.*;
import java.nio.file.*;
import java.util.*;

/** One screen's draft names, art and bounded animation pool; never registers unpublished content. */
final class WorkshopPresentation implements AutoCloseable {
    record Item(String value, String type, Identifier id, String name, String detail, JsonObject json) {}
    private final PvzceClient client;
    private final Workspace workspace;
    private RegistryAccess catalog;
    private EntityPreview previews;
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Item> cache = new HashMap<>();
    private Path previousOverlay;
    private boolean attached;
    private com.pvzce.client.sound.PreviewChannel.Session audition;

    WorkshopPresentation(PvzceClient client, Workspace workspace) {
        this.client = client;
        this.workspace = workspace;
        refresh();
    }

    void refresh() {
        if (previews != null) previews.release();
        cache.clear(); names.clear();
        catalog = workspace == null ? BuiltInRegistries.ACCESS : WorkshopContent.catalog(workspace, false);
        previews = new EntityPreview(client, catalog);
        if (workspace != null) {
            try {
                for (String path : workspace.files()) {
                    if (!path.endsWith("/lang/" + GuiLang.DEFAULT_LOCALE + ".json")
                            && !path.endsWith("/lang/" + GuiLang.locale() + ".json")) continue;
                    JsonObject json = JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
                    for (var entry : json.entrySet()) if (entry.getValue().isJsonPrimitive()) names.put(entry.getKey(), entry.getValue().getAsString());
                }
                // The selected locale wins over the base language, regardless of file order.
                for (String path : workspace.files()) if (path.endsWith("/lang/" + GuiLang.locale() + ".json")) {
                    JsonObject json = JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
                    for (var entry : json.entrySet()) if (entry.getValue().isJsonPrimitive()) names.put(entry.getKey(), entry.getValue().getAsString());
                }
            } catch (Exception ignored) { /* Invalid drafts still have a usable file card. */ }
        }
    }

    void activate(){attach();}
    private void attach() {
        if (workspace != null && client.resources() != null && !attached) {
            previousOverlay = client.resources().workspaceOverlay();
            attached = true;
            if (!workspace.root().equals(previousOverlay)) client.previewWorkspaceAssets(workspace.root());
        }
    }

    void draft(String file, JsonObject root) {
        Item item=item(file, "");
        for (var data : PvzceDataLoader.CONTENT_REGISTRIES) if(data.contentPath().equals(item.type())) applyDraft(data,item.id(),root);
        previews.release();
        cache.put("|"+file,new Item(item.value(),item.type(),item.id(),root.has("name")&&root.get("name").isJsonPrimitive()?root.get("name").getAsString():item.name(),item.detail(),root.deepCopy()));
    }
    private <T> void applyDraft(PvzceDataLoader.RegistryData<T> data, Identifier id, JsonObject root) {
        JsonObject json=root.deepCopy(); json.addProperty("id",id.toString());
        try { data.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,json).result().ifPresent(value -> {
            var registry=catalog.get(data.key());
            if(registry instanceof com.pvzce.api.registry.MappedRegistry<T> mapped){mapped.unfreeze();try{mapped.registerDynamic(id,value);}finally{mapped.freeze();}}
        }); } catch(RuntimeException ignored) { /* Keep the last valid preview while the draft is incomplete. */ }
    }
    RegistryAccess catalog() { return catalog; }
    static String category(String type) {
        if(type.endsWith("_capabilities"))return type.replace("_capabilities","_capability");
        return PvzceDataLoader.CONTENT_REGISTRIES.stream().filter(d -> d.contentPath().equals(type))
                .map(d -> d.idPath()).findFirst().orElse("");
    }
    Item item(String value, String type) {
        return cache.computeIfAbsent(type + "|" + value, ignored -> read(value, type));
    }
    private Item read(String value, String wantedType) {
        String type = wantedType == null ? "" : wantedType;
        Identifier id = Identifier.tryParse(value);
        JsonObject json = new JsonObject();
        String[] path = value.split("/", 4);
        boolean file = path.length == 4 && (path[0].equals("data") || path[0].equals("assets"));
        if (file) {
            type = path[2];
            String leaf = path[3].replaceFirst("\\.[^.]+$", "");
            id = Identifier.tryParse(path[1] + ":" + (type.equals("textures") ? "textures/" : "") + leaf);
        }
        try {
            String source = file ? value : id == null || type.isEmpty() ? null : WorkshopContent.path(workspace, type, id.toString());
            if (source != null && source.endsWith(".json")) {
                if (workspace != null && Files.isRegularFile(workspace.file(source))) json = JsonParser.parseString(Files.readString(workspace.file(source))).getAsJsonObject();
                else if (client.resources() != null) {
                    String[] parts = source.split("/", 3);
                    var resource = parts[0].equals("data") ? client.resources().getData(Identifier.parse(parts[1] + ":" + parts[2]))
                            : client.resources().getAsset(Identifier.parse(parts[1] + ":" + parts[2]));
                    if (resource.isPresent()) json = JsonParser.parseString(resource.get().readString()).getAsJsonObject();
                }
            }
        } catch (Exception ignored) { /* A malformed file is still selectable for repair. */ }
        String name = !file && type.isEmpty() ? WorkshopUi.label(value) : id == null ? WorkshopUi.label(value) : GuiLang.name(category(type), id);
        if (id != null) name = names.getOrDefault(RegistryCategories.key(category(type), id), name);
        if (json.has("name") && json.get("name").isJsonPrimitive()) name = json.get("name").getAsString();
        String detail = WorkshopUi.label(type);
        if(Set.of("sounds","sound_events").contains(type))detail+=" · 右键试听";
        if(Set.of("textures","animations").contains(type))detail+=" · 右键预览";
        if (json.has("health")&&json.get("health").isJsonPrimitive()) detail += " · 生命 " + json.get("health").getAsString();
        if (json.has("cost") && json.get("cost").isJsonObject()) {
            JsonObject cost = json.getAsJsonObject("cost");
            if (cost.has("resources") && cost.get("resources").isJsonObject() && cost.getAsJsonObject("resources").has("pvzce:sun") && cost.getAsJsonObject("resources").get("pvzce:sun").isJsonPrimitive()) detail += " · 阳光 " + cost.getAsJsonObject("resources").get("pvzce:sun").getAsString();
        }
        return new Item(value, type, id, name, detail, json);
    }

    void draw(Item item, float x, float y, float width, float height) {
        attach();
        if (item.id() == null) return;
        String kind = switch (item.type()) {
            case "plants" -> "plant"; case "zombies" -> "zombie"; case "projectiles" -> "projectile";
            case "resources" -> "resource"; case "tools" -> "tool"; default -> "";
        };
        Identifier id = item.id();
        if (item.type().equals("slots")) {
            var slot = catalog.get(PvzceRegistries.SLOT_TYPES).get(id);
            if (slot != null) { id = slot.content(); kind = slot.kind().json(); }
        }
        if (!kind.isEmpty() && previews.draw(id, kind, x, y, width, height)) return;
        Identifier texture = kind.isEmpty() ? null : EntityArt.sprite(id, kind, catalog);
        if (item.type().equals("textures")) texture = id;
        else if (item.type().equals("dialogue_characters")) {
            var character = catalog.get(PvzceRegistries.DIALOGUE_CHARACTERS).get(id);
            if (character != null) {
                texture = character.portraitTexture(id.path());
                if(!client.hasTexture(texture)) {
                    String prefix="assets/"+character.resolvedPortraitDir().toPath()+"/";
                    try {String first=client.resources().listResources(prefix).keySet().stream().filter(p->p.endsWith(".png")).sorted().findFirst().orElse(null);
                        if(first!=null)texture=Identifier.tryParse(WorkshopJsonScreen.assetId(first));
                    } catch(java.io.IOException ignored) { }
                }
            }
        } else if (item.type().equals("scene_elements")) {
            var def = catalog.get(PvzceRegistries.SCENE_ELEMENTS).get(id);
            if (def != null) texture = def.art().flatMap(com.pvzce.api.content.SceneElementArt::texture).orElse(Identifier.of(id.namespace(), "textures/scene/" + id.path()));
        } else if (item.type().equals("slots") && item.json().has("icon")) texture = Identifier.tryParse(item.json().get("icon").getAsString());
        if (texture != null && client.hasTexture(texture)) {
            var tex = client.textures().getOrLoad(texture);
            float scale = Math.min(width / tex.width(), height / tex.height());
            client.drawTexture(texture, x + (width - tex.width()*scale)/2, y + (height-tex.height()*scale)/2,
                    tex.width()*scale, tex.height()*scale, 0, 1, 1, 1, 1);
        } else {
            String glyph = switch (item.type()) { case "sounds", "sound_events" -> "♪"; case "animations" -> "▶"; case "levels" -> "▦"; default -> "◆"; };
            client.fonts().body().draw(glyph, x + width/2-12, y+height/2-8, 1.5F, .55F,.8F,.56F,1);
        }
    }

    Button card(Item item, int x, int y, int width, int height, Runnable action) {
        return new Button(x, y, width, height, item.name(), action) {
            @Override public boolean mouseClicked(double mx,double my,int button){
                if(button==1&&isMouseOver(mx,my)&&item.id()!=null){
                    if(Set.of("sounds","sound_events").contains(item.type())){if(client.sound()!=null){activate();if(audition==null)audition=client.sound().preview();audition.play(item.id().toString());}return true;}
                    if(Set.of("textures","animations").contains(item.type())&&workspace!=null){
                        String path=item.value().startsWith("assets/")?item.value():item.type().equals("textures")?"assets/"+item.id().namespace()+"/"+item.id().path()+".png":WorkshopContent.path(workspace,item.type(),item.id().toString());
                        client.openScreen(new WorkshopPreviewScreen(client,workspace,path));return true;
                    }
                }
                return button==0&&super.mouseClicked(mx,my,button);
            }
            @Override public void render(PvzceClient renderClient) {
                renderClient.drawSolid(x,y,width,height,0, hovered?.24F:.12F,hovered?.36F:.21F,.20F,1);
                renderClient.drawSolid(x,y+height-3,width,3,0,.48F,.70F,.36F,1);
                if(height<90){
                    int image=height-16;
                    draw(item,x+8,y+8,image,image);
                    int tx=x+image+16,tw=width-image-24;
                    WorkshopUi.fit(renderClient,item.name(),tx,y+height-21,tw,.8F,1,1,.85F);
                    if(height>=64)WorkshopUi.fit(renderClient,item.detail(),tx,y+25,tw,.6F,.73F,.84F,.72F);
                    WorkshopUi.fit(renderClient,item.id()==null?item.value():item.id().toString(),tx,y+9,tw,.58F,.55F,.66F,.62F);
                    return;
                }
                int image = Math.max(18, height-63);
                draw(item,x+8,y+57,width-16,image);
                WorkshopUi.fit(renderClient,item.name(),x+9,y+38,width-18,.9F,1,1,.85F);
                WorkshopUi.fit(renderClient,item.detail(),x+9,y+22,width-18,.65F,.73F,.84F,.72F);
                WorkshopUi.fit(renderClient,item.id()==null?item.value():item.id().toString(),x+9,y+8,width-18,.58F,.55F,.66F,.62F);
            }
        };
    }
    void stopAudition(){if(audition!=null)audition.stop();}
    @Override public void close() {
        if(audition!=null)audition.close();
        previews.release();
        if (attached) { attached = false; if (!Objects.equals(previousOverlay, client.resources().workspaceOverlay())) client.previewWorkspaceAssets(previousOverlay); }
    }
}
