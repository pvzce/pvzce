package com.pvzce.client.renderer.vertex;

public interface VertexConsumer {
    VertexConsumer vertex(float x, float y, float z);

    VertexConsumer color(float r, float g, float b, float a);

    VertexConsumer uv(float u, float v);

    void endVertex();
}
