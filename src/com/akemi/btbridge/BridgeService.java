package com.akemi.btbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;

public class BridgeService extends Service {
    private static final UUID BRIDGE_UUID =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    private static final int PORT = 31416;

    private static volatile boolean relayRunning = false;
    private volatile boolean running = false;
    private BluetoothServerSocket activeBtServer;
    private ServerSocket activeTcpServer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            cleanup();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            System.exit(0);
            return START_NOT_STICKY;
        }

        NotificationChannel ch = new NotificationChannel(
                "bt_bridge", "BT Bridge", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification notif = new Notification.Builder(this, "bt_bridge")
                .setContentTitle("BtBridge attivo")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();
        startForeground(1, notif);

        cleanup();
        acquireWakeLock();
        running = true;

        final String mode = intent != null ? intent.getStringExtra("mode") : "server";
        final String mac = intent != null ? intent.getStringExtra("mac") : "";

        new Thread(() -> {
            if ("client".equals(mode)) runClient(mac);
            else runServer();
        }).start();

        return START_STICKY;
    }

    private void runServer() {
        if (!relayRunning) {
            relayRunning = true;
            new Thread(() -> {
                try {
                    com.genymobile.gnirehtet.Main.main(new String[]{"relay"});
                } catch (Throwable ignored) {
                    relayRunning = false;
                }
            }).start();
            sleepQuiet(500);
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        while (running) {
            try {
                activeBtServer = adapter.listenUsingRfcommWithServiceRecord("BtBridge", BRIDGE_UUID);
                while (running) {
                    final BluetoothSocket bt = activeBtServer.accept();
                    new Thread(() -> {
                        try {
                            Socket tcp = new Socket("127.0.0.1", PORT);
                            tcp.setTcpNoDelay(true);
                            pipeBidirectional(bt, tcp);
                        } catch (Exception e) {
                            try { bt.close(); } catch (Exception ignored) {}
                        }
                    }).start();
                }
            } catch (Exception e) {
                sleepQuiet(1500);
            }
        }
    }

    private void runClient(String mac) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        while (running) {
            try {
                activeTcpServer = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
                while (running) {
                    final Socket tcp = activeTcpServer.accept();
                    tcp.setTcpNoDelay(true);
                    new Thread(() -> {
                        try {
                            BluetoothDevice dev = adapter.getRemoteDevice(mac);
                            BluetoothSocket bt = dev.createRfcommSocketToServiceRecord(BRIDGE_UUID);
                            bt.connect();
                            pipeBidirectional(bt, tcp);
                        } catch (Exception e) {
                            try { tcp.close(); } catch (Exception ignored) {}
                        }
                    }).start();
                }
            } catch (Exception e) {
                sleepQuiet(1500);
            }
        }
    }

    private void pipeBidirectional(BluetoothSocket bt, Socket tcp) {
        Thread t1 = new Thread(() -> copyStream(bt, tcp, true));
        Thread t2 = new Thread(() -> copyStream(bt, tcp, false));
        t1.start();
        t2.start();
        try { t1.join(); t2.join(); } catch (InterruptedException ignored) {}
    }

    private void copyStream(BluetoothSocket bt, Socket tcp, boolean btToTcp) {
        byte[] buf = new byte[8192];
        try {
            InputStream in = btToTcp ? bt.getInputStream() : tcp.getInputStream();
            OutputStream out = btToTcp ? tcp.getOutputStream() : bt.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { bt.close(); } catch (Exception ignored) {}
            try { tcp.close(); } catch (Exception ignored) {}
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtBridge::TunnelLock");
            wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void cleanup() {
        running = false;
        try { if (activeBtServer != null) activeBtServer.close(); } catch (Exception ignored) {}
        try { if (activeTcpServer != null) activeTcpServer.close(); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
