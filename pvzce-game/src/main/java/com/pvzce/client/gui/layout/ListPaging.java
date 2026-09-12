package com.pvzce.client.gui.layout;

/**
 * Which rows of a grid a page shows, and how the arrows move between pages.
 *
 * <p>Split out of the level list because the arithmetic is where the bug was and it is the
 * only part of that screen a test can reach without a window. The list used to keep a scroll
 * offset and move it by {@code visibleRows} per click, which is only a page when the row
 * count is an exact multiple: with 13 levels in four columns and two visible rows the pages
 * became rows 0-1, 2-3, 4-5, and one click showed rows 4-5 after 2-3 - the same cards
 * appearing on two different "pages".
 *
 * <p>Two rules make a page a page: the first row is always a multiple of the page size, and
 * the last page is clamped to {@code (pageCount - 1) * pageSize} rather than to
 * {@code totalRows - pageSize}, so it never repeats rows the previous page already showed.
 */
public final class ListPaging {
    private final int totalRows;
    private final int pageSize;
    private final int firstRow;

    private ListPaging(int totalRows, int pageSize, int firstRow) {
        this.totalRows = Math.max(0, totalRows);
        this.pageSize = Math.max(1, pageSize);
        this.firstRow = firstRow;
    }

    /** A pager starting on the first page. */
    public static ListPaging of(int totalRows, int pageSize) {
        return new ListPaging(totalRows, pageSize, 0);
    }

    /** This pager moved to {@code firstRow}, clamped to a whole page. */
    public ListPaging withFirstRow(int firstRow) {
        return new ListPaging(totalRows, pageSize, clampToPage(firstRow));
    }

    /** This pager turned by {@code delta} pages. */
    public ListPaging turn(int delta) {
        return withFirstRow(firstRow + delta * pageSize);
    }

    /**
     * The nearest whole-page first row at or after {@code firstRow}.
     *
     * <p>Returns {@code 0} when a page is already the whole list, and never a row that has
     * no cards in it.
     */
    private int clampToPage(int firstRow) {
        if (totalRows <= 0) {
            return 0;
        }
        int pages = pageCount();
        // Snap down to a page boundary, then into range: a resize can leave the stored row
        // mid-page, and off-boundary rows are what produced the overlap.
        int snapped = Math.max(0, firstRow) / pageSize * pageSize;
        return Math.min(pages - 1, snapped / pageSize) * pageSize;
    }

    /** Rows per page; at least one, so a tiny window still advances. */
    public int pageSize() {
        return pageSize;
    }

    public int totalRows() {
        return totalRows;
    }

    /**
     * Number of pages, always at least one so the indicator never reads "0/0".
     *
     * <p>Rounded <em>up</em>: a page holds {@code pageSize} rows and the last one shows
     * whatever is left. Flooring instead dropped that trailing page entirely, so with more
     * rows than fit on one page the arrows went dead and the leftover levels could not be
     * reached at all - five rows at four per page was "1/1" with row 4 off the grid.
     *
     * <p>A short last page still cannot repeat a row: it starts at a page boundary, because
     * {@link #clampToPage} snaps the first row to a multiple of the page size.
     */
    public int pageCount() {
        return Math.max(1, (totalRows + pageSize - 1) / pageSize);
    }

    /** Zero-based first row of the page. */
    public int firstRow() {
        return firstRow;
    }

    /** Zero-based index of the current page. */
    public int page() {
        return firstRow / pageSize;
    }

    /** One-based page number, for the "3/4" indicator. */
    public int pageNumber() {
        return page() + 1;
    }

    public boolean hasPrevious() {
        return firstRow > 0;
    }

    public boolean hasNext() {
        return page() < pageCount() - 1;
    }

    /**
     * How many rows the page shows.
     *
     * <p>A full page whenever the rows last that long, which is what keeps two consecutive
     * pages from overlapping.
     */
    public int visibleRows() {
        return Math.max(0, Math.min(pageSize, totalRows - firstRow));
    }

    /** True when the row at {@code rowIndex} is on this page. */
    public boolean showsRow(int rowIndex) {
        return rowIndex >= firstRow && rowIndex < firstRow + visibleRows();
    }
}
