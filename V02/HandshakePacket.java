package V02;

import java.nio.ByteBuffer;

public final class HandshakePacket {
    public static final byte HELLO = 1;
    public static final byte HELLO_REPLY = 2;
    public static final int SIZE = 1 + 4; // type + publicKey

    private HandshakePacket() {}

    public static byte[] encode(byte type, int publicKey) {
        ByteBuffer buffer = ByteBuffer.allocate(SIZE);
        buffer.put(type);
        buffer.putInt(publicKey);
        return buffer.array();
    }

    public static ParsedHandshake decode(byte[] data, int length) {
        if (length != SIZE) {
            throw new IllegalArgumentException("Invalid handshake packet size");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        byte type = buffer.get();
        int publicKey = buffer.getInt();
        return new ParsedHandshake(type, publicKey);
    }

    public static final class ParsedHandshake {
        public final byte type;
        public final int publicKey;

        public ParsedHandshake(byte type, int publicKey) {
            this.type = type;
            this.publicKey = publicKey;
        }
    }
}