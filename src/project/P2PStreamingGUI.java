package cse471;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

@SuppressWarnings("serial")
public class P2PStreamingGUI extends JFrame {

    private JLabel statusLabel, rootFolderLabel, bufferFolderLabel;
    private JTextArea logArea;
    private JProgressBar globalBufferBar;

    private JPanel topPanel;
    private JPanel bottomPanel;
    private JPanel leftPanel;
    private JScrollPane tableScroll;
    private JSplitPane mainSplit;
    private JSplitPane rightSplit;
    private JMenuBar mainMenuBar;

    private Map<String, Set<String>> validHashNames = new HashMap<>();
    private Map<String, Set<String>> hashToPeersMap = new HashMap<>();
    private Map<String, Long> hashToSizeMap = new HashMap<>();
    private Map<String, RemoteStreamInfo> remoteStreamStatus = new ConcurrentHashMap<>();
    
    private DefaultListModel<String> listModel;
    private ArrayList<String> listModelHashes = new ArrayList<>();
    private DefaultTableModel tableModel;

    private P2PNetworkManager networkManager;
    private FileChunkServer fileServer;
    private StreamManager currentStreamManager; 
    
    private File rootFolder;
    private File bufferFolder;
    private Timer uiUpdateTimer;

    private JPanel videoPanel, videoContainer;
    private EmbeddedMediaPlayerComponent vlcPlayer;
    private boolean isFullscreen = false;
    
    private List<String> allowedExtensions = new ArrayList<>(Arrays.asList("mp4", "avi", "mkv"));

    class RemoteStreamInfo {
        String filename;
        String progress;
        String status;
        long lastUpdate;

        public RemoteStreamInfo(String filename, String progress, String status) {
            this.filename = filename;
            this.progress = progress;
            this.status = status;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    public P2PStreamingGUI() {
        setTitle("P2P Video Streaming");
        setSize(1200, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        P2PNetworkManager.PeerDiscoveryListener discoveryListener = new P2PNetworkManager.PeerDiscoveryListener() {
            @Override
            public void onPeerDiscovered(String peerIP, List<String> filesData) {
                if (filesData == null) {
                    if (rootFolder != null && networkManager != null) {
                        List<String> myFileList = scanLocalFilesWithHash();
                        networkManager.sendHelloTo(peerIP, myFileList);
                    }
                    return; 
                }

                SwingUtilities.invokeLater(() -> {
                    boolean changed = false;
                    for (String entry : filesData) {
                        String[] parts = entry.split(":");
                        if (parts.length >= 3) {
                            String hash = parts[0];
                            String name = parts[1];
                            long size = Long.parseLong(parts[2]);

                            validHashNames.computeIfAbsent(hash, k -> new HashSet<>()).add(name);
                            hashToSizeMap.put(hash, size);
                            hashToPeersMap.computeIfAbsent(hash, k -> new HashSet<>()).add(peerIP);
                            changed = true;
                        }
                    }
                    if (changed) refreshVideoList(null);
                });
            }

            @Override
            public void onStatusReceived(String peerIP, String filename, String progress, String state) {
                remoteStreamStatus.put(peerIP, new RemoteStreamInfo(filename, progress, state));
            }
        };

        networkManager = new P2PNetworkManager(discoveryListener);

        createUI();
        startUiTimer();
        
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { cleanupAndExit(); }
        });
    }
    
    private void log(String message) {
        if (logArea != null) {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    private void createUI() {
        createMenuBar();
        createTopPanel();
        
        leftPanel = new JPanel(new BorderLayout());
        JPanel searchPanel = new JPanel(new BorderLayout(5, 5));
        searchPanel.setBorder(BorderFactory.createTitledBorder("Search Network"));
        JTextField searchField = new JTextField();
        JButton searchButton = new JButton("Search");
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);

        listModel = new DefaultListModel<>();
        JList<String> videoList = new JList<>(listModel);
        
        JButton streamButton = new JButton("Stream Selected");
        streamButton.addActionListener(e -> {
            int selectedIndex = videoList.getSelectedIndex();
            if (selectedIndex < 0) return;
            String selectedName = videoList.getSelectedValue();
            String hash = listModelHashes.get(selectedIndex);
            startStreaming(hash, selectedName);
        });

        JPanel listContainer = new JPanel(new BorderLayout());
        listContainer.setBorder(BorderFactory.createTitledBorder("Available Videos"));
        listContainer.add(new JScrollPane(videoList), BorderLayout.CENTER);
        listContainer.add(streamButton, BorderLayout.SOUTH);

        leftPanel.add(searchPanel, BorderLayout.NORTH);
        leftPanel.add(listContainer, BorderLayout.CENTER);

        String[] columns = {"Video", "Source Peer", "Progress %", "Status"};
        tableModel = new DefaultTableModel(null, columns);
        JTable table = new JTable(tableModel);
        tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Active Streams"));
        tableScroll.setPreferredSize(new Dimension(500, 200));

        videoContainer = new JPanel(new BorderLayout());
        videoPanel = new JPanel(new BorderLayout());
        videoPanel.setBackground(Color.BLACK);
        try {
            vlcPlayer = new EmbeddedMediaPlayerComponent();
            videoPanel.add(vlcPlayer, BorderLayout.CENTER);
            
            vlcPlayer.videoSurfaceComponent().addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE && isFullscreen) {
                        toggleFullscreen();
                    }
                }
            });
            
        } catch (Throwable err) {
            videoPanel.add(new JLabel("VLC Not Found", SwingConstants.CENTER));
        }

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton fsButton = new JButton("Toggle Fullscreen");
        fsButton.addActionListener(e -> toggleFullscreen());
        controls.add(fsButton);
        videoPanel.add(controls, BorderLayout.SOUTH);

        videoContainer.add(videoPanel, BorderLayout.CENTER);
        
        rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, videoContainer);
        rightSplit.setDividerLocation(200);
        
        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightSplit);
        mainSplit.setDividerLocation(350);
        
        add(mainSplit, BorderLayout.CENTER);
        
        createBottomPanel();
        searchButton.addActionListener(e -> {
            if (networkManager != null && networkManager.isRunning()) {
                networkManager.sendDiscovery();
            }
            refreshVideoList(searchField.getText());
        });
  }
    
    private void createTopPanel() {
        topPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Status: DISCONNECTED");
        statusLabel.setForeground(Color.RED);
        rootFolderLabel = new JLabel("Root: (not set)");
        bufferFolderLabel = new JLabel("Buffer: (not set)");
        
        JPanel folderPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        folderPanel.add(rootFolderLabel);
        folderPanel.add(Box.createHorizontalStrut(15));
        folderPanel.add(bufferFolderLabel);
        
        topPanel.add(statusLabel, BorderLayout.WEST);
        topPanel.add(folderPanel, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);
    }
    
    private void createBottomPanel() {
        bottomPanel = new JPanel(new BorderLayout());
        
        JPanel bufferPanel = new JPanel(new BorderLayout());
        bufferPanel.setBorder(BorderFactory.createTitledBorder("Global Buffer"));
        globalBufferBar = new JProgressBar(0, 100);
        globalBufferBar.setStringPainted(true);
        bufferPanel.add(globalBufferBar, BorderLayout.CENTER);
        
        logArea = new JTextArea(5, 20);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Event Log"));
        
        bottomPanel.add(bufferPanel, BorderLayout.NORTH);
        bottomPanel.add(logScroll, BorderLayout.CENTER);
        bottomPanel.setPreferredSize(new Dimension(100, 180));
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;

        boolean showUI = !isFullscreen;

        if (topPanel != null) topPanel.setVisible(showUI);
        if (bottomPanel != null) bottomPanel.setVisible(showUI);
        if (mainMenuBar != null) mainMenuBar.setVisible(showUI);
        if (leftPanel != null) leftPanel.setVisible(showUI);
        if (tableScroll != null) tableScroll.setVisible(showUI);

        if (isFullscreen) {
            setExtendedState(JFrame.MAXIMIZED_BOTH);
            mainSplit.setDividerSize(0);
            rightSplit.setDividerSize(0);
        } else {
            setExtendedState(JFrame.NORMAL);
            mainSplit.setDividerSize(10);
            rightSplit.setDividerSize(10);

            SwingUtilities.invokeLater(() -> {
                mainSplit.setDividerLocation(350);
                rightSplit.setDividerLocation(200);
            });
        }

        revalidate();
        repaint();

        SwingUtilities.invokeLater(() -> {
            if (vlcPlayer != null) {
                vlcPlayer.videoSurfaceComponent().revalidate();
                vlcPlayer.videoSurfaceComponent().repaint();
            }
        });
    }
    
    private void startStreaming(String hash, String fileName) {
        if (bufferFolder == null) {
            JOptionPane.showMessageDialog(this, "Please set Buffer Folder first.");
            return;
        }

        Set<String> peers = hashToPeersMap.get(hash);
        Long size = hashToSizeMap.get(hash);

        if (peers == null || peers.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No sources available.");
            return;
        }

        if (currentStreamManager != null) {
            try { currentStreamManager.close(); } catch (IOException e) {}
        }

        try {
            List<String> peerList = new ArrayList<>(peers);
            currentStreamManager = new StreamManager(fileName, hash, size, bufferFolder.getAbsolutePath(), peerList);
            
            log(">> Starting stream: " + fileName);
            currentStreamManager.startDownload();
            
            new Thread(() -> {
                StreamManager sm = currentStreamManager;
                try {
                    while (!sm.isReadyToPlay()) {
                        if (currentStreamManager != sm) return;
                        Thread.sleep(200);
                    }
                    SwingUtilities.invokeLater(() -> {
                         if (vlcPlayer != null) {
                             vlcPlayer.mediaPlayer().media().play(sm.getFile().getAbsolutePath());
                             log(">> Playback started!");
                         }
                    });
                } catch(Exception e) {}
            }).start();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private List<String> scanLocalFilesWithHash() {
        List<String> list = new ArrayList<>();
        if (rootFolder == null) return list;

        File[] files = rootFolder.listFiles((d, name) -> {
            String lowerName = name.toLowerCase();
            for (String ext : allowedExtensions) {
                if (lowerName.endsWith("." + ext)) return true;
            }
            return false;
        });

        if (files != null) {
            for (File f : files) {
                try {
                    String hash = FileChecksum.getFileHash(f);
                    list.add(hash + ":" + f.getName() + ":" + f.length());
                    log("Indexed: " + f.getName());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return list;
    }

    private void refreshVideoList(String query) {
        listModel.clear();
        listModelHashes.clear();
        String q = (query == null) ? "" : query.toLowerCase();
        
        for (Map.Entry<String, Set<String>> entry : validHashNames.entrySet()) {
            String hash = entry.getKey();
            Set<String> names = entry.getValue();
            for (String name : names) {
                String lowerName = name.toLowerCase();
                
                boolean isExtensionAllowed = false;
                for (String ext : allowedExtensions) {
                    if (lowerName.endsWith("." + ext)) {
                        isExtensionAllowed = true;
                        break;
                    }
                }
                if (!isExtensionAllowed) continue;

                if (lowerName.contains(q)) {
                    listModel.addElement(name);
                    listModelHashes.add(hash);
                }
            }
        }
    }

    private void updateActiveStreamsTable() {
        tableModel.setRowCount(0); 
        long now = System.currentTimeMillis();

        if (currentStreamManager != null) {
            String myFileName = currentStreamManager.getFile().getName();
            int prog = currentStreamManager.getProgress();
            
            Map<String, String> activePeersMap = currentStreamManager.getActivePeerStatus();
            String sourcePeersStr;
            
            if (activePeersMap.isEmpty()) {
                sourcePeersStr = "Connecting...";
            } else {
                sourcePeersStr = String.join(", ", activePeersMap.keySet());
            }
            
            tableModel.addRow(new Object[]{myFileName, sourcePeersStr, prog + "%", "Downloading"});
            
            if (prog >= 100) {
                 tableModel.setValueAt("Completed", tableModel.getRowCount()-1, 3);
            }
        }

        remoteStreamStatus.entrySet().removeIf(entry -> (now - entry.getValue().lastUpdate) > 10000);

        for (Map.Entry<String, RemoteStreamInfo> entry : remoteStreamStatus.entrySet()) {
            String remoteIP = entry.getKey();
            RemoteStreamInfo info = entry.getValue();
            tableModel.addRow(new Object[]{info.filename, remoteIP, info.progress, info.status});
        }
    }

    private void cleanupAndExit() {
        if (networkManager != null) networkManager.stop();
        if (fileServer != null) fileServer.stop();
        if (currentStreamManager != null) try { currentStreamManager.close(); } catch(Exception e){}
        if (vlcPlayer != null) vlcPlayer.release();
        System.exit(0);
    }
    
    private void startUiTimer() {
        uiUpdateTimer = new Timer();
        uiUpdateTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> {
                    if (currentStreamManager != null) {
                        int p = currentStreamManager.getProgress();
                        globalBufferBar.setValue(p);
                        globalBufferBar.setString(p + "% Downloaded");
                        
                        if (networkManager != null) {
                            String status = (p >= 100) ? "Completed" : "Streaming";
                            networkManager.broadcastStatus(currentStreamManager.getFile().getName(), p + "%", status);
                        }
                    }
                    updateActiveStreamsTable();
                });
            }
        }, 1000, 1000); 
    }

    private void createMenuBar() {
        mainMenuBar = new JMenuBar();
        JMenu mStream = new JMenu("Stream"); 
        
        JMenuItem mConnect = new JMenuItem("Connect");
        mConnect.addActionListener(e -> {
            networkManager.start();

            if (rootFolder != null) {
                networkManager.announcePresence(scanLocalFilesWithHash());
                log("Announced my local files after connect.");
            } else {
                log("Root folder not set yet (no files to announce).");
            }

            networkManager.sendDiscovery();

            statusLabel.setText("Status: CONNECTED");
            statusLabel.setForeground(new Color(0, 128, 0));
            log("Connected to Network.");
        });


        JMenuItem mDisconnect = new JMenuItem("Disconnect");
        mDisconnect.addActionListener(e -> cleanupAndExit());
        
        JMenuItem mRoot = new JMenuItem("Set Root Video Folder...");
        mRoot.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                rootFolder = fc.getSelectedFile();
                rootFolderLabel.setText("Root: " + rootFolder.getName());
                log("Root set: " + rootFolder.getAbsolutePath());
                if (fileServer != null) fileServer.stop();
                fileServer = new FileChunkServer(rootFolder);
                fileServer.start();
                networkManager.announcePresence(scanLocalFilesWithHash());
            }
        });
        
        JMenuItem mBuffer = new JMenuItem("Set Buffer Folder...");
        mBuffer.addActionListener(e -> {
             JFileChooser fc = new JFileChooser();
             fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
             if(fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                 bufferFolder = fc.getSelectedFile();
                 bufferFolderLabel.setText("Buffer: " + bufferFolder.getName());
                 log("Buffer set: " + bufferFolder.getAbsolutePath());
             }
        });

        JMenuItem mFilter = new JMenuItem("Set File Filter...");
        mFilter.addActionListener(e -> {
            String currentFilters = String.join(",", allowedExtensions);
            String input = JOptionPane.showInputDialog(this, 
                    "Enter file extensions separated by comma:", 
                    currentFilters);

            if (input != null && !input.trim().isEmpty()) {
                allowedExtensions.clear();
                String[] parts = input.split(",");
                for (String p : parts) {
                    allowedExtensions.add(p.trim().toLowerCase());
                }
                log("File filter updated: " + allowedExtensions);
                
                if (rootFolder != null) {
                    List<String> newFileList = scanLocalFilesWithHash();
                    if (networkManager != null && networkManager.isRunning()) {
                        networkManager.announcePresence(newFileList);
                        log("Re-announced files with new filter.");
                    }
                }
                
                refreshVideoList(null); 
            }
        });

        JMenuItem mExit = new JMenuItem("Exit");
        mExit.addActionListener(e -> cleanupAndExit());

        mStream.add(mConnect);
        mStream.add(mDisconnect);
        mStream.addSeparator();
        mStream.add(mRoot);
        mStream.add(mBuffer);
        mStream.add(mFilter); 
        mStream.addSeparator();
        mStream.add(mExit);
        
        JMenu mHelp = new JMenu("Help");
        JMenuItem mAbout = new JMenuItem("About Developer"); 
        mAbout.addActionListener(e -> JOptionPane.showMessageDialog(this, "CSE471 Project"));
        mHelp.add(mAbout);

        mainMenuBar.add(mStream);
        mainMenuBar.add(mHelp);
        setJMenuBar(mainMenuBar);
    }

    public static void main(String[] args) {
        System.setProperty("jna.library.path", "C:\\Program Files\\VideoLAN\\VLC");
        new NativeDiscovery().discover();
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) {}
            new P2PStreamingGUI().setVisible(true);
        });
    }
}