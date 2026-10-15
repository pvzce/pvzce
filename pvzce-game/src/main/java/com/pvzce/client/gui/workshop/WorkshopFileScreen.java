package com.pvzce.client.gui.workshop;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** File selection runs away from the render thread; the result is consumed on tick. */
final class WorkshopFileScreen extends Screen {
    private final String type;
    private final Consumer<Path> accept;
    private CompletableFuture<Path> selection;
    private String error="";
    WorkshopFileScreen(PvzceClient client,String type,Consumer<Path> accept){super(client);this.type=type;this.accept=accept;}
    protected void init(){
        int w=Math.min(500,client.guiWidth()-32),x=centerX(w),y=client.guiHeight()/2;
        EditBox path=new EditBox(x,y,w,30,4096,()->{});addWidget(path);
        addWidget(new Button(x,y-40,w/2-4,30,WorkshopUi.text("file_path","导入此路径"),()->finish(Path.of(path.value()))));
        addWidget(new Button(x+w/2+4,y-40,w/2-4,30,WorkshopUi.text("file_choose","选择文件…"),()->{
            if(selection==null)selection=CompletableFuture.supplyAsync(this::choose);
        }));
        addWidget(new Button(centerX(140),20,140,28,WorkshopUi.text("cancel","取消"),this::requestClose));
    }
    private Path choose(){
        try{
            for(String executable:java.util.List.of("/usr/bin/zenity","/usr/bin/kdialog"))if(Files.isExecutable(Path.of(executable))){
                Process process=new ProcessBuilder(executable,executable.endsWith("zenity")?"--file-selection":"--getopenfilename")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
                return process.waitFor()==0&&!result.isEmpty()?Path.of(result):null;
            }
            javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
            final Path[] result={null};javax.swing.SwingUtilities.invokeAndWait(()->{
                if(chooser.showOpenDialog(null)==javax.swing.JFileChooser.APPROVE_OPTION)result[0]=chooser.getSelectedFile().toPath();
            });return result[0];
        }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
    }
    private void finish(Path path){if(!Files.isRegularFile(path)){error=WorkshopUi.text("file_missing","文件不存在");return;}client.popScreen();accept.accept(path);}
    public void tick(){if(selection!=null&&selection.isDone()){
        try{Path path=selection.join();selection=null;if(path!=null)finish(path);}catch(Exception e){selection=null;error=WorkshopUi.text("file_fallback","系统选择器不可用，请填写文件绝对路径");}
    }}
    public void render(){renderBackground(0.08F,0.12F,0.1F);
        client.fonts().body().draw(WorkshopUi.text("import","导入素材")+" · "+type,16,client.guiHeight()-30,1,1,1,0.8F,1);
        client.fonts().body().draw(WorkshopScreen.shorten(error,client.guiWidth()-32),16,60,0.85F,1,0.8F,0.6F,1);
        for(var widget:widgets)widget.render(client);
    }
}
