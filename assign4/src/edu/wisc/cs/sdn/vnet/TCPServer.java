package edu.wisc.cs.sdn.vnet;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.lang.*;
import java.nio.ByteBuffer;



public class TCPServer {


    int port;
    int mtu;
    int sws;
    String filename;
    DatagramSocket socket;

    int ackNum = -1;
    int seqNum = -1;
    int expectedSeq = ackNum + 1;
    int expectedAck = seqNum + 1;

    public TCPServer(int port, int mtu, int sws, String filename) {
        this.port = port;
        this.mtu = mtu;
        this.sws = sws;
        this.filename = filename;
        try {
            this.socket =  new DatagramSocket(this.port);
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

    public int getFlag(byte [] p) {

        byte[] r = new byte[4];
        System.arraycopy(p, 16, r, 0, 4);
        int f = ByteBuffer.wrap(r).getInt();

        if(((f >> 2) & 1) == 1) return 2; // SYN
        if(((f >> 1) & 1) == 1) return 1; // FIN
        if(((f >> 0) & 1) == 1) return 0; // ACK

        return -1; // Data/NoFlag

    }

    public void run() throws IOException {
        while(true) { 
            byte[] p = new byte[mtu];
            DatagramPacket pack = new DatagramPacket(p, mtu);
            socket.receive(pack);


            

            // compute flags
            int flag = getFlag(p);
            // if SYN
            if(flag == 2) { 
                // compute seqNumber
                int seq = getSequenceNumber(p);
                
                // send ack with seq + 1;
                // send syn with seqNum = 0

                seqNum = 0;


            }else if(flag == 1) {
                // 

            }else if(flag == 0) {

            }else if(flag == -1) {

            }
            
    }

}