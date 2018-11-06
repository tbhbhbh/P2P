package Tracker;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import Commons.Packet;
import Commons.QueryDirPacket;
import Commons.QueryFilePacket;
import Commons.RegisterPacket;
import Commons.UpdatePacket;
import Commons.ChunkInfo;
import Commons.FileInfo;

public class Server {

    private static final Logger LOGGER = Logger.getLogger( Server.class.getName() );
    private final int LISTENING_PORT = 8080;
    private ServerSocket serverSocket;

    private HashMap<String, FileInfo> fileList;

    public static void main(String[] args) throws Exception {
        LOGGER.info("Starting P2P server");
        new Server();
    }


    public Server() throws Exception {
        fileList = new HashMap<>();
        LOGGER.info("Init server on port " + LISTENING_PORT + "");

        //try to obtain server socket
        try {
            ServerSocket serverSocket = new ServerSocket(LISTENING_PORT);

            while (true) {
                LOGGER.info("Waiting for peer");
                Socket clientSocket = serverSocket.accept();
                LOGGER.info(String.format("Client received %s:%d", clientSocket.getInetAddress(), clientSocket.getPort()));
                Thread t = new Thread() {
                    public void run() {
                        try {
                            handleClientSocket(clientSocket);
                        } catch (Exception e) {
                            LOGGER.warning(e.getMessage());
                            e.printStackTrace();
                        }

                    }
                };
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            System.out.println(e);
        }


    }

    private void handleClientSocket(Socket socket) throws Exception {
        LOGGER.info("Client connected\n");
        ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
        ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
        InetSocketAddress clientAddress = null;
        while (true) {
            Object obj = ois.readObject();
            Packet pkt = (Packet) obj;
            if (pkt.getType() == 0) { // Register packet
                LOGGER.info("Register to Tracker");
                RegisterPacket regPkt = (RegisterPacket) pkt;
//                String clientIP = socket.getInetAddress().getHostAddress();
                int clientPort = regPkt.getPort();
                InetAddress clientIP = regPkt.getPublicIP();
                clientAddress = new InetSocketAddress(clientIP, clientPort);
                LOGGER.info(String.format("Client Address: %s :%d", clientIP, clientPort));
            }
            if (pkt.getType() == 4) {// Update Packet
                LOGGER.info("Update Availability");
                if (clientAddress == null) {
                    continue;
                }
                UpdatePacket upPkt = (UpdatePacket) pkt;
                String filename = upPkt.getFilename();
                int numChunks = upPkt.getChunks();
                FileInfo fileInfo;
                if (!fileList.containsKey(filename)) {
                    LOGGER.info("creating new file entry: %s".format(filename));
                    fileInfo = new FileInfo(filename);
                    for (int i = 0; i < numChunks; i++) {
                        ChunkInfo chunk = new ChunkInfo(i);
                        chunk.addPeer(clientAddress);
                        fileInfo.addChunk(chunk);
                    }
                } else {
                    fileInfo = fileList.get(filename);
                    fileInfo.addPeer(clientAddress);
                }
                fileList.put(filename, fileInfo);
                LOGGER.info(String.format("%s:%d :Add to FileList", clientAddress.getAddress(), clientAddress.getPort()));
            }
            if (pkt.getType() == 1) { // Query Packet (Directory)
                LOGGER.info("List Directory");
                List<String> filenames = fileList.keySet().stream().collect(Collectors.toList());
                QueryDirPacket responsePkt = new QueryDirPacket(filenames);
                oos.writeObject(responsePkt);
                oos.flush();
            }

            if (pkt.getType() == 2) { // Query Packet (File)
                QueryFilePacket queryPkt = (QueryFilePacket) pkt;
                String filename = queryPkt.getFilename();
                LOGGER.info(String.format("Request for file: %s", filename));
                FileInfo fileInfo = fileList.get(filename);
                QueryFilePacket responsePkt = new QueryFilePacket(fileInfo);
                oos.writeObject(responsePkt);
                oos.flush();
            }

            if (pkt.getType() == 5) { // Shutdown
                // Remove records...
                LOGGER.info("shutdown peer");
                for (FileInfo fileInfo : fileList.values()) {
                    fileInfo.removePeer(clientAddress);
                }
                break;
            }
        }
        ois.close();
        oos.close();
        socket.close();
    }
}