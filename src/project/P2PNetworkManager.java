package cse471;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class P2PNetworkManager {

    private static final int UDP_PORT = 8888; 
    private static final int MAX_PACKET_SIZE = 4096;

    private List<InetAddress> knownPeers = new CopyOnWriteArrayList<>();
    
    private Set<String> seenPackets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private DatagramSocket socket;
    private boolean running = false;
    private String myPeerID; 
    
    private static final byte TYPE_DISCOVERY  = 0x01;
    private static final byte TYPE_HELLO      = 0x02;
    private static final byte TYPE_STATUS     = 0x03;

    public interface PeerDiscoveryListener {
        void onPeerDiscovered(String peerIP, List<String> filesData);
        void onStatusReceived(String peerIP, String filename, String progress, String state);
    }
    
    private PeerDiscoveryListener listener;

    public P2PNetworkManager(PeerDiscoveryListener listener) {
        this.listener = listener;
        this.myPeerID = UUID.randomUUID().toString();
        
        String bootstrapIp = System.getenv("BOOTSTRAP_PEER");
        if (bootstrapIp != null && !bootstrapIp.trim().isEmpty()) {
            String[] parts = bootstrapIp.split("[,;\\s]+");
            for (String p : parts) {
                String ip = p.trim();
                if (ip.isEmpty()) continue;
                try {
                    InetAddress addr = InetAddress.getByName(ip);
                    knownPeers.add(addr);
                    System.out.println("[P2P] Bootstrap Peer added: " + ip);
                } catch (UnknownHostException e) {
                    System.err.println("[P2P] Invalid Bootstrap IP: " + ip);
                }
            }}
        }


    public void start() {
        if (running) return;
        try {
            this.socket = new DatagramSocket(UDP_PORT);
            this.socket.setBroadcast(true);
            this.running = true;
            new Thread(this::listenLoop).start();
            System.out.println("P2P Network started on UDP Port " + UDP_PORT);
            
            if (!knownPeers.isEmpty()) {
                sendDiscovery();
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
    }

    public boolean isRunning() { return running; }

    public void sendDiscovery() {
        byte[] payload = new byte[0]; 
        floodMessage(TYPE_DISCOVERY, 5, payload, null); 
    }

    public void announcePresence(List<String> myFiles) {
        if (!running) return;
        String fileListStr = String.join(",", myFiles);
        byte[] payload = fileListStr.getBytes(StandardCharsets.UTF_8);
        floodMessage(TYPE_HELLO, 5, payload, null);
    }
    
    public void broadcastStatus(String filename, String progress, String state) {
        if (!running) return;
        String msg = filename + "|" + progress + "|" + state;
        byte[] payload = msg.getBytes(StandardCharsets.UTF_8);
        floodMessage(TYPE_STATUS, 5, payload, null);
    }

    public void sendHelloTo(String targetIP, List<String> myFiles) {
        try {
            String fileListStr = String.join(",", myFiles);
            byte[] payload = fileListStr.getBytes(StandardCharsets.UTF_8);
            sendBinaryPacket(TYPE_HELLO, 1, payload, InetAddress.getByName(targetIP));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                processPacket(packet);
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private void processPacket(DatagramPacket packet) {
        InetAddress senderAddress = packet.getAddress();
        
        addKnownPeer(senderAddress);

        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 4) return; 

        int ttl = data[1] & 0xFF; 
        byte type = data[2];
        int idLen = data[3] & 0xFF;

        if (length < 4 + idLen) return;

        String senderID = new String(data, 4, idLen, StandardCharsets.UTF_8);
        if (senderID.equals(myPeerID)) return;

        String packetSignature = senderID + "_" + type + "_" + Arrays.hashCode(Arrays.copyOfRange(data, 4 + idLen, length));
        if (seenPackets.contains(packetSignature)) return; 
        seenPackets.add(packetSignature);

        byte[] payload = Arrays.copyOfRange(data, 4 + idLen, length);

        if (type == TYPE_DISCOVERY) {
            if (listener != null) listener.onPeerDiscovered(senderAddress.getHostAddress(), null);
        } 
        else if (type == TYPE_HELLO) {
            String content = new String(payload, StandardCharsets.UTF_8);
            List<String> files = new ArrayList<>();
            if (!content.isEmpty()) {
                files = Arrays.asList(content.split(","));
            }
            if (listener != null) listener.onPeerDiscovered(senderAddress.getHostAddress(), files);
        }
        else if (type == TYPE_STATUS) {
            String content = new String(payload, StandardCharsets.UTF_8);
            String[] parts = content.split("\\|");
            if (parts.length >= 3) {
                if (listener != null) listener.onStatusReceived(senderAddress.getHostAddress(), parts[0], parts[1], parts[2]);
            }
        }

        if (ttl > 0) {
            floodMessage(type, ttl - 1, payload, senderAddress);
        }
    }
    
    private void addKnownPeer(InetAddress address) {
        boolean exists = false;
        for (InetAddress peer : knownPeers) {
            if (peer.equals(address)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            knownPeers.add(address);
            System.out.println("[P2P] New Peer Discovered via packet: " + address.getHostAddress());
        }
    }

    private void floodMessage(byte type, int ttl, byte[] payload, InetAddress excludeAddress) {
        for (InetAddress neighbor : knownPeers) {
            if (excludeAddress != null && neighbor.equals(excludeAddress)) {
                continue;
            }
            sendBinaryPacket(type, ttl, payload, neighbor);
        }
        
        try {
             sendBinaryPacket(type, ttl, payload, InetAddress.getByName("255.255.255.255"));
        } catch (Exception e) {}
    }

    private void sendBinaryPacket(byte type, int ttl, byte[] payload, InetAddress target) {
        try {
            byte[] idBytes = myPeerID.getBytes(StandardCharsets.UTF_8);
            int idLen = idBytes.length;
            
            byte[] buffer = new byte[4 + idLen + payload.length];
            buffer[0] = 0x00;
            buffer[1] = (byte) ttl;
            buffer[2] = type;
            buffer[3] = (byte) idLen;
            
            System.arraycopy(idBytes, 0, buffer, 4, idLen);
            System.arraycopy(payload, 0, buffer, 4 + idLen, payload.length);
            
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, target, UDP_PORT);
            socket.send(packet);
        } catch (IOException e) {
        }
    }
    
    public String getMyPeerID() { return myPeerID; }
}