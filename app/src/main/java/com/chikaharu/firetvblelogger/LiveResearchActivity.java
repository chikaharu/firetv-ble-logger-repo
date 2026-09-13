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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class LiveResearchActivity extends Activity {
    private static final int APPLE_ID = 0x004C;
    private static final int REQ_PERMS = 1001;
    private static final String PREFS = "ble_logger_prefs";
    private static final String PREF_NEXT_EXPERIMENT = "next_experiment";

    private BluetoothLeScanner scanner;
    private boolean scanning;
    private BufferedWriter csvWriter;
    private BufferedWriter eventWriter;
    private File experimentDir;
    private long startedAt;
    private long totalPackets;
    private long applePackets;
    private long rowsSinceFlush;
    private int markCount;
    private int currentExperimentNo;
    private int strongestRssi = -127;

    private long type02, type10, type12, type16;
    private long last02, last10, last12, last16;
    private final Set<String> appleAddresses = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> linkCounts = Collections.synchronizedMap(new HashMap<>());
    private final ArrayDeque<String> recentLinks = new ArrayDeque<>();

    private TextView status;
    private TextView stats;
    private TextView recent;
    private TextView path;
    private Button startButton;
    private Button markButton;
    private Button stopButton;
    private ResearchView researchView;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            sampleRates();
            refreshUi();
            if (scanning) ui.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        scanner = adapter != null ? adapter.getBluetoothLeScanner() : null;
        buildUi();
        requestNeededPermissions();
        refreshUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 18, 24, 18);
        root.setBackgroundColor(Color.rgb(12, 15, 21));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Instant Hotspot BLE — Live Research Console");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, 1);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        status = new TextView(this);
        status.setText("READY");
        status.setTextSize(16);
        status.setTextColor(Color.rgb(120, 220, 170));
        status.setGravity(Gravity.END);
        top.addView(status, new LinearLayout.LayoutParams(300, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(top);

        stats = new TextView(this);
        stats.setTextColor(Color.rgb(215, 222, 232));
        stats.setTextSize(15);
        stats.setPadding(0, 8, 0, 8);
        root.addView(stats);

        researchView = new ResearchView(this);
        root.addView(researchView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout lower = new LinearLayout(this);
        lower.setOrientation(LinearLayout.HORIZONTAL);
        lower.setPadding(0, 8, 0, 8);

        recent = new TextView(this);
        recent.setTextColor(Color.rgb(185, 198, 216));
        recent.setTextSize(13);
        recent.setTypeface(android.graphics.Typeface.MONOSPACE);
        lower.addView(recent, new LinearLayout.LayoutParams(0, 120, 1f));

        path = new TextView(this);
        path.setTextColor(Color.rgb(145, 155, 170));
        path.setTextSize(11);
        path.setGravity(Gravity.END | Gravity.BOTTOM);
        lower.addView(path, new LinearLayout.LayoutParams(0, 120, 1f));
        root.addView(lower);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        startButton = button("START EXPERIMENT");
        startButton.setOnClickListener(v -> startCapture());
        controls.addView(startButton, weightedButton());

        markButton = button("MARK EVENT");
        markButton.setEnabled(false);
        markButton.setOnClickListener(v -> markEvent());
        controls.addView(markButton, weightedButton());

        stopButton = button("STOP / SAVE");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopCapture());
        controls.addView(stopButton, weightedButton());

        root.addView(controls, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 70));
        setContentView(root);
        startButton.requestFocus();
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(4, 3, 4, 3);
        return p;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    private void startCapture() {
        if (scanning) return;
        if (scanner == null) {
            status.setText("BLE UNAVAILABLE");
            return;
        }
        currentExperimentNo = getNextExperimentNo();
        try {
            experimentDir = createExperimentDirectory(currentExperimentNo);
            csvWriter = new BufferedWriter(new FileWriter(new File(experimentDir, "ble_capture.csv")), 64 * 1024);
            eventWriter = new BufferedWriter(new FileWriter(new File(experimentDir, "events.csv")), 8 * 1024);
            csvWriter.write("timestamp_utc,scan_timestamp_nanos,rssi,address,name,company_id,manufacturer_hex,service_data_hex,raw_scan_record_hex,is_apple\n");
            eventWriter.write("timestamp_utc,event,elapsed_ms\n");
            csvWriter.flush();
            eventWriter.flush();
        } catch (IOException e) {
            closeWriters();
            status.setText("FILE OPEN FAILED");
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_NEXT_EXPERIMENT, currentExperimentNo + 1).apply();
        resetState();
        startedAt = SystemClock.elapsedRealtime();
        scanning = true;
        startButton.setEnabled(false);
        markButton.setEnabled(true);
        stopButton.setEnabled(true);

        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, settings, callback);
        } catch (SecurityException e) {
            scanning = false;
            closeWriters();
            status.setText("PERMISSION DENIED");
            return;
        }
        status.setText("LIVE");
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void resetState() {
        totalPackets = applePackets = rowsSinceFlush = 0;
        type02 = type10 = type12 = type16 = 0;
        last02 = last10 = last12 = last16 = 0;
        markCount = 0;
        strongestRssi = -127;
        appleAddresses.clear();
        linkCounts.clear();
        recentLinks.clear();
        researchView.reset();
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { handleResult(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) handleResult(r); }
        @Override public void onScanFailed(int code) { runOnUiThread(() -> status.setText("SCAN FAILED " + code)); }
    };

    private void handleResult(ScanResult result) {
        if (!scanning) return;
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        totalPackets++;

        String address = "";
        try { address = result.getDevice().getAddress(); } catch (SecurityException ignored) {}
        String name = record.getDeviceName();
        if (name == null) name = "";
        SparseArray<byte[]> manufacturer = record.getManufacturerSpecificData();
        byte[] apple = manufacturer != null ? manufacturer.get(APPLE_ID) : null;

        if (apple != null) {
            applePackets++;
            strongestRssi = Math.max(strongestRssi, result.getRssi());
            if (!address.isEmpty()) appleAddresses.add(address);
            if (apple.length > 0) {
                int type = apple[0] & 0xff;
                countType(type);
                updateLink(address, type, result.getRssi());
            }
        }

        String serviceHex = encodeServiceData(record.getServiceData());
        String rawHex = toHex(record.getBytes());
        long ts = result.getTimestampNanos();
        if (manufacturer == null || manufacturer.size() == 0) {
            writeRow(ts, result.getRssi(), address, name, "", "", serviceHex, rawHex, false);
        } else {
            for (int i = 0; i < manufacturer.size(); i++) {
                int company = manufacturer.keyAt(i);
                writeRow(ts, result.getRssi(), address, name, String.format(Locale.US, "0x%04X", company),
                        toHex(manufacturer.valueAt(i)), serviceHex, rawHex, company == APPLE_ID);
            }
        }
    }

    private void countType(int type) {
        if (type == 0x02) type02++;
        else if (type == 0x10) type10++;
        else if (type == 0x12) type12++;
        else if (type == 0x16) type16++;
    }

    private void updateLink(String address, int type, int rssi) {
        String typeName = String.format(Locale.US, "0x%02X", type);
        String device = address.isEmpty() ? "unknown" : address;
        String key = device + "→" + typeName;
        long n;
        synchronized (linkCounts) {
            n = linkCounts.containsKey(key) ? linkCounts.get(key) + 1 : 1;
            linkCounts.put(key, n);
        }
        if (n == 1 || n == 2 || n == 4 || n == 8 || n % 32 == 0) {
            synchronized (recentLinks) {
                recentLinks.addFirst(typeName + "  RSSI " + rssi + "  n=" + n + "  " + shortAddress(device));
                while (recentLinks.size() > 5) recentLinks.removeLast();
            }
        }
    }

    private void sampleRates() {
        if (!scanning) return;
        long d02 = type02 - last02;
        long d10 = type10 - last10;
        long d12 = type12 - last12;
        long d16 = type16 - last16;
        last02 = type02;
        last10 = type10;
        last12 = type12;
        last16 = type16;
        researchView.addSample(d02 * 2f, d10 * 2f, d12 * 2f, d16 * 2f, markCount);
    }

    private void refreshUi() {
        long elapsed = scanning ? SystemClock.elapsedRealtime() - startedAt : 0;
        stats.setText(String.format(Locale.US,
                "EXP %04d   %s   total %,d   Apple %,d   devices %d   RSSI %s   02 %,d   10 %,d   12 %,d   16 %,d",
                currentExperimentNo > 0 ? currentExperimentNo : getNextExperimentNo(),
                formatElapsed(elapsed), totalPackets, applePackets, appleAddresses.size(),
                strongestRssi > -127 ? strongestRssi + " dBm" : "--", type02, type10, type12, type16));

        StringBuilder sb = new StringBuilder("NEW / GROWING LINKS\n");
        synchronized (recentLinks) {
            for (String s : recentLinks) sb.append(s).append('\n');
        }
        recent.setText(sb.toString());
        if (experimentDir != null) path.setText(experimentDir.getAbsolutePath());
        researchView.setTotals(type02, type10, type12, type16, appleAddresses.size(), linkCounts.size());
        researchView.invalidate();
    }

    private void markEvent() {
        if (!scanning || eventWriter == null) return;
        markCount++;
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        String label = String.format(Locale.US, "MARK_%03d", markCount);
        try {
            synchronized (this) {
                eventWriter.write(csv(utcNow()) + "," + csv(label) + "," + elapsed + "\n");
                eventWriter.flush();
            }
            status.setText(label);
            researchView.addMarker();
            ui.postDelayed(() -> { if (scanning) status.setText("LIVE"); }, 700);
        } catch (IOException e) {
            status.setText("EVENT WRITE ERROR");
        }
    }

    private void stopCapture() {
        if (!scanning) return;
        scanning = false;
        ui.removeCallbacks(ticker);
        try { scanner.stopScan(callback); } catch (Exception ignored) {}
        closeWriters();
        startButton.setEnabled(true);
        markButton.setEnabled(false);
        stopButton.setEnabled(false);
        status.setText("SAVED");
        refreshUi();
        startButton.requestFocus();
    }

    private synchronized void writeRow(long ts, int rssi, String address, String name, String company,
                                       String manufacturer, String service, String raw, boolean isApple) {
        if (csvWriter == null) return;
        try {
            csvWriter.write(csv(utcNow()) + "," + ts + "," + rssi + "," + csv(address) + "," + csv(name) + "," +
                    csv(company) + "," + csv(manufacturer) + "," + csv(service) + "," + csv(raw) + "," + (isApple ? "1" : "0") + "\n");
            rowsSinceFlush++;
            if (rowsSinceFlush >= 100) {
                csvWriter.flush();
                rowsSinceFlush = 0;
            }
        } catch (IOException e) {
            runOnUiThread(() -> status.setText("WRITE ERROR"));
        }
    }

    private void closeWriters() {
        try { if (csvWriter != null) { csvWriter.flush(); csvWriter.close(); } } catch (IOException ignored) {}
        try { if (eventWriter != null) { eventWriter.flush(); eventWriter.close(); } } catch (IOException ignored) {}
        csvWriter = null;
        eventWriter = null;
    }

    private int getNextExperimentNo() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return Math.max(1, p.getInt(PREF_NEXT_EXPERIMENT, 1));
    }

    private File createExperimentDirectory(int n) throws IOException {
        String exp = String.format(Locale.US, "EXP%04d", n);
        File base;
        if (Build.VERSION.SDK_INT <= 28) base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "instant-hotspot-ble-logger");
        else {
            File docs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (docs == null) docs = getFilesDir();
            base = new File(docs, "instant-hotspot-ble-logger");
        }
        File dir = new File(base, exp);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create output directory");
        return dir;
    }

    private static String shortAddress(String s) {
        if (s == null || s.length() < 8) return s == null ? "" : s;
        return "…" + s.substring(Math.max(0, s.length() - 8));
    }

    private static String formatElapsed(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    private static String encodeServiceData(Map<ParcelUuid, byte[]> map) {
        if (map == null || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ParcelUuid, byte[]> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(toHex(e.getValue()));
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
        ui.removeCallbacks(ticker);
        super.onDestroy();
    }

    private static class ResearchView extends View {
        private static final int MAX = 120;
        private final ArrayDeque<float[]> samples = new ArrayDeque<>();
        private final Set<Integer> markers = new HashSet<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long total02, total10, total12, total16;
        private int devices, links;
        private int sampleIndex;

        ResearchView(Context c) { super(c); setBackgroundColor(Color.rgb(20, 25, 34)); }

        void reset() {
            samples.clear();
            markers.clear();
            sampleIndex = 0;
            total02 = total10 = total12 = total16 = 0;
            devices = links = 0;
            invalidate();
        }

        void addSample(float a, float b, float c, float d, int markCount) {
            samples.addLast(new float[]{a,b,c,d});
            while (samples.size() > MAX) samples.removeFirst();
            sampleIndex++;
        }

        void addMarker() { markers.add(sampleIndex); invalidate(); }
        void setTotals(long a,long b,long c,long d,int dev,int l) { total02=a; total10=b; total12=c; total16=d; devices=dev; links=l; }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float split = h * 0.58f;
            drawRates(c, 18, 18, w - 18, split - 10);
            drawGraph(c, 18, split + 4, w - 18, h - 18);
        }

        private void drawRates(Canvas c, float l, float t, float r, float b) {
            p.setStyle(Paint.Style.FILL); p.setTextSize(20); p.setColor(Color.WHITE);
            c.drawText("LIVE TYPE RATE   packets/s", l + 4, t + 22, p);
            p.setTextSize(14); p.setColor(Color.rgb(150,160,175));
            c.drawText("0x02  0x10  0x12  0x16   • event markers", l + 4, t + 44, p);

            float top = t + 58, bottom = b - 10;
            p.setColor(Color.rgb(55,62,76)); p.setStrokeWidth(1);
            for (int i=0;i<4;i++) {
                float y = top + (bottom-top)*i/3f;
                c.drawLine(l,y,r,y,p);
            }
            if (samples.size() < 2) return;
            float max = 8f;
            for (float[] s : samples) for (float v:s) max = Math.max(max,v);
            int[] colors = {Color.rgb(80,170,255), Color.rgb(255,170,70), Color.rgb(100,220,130), Color.rgb(235,95,115)};
            for (int k=0;k<4;k++) {
                Path path = new Path(); int i=0; boolean first=true;
                for (float[] s:samples) {
                    float x = l + (r-l) * i / Math.max(1f, samples.size()-1f);
                    float y = bottom - (bottom-top) * (s[k]/max);
                    if (first) { path.moveTo(x,y); first=false; } else path.lineTo(x,y);
                    i++;
                }
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(colors[k]); c.drawPath(path,p);
            }
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(255,235,125));
            int baseIndex = Math.max(0, sampleIndex - samples.size());
            for (Integer m:markers) {
                if (m < baseIndex || m > sampleIndex) continue;
                float x = l + (r-l)*(m-baseIndex)/Math.max(1f,samples.size()-1f);
                c.drawRect(x-1,top,x+1,bottom,p);
            }
        }

        private void drawGraph(Canvas c, float l, float t, float r, float b) {
            p.setStyle(Paint.Style.FILL); p.setTextSize(18); p.setColor(Color.WHITE);
            c.drawText("OBSERVATION LINK GRAPH", l + 4, t + 22, p);
            p.setTextSize(13); p.setColor(Color.rgb(150,160,175));
            c.drawText("devices " + devices + "   links " + links + "   edge weight = observations", l + 4, t + 42, p);

            float cy = (t+b)/2f + 18;
            float cx = (l+r)/2f;
            float radius = Math.min((r-l)*0.32f, (b-t)*0.28f);
            float[][] pos = {{cx-radius,cy},{cx,cy-radius*0.72f},{cx+radius,cy},{cx,cy+radius*0.72f}};
            long[] totals = {total02,total10,total12,total16};
            String[] names = {"0x02","0x10","0x12","0x16"};
            int[] colors = {Color.rgb(80,170,255),Color.rgb(255,170,70),Color.rgb(100,220,130),Color.rgb(235,95,115)};
            long max = 1; for (long x:totals) max=Math.max(max,x);

            for (int i=0;i<4;i++) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1f + 8f * totals[i] / (float)max);
                p.setColor(Color.rgb(75,85,105));
                c.drawLine(cx,cy,pos[i][0],pos[i][1],p);
            }
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(225,230,238)); c.drawCircle(cx,cy,28,p);
            p.setColor(Color.rgb(18,22,30)); p.setTextSize(12); p.setTextAlign(Paint.Align.CENTER); c.drawText("DEV",cx,cy+4,p);
            for (int i=0;i<4;i++) {
                float rr = 18f + 16f * (float)Math.sqrt(totals[i]/(double)max);
                p.setColor(colors[i]); c.drawCircle(pos[i][0],pos[i][1],rr,p);
                p.setColor(Color.WHITE); p.setTextSize(13); c.drawText(names[i],pos[i][0],pos[i][1]+4,p);
            }
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
}
