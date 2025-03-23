package cn.mrack.sock;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.app.NotificationManagerCompat;

import cn.mrack.socks2vpn.R;


public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private Intent vpnServiceIntent;

    private EditText proxyHostEditText;
    private EditText proxyPortEditText;
    private EditText proxyUsernameEditText;
    private EditText proxyPasswordEditText;
    private TextView statisticsTextView;
    private Switch vpnSwitch;

    private SharedPreferences sharedPreferences;

    private static final String PREFS_NAME = "ProxyPrefs";
    private static final String KEY_PROXY_HOST = "proxy_host";
    private static final String KEY_PROXY_PORT = "proxy_port";
    private static final String KEY_PROXY_USERNAME = "proxy_username";
    private static final String KEY_PROXY_PASSWORD = "proxy_password";
    private Runnable uiUpdateRunnable;

    private void saveProxyPreferences(String proxyHost, String proxyPort, String proxyUsername, String proxyPassword) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_PROXY_HOST, proxyHost);
        editor.putString(KEY_PROXY_PORT, proxyPort);
        editor.putString(KEY_PROXY_USERNAME, proxyUsername);
        editor.putString(KEY_PROXY_PASSWORD, proxyPassword);
        editor.apply(); // Save changes asynchronously
    }

    private void loadSavedPreferences() {
        String proxyHost = sharedPreferences.getString(KEY_PROXY_HOST, "");
        String proxyPort = sharedPreferences.getString(KEY_PROXY_PORT, "");
        String proxyUsername = sharedPreferences.getString(KEY_PROXY_USERNAME, "");
        String proxyPassword = sharedPreferences.getString(KEY_PROXY_PASSWORD, "");

        proxyHostEditText.setText(proxyHost);
        proxyPortEditText.setText(proxyPort);
        proxyUsernameEditText.setText(proxyUsername);
        proxyPasswordEditText.setText(proxyPassword);
    }

    private Handler uiHandler;
    private static final long REFRESH_INTERVAL = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        long[] olongArray = new long[14];
        byte[] uobyteArray = new byte[300];
        byte[] uobyteArray1 = new byte[100];

        proxyHostEditText = findViewById(R.id.proxy_host);
        proxyPortEditText = findViewById(R.id.proxy_port);
        proxyUsernameEditText = findViewById(R.id.proxy_username);
        proxyPasswordEditText = findViewById(R.id.proxy_password);
        statisticsTextView = findViewById(R.id.statistics);

        vpnSwitch = findViewById(R.id.vpn_switch);
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadSavedPreferences();

        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, 1);
        }

        vpnServiceIntent = new Intent(this, ProxifierVpnService.class);

        vpnSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startVpnService();
            } else {
                stopVpnService();
            }
        });

        uiHandler = new Handler();

        // Defining the Runnable that will update the UI
        uiUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                statisticsTextView.setText(GetStatisticsFromNative(false));
                uiHandler.postDelayed(this, REFRESH_INTERVAL);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(uiUpdateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiUpdateRunnable);
    }



    static {
        System.loadLibrary("native-lib");
    }

    private void startVpnService() {

        SetSettings(true, false, 2, 0);
        String proxyHost = proxyHostEditText.getText().toString().trim();
        String proxyPortString = proxyPortEditText.getText().toString().trim();
        String proxyUsername = proxyUsernameEditText.getText().toString().trim();
        String proxyPassword = proxyPasswordEditText.getText().toString().trim();

        // Ensure that the port is a valid integer
        int proxyPort = 0;
        if (!proxyPortString.isEmpty()) {
            try {
                proxyPort = Integer.parseInt(proxyPortString);
            } catch (NumberFormatException e) {
                proxyPort = 0; // Or handle the error accordingly
            }
        }
        saveProxyPreferences(proxyHost, proxyPortString, proxyUsername, proxyPassword);
        SetProxyParamToNative("SOCKS", proxyHost, proxyPort, proxyUsername, proxyPassword);

        boolean isNotifyEnable = NotificationManagerCompat.from(getApplicationContext()).areNotificationsEnabled();
        if (!isNotifyEnable) {
            Intent intent = new Intent();
            if (!(getApplicationContext() instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            getApplicationContext().startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else {
            vpnServiceIntent.setAction("start");
            vpnServiceIntent.putExtra("appNames", new String[]{});
            vpnServiceIntent.putExtra("isProcess", false);
            if (Build.VERSION.SDK_INT >= 26) {
                vpnServiceIntent.putExtra("isForeground", true);
                startForegroundService(vpnServiceIntent);
            } else {
                startService(vpnServiceIntent);
            }

            Log.d(TAG, "VPN Service Started");
        }
    }

    private void stopVpnService() {
        vpnServiceIntent.setAction("stop");
        startService(vpnServiceIntent);
        Log.d(TAG, "VPN Service Stopped");
    }

    private native long GetCurrentConnections(long j, long[] jArr, byte[] bArr, byte[] bArr2);

    public static native String GetStatisticsFromNative(boolean z);

    private native void SetProxyParamToNative(String str, String str2, int i, String str3, String str4);

    public static native boolean SetSettings(boolean z, boolean z2, int i, int i2);

    private final BroadcastReceiver vpnStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean isRunning = intent.getBooleanExtra("isRunning", false);
            Log.d(TAG, "VPN Running: " + isRunning);
            vpnSwitch.setChecked(isRunning);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(vpnStatusReceiver, new IntentFilter("vpnStatus"), Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(vpnStatusReceiver);
    }

}