package org.tagmod.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Paged, high-contrast list drawn directly on a canvas. Rows never scroll: the list moves a whole
 * page at a time, which is what e-ink handles best. Dirty-rect invalidate() limits the e-ink
 * update region on API 19.
 */
final class LibraryView extends View {
    interface Row {
        String title();
        String subtitle();
    }

    private static final int HEADER = 76;
    private static final int FOOTER = 96;
    private static final int ROW = 108;
    private static final int PAD = 24;

    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private List<? extends Row> rows = Collections.emptyList();
    private int selected;
    private String message;
    private int battery = -1;
    private boolean charging;

    LibraryView(Context context) {
        super(context);
        setBackgroundColor(Color.WHITE);
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextSize(32);
        titlePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        subPaint.setColor(Color.BLACK);
        subPaint.setTextSize(23);
        subPaint.setTypeface(Typeface.SANS_SERIF);
        headerPaint.setColor(Color.BLACK);
        headerPaint.setTextSize(36);
        headerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        smallPaint.setColor(Color.BLACK);
        smallPaint.setTextSize(22);
        smallPaint.setTypeface(Typeface.SANS_SERIF);
        fill.setColor(Color.BLACK);
    }

    void setRows(List<? extends Row> rows, int selected) {
        this.rows = rows;
        this.selected = rows.isEmpty() ? 0 : Math.max(0, Math.min(selected, rows.size() - 1));
        invalidate();
    }

    void setMessage(String message) {
        this.message = message;
        invalidate();
    }

    void setBattery(int percent, boolean charging) {
        if (percent == battery && charging == this.charging) return;
        battery = percent;
        this.charging = charging;
        invalidate(0, 0, getWidth(), HEADER);
    }

    void tick() {
        invalidate(0, 0, getWidth(), HEADER);
    }

    int selected() {
        return selected;
    }

    int perPage() {
        return Math.max(1, (getHeight() - HEADER - FOOTER) / ROW);
    }

    void select(int index) {
        if (rows.isEmpty()) return;
        int n = rows.size();
        index = ((index % n) + n) % n;
        if (index == selected) return;
        int per = perPage();
        if (index / per != selected / per) {
            selected = index;
            invalidate();
        } else {
            invalidateRow(selected);
            selected = index;
            invalidateRow(selected);
        }
    }

    /** Row index at the given y coordinate, or -1. */
    int rowAt(float y) {
        if (y < HEADER || y >= getHeight() - FOOTER) return -1;
        int index = (selected / perPage()) * perPage() + (int) ((y - HEADER) / ROW);
        return index < rows.size() ? index : -1;
    }

    private void invalidateRow(int index) {
        int top = HEADER + (index % perPage()) * ROW;
        invalidate(0, top, getWidth(), top + ROW);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();

        // Header: title, battery and clock.
        float base = HEADER / 2f + 13;
        c.drawText("Biblioteca", PAD, base, headerPaint);
        String status = clock.format(new Date());
        if (battery >= 0) status = battery + "%" + (charging ? "+" : "") + "   " + status;
        c.drawText(status, w - PAD - smallPaint.measureText(status), base - 4, smallPaint);
        c.drawRect(0, HEADER - 3, w, HEADER, fill);

        int per = perPage();
        int first = (selected / per) * per;

        if (message != null || rows.isEmpty()) {
            String text = message != null ? message : "No hay libros";
            c.drawText(text, PAD, HEADER + ROW / 2f + 10, titlePaint);
        } else {
            for (int i = first; i < Math.min(rows.size(), first + per); i++) {
                drawRow(c, rows.get(i), HEADER + (i - first) * ROW, w, i == selected);
            }
        }

        // Footer: gesture help and page number.
        int top = h - FOOTER;
        c.drawRect(0, top, w, top + 3, fill);
        c.drawText("Pulsar: siguiente    Doble: anterior", PAD, top + 38, smallPaint);
        c.drawText("Mantener: abrir    Mantener 3 s: dormir", PAD, top + 74, smallPaint);
        if (!rows.isEmpty()) {
            int pages = (rows.size() + per - 1) / per;
            String page = (first / per + 1) + "/" + pages;
            c.drawText(page, w - PAD - smallPaint.measureText(page), top + 56, smallPaint);
        }
    }

    private void drawRow(Canvas c, Row row, int top, int w, boolean isSelected) {
        int fg = isSelected ? Color.WHITE : Color.BLACK;
        if (isSelected) c.drawRect(0, top, w, top + ROW, fill);
        titlePaint.setColor(fg);
        subPaint.setColor(fg);
        float avail = w - 2 * PAD;
        String sub = row.subtitle();
        boolean hasSub = sub != null && sub.length() > 0;
        float titleBase = hasSub ? top + 48 : top + ROW / 2f + 11;
        c.drawText(TextUtils.ellipsize(row.title(), titlePaint, avail, TextUtils.TruncateAt.END).toString(),
                PAD, titleBase, titlePaint);
        if (hasSub) {
            c.drawText(TextUtils.ellipsize(sub, subPaint, avail, TextUtils.TruncateAt.END).toString(),
                    PAD, top + 84, subPaint);
        }
        if (!isSelected) {
            fill.setColor(Color.LTGRAY);
            c.drawRect(PAD, top + ROW - 1, w - PAD, top + ROW, fill);
            fill.setColor(Color.BLACK);
        }
        titlePaint.setColor(Color.BLACK);
        subPaint.setColor(Color.BLACK);
    }
}
