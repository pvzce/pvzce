package com.pvzce.common.capability;

import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.plant.PlantShots;
import com.pvzce.common.level.WorldPosition;
import com.pvzce.server.Team;
import com.pvzce.server.entity.*;
import java.util.*;

/** Shared behaviour sees a host's services, rather than assuming a stationary plant. */
public record CapabilityHost(PvzceEntity entity) {
    public float cellX(){return entity.cellX();}
    public float cellY(){return entity.cellY();}
    public int gridX(){return entity.gridX();}
    public int gridY(){return entity.gridY();}
    public float height(){return entity.height();}
    public String surfaceId(){return entity.surfaceId();}
    public WorldPosition position(){return entity.position();}
    public Team team(){return entity.team();}
    public boolean isRemoved(){return entity.isRemoved();}
    public void remove(){entity.remove();}
    public float actionRate(){return entity instanceof PlantEntity p?p.actionRate():1F;}
    public float actionRate(float rate){return entity instanceof PlantEntity p?p.actionRate(rate):rate;}
    public float weatherAction(LevelAccess level){return entity instanceof PlantEntity p?level.weatherActionMultiplier(p):1F;}
    public float weatherRange(LevelAccess level){return entity instanceof PlantEntity p?level.weatherRangeMultiplier(p):1F;}
    public void setAnimation(String state){if(entity instanceof ZombieEntity z)z.capabilityState(state);else entity.setAnimation(state);}
    public void setState(String state){if(entity instanceof PlantEntity p)p.setState(state);else if(!state.equals("idle"))setAnimation(state);}
    public void setState(String state,String variant){setState(entity instanceof ZombieEntity&&!variant.isEmpty()?state+"_"+variant:state);}
    public void beginAction(String state){if(entity instanceof PlantEntity p)p.beginAction(state);else if(entity instanceof ZombieEntity z)z.beginCapabilityAction(state);}
    public Optional<Identifier> produceSound(){return entity instanceof PlantEntity p?p.def().sounds().produce():Optional.empty();}
    public Optional<Identifier> shootSound(){return entity instanceof PlantEntity p?p.def().sounds().shoot():Optional.empty();}
    public Optional<Identifier> explodeSound(){return entity instanceof PlantEntity p?p.def().sounds().explode():Optional.empty();}
    public int projectileMultiplier(LevelAccess level){return entity instanceof PlantEntity p?level.projectileCountMultiplier(p):1;}
    public ProjectileRef scaled(ProjectileRef ref,LevelAccess level){
        if(entity instanceof PlantEntity p)return PlantShots.scaled(ref,p,level);
        return new ProjectileRef(ref.projectile(),ref.damage(),ref.count(),ref.rowOffset(),(entity.charmed() ? ref.backward() : !ref.backward()),ref.rows(),ref.range(),ref.burstDelay(),ref.initialDelay(),ref.targetRow(),ref.vectorX(),ref.vectorY(),ref.launchHeight());
    }
    public void shoot(LevelAccess level,ProjectileRef ref,float x,float y){
        if(entity instanceof PlantEntity p)level.spawnProjectile(ref,x,y,p);
        else level.spawnZombieProjectile(ref,x,y,(ZombieEntity)entity);
    }
    public void produce(LevelAccess level,Identifier id,int amount,float x,float y,float scale,float drift){
        if(entity instanceof PlantEntity p)level.spawnProducedResource(id,amount,x,y,p,scale,drift);
        else level.spawnProducedResource(id,amount,x,y,team(),scale,drift,surfaceId());
    }
    public void damagePlants(LevelAccess level, float radius, int damage, boolean square, boolean row) {
        if (!(entity instanceof ZombieEntity)) return;
        for (PvzceEntity target : enemies(level)) if (target instanceof PlantEntity plant
                && (row ? plant.gridY() == gridY() : level.reachedByBlast(position(), plant, radius, square))) {
            plant.damageFrom(damage);
        }
    }

    public List<PvzceEntity> enemies(LevelAccess level,int row){
        List<PvzceEntity> result=new ArrayList<>(level.enemiesInRow(row,team(),surfaceId()));
        if(entity instanceof ZombieEntity)for(int x=0;x<level.width();x++)for(PlantEntity p:level.plantsAt(x,row,surfaceId()))
            if(!p.isRemoved()&&p.team()!=team()&&!p.team().id().equals(team().id()))result.add(p);
        return result;
    }
    public List<PvzceEntity> enemies(LevelAccess level){List<PvzceEntity> result=new ArrayList<>();for(int row=0;row<level.height();row++)result.addAll(enemies(level,row));return result;}
    public static boolean ground(PvzceEntity entity){return entity instanceof ZombieEntity z?z.canBeHitByGround():!entity.isRemoved();}
    public static boolean air(PvzceEntity entity){return entity instanceof ZombieEntity z&&!z.isGrounded();}
}
