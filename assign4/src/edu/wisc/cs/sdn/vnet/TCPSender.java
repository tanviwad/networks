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
    private static final int timeout = 5000;
    private Map<Integer, TCPPacket> unackedPackets = new HashMap<>(); //retransmissions
    private static final int MAX_RETRANSMISSIONS = 16;

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
        short checksum;
        
        public TCPPacket(int seqNum, int ackNum, byte flags, byte[] data) {
            this.seqNum = seqNum;
            this.ackNum = ackNum;
            this.flags = flags;
            this.data = data;
            this.timestamp = System.nanoTime();
            this.retransmits = 0;
            this.checksum = 0;
        }
        
        //serialize the packet into a byte array
        public byte[] serialize() {
            int packetLength = 24; // Header size
            if (data != null) {
                packetLength += data.length;
            }
            
            byte[] packetData = new byte[packetLength];
            ByteBuffer bb = ByteBuffer.wrap(packetData);
            
            // Fill header fields
            bb.putInt(seqNum);
            bb.putInt(ackNum);
            bb.putLong(timestamp);
            
            // Length and flags (length in upper 29 bits, flags in lower 3 bits)
            int dataLength = (data != null) ? data.length : 0;
            int lengthAndFlags = (dataLength << 3) | (flags & 0x7);
            bb.putInt(lengthAndFlags);
            
            // Placeholder for checksum (will be filled in later)
            bb.putShort((short)0);
            
            // Zero padding for the remaining 2 bytes
            bb.putShort((short)0);
            
            // Add data if any
            if (data != null && data.length > 0) {
                bb.put(data);
            }
            
            // Calculate checksum
            this.checksum = calculateChecksum(packetData);
            
            // Update checksum in the packet
            bb.putShort(20, this.checksum);
            
            return packetData;
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
        byte[] buffer = new byte[mtu - 24]; // header space
        int bytesRead;
        
        //send data
        while ((bytesRead = fileIn.read(buffer)) > 0) {
            byte[] data = new byte[bytesRead]; //copy it for buffer reuse
            System.arraycopy(buffer, 0, data, 0, bytesRead);
            
            //wait until window allows sending
            while (nextSeqNum - baseSeqNum >= cwnd) {
                if (!receiveAcks()) {
                    //check for retransmissions if no ACKs received
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

    private boolean establishConnection() throws IOException{
        System.out.println("Establishing Connection...");

        //syn
        TCPPacket synPacket = new TCPPacket(seqNum, 0, SYN_FLAG, null);
        sendPacket(synPacket);
        seqNum++;
        nextSeqNum = seqNum;

        //syn + ack --> wait for server to send
        int tries = 0;
        boolean connect = false;
        
        while (!connect && tries < MAX_RETRANSMISSIONS) {
            try {
                byte[] receiveBuffer = new byte[mtu + 24];
                DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(udpPacket);
                packetsReceived++;
                
                //get data from packets to update seq and ack number
                ByteBuffer buf = ByteBuffer.wrap(udpPacket.getData(), 0, udpPacket.getLength());
                int rcvSeqNum = buf.getInt();
                int rcvAckNum = buf.getInt();
                long timestamp = buf.getLong();
                int lengthAndFlags = buf.getInt();
                short checksum = buf.getShort();

                //verify checksum
                buf.position(buf.position() - 2);  // Move back to checksum position
                buf.putShort((short)0);  // Set checksum to 0 for calculation
                byte[] packetData = new byte[udpPacket.getLength()];
                System.arraycopy(udpPacket.getData(), 0, packetData, 0, udpPacket.getLength());
                short calculatedChecksum = calculateChecksum(packetData);
                
                if (calculatedChecksum != checksum) {
                    System.out.println("Checksum error in SYN-ACK");
                    checksumErrors++;
                    continue;
                }

                //extract flags
                byte flags = (byte)(lengthAndFlags & 0x7);
                
                //log packet
                logPacket("rcv", flags, rcvSeqNum, (lengthAndFlags >>> 3), rcvAckNum);
                
                //check if SYN-ACK
                if ((flags & (SYN_FLAG | ACK_FLAG)) == (SYN_FLAG | ACK_FLAG) && rcvAckNum == seqNum) {
                    ackNum = rcvSeqNum + 1;  // SYN consumes one sequence number
                    
                    //send ACK
                    TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, ACK_FLAG, null);
                    sendPacket(ackPacket);
                    
                    connect = true;
                    System.out.println("Connection established");
                }
            } catch (SocketTimeoutException e) {
                //Retransmit SYN
                System.out.println("Timeout, retransmitting SYN");
                TCPPacket synPacket2 = new TCPPacket(seqNum - 1, 0, SYN_FLAG, null);
                sendPacket(synPacket2);
                retransmissions++;
                tries++;
            }
        }
        
        return connect;
    }
    
    //send data packet and add to unacked packets
    private void sendDataPacket(byte[] data) throws IOException {
        TCPPacket packet = new TCPPacket(nextSeqNum, ackNum, ACK_FLAG, data);
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
        //update timestamp
        packet.timestamp = System.nanoTime();
        
        //serialize packet
        byte[] packetData = packet.serialize();
        
        //create UDP packet
        DatagramPacket udpPacket = new DatagramPacket(
                packetData, packetData.length, serverAddr, serverPort);
        
        //send packet
        socket.send(udpPacket);
        packetsSent++;
        
        //log packet
        logPacket("snd", packet.flags, packet.seqNum, 
                 (packet.data != null) ? packet.data.length : 0, packet.ackNum);
    }
    
    //receive and process ACKs
    private boolean receiveAcks() throws IOException {
        try {
            //try to receive an ACK
            byte[] receiveBuffer = new byte[mtu + 24];
            DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(udpPacket);
            packetsReceived++;
            
            //parse packet
            ByteBuffer buf = ByteBuffer.wrap(udpPacket.getData(), 0, udpPacket.getLength());
            int rcvSeqNum = buf.getInt();
            int rcvAckNum = buf.getInt();
            long timestamp = buf.getLong();
            int lengthAndFlags = buf.getInt();
            short checksum = buf.getShort();
            
            //verify checksum
            buf.position(buf.position() - 2);  //move back to checksum position
            buf.putShort((short)0);  //set checksum to 0 for calculation
            byte[] packetData = new byte[udpPacket.getLength()];
            System.arraycopy(udpPacket.getData(), 0, packetData, 0, udpPacket.getLength());
            short calculatedChecksum = calculateChecksum(packetData);
            
            if (calculatedChecksum != checksum) {
                System.out.println("Checksum error in ACK");
                checksumErrors++;
                return true;  //we did receive a packet, even if it had errors
            }
            
            //extract flags
            byte flags = (byte)(lengthAndFlags & 0x7);
            int dataLength = lengthAndFlags >>> 3;
            
            //log packet
            logPacket("rcv", flags, rcvSeqNum, dataLength, rcvAckNum);
            
            //process ACK
            if ((flags & ACK_FLAG) != 0) {
                if (rcvAckNum > baseSeqNum) { //new ack so update the RTT
                    updateRTT(timestamp);
                    
                    //remove acknowledged packets
                    for (int seq = baseSeqNum; seq < rcvAckNum; ) {
                        TCPPacket packet = unackedPackets.get(seq);
                        if (packet != null) {
                            unackedPackets.remove(seq);
                            seq += (packet.data != null) ? packet.data.length : 1;
                        } else {
                            seq++;
                        }
                    }
                    
                    //update base sequence number
                    baseSeqNum = rcvAckNum;
                    
                    //reset duplicate ACK counter
                    duplicateAcks = 0;
                } else if (rcvAckNum == baseSeqNum) {
                    //dup ACK
                    duplicateAcks++;
                    
                    //fast retransmit after 3 duplicate ACKs
                    if (duplicateAcks >= 3) {
                        TCPPacket lostPacket = unackedPackets.get(baseSeqNum); 
                        if (lostPacket != null) {//retransmit
                            System.out.println("Fast retransmit triggered by 3 duplicate ACKs");
                            sendPacket(lostPacket);
                            retransmissions++;
                            lostPacket.retransmits++;
                            
                            //reset duplicate ACK counter
                            duplicateAcks = 0;
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
                    sendPacket(packet);
                    retransmissions++;
                    packet.retransmits++;
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
        TCPPacket finPacket = new TCPPacket(seqNum, ackNum, FIN_FLAG, null);
        
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
                
                //parse packet
                ByteBuffer buf = ByteBuffer.wrap(udpPacket.getData(), 0, udpPacket.getLength());
                int rcvSeqNum = buf.getInt();
                int rcvAckNum = buf.getInt();
                long timestamp = buf.getLong();
                int lengthAndFlags = buf.getInt();
                short checksum = buf.getShort();
                
                //verify checksum
                buf.position(buf.position() - 2);
                buf.putShort((short)0);
                byte[] packetData = new byte[udpPacket.getLength()];
                System.arraycopy(udpPacket.getData(), 0, packetData, 0, udpPacket.getLength());
                short calculatedChecksum = calculateChecksum(packetData);
                
                if (calculatedChecksum != checksum) {
                    System.out.println("Checksum error in FIN-ACK");
                    checksumErrors++;
                    continue;
                }
                
                //extract flags
                byte flags = (byte)(lengthAndFlags & 0x7);
                
                //log packet
                logPacket("rcv", flags, rcvSeqNum, (lengthAndFlags >>> 3), rcvAckNum);
                
                //check for ACK of our FIN
                if ((flags & ACK_FLAG) != 0 && rcvAckNum == seqNum) {
                    //got ACK for our FIN
                    
                    //check if this is also a FIN from the server
                    if ((flags & FIN_FLAG) != 0) {
                        //this is a FIN-ACK, send ACK for the server's FIN
                        ackNum = rcvSeqNum + 1;  //FIN consumes one sequence number
                        TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, ACK_FLAG, null);
                        sendPacket(ackPacket);
                        closed = true;
                    } else {
                        //just an ACK, wait for FIN from server
                        continue;
                    }
                }
                //if we got a separate FIN from the server
                else if ((flags & FIN_FLAG) != 0) {
                    //send ACK for the FIN
                    ackNum = rcvSeqNum + 1;
                    TCPPacket ackPacket = new TCPPacket(seqNum, ackNum, ACK_FLAG, null);
                    sendPacket(ackPacket);
                    closed = true;
                }
            } catch (SocketTimeoutException e) {
                //retransmit FIN
                System.out.println("Timeout, retransmitting FIN");
                attempts++;
            }
        }
        
        if (closed) {
            System.out.println("Connection closed");
        } else {
            System.out.println("Warning: Connection may not have closed properly");
        }
    }
    
    //calculate one's complement checksum
    private short calculateChecksum(byte[] data) {
        //ensure even length
        int length = data.length;
        if (length % 2 != 0) {
            byte[] paddedData = new byte[length + 1];
            System.arraycopy(data, 0, paddedData, 0, length);
            data = paddedData;
            length++;
        }
        
        //calculate sum
        int sum = 0;
        for (int i = 0; i < length; i += 2) {
            int word = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += word;
            //add carry
            if ((sum & 0xFFFF0000) > 0) {
                sum = (sum & 0xFFFF) + 1;
            }
        }
        
        //one's complement
        return (short) (~sum & 0xFFFF);
    }
    
    //update RTT estimates
    private void updateRTT(long timestamp) {
        //TODO: Implement RTT estimation using the EWMA method from the assignment
    }
    
    //log packet
    private void logPacket(String action, byte flags, int seqNum, int dataLength, int ackNum) {
        StringBuilder flagStr = new StringBuilder();
        
        if ((flags & SYN_FLAG) != 0) flagStr.append("S ");
        else flagStr.append("- ");
        
        if ((flags & ACK_FLAG) != 0) flagStr.append("A ");
        else flagStr.append("- ");
        
        if ((flags & FIN_FLAG) != 0) flagStr.append("F ");
        else flagStr.append("- ");
        
        if (dataLength > 0) flagStr.append("D");
        else flagStr.append("-");
        
        System.out.printf("%s %.3f %s %d %d %d\n", 
                          action, 
                          System.currentTimeMillis() / 1000.0, 
                          flagStr.toString(), 
                          seqNum, 
                          dataLength, 
                          ackNum);
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
