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
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;

public class BridgeService extends Service {
    private static final UUID BRIDGE_UUID =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    private static final int PORT = 31416;

    public static volatile String lastInfo = "Pronto";
    private static volatile boolean relayRunning = false;
    private static volatile String relayError = null;
    private volatile boolean running = false;
    private volatile int sessionId = 0;
    private BluetoothServerSocket activeBtServer;
    private LocalServerSocket activeLocalServer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            sessionId++;
            cleanup();
            lastInfo = "Fermo";
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

        final int mySession = ++sessionId;
        cleanup();
        acquireWakeLock();
        running = true;

        final String mode = intent != null ? intent.getStringExtra("mode") : "server";
        final String mac = intent != null ? intent.getStringExtra("mac") : "";

        new Thread(() -> {
            if ("client".equals(mode)) runClient(mac, mySession);
            else runServer(mySession);
        }).start();

        return START_STICKY;
    }

    private void runServer(int mySession) {
        if (!relayRunning) {
            relayRunning = true;
            relayError = null;
            new Thread(() -> {
                try {
                    com.genymobile.gnirehtet.Main.main(new String[]{"relay"});
                    relayError = "Relay terminato";
                } catch (Throwable t) {
                    relayError = t.getClass().getSimpleName() + ": " + t.getMessage();
                    relayRunning = false;
                }
            }).start();
            sleepQuiet(700);
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        while (running && sessionId == mySession) {
            try {
                activeBtServer = adapter.listenUsingInsecureRfcommWithServiceRecord("BtBridge", BRIDGE_UUID);
                if (sessionId == mySession) {
                    lastInfo = (relayError != null) ? ("Err relay: " + relayError) : "Server BT in ascolto";
                }
                while (running && sessionId == mySession) {
                    final BluetoothSocket bt = activeBtServer.accept();
                    new Thread(() -> {
                        try {
                            Socket tcp = connectLocalRelay();
                            if (sessionId == mySession) lastInfo = "Tunnel attivo con Watch!";
                            Runnable closeAll = () -> {
                                try { bt.close(); } catch (Exception ignored) {}
                                try { tcp.close(); } catch (Exception ignored) {}
                            };
                            pipeStreams(bt.getInputStream(), tcp.getOutputStream(),
                                    tcp.getInputStream(), bt.getOutputStream(), closeAll);
                        } catch (Exception e) {
                            if (sessionId == mySession) {
                                lastInfo = (relayError != null)
                                        ? ("Err relay: " + relayError)
                                        : ("Err TCP ZFlip: " + e.getMessage());
                            }
                            try { bt.close(); } catch (Exception ignored) {}
                        }
                    }).start();
                }
            } catch (Exception e) {
                if (sessionId == mySession) {
                    lastInfo = "Err BT Server: " + e.getMessage();
                    sleepQuiet(1500);
                }
            }
        }
    }

    private Socket connectLocalRelay() throws Exception {
        try {
            Socket s = new Socket(InetAddress.getLoopbackAddress(), PORT);
            s.setTcpNoDelay(true);
            return s;
        } catch (Exception ignored) {}
        try {
            Socket s = new Socket("::1", PORT);
            s.setTcpNoDelay(true);
            return s;
        } catch (Exception ignored) {}
        Socket s = new Socket("127.0.0.1", PORT);
        s.setTcpNoDelay(true);
        return s;
    }

    private void runClient(String mac, int mySession) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        while (running && sessionId == mySession) {
            try {
                activeLocalServer = new LocalServerSocket("gnirehtet");
                if (sessionId == mySession) lastInfo = "Client pronto (" + mac + ")";
                while (running && sessionId == mySession) {
                    final LocalSocket local = activeLocalServer.accept();
                    new Thread(() -> {
                        BluetoothSocket bt = null;
                        try {
                            BluetoothDevice dev = adapter.getRemoteDevice(mac);
                            bt = connectBt(dev);
                            if (sessionId == mySession) lastInfo = "BT connesso a Z Flip!";
                            final BluetoothSocket finalBt = bt;
                            Runnable closeAll = () -> {
                                try { finalBt.close(); } catch (Exception ignored) {}
                                try { local.close(); } catch (Exception ignored) {}
                            };
                            pipeStreams(finalBt.getInputStream(), local.getOutputStream(),
                                    local.getInputStream(), finalBt.getOutputStream(), closeAll);
                        } catch (Exception e) {
                            if (sessionId == mySession) lastInfo = "Err BT: " + e.getMessage();
                            try { if (bt != null) bt.close(); } catch (Exception ignored) {}
                            try { local.close(); } catch (Exception ignored) {}
                        }
                    }).start();
                }
            } catch (Exception e) {
                if (sessionId == mySession) {
                    lastInfo = "Err LocalSocket: " + e.getMessage();
                    sleepQuiet(1500);
                }
            }
        }
    }

    private BluetoothSocket connectBt(BluetoothDevice dev) throws Exception {
        try {
            BluetoothSocket s = dev.createInsecureRfcommSocketToServiceRecord(BRIDGE_UUID);
            s.connect();
            return s;
        } catch (Exception ignored) {}
        try {
            BluetoothSocket s = dev.createRfcommSocketToServiceRecord(BRIDGE_UUID);
            s.connect();
            return s;
        } catch (Exception ignored) {}
        Method m = dev.getClass().getMethod("createInsecureRfcommSocket", int.class);
        BluetoothSocket s = (BluetoothSocket) m.invoke(dev, 1);
        s.connect();
        return s;
    }

    private void pipeStreams(InputStream in1, OutputStream out1,
                             InputStream in2, OutputStream out2, Runnable closeAll) {
        Thread t1 = new Thread(() -> copyLoop(in1, out1, closeAll));
        Thread t2 = new Thread(() -> copyLoop(in2, out2, closeAll));
        t1.start();
        t2.start();
        try { t1.join(); t2.join(); } catch (InterruptedException ignored) {}
    }

    private void copyLoop(InputStream in, OutputStream out, Runnable closeAll) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            closeAll.run();
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
        try { if (activeLocalServer != null) activeLocalServer.close(); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @Override
    public void onDestroy() {
        sessionId++;
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
