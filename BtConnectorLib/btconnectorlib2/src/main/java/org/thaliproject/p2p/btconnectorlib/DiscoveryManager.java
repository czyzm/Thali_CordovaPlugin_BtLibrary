/* Copyright (c) 2015-2016 Microsoft Corporation. This software is licensed under the MIT License.
 * See the license file delivered with this project for further information.
 */
package org.thaliproject.p2p.btconnectorlib;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.util.Log;
import org.thaliproject.p2p.btconnectorlib.internal.AbstractBluetoothConnectivityAgent;
import org.thaliproject.p2p.btconnectorlib.internal.BluetoothMacAddressResolutionHelper;
import org.thaliproject.p2p.btconnectorlib.internal.CommonUtils;
import org.thaliproject.p2p.btconnectorlib.internal.bluetooth.BluetoothDeviceDiscoverer;
import org.thaliproject.p2p.btconnectorlib.internal.bluetooth.BluetoothUtils;
import org.thaliproject.p2p.btconnectorlib.internal.bluetooth.le.BlePeerDiscoverer;
import org.thaliproject.p2p.btconnectorlib.internal.wifi.WifiDirectManager;
import org.thaliproject.p2p.btconnectorlib.internal.wifi.WifiPeerDiscoverer;
import org.thaliproject.p2p.btconnectorlib.utils.PeerModel;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.UUID;

/**
 * The main interface for managing peer discovery.
 */
public class DiscoveryManager
        extends AbstractBluetoothConnectivityAgent
        implements
            WifiDirectManager.WifiStateListener,
            WifiPeerDiscoverer.WifiPeerDiscoveryListener,
            BlePeerDiscoverer.BlePeerDiscoveryListener,
            BluetoothDeviceDiscoverer.BluetoothDeviceDiscovererListener,
            BluetoothMacAddressResolutionHelper.BluetoothMacAddressResolutionHelperListener,
            PeerModel.Listener,
            DiscoveryManagerSettings.Listener {

    public enum DiscoveryManagerState {
        NOT_STARTED,
        WAITING_FOR_SERVICES_TO_BE_ENABLED, // When the chosen peer discovery method is disabled and waiting for it to be enabled to start
        WAITING_FOR_BLUETOOTH_MAC_ADDRESS, // When we don't know our own Bluetooth MAC address
        PROVIDING_BLUETOOTH_MAC_ADDRESS, // When helping a peer by providing it its Bluetooth MAC address
        RUNNING_BLE,
        RUNNING_WIFI,
        RUNNING_BLE_AND_WIFI
    }

    public enum DiscoveryMode {
        NOT_SET,
        BLE,
        WIFI,
        BLE_AND_WIFI
    }

    public interface DiscoveryManagerListener {
        /**
         * Called when a permission check for a certain functionality is needed. The activity
         * utilizing this class then needs to perform the check and return true, if allowed.
         *
         * Note: The permission check is only needed if we are running on Marshmallow (Android
         * version 6.x) or higher.
         *
         * @param permission The permission to check.
         * @return True, if permission is granted. False, if not.
         */
        boolean onPermissionCheckRequired(String permission);

        /**
         * Called when the state of this instance is changed.
         * @param state The new state.
         */
        void onDiscoveryManagerStateChanged(DiscoveryManagerState state);

        /**
         * Called when we discovery a device, which needs to find out its own Bluetooth MAC address.
         *
         * Part of Bro Mode.
         *
         * Note: If the Bluetooth MAC address resolution process is set to be automated, this
         * callback will not be called.
         *
         * @param requestId The request ID associated with the device.
         */
        void onProvideBluetoothMacAddressRequest(String requestId);

        /**
         * Called when we see that a peer is willing to provide us our own Bluetooth MAC address
         * via Bluetooth device discovery. After receiving this event, we should make our device
         * discoverable via Bluetooth.
         *
         * Part of Bro Mode.
         *
         * Note: If the Bluetooth MAC address resolution process is set to be automated, this
         * callback will not be called.
         */
        void onPeerReadyToProvideBluetoothMacAddress();

        /**
         * Called when the Bluetooth MAC address of this device is resolved.
         *
         * Part of Bro Mode.
         *
         * @param bluetoothMacAddress The Bluetooth MAC address.
         */
        void onBluetoothMacAddressResolved(String bluetoothMacAddress);

        /**
         * Called when a new peer is discovered.
         * @param peerProperties The properties of the new peer.
         */
        void onPeerDiscovered(PeerProperties peerProperties);

        /**
         * Called when a peer data is updated (missing data is added). This callback is never
         * called, if data is lost.
         * @param peerProperties The updated properties of a discovered peer.
         */
        void onPeerUpdated(PeerProperties peerProperties);

        /**
         * Called when an existing peer is lost (i.e. not available anymore).
         * @param peerProperties The properties of the lost peer.
         */
        void onPeerLost(PeerProperties peerProperties);
    }

    private static final String TAG = DiscoveryManager.class.getName();

    private final Context mContext;
    private final DiscoveryManagerListener mListener;
    private final UUID mBleServiceUuid;
    private final String mServiceType;
    private final Handler mHandler;
    private WifiDirectManager mWifiDirectManager = null;
    private BlePeerDiscoverer mBlePeerDiscoverer = null;
    private WifiPeerDiscoverer mWifiPeerDiscoverer = null;
    private BluetoothDeviceDiscoverer mBluetoothDeviceDiscoverer = null;
    private DiscoveryManagerState mState = DiscoveryManagerState.NOT_STARTED;
    private DiscoveryManagerSettings mSettings = null;
    private PeerModel mPeerModel = null;
    private BluetoothMacAddressResolutionHelper mBluetoothMacAddressResolutionHelper = null;
    private String mMissingPermission = null;
    private long mLastTimeDeviceWasMadeDiscoverable = 0;
    private int mPreviousDeviceDiscoverableTimeInSeconds = 0;
    private boolean mShouldBeRunning = false;

    /**
     * Constructor.
     * @param context The application context.
     * @param listener The listener.
     * @param bleServiceUuid Our BLE service UUID (both ours and requirement for the peer).
     *                       Required by BLE based peer discovery only.
     * @param serviceType The service type (both ours and requirement for the peer).
     *                    Required by Wi-Fi Direct based peer discovery only.
     */
    public DiscoveryManager(Context context, DiscoveryManagerListener listener, UUID bleServiceUuid, String serviceType) {
        super(context); // Gets the BluetoothManager instance

        mContext = context;
        mListener = listener;
        mBleServiceUuid = bleServiceUuid;
        mServiceType = serviceType;

        mBluetoothMacAddressResolutionHelper = new BluetoothMacAddressResolutionHelper(
                context, this, mBleServiceUuid, BlePeerDiscoverer.generateNewProvideBluetoothMacAddressRequestUuid(mBleServiceUuid));

        mSettings = DiscoveryManagerSettings.getInstance(mContext);
        mSettings.load();
        mSettings.addListener(this);

        mPeerModel = new PeerModel(this, mSettings);

        mHandler = new Handler(mContext.getMainLooper());
        mWifiDirectManager = WifiDirectManager.getInstance(mContext);
    }

    /**
     * @return True, if Bluetooth LE advertising is supported. False otherwise.
     */
    public boolean isBleMultipleAdvertisementSupported() {
        return mBluetoothManager.isBleMultipleAdvertisementSupported();
    }

    /**
     * @return True, if Wi-Fi Direct is supported. False otherwise.
     */
    public boolean isWifiDirectSupported() {
        return mWifiDirectManager.isWifiDirectSupported();
    }

    /**
     * @return The current state.
     */
    public DiscoveryManagerState getState() {
        return mState;
    }

    /**
     * Used to check, if some required permission has not been granted by the user.
     * @return The name of the missing permission or null, if none.
     */
    public String getMissingPermission() {
        return mMissingPermission;
    }

    /**
     * Checks the current state and returns true, if running regardless of the discovery mode in use.
     * @return True, if running regardless of the mode. False otherwise.
     */
    public boolean isRunning() {
        return (mState == DiscoveryManagerState.WAITING_FOR_BLUETOOTH_MAC_ADDRESS
                || mState == DiscoveryManagerState.PROVIDING_BLUETOOTH_MAC_ADDRESS
                || mState == DiscoveryManagerState.RUNNING_BLE
                || mState == DiscoveryManagerState.RUNNING_WIFI
                || mState == DiscoveryManagerState.RUNNING_BLE_AND_WIFI);
    }

    /**
     * Starts the peer discovery.
     * @param myPeerId Our peer ID (used for the identity).
     * @param myPeerName Our peer name (used for the identity).
     * @return True, if started successfully or was already running. False otherwise.
     */
    public synchronized boolean start(String myPeerId, String myPeerName) {
        DiscoveryMode discoveryMode = mSettings.getDiscoveryMode();
        Log.i(TAG, "start: Peer ID: " + myPeerId + ", peer name: " + myPeerName + ", mode: " + discoveryMode);
        mShouldBeRunning = true;
        mMyPeerId = myPeerId;
        mMyPeerName = myPeerName;

        mBluetoothManager.bind(this);
        mWifiDirectManager.bind(this);

        if (discoveryMode != DiscoveryMode.NOT_SET) {
            boolean bleDiscoveryStarted = false;
            boolean wifiDiscoveryStarted = false;

            if (discoveryMode == DiscoveryMode.BLE || discoveryMode == DiscoveryMode.BLE_AND_WIFI) {
                if (mBluetoothManager.isBluetoothEnabled()) {
                    // Try to start BLE based discovery
                    stopBluetoothDeviceDiscovery();
                    bleDiscoveryStarted = startBlePeerDiscoverer();
                } else {
                    Log.e(TAG, "start: Cannot start BLE based peer discovery, because Bluetooth is not enabled on the device");
                }
            }

            if (discoveryMode == DiscoveryMode.WIFI || discoveryMode == DiscoveryMode.BLE_AND_WIFI) {
                if (mWifiDirectManager.isWifiEnabled()) {
                    if (verifyIdentityString()) {
                        // Try to start Wi-Fi Direct based discovery
                        wifiDiscoveryStarted = startWifiPeerDiscovery();
                    } else {
                        if (mMyIdentityString == null || mMyIdentityString.length() == 0) {
                            Log.e(TAG, "start: Identity string is null or empty");
                        } else {
                            Log.e(TAG, "start: Invalid identity string: " + mMyIdentityString);
                        }
                    }
                } else {
                    Log.e(TAG, "start: Cannot start Wi-Fi Direct based peer discovery, because Wi-Fi is not enabled on the device");
                }
            }

            if ((discoveryMode == DiscoveryMode.BLE_AND_WIFI && (bleDiscoveryStarted || wifiDiscoveryStarted))
                    || (discoveryMode == DiscoveryMode.BLE && bleDiscoveryStarted)
                    || (discoveryMode == DiscoveryMode.WIFI && wifiDiscoveryStarted)) {
                if (bleDiscoveryStarted && wifiDiscoveryStarted) {
                    setState(DiscoveryManagerState.RUNNING_BLE_AND_WIFI);
                } else if (bleDiscoveryStarted) {
                    if (BluetoothUtils.isBluetoothMacAddressUnknown(getBluetoothMacAddress())) {
                        Log.i(TAG, "start: Our Bluetooth MAC address is not known");

                        if (mBlePeerDiscoverer != null) {
                            mBluetoothMacAddressResolutionHelper.startBluetoothMacAddressGattServer(
                                    mBlePeerDiscoverer.getProvideBluetoothMacAddressRequestId());
                        }

                        setState(DiscoveryManagerState.WAITING_FOR_BLUETOOTH_MAC_ADDRESS);
                    } else {
                        setState(DiscoveryManagerState.RUNNING_BLE);
                    }
                } else if (wifiDiscoveryStarted) {
                    setState(DiscoveryManagerState.RUNNING_WIFI);
                }

                Log.i(TAG, "start: OK");
            }
        } else {
            Log.e(TAG, "start: Discovery mode not set, call setDiscoveryMode() to set");
        }

        return isRunning();
    }

    /**
     * Starts the peer discovery.
     * This method uses the Bluetooth MAC address to set the value of the peer ID.
     * @param myPeerName Our peer name (used for the identity).
     * @return True, if started successfully or was already running. False otherwise.
     */
    public boolean start(String myPeerName) {
        return start(getBluetoothMacAddress(), myPeerName);
    }

    /**
     * Starts the peer discovery.
     *
     * This method uses the Bluetooth MAC address to set the value of the peer ID.
     * No peer name is used. Use this method, if you rely only on BLE based peer discovery.
     *
     * @return True, if started successfully or was already running. False otherwise.
     */
    public boolean start() {
        return start(getBluetoothMacAddress(), PeerProperties.NO_PEER_NAME_STRING);
    }

    /**
     * Stops the peer discovery.
     * Calling this method does nothing, if not running.
     */
    public synchronized void stop() {
        if (mState != DiscoveryManagerState.NOT_STARTED) {
            Log.i(TAG, "stop: Stopping peer discovery...");
        }

        mShouldBeRunning = false;

        stopForRestart();

        mWifiDirectManager.release(this);
        mBluetoothManager.release(this);

        mPeerModel.clear();

        setState(DiscoveryManagerState.NOT_STARTED);
    }

    /**
     * Makes the device (Bluetooth) discoverable for the given duration.
     * @param durationInSeconds The duration in seconds. 0 means the device is always discoverable.
     *                          Any value below 0 or above 3600 is automatically set to 120 secs.
     */
    public void makeDeviceDiscoverable(int durationInSeconds) {
        long currentTime = new Date().getTime();

        if (currentTime > mLastTimeDeviceWasMadeDiscoverable + mPreviousDeviceDiscoverableTimeInSeconds * 1000) {
            mLastTimeDeviceWasMadeDiscoverable = currentTime;
            mPreviousDeviceDiscoverableTimeInSeconds = durationInSeconds;

            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationInSeconds);
            mContext.startActivity(discoverableIntent);
        }
    }

    /**
     * Adds the given peer to the list of discovered peer, if not already in the list. If the peer
     * is in the list, its timestamp is updated.
     *
     * This method is public so that, for instance, if you get a peer lost event while you still
     * have an existing connection with that peer, it is definitely not lost and can be added back.
     * You might also get an incoming connection from a peer that you haven't discovered yet so it
     * makes sense to add it.
     *
     * @param peerProperties The properties of a discovered peer.
     */
    public void addOrUpdateDiscoveredPeer(PeerProperties peerProperties) {
        Log.i(TAG, "addOrUpdateDiscoveredPeer: " + peerProperties.toString());
        mPeerModel.addOrUpdateDiscoveredPeer(peerProperties);
    }

    @Override
    public void dispose() {
        Log.i(TAG, "dispose");
        super.dispose();

        if (mState != DiscoveryManagerState.NOT_STARTED) {
            stop();
        }

        mSettings.removeListener(this);
    }

    /**
     * Constructs the BlePeerDiscoverer instance, if one does not already exist.
     * @return The BlePeerDiscoverer instance.
     */
    public BlePeerDiscoverer getBlePeerDiscovererInstanceAndCheckBluetoothMacAddress() {
        if (mBlePeerDiscoverer == null) {
            Log.v(TAG, "getBlePeerDiscovererInstanceAndCheckBluetoothMacAddress: Constructing...");
            mBlePeerDiscoverer = new BlePeerDiscoverer(
                    this,
                    mBluetoothManager.getBluetoothAdapter(),
                    mBleServiceUuid,
                    mBluetoothMacAddressResolutionHelper.getProvideBluetoothMacAddressRequestUuid(),
                    getBluetoothMacAddress(),
                    BlePeerDiscoverer.AdvertisementDataType.SERVICE_DATA);
        }

        if (BluetoothUtils.isBluetoothMacAddressUnknown(mBlePeerDiscoverer.getBluetoothMacAddress())
                && BluetoothUtils.isValidBluetoothMacAddress(getBluetoothMacAddress())) {
            Log.v(TAG, "getBlePeerDiscovererInstanceAndCheckBluetoothMacAddress: Updating Bluetooth MAC address...");
            mBlePeerDiscoverer.setBluetoothMacAddress(getBluetoothMacAddress());
        }

        return mBlePeerDiscoverer;
    }

    /**
     * From DiscoveryManagerSettings.Listener
     * @param discoveryMode The new discovery mode.
     * @param startIfNotRunning If true, will start even if the discovery wasn't running.
     */
    @Override
    public void onDiscoveryModeChanged(final DiscoveryMode discoveryMode, boolean startIfNotRunning) {
        if (mState != DiscoveryManagerState.NOT_STARTED) {
            stopForRestart();
            start(mMyPeerName);
        } else if (startIfNotRunning) {
            start(mMyPeerName);
        }
    }

    /**
     * From DiscoveryManagerSettings.Listener
     * @param peerExpirationInMilliseconds The new peer expiration time in milliseconds.
     */
    @Override
    public void onPeerExpirationSettingChanged(long peerExpirationInMilliseconds) {
        mPeerModel.onPeerExpirationTimeChanged();
    }

    /**
     * From DiscoveryManagerSettings.Listener
     * @param advertiseMode The new advertise mode.
     * @param advertiseTxPowerLevel The new advertise TX power level.
     */
    @Override
    public void onAdvertiseSettingsChanged(int advertiseMode, int advertiseTxPowerLevel) {
        if (mBlePeerDiscoverer != null) {
            mBlePeerDiscoverer.applySettings(
                    advertiseMode, advertiseTxPowerLevel,
                    mSettings.getScanMode(), mSettings.getScanReportDelay());
        }
    }

    /**
     * From DiscoveryManagerSettings.Listener
     * @param scanMode The new scan mode.
     * @param scanReportDelayInMilliseconds The new scan report delay in milliseconds.
     */
    @Override
    public void onScanSettingsChanged(int scanMode, long scanReportDelayInMilliseconds) {
        if (mBlePeerDiscoverer != null) {
            mBlePeerDiscoverer.applySettings(
                    mSettings.getAdvertiseMode(), mSettings.getAdvertiseTxPowerLevel(),
                    scanMode, scanReportDelayInMilliseconds);
        }
    }

    /**
     * From BluetoothManager.BluetoothManagerListener
     *
     * Stops/restarts the BLE based peer discovery depending on the given mode.
     * @param mode The new mode.
     */
    @Override
    public void onBluetoothAdapterScanModeChanged(int mode) {
        DiscoveryMode discoveryMode = mSettings.getDiscoveryMode();

        if (discoveryMode == DiscoveryMode.BLE || discoveryMode == DiscoveryMode.BLE_AND_WIFI) {
            Log.i(TAG, "onBluetoothAdapterScanModeChanged: Mode changed to " + mode);

            if (mode == BluetoothAdapter.SCAN_MODE_NONE) {
                if (mState != DiscoveryManagerState.WAITING_FOR_SERVICES_TO_BE_ENABLED) {
                    Log.w(TAG, "onBluetoothAdapterScanModeChanged: Bluetooth disabled, pausing BLE based peer discovery...");
                    stopBlePeerDiscoverer(false);

                    if (discoveryMode == DiscoveryMode.BLE ||
                            (discoveryMode == DiscoveryMode.BLE_AND_WIFI &&
                                    !mWifiDirectManager.isWifiEnabled())) {
                        setState(DiscoveryManagerState.WAITING_FOR_SERVICES_TO_BE_ENABLED);
                    } else if (isRunning() && discoveryMode == DiscoveryMode.BLE_AND_WIFI) {
                        setState(DiscoveryManagerState.RUNNING_WIFI);
                    }
                }
            } else {
                if (mShouldBeRunning && mBluetoothManager.isBluetoothEnabled()
                        && !mBluetoothMacAddressResolutionHelper.getIsBluetoothMacAddressGattServerStarted()) {
                    Log.i(TAG, "onBluetoothAdapterScanModeChanged: Bluetooth enabled, restarting BLE based peer discovery...");
                    start(mMyPeerId, mMyPeerName);
                }
            }
        }
    }

    /**
     * From WifiDirectManager.WifiStateListener
     *
     * Stops/restarts the Wi-Fi Direct based peer discovery depending on the given state.
     * @param state The new state.
     */
    @Override
    public void onWifiStateChanged(int state) {
        DiscoveryMode discoveryMode = mSettings.getDiscoveryMode();

        if (discoveryMode == DiscoveryMode.WIFI || discoveryMode == DiscoveryMode.BLE_AND_WIFI) {
            Log.i(TAG, "onWifiStateChanged: State changed to " + state);

            if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                if (mState != DiscoveryManagerState.WAITING_FOR_SERVICES_TO_BE_ENABLED) {
                    Log.w(TAG, "onWifiStateChanged: Wi-Fi disabled, pausing Wi-Fi Direct based peer discovery...");
                    stopWifiPeerDiscovery(false);

                    if (discoveryMode == DiscoveryMode.WIFI
                            || (discoveryMode == DiscoveryMode.BLE_AND_WIFI
                                && !mBluetoothManager.isBluetoothEnabled())) {
                        setState(DiscoveryManagerState.WAITING_FOR_SERVICES_TO_BE_ENABLED);
                    } else if (isRunning() && discoveryMode == DiscoveryMode.BLE_AND_WIFI) {
                        setState(DiscoveryManagerState.RUNNING_BLE);
                    }
                }
            } else {
                if (mShouldBeRunning) {
                    Log.i(TAG, "onWifiStateChanged: Wi-Fi enabled, trying to restart Wi-Fi Direct based peer discovery...");
                    start(mMyPeerId, mMyPeerName);
                }
            }
        }
    }

    /**
     * From WifiPeerDiscoverer.WifiPeerDiscoveryListener
     *
     * Does nothing but logs the event.
     * @param isStarted If true, the discovery was started. If false, it was stopped.
     */
    @Override
    public void onIsWifiPeerDiscoveryStartedChanged(boolean isStarted) {
        Log.i(TAG, "onIsWifiPeerDiscoveryStartedChanged: " + isStarted);
    }

    /**
     * From BlePeerDiscoverer.BlePeerDiscoveryListener
     *
     * Does nothing but logs the event.
     * @param state The new state.
     */
    @Override
    public void onBlePeerDiscovererStateChanged(EnumSet<BlePeerDiscoverer.BlePeerDiscovererStateSet> state) {
        Log.i(TAG, "onBlePeerDiscovererStateChanged: " + state);
    }

    /**
     * From both WifiPeerDiscoverer.WifiPeerDiscoveryListener and BlePeerDiscoverer.BlePeerDiscoveryListener
     *
     * Adds or updates the discovered peer.
     * @param peerProperties The properties of the discovered peer.
     */
    @Override
    public void onPeerDiscovered(PeerProperties peerProperties) {
        //Log.d(TAG, "onPeerDiscovered: " + peerProperties.toString());
        mPeerModel.addOrUpdateDiscoveredPeer(peerProperties); // Will notify us, if added/updated
    }

    /**
     * From WifiPeerDiscoverer.WifiPeerDiscoveryListener
     *
     * Updates the discovered peers, which match the ones on the given list.
     * @param p2pDeviceList A list containing the discovered P2P devices.
     */
    @Override
    public synchronized void onP2pDeviceListChanged(Collection<WifiP2pDevice> p2pDeviceList) {
        if (p2pDeviceList != null && p2pDeviceList.size() > 0) {
            int index = 0;

            for (WifiP2pDevice wifiP2pDevice : p2pDeviceList) {
                if (wifiP2pDevice != null) {
                    Log.d(TAG, "onP2pDeviceListChanged: Peer " + (index + 1) + ": "
                            + wifiP2pDevice.deviceName + " " + wifiP2pDevice.deviceAddress);

                    PeerProperties peerProperties = mPeerModel.getDiscoveredPeer(wifiP2pDevice.deviceAddress);

                    if (peerProperties != null) {
                        mPeerModel.addOrUpdateDiscoveredPeer(peerProperties);
                    }
                }

                index++;
            }
        }
    }

    /**
     * From BlePeerDiscoverer.BlePeerDiscoveryListener
     *
     * Part of Bro Mode.
     *
     * Forwards the event to the listener, if the Bluetooth MAC address resolution process is not
     * set to be automated.
     *
     * Otherwise, starts discovering Bluetooth devices to find out their Bluetooth MAC addresses so
     * that we can provide them to the devices unaware of their own addresses.
     */
    @Override
    public void onProvideBluetoothMacAddressRequest(final String requestId) {
        String currentProvideBluetoothMacAddressRequestId =
                mBluetoothMacAddressResolutionHelper.getCurrentProvideBluetoothMacAddressRequestId();

        if (!mBluetoothMacAddressResolutionHelper.getIsProvideBluetoothMacAddressModeStarted()) {
            Log.d(TAG, "onProvideBluetoothMacAddressRequest: " + requestId);

            if (mSettings.getAutomateBluetoothMacAddressResolution()) {
                if (!mBluetoothMacAddressResolutionHelper.startProvideBluetoothMacAddressMode(requestId)) {
                    Log.e(TAG, "onProvideBluetoothMacAddressRequest: Failed to start the \"Provide Bluetooth MAC address\" mode");
                }
            } else {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onProvideBluetoothMacAddressRequest(requestId);
                    }
                });
            }
        } else if (currentProvideBluetoothMacAddressRequestId != null
                && !currentProvideBluetoothMacAddressRequestId.equals(requestId)) {
            Log.d(TAG, "onProvideBluetoothMacAddressRequest: Received request ID \""
                    + requestId + "\", but already servicing \""
                    + currentProvideBluetoothMacAddressRequestId + "\"");
        }
    }

    /**
     * From BlePeerDiscoverer.BlePeerDiscoveryListener
     *
     * Part of Bro Mode.
     *
     * Forwards the event to the listener, if the Bluetooth MAC address resolution process is not
     * set to be automated.
     *
     * Otherwise, starts "Receive Bluetooth MAC address" mode, which also requests the device to
     * make itself discoverable (requires user's attention).
     *
     * @param requestId The request ID.
     */
    @Override
    public void onPeerReadyToProvideBluetoothMacAddress(String requestId) {
        if (mSettings.getAutomateBluetoothMacAddressResolution() && mBlePeerDiscoverer != null) {
            mBluetoothMacAddressResolutionHelper.startReceiveBluetoothMacAddressMode(
                    mBlePeerDiscoverer.getProvideBluetoothMacAddressRequestId());
        } else {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onPeerReadyToProvideBluetoothMacAddress();
                }
            });
        }
    }

    /**
     * From both BlePeerDiscoverer.BlePeerDiscoveryListener
     *
     * Part of Bro Mode.
     *
     * @param requestId The request ID associated with the device in need of assistance.
     * @param wasCompleted True, if the operation was completed.
     */
    @Override
    public void onProvideBluetoothMacAddressResult(String requestId, boolean wasCompleted) {
        Log.d(TAG, "onProvideBluetoothMacAddressResult: Operation with request ID \""
                + requestId + (wasCompleted ? "\" was completed" : "\" was not completed"));

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mShouldBeRunning) {
                    start(mMyPeerName);
                } else {
                    setState(DiscoveryManagerState.NOT_STARTED);
                }
            }
        });
    }

    /**
     * From BlePeerDiscoverer.BlePeerDiscoveryListener
     *
     * Part of Bro Mode.
     *
     * Stores and forwards the resolved Bluetooth MAC address to the listener.
     * @param bluetoothMacAddress Our Bluetooth MAC address.
     */
    @Override
    public void onBluetoothMacAddressResolved(final String bluetoothMacAddress) {
        Log.i(TAG, "onBluetoothMacAddressResolved: " + bluetoothMacAddress);

        if (BluetoothUtils.isValidBluetoothMacAddress(bluetoothMacAddress)) {
            mSettings.setBluetoothMacAddress(bluetoothMacAddress);

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onBluetoothMacAddressResolved(bluetoothMacAddress);

                    mBluetoothMacAddressResolutionHelper.stopReceiveBluetoothMacAddressMode();

                    if (mShouldBeRunning) {
                        start(mMyPeerName);
                    }  else {
                        setState(DiscoveryManagerState.NOT_STARTED);
                    }
                }
            });
        }
    }

    /**
     * From BluetoothDeviceDiscoverer.BluetoothDeviceDiscovererListener
     *
     * Part of Bro Mode.
     *
     * Initiates the operation to read the Bluetooth GATT characteristic containing the request ID
     * from a GATT service of the given Bluetooth device.
     * @param bluetoothDevice The Bluetooth device.
     */
    @Override
    public void onBluetoothDeviceDiscovered(BluetoothDevice bluetoothDevice) {
        String bluetoothMacAddress = bluetoothDevice.getAddress();
        Log.d(TAG, "onBluetoothDeviceDiscovered: " + bluetoothMacAddress);
        mBluetoothMacAddressResolutionHelper.provideBluetoothMacAddressToDevice(bluetoothDevice);
    }

    /**
     * From BluetoothMacAddressResolutionHelper.BluetoothMacAddressResolutionHelperListener
     *
     * Part of Bro Mode.
     *
     * Changes the state based on the given argument.
     * @param isStarted If true, was started. If false, was stopped.
     */
    @Override
    public void onProvideBluetoothMacAddressModeStartedChanged(final boolean isStarted) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isStarted) {
                    setState(DiscoveryManagerState.PROVIDING_BLUETOOTH_MAC_ADDRESS);
                } else {
                    stopBluetoothDeviceDiscovery();
                    stopBlePeerDiscoverer(false);

                    if (mShouldBeRunning) {
                        start(mMyPeerName);
                    } else {
                        setState(DiscoveryManagerState.NOT_STARTED);
                    }
                }
            }
        });
    }

    /**
     * From BluetoothMacAddressResolutionHelper.BluetoothMacAddressResolutionHelperListener
     *
     * Part of Bro Mode.
     *
     * Changes the state based on the given argument.
     * @param isStarted If true, was started. If false, was stopped.
     */
    @Override
    public void onReceiveBluetoothMacAddressModeStartedChanged(final boolean isStarted) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isStarted) {
                    if (mBlePeerDiscoverer != null) {
                        Log.v(TAG, "onReceiveBluetoothMacAddressModeStartedChanged: Stopping BLE scanning in order to increase the bandwidth for GATT");
                        mBlePeerDiscoverer.stopScanner();
                    }
                } else {
                    if (mShouldBeRunning) {
                        start(mMyPeerName);
                    }
                }
            }
        });
    }

    /**
     * From PeerModel.Listener
     *
     * Forwards the event to the listener.
     * @param peerProperties The properties of the added peer.
     */
    @Override
    public void onPeerAdded(final PeerProperties peerProperties) {
        if (mListener != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onPeerDiscovered(peerProperties);
                }
            });
        }
    }

    /**
     * From PeerModel.Listener
     *
     * Forwards the event to the listener.
     * @param peerProperties The properties of the updated peer.
     */
    @Override
    public void onPeerUpdated(final PeerProperties peerProperties) {
        if (mListener != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onPeerUpdated(peerProperties);
                }
            });
        }
    }

    /**
     * From PeerModel.Listener
     *
     * Forwards the event to the listener.
     * @param peerProperties The properties of the expired and removed peer.
     */
    @Override
    public void onPeerExpiredAndRemoved(final PeerProperties peerProperties) {
        if (mListener != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onPeerLost(peerProperties);
                }
            });
        }
    }

    /**
     * Stops the discovery for pending restart. Does not notify the listener.
     */
    private synchronized void stopForRestart() {
        if (mState != DiscoveryManagerState.NOT_STARTED) {
            Log.d(TAG, "stopForRestart");
            mBluetoothMacAddressResolutionHelper.stopAllBluetoothMacAddressResolutionOperations();
            stopBlePeerDiscoverer(false);
            stopWifiPeerDiscovery(false);
        }
    }

    /**
     * Tries to start the BLE peer discoverer.
     * @return True, if started (or already running). False otherwise.
     */
    private synchronized boolean startBlePeerDiscoverer() {
        boolean started = false;
        boolean permissionsGranted = false;

        if (CommonUtils.isMarshmallowOrHigher()) {
            permissionsGranted = mListener.onPermissionCheckRequired(Manifest.permission.ACCESS_COARSE_LOCATION);
        } else {
            permissionsGranted = true;
            mMissingPermission = null;
        }

        if (permissionsGranted) {
            if (mBluetoothManager.bind(this)) {
                getBlePeerDiscovererInstanceAndCheckBluetoothMacAddress();

                if (mBleServiceUuid != null) {
                    started = mBlePeerDiscoverer.start();
                } else {
                    Log.e(TAG, "startBlePeerDiscoverer: No BLE service UUID");
                }
            }
        } else {
            mMissingPermission = Manifest.permission.ACCESS_COARSE_LOCATION;
            Log.e(TAG, "startBlePeerDiscoverer: Permission \"" + mMissingPermission + "\" denied");
        }

        if (started) {
            Log.d(TAG, "startBlePeerDiscoverer: OK");
        }

        return started;
    }

    /**
     * Stops the BLE peer discoverer.
     * @param updateState If true, will update the state.
     */
    private synchronized void stopBlePeerDiscoverer(boolean updateState) {
        if (mBlePeerDiscoverer != null) {
            mBlePeerDiscoverer.stop();
            mBlePeerDiscoverer = null;
            Log.d(TAG, "stopBlePeerDiscoverer: Stopped");

            if (updateState) {
                if (mState == DiscoveryManagerState.RUNNING_BLE) {
                    setState(DiscoveryManagerState.NOT_STARTED);
                } else if (mState == DiscoveryManagerState.RUNNING_BLE_AND_WIFI) {
                    setState(DiscoveryManagerState.RUNNING_WIFI);
                }
            }
        }
    }

    /**
     * Tries to start the Wi-Fi Direct based peer discovery.
     * Note that this method does not validate the current state nor the identity string.
     * @return True, if started (or already running). False otherwise.
     */
    private synchronized boolean startWifiPeerDiscovery() {
        boolean started = false;

        if (mWifiDirectManager.bind(this)) {
            if (mWifiPeerDiscoverer == null) {
                WifiP2pManager p2pManager = mWifiDirectManager.getWifiP2pManager();
                WifiP2pManager.Channel channel = mWifiDirectManager.getWifiP2pChannel();

                if (p2pManager != null && channel != null) {
                    mWifiPeerDiscoverer = new WifiPeerDiscoverer(
                            mContext, channel, p2pManager, this, mServiceType, mMyIdentityString);

                    mWifiPeerDiscoverer.start();
                } else {
                    Log.e(TAG, "startWifiPeerDiscovery: Failed to get Wi-Fi P2P manager or channel");
                }
            }

            if (mWifiPeerDiscoverer != null) {
                started = mWifiPeerDiscoverer.start();
                Log.d(TAG, "startWifiPeerDiscovery: Wi-Fi Direct OK");
            }
        } else {
            Log.e(TAG, "startWifiPeerDiscovery: Failed to start, this may indicate that Wi-Fi Direct is not supported on this device");
        }

        return started;
    }

    /**
     * Stops the Wi-Fi Direct based peer discovery.
     * @param updateState If true, will update the state.
     */
    private synchronized void stopWifiPeerDiscovery(boolean updateState) {
        if (mWifiPeerDiscoverer != null) {
            mWifiPeerDiscoverer.stop();
            mWifiPeerDiscoverer = null;
            Log.i(TAG, "stopWifiPeerDiscovery: Stopped");

            if (updateState) {
                if (mState == DiscoveryManagerState.RUNNING_WIFI) {
                    setState(DiscoveryManagerState.NOT_STARTED);
                } else if (mState == DiscoveryManagerState.RUNNING_BLE_AND_WIFI) {
                    setState(DiscoveryManagerState.RUNNING_BLE);
                }
            }
        }
    }

    /**
     * Starts Bluetooth device discovery.
     *
     * Note that Bluetooth LE scanner cannot be running, when doing Bluetooth device discovery.
     * Otherwise, the state of the Bluetooth stack on the device may become invalid. Observed
     * consequences on Lollipop (Android version 5.x) include BLE scanning not turning on
     * ("app cannot be registered" error state) and finally the application utilizing this library
     * won't start at all (you get only a blank screen). To prevent this, calling this method will
     * always stop BLE discovery.
     *
     * This method should not be called directly by an app utilizing this library. The method is
     * public for testing purposes only.
     *
     * @return True, if started successfully. False otherwise.
     */
    public synchronized boolean startBluetoothDeviceDiscovery() {
        Log.d(TAG, "startBluetoothDeviceDiscovery");

        if (mBlePeerDiscoverer != null) {
            mBlePeerDiscoverer.stopScanner();
        }

        if (mBluetoothDeviceDiscoverer == null) {
            mBluetoothDeviceDiscoverer =
                    new BluetoothDeviceDiscoverer(mContext, mBluetoothManager.getBluetoothAdapter(), this);
        }

        boolean isStarted = false;

        if (mBlePeerDiscoverer == null
            || !mBlePeerDiscoverer.getState().contains(BlePeerDiscoverer.BlePeerDiscovererStateSet.SCANNING)) {
            isStarted = (mBluetoothDeviceDiscoverer.isRunning()
                    || mBluetoothDeviceDiscoverer.start(mSettings.getProvideBluetoothMacAddressTimeout()));
        } else {
            Log.e(TAG, "startBluetoothDeviceDiscovery: Bluetooth LE peer discoverer cannot be running, when doing Bluetooth LE scanning");
        }

        return isStarted;
    }

    /**
     * Stops the Bluetooth device discovery.
     *
     * This method should not be called directly by an app utilizing this library. The method is
     * public for testing purposes only.
     *
     * @return True, if stopped. False otherwise.
     */
    public synchronized boolean stopBluetoothDeviceDiscovery() {
        boolean wasStopped = false;

        if (mBluetoothDeviceDiscoverer != null) {
            mBluetoothDeviceDiscoverer.stop();
            mBluetoothDeviceDiscoverer = null;
            Log.d(TAG, "stopBluetoothDeviceDiscovery: Stopped");
            wasStopped = true;
        }

        return wasStopped;
    }

    /**
     * Sets the state of this instance and notifies the listener.
     * @param state The new state.
     */
    private synchronized void setState(final DiscoveryManagerState state) {
        if (mState != state) {
            Log.d(TAG, "setState: " + state.toString());
            mState = state;

            if (mListener != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onDiscoveryManagerStateChanged(state);
                    }
                });
            }
        }
    }
}
