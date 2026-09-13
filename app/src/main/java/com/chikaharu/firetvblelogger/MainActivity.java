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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.SparseArray;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;
    private static final int APPLE_COMPANY_ID = 0x004C;
    private TextView status, logView;
    private Button startButton, stopButton;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private FileWriter csvWriter;
    private File currentFile;
    private long totalPackets, applePackets;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager!=null?manager.getAdapter():null;
        scanner=adapter!=null?adapter.getBluetoothLeScanner():null;
        buildUi(); requestNeededPermissions();
    }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(36,28,36,28);
        TextView title=new TextView(this); title.setText("Fire TV BLE Logger"); title.setTextSize(28); root.addView(title);
        status=new TextView(this); status.setText("Ready"); status.setTextSize(18); status.setPadding(0,16,0,16); root.addView(status);
        LinearLayout buttons=new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); buttons.setGravity(Gravity.START);
        startButton=new Button(this); startButton.setText("START CAPTURE"); startButton.setFocusable(true); startButton.setOnClickListener(v->startCapture()); buttons.addView(startButton);
        stopButton=new Button(this); stopButton.setText("STOP / SAVE"); stopButton.setFocusable(true); stopButton.setEnabled(false); stopButton.setOnClickListener(v->stopCapture()); buttons.addView(stopButton); root.addView(buttons);
        ScrollView scroll=new ScrollView(this); logView=new TextView(this); logView.setTextSize(14); logView.setText("All BLE advertisements are logged.\nApple manufacturer ID 0x004C is highlighted.\n"); scroll.addView(logView);
        root.addView(scroll,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f)); setContentView(root); startButton.requestFocus();
    }

    private void requestNeededPermissions() {
        if(Build.VERSION.SDK_INT>=31) {
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},REQ_PERMS);
        } else if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ_PERMS);
    }

    private void startCapture() {
        if(scanning)return; if(scanner==null){status.setText("BLE scanner unavailable");return;}
        try { File dir=getExternalFilesDir(null); if(dir==null)dir=getFilesDir(); String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date()); currentFile=new File(dir,"ble_capture_"+stamp+".csv"); csvWriter=new FileWriter(currentFile); csvWriter.write("timestamp_utc,scan_timestamp_nanos,rssi,address,name,company_id,manufacturer_hex,service_data_hex,raw_scan_record_hex,is_apple\n"); csvWriter.flush(); }
        catch(IOException e){status.setText("File open failed: "+e.getMessage());return;}
        totalPackets=0; applePackets=0; scanning=true; startButton.setEnabled(false); stopButton.setEnabled(true);
        ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(); scanner.startScan(null,settings,scanCallback); status.setText("Scanning...");
    }

    private void stopCapture() {
        if(!scanning)return; scanning=false; try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
        try{if(csvWriter!=null){csvWriter.flush();csvWriter.close();}}catch(IOException ignored){} csvWriter=null; startButton.setEnabled(true); stopButton.setEnabled(false);
        String path=currentFile!=null?currentFile.getAbsolutePath():"(unknown)"; status.setText("Saved. total="+totalPackets+"  Apple(0x004C)="+applePackets+"\n"+path); startButton.requestFocus();
    }

    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int callbackType,ScanResult result){handleResult(result);}
        @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult r:results)handleResult(r);}
        @Override public void onScanFailed(int errorCode){status.setText("Scan failed: "+errorCode);}
    };

    private void handleResult(ScanResult result) {
        if(!scanning)return; ScanRecord record=result.getScanRecord(); if(record==null)return; totalPackets++;
        SparseArray<byte[]> manufacturer=record.getManufacturerSpecificData(); boolean hasApple=manufacturer!=null&&manufacturer.get(APPLE_COMPANY_ID)!=null; if(hasApple)applePackets++;
        String name=record.getDeviceName(); if(name==null)name=""; String address=""; try{address=result.getDevice().getAddress();}catch(SecurityException ignored){}
        String serviceHex=encodeServiceData(record.getServiceData()); String rawHex=toHex(record.getBytes()); long scanTimestampNanos=result.getTimestampNanos();
        if(manufacturer==null||manufacturer.size()==0) writeRow(scanTimestampNanos,result.getRssi(),address,name,"","",serviceHex,rawHex,false);
        else for(int i=0;i<manufacturer.size();i++){int companyId=manufacturer.keyAt(i); writeRow(scanTimestampNanos,result.getRssi(),address,name,String.format(Locale.US,"0x%04X",companyId),toHex(manufacturer.valueAt(i)),serviceHex,rawHex,companyId==APPLE_COMPANY_ID);}
        if(hasApple||totalPackets%25==0){final String msg="packets="+totalPackets+"  Apple="+applePackets+(hasApple?"   <-- 0x004C\n":"\n"); runOnUiThread(()->{status.setText("Scanning... total="+totalPackets+" Apple="+applePackets);logView.append(msg);});}
    }

    private synchronized void writeRow(long ts,int rssi,String address,String name,String companyId,String manufacturerHex,String serviceHex,String rawHex,boolean isApple) {
        if(csvWriter==null)return; try{csvWriter.write(csv(utcNow())+","+ts+","+rssi+","+csv(address)+","+csv(name)+","+csv(companyId)+","+csv(manufacturerHex)+","+csv(serviceHex)+","+csv(rawHex)+","+(isApple?"1":"0")+"\n");csvWriter.flush();}catch(IOException e){runOnUiThread(()->status.setText("Write error: "+e.getMessage()));}
    }

    private static String encodeServiceData(Map<ParcelUuid,byte[]> map){if(map==null||map.isEmpty())return "";StringBuilder sb=new StringBuilder();for(Map.Entry<ParcelUuid,byte[]> e:map.entrySet()){if(sb.length()>0)sb.append(";");sb.append(e.getKey()).append("=").append(toHex(e.getValue()));}return sb.toString();}
    private static String toHex(byte[] data){if(data==null)return "";StringBuilder sb=new StringBuilder(data.length*2);for(byte b:data)sb.append(String.format(Locale.US,"%02x",b&0xff));return sb.toString();}
    private static String csv(String s){if(s==null)s="";return "\""+s.replace("\"","\"\"")+"\"";}
    private static String utcNow(){SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);fmt.setTimeZone(TimeZone.getTimeZone("UTC"));return fmt.format(new Date());}
    @Override protected void onDestroy(){if(scanning)stopCapture();super.onDestroy();}
}
