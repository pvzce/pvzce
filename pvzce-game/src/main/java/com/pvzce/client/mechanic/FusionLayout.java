package com.pvzce.client.mechanic;

/** One reference layout for drawing and picking both toolbars, independent of a live window. */
public record FusionLayout(float scale, float left, float topY, float topRight, int abilitiesPerPage) {
    public static final float WIDTH = 960F;
    public static final float TOP_HEIGHT = 104F;
    public static final float BOTTOM_HEIGHT = 128F;
    public static final int ABILITIES_PER_PAGE = 8;
    public static final int SEEDS_PER_PAGE = 5;
    public static final int TRAY_PER_PAGE = 6;

    public record Box(float x, float y, float width, float height) {
        public Box offset(float dy) { return new Box(x, y + dy, width, height); }
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
    public static FusionLayout of(int width, int height) {
        return of(width, height, width);
    }
    public static FusionLayout of(int width, int height, float toolbarRight) {
        float scale = Math.max(0.001F, Math.min(width / WIDTH, height / 540F));
        float left = (width - WIDTH * scale) / 2F;
        float right = Math.min(712F, (toolbarRight - left) / scale);
        int count = Math.max(1, Math.min(ABILITIES_PER_PAGE, (int) ((right - 172F - 78F) / 57F)));
        return new FusionLayout(scale, left, height / scale - TOP_HEIGHT, right, count);
    }
    public double x(double guiX) { return (guiX - left) / scale; }
    public double y(double guiY) { return guiY / scale; }
    public boolean inToolbar(double x, double y) {
        return y < BOTTOM_HEIGHT || y >= topY;
    }
    public Box shovel() { return new Box(88, 27, 64, 61).offset(topY); }
    public Box ability(int index) { return new Box(172 + index * 57, 24, 43, 64).offset(topY); }
    public Box buy(int index) { return new Box(170 + index * 57, 3, 47, 20).offset(topY); }
    public Box abilityPrevious() { return new Box(topRight - 74F, 44, 25, 25).offset(topY); }
    public Box abilityNext() { return new Box(topRight - 38F, 44, 25, 25).offset(topY); }
    public Box tray(int index) { return new Box(24 + index * 60, 36, 43, 54); }
    public Box fuse() { return new Box(18, 4, 134, 26); }
    public Box clear() { return new Box(159, 4, 95, 26); }
    public Box trayNext() { return new Box(261, 4, 27, 26); }
    public Box collect() { return new Box(295, 4, 106, 26); }
    public Box seed(int index) { return new Box(426 + index * 60, 33, 43, 60); }
    public Box recycle(int index) { return new Box(424 + index * 60, 4, 47, 23); }
    public Box seedPrevious() { return new Box(734, 62, 23, 25); }
    public Box seedNext() { return new Box(764, 62, 23, 25); }
    public Box held() { return new Box(835, 33, 43, 60); }
    public Box recycleHeld() { return new Box(813, 4, 111, 23); }
}
