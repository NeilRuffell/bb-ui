package dev.q25.trackpad;

/**
 * Layout policy for the single native Quickstep RecentsView. No Android,
 * Xposed or task-service dependency; suitable for a direct ROM port.
 *
 * The pager uses native horizontal page offsets, with sixteen icon TaskViews
 * on each page. No overlay, secondary view tree or duplicate task model.
 */
final class RecentsIconGridLayout {
    static final int COLUMNS = 4;
    static final int ROWS = 4;
    static final int PAGE_SIZE = COLUMNS * ROWS;

    final int pageWidth;
    final int cellWidth;
    final int cellHeight;
    final int gap;
    final int top;
    final int tasks;
    final int pageCount;

    private RecentsIconGridLayout(int width, int tileWidth, int tileHeight,
            int spacing, int topPadding, int taskCount) {
        pageWidth = width;
        cellWidth = tileWidth;
        cellHeight = tileHeight;
        gap = spacing;
        top = topPadding;
        tasks = taskCount;
        pageCount = (taskCount + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    static RecentsIconGridLayout calculate(int width, int height,
            float density, int statusBarOverlap, int taskCount) {
        if (width <= 0 || height <= 0 || density <= 0 || taskCount < 0) return null;
        // Square-ish icon hit targets rather than full-screen thumbnail cards.
        // The native task is still one cell, retaining its original task ID
        // and click action. Extra tasks continue on the native pager.
        int spacing = Math.max(2, Math.round(8 * density));
        int topPadding = Math.max(0, statusBarOverlap) + Math.round(18 * density);
        int tileWidth = (width - spacing * (COLUMNS + 1)) / COLUMNS;
        int tileHeight = Math.min(tileWidth,
                (height - topPadding - spacing * (ROWS + 1)) / ROWS);
        if (tileWidth < Math.round(40 * density)
                || tileHeight < Math.round(40 * density)) return null;
        return new RecentsIconGridLayout(width, tileWidth, tileHeight,
                spacing, topPadding, taskCount);
    }

    int page(int taskIndex) {
        return taskIndex / PAGE_SIZE;
    }

    int x(int taskIndex) {
        return page(taskIndex) * pageWidth
                + gap + (taskIndex % COLUMNS) * (cellWidth + gap);
    }

    int y(int taskIndex) {
        int row = (taskIndex % PAGE_SIZE) / COLUMNS;
        return top + row * (cellHeight + gap);
    }

    int scrollForTask(int taskIndex) {
        return page(taskIndex) * pageWidth;
    }

    int lastTaskScroll() {
        return Math.max(0, pageCount - 1) * pageWidth;
    }

    int nextIndex(int current, int direction) {
        if (tasks == 0) return -1;
        if (current < 0 || current >= tasks) current = 0;
        int candidate = current;
        switch (direction) {
            case 0: // up
                candidate -= COLUMNS;
                break;
            case 1: // down
                candidate += COLUMNS;
                break;
            case 2: // left
                candidate--;
                break;
            case 3: // right
                candidate++;
                break;
            default: return current;
        }
        if (candidate < 0 || candidate >= tasks) return current;
        // Directional navigation respects four columns, including page edges.
        if (direction == 2 && current % COLUMNS == 0) return current;
        if (direction == 3 && current % COLUMNS == COLUMNS - 1) return current;
        return candidate;
    }
}
