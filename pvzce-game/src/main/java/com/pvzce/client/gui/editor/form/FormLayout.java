package com.pvzce.client.gui.editor.form;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.common.util.MathUtil;

/**
 * Row geometry for a form page: labelled rows of controls, in columns when there are too many
 * to stack.
 *
 * <p>Computed from the area instead of hard-coded, because the editor has to work from 720p up
 * and every page used to invent its own arithmetic - which is why four labelled fields did not
 * fit at 720p and why a slider once sat half outside its panel. A form that has more fields
 * than fit in one column gets a second one rather than losing them off the bottom: the rules
 * page has one field per registered game rule, so its length is not the page's to choose.
 */
public final class FormLayout {
    private static final int MIN_ROW_HEIGHT = 20;
    private static final int MAX_ROW_HEIGHT = 40;
    private static final int MIN_LABEL_WIDTH = 88;
    private static final int MAX_LABEL_WIDTH = 200;
    private static final int MAX_COLUMNS = 3;

    /**
     * One row: its label, its full rectangle and the part left for controls.
     *
     * <p>The control rectangle is computed once, when the row is made, so a field never has to
     * know how the layout arrived at it.
     */
    public record Row(int index, String label, EditorContext.Rect bounds, EditorContext.Rect control) {
    }

    private final EditorContext.Rect area;
    private final int rowHeight;
    private final int labelWidth;
    private final int columns;
    private final int rowsPerColumn;
    private final int columnWidth;

    public FormLayout(EditorContext.Rect area, int rows) {
        this.area = area;
        int count = Math.max(1, rows);
        // One row for the heading, then the fields.
        int usable = Math.max(1, area.height() - MIN_ROW_HEIGHT);
        this.columns = Math.min(MAX_COLUMNS, Math.max(1, MathUtil.ceilDiv(count, Math.max(1, usable / MAX_ROW_HEIGHT))));
        this.rowsPerColumn = MathUtil.ceilDiv(count, columns);
        this.rowHeight = MathUtil.clamp(usable / Math.max(1, rowsPerColumn + 1), MIN_ROW_HEIGHT, MAX_ROW_HEIGHT);
        this.columnWidth = Math.max(120, (area.width() - 12) / columns);
        this.labelWidth = MathUtil.clamp(columnWidth / 3, MIN_LABEL_WIDTH, MAX_LABEL_WIDTH);
    }

    /** Where the page's heading goes. */
    public Row heading(String text) {
        EditorContext.Rect bounds = new EditorContext.Rect(area.x() + 6, area.y() + area.height() - rowHeight,
                Math.max(60, area.width() - 12), rowHeight);
        return new Row(-1, text, bounds, bounds);
    }

    public Row row(int index, String label) {
        int column = index / rowsPerColumn;
        int withinColumn = index % rowsPerColumn;
        int x = area.x() + 6 + column * columnWidth;
        int y = area.y() + area.height() - rowHeight * (withinColumn + 2);
        EditorContext.Rect bounds = new EditorContext.Rect(x, y, Math.max(60, columnWidth - 8), rowHeight);
        EditorContext.Rect control = new EditorContext.Rect(bounds.x() + labelWidth, bounds.y() + 3,
                Math.max(40, bounds.width() - labelWidth - 4), Math.max(16, rowHeight - 6));
        return new Row(index, label, bounds, control);
    }

    /** True when even minimum-height rows do not fit; the only case worth warning about. */
    public boolean fits(int rows) {
        return rowsPerColumn == 0 || rowHeight > MIN_ROW_HEIGHT || area.height() >= MIN_ROW_HEIGHT * (rows + 1);
    }

    public void drawHeading(PvzceClient client, Row row) {
        client.fonts().body().draw(row.label(), row.bounds().x() + 2, row.bounds().y() + rowHeight / 2F - 5F,
                0.82F, 1F, 1F, 1F, 1F);
    }

    public void drawLabel(PvzceClient client, Row row) {
        client.fonts().body().draw(row.label(), row.bounds().x() + 2, row.bounds().y() + rowHeight / 2F - 4F,
                0.72F, 0.88F, 0.92F, 0.96F, 1F);
    }

}
