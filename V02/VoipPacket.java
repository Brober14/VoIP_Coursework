package V02;

import java.nio.ByteBuffer;

public class VoipPacket {

    // Encryption Function
    


    // Packet encoder/decoder functions

    public static final class ParsedPacket {
        public final short authKey;
        public final int seq;
        public final int timestamp;
        public final short reserved;
        public final byte[] payload; // 512 bytes

        public ParsedPacket(short authKey, int seq, int timestamp, short reserved, byte[] payload) {
            this.authKey = authKey;
            this.seq = seq;
            this.timestamp = timestamp;
            this.reserved = reserved;
            this.payload = payload;
        }
    }

    public static byte[] encodePacket(short authKey, int seqNum, int timestamp, byte[] audioBlock) {

        ByteBuffer packet = ByteBuffer.allocate(VoipConfig.PACKET_SIZE_BYTES);
        packet.putShort(authKey); 
        packet.putInt(seqNum);    
        packet.putInt(timestamp); 
        packet.putShort((short)0); // 2 bytes reserved
        packet.put(audioBlock);   
        return packet.array();

    }

    public static ParsedPacket decodePacket(byte[] packetData) {
        if (packetData == null || packetData.length != VoipConfig.PACKET_SIZE_BYTES) {
            throw new IllegalArgumentException("Invalid packet data");
        }

        ByteBuffer packet = ByteBuffer.wrap(packetData);
        short authKey = packet.getShort();
        int seq = packet.getInt();
        int timestamp = packet.getInt();
        short reserved = packet.getShort();
        byte[] payload = new byte[VoipConfig.BODY_BYTES];
        packet.get(payload);
        return new ParsedPacket(authKey, seq, timestamp, reserved, payload);
    }
}
