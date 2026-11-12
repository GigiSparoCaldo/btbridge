package com.akemi.btbridge;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
        statusText.setText("BtBridge Pronto");
        statusText.setPadding(0, 0, 0, 16);
        root.addView(statusText);

        String savedMac = loadMac();
        if (!savedMac.isEmpty()) {
            Button quickClientBtn = new Button(this);
            quickClientBtn.setText("Avvia CLIENT\n(" + savedMac + ")");
            quickClientBtn.setOnClickListener(v -> startBridge("client", savedMac));
            root.addView(quickClientBtn);
        }

        Button serverBtn = new Button(this);
        serverBtn.setText("Avvia SERVER (Z Flip)");
        serverBtn.setOnClickListener(v -> startBridge("server", ""));
        root.addView(serverBtn);

        Button vpnBtn = new Button(this);
        vpnBtn.setText("Attiva VPN (Watch)");
        vpnBtn.setOnClickListener(v -> startGnirehtet());
        root.addView(vpnBtn);

        Button testBtn = new Button(this);
        testBtn.setText("Test Internet");
        testBtn.setOnClickListener(v -> runNetTest());
        root.addView(testBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("Stop Bridge");
        stopBtn.setOnClickListener(v -> stopBridge());
        root.addView(stopBtn);

        TextView clientLabel = new TextView(this);
        clientLabel.setGravity(Gravity.CENTER);
        clientLabel.setText("\nScegli Z Flip (Client):");
        clientLabel.setPadding(0, 10, 0, 8);
        root.addView(clientLabel);

        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(devicesBox);

        scroll.addView(root);
        setContentView(scroll);

        requestBtPermissionAndLoad();
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
                b.setOnClickListener(v -> {
                    saveMac(mac);
                    startBridge("client", mac);
                });
                devicesBox.addView(b);
            }
        } catch (SecurityException e) {
            statusText.setText("Permesso BT mancante");
        }
    }

    private void startBridge(String mode, String mac) {
        Intent svc = new Intent(this, BridgeService.class);
        svc.putExtra("mode", mode);
        svc.putExtra("mac", mac);
        startForegroundService(svc);
        statusText.setText("ATTIVO: " + mode.toUpperCase() + (mac.isEmpty() ? "" : "\n" + mac));
    }

    private void stopBridge() {
        Intent svc = new Intent(this, BridgeService.class);
        svc.setAction("STOP");
        startService(svc);
        statusText.setText("FERMO");
    }

    private void startGnirehtet() {
        try {
            Intent i = new Intent("com.genymobile.gnirehtet.START");
            i.setComponent(new ComponentName("com.genymobile.gnirehtet", "com.genymobile.gnirehtet.GnirehtetActivity"));
            startActivity(i);
        } catch (Exception e) {
            statusText.setText("Installa prima gnirehtet.apk");
        }
    }

    private void runNetTest() {
        statusText.setText("Verifica rete in corso...");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://clients3.google.com/generate_204").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                int code = c.getResponseCode();
                c.disconnect();
                runOnUiThread(() -> statusText.setText("ONLINE OK (HTTP " + code + ")"));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("OFFLINE: " + e.getClass().getSimpleName()));
            }
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
