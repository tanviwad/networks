public class TCPPacket {
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