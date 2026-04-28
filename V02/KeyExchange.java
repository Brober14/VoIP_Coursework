package V02;

public final class KeyExchange {

    public static final int PRIME = 97;
    public static final int GENERATOR = 5;

    private KeyExchange() {}

    public static int modExp(int base, int exp, int mod) {
        int result = 1;
        base = base % mod;

        while (exp > 0) {
            if ((exp & 1) == 1) {
                result = (result * base) % mod;
            }
            exp >>= 1;
            base = (base * base) % mod;
        }
        return result;
    }

    public static int generatePublicKey(int mySecret) {
        return modExp(GENERATOR, mySecret, PRIME);
    }

    public static int calculateSharedKey(int mySecret, int partnerPublic) {
        return modExp(partnerPublic, mySecret, PRIME);
    }

    public static int deriveEncryptionKey(int sharedSecret) {
        return 0x5A5A0000 ^ (sharedSecret * 1103);
    }

    public static short deriveAuthKey(int sharedSecret) {
        return (short) (0x4000 ^ (sharedSecret * 73));
    }
} 