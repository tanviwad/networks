// import java.nio.ByteBuffer;

// import net.floodlightcontroller.packet.TCP;

// public class TCPPacket {
//     int seqNum;
//     int ackNum;
//     boolean ack, syn, fin;
//     byte[] data;
//     int length;
//     long timestamp;
//     int retransmits;
//     short checksum = -1;
    
//     public TCPPacket(int seqNum, int ackNum, long timestamp, boolean ack, boolean syn, boolean fin, byte[] data) {
//         this.seqNum = seqNum;
//         this.ackNum = ackNum;
//         this.ack = ack;
//         this.syn = syn;
//         this.fin = fin;
//         this.data = data;
//         this.length = data.length + 24; // 24 bytes for header
//         this.timestamp = timestamp;
//         this.retransmits = 0;
//         byte [] temp = toBytes();
//         this.checksum = computeChecksum(temp);
//     }


//     public byte[] toBytes() { 

//         ByteBuffer buffer = ByteBuffer.allocate(24 + (data != null ? data.length : 0));
//         buffer.putInt(this.seqNum);
//         buffer.putInt(this.ackNum);
//         buffer.putLong(this.timestamp);
//         int length = (data != null ? data.length : 0);
//         length = length << 3; // Shift left by 3 to account for flags
//         int flags = 0;
//         if (this.ack) flags |= 1; // ACK flag
//         if (this.syn) flags |= 2; // SYN flag  
//         if (this.fin) flags |= 4; // FIN flag
//         buffer.putInt(length | flags);
//         buffer.putShort((short)0);
//         if(this.checksum != -1) {
//             buffer.putShort(this.checksum);
//         } else {
//             buffer.putShort((short)0); // Placeholder for checksum if not set
//         }
//         if (data != null) {
//             buffer.put(data);
//         }   
       
//         return buffer.array();

//     }

//     public TCPPacket(byte [] pkt) {
//         if (pkt.length < 24) {
//             throw new IllegalArgumentException("Invalid Packet");
//         } 
//         ByteBuffer buffer = ByteBuffer.wrap(pkt);
//         this.seqNum = buffer.getInt();
//         this.ackNum = buffer.getInt();
//         this.timestamp = buffer.getLong();
//         int length = buffer.getInt();
//         int flags = length & 0x7; // Extract the flags from the length
//         length >>= 3; // Shift right to get the actual length
//         this.ack = (flags & 1) != 0; // ACK flag
//         this.syn = (flags & 2) != 0; // SYN flag
//         this.fin = (flags & 4) != 0; // FIN flag
//         buffer.getShort();
//         this.checksum = buffer.getShort(); 
//         if (length > 0) {
//             this.data = new byte[length];
//             buffer.get(this.data);
//         } else {
//             this.data = null;
//         }                   
//     }


//     public short computeChecksum(byte[] pkt) { 

//         int total = 0;
//         for(int i = 0; i < pkt.length; i += 2) {
//             if (i + 1 < pkt.length) {
//                 total += ((pkt[i] & 0xFF) << 8 | (pkt[i + 1] & 0xFF));
//             } else {
//                 total += ((pkt[i] & 0xFF) << 8);
//             }
//             if ((total & 0xFFFF0000) != 0) { // if sum has overflow into higher bits
//                 total = (total & 0xFFFF) + 1;
//             }
//         }

//         return (short) ~total; // One's complement

//     }
// }

import java.nio.ByteBuffer;

public class TCPPacket {
    public int seqNum;
    public int ackNum;
    public boolean ack, syn, fin;
    public byte[] data;
    public int length;
    public long timestamp;
    public int retransmits;
    public short checksum = -1;
    
    public TCPPacket(int seqNum, int ackNum, long timestamp, boolean ack, boolean syn, boolean fin, byte[] data) {
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.ack = ack;
        this.syn = syn;
        this.fin = fin;
        this.data = data;
        this.length = data != null ? data.length + 24 : 24; // 24 bytes for header
        this.timestamp = timestamp;
        this.retransmits = 0;
        byte[] temp = toBytes();
        this.checksum = computeChecksum(temp);
    }

    public byte[] toBytes() { 
        ByteBuffer buffer = ByteBuffer.allocate(24 + (data != null ? data.length : 0));
        buffer.putInt(this.seqNum);
        buffer.putInt(this.ackNum);
        buffer.putLong(this.timestamp);
        int length = (data != null ? data.length : 0);
        length = length << 3; // Shift left by 3 to account for flags
        int flags = 0;
        if (this.ack) flags |= 1; // ACK flag
        if (this.syn) flags |= 2; // SYN flag  
        if (this.fin) flags |= 4; // FIN flag
        buffer.putInt(length | flags);
        buffer.putShort((short)0);
        if(this.checksum != -1) {
            buffer.putShort(this.checksum);
        } else {
            buffer.putShort((short)0); // Placeholder for checksum if not set
        }
        if (data != null) {
            buffer.put(data);
        }   
       
        return buffer.array();
    }

    public TCPPacket(byte[] pkt) {
        if (pkt.length < 24) {
            throw new IllegalArgumentException("Invalid Packet");
        } 
        ByteBuffer buffer = ByteBuffer.wrap(pkt);
        this.seqNum = buffer.getInt();
        this.ackNum = buffer.getInt();
        this.timestamp = buffer.getLong();
        int length = buffer.getInt();
        int flags = length & 0x7; // Extract the flags from the length
        length >>= 3; // Shift right to get the actual length
        this.ack = (flags & 1) != 0; // ACK flag
        this.syn = (flags & 2) != 0; // SYN flag
        this.fin = (flags & 4) != 0; // FIN flag
        buffer.getShort();
        this.checksum = buffer.getShort(); 
        if (length > 0) {
            this.data = new byte[length];
            buffer.get(this.data);
        } else {
            this.data = new byte[0];
        }                   
    }

    public short computeChecksum(byte[] pkt) { 
        int total = 0;
        for(int i = 0; i < pkt.length; i += 2) {
            if (i + 1 < pkt.length) {
                total += ((pkt[i] & 0xFF) << 8 | (pkt[i + 1] & 0xFF));
            } else {
                total += ((pkt[i] & 0xFF) << 8);
            }
            if ((total & 0xFFFF0000) != 0) { // if sum has overflow into higher bits
                total = (total & 0xFFFF) + 1;
            }
        }

        return (short) ~total; // One's complement
    }
}
