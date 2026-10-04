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
    /** How long the forecast takes to reach full brightness, in seconds of level time. */
    private static final float WARNING_FADE_SECONDS = 0.4F;

    /** The forecast this client has already reacted to, and when it appeared. */
    private int warningSequence;
    private long warningStartTicks;

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
        renderWarning(client, client.level(), state);
    }

    /**
     * The forecast, in the same red the huge-wave call uses.
     *
     * <p>Ten seconds before the sky changes, and once: the message is a moment rather than a
     * state, so this watches the state's own sequence counter for "a new one" - two identical
     * forecasts in a row (cloudy to rainy and back, on a level that has both) would otherwise read
     * as one banner that never went away. It runs on the level's own clock rather than a wall
     * clock, so a paused game holds the banner where it is.
     */
    private void renderWarning(PvzceClient client, ClientLevel level, WeatherState state) {
        if (level.aliveZombieCount() > com.pvzce.common.PvzceConstants.MANY_ZOMBIES) {
            // A lawn full of zombies is its own warning; a line of red text over it is noise, and
            // the player has something more urgent to look at.
            return;
        }
        if (state.sequence() != warningSequence) {
            warningSequence = state.sequence();
            warningStartTicks = (long) level.smoothLevelTicks();
        }
        String warning = state.warning() == null
                ? "" : GuiLang.raw("gui.pvzce.weather.warn." + state.warning().key(), "");
        if (warning.isEmpty()) {
            return;
        }
        long shownFor = (long) level.smoothLevelTicks() - warningStartTicks;
        float alpha = Math.min(1F, shownFor / (WARNING_FADE_SECONDS * com.pvzce.common.PvzceConstants.TICKS_PER_SECOND));
        if (alpha <= 0F) {
            return;
        }
        float unit = Math.max(1F, client.fonts().body().width(warning, 1F));
        float textScale = Math.max(1.2F, Math.min(3F, client.guiWidth() * 0.7F / unit));
        float x = (client.guiWidth() - client.fonts().body().width(warning, textScale)) / 2F;
        float y = client.guiHeight() * 0.58F;
        float shadow = Math.max(1.5F, textScale * 1.6F);
        client.fonts().body().draw(warning, x + shadow, y + shadow, textScale,
                0.05F, 0.02F, 0.02F, 0.75F * alpha);
        client.fonts().body().draw(warning, x, y, textScale, 1F, 0.25F, 0.2F, alpha);
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
