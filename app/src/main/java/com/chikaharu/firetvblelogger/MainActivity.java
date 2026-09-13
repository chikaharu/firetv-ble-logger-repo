package com.chikaharu.firetvblelogger;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;
    private static final int APPLE_COMPANY_ID = 0x004C;
    private static final String PREFS = "ble_logger_prefs";
    private static final String PREF_NEXT_EXPERIMENT = "next_experiment";

    private BluetoothLeScanner scanner;
    private boolean scanning;
    private BufferedWriter csvWriter;
    private BufferedWriter eventWriter;
    private File experimentDir;

    private long totalPackets;
    private long applePackets;
    private long type02;
    private long type0D;
    private long type0E;
    private long type10;
    private long type12;
    private long type16;
    private long rowsSinceFlush;
    private int strongestAppleRssi = -127;
    private int markCount;
    private int currentExperimentNo;
    private long startedAtElapsed;
    private final Set<String> appleDevices = Collections.synchronizedSet(new HashSet<>());

    private TextView statusValue;
    private TextView experimentValue;
    private TextView elapsedValue;
    private TextView pathValue;
    private TextView totalValue;
    private TextView appleValue;
    private TextView deviceValue;
    private TextView rssiValue;
    private TextView tetherTargetValue;
    private TextView tetherSourceValue;
    private TextView continuityValue;
    private TextView markValue;
    private Button startButton;
    private Button markButton;
    private Button stopButton;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            refreshDashboard();
            if (scanning) uiHandler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        scanner = adapter != null ? adapter.getBluetoothLeScanner() : null;
        buildUi();
        requestNeededPermissions();
        refreshDashboard();
    }

    private void buildUi() {
        final int bg = Color.rgb(15, 18, 24);
        final int card = Color.rgb(28, 33, 43);
        final int muted = Color.rgb(170, 180, 195);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 24, 32, 24);
        root.setBackgroundColor(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Instant Hotspot BLE Logger");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        statusValue = new TextView(this);
        statusValue.setTextSize(18);
        statusValue.setTextColor(Color.WHITE);
        statusValue.setGravity(Gravity.END);
        header.addView(statusValue, new LinearLayout.LayoutParams(360, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setPadding(0, 18, 0, 12);
        experimentValue = makeMetaText("EXP ----", Color.WHITE);
        elapsedValue = makeMetaText("00:00.0", muted);
        markValue = makeMetaText("MARKS 0", muted);
        meta.addView(experimentValue, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        meta.addView(elapsedValue, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        meta.addView(markValue, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(meta);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        totalValue = addStatCard(row1, "TOTAL", "0", card);
        appleValue = addStatCard(row1, "APPLE 0x004C", "0", card);
        deviceValue = addStatCard(row1, "APPLE DEVICES", "0", card);
        rssiValue = addStatCard(row1, "STRONGEST RSSI", "-- dBm", card);
        root.addView(row1, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 118));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        tetherTargetValue = addStatCard(row2, "0x0D TARGET", "0", card);
        tetherSourceValue = addStatCard(row2, "0x0E SOURCE", "0", card);
        continuityValue = addStatCard(row2, "0x10 / 0x12 / 0x16", "0 / 0 / 0", card);
        TextView beaconValue = addStatCard(row2, "0x02 iBEACON", "0", card);
        beaconValue.setTag("beacon");
        root.addView(row2, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 118));

        TextView pathLabel = new TextView(this);
        pathLabel.setText("OUTPUT");
        pathLabel.setTextColor(muted);
        pathLabel.setTextSize(13);
        pathLabel.setPadding(4, 14, 0, 2);
        root.addView(pathLabel);

        pathValue = new TextView(this);
        pathValue.setTextColor(Color.WHITE);
        pathValue.setTextSize(15);
        pathValue.setSingleLine(true);
        pathValue.setPadding(4, 0, 0, 12);
        root.addView(pathValue);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        startButton = makeButton("START EXPERIMENT");
        startButton.setOnClickListener(v -> startCapture());
        controls.addView(startButton, buttonParams());

        markButton = makeButton("MARK EVENT");
        markButton.setEnabled(false);
        markButton.setOnClickListener(v -> markEvent());
        controls.addView(markButton, buttonParams());

        stopButton = makeButton("STOP / SAVE");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopCapture());
        controls.addView(stopButton, buttonParams());

        root.addView(controls, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 78));

        TextView hint = new TextView(this);
        hint.setText("Dashboard refreshes at 2 Hz. Raw BLE packets are written to CSV only; they are not rendered on screen.");
        hint.setTextColor(muted);
        hint.setTextSize(13);
        hint.setPadding(4, 10, 0, 0);
        root.addView(hint);

        setContentView(root);
        startButton.requestFocus();
    }

    private TextView makeMetaText(String text, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(17);
        return v;
    }

    private TextView addStatCard(LinearLayout parent, String label, String value, int cardColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(18, 10, 18, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(16f);
        card.setBackground(bg);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.rgb(165, 175, 190));
        l.setTextSize(12);
        card.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(25);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(v);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(5, 5, 5, 5);
        parent.addView(card, p);
        return v;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(5, 4, 5, 4);
        return p;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    private void startCapture() {
        if (scanning) return;
        if (scanner == null) {
            statusValue.setText("BLE unavailable");
            return;
        }
        if (Build.VERSION.SDK_INT <= 28 && Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            statusValue.setText("Grant storage permission");
            requestNeededPermissions();
            return;
        }

        currentExperimentNo = getNextExperimentNo();
        try {
            experimentDir = createExperimentDirectory(currentExperimentNo);
            File csvFile = new File(experimentDir, "ble_capture.csv");
            File eventsFile = new File(experimentDir, "events.csv");
            csvWriter = new BufferedWriter(new FileWriter(csvFile), 64 * 1024);
            eventWriter = new BufferedWriter(new FileWriter(eventsFile), 8 * 1024);
            csvWriter.write("timestamp_utc,scan_timestamp_nanos,rssi,address,name,company_id,manufacturer_hex,service_data_hex,raw_scan_record_hex,is_apple\n");
            eventWriter.write("timestamp_utc,event,elapsed_ms\n");
            csvWriter.flush();
            eventWriter.flush();
        } catch (IOException e) {
            closeWriters();
            statusValue.setText("File open failed");
            pathValue.setText(e.getMessage() == null ? "" : e.getMessage());
            return;
        }

        saveNextExperimentNo(currentExperimentNo + 1);
        resetCounters();
        startedAtElapsed = SystemClock.elapsedRealtime();
        scanning = true;
        startButton.setEnabled(false);
        markButton.setEnabled(true);
        stopButton.setEnabled(true);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            closeWriters();
            statusValue.setText("Bluetooth permission denied");
            return;
        }
        statusValue.setText("RECORDING");
        uiHandler.removeCallbacks(uiTicker);
        uiHandler.post(uiTicker);
        refreshDashboard();
    }

    private File createExperimentDirectory(int experimentNo) throws IOException {
        String exp = String.format(Locale.US, "EXP%04d", experimentNo);
        File base;
        if (Build.VERSION.SDK_INT <= 28) {
            base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "instant-hotspot-ble-logger");
        } else {
            File appDocs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (appDocs == null) appDocs = getFilesDir();
            base = new File(appDocs, "instant-hotspot-ble-logger");
        }
        File dir = new File(base, exp);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create " + dir.getAbsolutePath());
        return dir;
    }

    private int getNextExperimentNo() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return Math.max(1, p.getInt(PREF_NEXT_EXPERIMENT, 1));
    }

    private void saveNextExperimentNo(int n) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_NEXT_EXPERIMENT, n).apply();
    }

    private void resetCounters() {
        totalPackets = applePackets = 0;
        type02 = type0D = type0E = type10 = type12 = type16 = 0;
        rowsSinceFlush = 0;
        strongestAppleRssi = -127;
        markCount = 0;
        appleDevices.clear();
    }

    private void markEvent() {
        if (!scanning || eventWriter == null) return;
        markCount++;
        long elapsed = SystemClock.elapsedRealtime() - startedAtElapsed;
        String label = String.format(Locale.US, "MARK_%03d", markCount);
        try {
            synchronized (this) {
                eventWriter.write(csv(utcNow()) + "," + csv(label) + "," + elapsed + "\n");
                eventWriter.flush();
            }
            statusValue.setText(label);
            uiHandler.postDelayed(() -> { if (scanning) statusValue.setText("RECORDING"); }, 700);
        } catch (IOException e) {
            statusValue.setText("Event write error");
        }
        refreshDashboard();
    }

    private void stopCapture() {
        if (!scanning) return;
        scanning = false;
        uiHandler.removeCallbacks(uiTicker);
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        closeWriters();
        startButton.setEnabled(true);
        markButton.setEnabled(false);
        stopButton.setEnabled(false);
        statusValue.setText("SAVED");
        refreshDashboard();
        startButton.requestFocus();
    }

    private void closeWriters() {
        try { if (csvWriter != null) { csvWriter.flush(); csvWriter.close(); } } catch (IOException ignored) {}
        try { if (eventWriter != null) { eventWriter.flush(); eventWriter.close(); } } catch (IOException ignored) {}
        csvWriter = null;
        eventWriter = null;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { handleResult(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) handleResult(r); }
        @Override public void onScanFailed(int errorCode) {
            runOnUiThread(() -> statusValue.setText("SCAN FAILED " + errorCode));
        }
    };

    private void handleResult(ScanResult result) {
        if (!scanning) return;
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        totalPackets++;

        SparseArray<byte[]> manufacturer = record.getManufacturerSpecificData();
        byte[] appleData = manufacturer != null ? manufacturer.get(APPLE_COMPANY_ID) : null;
        boolean hasApple = appleData != null;
        if (hasApple) {
            applePackets++;
            int rssi = result.getRssi();
            if (rssi > strongestAppleRssi) strongestAppleRssi = rssi;
            try {
                String address = result.getDevice().getAddress();
                if (address != null && !address.isEmpty()) appleDevices.add(address);
            } catch (SecurityException ignored) {}
            if (appleData.length > 0) incrementAppleType(appleData[0] & 0xff);
        }

        String name = record.getDeviceName();
        if (name == null) name = "";
        String address = "";
        try { address = result.getDevice().getAddress(); } catch (SecurityException ignored) {}
        String serviceHex = encodeServiceData(record.getServiceData());
        String rawHex = toHex(record.getBytes());
        long scanTimestampNanos = result.getTimestampNanos();

        if (manufacturer == null || manufacturer.size() == 0) {
            writeRow(scanTimestampNanos, result.getRssi(), address, name, "", "", serviceHex, rawHex, false);
        } else {
            for (int i = 0; i < manufacturer.size(); i++) {
                int companyId = manufacturer.keyAt(i);
                writeRow(scanTimestampNanos, result.getRssi(), address, name,
                        String.format(Locale.US, "0x%04X", companyId),
                        toHex(manufacturer.valueAt(i)), serviceHex, rawHex,
                        companyId == APPLE_COMPANY_ID);
            }
        }
    }

    private void incrementAppleType(int type) {
        switch (type) {
            case 0x02: type02++; break;
            case 0x0D: type0D++; break;
            case 0x0E: type0E++; break;
            case 0x10: type10++; break;
            case 0x12: type12++; break;
            case 0x16: type16++; break;
        }
    }

    private synchronized void writeRow(long ts, int rssi, String address, String name,
                                       String companyId, String manufacturerHex,
                                       String serviceHex, String rawHex, boolean isApple) {
        if (csvWriter == null) return;
        try {
            csvWriter.write(csv(utcNow()) + "," + ts + "," + rssi + "," + csv(address) + "," + csv(name) + "," +
                    csv(companyId) + "," + csv(manufacturerHex) + "," + csv(serviceHex) + "," + csv(rawHex) + "," +
                    (isApple ? "1" : "0") + "\n");
            rowsSinceFlush++;
            if (rowsSinceFlush >= 100) {
                csvWriter.flush();
                rowsSinceFlush = 0;
            }
        } catch (IOException e) {
            runOnUiThread(() -> statusValue.setText("WRITE ERROR"));
        }
    }

    private void refreshDashboard() {
        int next = scanning ? currentExperimentNo : getNextExperimentNo();
        experimentValue.setText(String.format(Locale.US, "EXP %04d", next));
        long elapsed = scanning ? SystemClock.elapsedRealtime() - startedAtElapsed : 0;
        elapsedValue.setText(formatElapsed(elapsed));
        markValue.setText("MARKS " + markCount);
        totalValue.setText(String.valueOf(totalPackets));
        appleValue.setText(String.valueOf(applePackets));
        deviceValue.setText(String.valueOf(appleDevices.size()));
        rssiValue.setText(strongestAppleRssi > -127 ? strongestAppleRssi + " dBm" : "-- dBm");
        tetherTargetValue.setText(String.valueOf(type0D));
        tetherSourceValue.setText(String.valueOf(type0E));
        continuityValue.setText(type10 + " / " + type12 + " / " + type16);

        View row2 = ((LinearLayout) tetherTargetValue.getParent()).getParent();
        if (row2 instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) row2;
            if (row.getChildCount() >= 4) {
                LinearLayout card = (LinearLayout) row.getChildAt(3);
                if (card.getChildCount() >= 2) ((TextView) card.getChildAt(1)).setText(String.valueOf(type02));
            }
        }

        if (experimentDir != null) pathValue.setText(experimentDir.getAbsolutePath());
        else pathValue.setText(defaultOutputHint(next));
    }

    private String defaultOutputHint(int experimentNo) {
        String exp = String.format(Locale.US, "EXP%04d", experimentNo);
        if (Build.VERSION.SDK_INT <= 28) {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "instant-hotspot-ble-logger/" + exp).getAbsolutePath();
        }
        File appDocs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (appDocs == null) appDocs = getFilesDir();
        return new File(appDocs, "instant-hotspot-ble-logger/" + exp).getAbsolutePath();
    }

    private static String formatElapsed(long ms) {
        long totalTenths = ms / 100;
        long minutes = totalTenths / 600;
        long seconds = (totalTenths / 10) % 60;
        long tenths = totalTenths % 10;
        return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    private static String encodeServiceData(Map<ParcelUuid, byte[]> map) {
        if (map == null || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ParcelUuid, byte[]> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(";");
            sb.append(e.getKey()).append("=").append(toHex(e.getValue()));
        }
        return sb.toString();
    }

    private static String toHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String utcNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    @Override protected void onDestroy() {
        if (scanning) stopCapture();
        uiHandler.removeCallbacks(uiTicker);
        super.onDestroy();
    }
}
