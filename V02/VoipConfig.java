package V02;


public final class VoipConfig {

    // Constants related to packet structure and timing.

    // Audio block size in bytes
    public static final int BODY_BYTES = 512;

    // Packet header size
    public static final int HEADER_BYTES = 12;

    // Total packet size in bytes
    public static final int PACKET_SIZE_BYTES = HEADER_BYTES + BODY_BYTES; 

    // Audio frame duration in milliseconds
    public static final int FRAME_MS = 32;

    //Playout buffer size
    public static final int MAX_BUFFER_FRAMES = 50;

    //Skip threshold in frames
    public static final int SKIP_AHEAD_THRESHOLD = 6;

    //Startup buffer size in frames
    public static final int STARTUP_BUFFER_FRAMES = 5;

    //Buffer resync threshold in frames
    public static final int RESYNC_THRESHOLD = 10;

    // Default port for receiver bind and sender destination
    public static final int DEFAULT_PORT = 55555;

    // Default peer IP address (localhost) and port
    public static final String PEER_IP = "10.10.4.5";

    // Chosen socket type
    public static final int SOCKET_TYPE = 3;

    // Socket timeout in milliseconds (for receiver)
    public static final int SOCKET_TIMEOUT = 500;

    //Handshake timeout and retry settings
    public static final int HANDSHAKE_TIMEOUT_MS = 1000;

    public static final int HANDSHAKE_MAX_RETRIES = 3;

}
