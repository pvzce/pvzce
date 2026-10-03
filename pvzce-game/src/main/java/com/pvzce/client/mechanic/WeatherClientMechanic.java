package com.pvzce.client.mechanic;

import com.pvzce.api.content.WeatherData;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.level.mechanic.WeatherState;
import com.pvzce.common.network.PacketByteBuf;

/** A readable forecast and ordinary rain; weather never hides entities or introduces lightning. */
public final class WeatherClientMechanic implements ClientMechanic {
    private static final Identifier CLOUD = PvzceIds.id("textures/gui/screen/fog_cloud");

    @Override public Identifier id() { return PvzceIds.MECHANIC_WEATHER; }

    @Override public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(id(), WeatherState.CODEC.decode(payload));
    }

    private static WeatherState stateOf(ClientLevel level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_WEATHER, WeatherState.class);
    }

    private static String label(WeatherData.Kind kind) {
        return GuiLang.raw("gui.pvzce.weather." + kind.key(), kind.key());
    }

    @Override public void renderHud(PvzceClient client) {
        WeatherData data = client.level().mechanicData(id(), WeatherData.class);
        WeatherState state = stateOf(client.level());
        if (data == null || state == null) return;
        WeatherData.Phase next = data.nextAtWave(client.level().currentWave());
        String heading = next == null ? label(state.weather()) : String.format(
                GuiLang.raw("gui.pvzce.weather.forecast", "%s · wave %d: %s"),
                label(state.weather()), next.fromWave(), label(next.weather()));
        float mushrooms = state.mushroomMultiplier();
        float flowers = state.sunflowerMultiplier();
        String rule = String.format(GuiLang.raw("gui.pvzce.weather.rates",
                "Mushrooms %d%% · sunflowers %d%%"), Math.round(mushrooms * 100), Math.round(flowers * 100));
        String ash = switch (state.weather()) {
            case CLEAR -> GuiLang.raw("gui.pvzce.weather.clear_fire", "Torch / ash normal");
            case CLOUDY -> GuiLang.raw("gui.pvzce.weather.cloudy_fire", "Torch / ash weakened");
            case RAIN -> GuiLang.raw("gui.pvzce.weather.rain_fire", "Torch out · ash cooldown doubled");
        };
        String cold = String.format(GuiLang.raw("gui.pvzce.weather.cold",
                "Cold duration %d%% · icy splash %d%%"), Math.round(state.coldDurationMultiplier() * 100),
                Math.round(state.icySplashMultiplier() * 100));
        float scale = 0.7F;
        float width = Math.max(client.fonts().body().width(heading, scale),
                Math.max(client.fonts().body().width(rule, scale),
                        Math.max(client.fonts().body().width(ash, scale), client.fonts().body().width(cold, scale)))) + 16F;
        float x = client.guiWidth() - width - 12F;
        float y = 56F;
        client.drawSolid(x, y, width, 59F, 8F, 0.04F, 0.07F, 0.14F, 0.88F);
        client.fonts().body().draw(heading, x + 8F, y + 45F, scale, 0.65F, 0.85F, 1F, 1F);
        client.fonts().body().draw(rule, x + 8F, y + 32F, scale, 1F, 1F, 1F, 1F);
        client.fonts().body().draw(cold, x + 8F, y + 19F, scale, 0.65F, 0.85F, 1F, 1F);
        client.fonts().body().draw(ash, x + 8F, y + 6F, scale, 1F, 0.84F, 0.55F, 1F);
    }

    @Override public WorldOverlay createWorldOverlay(ClientLevel level) {
        if (level.mechanicData(id(), WeatherData.class) == null) return null;
        return (client, camera) -> {
            WeatherState state = stateOf(level);
            if (state == null) return;
            boolean rain = state.weather() == WeatherData.Kind.RAIN;
            if (rain) {
                client.sound().playAmbient(PvzceSounds.AMBIENT_RAIN.toString(), 0.45F);
            } else if (StormClientMechanic.dataOf(level) == null
                    && level.mechanicData(PvzceIds.MECHANIC_SEED_RAIN,
                        com.pvzce.api.content.SeedRainData.class) == null) {
                client.sound().stopAmbient();
            }
            if (state.weather() == WeatherData.Kind.CLEAR) return;
            client.drawSolid(camera.worldLeft(), camera.worldBottom(), camera.worldWidth(), camera.worldHeight(),
                    0.30F, 0.03F, 0.06F, 0.12F, rain ? 0.16F : 0.08F);
            float drift = (float) (level.smoothLevelTicks() % 2400) / 2400F;
            client.drawTextureShaded(CLOUD, 0F, 0F, client.textureWidth(CLOUD), client.textureHeight(CLOUD),
                    camera.worldLeft() - camera.worldWidth() * drift, camera.worldTop() - 1.5F,
                    camera.worldWidth() * 2F, 1.8F, 0.31F, 0.27F, 0.30F, 0.38F,
                    0F, 0F, rain ? 0.8F : 0.6F, rain ? 0.8F : 0.6F);
            if (rain) SeedRainClientMechanic.renderRain(client, camera, level.smoothLevelTicks());
        };
    }
}
