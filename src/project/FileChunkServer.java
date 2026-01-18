package cse471;

import java.io.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class FileChunkServer {
    
    private static final int TCP_PORT = 8889;
    private static final int CHUNK_SIZE = 256 * 1024;
    private File rootFolder;
    private boolean running = false;
    private ServerSocket serverSocket;
    
    private Map<String, File> fileMap = new ConcurrentHashMap<>();

    public FileChunkServer(File rootFolder) {
        this.rootFolder = rootFolder;
    }

    public void start() {
        running = true;
        indexFiles();
        
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(TCP_PORT);
                System.out.println("File Chunk Server started on TCP Port " + TCP_PORT);
                
                while (running) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (IOException e) {
                if(running) e.printStackTrace();
            }
        }).start();
    }

    private void indexFiles() {
        System.out.println("Indexing files in root folder...");
        File[] files = rootFolder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.getName().startsWith(".")) {
                    try {
                        String hash = FileChecksum.getFileHash(f);
                        fileMap.put(hash, f);
                        System.out.println("Indexed: " + f.getName() + " -> " + hash);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        System.out.println("Indexing complete.");
    }

    public void stop() {
        running = false;
        try { if(serverSocket != null) serverSocket.close(); } catch (IOException e) {}
    }

    private void handleClient(Socket socket) {
        try (
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream())
        ) {
            String requestedHash = in.readUTF(); 
            int chunkIndex = in.readInt();
            
            File file = fileMap.get(requestedHash); 
            
            if (file == null || !file.exists()) {
                out.writeInt(-1); 
                return;
            }

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long offset = (long) chunkIndex * CHUNK_SIZE;
                if (offset >= file.length()) {
                    out.writeInt(0); 
                    return;
                }
                
                raf.seek(offset);
                byte[] buffer = new byte[CHUNK_SIZE];
                int bytesRead = raf.read(buffer);
                
                if (bytesRead > 0) {
                    out.writeInt(bytesRead);
                    out.write(buffer, 0, bytesRead);
                } else {
                    out.writeInt(0);
                }
            }
            
        } catch (IOException e) {
        } finally {
            try { socket.close(); } catch (IOException e) {}
        }
    }
}