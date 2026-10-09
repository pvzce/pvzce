package com.pvzce.client.mechanic;

/** One reference layout for drawing and picking both toolbars, independent of a live window. */
public record FusionLayout(float scale, float left, float topY, float topRight, int abilitiesPerPage) {
    public static final float WIDTH = 960F;
    public static final float HEIGHT = 540F;
    public static final float TOP_HEIGHT = 72F;
    public static final float WORKSHOP_LEFT = 740F;
    public static final float FOOTER_HEIGHT = 18F;
    public static final int ABILITIES_PER_PAGE = 8;
    public static final int SEEDS_PER_PAGE = 3;
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
        float scale = Math.max(0.001F, Math.min(width / WIDTH, height / HEIGHT));
        float left = (width - WIDTH * scale) / 2F;
        float right = Math.min(712F, (toolbarRight - left) / scale);
        int count = Math.max(1, Math.min(ABILITIES_PER_PAGE, (int) ((right - 172F - 78F) / 57F)));
        return new FusionLayout(scale, left, height / scale - TOP_HEIGHT, right, count);
    }
    public double x(double guiX) { return (guiX - left) / scale; }
    public double y(double guiY) { return guiY / scale; }
    public boolean inToolbar(double x, double y) {
        return y < FOOTER_HEIGHT || y >= topY || x >= WORKSHOP_LEFT;
    }
    public Box shovel() { return new Box(88, 8, 56, 56).offset(topY); }
    public Box ability(int index) { return new Box(172 + index * 57, 7, 43, 44).offset(topY); }
    public Box buy(int index) { return new Box(170 + index * 57, 54, 47, 15).offset(topY); }
    public Box abilityPrevious() { return new Box(topRight - 74F, 27, 25, 25).offset(topY); }
    public Box abilityNext() { return new Box(topRight - 38F, 27, 25, 25).offset(topY); }
    public Box tray(int index) { return new Box(752 + index % 3 * 60, 370 - index / 3 * 60, 43, 54); }
    public Box fuse() { return new Box(752, 278, 112, 26); }
    public Box clear() { return new Box(871, 278, 72, 26); }
    public Box trayNext() { return new Box(916, 427, 27, 26); }
    public Box collect() { return new Box(752, 246, 191, 26); }
    public Box seed(int index) { return new Box(752 + index * 60, 140, 43, 60); }
    public Box recycle(int index) { return new Box(750 + index * 60, 110, 47, 23); }
    public Box seedPrevious() { return new Box(752, 206, 23, 25); }
    public Box seedNext() { return new Box(920, 206, 23, 25); }
    public Box held() { return new Box(752, 35, 43, 60); }
    public Box recycleHeld() { return new Box(815, 39, 111, 26); }
}
