package com.pvzce.client.gui.workshop;

import com.google.gson.*;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.animation.AnimationResourceLoader;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.workshop.*;
import java.nio.file.*;
import java.util.function.Consumer;

final class WorkshopAssets {
    private WorkshopAssets() {}
    static void chooseFile(Screen owner,String type,Consumer<Path> accept) {
        owner.client().openScreen(new WorkshopFileScreen(owner.client(),type,path->{
            if(path!=null)accept.accept(path);
        }));
    }
    static void importPackIcon(Workspace workspace,Path source)throws Exception {
        byte[] bytes=Files.readAllBytes(source);
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        if(bytes.length<signature.length||!java.util.Arrays.equals(signature,java.util.Arrays.copyOf(bytes,signature.length))
                ||javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))==null)throw new IllegalArgumentException("图标需要有效的 PNG 图片");
        workspace.writeBytes(com.pvzce.common.resource.PvzcePack.ICON_FILE,bytes);
    }
    static void importFile(Workspace workspace,String path,Path source)throws Exception {
        if(!Files.isRegularFile(source))throw new IllegalArgumentException("文件不存在");
        if(Files.exists(workspace.file(path)))throw new IllegalArgumentException("素材已存在，请使用其它路径或先删除旧素材");
        if(path.endsWith(".png")&&javax.imageio.ImageIO.read(source.toFile())==null)throw new IllegalArgumentException("贴图需要 PNG 图片");
        if(path.endsWith(".ogg")) {
            try(var stream=Files.newInputStream(source)) {
                if(!new String(stream.readNBytes(4),java.nio.charset.StandardCharsets.US_ASCII).equals("OggS"))
                    throw new IllegalArgumentException("音频需要 Ogg Vorbis 文件");
            }
        }
        if(path.contains("/animations/"))AnimationResourceLoader.parse(JsonParser.parseString(Files.readString(source)).getAsJsonObject(),Identifier.parse("preview:import"));
        Path target=workspace.file(path);Files.createDirectories(target.getParent());Files.copy(source,target);
        if(path.contains("/sounds/")) {
            String[] parts=path.split("/",4);String id=parts[3].substring(0,parts[3].length()-4);
            String manifest="assets/"+parts[1]+"/sounds.json";
            JsonObject sounds=Files.exists(workspace.file(manifest))?JsonParser.parseString(Files.readString(workspace.file(manifest))).getAsJsonObject():new JsonObject();
            JsonObject sound=new JsonObject();JsonArray choices=new JsonArray();choices.add(parts[1]+":"+id);sound.add("sounds",choices);sounds.add(id,sound);workspace.write(manifest,sounds);
            JsonObject event=new JsonObject();event.addProperty("id",parts[1]+":"+id);workspace.write("data/"+parts[1]+"/sound_events/"+id+".json",event);
        }
    }
    static void validate(Workspace workspace) {
        WorkshopContent.validate(workspace);
        try (var resources=new com.pvzce.common.resource.PvzceResourceManager(WorkshopAssets.class.getClassLoader())) {
            resources.workspaceOverlay(workspace.root());resources.init(workspace.root().getParent().getParent());
            for(String path:workspace.files())if(path.endsWith(".json")) {
                JsonObject json=JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
                if(path.contains("/animations/")) {
                    var file=AnimationResourceLoader.parse(json,Identifier.parse(WorkshopJsonScreen.assetId(path).replace(":animations/",":")));
                    if(file instanceof com.pvzce.client.animation.FlipbookFile flipbook)
                        for(var clip:flipbook.clips().values())for(var frame:clip.frames())requireAsset(resources,frame,".png",path);
                    if(file instanceof com.pvzce.client.animation.ControllerFile controller)
                        for(var bone:controller.model().bones().values())for(var part:bone.parts())requireAsset(resources,part.texture(),".png",path);
                } else if(path.startsWith("data/")&&!path.contains("/tags/")) {
                    for(String key:java.util.List.of("texture","icon"))if(json.has(key)&&!(key.equals("texture")&&(json.has("animation")||json.has("animation_dir"))))requireAsset(resources,Identifier.parse(json.get(key).getAsString()),".png",path);
                    var bindings=com.pvzce.api.content.AnimationBindings.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,json).getOrThrow();
                    if(bindings.animation().isPresent())requireAnimation(resources,bindings.animation().get(),null,path);
                    for(var binding:bindings.animations().entrySet())requireAnimation(resources,binding.getValue(),bindings.clipName(binding.getKey()),path);
                    for(var binding:bindings.clips().entrySet()) {
                        Identifier id=bindings.resolve(binding.getKey()).orElseGet(()->bindings.fileId(Identifier.parse(json.has("id")?json.get("id").getAsString():path.split("/",4)[1]+":"+path.split("/",4)[3].replaceFirst("\\.json$",""))));
                        requireAnimation(resources,id,binding.getValue(),path);
                    }
                }
            }
        }catch(java.io.IOException e){throw new IllegalArgumentException(e.getMessage(),e);}
    }
    private static void requireAsset(com.pvzce.common.resource.PvzceResourceManager resources,Identifier id,String suffix,String source)throws java.io.IOException {
        if(resources.getAsset(id.withSuffix(suffix)).isEmpty()&&resources.getAsset(id).isEmpty())throw new IllegalArgumentException(source+"：找不到素材 "+id);
    }
    private static void requireAnimation(com.pvzce.common.resource.PvzceResourceManager resources,Identifier id,String clip,String source)throws java.io.IOException {
        var asset=resources.getAsset(Identifier.of(id.namespace(),"animations/"+id.path()+".json"))
                .orElseThrow(()->new IllegalArgumentException(source+"：找不到动画 "+id));
        var parsed=AnimationResourceLoader.parse(JsonParser.parseString(asset.readString()).getAsJsonObject(),id);
        if(clip!=null&&!parsed.clipNames().contains(clip))throw new IllegalArgumentException(source+"：动画 "+id+" 没有片段 "+clip);
    }
}
