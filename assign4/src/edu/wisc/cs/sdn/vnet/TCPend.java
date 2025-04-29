package edu.wisc.cs.sdn.vnet;

import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.sql.DriverAction;
import java.util.*;
public class TCPend {


    public static void server(int sPort, int ip, int dPort, String filename, int mtu, int sws) {
        try{
            DatagramSocket socket = new DatagramSocket(sPort);
            FileOutputStream fout = new FileOutputStream(filename);

            //udp socker, file to write received data, buffer for packets
            //extra 24 is for the seq#, ack#, timestamp, length, flags and for the checksum
            byte[] receiveBuffer = new byte[mtu + 24]; 


            int expectedSeqNum = 0;
            boolean established = false;
            boolean connClose = false;
            //stats to display at end of testing
            int dataBytesReceived = 0;
            int packetsReveived = 0;
            int packetsSent = 0;
            int outOfOrder = 0;
            int checksumErrors = 0;
            int dupAcks = 0; //for fast recovery and fast re transmit

            System.out.println("Server listening on port " + sPort);

            InetAddress clientAddr = null;
            int clientPort = dPort;

            while(!connClose){
                DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(udpPacket);
                if(clientAddr == null){
                    clientAddr = udpPacket.getAddress();
                    clientPort = udpPacket.getPort();
                
                }

            }
        }
    }

    public static void client(int port, int mtu, int sws, String filename) { 

    }

    public static void main(String [] args) {

         final int DEFAULT_PORT = 8888;

        int hostPort = DEFAULT_PORT;
        int ip = -1;
        int dPort = -1;
        String filename = null;
        int mtu = -1;
        int sws = -1;


        for(int i = 0; i < args.length; i++) { 

            switch(args[i]) {
                case "-p":
                    hostPort = Integer.parseInt(args[++i]);
                    continue;
                case "-s":
                    ip = Integer.parseInt(args[++i]);
                    continue;

                case "-a":
                    dPort = Integer.parseInt(args[++i]);
                    continue;

                case "-m":
                    mtu = Integer.parseInt(args[++i]);
                    continue;
                case "-c":
                    sws = Integer.parseInt(args[++i]);
                case "-f":
                    filename = args[++i];
                    continue;
            }
        }
    

        if(hostPort == -1 || mtu == -1 || filename.isBlank() || sws == -1) {
            System.out.println("Improper arguements please follow the correct format ");
            System.exit(-1);
        }
        

        // determine ports based on number of args


        if(args.length > 9) {
            // server side
            // prelim param check
            if(dPort == -1 || ip == -1) {
                System.out.println("Improper arguements please follow the correct format ");
                System.exit(-1);
            }
            
            server(hostPort, ip, dPort, filename, mtu, sws);
        
        }else{
            // client side
            client(hostPort, mtu, sws, filename);

        }

    }
}
