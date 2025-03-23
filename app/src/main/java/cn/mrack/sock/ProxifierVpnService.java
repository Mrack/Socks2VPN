package cn.mrack.sock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.preference.PreferenceManager;
import android.util.Log;


import androidx.core.app.NotificationCompat;

import cn.mrack.socks2vpn.R;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class ProxifierVpnService extends VpnService {
    private static final String TAG = "ProxifierVpnService";
    public static final String PARAM_IS_VPN_RUNNING = "isVpnRunning";
    public static final String PARAM_LISTENER = "pendingListener";
    public static final String PARAM_PINTENT = "pendingIntent";
    private static final String DEFAULT_DNS = "8.8.8.8";
    private static final String VPN_ADDRESS_IPV4 = "100.64.0.1";
    private static final String VPN_ADDRESS_IPV6 = "fd00:696e:6974:6578:5f66:696c:7465:7201";
    private static final String VPN_DNS_SERVER = "100.64.1.1";
    private static final int MTU_SIZE = 1500;

    private static Network mLastUpdatedNetwork;
    private static BroadcastReceiver vpnNotificationReceiver;
    private ParcelFileDescriptor mVpnFD = null;
    private Thread mWorkerThread = null;

    static native boolean SetDnsServer(String dns, String str2);

    private native boolean doVpnProcess(int fd, int sdkVersion, String str);

    private native boolean stopVpnProcess();

    static {
        System.loadLibrary("native-lib");
    }

    private static void logDebug(String message) {
        Log.d(TAG, message);
    }

    private int getUid(int i, String str, int i2, String str2, int i3) {
        if (i != 6 && i != 17) {
            return -1;
        }
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                return -1;
            }
            return connectivityManager.getConnectionOwnerUid(i, new InetSocketAddress(str, i2), new InetSocketAddress(str2, i3));
        } catch (Exception unused) {
            return -1;
        }
    }

    public static void updateDnsServers(Context context, Network network, LinkProperties linkProperties) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = connectivityManager != null ? connectivityManager.getActiveNetwork() : null;

        if (activeNetwork != null && network.getNetworkHandle() != activeNetwork.getNetworkHandle()) {
            logDebug("DNS network (" + network + ") does not match current (" + activeNetwork + ")");
        }

        mLastUpdatedNetwork = network;
        logDebug("Updated network = " + connectivityManager.getNetworkInfo(mLastUpdatedNetwork));

        if (linkProperties == null) {
            linkProperties = connectivityManager.getLinkProperties(network);
        }

        String dns = extractDns(linkProperties);
        SetDnsServer(dns, "");
    }

    private static String extractDns(LinkProperties linkProperties) {
        if (linkProperties != null) {
            List<InetAddress> dnsServers = linkProperties.getDnsServers();
            for (InetAddress dns : dnsServers) {
                if (dns instanceof Inet4Address) {
                    return dns.getHostAddress();
                }
            }
            if (!dnsServers.isEmpty()) {
                return dnsServers.get(0).getHostAddress();
            }
        }
        return DEFAULT_DNS;
    }

    private void doUpdateDnsServer() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = connectivityManager != null ? connectivityManager.getActiveNetwork() : null;

        if (activeNetwork != null) {
            updateDnsServers(this, activeNetwork, connectivityManager.getLinkProperties(activeNetwork));
        }
    }

    private boolean processStartCommand(boolean isProcess, String[] appNames, int startId) {
        if (mVpnFD != null) {
            return false;
        }

        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString("supportLastStartServiceTime", getCurrentTimestamp());
        editor.apply();

        doUpdateDnsServer();

        Builder builder = createVpnBuilder(appNames, isProcess);

        try {
            this.mVpnFD = builder.establish();

            if (mVpnFD == null) {
                logDebug("VPN is not initialized");
                stopSelf(startId);
                return false;
            }

            startVpnProcess();
            return true;

        } catch (IllegalStateException e) {
            logDebug("VPN is not initialized: " + e.getLocalizedMessage());
            stopSelf(startId);
            return false;
        }
    }

    private Builder createVpnBuilder(String[] appNames, boolean isProcess) {
        Builder builder = new Builder();
        builder.addAddress(VPN_ADDRESS_IPV4, 32);
        builder.addAddress(VPN_ADDRESS_IPV6, 128);
        builder.addDnsServer(VPN_DNS_SERVER);
        builder.addRoute("0.0.0.0", 0);
        builder.addRoute("0:0:0:0:0:0:0:0", 0);
        builder.setMtu(MTU_SIZE);
        builder.setBlocking(false);

        try {
            if (appNames != null && appNames.length > 0) {
                if (isProcess) {
                    builder.addAllowedApplication(getPackageName());
                }
                for (String appName : appNames) {
                    if (isProcess) {
                        builder.addAllowedApplication(appName);
                    } else {
                        builder.addDisallowedApplication(appName);
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        return builder;
    }

    private void startVpnProcess() {
        final int fd = mVpnFD.getFd();
        logDebug("Proxifier started with VPN fd " + fd);
        final Context applicationContext = getApplicationContext();

        mWorkerThread = new Thread(() -> {
            Intent intent = new Intent("vpnStatus").setPackage(applicationContext.getPackageName());
            intent.putExtra("isRunning", true);
            applicationContext.sendBroadcast(intent);
            boolean result = doVpnProcess(fd, Build.VERSION.SDK_INT, null);
            intent.putExtra("isRunning", false);
            applicationContext.sendBroadcast(intent);
        }, "Vpn Processor");

        mWorkerThread.setPriority(Thread.NORM_PRIORITY);
        mWorkerThread.start();
    }

    private void processStopCommand(boolean isPersistent, int startId) {
        try {
            SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
            editor.putString("supportLastStopServiceTime", getCurrentTimestamp());
            editor.apply();

            stopVpnProcess();
            closeVpnFd(isPersistent);
            joinWorkerThread();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                stopForeground(true);
            }
            logDebug("Proxifier stopped");

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        if (startId >= 0) {
            stopSelf(startId);
        }
    }

    private void closeVpnFd(boolean isPersistent) throws IOException {
        if (mVpnFD != null) {
            mVpnFD.close();
            if (!isPersistent) {
                mVpnFD = null;
            }
        }
    }

    private void joinWorkerThread() throws InterruptedException {
        if (mWorkerThread != null) {
            if (mWorkerThread.isAlive()) {
                mWorkerThread.join(1000);
                if (mWorkerThread.isAlive()) {
                    mWorkerThread.interrupt();
                }
            }
            if (mWorkerThread.isAlive()) {
                mWorkerThread.join(10000);
            }
        }
        mWorkerThread = null; // Clear reference after stopping
    }

    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private void onGetStatus(Intent intent, int startId) {
        PendingIntent pendingIntent = intent.getParcelableExtra("pendingIntent");
        boolean isRunning = mVpnFD != null;

        // Send status back through PendingIntent
        if (pendingIntent != null) {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(PARAM_IS_VPN_RUNNING, isRunning);
            try {
                pendingIntent.send(this, 0, resultIntent);
            } catch (PendingIntent.CanceledException e) {
                logDebug("PendingIntent was canceled: " + e.getMessage());
            }
        }

        // Send status back through ResultReceiver
        ResultReceiver resultReceiver = intent.getParcelableExtra(PARAM_LISTENER);
        if (resultReceiver != null) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(PARAM_IS_VPN_RUNNING, isRunning);
            resultReceiver.send(-1, bundle);
        }

        if (mWorkerThread == null) {
            stopSelf(startId);
        }
    }

    private Notification buildForegroundNotification() {
        NotificationChannel notificationChannel;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationChannel = new NotificationChannel("CHANNEL_ONE_ID",
                    "CHANNEL_ONE_NAME", NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.setShowBadge(true);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(notificationChannel);
            }
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "CHANNEL_ONE_ID")
                .setChannelId("CHANNEL_ONE_ID")
                .setOngoing(true)
                .setContentTitle("Proxifier is running")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setTicker("Proxifier")
                .setShowWhen(false)
                .setSilent(true);
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logDebug("onStartCommand: " + intent);
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case "network_update":
                        handleNetworkUpdate(startId);
                        break;
                    case "stop":
                        processStopCommand(false, startId);
                        break;
                    case "start":
                        handleStartCommand(intent, startId);
                        break;
                    case "getStatus":
                        onGetStatus(intent, startId);
                        break;
                    default:
                        break;
                }
            }
        }
        return Service.START_STICKY;
    }

    private void handleNetworkUpdate(int startId) {
        if (mLastUpdatedNetwork != null) {
            logDebug("Updated network = " + ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).getNetworkInfo(mLastUpdatedNetwork));
        }
        stopSelf(startId);
    }

    private void handleStartCommand(Intent intent, int startId) {
        try {
            String[] appNames = intent.getStringArrayExtra("appNames");
            boolean isProcess = intent.getBooleanExtra("isProcess", true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intent.getBooleanExtra("isForeground", false)) {
                startForeground(101, buildForegroundNotification());
            }
            if (!processStartCommand(isProcess, appNames, startId)) {
                stopSelf(startId);
            }
        } catch (Exception e) {
            logDebug("VPN service start failed: " + e.getLocalizedMessage());
            stopSelf(startId);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (vpnNotificationReceiver == null) {
            vpnNotificationReceiver = new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    // Process notification actions if needed
                }
            };
            registerReceiver(vpnNotificationReceiver, new IntentFilter("vpnNotification"), Context.RECEIVER_EXPORTED);
        }
        logDebug("VPN service created");

    }

    @Override
    public void onDestroy() {
        if (mVpnFD != null || mWorkerThread != null) {
            processStopCommand(false, -1);
        }
        if (vpnNotificationReceiver != null) {
            unregisterReceiver(vpnNotificationReceiver);
            vpnNotificationReceiver = null;
        }
        super.onDestroy();
        logDebug("VPN service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onRevoke() {
        processStopCommand(false, -1);
        super.onRevoke();
    }

    public boolean myProtect(int uid) {
        return super.protect(uid);
    }
}