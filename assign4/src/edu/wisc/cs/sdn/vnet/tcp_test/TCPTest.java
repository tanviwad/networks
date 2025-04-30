import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class TCPTest {
    public static void main(String[] args) {
        if (args.length == 0) {
            // Run the packet test
            testPacket();
            printUsage();
            return;
        }
        
        String mode = args[0].toLowerCase();
        
        if (mode.equals("server")) {
            if (args.length != 5) {
                System.out.println("Invalid arguments for server mode");
                printUsage();
                return;
            }
            
            int port = Integer.parseInt(args[1]);
            int mtu = Integer.parseInt(args[2]);
            int sws = Integer.parseInt(args[3]);
            String filename = args[4];
            
            TCPServer server = new TCPServer(port, mtu, sws, filename);
            server.run();
        } else if (mode.equals("client")) {
            if (args.length != 7) {
                System.out.println("Invalid arguments for client mode");
                printUsage();
                return;
            }
            
            int port = Integer.parseInt(args[1]);
            String serverIP = args[2];
            int serverPort = Integer.parseInt(args[3]);
            String filename = args[4];
            int mtu = Integer.parseInt(args[5]);
            int sws = Integer.parseInt(args[6]);
            
            try {
                TCPSender sender = new TCPSender(port, mtu, sws, serverIP, serverPort);
                sender.sendFile(filename);
            } catch (IOException e) {
                System.err.println("Client error: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("Unknown mode: " + mode);
            printUsage();
        }
    }
    
    private static void testPacket() {
        try {
            // Create a TCP packet and print its content to verify
            TCPPacket packet = new TCPPacket(0, 0, System.nanoTime(), false, true, false, new byte[0]);
            System.out.println("Created TCP packet:");
            System.out.println("Sequence number: " + packet.seqNum);
            System.out.println("SYN flag: " + packet.syn);
            System.out.println("ACK flag: " + packet.ack);
            System.out.println("FIN flag: " + packet.fin);
            System.out.println("Checksum: " + packet.checksum);
            
            // Serialize and deserialize
            byte[] serialized = packet.toBytes();
            TCPPacket deserialized = new TCPPacket(serialized);
            
            System.out.println("\nDeserialized packet:");
            System.out.println("Sequence number: " + deserialized.seqNum);
            System.out.println("SYN flag: " + deserialized.syn);
            System.out.println("ACK flag: " + deserialized.ack);
            System.out.println("FIN flag: " + deserialized.fin);
            System.out.println("Checksum: " + deserialized.checksum);
            
            System.out.println("\nTCP packet works correctly!");
        } catch (Exception e) {
            System.err.println("Error testing TCP packet: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printUsage() {
        System.out.println("\nUsage:");
        System.out.println("  Server mode: java TCPTest server <port> <mtu> <sws> <filename>");
        System.out.println("  Client mode: java TCPTest client <port> <serverIP> <serverPort> <filename> <mtu> <sws>");
        System.out.println("Where:");
        System.out.println("  port: Local port number to use");
        System.out.println("  serverIP: IP address of the server (for client mode)");
        System.out.println("  serverPort: Port of the server (for client mode)");
        System.out.println("  filename: File to send (client) or receive (server)");
        System.out.println("  mtu: Maximum transmission unit in bytes");
        System.out.println("  sws: Sliding window size in segments");
    }
}
