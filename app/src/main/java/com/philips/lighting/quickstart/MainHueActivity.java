package com.philips.lighting.quickstart;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.philips.lighting.data.HueSharedPreferences;
import com.philips.lighting.hue.sdk.wrapper.connection.BridgeConnection;
import com.philips.lighting.hue.sdk.wrapper.connection.BridgeConnectionCallback;
import com.philips.lighting.hue.sdk.wrapper.connection.BridgeConnectionType;
import com.philips.lighting.hue.sdk.wrapper.connection.BridgeStateUpdatedCallback;
import com.philips.lighting.hue.sdk.wrapper.connection.BridgeStateUpdatedEvent;
import com.philips.lighting.hue.sdk.wrapper.connection.ConnectionEvent;
import com.philips.lighting.hue.sdk.wrapper.discovery.BridgeDiscovery;
import com.philips.lighting.hue.sdk.wrapper.discovery.BridgeDiscoveryCallback;
import com.philips.lighting.hue.sdk.wrapper.discovery.BridgeDiscoveryResult;
import com.philips.lighting.hue.sdk.wrapper.domain.Bridge;
import com.philips.lighting.hue.sdk.wrapper.domain.BridgeBuilder;
import com.philips.lighting.hue.sdk.wrapper.domain.HueError;
import com.philips.lighting.hue.sdk.wrapper.domain.ReturnCode;
import com.philips.lighting.hue.sdk.wrapper.knownbridges.KnownBridge;
import com.philips.lighting.hue.sdk.wrapper.knownbridges.KnownBridges;
import com.recek.huesleepwake.R;
import com.recek.huewakeup.app.MainActivity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainHueActivity extends AppCompatActivity
        implements View.OnClickListener, AdapterView.OnItemClickListener {

    private static final Logger LOG = LoggerFactory.getLogger(MainHueActivity.class);

    HueSharedPreferences prefs;

    private Bridge bridge;
    private BridgeDiscovery bridgeDiscovery;
    private List<BridgeDiscoveryResult> bridgeDiscoveryResults;

    // UI elements
    private TextView statusTextView;
    private ListView bridgeDiscoveryListView;
    private TextView bridgeIpTextView;
    private View pushlinkImage;
    private Button bridgeDiscoveryButton;
    private Button demoButton;

    enum UIState {
        Idle,
        BridgeDiscoveryRunning,
        BridgeDiscoveryResults,
        Connecting,
        Pushlinking,
        Connected
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hue_main);

        prefs = HueSharedPreferences.getInstance(getApplicationContext());
        if (showDialogOnFirstRun()) {
            return;
        }

        setupUiAndConnectToBridge();
    }

    private void setupUiAndConnectToBridge() {
        // Setup the UI
        statusTextView = findViewById(R.id.status_text);
        bridgeDiscoveryListView = findViewById(R.id.bridge_discovery_result_list);
        bridgeDiscoveryListView.setOnItemClickListener(this);
        bridgeIpTextView = findViewById(R.id.bridge_ip_text);
        pushlinkImage = findViewById(R.id.pushlink_image);
        bridgeDiscoveryButton = findViewById(R.id.bridge_discovery_button);
        bridgeDiscoveryButton.setOnClickListener(this);
        demoButton = findViewById(R.id.demoBtn);

        // Connect to a bridge or start the bridge discovery
        String lastUsedBridgeIp = getLastUsedBridgeIp();
        if (lastUsedBridgeIp == null) {
            startBridgeDiscovery();
        } else {
            connectToBridge(lastUsedBridgeIp);
        }
    }

    private boolean showDialogOnFirstRun() {
        String versionName = getVersionName();
        String lastVersionName = prefs.getLastAppVersionName();

        if (lastVersionName == null || lastVersionName.length() == 0 || versionName == null
                || !versionName.equals(lastVersionName)) {
            showEulaDialog(versionName);
            return true;
        }
        return false;
    }

    private String getVersionName() {
        try {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            LOG.error("Could not get version Name: " + e.getMessage());
            return null;
        }
    }

    private void showEulaDialog(final String versionName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.txt_eula_title);
        builder.setMessage(R.string.txt_eula_content)
                .setPositiveButton(R.string.btn_agree, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        LOG.debug("Agree EULA.");
                        prefs.setLastAppVersionName(versionName);
                        setupUiAndConnectToBridge();
                    }
                })
                .setNegativeButton(R.string.btn_decline, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        LOG.debug("Decline EULA.");
                        finish();
                    }
                });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                LOG.debug("Canceled EULA.");
                finish();
            }
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    /**
     * Use the KnownBridges API to retrieve the last connected bridge
     *
     * @return Ip address of the last connected bridge, or null
     */
    private String getLastUsedBridgeIp() {
        List<KnownBridge> bridges = KnownBridges.getAll();

        if (bridges.isEmpty()) {
            return null;
        }

        return Collections.max(bridges, new Comparator<KnownBridge>() {
            @Override
            public int compare(KnownBridge a, KnownBridge b) {
                return a.getLastConnected().compareTo(b.getLastConnected());
            }
        }).getIpAddress();
    }

    /**
     * Start the bridge discovery search
     * Read the documentation on meethue for an explanation of the bridge discovery options
     */
    private void startBridgeDiscovery() {
        disconnectFromBridge();
        bridgeDiscovery = new BridgeDiscovery();
        // ALL Include [UPNP, IPSCAN, NUPNP] but in some nets UPNP and NUPNP is not working properly
        bridgeDiscovery.search(BridgeDiscovery.BridgeDiscoveryOption.ALL, bridgeDiscoveryCallback);
        updateUI(UIState.BridgeDiscoveryRunning, "Scanning the network for hue bridges...");
    }

    /**
     * Stops the bridge discovery if it is still running
     */
    private void stopBridgeDiscovery() {
        if (bridgeDiscovery != null) {
            bridgeDiscovery.stop();
            bridgeDiscovery = null;
        }
    }

    /**
     * The callback that receives the results of the bridge discovery
     */
    private BridgeDiscoveryCallback bridgeDiscoveryCallback = new BridgeDiscoveryCallback() {
        @Override
        public void onFinished(final List<BridgeDiscoveryResult> results,
                               final ReturnCode returnCode) {
            // Set to null to prevent stopBridgeDiscovery from stopping it
            bridgeDiscovery = null;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (returnCode == ReturnCode.SUCCESS) {
                        bridgeDiscoveryListView.setAdapter(
                                new BridgeDiscoveryResultAdapter(getApplicationContext(), results));
                        bridgeDiscoveryResults = results;

                        updateUI(UIState.BridgeDiscoveryResults,
                                "Found " + results.size() + " bridge(s) in the network.");
                    } else if (returnCode == ReturnCode.STOPPED) {
                        LOG.info("Bridge discovery stopped.");
                    } else {
                        updateUI(UIState.Idle, "Error doing bridge discovery: " + returnCode);
                    }
                }
            });
        }
    };

    /**
     * Use the BridgeBuilder to create a bridge instance and connect to it
     */
    private void connectToBridge(String bridgeIp) {
        stopBridgeDiscovery();
        disconnectFromBridge();

        bridge = new BridgeBuilder(getString(R.string.app_name), "device name")
                .setIpAddress(bridgeIp)
                .setConnectionType(BridgeConnectionType.LOCAL)
                .setBridgeConnectionCallback(bridgeConnectionCallback)
                .addBridgeStateUpdatedCallback(bridgeStateUpdatedCallback)
                .build();

        bridge.connect();
        BridgeHolder.set(bridge);

        bridgeIpTextView.setText("Bridge IP: " + bridgeIp);
        updateUI(UIState.Connecting, "Connecting to bridge...");
    }

    /**
     * Disconnect a bridge
     * The hue SDK supports multiple bridge connections at the same time,
     * but for the purposes of this demo we only connect to one bridge at a time.
     */
    private void disconnectFromBridge() {
        if (bridge != null) {
            bridge.disconnect();
            bridge = null;
            BridgeHolder.clear();
        }
    }

    /**
     * The callback that receives bridge connection events
     */
    private BridgeConnectionCallback bridgeConnectionCallback = new BridgeConnectionCallback() {
        @Override
        public void onConnectionEvent(BridgeConnection bridgeConnection,
                                      ConnectionEvent connectionEvent) {
            LOG.info("Connection event: " + connectionEvent);

            switch (connectionEvent) {
                case LINK_BUTTON_NOT_PRESSED:
                    updateUI(UIState.Pushlinking, "Press the link button to authenticate.");
                    break;

                case COULD_NOT_CONNECT:
                    updateUI(UIState.Connecting, "Could not connect.");
                    break;

                case CONNECTION_LOST:
                    updateUI(UIState.Connecting, "Connection lost. Attempting to reconnect.");
                    break;

                case CONNECTION_RESTORED:
                    updateUI(UIState.Connected, "Connection restored.");
                    break;

                case DISCONNECTED:
                    // User-initiated disconnection.
                    break;

                default:
                    break;
            }
        }

        @Override
        public void onConnectionError(BridgeConnection bridgeConnection, List<HueError> list) {
            for (HueError error : list) {
                LOG.error("Connection error: " + error.toString());
            }
        }
    };

    /**
     * The callback the receives bridge state update events
     */
    private BridgeStateUpdatedCallback bridgeStateUpdatedCallback = new BridgeStateUpdatedCallback() {
        @Override
        public void onBridgeStateUpdated(Bridge bridge,
                                         BridgeStateUpdatedEvent bridgeStateUpdatedEvent) {
            LOG.info("Bridge state updated event: " + bridgeStateUpdatedEvent);

            switch (bridgeStateUpdatedEvent) {
                case INITIALIZED:
                    // The bridge state was fully initialized for the first time.
                    // It is now safe to perform operations on the bridge state.
                    updateUI(UIState.Connected, "Connected!");
                    break;

                case LIGHTS_AND_GROUPS:
                    // At least one light was updated.
                    break;

                default:
                    break;
            }
        }
    };

    // UI methods

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
        String bridgeIp = bridgeDiscoveryResults.get(i).getIP();

        connectToBridge(bridgeIp);
    }

    @Override
    public void onClick(View view) {
        if (view == bridgeDiscoveryButton) {
            startBridgeDiscovery();
        }
    }

    private void updateUI(final UIState state, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                LOG.info("Status: " + status);
                statusTextView.setText(status);

                bridgeDiscoveryListView.setVisibility(View.GONE);
                bridgeIpTextView.setVisibility(View.GONE);
                pushlinkImage.setVisibility(View.GONE);
                bridgeDiscoveryButton.setVisibility(View.GONE);
                demoButton.setVisibility(View.GONE);

                switch (state) {
                    case Idle:
                        bridgeDiscoveryButton.setVisibility(View.VISIBLE);
                        break;
                    case BridgeDiscoveryRunning:
                        bridgeDiscoveryListView.setVisibility(View.VISIBLE);
                        break;
                    case BridgeDiscoveryResults:
                        bridgeDiscoveryListView.setVisibility(View.VISIBLE);
                        if (status.contains(" 0 "))
                            demoButton.setVisibility(View.VISIBLE);
                        break;
                    case Connecting:
                        bridgeIpTextView.setVisibility(View.VISIBLE);
                        bridgeDiscoveryButton.setVisibility(View.VISIBLE);
                        break;
                    case Pushlinking:
                        bridgeIpTextView.setVisibility(View.VISIBLE);
                        pushlinkImage.setVisibility(View.VISIBLE);
                        bridgeDiscoveryButton.setVisibility(View.VISIBLE);
                        break;
                    case Connected:
                        bridgeIpTextView.setVisibility(View.VISIBLE);
                        bridgeDiscoveryButton.setVisibility(View.VISIBLE);
                        startAppActivity(true);
                        break;
                }
            }
        });
    }

    // Starting the main activity this way, prevents the PushLink Activity being shown when pressing the back button.
    private void startAppActivity(boolean isConnected) {
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("isConnected", isConnected);
        startActivity(intent);
    }

    public void startDemo(View view) {
        startAppActivity(false);
    }
}
