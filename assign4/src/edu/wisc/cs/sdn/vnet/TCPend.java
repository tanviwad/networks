package edu.wisc.cs.sdn.vnet;

import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.sql.DriverAction;
import java.io.IOException;
import java.util.*;

/*
 * 1. create tcp segment class specifying the right fields bc we only use the standard java udp socket classes
 * inside udp packets, find tcp fields that we want (tcp segment) 
 * - need to make segments, serialize segments, set and get , bit manipulation for length and flag fields, checksum
 * 2.checksum calculation on last page of assignment
 * 3. tcp connection class to initiate handshake, and for termination
 * 4.slidiing window, seq# and expected acknowledgements
 * 5.timeouts, separate timers, update RTT based on acks
 * 6/ retransmission logic, fast recovery using three dup acks
 * 7. logging and stats
 */

public class TCPend {


    public static void server(int sPort, String filename, int mtu, int sws) {
        
        // receiver

        // set up listening port on sPort

        // once packet received
        



        // try{
        //     DatagramSocket socket = new DatagramSocket(sPort);
        //     FileOutputStream fout = new FileOutputStream(filename);

        //     //udp socker, file to write received data, buffer for packets
        //     //extra 24 is for the seq#, ack#, timestamp, length, flags and for the checksum
        //     byte[] receiveBuffer = new byte[mtu + 24]; 


        //     int expectedSeqNum = 0;
        //     boolean established = false;
        //     boolean connClose = false;
        //     //stats to display at end of testing
        //     int dataBytesReceived = 0;
        //     int packetsReveived = 0;
        //     int packetsSent = 0;
        //     int outOfOrder = 0;
        //     int checksumErrors = 0;
        //     int dupAcks = 0; //for fast recovery and fast re transmit

        //     System.out.println("Server listening on port " + sPort);

        //     InetAddress clientAddr = null;
        //     int clientPort = dPort;

        //     while(!connClose){
        //         DatagramPacket udpPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        //         socket.receive(udpPacket);
        //         if(clientAddr == null){
        //             clientAddr = udpPacket.getAddress();
        //             clientPort = udpPacket.getPort();
                
        //         }
        //         //server functinality
        //     }
        //     fout.close();
        //     socket.close();
        //     //stats
        //     System.out.println("\nTransfer Statistics:");
        //     System.out.println("Amount of Data received: " + dataBytesReceived + " bytes");
        //     System.out.println("Number of packets sent: " + packetsSent);
        //     System.out.println("Number of packets received: " + packetsReceived);
        //     System.out.println("Number of out-of-sequence packets discarded: " + outOfOrder);
        //     System.out.println("Number of packets discarded due to incorrect checksum: " + checksumErrors);
        //     System.out.println("Number of duplicate acknowledgements: " + dupAcks);

        // }catch(IOException e){
        //     System.err.println("Server error" + e.getMessage());
        //     e.printStackTrace();

        // }


    }

    public static void client(int port, String serverIP, int serverPort, String filename, int mtu, int sws) {
        //TODO inditial functionality
        try {
            // Create TCPSender instance for client functionality
            TCPSender sender = new TCPSender(port, mtu, sws, serverIP, serverPort);
            
            // Send the file
            sender.sendFile(filename);
            
            System.out.println("File transfer completed");
            
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        //final int DEFAULT_PORT = 8888;

        int hostPort = -1;
        String serverIP = null;
        int serverPort = -1;
        String filename = null;
        int mtu = -1;
        int sws = -1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p":
                    hostPort = Integer.parseInt(args[++i]);
                    break;
                case "-s":
                    serverIP = args[++i];
                    break;
                case "-a":
                    serverPort = Integer.parseInt(args[++i]);
                    break;
                case "-f":
                    filename = args[++i];
                    break;
                case "-m":
                    mtu = Integer.parseInt(args[++i]);
                    break;
                case "-c":
                    sws = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }
    

        if(hostPort == -1 || mtu <= 0 || filename == null || sws <= -1) {
            System.out.println("Improper arguements please follow the correct format ");
            System.exit(-1);
        }
        
        if (serverIP != null && serverPort > 0) {
            // Client mode
            System.out.println("Running in client mode");
            
        } else {
            // Server mode
            System.out.println("Running in server mode");
            TCPServer server = new TCPServer(hostPort, mtu, sws, filename);
            try { 
                server.run();
            }catch(IOException e) {
                System.out.println("Error when trying to run server");
            }
        }

    }

    //debugging usage
    private static void printUsage() {
        System.out.println("Usage for client:");
        System.out.println("  java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");
        System.out.println("Usage for server:");
        System.out.println("  java TCPend -p <port> -f <file name> -m <mtu> -c <sws>");
        System.out.println("Where:");
        System.out.println("  port: local port number");
        System.out.println("  remote IP: the IP address of the remote peer (for client)");
        System.out.println("  remote port: the port at which the remote receiver is running (for client)");
        System.out.println("  file name: the path where the file should be read from (client) or written to (server)");
        System.out.println("  mtu: maximum transmission unit in bytes");
        System.out.println("  sws: sliding window size in number of segments");
    }
}
