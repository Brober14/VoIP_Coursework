package V02;

public final class SessionState {
    public static volatile boolean established = false;

    public static volatile int sharedSecret = 0;
    public static volatile int encryptionKey = 0;
    public static volatile short authKey = 0;

    private SessionState() {}
} 