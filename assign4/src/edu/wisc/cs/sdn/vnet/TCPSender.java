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



/*
 * Header: 4 + 4 + 8 + 4 + 2 + 2 = 24 bytes
 * 1. client initialization: create port bound to local port, set server addr n port, init seq #s, set socket for retransmissions,
 * init the sliding window parameters
 * 2.establish connection: syn from us with seq#0 as we are establishing the connection, send to receiver and increase the sequence number
 * Receive syn + ack, if there's a timeout, retransmit the last SYN packet, if it comes back, then we verify the checksum and the correct 
 * flags set, and then verify the ACK matches our seqNum
 * Ack back to server with server's SeqNum + 1
 * 2. Data transmission: read correct amount of bytes
 */
public class TCPSender {
    private static final int MAX_RETRANSMISSIONS = 16;
    private static final int INITIAL_TIMEOUT = 5000; // 5 seconds

    //connection
    private int seqNum = 0;
    private int ackNum = 0;
    private int baseSeqNum = 0; //first unacked Byte
    private int nextSeqNum = 0;

    //congestion control
    private int congestionWindow = 1; // Start with 1 MSS (Maximum Segment Size)
    private int congestionThreshold; // ssthresh - transition point between slow start and CA
    private boolean inSlowStart = true; // Track if we're in slow start or congestion avoidance
    private int duplicateAckCount = 0; // For fast retransmit

    private DatagramSocket socket;
    private InetAddress serverAddr;
    private int serverPort;

    // Sliding window parameters
    private int mtu;
    private int sws; // Window size in segments
    private int timeout = INITIAL_TIMEOUT; // Current timeout value

    // RTT estimation
    private double estimatedRTT = 0;
    private double devRTT = 0;
    private static final double ALPHA = 0.875; // For EWMA of RTT
    private static final double BETA = 0.75;   // For EWMA of RTT variance

    // Packet storage
    private Map<Integer, TCPPacket> unackedPackets = new HashMap<>(); //retransmissions

    //stats to display
    private long dataSent = 0;
    private int packetsSent = 0;
    private int packetsReceived = 0;
    private int retransmissions = 0;
    private int duplicateAcks = 0;
    private int outOfSequenceDiscarded = 0;
    private int checksumErrors = 0;

    public TCPSender(int localPort, int mtu, int sws, String serverIP, int serverPort) throws IOException {
        this.socket = new DatagramSocket(localPort);
        this.serverAddr = InetAddress.getByName(serverIP);
        this.serverPort = serverPort;
        this.mtu = mtu;
        this.sws = sws;
        
        //init congestion control
        this.congestionWindow = 1; // Start with 1 segment
        this.congestionThreshold = 65535; //infinity as max
        
        //timeout for socket
        this.socket.setSoTimeout(timeout);
    }

    public void sendFile(String filename) throws IOException {
        //3 way handshaeke
        if (!establishConnection()) {
            System.err.println("Failed to establish connection");
            return;
        }
        //being streaming of data
        System.out.println("Beginning to read data from file " + filename);
        FileInputStream fileIn = new FileInputStream(filename);
        byte[] buffer = new byte[mtu - 24]; 
        int bytesRead;
        
        //send data
        while ((bytesRead = fileIn.read(buffer)) > 0) {
            byte[] data = new byte[bytesRead];
            System.arraycopy(buffer, 0, data, 0, bytesRead);
            
            //wait until window allows sending
            while ((nextSeqNum - baseSeqNum) >= getEffectiveWindow()) {
                if (!receiveAcks()) {
                    //look for retransmissions if no ACKs received
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

    // we want the effective window size (minimum of congestion window and flow control window)
    private int getEffectiveWindow() {
        return congestionWindow;
    }

    private boolean establishConnection() throws IOException {
        System.out.println("Establishing Connection...");

        //syn
        TCPPacket synPacket = new TCPPacket(seqNum, 0, System.nanoTime(), false, true, false, new byte[0]);
        sendPacket(synPacket);
        seqNum++;
        nextSeqNum = seqNum;

        //syn + ack --> wait for server to send
        int tries = 0;
        boolean connect = false;
        
        while (!connect && tries < MAX_RETRANSMISSIONS) {
            try {
                //receive packet
                byte[] receiveBuffer = new byte[mtu + 24];
                DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(udpPacket);
                packetsReceived++;
                
                //deserialize packet using TCPPacket constructor
                TCPPacket receivedPacket = new TCPPacket(udpPacket.getData());
                
                //verify checksum
                short receivedChecksum = receivedPacket.checksum;
                receivedPacket.checksum = 0;
                byte[] packetData = receivedPacket.toBytes();
                short calculatedChecksum = receivedPacket.computeChecksum(packetData);
                
                if (calculatedChecksum != receivedChecksum) {
                    System.out.println("Checksum error in SYN-ACK");
                    checksumErrors++;
                    continue;
                }

                //log packet
                logPacket("rcv", receivedPacket);
                
                //check if SYN-ACK
                if (receivedPacket.ack && receivedPacket.syn && receivedPacket.ackNum == seqNum) {
                    ackNum = receivedPacket.seqNum + 1;  // SYN consumes one sequence number
                    
                    //send ACK
                    TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, System.nanoTime(), true, false, false, new byte[0]);
                    sendPacket(ackPacket);
                    
                    connect = true;
                    System.out.println("Connection established");
                }
            } catch (SocketTimeoutException e) {
                //retransmit SYN
                System.out.println("Timeout, retransmitting SYN");
                TCPPacket synPacket2 = new TCPPacket(seqNum - 1, 0, System.nanoTime(), false, true, false, new byte[0]);
                sendPacket(synPacket2);
                retransmissions++;
                tries++;
            }
        }
        
        return connect;
    }
    
    //send data packet and add to unacked packets
    private void sendDataPacket(byte[] data) throws IOException {
        TCPPacket packet = new TCPPacket(nextSeqNum, ackNum, System.nanoTime(), true, false, false, data);
        sendPacket(packet);
        
        //add to unacked packets
        unackedPackets.put(nextSeqNum, packet);
        
        //update sequence number
        nextSeqNum += data.length;
        
        //update statistics
        dataSent += data.length;
    }
    
    //send packet
    private void sendPacket(TCPPacket packet) throws IOException {
        //serialize packet
        byte[] packetData = packet.toBytes();
        
        //create UDP packet
        DatagramPacket udpPacket = new DatagramPacket(
                packetData, packetData.length, serverAddr, serverPort);
        
        //send packet
        socket.send(udpPacket);
        packetsSent++;
        
        //log packet
        logPacket("snd", packet);
    }
    
    //receive and process ACKs
    private boolean receiveAcks() throws IOException {
        try {
            //try to receive an ACK
            byte[] receiveBuffer = new byte[mtu + 24];
            DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(udpPacket);
            packetsReceived++;
            
            //deserialize packet
            TCPPacket receivedPacket = new TCPPacket(udpPacket.getData());
            
            //verify checksum
            short receivedChecksum = receivedPacket.checksum;
            receivedPacket.checksum = 0;
            byte[] packetData = receivedPacket.toBytes();
            short calculatedChecksum = receivedPacket.computeChecksum(packetData);
            
            if (calculatedChecksum != receivedChecksum) {
                System.out.println("Checksum error in ACK");
                checksumErrors++;
                return true;  //we did receive a packet, even if it had errors
            }
            
            //log packet
            logPacket("rcv", receivedPacket);
            
            //process ACK
            if (receivedPacket.ack) {
                if (receivedPacket.ackNum > baseSeqNum) {
                    //since we got a new ACK, advance window
                    updateRTT(unackedPackets.get(baseSeqNum));
                    
                    //remove acknowledged packets
                    for (int seq = baseSeqNum; seq < receivedPacket.ackNum; ) {
                        TCPPacket packet = unackedPackets.get(seq);
                        if (packet != null) {
                            unackedPackets.remove(seq);
                            seq += (packet.data != null) ? packet.data.length : 1;
                        } else {
                            seq++;
                        }
                    }
                    
                    //update base sequence number
                    baseSeqNum = receivedPacket.ackNum;
                    
                    //reset duplicate ACK counter
                    duplicateAckCount = 0;
                    
                    //Update congestion window based on current phase
                    if (inSlowStart) {
                        // Slow start - exponential increase
                        congestionWindow += mtu - 24; // Increase by 1 segment
                        
                        // Check if we should transition to congestion avoidance
                        if (congestionWindow >= congestionThreshold) {
                            inSlowStart = false;
                            System.out.println("Transitioning from slow start to congestion avoidance at window=" + 
                                               congestionWindow + ", threshold=" + congestionThreshold);
                        }
                    } else {
                        // Congestion avoidance - additive increase
                        congestionWindow += mtu - 24;
                    }
                    
                    System.out.println("Window updated - now " + congestionWindow + " bytes");
                    
                } else if (receivedPacket.ackNum == baseSeqNum) {
                    //dup ACK
                    duplicateAckCount++;
                    duplicateAcks++;
                    
                    //now we implement fast retransmit after 3 duplicate ACKs
                    if (duplicateAckCount >= 3) {
                        //Retransmit the first unacknowledged packet
                        TCPPacket lostPacket = unackedPackets.get(baseSeqNum);
                        if (lostPacket != null) {
                            System.out.println("Fast retransmit triggered by 3 duplicate ACKs");
                            sendPacket(lostPacket);
                            retransmissions++;
                            lostPacket.retransmits++;
                            
                            //now we implement fast recovery so it gets set to the old congestion window before the collapse which was 1/2
                            congestionThreshold = congestionWindow / 2;
                            congestionWindow = congestionThreshold;
                            inSlowStart = false; // now we aren't slow starting and we are in congestion avoidance
                            
                            System.out.println("Fast recovery - window=" + congestionWindow + 
                                               ", threshold=" + congestionThreshold);
                            
                            //reset duplicate ACK counter
                            duplicateAckCount = 0;
                        }
                    }
                }
            }
            
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        }
    }
    
    //check for packets that need retransmission
    private void checkForRetransmissions() throws IOException {
        long currentTime = System.nanoTime();
        
        for (Map.Entry<Integer, TCPPacket> entry : unackedPackets.entrySet()) {
            TCPPacket packet = entry.getValue();
            
            //check if timeout has occurred
            if ((currentTime - packet.timestamp) / 1_000_000 > timeout) {
                //check if max retransmissions reached
                if (packet.retransmits < MAX_RETRANSMISSIONS) {
                    //retransmit
                    // Create a new packet with updated timestamp
                    TCPPacket retransPacket = new TCPPacket(
                        packet.seqNum, 
                        packet.ackNum, 
                        System.nanoTime(), 
                        packet.ack, 
                        packet.syn, 
                        packet.fin, 
                        packet.data
                    );
                    retransPacket.retransmits = packet.retransmits + 1;
                    
                    // Replace old packet in unacked map
                    unackedPackets.put(packet.seqNum, retransPacket);
                    
                    // Send the packet
                    sendPacket(retransPacket);
                    retransmissions++;
                    
                    // if there's a timeout --> multiplicative decrease
                    congestionThreshold = congestionWindow / 2; 
                    congestionWindow = 1; // reset to 1 segment
                    inSlowStart = true;  // enter slow start again
                    
                    System.out.println("Timeout - reset window to " + congestionWindow + ", threshold=" + congestionThreshold);
                } else {
                    throw new IOException("Max retransmissions reached for packet with seq " + packet.seqNum);
                }
            }
        }
    }
    
    //close connection with FIN handshake
    private void closeConnection() throws IOException {
        System.out.println("Closing connection...");
        
        //create FIN packet
        TCPPacket finPacket = new TCPPacket(seqNum, ackNum, System.nanoTime(), false, false, true, new byte[0]);
        
        boolean closed = false;
        int attempts = 0;
        
        while (!closed && attempts < MAX_RETRANSMISSIONS) {
            //send FIN
            sendPacket(finPacket);
            seqNum++;  //FIN consumes one sequence number
            
            try {
                //wait for ACK
                byte[] receiveBuffer = new byte[mtu + 24];
                DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(udpPacket);
                packetsReceived++;
                
                //deserialize packet
                TCPPacket receivedPacket = new TCPPacket(udpPacket.getData());
                
                //verify checksum
                short receivedChecksum = receivedPacket.checksum;
                receivedPacket.checksum = 0;
                byte[] packetData = receivedPacket.toBytes();
                short calculatedChecksum = receivedPacket.computeChecksum(packetData);
                
                if (calculatedChecksum != receivedChecksum) {
                    System.out.println("Checksum error in FIN-ACK");
                    checksumErrors++;
                    continue;
                }
                
                //log packet
                logPacket("rcv", receivedPacket);
                
                //check for ACK of our FIN
                if (receivedPacket.ack && receivedPacket.ackNum == seqNum) {
                    //got ACK for our FIN
                    
                    //check if this is also a FIN from the server
                    if (receivedPacket.fin) {
                        //this is a FIN-ACK, send ACK for the server's FIN
                        ackNum = receivedPacket.seqNum + 1;  //FIN consumes one sequence number
                        TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, System.nanoTime(), true, false, false, new byte[0]);
                        sendPacket(ackPacket);
                        closed = true;
                    } else {
                        //just an ACK, wait for FIN from server
                        continue;
                    }
                }
                //if we got a separate FIN from the server
                else if (receivedPacket.fin) {
                    //send ACK for the FIN
                    ackNum = receivedPacket.seqNum + 1;
                    TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, System.nanoTime(), true, false, false, new byte[0]);
                    sendPacket(ackPacket);
                    closed = true;
                }
            } catch (SocketTimeoutException e) {
                //retransmit FIN
                System.out.println("Timeout, retransmitting FIN");
                attempts++;
                
                // Create a new FIN packet with updated timestamp
                finPacket = new TCPPacket(seqNum - 1, ackNum, System.nanoTime(), false, false, true, new byte[0]);
            }
        }
        
        if (closed) {
            System.out.println("Connection closed");
        } else {
            System.out.println("WARNING: CHECK IF CONNECTION CLOSED");
        }
    }
    
    //update RTT estimates
    private void updateRTT(TCPPacket packet) {
        if (packet == null) return;
        
        long sampleRTT = (System.nanoTime() - packet.timestamp) / 1_000_000; // Convert to ms
        
        if (estimatedRTT == 0) {
            estimatedRTT = sampleRTT;
            devRTT = sampleRTT / 2;
        } else {
            // EWMA (Exponentially Weighted Moving Average)
            double oldEstimatedRTT = estimatedRTT;
            estimatedRTT = ALPHA * estimatedRTT + (1 - ALPHA) * sampleRTT;
            devRTT = BETA * devRTT + (1 - BETA) * Math.abs(sampleRTT - oldEstimatedRTT);
        }
        
        timeout = (int)(estimatedRTT + 4 * devRTT);
        
        if (timeout < 100) timeout = 100;  // min 100ms
        if (timeout > 60000) timeout = 60000;  // max 60 seconds
        
        System.out.println("RTT updated - sample: " + sampleRTT + "ms, estimated: " + 
                           (int)estimatedRTT + "ms, timeout: " + timeout + "ms");
    }
    
    //log packet
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
    
    //print statistics
    private void printStatistics() {
        System.out.println("\nTransfer Statistics:");
        System.out.println("Amount of Data transferred: " + dataSent + " bytes");
        System.out.println("Number of packets sent: " + packetsSent);
        System.out.println("Number of packets received: " + packetsReceived);
        System.out.println("Number of out-of-sequence packets discarded: " + outOfSequenceDiscarded);
        System.out.println("Number of packets discarded due to incorrect checksum: " + checksumErrors);
        System.out.println("Number of retransmissions: " + retransmissions);
        System.out.println("Number of duplicate acknowledgements: " + duplicateAcks);
    }
}
