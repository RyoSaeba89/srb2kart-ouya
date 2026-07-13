package org.srb2kart.ouya;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * First-launch installer with a visible progress bar.
 *
 * The bundled data is ~490MB and takes minutes to copy out of the APK on the
 * Ouya; doing that synchronously in the game activity's onCreate (the old
 * behaviour) left a black screen with no feedback. This activity is the
 * launcher instead: if the data is already in place it forwards to the game
 * immediately, otherwise it shows progress while a background thread
 * extracts, then starts the game.
 */
public class InstallActivity extends Activity {
    private ProgressBar progressBar;
    private TextView progressText;
    private long lastUiUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (AssetExporter.isExported(this)) {
            launchGame();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        new Thread(new Runnable() {
            @Override
            public void run() {
                AssetExporter.export(InstallActivity.this, new AssetExporter.Listener() {
                    @Override
                    public void onProgress(long copied, long total, String file) {
                        postProgress(copied, total, file);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        launchGame();
                    }
                });
            }
        }, "srb2kart-install").start();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        // generous padding keeps everything inside the TV overscan area
        int pad = (int) (48 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("SRB2Kart");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Installing game data (first run only)...");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = pad / 4;
        root.addView(subtitle, subLp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setIndeterminate(false);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = pad / 2;
        root.addView(progressBar, barLp);

        progressText = new TextView(this);
        progressText.setText("Preparing...");
        progressText.setTextColor(Color.LTGRAY);
        progressText.setTextSize(16);
        progressText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams txtLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        txtLp.topMargin = pad / 4;
        root.addView(progressText, txtLp);

        setContentView(root);
    }

    /** Called from the copy thread for every chunk; throttle the UI posts. */
    private void postProgress(final long copied, final long total, final String file) {
        long now = System.currentTimeMillis();
        if (now - lastUiUpdate < 100 && copied != total)
            return;
        lastUiUpdate = now;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progressBar == null || progressText == null)
                    return;
                if (total > 0) {
                    progressBar.setProgress((int) (copied * 1000 / total));
                    progressText.setText(String.format("%s  -  %d / %d MB",
                            file, copied / (1024 * 1024), total / (1024 * 1024)));
                } else {
                    progressBar.setIndeterminate(true);
                    progressText.setText(String.format("%s  -  %d MB",
                            file, copied / (1024 * 1024)));
                }
            }
        });
    }

    private void launchGame() {
        startActivity(new Intent(this, SRB2KartActivity.class));
        finish();
    }
}
