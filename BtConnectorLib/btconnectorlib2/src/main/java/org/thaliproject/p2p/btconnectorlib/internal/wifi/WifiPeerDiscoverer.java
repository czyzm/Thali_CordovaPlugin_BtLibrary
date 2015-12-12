/* Copyright (c) 2015 Microsoft Corporation. This software is licensed under the MIT License.
 * See the license file delivered with this project for further information.
 */
package org.thaliproject.p2p.btconnectorlib.internal.wifi;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;
import org.thaliproject.p2p.btconnectorlib.PeerProperties;
import org.thaliproject.p2p.btconnectorlib.internal.ServiceDiscoveryListener;
import java.util.Collection;
import java.util.List;

/**
 * The main interface for peer discovery via Wi-Fi.
 */
public class WifiPeerDiscoverer implements
        WifiP2pDeviceDiscoverer.Listener, ServiceDiscoveryListener {
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
         * @param peerProperties The properties of the discovered peer.
         */
        void onPeerDiscovered(PeerProperties peerProperties);

        /**
         * Called when we resolve a new list of available peers.
         * @param peerPropertiesList The new list of available peers.
         */
        void onPeerListChanged(List<PeerProperties> peerPropertiesList);
    }

    private static final String TAG = WifiPeerDiscoverer.class.getName();
    private final Context mContext;
    private final WifiP2pManager.Channel mP2pChannel;
    private final WifiP2pManager mP2pManager;
    private final WifiPeerDiscoveryListener mWifiPeerDiscoveryListener;
    private final String mServiceType;
    private final String mIdentityString;
    private WifiP2pDeviceDiscoverer mWifiP2pDeviceDiscoverer = null;
    private WifiServiceAdvertiser mWifiServiceAdvertiser = null;
    private WifiServiceWatcher mWifiServiceWatcher = null;
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
    }

    /**
     * Starts both advertising a Wi-Fi service and listening to other Wi-Fi services.
     * If already started, this method does nothing.
     */
    public synchronized void start() {
        if (!mIsStarted) {
            if (mP2pManager != null && mP2pChannel != null) {
                Log.i(TAG, "start: " + mIdentityString);

                mWifiServiceAdvertiser = new WifiServiceAdvertiser(mP2pManager, mP2pChannel);
                mWifiServiceAdvertiser.start(mIdentityString, mServiceType);

                mWifiP2pDeviceDiscoverer = new WifiP2pDeviceDiscoverer(this, mContext, mP2pManager, mP2pChannel);
                mWifiServiceWatcher = new WifiServiceWatcher(this, mP2pManager, mP2pChannel, mServiceType);

                if (mWifiP2pDeviceDiscoverer.initialize() && mWifiP2pDeviceDiscoverer.start()) {
                    mWifiServiceWatcher.start();
                    setIsStarted(true);
                } else {
                    Log.e(TAG, "Failed to initialize and start the peer discovery");
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

        if (mWifiServiceAdvertiser != null)
        {
            mWifiServiceAdvertiser.stop();
            mWifiServiceAdvertiser = null;
        }

        if (mWifiP2pDeviceDiscoverer != null) {
            mWifiP2pDeviceDiscoverer.deinitialize();
            mWifiP2pDeviceDiscoverer = null;
        }

        if (mWifiServiceWatcher != null)
        {
            mWifiServiceWatcher.stop();
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
     * @param peerPropertiesList The new list of peers (with the appropriate services) available.
     */
    @Override
    public void onServiceListChanged(List<PeerProperties> peerPropertiesList) {
        if (mWifiPeerDiscoveryListener != null
                && peerPropertiesList != null
                && peerPropertiesList.size() > 0) {
            Log.i(TAG, "onServiceListChanged: " + peerPropertiesList.size() + " services discovered");
            mWifiPeerDiscoveryListener.onPeerListChanged(peerPropertiesList);
        } else {
            Log.w(TAG, "onServiceListChanged: Got empty list");
        }
    }

    /**
     * Forwards the event to the listener.
     * @param peerProperties The discovered peer device with an appropriate service.
     */
    @Override
    public void onServiceDiscovered(PeerProperties peerProperties) {
        Log.i(TAG, "onServiceDiscovered");

        if (mWifiPeerDiscoveryListener != null) {
            mWifiPeerDiscoveryListener.onPeerDiscovered(peerProperties);
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
