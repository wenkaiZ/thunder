package network.thunder.core.communication;

import com.google.common.collect.Sets;
import network.thunder.core.communication.layer.ContextFactory;
import network.thunder.core.communication.layer.high.channel.ChannelManager;
import network.thunder.core.communication.layer.middle.broadcasting.sync.SynchronizationHelper;
import network.thunder.core.communication.layer.middle.broadcasting.types.PubkeyIPObject;
import network.thunder.core.communication.nio.P2PClient;
import network.thunder.core.communication.nio.P2PServer;
import network.thunder.core.communication.processor.ConnectionIntent;
import network.thunder.core.database.DBHandler;
import network.thunder.core.etc.SeedNodes;
import network.thunder.core.etc.Tools;
import network.thunder.core.helper.callback.ChannelOpenListener;
import network.thunder.core.helper.callback.ConnectionListener;
import network.thunder.core.helper.callback.ResultCommand;
import network.thunder.core.helper.callback.results.*;
import network.thunder.core.helper.events.LNEventHelper;
import org.bitcoinj.core.ECKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static network.thunder.core.communication.processor.ConnectionIntent.*;

/**
 * ConnectionManager is responsible to keep track of which connections are currently open and connect to nodes by NodeKey.
 * <p>
 * We are keeping track of open connections not on IP level, but rather on a per node basis. Similar,
 * when a new connection is requested, the ConnectionManager is in charge to look up the most recent
 * address out of the database.
 * <p>
 * TODO: When a new connection comes in from a node that we were connected already, close the old connection
 * TODO: Move Syncing and IP-Seeding to their respective classes, together with hookups for Syncer / IPSeeder interface hookups
 * TODO: Think about how we want to close channels again once we removed the ChannelIntent object in ClientObject (maybe prune connection pool?)
 */
public class ConnectionManagerImpl implements ConnectionManager, ConnectionRegistry {
    public final static int CHANNELS_TO_OPEN = 5;
    public final static int MINIMUM_AMOUNT_OF_IPS = 10;

    Set<NodeKey> connectedNodes = Sets.newConcurrentHashSet();
    Set<NodeKey> currentlyConnecting = Sets.newConcurrentHashSet();
    Map<NodeKey, ConnectionListener> connectionListenerMap = new ConcurrentHashMap<>();

    P2PServer server;
    ServerObject node;

    DBHandler dbHandler;
    ContextFactory contextFactory;
    LNEventHelper eventHelper;
    ChannelManager channelManager;

    public ConnectionManagerImpl (ContextFactory contextFactory, DBHandler dbHandler) {
        this.dbHandler = dbHandler;
        this.node = contextFactory.getServerSettings();
        this.contextFactory = contextFactory;
        this.eventHelper = contextFactory.getEventHelper();
        this.channelManager = contextFactory.getChannelManager();
    }

    @Override
    public void onConnected (NodeKey node) {
        connectedNodes.add(node);

        ConnectionListener listener = connectionListenerMap.get(node);
        if (listener != null) {
            listener.onConnection(new SuccessResult());
            connectionListenerMap.remove(node);
        }
        currentlyConnecting.remove(node);
    }

    @Override
    public void onDisconnected (NodeKey node) {
        connectedNodes.remove(node);
        currentlyConnecting.remove(node);
    }

    @Override
    public boolean isConnected (NodeKey node) {
        return connectedNodes.contains(node);
    }

    @Override
    public void connect (NodeKey node, ConnectionListener connectionListener) {
        if (connectedNodes.contains(node)) {
            //Already connected
            connectionListener.onConnection(new SuccessResult());
        } else if (currentlyConnecting.contains(node)) {
            //TODO we are overwriting the old listener - not sure if rather multimap or normal map..
            connectionListenerMap.put(node, connectionListener);
        } else {
            currentlyConnecting.add(node);
            connectionListenerMap.put(node, connectionListener);

            //TODO legacy below...
            PubkeyIPObject ipObject = dbHandler.getIPObject(node.nodeKey.getPubKey());
            connect(ipObjectToNode(ipObject, ConnectionIntent.MISC), getDisconnectListener(node));
        }
    }

    private ConnectionListener getDisconnectListener (NodeKey nodeKey) {
        return new ConnectionListener() {
            @Override
            public void onConnection (Result result) {
                onDisconnected(nodeKey);
            }
        };
    }

    public void startListening (ResultCommand callback) {
        System.out.println("Started listening on port " + this.node.portServer);
        server = new P2PServer(contextFactory);
        server.startServer(this.node.portServer);
        callback.execute(new SuccessResult());
    }

    @Override
    public void fetchNetworkIPs (ResultCommand callback) {
        new Thread(() -> {
            fetchNetworkIPsBlocking(callback);
        }).start();
    }

    private void fetchNetworkIPsBlocking (ResultCommand callback) {
        List<PubkeyIPObject> ipList = dbHandler.getIPObjects();
        List<PubkeyIPObject> alreadyFetched = new ArrayList<>();
        List<PubkeyIPObject> seedNodes = SeedNodes.getSeedNodes();
        ipList.addAll(seedNodes);

        while (ipList.size() < MINIMUM_AMOUNT_OF_IPS) {
            try {
                ipList = PubkeyIPObject.removeFromListByPubkey(ipList, alreadyFetched);
                ipList = PubkeyIPObject.removeFromListByPubkey(ipList, node.pubKeyServer.getPubKey());

                if (ipList.size() == 0) {
                    break;
                }

                PubkeyIPObject randomNode = Tools.getRandomItemFromList(ipList);
                ClientObject client = ipObjectToNode(randomNode, GET_IPS);
                client.resultCallback = new NullResultCommand();
                connectBlocking(client);
                alreadyFetched.add(randomNode);

                ipList = dbHandler.getIPObjects();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        ipList = dbHandler.getIPObjects();
        if (ipList.size() > 0) {
            callback.execute(new SuccessResult());
        } else {
            callback.execute(new FailureResult());
        }
    }

    @Override
    public void startBuildingRandomChannel (ResultCommand callback) {
        try {
            List<PubkeyIPObject> ipList = dbHandler.getIPObjects();
            List<PubkeyIPObject> alreadyConnected = dbHandler.getIPObjectsWithActiveChannel();
            List<PubkeyIPObject> alreadyTried = new ArrayList<>();

            while (alreadyConnected.size() < CHANNELS_TO_OPEN) {
                //TODO Here we want some algorithm to determine who we want to connect to initially..

                ipList = dbHandler.getIPObjects();

                ipList = PubkeyIPObject.removeFromListByPubkey(ipList, node.pubKeyServer.getPubKey());
                ipList = PubkeyIPObject.removeFromListByPubkey(ipList, alreadyConnected);
                ipList = PubkeyIPObject.removeFromListByPubkey(ipList, alreadyTried);

                if (ipList.size() == 0) {
                    callback.execute(new FailureResult());
                    return;
                }

                PubkeyIPObject randomNode = Tools.getRandomItemFromList(ipList);

                System.out.println("BUILD CHANNEL WITH: " + randomNode);

                ClientObject node = ipObjectToNode(randomNode, OPEN_CHANNEL);
                channelManager.openChannel(new NodeKey(node.pubKeyClient), new ChannelOpenListener());

                alreadyTried.add(randomNode);

                alreadyConnected = dbHandler.getIPObjectsWithActiveChannel();

                Thread.sleep(5000);
            }
            callback.execute(new SuccessResult());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void startSyncing (ResultCommand callback) {
        new Thread(() -> {
            startSyncingBlocking(callback);
        }).start();
    }

    //TODO: Right now we are blindly and fully syncing with 4 random nodes. Let this be a more distributed process, when data grows
    private void startSyncingBlocking (ResultCommand callback) {
        SynchronizationHelper synchronizationHelper = contextFactory.getSyncHelper();
        List<PubkeyIPObject> ipList = dbHandler.getIPObjects();
        List<PubkeyIPObject> alreadyFetched = new ArrayList<>();
        List<PubkeyIPObject> seedNodes = SeedNodes.getSeedNodes();
        ipList.addAll(seedNodes);
        int amountOfNodesToSyncFrom = 3;
        int totalSyncs = 0;
        while (totalSyncs < amountOfNodesToSyncFrom) {
            synchronizationHelper.resync();
            ipList = PubkeyIPObject.removeFromListByPubkey(ipList, alreadyFetched);
            ipList = PubkeyIPObject.removeFromListByPubkey(ipList, node.pubKeyServer.getPubKey());

            if (ipList.size() == 0) {
                callback.execute(new NoSyncResult());
                return;
            }

            PubkeyIPObject randomNode = Tools.getRandomItemFromList(ipList);
            ClientObject client = ipObjectToNode(randomNode, GET_SYNC_DATA);
            connectBlocking(client);
            alreadyFetched.add(randomNode);
            totalSyncs++;
            ipList = dbHandler.getIPObjects();
        }
        callback.execute(new SyncSuccessResult());
    }

    private void connect (ClientObject node, ConnectionListener listener) {
        P2PClient client = new P2PClient(contextFactory);
        client.connectTo(node, listener);
    }

    private void connectBlocking (ClientObject node) {
        try {
            P2PClient client = new P2PClient(contextFactory);
            client.connectBlocking(node, new ConnectionListener());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ClientObject ipObjectToNode (PubkeyIPObject ipObject, ConnectionIntent intent) {
        ClientObject node = new ClientObject();
        node.isServer = false;
        node.intent = intent;
        node.pubKeyClient = ECKey.fromPublicOnly(ipObject.pubkey);
        node.host = ipObject.hostname;
        node.port = ipObject.port;
        return node;
    }

}