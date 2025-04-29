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
    DatagramSocket Listensocket;
    DatagramSocket SendSocket;  
    
    //
    int seqNum = 0;
    int nextAck = 0;
    int prevAck = 0;
    boolean connected = false;
    boolean complete = false;


    InetAddress clientAddy;
    int clientPort;


    public TCPServer(int port, int mtu, int sws, String filename) {
        this.port = port;
        this.mtu = mtu;
        this.sws = sws;
        this.filename = filename;
        try {
            this.Listensocket =  new DatagramSocket(this.port);
            this.SendSocket = new DatagramSocket();
        }catch(Exception e) {
            System.out.println("Unable to bind socket");
        }
        try {
            init();    
        } catch (IOException e) {
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

    public void init() throws IOException {
        while(true) { 
            byte[] p = new byte[mtu];
            DatagramPacket pack = new DatagramPacket(p, mtu);
            Listensocket.receive(pack);
            

            // compute flags
            int flag = getFlag(p);
            
            // if SYN
            if(flag == 2) { 
                // compute seqNumber
                int seq = getSequenceNumber(p);

            }else if(flag == 1) { 

            }else if(flag == 0) {

            }else if(flag == -1) {

            }
            
    }

}

class ServerListen extends Thread { 

    public void run() { 
        while(true) { 

        }
    }
}

public class ServerSend extends Thread {


}
