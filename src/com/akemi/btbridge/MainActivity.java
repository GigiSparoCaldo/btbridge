package com.akemi.btbridge;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

public class MainActivity extends Activity {
    private TextView statusText;
    private LinearLayout devicesBox;
    private String netStatus = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatusView();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(64, 72, 64, 84);

        statusText = new TextView(this);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 0, 0, 16);
        root.addView(statusText);
        updateStatusView();

        String savedMac = loadMac();
        if (!savedMac.isEmpty()) {
            Button oneTapBtn = new Button(this);
            oneTapBtn.setText("Connetti Watch (1-Tap)\n" + savedMac);
            oneTapBtn.setOnClickListener(v -> connectWatchOneTap(savedMac));
            root.addView(oneTapBtn);
        }

        Button serverBtn = new Button(this);
        serverBtn.setText("Avvia SERVER (Z Flip)");
        serverBtn.setOnClickListener(v -> startBridge("server", ""));
        root.addView(serverBtn);

        Button testBtn = new Button(this);
        testBtn.setText("Test Internet");
        testBtn.setOnClickListener(v -> runNetTest());
        root.addView(testBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("Stop Tutto");
        stopBtn.setOnClickListener(v -> stopAll());
        root.addView(stopBtn);

        TextView clientLabel = new TextView(this);
        clientLabel.setGravity(Gravity.CENTER);
        clientLabel.setText("\nOppure scegli dispositivo:");
        clientLabel.setPadding(0, 10, 0, 8);
        root.addView(clientLabel);

        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(devicesBox);

        scroll.addView(root);
        setContentView(scroll);

        requestBtPermissionAndLoad();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusUpdater);
        super.onPause();
    }

    private void updateStatusView() {
        if (statusText == null) return;
        String text = "Bridge: " + BridgeService.lastInfo;
        if (!netStatus.isEmpty()) {
            text += "\nRete: " + netStatus;
        }
        statusText.setText(text);
    }

    private void requestBtPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.POST_NOTIFICATIONS"
            }, 100);
        } else {
            populatePairedDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        populatePairedDevices();
    }

    private void populatePairedDevices() {
        devicesBox.removeAllViews();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("Nessun dispositivo associato");
                empty.setGravity(Gravity.CENTER);
                devicesBox.addView(empty);
                return;
            }
            for (BluetoothDevice dev : bonded) {
                final String mac = dev.getAddress();
                final String name = dev.getName() != null ? dev.getName() : "Dispositivo";
                Button b = new Button(this);
                b.setText(name + "\n" + mac);
                b.setOnClickListener(v -> connectWatchOneTap(mac));
                devicesBox.addView(b);
            }
        } catch (SecurityException e) {
            netStatus = "Permesso BT mancante";
            updateStatusView();
        }
    }

    private void connectWatchOneTap(String mac) {
        saveMac(mac);
        startBridge("client", mac);
        netStatus = "Avvio Client + VPN...";
        updateStatusView();
        handler.postDelayed(this::startGnirehtet, 450);
    }

    private void startBridge(String mode, String mac) {
        netStatus = "";
        Intent svc = new Intent(this, BridgeService.class);
        svc.putExtra("mode", mode);
        svc.putExtra("mac", mac);
        startForegroundService(svc);
        updateStatusView();
    }

    private void stopAll() {
        netStatus = "";
        handler.removeCallbacksAndMessages(null);
        try {
            Intent stopVpn = new Intent(this, com.genymobile.gnirehtet.GnirehtetActivity.class);
            stopVpn.setAction("com.genymobile.gnirehtet.STOP");
            startActivity(stopVpn);
        } catch (Exception ignored) {}
        Intent svc = new Intent(this, BridgeService.class);
        svc.setAction("STOP");
        startService(svc);
        updateStatusView();
    }

    private void startGnirehtet() {
        try {
            Intent i = new Intent(this, com.genymobile.gnirehtet.GnirehtetActivity.class);
            i.setAction("com.genymobile.gnirehtet.START");
            startActivity(i);
            netStatus = "VPN + Client attivi";
        } catch (Exception e) {
            netStatus = "Err VPN: " + e.getMessage();
        }
        updateStatusView();
    }

    private void runNetTest() {
        netStatus = "Verifica rete...";
        updateStatusView();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://clients3.google.com/generate_204").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                int code = c.getResponseCode();
                c.disconnect();
                netStatus = "ONLINE OK (HTTP " + code + ")";
            } catch (Exception e) {
                netStatus = "OFFLINE: " + e.getClass().getSimpleName();
            }
            runOnUiThread(this::updateStatusView);
        }).start();
    }

    private void saveMac(String mac) {
        try { Files.write(new File(getFilesDir(), "mac.txt").toPath(), mac.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
    }

    private String loadMac() {
        try {
            File f = new File(getFilesDir(), "mac.txt");
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        return "";
    }
}
