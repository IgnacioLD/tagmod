package org.tagmod.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shows the panel full screen with fresh data, so it can be checked without putting the device to
 * sleep. Any key or tap goes back.
 */
public class PanelPreviewActivity extends Activity {
    private Bitmap bitmap;
    private View view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new View(this) {
            private final Paint paint = new Paint();

            @Override
            protected void onDraw(Canvas c) {
                c.drawColor(Color.WHITE);
                if (bitmap != null) {
                    c.drawBitmap(bitmap, 0, 0, null);
                } else {
                    paint.setAntiAlias(true);
                    paint.setTextSize(32);
                    paint.setTextAlign(Paint.Align.CENTER);
                    c.drawText("Actualizando el panel…", getWidth() / 2f, getHeight() / 2f, paint);
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) finish();
                return true;
            }
        };
        setContentView(view);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    PanelConfig config = PanelConfig.load(PanelPreviewActivity.this);
                    b = new PanelRenderer().render(PanelSource.collect(PanelPreviewActivity.this, config));
                } catch (Exception e) {
                    android.util.Log.e("tagmod", "preview", e);
                }
                final Bitmap result = b;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        bitmap = result;
                        view.invalidate();
                    }
                });
            }
        }, "panel-preview").start();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        finish();
        return true;
    }
}
