package com.pvzce.client.gui.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level grid's paging.
 *
 * <p>The bug this guards against: with 13 levels in four columns and two visible rows, the old
 * arrows moved a scroll offset by two rows and clamped it to {@code totalRows - visibleRows}
 * (4 - 2 = 2), so the "pages" were rows 0-1, 2-3 and then nothing - and a scroll of one row
 * mixed two pages together. Two consecutive pages must never show the same row.
 */
class ListPagingTest {
    /** Rows visible on each page, in order, walking forward to the end. */
    private static List<Integer> walkPages(ListPaging start) {
        ListPaging paging = start;
        List<Integer> rowsPerPage = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        while (true) {
            for (int row = paging.firstRow(); row < paging.firstRow() + paging.visibleRows(); row++) {
                assertTrue(seen.add(row), "row " + row + " appeared on two pages");
            }
            rowsPerPage.add(paging.visibleRows());
            if (!paging.hasNext()) {
                break;
            }
            ListPaging next = paging.turn(1);
            assertTrue(next.page() > paging.page(), "turning forward must advance the page");
            paging = next;
        }
        return rowsPerPage;
    }

    @Test
    void anExactMultipleHasNoEmptyTailPage() {
        ListPaging paging = ListPaging.of(6, 2);
        assertEquals(3, paging.pageCount());
        assertEquals(List.of(2, 2, 2), walkPages(paging));
        assertFalse(ListPaging.of(6, 2).withFirstRow(4).hasNext());
    }

    @Test
    void aSinglePageHasNoArrows() {
        ListPaging paging = ListPaging.of(2, 4);
        assertEquals(1, paging.pageCount());
        assertFalse(paging.hasPrevious());
        assertFalse(paging.hasNext());
        assertEquals(2, paging.visibleRows(), "the page shows the rows that exist");
    }

    @Test
    void noRowsIsStillOnePageAndNeverZeroOfZero() {
        ListPaging paging = ListPaging.of(0, 3);
        assertEquals(1, paging.pageCount());
        assertEquals(1, paging.pageNumber());
        assertEquals(0, paging.visibleRows());
        assertFalse(paging.hasPrevious());
        assertFalse(paging.hasNext());
    }

    @Test
    void aZeroPageSizeStillAdvances() {
        // A window too short for one card row must not make the arrows dead.
        ListPaging paging = ListPaging.of(4, 0);
        assertEquals(1, paging.pageSize());
        assertTrue(paging.hasNext());
        assertEquals(1, paging.turn(1).page());
    }

    @Test
    void turningBackFromTheLastPageReachesTheOneBeforeIt() {
        ListPaging last = ListPaging.of(13, 4).turn(99);
        assertEquals(3, last.page(), "13 rows at 4 per page is 4 pages (rows 0-3, 4-7, 8-11, 12)");
        assertFalse(last.hasNext());
        assertEquals(2, last.turn(-1).page(), "one page back from the last page");
        assertEquals(0, last.turn(-99).page(), "and the far end clamps to the first");
        assertFalse(last.turn(-99).hasPrevious());
    }

    @Test
    void resizingSnapsBackOntoAPageBoundary() {
        // The stored row survives a resize, which can leave it mid-page; an off-boundary row
        // is exactly what overlapped two pages.
        ListPaging resized = ListPaging.of(10, 3).withFirstRow(4);
        assertEquals(3, resized.firstRow(), "row 4 is not a multiple of the page size");
        assertEquals(1, resized.page());
    }

    @Test
    void aStoredRowBeyondTheEndClampsToTheLastPage() {
        ListPaging clamped = ListPaging.of(4, 2).withFirstRow(99);
        assertEquals(2, clamped.firstRow());
        assertEquals(1, clamped.page());
        assertFalse(clamped.hasNext());
    }

    @Test
    void showsRowDescribesThePage() {
        ListPaging paging = ListPaging.of(9, 3).turn(1);
        assertEquals(1, paging.page());
        for (int row = 3; row < 6; row++) {
            assertTrue(paging.showsRow(row), "row " + row + " is on page 2");
        }
        assertFalse(paging.showsRow(2));
        assertFalse(paging.showsRow(6));
    }

    /**
     * Every row is on exactly one page, for every row count and page size.
     *
     * <p>This is the property the two bugs violated: the overlap bug showed a row twice, and
     * the floored page count left trailing rows on no page at all.
     */
    @Test
    void everyRowIsReachableOnExactlyOnePage() {
        for (int totalRows = 0; totalRows <= 24; totalRows++) {
            for (int pageSize = 1; pageSize <= 6; pageSize++) {
                ListPaging paging = ListPaging.of(totalRows, pageSize);
                assertEquals(Math.max(1, (totalRows + pageSize - 1) / pageSize), paging.pageCount(),
                        "pages for " + totalRows + " rows at " + pageSize + " per page");
                List<Integer> rowsPerPage = walkPages(paging);
                int seen = rowsPerPage.stream().mapToInt(Integer::intValue).sum();
                assertEquals(totalRows, seen,
                        "rows shown across the pages of " + totalRows + " rows at " + pageSize + " per page");
                if (totalRows > 0) {
                    assertTrue(rowsPerPage.stream().allMatch(rows -> rows > 0),
                            "no page may be empty: " + rowsPerPage);
                }
            }
        }
    }
}
