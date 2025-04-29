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