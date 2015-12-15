/* Copyright (c) 2015 Microsoft Corporation. This software is licensed under the MIT License.
 * See the license file delivered with this project for further information.
 */
package org.thaliproject.nativesample.app;

import android.util.Log;
import org.thaliproject.p2p.btconnectorlib.PeerProperties;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * The model containing discovered peers and connections.
 */
public class PeerAndConnectionModel {
    public interface Listener {
        void onDataChanged();
    }

    private static final String TAG = PeerAndConnectionModel.class.getName();
    private static PeerAndConnectionModel mInstance = null;
    private ArrayList<PeerProperties> mPeers = new ArrayList<PeerProperties>();
    private ArrayList<Connection> mConnections = new ArrayList<Connection>();
    private Listener mListener = null;

    public static PeerAndConnectionModel getInstance() {
        if (mInstance == null) {
            mInstance = new PeerAndConnectionModel();
        }

        return mInstance;
    }

    /**
     * Private constructor.
     */
    private PeerAndConnectionModel() {
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public ArrayList<PeerProperties> getPeers() {
        return mPeers;
    }

    public ArrayList<Connection> getConnections() {
        return mConnections;
    }

    /**
     * Adds the given peer to the list.
     * @param peerProperties The peer to add.
     * @return True, if the peer was added. False, if it was already in the list.
     */
    public synchronized boolean addPeer(PeerProperties peerProperties) {
        boolean alreadyInTheList = false;
        final String newPeerId = peerProperties.getId();

        for (PeerProperties existingPeerProperties : mPeers) {
            if (existingPeerProperties.getId().equals(newPeerId)) {
                alreadyInTheList = true;
                break;
            }
        }

        if (alreadyInTheList) {
            Log.i(TAG, "addPeer: Peer " + peerProperties.toString() + " already in the list");
        } else {
            mPeers.add(peerProperties);
            Log.i(TAG, "addPeer: Peer " + peerProperties.toString() + " added to list");

            if (mListener != null) {
                mListener.onDataChanged();
            }
        }

        return !alreadyInTheList;
    }

    /**
     * Tries to remove the given peer.
     * @return True, if the peer was found and removed. False otherwise.
     */
    public boolean removePeer(final PeerProperties peerProperties) {
        boolean wasRemoved = false;

        for (Iterator<PeerProperties> iterator = mPeers.iterator(); iterator.hasNext();) {
            PeerProperties existingPeer = iterator.next();

            if (existingPeer.equals(peerProperties)) {
                iterator.remove();
                wasRemoved = true;
                break;
            }
        }

        if (wasRemoved && mListener != null) {
            mListener.onDataChanged();
        }

        return wasRemoved;
    }

    /**
     * @return The total number of connections.
     */
    public synchronized int getTotalNumberOfConnections() {
        return mConnections.size();
    }

    /**
     * Checks if we are connected to the given peer (incoming or outgoing).
     * @param peerProperties The peer properties.
     * @return True, if connected. False otherwise.
     */
    public synchronized boolean hasConnectionToPeer(PeerProperties peerProperties) {
        boolean hasConnection = false;

        for (Connection connection : mConnections) {
            if (connection.getPeerProperties().equals(peerProperties)) {
                hasConnection = true;
                break;
            }
        }

        return hasConnection;
    }

    /**
     * Checks if we are connected to the given peer.
     * @param peerId The peer ID.
     * @param isIncoming If true, will check if we have an incoming connection. If false, check if we have an outgoing connection.
     * @return True, if connected. False otherwise.
     */
    public synchronized boolean hasConnectionToPeer(final String peerId, boolean isIncoming) {
        boolean hasConnection = false;
        //int i = 0;

        for (Connection connection : mConnections) {
            //Log.d(TAG, "hasConnectionToPeer: " + ++i + ": " + connection.toString());

            if (connection.getPeerId().equals(peerId) && connection.getIsIncoming() == isIncoming) {
                hasConnection = true;
                break;
            }
        }

        return hasConnection;
    }

    /**
     * Closes all connections.
     */
    public synchronized void closeAllConnections() {
        for (Connection connection : mConnections) {
            connection.close(true);
        }

        mConnections.clear();
    }

    /**
     * Adds/removes a connection from the list of connections.
     * @param connection The connection to add/remove.
     * @param add If true, will add the given connection. If false, will remove it.
     * @return True, if was added/removed successfully. False otherwise.
     */
    public synchronized boolean addOrRemoveConnection(Connection connection, boolean add) {
        boolean wasAddedOrRemoved = false;

        if (connection != null) {
            if (add) {
                wasAddedOrRemoved = mConnections.add(connection);
            } else {
                // Remove
                for (Iterator<Connection> iterator = mConnections.iterator(); iterator.hasNext();) {
                    Connection existingConnection = iterator.next();

                    if (existingConnection.equals(connection)) {
                        iterator.remove();
                        wasAddedOrRemoved = true;
                        // Do not break just to make sure we get any excess connections
                    }
                }
            }
        }

        if (wasAddedOrRemoved && mListener != null) {
            mListener.onDataChanged();
        }

        return wasAddedOrRemoved;
    }
}