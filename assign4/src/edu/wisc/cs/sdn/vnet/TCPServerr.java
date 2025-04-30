package edu.wisc.cs.sdn.vnet;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;

import javax.xml.crypto.Data;

import java.lang.*;
import java.nio.ByteBuffer;




public class TCPServer {

    public static final int HEADER_SIZE = 24;
    public static final int SYN = 0x4; 
    public static final int FIN = 0x2; 
    public static final int ACK = 0x1; 


    int port;
    int mtu;
    int sws;
    String filename;
    DatagramSocket socket;

    int ackNum;
    int expectedSeq;

    //packets buffer
    Map<Integer, DatagramPacket> packetsBuffer;
    FileOutputStream fout;
    Map<Integer, byte[]> receieverBuffer;

    InetAddress clientAddr;
    int clientPort;

    int totalBytes;
    int totalPackets;
    int outOfOrder;
    int corrupted;
    int doubleACK;




    public TCPServer(int port, int mtu, int sws, String filename) {
        this.port = port;
        this.mtu = mtu;
        this.sws = sws;
        this.filename = filename;
        this.packetsBuffer = new HashMap<>();
        this.receieverBuffer = new HashMap<>();
        this.fout = new FileOutputStream(filename);
        this.ackNum = 0;
        this.expectedSeq = 0;
        this.totalBytes = 0;
        this.totalPackets = 0;
        this.outOfOrder = 0;
        this.corrupted = 0; 
        this.doubleACK = 0;

        try {
            this.socket =  new DatagramSocket(this.port);
            this.socket.setSoTimeout(200); // poll packets for receive
        }catch(Exception e) {
            System.out.println("Unable to bind socket");
        }
        

    }

    public int getSequenceNumber(byte [] p ) { 

        // first 4 bytes
        byte[] BSN = new byte[4];
        System.arraycopy(p, 0, BSN, 0, 4);
        int seq = ByteBuffer.wrap(BSN).getInt();

        return seq;
    }

    public int[] getFlag(byte [] p) {

        byte[] r = new byte[4];
        System.arraycopy(p, 16, r, 0, 4);
        int f = ByteBuffer.wrap(r).getInt();

        int[] ret = new int[3];

        if(((f >> 2) & 1) == 1) ret[0] = 3; // SYN
        if(((f >> 1) & 1) == 1) ret[1] = 2; // FIN
        if(((f >> 0) & 1) == 1) ret[2] = 1;// ACK

        return ret; // Data/NoFlag

    }

    public void sendPacket(int seqNum, int ackNum, long timestamp, int[] flags, InetAddress remoteAddr, int remotePort) {
        
        //header size of 24 bytes

        // server only sends empty data

        // build packet
        try { 
            int header_size = 24;
            // build TCP packet
            byte[] packet = new byte[header_size];
            
            ByteBuffer temp = ByteBuffer.allocate(header_size);

            // build seq Num
            temp.putInt(seqNum);
            
            // build ack Num
            temp.putInt(ackNum);

            
            // build timestamp
            temp.putLong(timestamp);
            
            // build length
            int length = 0;
            length = length << 3;
            for(int f : flags) { 
                int mask = 1 << (f-1);
                length = length| mask;
            }
            temp.putInt(length);

            
            // build zeroes array

           temp.putInt(0);
           short checksum = calcCheckSum(temp.array());
           temp.getShort();
           temp.putShort(checksum);
           // construct datagram packet
           DatagramPacket p = new DatagramPacket(packet, packet.length);
            
            p.setAddress(remoteAddr);
            p.setPort(remotePort);
            
            this.socket.send(p);
            this.packetsBuffer.put(expectedAck, p);
            
        
        }catch(Exception e) { 
            
            e.printStackTrace();
            System.out.println("Error in sendPacket in TCPSERVER");
        
        }

    }


    public short calcCheckSum(byte[] pkt) {
        int total = 0;
    
        // Process every 16-bit (2-byte) word
        for (int i = 0; i < pkt.length - 1; i += 2) {
            int first = pkt[i] & 0xFF;
            int second = pkt[i + 1] & 0xFF;
    
            int word = (first << 8) | second;  // Combine into 16-bit word (big endian)
            total += word;
    
            // Carry around addition (wrap around carry bits)
            if ((total & 0xFFFF0000) != 0) {
                total = (total & 0xFFFF) + (total >>> 16);
            }
        }
    
        // Handle odd-length packet (last byte)
        if (pkt.length % 2 != 0) {
            int lastByte = (pkt[pkt.length - 1] & 0xFF) << 8; // Pad with zero on right
            total += lastByte;
    
            if ((total & 0xFFFF0000) != 0) {
                total = (total & 0xFFFF) + (total >>> 16);
            }
        }
    
        // Final wrap around
        while ((total & 0xFFFF0000) != 0) {
            total = (total & 0xFFFF) + (total >>> 16);
        }
    
        // One's complement
        return (short) ~total;
    }

    public short getCheckSum(DatagramPacket pack) { 

        short checksum = ByteBuffer.wrap(pack.getData()).getShort(22);
        return checksum;
    }
    // check checksum
    public boolean check(DatagramPacket pack) { 

        ByteBuffer p = ByteBuffer.wrap(pack.getData());
    
        //zero out checksum and calculate
        short checksum = p.getShort(22);
        p.putShort(22, (short)0);
        short calc = calcCheckSum(p.array());
        if(checksum == calc) { 
            p.putShort(22, checksum);
            return true;
        }

        return false;
    }


    public void beginHandshake() {
        
        System.out.println("Beginning TCP Handshake on server side");

        try{
            // listen for SYN packet
            while(true) { 

            }
        }catch ( Exception e){
            System.err.println("Error with getting syn packet");
        }

    }

    public void readData() { 

    }

    public void closeConnection() { 

    }

    public void run() throws IOException {

        System.out.println("Server listening on port: " + this.port);

        try{
            
            beginHandshake();
            readData();
            closeConnection();
        }catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error in TCPServer run method");
        }   
        // while(true) { 
        //     byte[] p = new byte[mtu];
        //     DatagramPacket pack = new DatagramPacket(p, mtu);
        //     socket.receive(pack);
        //     // verify checksum of packet
        //     if(!check(pack)) { 
        //         // if checksum
        //         continue;
        //     }
        //     long timestamp = System.nanoTime();
        //     // compute flags
        //     int[] flag = getFlag(p);
        //     // if SYN
        //     if(flag[0] == 3) { 
        //         // compute seqNumber
        //         int seq = getSequenceNumber(p);
                
        //         // send syn, ack
        //         ackNum = seq + 1;

        //         // get InetAddres and Port
        //         InetAddress remoteAddr = pack.getAddress();
        //         int remotePort = pack.getPort();

        //         int [] flags = {3, 2, 1};

        //         // send syn and wait for ack, 5 second timer, resend this packet.
        //         sendPacket(seqNum, ackNum, timestamp, flags, remoteAddr, remotePort);
        //     }else if(flag[1] == 2) {
        //         // FIN

        //         // send back an ack

        //         // send ack with a different seqyence number based on FIN seq #


        //     }else if(flag[2] == 1 ) {
        //         // ACK

        //         // get the ACK number
        //         ByteBuffer buf = ByteBuffer.wrap(pack.getData());

        //         // if ACK # is expecyed remove entry from map
        //         if ()

        //     }else {
        //         // improper packet

        //     }
            
        // }

    }
}