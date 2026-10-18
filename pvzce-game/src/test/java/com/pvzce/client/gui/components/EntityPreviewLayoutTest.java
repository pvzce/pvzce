package com.pvzce.client.gui.components;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityPreviewLayoutTest {
    @Test void aCircleKeepsItsShapeInsideWideTallAndSquarePreviewBoxes() {
        // Workshop cards use wide boxes; their property pane uses a tall box.
        for(float[] box:new float[][]{{16,57,163,89},{22,126,220,252},{8,8,36,36},{11.5F,27.5F,101,67}}){
            float[] view=EntityPreview.previewArea(box[0],box[1],box[2],box[3]);
            assertEquals(view[2],view[3],0.0001F,"a square world region needs equal pixels per world unit");
            assertEquals(box[0]+box[2]/2F,view[0]+view[2]/2F,0.0001F);
            assertEquals(box[1]+box[3]/2F,view[1]+view[3]/2F,0.0001F);
            assertTrue(view[0]>=box[0]&&view[1]>=box[1]);
            assertTrue(view[0]+view[2]<=box[0]+box[2]&&view[1]+view[3]<=box[1]+box[3]);
            assertEquals(Math.min(box[2],box[3]),view[2],0.0001F,"fill the limiting axis instead of stretching the other one");
        }
    }
    @Test void tallAndOversizedArtFitsTheWorldViewWithoutChangingItsProportions() {
        for(float[] art:new float[][]{{0.625284F,0.95F},{0.7F,0.7F},{2.5F,1.2F}}){
            float cx=0.425F,ground=0.065F,cy=ground+art[1]/2F;
            float[] world=EntityPreview.worldArea(cx,cy,art[0],art[1]);
            assertEquals(world[1]-world[0],world[3]-world[2],0.0001F);
            assertTrue(world[0]<cx-art[0]/2F&&world[1]>cx+art[0]/2F);
            assertTrue(world[2]<ground&&world[3]>ground+art[1],"the head and feet must both fit");
        }
    }

    @Test void shippedPlantAndZombieIdleGeometryFitsIncludingHelmets()throws Exception {
        try(var resources=com.pvzce.common.tag.TestContent.loadBuiltInContentAndTags()){
            var manager=new com.pvzce.client.animation.AnimationManager(null,new com.pvzce.client.ClientLevel(),resources);
            for(String name:java.util.List.of("pea_shooter","snow_pea","sunflower","wall_nut","basic_zombie","buckethead_zombie","conehead_zombie")){
                String kind=name.contains("zombie")?"zombie":"plant";
                var entity=new com.pvzce.client.ClientEntity(1,kind,"pvzce:"+name,.425F,.425F,100,1,"idle",0,"");
                entity.attachAnimationManager(manager);entity.playAnimation("idle");
                var playback=(com.pvzce.client.animation.ControllerPlayback)manager.playback(entity);
                assertNotNull(playback,name);
                var model=((com.pvzce.client.animation.ControllerFile)playback.file()).model();
                float scale=com.pvzce.common.core.EntityArt.renderScale(entity.defId(),entity.previewCatalog());
                float[] bounds=playback.bounds(manager.now()),anchor=manager.anchor(entity);
                float[] world=EntityPreview.worldArea(anchor[0]+(bounds[0]+bounds[1])*scale/2F,
                        anchor[1]+(bounds[2]+bounds[3])*scale/2F,(bounds[1]-bounds[0])*scale,(bounds[3]-bounds[2])*scale);
                var poses=playback.currentPose(manager.now());
                var transforms=new java.util.HashMap<String,com.pvzce.client.animation.Affine2>();
                for(var bone:model.renderOrder()){
                    var pose=poses.getOrDefault(bone.name(),bone.restPose());
                    var parent=bone.parent()==null?com.pvzce.client.animation.Affine2.IDENTITY:transforms.get(bone.parent());
                    var transform=parent.multiply(pose.toAffine(bone.pivot()));transforms.put(bone.name(),transform);
                    if(!pose.visible())continue;
                    for(var part:bone.parts())for(int corner=0;corner<4;corner++){
                        float x=part.offsetX()+part.sizeX()*(corner%2==0?-.5F:.5F);
                        float y=part.offsetY()+part.sizeY()*(corner<2?-.5F:.5F);
                        float px=anchor[0]+transform.transformX(x,y)*scale,py=anchor[1]+transform.transformY(x,y)*scale;
                        assertTrue(px>=world[0]&&px<=world[1]&&py>=world[2]&&py<=world[3],name+" / "+bone.name()+" must fit the preview");
                    }
                }
                manager.release(entity);
            }
        }
    }

}
