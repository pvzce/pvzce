package com.pvzce.client.renderer;

import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.hud.cardbar.CardBarLayout;
import com.pvzce.client.mechanic.FogClientMechanic;
import com.pvzce.client.mechanic.StormClientMechanic;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Presentation of the server's lily states, on every lawn; never decides when to attack. */
public final class EchoLilyLinks {
    private final Map<Integer, String> seenAnimations = new HashMap<>();
    private final Map<Integer, Double> shotTicks = new HashMap<>();

    private static List<ClientEntity> visibleLilies(PvzceClient client) {
        if (StormClientMechanic.hides(client.level())) {
            return List.of();
        }
        return client.level().entities().values().stream()
                .filter(e -> PvzceIds.ECHO_LILY.equals(e.defId()) && e.health() > 0
                        && !"sleep".equals(e.animation())
                        && !FogClientMechanic.hides(client.level(), e.cellX(), e.cellY())).toList();
    }

    private static boolean adjacent(ClientEntity a, ClientEntity b) {
        return a.teamId().equals(b.teamId())
                && Math.abs(a.gridX() - b.gridX()) + Math.abs(a.gridY() - b.gridY()) == 1;
    }

    static boolean resonating(String animation) {
        return "charged".equals(animation) || "resonate".equals(animation);
    }

    static int charge(String animation) {
        return resonating(animation) ? PvzceConstants.ECHO_CHARGE_VOLLEYS
                : animation.endsWith("charge_2") ? 2 : animation.endsWith("charge_1") ? 1 : 0;
    }

    private static boolean shooting(String animation) {
        return "resonate".equals(animation) || animation.startsWith("shoot") || animation.startsWith("echo");
    }

    public void renderWorld(PvzceClient client) {
        List<ClientEntity> lilies = visibleLilies(client);
        Set<Integer> live = new HashSet<>();
        double now = client.level().smoothLevelTicks();
        for (ClientEntity lily : lilies) {
            live.add(lily.id());
            String previous = seenAnimations.put(lily.id(), lily.animation());
            if (!lily.animation().equals(previous) && shooting(lily.animation())) {
                shotTicks.put(lily.id(), now);
            }
        }
        seenAnimations.keySet().retainAll(live);
        shotTicks.keySet().retainAll(live);
        var ring = BuiltInRegistries.PARTICLES.get(PvzceIds.ECHO_RING);
        for (ClientEntity a : lilies) {
            boolean resonating = resonating(a.animation());
            if (resonating && ring != null) {
                float size = 1.18F + (float) Math.sin(now / 10F) * 0.04F;
                client.drawTexture(ring.look().texture(),
                        a.cellX() - size / 2F, a.cellY() - size / 2F, size, size,
                        0.07F, 1F, 0.95F, 0.55F, 0.8F);
            }
            for (ClientEntity b : lilies) {
                if (b.id() <= a.id() || !adjacent(a, b)) {
                    continue;
                }
                float thickness = resonating || resonating(b.animation()) ? 0.08F : 0.05F;
                client.drawSolid(Math.min(a.cellX(), b.cellX()) - thickness / 2F,
                        Math.min(a.cellY(), b.cellY()) - thickness / 2F,
                        Math.abs(a.cellX() - b.cellX()) + thickness,
                        Math.abs(a.cellY() - b.cellY()) + thickness,
                        0.06F, 1F, 0.83F, 0.3F, thickness > 0.05F ? 0.95F : 0.65F);
                // A real shooting-state transition lights its outgoing connection; interpolation
                // is decorative, not a client-side attack or a continuously running fake relay.
                double fromA = shotTicks.getOrDefault(a.id(), -1000D);
                double fromB = shotTicks.getOrDefault(b.id(), -1000D);
                ClientEntity source = fromA >= fromB ? a : b;
                ClientEntity target = source == a ? b : a;
                float phase = (float) ((now - Math.max(fromA, fromB)) / PvzceConstants.ECHO_RELAY_TICKS);
                if (phase >= 0F && phase <= 1F) {
                    float x = source.cellX() + (target.cellX() - source.cellX()) * phase;
                    float y = source.cellY() + (target.cellY() - source.cellY()) * phase;
                    client.drawSolid(x - 0.055F, y - 0.055F, 0.11F, 0.11F,
                            0.09F, 1F, 1F, 0.72F, 1F);
                }
            }
        }
    }

    /** Preview nearby connections before planting, without claiming the cell is plantable. */
    public void renderPreview(PvzceClient client, int x, int y) {
        for (ClientEntity lily : visibleLilies(client)) {
            if (!lily.teamId().equals(client.level().controlledTeam())
                    || Math.abs(lily.gridX() - x) + Math.abs(lily.gridY() - y) != 1) {
                continue;
            }
            float cx = x + 0.5F;
            float cy = y + 0.5F;
            client.drawSolid(Math.min(cx, lily.cellX()) - 0.035F, Math.min(cy, lily.cellY()) - 0.035F,
                    Math.abs(cx - lily.cellX()) + 0.07F, Math.abs(cy - lily.cellY()) + 0.07F,
                    0.1F, 1F, 0.87F, 0.32F, 0.8F);
        }
    }

    private static float[] gaugeAnchor(PvzceClient client, ClientEntity lily) {
        float scale = client.guiScale();
        float x = client.camera().screenX(lily.cellX()) / scale;
        // Above the tallest 0.61-cell shooting pose; GUI padding leaves the flower visible.
        float y = client.camera().screenY(lily.cellY() + 0.68F) / scale;
        float ceiling = CardBarLayout.bankY(client.guiHeight()) - 24F;
        if (y > ceiling) {
            // On the top lawn row the card tray covers the space above the head. Put the
            // gauge beside it instead, with room for the label underneath the tray.
            y = ceiling;
            x += client.camera().unitX() * 0.5F / scale;
        }
        return new float[]{x, y};
    }

    /** Draw after the entities, in GUI space, so leaves and shadows cannot cover the charge. */
    public void renderHud(PvzceClient client) {
        List<ClientEntity> lilies = visibleLilies(client);
        Set<Integer> labelled = new HashSet<>();
        for (ClientEntity lily : lilies) {
            int charge = charge(lily.animation());
            float[] anchor = gaugeAnchor(client, lily);
            float x = anchor[0];
            float y = anchor[1];
            client.drawSolid(x - 15F, y, 30F, 6F, 8F, 0.03F, 0.16F, 0.13F, 0.95F);
            for (int i = 0; i < PvzceConstants.ECHO_CHARGE_VOLLEYS; i++) {
                client.drawSolid(x - 13F + i * 9F, y + 1.5F, 8F, 3F, 8.1F,
                        i < charge ? 1F : 0.22F, i < charge ? 0.85F : 0.36F, 0.28F, 1F);
            }
            if (labelled.contains(lily.id())) {
                continue;
            }
            List<ClientEntity> group = new ArrayList<>();
            group.add(lily);
            labelled.add(lily.id());
            for (int i = 0; i < group.size(); i++) {
                for (ClientEntity other : lilies) {
                    if (!labelled.contains(other.id()) && adjacent(group.get(i), other)) {
                        labelled.add(other.id());
                        group.add(other);
                    }
                }
            }
            ClientEntity top = group.stream().max(java.util.Comparator.comparingInt(ClientEntity::gridY)
                    .thenComparingInt(e -> -e.gridX())).orElse(lily);
            String text = resonating(top.animation()) ? GuiLang.raw("gui.pvzce.echo_lily.resonating", "RESONANCE · RAPID FIRE")
                    : group.size() > 1 ? String.format(GuiLang.raw("gui.pvzce.echo_lily.charging", "Relay %d/3"), charge(top.animation()))
                    : GuiLang.raw("gui.pvzce.echo_lily.solo", "Solo · connect neighbours");
            float scale = 0.7F;
            float width = client.fonts().body().width(text, scale) + 8F;
            float[] labelAnchor = gaugeAnchor(client, top);
            float labelX = labelAnchor[0];
            float labelY = labelAnchor[1] + 8F;
            client.drawSolid(labelX - width / 2F, labelY, width, 12F, 8F,
                    0.03F, 0.16F, 0.13F, 0.95F);
            client.fonts().body().draw(text, labelX - width / 2F + 4F, labelY + 2F,
                    scale, 1F, 0.9F, 0.45F, 1F);
        }
    }
}
