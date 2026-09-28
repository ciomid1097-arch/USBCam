package com.usb.cam;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Control screen on the phone: pick camera + size, start/stop the streaming service.
 * The app auto-starts streaming when opened. It also accepts extras, which lets you
 * control it remotely:
 *   adb shell am start -n com.usb.cam/.MainActivity --ei facing 1 --ei size_index 2
 * (facing: 0=back 1=front; autostart=false to open without starting)
 */
public class MainActivity extends Activity {

    private static final String TAG = "USBCam";
    /** App version shown to users; compare against the latest GitHub release tag. */
    private static final String APP_VERSION = "1.1.1";
    private static final String GITHUB_OWNER = "ciomid1097-arch";
    private static final String GITHUB_REPO = "USBCam";
    private static final String RELEASES_PAGE =
            "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
    private static final String RELEASES_API =
            "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    private Spinner sizeSpinner;
    private CheckBox frontCheck;
    private Button toggleButton;
    private TextView statusText;
    private final AtomicBoolean updateCheckRunning = new AtomicBoolean(false);
    /** Last settings actually sent to the service; guards the initial spinner fire. */
    private int lastAppliedFacing = StreamConfig.FACING_BACK;
    private int lastAppliedSizeIdx = StreamConfig.DEFAULT_SIZE_INDEX;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        statusText = new TextView(this);
        statusText.setGravity(Gravity.CENTER);
        statusText.setTextSize(16);
        root.addView(statusText);

        Button switchCam = new Button(this);
        switchCam.setText("🔄 Switch camera (front / back)");
        switchCam.setOnClickListener(v -> frontCheck.toggle()); // listener applies instantly
        root.addView(switchCam, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        frontCheck = new CheckBox(this);
        frontCheck.setText("Use front camera");
        frontCheck.setPadding(0, 32, 0, 0);
        // Instant camera switch while streaming (ignore the initial programmatic set).
        frontCheck.setOnCheckedChangeListener((b, checked) -> {
            int want = checked ? StreamConfig.FACING_FRONT : StreamConfig.FACING_BACK;
            if (JpegStreamService.isServiceRunning() && want != lastAppliedFacing) {
                startWithCurrentSettings();
            } else {
                updateStatus();
            }
        });
        root.addView(frontCheck);

        String[] labels = new String[StreamConfig.SIZES.length];
        for (int i = 0; i < labels.length; i++)
            labels[i] = StreamConfig.SIZES[i][0] + " x " + StreamConfig.SIZES[i][1];
        sizeSpinner = new Spinner(this);
        sizeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        sizeSpinner.setSelection(StreamConfig.DEFAULT_SIZE_INDEX);
        // Instant quality switch: applying the selection reconfigures the running
        // service in place — no stop/start needed.
        sizeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 android.view.View view, int pos, long id) {
                // Fires once at layout time; only react to real user changes.
                if (JpegStreamService.isServiceRunning() && pos != lastAppliedSizeIdx) {
                    startWithCurrentSettings();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        root.addView(sizeSpinner);

        toggleButton = new Button(this);
        toggleButton.setText(JpegStreamService.isServiceRunning() ? "Stop streaming" : "Start streaming");
        toggleButton.setOnClickListener(v -> toggle());
        root.addView(toggleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        TextView help = new TextView(this);
        help.setText("1. Connect phone by USB (file transfer mode ok)\n"
                + "2. Run the PC app; it sets up adb itself\n"
                + "3. Opening the app starts streaming");
        help.setPadding(0, 48, 0, 0);
        root.addView(help);

        // Footer: developer contact (kept in English, tappable links).
        TextView contactEmail = new TextView(this);
        contactEmail.setText("Developer: workspikestudio@gmail.com");
        contactEmail.setTextSize(13);
        contactEmail.setTextColor(0xFF8A8A8A);
        contactEmail.setGravity(Gravity.CENTER);
        contactEmail.setPadding(0, 40, 0, 0);
        Linkify.addLinks(contactEmail, Linkify.EMAIL_ADDRESSES);
        contactEmail.setMovementMethod(LinkMovementMethod.getInstance());
        root.addView(contactEmail);

        TextView contactTg = new TextView(this);
        contactTg.setText("Telegram: @spike_c");
        contactTg.setTextSize(13);
        contactTg.setTextColor(0xFF8A8A8A);
        contactTg.setGravity(Gravity.CENTER);
        contactTg.setPadding(0, 8, 0, 0);
        contactTg.setOnClickListener(v -> openUrl("https://t.me/spike_c"));
        root.addView(contactTg);

        setContentView(root);

        boolean explicitSettings = applyIntent(getIntent());

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else if (explicitSettings) {
            // Remote command: start or reconfigure the running service in place.
            startWithCurrentSettings();
        } else if (getIntent() == null || getIntent().getBooleanExtra("autostart", true)) {
            if (!JpegStreamService.isServiceRunning()) startWithCurrentSettings();
        }

        checkForUpdate();
    }

    /** Applies camera/size extras from an intent; true if explicit settings were given. */
    private boolean applyIntent(Intent in) {
        if (in == null) return false;
        int facing = in.getIntExtra("facing", -1);
        int sizeIdx = in.getIntExtra("size_index", -1);
        if (facing == StreamConfig.FACING_FRONT) frontCheck.setChecked(true);
        else if (facing == StreamConfig.FACING_BACK) frontCheck.setChecked(false);
        if (sizeIdx >= 0) sizeSpinner.setSelection(
                Math.min(sizeIdx, StreamConfig.SIZES.length - 1));
        return in.hasExtra("facing") || in.hasExtra("size_index");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (applyIntent(intent)
                && checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
            startWithCurrentSettings();
        }
    }

    /** Starts the service with the UI's current camera + size selection. */
    private void startWithCurrentSettings() {
        lastAppliedFacing = frontCheck.isChecked()
                ? StreamConfig.FACING_FRONT : StreamConfig.FACING_BACK;
        lastAppliedSizeIdx = sizeSpinner.getSelectedItemPosition();
        Intent i = new Intent(this, JpegStreamService.class);
        i.putExtra(JpegStreamService.EXTRA_FACING,
                frontCheck.isChecked() ? StreamConfig.FACING_FRONT : StreamConfig.FACING_BACK);
        i.putExtra(JpegStreamService.EXTRA_SIZE_INDEX, sizeSpinner.getSelectedItemPosition());
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        toggleButton.postDelayed(this::updateStatus, 400);
    }

    private void stopStreaming() {
        Intent stop = new Intent(this, JpegStreamService.class);
        stop.putExtra("stop", true);
        startService(stop);
        toggleButton.postDelayed(this::updateStatus, 400);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean running = JpegStreamService.isServiceRunning();
        toggleButton.setText(running ? "Stop streaming" : "Start streaming");
        statusText.setText(running ? "Streaming…" : "Stopped");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (getIntent() == null || getIntent().getBooleanExtra("autostart", true)) {
                startWithCurrentSettings();
            }
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
        }
    }

    private void toggle() {
        if (JpegStreamService.isServiceRunning()) {
            stopStreaming();
            Toast.makeText(this, "Stopping…", Toast.LENGTH_SHORT).show();
        } else {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 2);
                Toast.makeText(this, "Grant camera permission first", Toast.LENGTH_LONG).show();
                return;
            }
            startWithCurrentSettings();
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Log.w(TAG, "no browser for " + url);
        }
    }

    // ---------- update check (silent on any failure) ----------

    /**
     * Checks GitHub for a newer release in the background. If one exists, shows a
     * dialog with a Download button that opens the releases page. Never blocks or
     * disturbs the user: any network/parse error is silently ignored.
     */
    private void checkForUpdate() {
        if (GITHUB_OWNER.equals("REPLACE_OWNER")) return; // repo not configured yet
        if (!updateCheckRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "USBCam-Android");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                for (String line; (line = r.readLine()) != null; ) sb.append(line);
                r.close();
                String tag = extractJsonString(sb.toString(), "tag_name");
                if (TextUtils.isEmpty(tag)) throw new Exception("no tag_name");
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                if (isNewerVersion(latest, APP_VERSION)) {
                    String message = "Version " + latest + " is available.\nYou are using "
                            + APP_VERSION + ".";
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("Update available")
                                .setMessage(message)
                                .setPositiveButton("Download",
                                        (DialogInterface d, int w) -> openUrl(RELEASES_PAGE))
                                .setNegativeButton("Later", null)
                                .show();
                    });
                }
            } catch (Exception e) {
                Log.d(TAG, "update check skipped: " + e);
            } finally {
                updateCheckRunning.set(false);
            }
        }, "update-check").start();
    }

    /** Pulls the value of "key":"…" out of a (small) JSON body without a parser. */
    private static String extractJsonString(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = json.indexOf(':', k + key.length() + 2);
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        return (colon < 0 || q2 < 0) ? null : json.substring(q1 + 1, q2);
    }

    /** True if remote looks newer than local (semver-ish: compares numeric parts). */
    private static boolean isNewerVersion(String remote, String local) {
        try {
            String[] a = remote.split("[.\\-]");
            String[] b = local.split("[.\\-]");
            int n = Math.max(a.length, b.length);
            for (int i = 0; i < n; i++) {
                int x = i < a.length ? Integer.parseInt(a[i].replaceAll("[^0-9]", "")) : 0;
                int y = i < b.length ? Integer.parseInt(b[i].replaceAll("[^0-9]", "")) : 0;
                if (x != y) return x > y;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
