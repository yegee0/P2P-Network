package cse471;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class StreamManager {

    private static final int CHUNK_SIZE = 256 * 1024;
    private static final int TCP_PORT = 8889;
    private static final int PRIORITY_CHUNKS = 5;

    private static final boolean VERIFY_WITH_SECOND_PEER = true;
    private static final int VERIFY_FIRST_N_CHUNKS = 3;

    private String fileName;
    private final String fileHash;
    private long fileSize;
    private final int totalChunks;

    private final BitSet receivedChunks;
    private final BitSet inFlightChunks;  
    private final Object chunkStateLock = new Object();

    private final RandomAccessFile fileAccess;
    private final File outputFile;

    private final List<String> sourcePeers;
    private int peerRoundRobinIndex = 0;

    private volatile boolean playing = false;
    private volatile boolean downloading = false;

    private ExecutorService downloadExecutor;

    private final Map<String, Long> peerLastActivity = new ConcurrentHashMap<>();
    private final Map<String, String> peerLastAction = new ConcurrentHashMap<>();

    private volatile int minBufferChunks = 2;
    private static final int MAX_BUFFER_CHUNKS = 15;
    private static final int MIN_BUFFER_CHUNKS = 2;

    private final AtomicLong totalLatency = new AtomicLong(0);
    private final AtomicInteger successfulDownloads = new AtomicInteger(0);
    private final AtomicInteger failedDownloads = new AtomicInteger(0);
    private final AtomicLong lastAdjustmentTime = new AtomicLong(System.currentTimeMillis());

    private final ConcurrentMap<Integer, String> chunkHashMap = new ConcurrentHashMap<>();

    public StreamManager(String fileName, String fileHash, long fileSize, String bufferFolderPath, List<String> sourcePeers) throws IOException {
        this.fileName = fileName;
        this.fileHash = fileHash;
        this.fileSize = fileSize;

        this.sourcePeers = new ArrayList<>(sourcePeers);
        Collections.shuffle(this.sourcePeers);

        this.totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
        this.receivedChunks = new BitSet(totalChunks);
        this.inFlightChunks = new BitSet(totalChunks);

        this.outputFile = new File(bufferFolderPath, fileName);
        this.fileAccess = new RandomAccessFile(outputFile, "rw");
        this.fileAccess.setLength(fileSize);
    }

    public void startDownload() {
        if (downloading) return;
        downloading = true;

        if (downloadExecutor == null || downloadExecutor.isShutdown()) {
            downloadExecutor = Executors.newFixedThreadPool(4);
        }

        new Thread(this::downloadLoop, "StreamManager-DownloadLoop").start();
    }

    private void downloadLoop() {
        try {
            for (int i = 0; i < Math.min(PRIORITY_CHUNKS, totalChunks); i++) {
                if (!downloading) return;
                downloadChunkWithRetry(i);
            }

            while (downloading && receivedChunks.cardinality() < totalChunks) {

                boolean anySubmitted = false;

                for (int i = 0; i < totalChunks; i++) {
                    if (!downloading) break;

                    if (!receivedChunks.get(i) && tryMarkInFlight(i)) {
                        final int chunkIndex = i;
                        downloadExecutor.submit(() -> {
                            try {
                                downloadChunkWithRetry(chunkIndex);
                            } finally {
                                clearInFlight(chunkIndex);
                            }
                        });
                        anySubmitted = true;
                    }
                }

                if (!anySubmitted) break;

                Thread.sleep(150);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            downloading = false;
        }
    }

    private boolean tryMarkInFlight(int chunkIndex) {
        synchronized (chunkStateLock) {
            if (receivedChunks.get(chunkIndex) || inFlightChunks.get(chunkIndex)) return false;
            inFlightChunks.set(chunkIndex);
            return true;
        }
    }

    private void clearInFlight(int chunkIndex) {
        synchronized (chunkStateLock) {
            inFlightChunks.clear(chunkIndex);
        }
    }

    private void downloadChunkWithRetry(int chunkIndex) {
        if (receivedChunks.get(chunkIndex)) return;
        if (sourcePeers.isEmpty()) return;

        int attempts = 0;
        boolean success = false;

        while (!success && attempts < sourcePeers.size() * 2 && downloading) {
            if (receivedChunks.get(chunkIndex)) return;

            String targetIP = getNextPeer();
            if (targetIP != null) {
                peerLastActivity.put(targetIP, System.currentTimeMillis());
                peerLastAction.put(targetIP, "Downloading Chunk #" + chunkIndex);

                long startTime = System.currentTimeMillis();
                success = downloadSingleChunk(chunkIndex, targetIP);
                long duration = System.currentTimeMillis() - startTime;

                updateNetworkMetrics(duration, success);

                if (success) {
                    peerLastAction.put(targetIP, "Completed Chunk #" + chunkIndex);
                } else {
                    peerLastAction.put(targetIP, "Failed Chunk #" + chunkIndex);
                }
            }

            attempts++;
        }
    }

    private synchronized void updateNetworkMetrics(long latencyMs, boolean success) {
        if (success) {
            totalLatency.addAndGet(latencyMs);
            successfulDownloads.incrementAndGet();
        } else {
            failedDownloads.incrementAndGet();
        }

        long now = System.currentTimeMillis();
        if (now - lastAdjustmentTime.get() > 2000) {
            adjustBufferStrategy();
            lastAdjustmentTime.set(now);
        }
    }

    private void adjustBufferStrategy() {
        int success = successfulDownloads.get();
        int failed = failedDownloads.get();
        int total = success + failed;
        if (total == 0) return;

        double avgLatency = (double) totalLatency.get() / (success == 0 ? 1 : success);
        double lossRate = (double) failed / total;

        int oldBuffer = minBufferChunks;

        if (avgLatency > 1500 || lossRate > 0.15) {
            minBufferChunks = Math.min(MAX_BUFFER_CHUNKS, minBufferChunks + 1);
        } else if (avgLatency < 500 && lossRate < 0.05) {
            minBufferChunks = Math.max(MIN_BUFFER_CHUNKS, minBufferChunks - 1);
        }

        totalLatency.set(0);
        successfulDownloads.set(0);
        failedDownloads.set(0);

        if (oldBuffer != minBufferChunks) {
            System.out.println(">> Dynamic Buffer Adjusted: Latency=" + (int) avgLatency +
                    "ms, Loss=" + String.format("%.2f", lossRate) +
                    " -> New Buffer Target: " + minBufferChunks + " chunks");
        }
    }

    private synchronized String getNextPeer() {
        if (sourcePeers.isEmpty()) return null;
        String peer = sourcePeers.get(peerRoundRobinIndex);
        peerRoundRobinIndex = (peerRoundRobinIndex + 1) % sourcePeers.size();
        return peer;
    }

    private boolean downloadSingleChunk(int chunkIndex, String targetIP) {
        try {
            byte[] data = fetchChunk(chunkIndex, targetIP);
            if (data != null && data.length > 0) {
                saveChunk(chunkIndex, data, targetIP);
                return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private byte[] fetchChunk(int chunkIndex, String targetIP) throws IOException {
        try (Socket socket = new Socket(targetIP, TCP_PORT)) {
            int currentTimeout = (minBufferChunks > 5) ? 10000 : 5000;
            socket.setSoTimeout(currentTimeout);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            out.writeUTF(fileHash);
            out.writeInt(chunkIndex);
            out.flush();

            int len = in.readInt();
            if (len <= 0) return null;

            byte[] data = new byte[len];
            in.readFully(data);
            return data;
        }
    }

    private synchronized void saveChunk(int chunkIndex, byte[] data, String fromPeer) throws IOException {
        String incomingHash = sha256Hex(data);

        if (receivedChunks.get(chunkIndex)) {
            String old = chunkHashMap.get(chunkIndex);
            if (old != null && !old.equals(incomingHash)) {
                System.out.println("!! CHUNK MISMATCH chunk=" + chunkIndex +
                        " from=" + fromPeer + " old=" + old + " new=" + incomingHash);
            }
            return;
        }

        long offset = (long) chunkIndex * CHUNK_SIZE;
        fileAccess.seek(offset);
        fileAccess.write(data);

        receivedChunks.set(chunkIndex);
        chunkHashMap.put(chunkIndex, incomingHash);

        if (VERIFY_WITH_SECOND_PEER && chunkIndex < VERIFY_FIRST_N_CHUNKS && sourcePeers.size() > 1) {
            verifyChunkAgainstAnotherPeer(chunkIndex, incomingHash, fromPeer);
        }
    }

    private void verifyChunkAgainstAnotherPeer(int chunkIndex, String originalHash, String fromPeer) {
        try {
            String otherPeer = null;
            for (String p : sourcePeers) {
                if (!p.equals(fromPeer)) {
                    otherPeer = p;
                    break;
                }
            }
            if (otherPeer == null) return;

            byte[] other = fetchChunk(chunkIndex, otherPeer);
            if (other == null || other.length == 0) return;

            String otherHash = sha256Hex(other);

            if (!originalHash.equals(otherHash)) {
                System.out.println("!! VERIFY FAILED chunk=" + chunkIndex +
                        " from=" + fromPeer + " vs " + otherPeer +
                        " " + originalHash + " != " + otherHash);
            } else {
                System.out.println(">> VERIFY OK chunk=" + chunkIndex +
                        " from=" + fromPeer + " matches " + otherPeer);
            }
        } catch (Exception ignored) {
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public int getProgress() {
        if (totalChunks == 0) return 0;
        return (int) ((double) receivedChunks.cardinality() / totalChunks * 100);
    }

    public Map<String, String> getActivePeerStatus() {
        Map<String, String> activeParams = new HashMap<>();
        long now = System.currentTimeMillis();

        for (String peer : peerLastActivity.keySet()) {
            if (now - peerLastActivity.get(peer) < 5000) {
                activeParams.put(peer, peerLastAction.getOrDefault(peer, "Active"));
            }
        }
        return activeParams;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    public File getFile() {
        return outputFile;
    }

    public boolean isReadyToPlay() {
        int chunksCheck = Math.min(totalChunks, minBufferChunks);
        for (int i = 0; i < chunksCheck; i++) {
            if (!receivedChunks.get(i)) return false;
        }
        return true;
    }

    public void close() throws IOException {
        downloading = false;

        if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();
            try {
                downloadExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        if (fileAccess != null) fileAccess.close();
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
}
