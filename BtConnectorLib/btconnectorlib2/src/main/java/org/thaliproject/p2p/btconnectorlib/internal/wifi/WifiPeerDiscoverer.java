/* Copyright (c) Microsoft. All Rights Reserved. Licensed under the MIT License.
 * See license.txt in the project root for further information.
 */
package org.thaliproject.p2p.btconnectorlib.internal.wifi;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.CountDownTimer;
import android.util.Log;
import org.thaliproject.p2p.btconnectorlib.PeerDeviceProperties;
import java.util.Collection;
import java.util.List;

/**
 * The main interface for peer discovery via Wi-Fi.
 */
public class WifiPeerDiscoverer implements WifiServiceWatcher.WifiServiceWatcherListener {
    /**
     * A listener for peer discovery events.
     */
    public interface WifiPeerDiscoveryListener {
        /**
         * Called when the discovery is started or stopped.
         * @param isStarted If true, the discovery was started. If false, it was stopped.
         */
        void onIsDiscoveryStartedChanged(boolean isStarted);

        /**
         * Called when a peer was discovered.
         * @param peerDeviceProperties The properties of the discovered peer.
         */
        void onPeerDiscovered(PeerDeviceProperties peerDeviceProperties);

        /**
         * Called when we resolve a new list of available peers.
         * @param peerDevicePropertiesList The new list of available peers.
         */
        void onPeerListChanged(List<PeerDeviceProperties> peerDevicePropertiesList);
    }

    private static final String TAG = WifiPeerDiscoverer.class.getName();
    private static final long PEER_DISCOVERY_TIMEOUT_IN_MILLISECONDS = 60000;
    private static final long PEER_DISCOVERY_TIMEOUT_TIMER_INTERVAL_IN_MILLISECONDS = 10000;
    private final Context mContext;
    private final WifiP2pManager.Channel mP2pChannel;
    private final WifiP2pManager mP2pManager;
    private final WifiPeerDiscoveryListener mWifiPeerDiscoveryListener;
    private final String mServiceType;
    private final String mIdentityString;
    private WifiServiceAdvertiser mWifiServiceAdvertiser = null;
    private WifiServiceWatcher mWifiServiceWatcher = null;
    private CountDownTimer mPeerDiscoveryTimeoutTimer = null;
    private boolean mIsStarted = false;

    /**
     * Constructor.
     * @param context The application context.
     * @param p2pChannel The Wi-Fi P2P manager.
     * @param p2pManager The Wi-Fi P2P channel.
     * @param listener The listener.
     * @param serviceType The service type.
     * @param identityString Our identity (for service advertisement).
     */
    public WifiPeerDiscoverer (
            Context context, WifiP2pManager.Channel p2pChannel, WifiP2pManager p2pManager,
            WifiPeerDiscoveryListener listener, String serviceType, String identityString) {
        mContext = context;
        mP2pChannel = p2pChannel;
        mP2pManager = p2pManager;
        mWifiPeerDiscoveryListener = listener;
        mServiceType = serviceType;
        mIdentityString = identityString;

        final WifiPeerDiscoverer thisInstance = this;

        mPeerDiscoveryTimeoutTimer =
            new CountDownTimer(PEER_DISCOVERY_TIMEOUT_IN_MILLISECONDS,
                    PEER_DISCOVERY_TIMEOUT_TIMER_INTERVAL_IN_MILLISECONDS) {
                public void onTick(long millisUntilFinished) {
                    // Not used
                }

                public void onFinish() {
                    if (thisInstance.mWifiPeerDiscoveryListener != null) {
                        // Clear the existing list of found peers
                        thisInstance.mWifiPeerDiscoveryListener.onPeerListChanged(null);
                    }

                    // Restart the discovery
                    thisInstance.stop();
                    thisInstance.start();
                }
            };
    }

    /**
     * Starts both advertising a Wi-Fi service and listening to other Wi-Fi services.
     * If already started, this method does nothing.
     */
    public synchronized void start() {
        if (!mIsStarted) {
            if (mP2pManager != null && mP2pChannel != null) {
                Log.i(TAG, "start: " + mIdentityString);

                mPeerDiscoveryTimeoutTimer.cancel();
                mPeerDiscoveryTimeoutTimer.start();

                mWifiServiceAdvertiser = new WifiServiceAdvertiser(mP2pManager, mP2pChannel);
                mWifiServiceAdvertiser.start(mIdentityString, mServiceType);

                mWifiServiceWatcher = new WifiServiceWatcher(mContext, mP2pManager, mP2pChannel, this, mServiceType);

                if (mWifiServiceWatcher.initialize() && mWifiServiceWatcher.start()) {
                    setIsStarted(true);
                } else {
                    Log.e(TAG, "Failed to initialize and start the service watcher");
                    stop();
                }
            } else {
                Log.e(TAG, "start: Missing critical P2P instances!");
            }
        } else {
            Log.w(TAG, "start: Already running, call stopListening() first to restart");
        }
    }

    /**
     * Stops advertising and listening to Wi-Fi services.
     */
    public synchronized void stop() {
        if (mIsStarted) {
            Log.i(TAG, "stop: Stopping services");
        }

        mPeerDiscoveryTimeoutTimer.cancel();

        if (mWifiServiceAdvertiser != null)
        {
            mWifiServiceAdvertiser.stop();
            mWifiServiceAdvertiser = null;
        }

        if (mWifiServiceWatcher != null)
        {
            mWifiServiceWatcher.deinitialize();
            mWifiServiceWatcher = null;
        }

        setIsStarted(false);
    }

    /**
     * Restarts the peer discovery timeout timer, if the received list is not empty.
     * If the list empty, the listener is notified.
     * @param p2pDeviceList The new list of P2P device discovered.
     */
    @Override
    public void onP2pDeviceListChanged(Collection<WifiP2pDevice> p2pDeviceList) {
        if (p2pDeviceList != null && p2pDeviceList.size() > 0) {
            Log.i(TAG, "onP2pDeviceListChanged: " + p2pDeviceList.size() + " P2P devices discovered");
            mPeerDiscoveryTimeoutTimer.cancel();
            mPeerDiscoveryTimeoutTimer.start();

            int index = 0;

            for (WifiP2pDevice p2pDevice : p2pDeviceList) {
                index++;
                Log.i(TAG, "onP2pDeviceListChanged: Peer " + index + ": " + p2pDevice.deviceName + " " + p2pDevice.deviceAddress);
            }
        } else {
            Log.w(TAG, "onP2pDeviceListChanged: Got empty list");

            if (mWifiPeerDiscoveryListener != null) {
                mWifiPeerDiscoveryListener.onPeerListChanged(null);
            }
        }
    }

    /**
     * If the list is not empty, it is forwarded to the listener.
     * @param peerDevicePropertiesList The new list of peers (with the appropriate services) available.
     */
    @Override
    public void onServiceListChanged(List<PeerDeviceProperties> peerDevicePropertiesList) {
        if (mWifiPeerDiscoveryListener != null
                && peerDevicePropertiesList != null
                && peerDevicePropertiesList.size() > 0) {
            Log.i(TAG, "onServiceListChanged: " + peerDevicePropertiesList.size() + " services discovered");
            mWifiPeerDiscoveryListener.onPeerListChanged(peerDevicePropertiesList);
        } else {
            Log.w(TAG, "onServiceListChanged: Got empty list");
        }
    }

    /**
     * Forwards the event to the listener.
     * @param peerDeviceProperties The discovered peer device with an appropriate service.
     */
    @Override
    public void onServiceDiscovered(PeerDeviceProperties peerDeviceProperties) {
        Log.i(TAG, "onServiceDiscovered");

        if (mWifiPeerDiscoveryListener != null) {
            mWifiPeerDiscoveryListener.onPeerDiscovered(peerDeviceProperties);
        }
    }

    /**
     * Sets the "is started" state and notifies the listener.
     * @param isStarted Expected: True, if started. False, if stopped.
     */
    private void setIsStarted(boolean isStarted) {
        if (mIsStarted != isStarted) {
            mIsStarted = isStarted;

            if (mWifiPeerDiscoveryListener != null) {
                mWifiPeerDiscoveryListener.onIsDiscoveryStartedChanged(mIsStarted);
            }
        }
    }
}