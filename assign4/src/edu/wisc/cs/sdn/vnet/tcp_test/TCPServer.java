import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;

public class TCPServer {
    private static final int HEADER_SIZE = 24;
    
    private int port;
    private int mtu;
    private int sws;
    private String filename;
    private DatagramSocket socket;
    private int expectedSeqNum = 1;
    private int nextAckNum = 0;
    private boolean connected = false;
    private boolean finishReceiving = false;
    
    // Stats
    private int totalBytes = 0;
    private int totalPackets = 0;
    private int outOfOrderPackets = 0;
    private int corruptedPackets = 0;
    private int duplicateAcks = 0;
    
    public TCPServer(int port, int mtu, int sws, String filename) {
        this.port = port;
        this.mtu = mtu;
        this.sws = sws;
        this.filename = filename;
        
        try {
            this.socket = new DatagramSocket(this.port);
            System.out.println("Server listening on port: " + this.port);
        } catch (Exception e) {
            System.err.println("Unable to bind socket: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void run() {
        try (FileOutputStream fout = new FileOutputStream(filename)) {
            byte[] receiveBuffer = new byte[mtu + HEADER_SIZE];
            
            while (!finishReceiving) {
                DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                
                try {
                    socket.receive(udpPacket);
                    totalPackets++;
                    
                    TCPPacket tcpPacket = new TCPPacket(udpPacket.getData());
                    
                    // Verify checksum
                    short receivedChecksum = tcpPacket.checksum;
                    tcpPacket.checksum = 0;
                    byte[] packetData = tcpPacket.toBytes();
                    short calculatedChecksum = tcpPacket.computeChecksum(packetData);
                    
                    if (calculatedChecksum != receivedChecksum) {
                        System.out.println("Checksum error");
                        corruptedPackets++;
                        continue;
                    }
                    
                    // Log received packet
                    logPacket("rcv", tcpPacket);
                    
                    InetAddress clientAddr = udpPacket.getAddress();
                    int clientPort = udpPacket.getPort();
                    
                    // Handle connection establishment
                    if (tcpPacket.syn && !connected) {
                        // SYN received, send SYN-ACK
                        nextAckNum = tcpPacket.seqNum + 1;
                        TCPPacket synAckPacket = new TCPPacket(0, nextAckNum, System.nanoTime(), true, true, false, new byte[0]);
                        sendPacket(synAckPacket, clientAddr, clientPort);
                        continue;
                    }
                    
                    // Handle ACK for SYN-ACK
                    if (tcpPacket.ack && !connected && tcpPacket.ackNum == 1) {
                        connected = true;
                        System.out.println("Connection established");
                        continue;
                    }
                    
                    // Handle FIN
                    if (tcpPacket.fin) {
                        // FIN received, send ACK
                        nextAckNum = tcpPacket.seqNum + 1;
                        TCPPacket ackPacket = new TCPPacket(0, nextAckNum, System.nanoTime(), true, false, false, new byte[0]);
                        sendPacket(ackPacket, clientAddr, clientPort);
                        
                        // Send FIN-ACK
                        TCPPacket finAckPacket = new TCPPacket(1, nextAckNum, System.nanoTime(), true, false, true, new byte[0]);
                        sendPacket(finAckPacket, clientAddr, clientPort);
                        finishReceiving = true;
                        continue;
                    }
                    
                    // Handle normal data
                    if (tcpPacket.data != null && tcpPacket.data.length > 0) {
                        if (tcpPacket.seqNum == expectedSeqNum) {
                            // In-order packet, process it
                            fout.write(tcpPacket.data);
                            totalBytes += tcpPacket.data.length;
                            expectedSeqNum += tcpPacket.data.length;
                            
                            // Send ACK
                            System.out.println("sending ack becuase ack normal data");
                            TCPPacket ackPacket = new TCPPacket(0, expectedSeqNum, System.nanoTime(), true, false, false, new byte[0]);
                            sendPacket(ackPacket, clientAddr, clientPort);
                        } else if (tcpPacket.seqNum < expectedSeqNum) {
                            // Duplicate packet, send duplicate ACK
                            System.out.println("sending dup ack for dup packet");
                            TCPPacket ackPacket = new TCPPacket(0, expectedSeqNum, System.nanoTime(), true, false, false, new byte[0]);
                            sendPacket(ackPacket, clientAddr, clientPort);
                            duplicateAcks++;
                        } else {
                            // Out-of-order packet, send duplicate ACK
                            System.out.println("sending dup ack for out of order packet");
                            TCPPacket ackPacket = new TCPPacket(0, expectedSeqNum, System.nanoTime(), true, false, false, new byte[0]);
                            sendPacket(ackPacket, clientAddr, clientPort);
                            outOfOrderPackets++;
                        }
                    }
                    
                } catch (SocketTimeoutException e) {
                    // Ignore timeout, continue listening
                }
            }
            
            // Print statistics
            System.out.println("\nTransfer Statistics:");
            System.out.println("Amount of Data received: " + totalBytes + " bytes");
            System.out.println("Number of packets received: " + totalPackets);
            System.out.println("Number of out-of-sequence packets: " + outOfOrderPackets);
            System.out.println("Number of packets with incorrect checksum: " + corruptedPackets);
            System.out.println("Number of duplicate acknowledgements: " + duplicateAcks);
            
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }
    
    private void sendPacket(TCPPacket packet, InetAddress destAddr, int destPort) throws IOException {
        byte[] packetData = packet.toBytes();
        DatagramPacket udpPacket = new DatagramPacket(packetData, packetData.length, destAddr, destPort);
        socket.send(udpPacket);
        logPacket("snd", packet);
    }
    
    private void logPacket(String action, TCPPacket packet) {
        StringBuilder flagStr = new StringBuilder();
        
        if (packet.syn) flagStr.append("S ");
        else flagStr.append("- ");
        
        if (packet.ack) flagStr.append("A ");
        else flagStr.append("- ");
        
        if (packet.fin) flagStr.append("F ");
        else flagStr.append("- ");
        
        if (packet.data != null && packet.data.length > 0) flagStr.append("D");
        else flagStr.append("-");
        
        int dataLength = (packet.data != null) ? packet.data.length : 0;
        
        System.out.printf("%s %.3f %s %d %d %d\n", 
                          action, 
                          System.currentTimeMillis() / 1000.0, 
                          flagStr.toString(), 
                          packet.seqNum, 
                          dataLength, 
                          packet.ackNum);
    }
}
