package edu.wisc.cs.sdn.vnet;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedList;
import java.util.Queue;



public class TCPSender{
    private static final byte SYN_FLAG = 0x4;
    private static final byte ACK_FLAG = 0x1;
    private static final byte FIN_FLAG = 0x2;
    private static final byte DATA_FLAG = 0x0;


    //connection
    private int seqNum = 0;
    private int ackNum = 0;
    private int baseSeqNum = 0; //first unacked Byte
    private int nextSeqNum = 0;

    private DatagramSocket socket;
    private InetAddress serverAddr;
    private int serverPort;

    // Sliding window parameters
    private int mtu;
    private int sws;  // Sliding window size in segments
    private int cwnd; // Congestion window in bytes

    //stats to display
    private long dataSent = 0;
    private int packetsSent = 0;
    private int packetsReceived = 0;
    private int retransmissions = 0;
    private int duplicateAcks = 0;
    private int outOfSequenceDiscarded = 0;
    private int checksumErrors = 0;
    
    private class TCPPacket {
        int seqNum;
        int ackNum;
        byte flags;
        byte[] data;
        long timestamp;
        int retransmits;
        
        public TCPPacket(int seqNum, int ackNum, byte flags, byte[] data) {
            this.seqNum = seqNum;
            this.ackNum = ackNum;
            this.flags = flags;
            this.data = data;
            this.timestamp = System.nanoTime();
            this.retransmits = 0;
        }
    }

    public TCPSender(int localPort, int mtu, int sws, String serverIP, int serverPort) throws IOException {
        this.socket = new DatagramSocket(localPort);
        this.serverAddr = InetAddress.getByName(serverIP);
        this.serverPort = serverPort;
        this.mtu = mtu;
        this.sws = sws;
        this.cwnd = mtu * sws;
        this.seqNum = 0;
        this.baseSeqNum = 0;
        this.nextSeqNum = 0;
        
        //timeout for socket
        this.socket.setSoTimeout(timeout);
    }

    public void sendFile(String filename) throws IOException {
        //3 way handshaeke
        if (!establishConnection()) {
            System.err.println("Failed to establish connection");
            return;
        }
        
        FileInputStream fileIn = new FileInputStream(filename);
        byte[] buffer = new byte[mtu];
        int bytesRead;
        
        //send data
        while ((bytesRead = fileIn.read(buffer)) > 0) {
            // Copy to prevent buffer reuse issues
            byte[] data = new byte[bytesRead];
            System.arraycopy(buffer, 0, data, 0, bytesRead);
            
            //wait until window allows sending
            while (nextSeqNum - baseSeqNum >= cwnd) {
                if (!receiveAcks()) {
                    // Check for retransmissions if no ACKs received ????
                    checkForRetransmissions();
                }
            }
            
            sendDataPacket(data);
            
            //incoming ACKs
            receiveAcks();
        }
        
        fileIn.close();
        
        while (baseSeqNum < nextSeqNum) { //make sure we have all data
            if (!receiveAcks()) {
                checkForRetransmissions();
            }
        }
        
        closeConnection();
        printStatistics();
    }
}
// package edu.wisc.cs.sdn.vnet;

// import java.io.FileInputStream;
// import java.io.IOException;
// import java.net.DatagramPacket;
// import java.net.DatagramSocket;
// import java.net.InetAddress;
// import java.net.SocketTimeoutException;
// import java.nio.ByteBuffer;
// import java.util.HashMap;
// import java.util.Map;
// import java.util.LinkedList;
// import java.util.Queue;

// /**
//  * Implementation of TCP sender for CS640 Assignment 4
//  */
// public class TCPSender {
//     // Constants
//     private static final byte SYN_FLAG = 0x4;
//     private static final byte ACK_FLAG = 0x1;
//     private static final byte FIN_FLAG = 0x2;
//     private static final byte DATA_FLAG = 0x0;

//     // Maximum number of retransmissions
//     private static final int MAX_RETRANSMITS = 16;
    
//     // Timeout parameters
//     private static final double ALPHA = 0.875;
//     private static final double BETA = 0.75;
//     private static final int INITIAL_TIMEOUT = 5000; // 5 seconds
    
//     // Connection state
//     private int seqNum = 0;
//     private int ackNum = 0;
//     private int baseSeqNum = 0;  // For sliding window (first unacknowledged byte)
//     private int nextSeqNum = 0;  // Next sequence number to use
    
//     // Socket and addresses
//     private DatagramSocket socket;
//     private InetAddress serverAddr;
//     private int serverPort;
    
//     // Sliding window parameters
//     private int mtu;
//     private int sws;  // Sliding window size in segments
//     private int cwnd; // Congestion window in bytes
    
//     // Retransmission timer parameters
//     private double estimatedRTT = 0;
//     private double devRTT = 0;
//     private int timeout = INITIAL_TIMEOUT;
    
//     // Statistics
//     private long dataSent = 0;
//     private int packetsSent = 0;
//     private int packetsReceived = 0;
//     private int retransmissions = 0;
//     private int duplicateAcks = 0;
//     private int outOfSequenceDiscarded = 0;
//     private int checksumErrors = 0;
    
//     // Packet buffer for retransmission
//     private Map<Integer, TCPPacket> unackedPackets = new HashMap<>();
    
//     // Class to represent a TCP packet
//     private class TCPPacket {
//         int seqNum;
//         int ackNum;
//         byte flags;
//         byte[] data;
//         long timestamp;
//         int retransmits;
        
//         public TCPPacket(int seqNum, int ackNum, byte flags, byte[] data) {
//             this.seqNum = seqNum;
//             this.ackNum = ackNum;
//             this.flags = flags;
//             this.data = data;
//             this.timestamp = System.nanoTime();
//             this.retransmits = 0;
//         }
//     }
    
//     // Duplicate ACK tracking
//     private Map<Integer, Integer> duplicateAckCount = new HashMap<>();
    
//     /**
//      * Constructor
//      */
//     public TCPSender(int localPort, int mtu, int sws, String serverIP, int serverPort) throws IOException {
//         this.socket = new DatagramSocket(localPort);
//         this.serverAddr = InetAddress.getByName(serverIP);
//         this.serverPort = serverPort;
//         this.mtu = mtu;
//         this.sws = sws;
//         this.cwnd = mtu * sws;
        
//         // Initialize sequence number (can be randomized in real TCP)
//         this.seqNum = 0;
//         this.baseSeqNum = 0;
//         this.nextSeqNum = 0;
        
//         // Set timeout for socket
//         this.socket.setSoTimeout(timeout);
//     }
    
//     /**
//      * Send a file to the server
//      */
//     public void sendFile(String filename) throws IOException {
//         // Establish connection first
//         if (!establishConnection()) {
//             System.err.println("Failed to establish connection");
//             return;
//         }
        
//         // Open file for reading
//         FileInputStream fileIn = new FileInputStream(filename);
//         byte[] buffer = new byte[mtu];
//         int bytesRead;
        
//         // Send file data
//         while ((bytesRead = fileIn.read(buffer)) > 0) {
//             // Copy to prevent buffer reuse issues
//             byte[] data = new byte[bytesRead];
//             System.arraycopy(buffer, 0, data, 0, bytesRead);
            
//             // Wait until window allows sending
//             while (nextSeqNum - baseSeqNum >= cwnd) {
//                 if (!receiveAcks()) {
//                     // Check for retransmissions if no ACKs received
//                     checkForRetransmissions();
//                 }
//             }
            
//             // Send data packet
//             sendDataPacket(data);
            
//             // Process any incoming ACKs
//             receiveAcks();
//         }
        
//         // Close file
//         fileIn.close();
        
//         // Wait for all data to be acknowledged
//         while (baseSeqNum < nextSeqNum) {
//             if (!receiveAcks()) {
//                 checkForRetransmissions();
//             }
//         }
        
//         // Close connection
//         closeConnection();
        
//         // Print statistics
//         printStatistics();
//     }
    
//     /**
//      * Establish connection with three-way handshake
//      */
//     private boolean establishConnection() throws IOException {
//         System.out.println("Establishing connection...");
        
//         // Send SYN packet
//         TCPPacket synPacket = new TCPPacket(seqNum, 0, SYN_FLAG, new byte[0]);
//         sendPacket(synPacket);
//         seqNum++;  // SYN consumes one sequence number
//         nextSeqNum = seqNum;
        
//         // Wait for SYN-ACK
//         int attempts = 0;
//         boolean connected = false;
        
//         while (attempts < MAX_RETRANSMITS && !connected) {
//             try {
//                 // Receive packet
//                 byte[] receiveBuffer = new byte[mtu + 24];  // Header size + data
//                 DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
//                 socket.receive(udpPacket);
//                 packetsReceived++;
                
//                 // Parse packet
//                 ByteBuffer bb = ByteBuffer.wrap(udpPacket.getData(), 0, udpPacket.getLength());
//                 int rcvSeqNum = bb.getInt();
//                 int rcvAckNum = bb.getInt();
//                 long timestamp = bb.getLong();
//                 int lengthAndFlags = bb.getInt();
//                 short checksum = bb.getShort();
                
//                 // Verify checksum
//                 bb.position(bb.position() - 2);  // Move back to checksum position
//                 bb.putShort((short)0);  // Set checksum to 0 for calculation
//                 byte[] packetData = new byte[udpPacket.getLength()];
//                 System.arraycopy(udpPacket.getData(), 0, packetData, 0, udpPacket.getLength());
//                 short calculatedChecksum = calculateChecksum(packetData);
                
//                 if (calculatedChecksum != checksum) {
//                     System.out.println("Checksum error in SYN-ACK");
//                     checksumErrors++;
//                     continue;
//                 }
                
//                 // Extract flags
//                 byte flags = (byte)(lengthAndFlags & 0x7);
                
//                 // Log packet
//                 logPacket("rcv", flags, rcvSeqNum, (lengthAndFlags >>> 3), rcvAckNum);
                
//                 // Check if SYN-ACK
//                 if ((flags & (SYN_FLAG | ACK_FLAG)) == (SYN_FLAG | ACK_FLAG) && rcvAckNum == seqNum) {
//                     // Save ACK number
//                     ackNum = rcvSeqNum + 1;  // SYN consumes one sequence number
                    
//                     // Send ACK
//                     TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, ACK_FLAG, new byte[0]);
//                     sendPacket(ackPacket);
                    
//                     connected = true;
//                     System.out.println("Connection established");
//                 }
//             } catch (SocketTimeoutException e) {
//                 // Retransmit SYN
//                 System.out.println("Timeout, retransmitting SYN");
//                 TCPPacket synPacket2 = new TCPPacket(seqNum - 1, 0, SYN_FLAG, new byte[0]);
//                 sendPacket(synPacket2);
//                 retransmissions++;
//                 attempts++;
//             }
//         }
        
//         return connected;
//     }
    
//     /**
//      * Send a data packet
//      */
//     private void sendDataPacket(byte[] data) throws IOException {
//         // Create packet with current sequence number
//         TCPPacket packet = new TCPPacket(nextSeqNum, ackNum, ACK_FLAG, data);
        
//         // Send packet
//         sendPacket(packet);
        
//         // Store for potential retransmission
//         unackedPackets.put(nextSeqNum, packet);
        
//         // Update sequence number
//         nextSeqNum += data.length;
        
//         // Update statistics
//         dataSent += data.length;
//     }
    
//     /**
//      * Receive and process ACKs
//      */
//     private boolean receiveAcks() throws IOException {
//         try {
//             // Try to receive an ACK
//             byte[] receiveBuffer = new byte[mtu + 24];
//             DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
//             socket.receive(udpPacket);
//             packetsReceived++;
            
//             // Parse packet
//             ByteBuffer bb = ByteBuffer.wrap(udpPacket.getData(), 0, udpPacket.getLength());
//             int rcvSeqNum = bb.getInt();
//             int rcvAckNum = bb.getInt();
//             long timestamp = bb.getLong();
//             int lengthAndFlags = bb.getInt();
//             short checksum = bb.getShort();
            
//             // Verify checksum
//             bb.position(bb.position() - 2);  // Move back to checksum position
//             bb.putShort((short)0);  // Set checksum to 0 for calculation
//             byte[] packetData = new byte[udpPacket.getLength()];
//             System.arraycopy(udpPacket.getData(), 0, packetData, 0, udpPacket.getLength());
//             short calculatedChecksum = calculateChecksum(packetData);
            
//             if (calculatedChecksum != checksum) {
//                 System.out.println("Checksum error in ACK");
//                 checksumErrors++;
//                 return true;  // We did receive a packet, even if it had errors
//             }
            
//             // Extract flags
//             byte flags = (byte)(lengthAndFlags & 0x7);
//             int dataLength = lengthAndFlags >>> 3;
            
//             // Log packet
//             logPacket("rcv", flags, rcvSeqNum, dataLength, rcvAckNum);
            
//             // Process ACK
//             if ((flags & ACK_FLAG) != 0) {
//                 if (rcvAckNum > baseSeqNum) {
//                     // New ACK, advance window
//                     updateRTT(rcvAckNum);
                    
//                     // Remove acknowledged packets
//                     for (int seq = baseSeqNum; seq < rcvAckNum; seq++) {
//                         unackedPackets.remove(seq);
//                     }
                    
//                     // Update base sequence number
//                     baseSeqNum = rcvAckNum;
                    
//                     // Reset duplicate ACK counter
//                     duplicateAckCount.remove(rcvAckNum);
//                 } else if (rcvAckNum == baseSeqNum) {
//                     // Duplicate ACK
//                     int count = duplicateAckCount.getOrDefault(rcvAckNum, 0) + 1;
//                     duplicateAckCount.put(rcvAckNum, count);
//                     duplicateAcks++;
                    
//                     // Fast retransmit after 3 duplicate ACKs
//                     if (count >= 3) {
//                         // Retransmit the first unacknowledged packet
//                         TCPPacket lostPacket = unackedPackets.get(baseSeqNum);
//                         if (lostPacket != null) {
//                             System.out.println("Fast retransmit triggered by 3 duplicate ACKs");
//                             sendPacket(lostPacket);
//                             retransmissions++;
//                             lostPacket.retransmits++;
//                             lostPacket.timestamp = System.nanoTime();
                            
//                             // Reset duplicate ACK counter
//                             duplicateAckCount.put(rcvAckNum, 0);
//                         }
//                     }
//                 }
//             }
            
//             return true;
//         } catch (SocketTimeoutException e) {
//             return false;
//         }
//     }
    
//     /**
//      * Check for packets that need retransmission
//      */
//     private void checkForRetransmissions() throws IOException {
//         long currentTime = System.nanoTime();
        
//         for (Map.Entry<Integer, TCPPacket> entry : unackedPackets.entrySet()) {
//             TCPPacket packet = entry.getValue();
//             long timeSinceSent = (currentTime - packet.timestamp) / 1_000_000;  // Convert to ms
            
//             if (timeSinceSent > timeout && packet.retransmits < MAX_RETRANSMITS) {
//                 // Time to retransmit
//                 System.out.println("Timeout retransmitting packet with seq " + packet.seqNum);
//                 sendPacket(packet);
//                 retransmissions++;
//                 packet.retransmits++;
//                 packet.timestamp = currentTime;
//             }
//         }
//     }
    
//     /**
//      * Close connection with FIN handshake
//      */
//     private void closeConnection() throws IOException {
//         System.out.println("Closing connection...");
        
//         // Send FIN packet
//         TCPPacket finPacket = new TCPPacket(seqNum, ackNum, FIN_FLAG, new byte[0]);
//         sendPacket(finPacket);
//         seqNum++;  // FIN consumes one sequence number
        
//         // Wait for FIN-ACK
//         int attempts = 0;
//         boolean closed = false;
        
//         while (attempts < MAX_RETRANSMITS && !closed) {
//             try {
//                 // Receive packet
//                 byte[] receiveBuffer = new byte[mtu + 24];
//                 DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
//                 socket.receive(udpPacket);
//                 packetsReceived++;
                
//                 // Parse packet
//                 ByteBuffer bb = ByteBuffer.wrap(udpPacket.getData(), 0, udpPacket.getLength());
//                 int rcvSeqNum = bb.getInt();
//                 int rcvAckNum = bb.getInt();
//                 long timestamp = bb.getLong();
//                 int lengthAndFlags = bb.getInt();
//                 short checksum = bb.getShort();
                
//                 // Verify checksum
//                 bb.position(bb.position() - 2);  // Set checksum to 0 for calculation
//                 bb.putShort((short)0);
//                 byte[] packetData = new byte[udpPacket.getLength()];
//                 System.arraycopy(udpPacket.getData(), 0, packetData, 0, udpPacket.getLength());
//                 short calculatedChecksum = calculateChecksum(packetData);
                
//                 if (calculatedChecksum != checksum) {
//                     System.out.println("Checksum error in FIN-ACK");
//                     checksumErrors++;
//                     continue;
//                 }
                
//                 // Extract flags
//                 byte flags = (byte)(lengthAndFlags & 0x7);
                
//                 // Log packet
//                 logPacket("rcv", flags, rcvSeqNum, (lengthAndFlags >>> 3), rcvAckNum);
                
//                 // Check for ACK of our FIN
//                 if ((flags & ACK_FLAG) != 0 && rcvAckNum == seqNum) {
//                     // Got ACK for our FIN
                    
//                     // Check if this is also a FIN from the server
//                     if ((flags & FIN_FLAG) != 0) {
//                         // This is a FIN-ACK, send ACK for the server's FIN
//                         ackNum = rcvSeqNum + 1;  // FIN consumes one sequence number
//                         TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, ACK_FLAG, new byte[0]);
//                         sendPacket(ackPacket);
//                         closed = true;
//                     } else {
//                         // Just an ACK, wait for FIN from server
//                         continue;
//                     }
//                 }
//                 // If we got a separate FIN from the server
//                 else if ((flags & FIN_FLAG) != 0) {
//                     // Send ACK for the FIN
//                     ackNum = rcvSeqNum + 1;
//                     TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, ACK_FLAG, new byte[0]);
//                     sendPacket(ackPacket);
//                     closed = true;
//                 }
//             } catch (SocketTimeoutException e) {
//                 // Retransmit FIN
//                 System.out.println("Timeout, retransmitting FIN");
//                 TCPPacket finPacket2 = new TCPPacket(seqNum - 1, ackNum, FIN_FLAG, new byte[0]);
//                 sendPacket(finPacket2);
//                 retransmissions++;
//                 attempts++;
//             }
//         }
        
//         System.out.println("Connection closed");
//     }
    
//     /**
//      * Send a packet
//      */
//     private void sendPacket(TCPPacket packet) throws IOException {
//         // Create packet buffer
//         int packetSize = 24 + packet.data.length;  // Header + data
//         byte[] packetData = new byte[packetSize];
//         ByteBuffer bb = ByteBuffer.wrap(packetData);
        
//         // Fill header fields
//         bb.putInt(packet.seqNum);
//         bb.putInt(packet.ackNum);
//         bb.putLong(System.nanoTime());  // Timestamp
        
//         // Length and flags (length in upper 29 bits, flags in lower 3 bits)
//         int lengthAndFlags = (packet.data.length << 3) | (packet.flags & 0x7);
//         bb.putInt(lengthAndFlags);
        
//         // Placeholder for checksum (will be filled in later)
//         bb.putShort((short)0);
        
//         // Zero padding for the remaining 2 bytes
//         bb.putShort((short)0);
        
//         // Add data if any
//         if (packet.data.length > 0) {
//             bb.put(packet.data);
//         }
        
//         // Calculate checksum
//         short checksum = calculateChecksum(packetData);
        
//         // Update checksum in the packet
//         bb.putShort(20, checksum);  // Position 20 is where checksum is stored
        
//         // Send packet
//         DatagramPacket udpPacket = new DatagramPacket(packetData, packetSize, serverAddr, serverPort);
//         socket.send(udpPacket);
//         packetsSent++;
        
//         // Log packet
//         logPacket("snd", packet.flags, packet.seqNum, packet.data.length, packet.ackNum);
//     }
    
//     /**
//      * Calculate one's complement checksum
//      */
//     private short calculateChecksum(byte[] data) {
//         // If data length is odd, add padding
//         int length = data.length;
//         if (length % 2 != 0) {
//             byte[] paddedData = new byte[length + 1];
//             System.arraycopy(data, 0, paddedData, 0, length);
//             paddedData[length] = 0;
//             data = paddedData;
//             length++;
//         }
        
//         // Calculate sum
//         int sum = 0;
//         for (int i = 0; i < length; i += 2) {
//             int word = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
//             sum += word;
//             // Add carry
//             if ((sum & 0xFFFF0000) > 0) {
//                 sum = (sum & 0xFFFF) + 1;
//             }
//         }
        
//         // One's complement
//         return (short) (~sum & 0xFFFF);
//     }
    
//     /**
//      * Update RTT estimate
//      */
//     private void updateRTT(int ackNum) {
//         // Find the oldest acknowledged packet
//         for (int seq = baseSeqNum; seq < ackNum; seq++) {
//             TCPPacket packet = unackedPackets.get(seq);
//             if (packet != null) {
//                 // Calculate sample RTT
//                 long sampleRTT = (System.nanoTime() - packet.timestamp) / 1_000_000;  // Convert to ms
                
//                 if (estimatedRTT == 0) {
//                     // First RTT measurement
//                     estimatedRTT = sampleRTT;
//                     devRTT = sampleRTT / 2;
//                 } else {
//                     // Update estimates using EWMA
//                     double oldEstimatedRTT = estimatedRTT;
//                     estimatedRTT = ALPHA * estimatedRTT + (1 - ALPHA) * sampleRTT;
//                     devRTT = BETA * devRTT + (1 - BETA) * Math.abs(sampleRTT - oldEstimatedRTT);
//                 }
                
//                 // Update timeout
//                 timeout = (int) (estimatedRTT + 4 * devRTT);
                
//                 // Ensure reasonable bounds
//                 if (timeout < 100) timeout = 100;  // Minimum 100ms
//                 if (timeout > 60000) timeout = 60000;  // Maximum 60 seconds
                
//                 // Update socket timeout
//                 try {
//                     socket.setSoTimeout(timeout);
//                 } catch (Exception e) {
//                     System.err.println("Error setting socket timeout: " + e.getMessage());
//                 }
                
//                 break;  // We only need one sample
//             }
//         }
//     }
    
//     /**
//      * Log packet send/receive
//      */
//     private void logPacket(String action, byte flags, int seqNum, int dataLength, int ackNum) {
//         StringBuilder flagStr = new StringBuilder();
        
//         if ((flags & SYN_FLAG) != 0) flagStr.append("S ");
//         else flagStr.append("- ");
        
//         if ((flags & ACK_FLAG) != 0) flagStr.append("A ");
//         else flagStr.append("- ");
        
//         if ((flags & FIN_FLAG) != 0) flagStr.append("F ");
//         else flagStr.append("- ");
        
//         if (dataLength > 0) flagStr.append("D ");
//         else flagStr.append("- ");
        
//         System.out.printf("%s %.3f %s %d %d %d\n", 
//                           action, 
//                           System.currentTimeMillis() / 1000.0, 
//                           flagStr.toString(), 
//                           seqNum, 
//                           dataLength, 
//                           ackNum);
//     }
    
//     /**
//      * Print statistics
//      */
//     private void printStatistics() {
//         System.out.println("\nTransfer Statistics:");
//         System.out.println("Amount of Data transferred: " + dataSent + " bytes");
//         System.out.println("Number of packets sent: " + packetsSent);
//         System.out.println("Number of packets received: " + packetsReceived);
//         System.out.println("Number of out-of-sequence packets discarded: " + outOfSequenceDiscarded);
//         System.out.println("Number of packets discarded due to incorrect checksum: " + checksumErrors);
//         System.out.println("Number of retransmissions: " + retransmissions);
//         System.out.println("Number of duplicate acknowledgements: " + duplicateAcks);
//     }
// }