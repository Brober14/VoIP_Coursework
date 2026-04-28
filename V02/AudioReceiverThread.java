package V02;
/*
 * TextReceiver.java
*/

/**
 *
 * @author  abj
 */
import java.net.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.TreeMap;
import java.io.*;

//JAR package Import
import CMPC3M06.AudioPlayer;
import V02.VoipPacket.ParsedPacket;
import uk.ac.uea.cmp.voip.DatagramSocket2;
import uk.ac.uea.cmp.voip.DatagramSocket3;


public class AudioReceiverThread implements Runnable {
    
    //Statistics variables
    private int expectedSeq = 0;
    private int receivedPackets = 0;
    private int lostPackets = 0;
    private int duplicatePackets = 0;
    private int lateDiscards = 0;
    //private int outOfOrderPackets = 0;
    
    private long previousDelay = -1;
    private long totalJitter = 0;

    private long totalPlayedFrames = 0;
    private long totalMissingFrames = 0;
    private int currentMissingStreak = 0;
    private int maxMissingStreak = 0;
    private int burstCount = 0;
    private int sumBurstLen = 0;
    private double avgBurstLen = 0;
    private double plcRate = 0;

    
    //Socket variables
    private int socketType;
    static DatagramSocket receiving_socket;

    public void start() {
        this.socketType = VoipConfig.SOCKET_TYPE;
        Thread thread = new Thread(this);
	thread.start();
    }

    //Handshake helper function
    private void sendHandshakeReply(byte[] data, InetAddress ip, int port) throws IOException {
        DatagramPacket packet1 = new DatagramPacket(data, data.length, ip, port);
        receiving_socket.send(packet1);

        if (socketType == 3) {
            DatagramPacket packet2 = new DatagramPacket(data, data.length, ip, port);
            receiving_socket.send(packet2);
        }
    }

    //Handshake handler function
    private Integer mySecret = null;
    private Integer myPublic = null;

    private void handleHandshake(DatagramPacket packet) throws IOException {
        HandshakePacket.ParsedHandshake hs =
            HandshakePacket.decode(packet.getData(), packet.getLength());

        if (hs.type == HandshakePacket.HELLO) {

            if (SessionState.established) {
                System.out.println("Duplicate HELLO received after session established. Re-sending HELLO_REPLY.");
                byte[] reply = HandshakePacket.encode(HandshakePacket.HELLO_REPLY, myPublic);
                sendHandshakeReply(reply, packet.getAddress(), packet.getPort());
                return;
            }

            if (mySecret == null) {
                mySecret = 2 + new SecureRandom().nextInt(90);
                myPublic = KeyExchange.generatePublicKey(mySecret);
            }

            int shared = KeyExchange.calculateSharedKey(mySecret, hs.publicKey);

            SessionState.sharedSecret = shared;
            SessionState.encryptionKey = KeyExchange.deriveEncryptionKey(shared);
            SessionState.authKey = KeyExchange.deriveAuthKey(shared);
            SessionState.established = true;

            byte[] reply = HandshakePacket.encode(HandshakePacket.HELLO_REPLY, myPublic);
            sendHandshakeReply(reply, packet.getAddress(), packet.getPort());

            System.out.println("Handshake complete on receiver. Shared = " + shared);
        } else {
            System.out.println("Unexpected handshake packet type received. Ignoring.");
        }
    }



    /*
     * DECRYPTION FUNCTION
     * This just reverses the encryption steps exactly.
     */
    public static byte[] decrypt(byte[] scrambledData, int key) {
        byte[] output = new byte[scrambledData.length];
        int byteKey = key & 0xFF;

        for (int i = 0; i < scrambledData.length; i++) {
            int b = scrambledData[i] & 0xFF;
            int unrotated = ((b >>> 3) | (b << 5)) & 0xFF;
            int original = (unrotated ^ byteKey) & 0xFF;
            output[i] = (byte) original;
        }

        return output;
    }


    //Audio buffering
    private Object bufferLock = new Object();
    TreeMap<Integer, byte[]> playoutBuffer = new TreeMap<>();
    private volatile int playoutSeq = 0;
    private volatile boolean playoutSeqInitialized = false;
    private byte[] lastGoodFrame = new byte[VoipConfig.BODY_BYTES];

    public void bufferPut(int seq, byte[] audioBlock) {
        synchronized (bufferLock) {
            //Discard late packets
            if (seq < playoutSeq) {
                lateDiscards++; 
                return;
            }

            if (playoutBuffer.containsKey(seq)) {
                duplicatePackets++;
                return;
            }
            
            playoutBuffer.put(seq, audioBlock);

            while(playoutBuffer.size() > VoipConfig.MAX_BUFFER_FRAMES) {
                playoutBuffer.pollFirstEntry();
            }
        }
    }

    public byte[] bufferGet(int seq) {
        synchronized (bufferLock) {
            return playoutBuffer.remove(seq);
        }
    }

    public int bufferSize() {
        synchronized (bufferLock) {
            return playoutBuffer.size();
        }
    }

    //Player loop
    private boolean playerRunning = true;
    private boolean inBurst = false;
    private void playerLoop(AudioPlayer player) {
        while(playerRunning) {
            if(bufferSize() >= VoipConfig.STARTUP_BUFFER_FRAMES && playoutSeqInitialized) break;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        long nextPlayTime = System.nanoTime();

        while(playerRunning) {
            nextPlayTime += VoipConfig.FRAME_MS * 1_000_000L;

            byte[] frame = bufferGet(playoutSeq);
            if(frame != null) {
                if(inBurst) {
                    sumBurstLen += currentMissingStreak;
                    currentMissingStreak = 0;
                    inBurst = false;
                } else {
                    currentMissingStreak = 0;
                }

                lastGoodFrame = frame;
            } else {
                totalMissingFrames++;
                currentMissingStreak++;

                if(!inBurst) {
                    burstCount++;
                    inBurst = true;
                }
                maxMissingStreak = Math.max(maxMissingStreak, currentMissingStreak);
                
                //Starvation handling logic
                if (bufferSize() == 0 && currentMissingStreak >= VoipConfig.RESYNC_THRESHOLD) {
                    while(playerRunning && bufferSize() < VoipConfig.STARTUP_BUFFER_FRAMES) {
                        try {
                            Thread.sleep(5); 
                        } catch (InterruptedException e) { 
                            Thread.currentThread().interrupt(); 
                            return; 
                        }
                    }

                    Integer restartSeq = null;
                    synchronized (bufferLock) {
                        if (!playoutBuffer.isEmpty()) restartSeq = playoutBuffer.firstKey();
                    }

                    if (restartSeq != null) {
                        sumBurstLen += currentMissingStreak;
                        currentMissingStreak = 0;
                        inBurst = false;

                        playoutSeq = restartSeq;
                        frame = bufferGet(playoutSeq);
                        if (frame != null) lastGoodFrame = frame;
                    }
                }

                //Jump ahead logic
                if (currentMissingStreak >= VoipConfig.SKIP_AHEAD_THRESHOLD && bufferSize() >= VoipConfig.STARTUP_BUFFER_FRAMES) {
                    
                    Integer jumpSeq;
                    synchronized (bufferLock) {
                        if(!playoutBuffer.isEmpty()) {
                            jumpSeq = playoutBuffer.firstKey();
                        } else {
                            jumpSeq = null;
                        }
                    }

                    if (jumpSeq != null && jumpSeq > playoutSeq) {
                        sumBurstLen += currentMissingStreak;
                        currentMissingStreak = 0;
                        inBurst = false;

                        playoutSeq = jumpSeq;
                        
                        frame = bufferGet(playoutSeq);
                        if (frame != null) {
                            lastGoodFrame = frame;
                        } else {
                            frame = applyPLC(lastGoodFrame, 1);
                        }
                    } else {
                        frame = applyPLC(lastGoodFrame, currentMissingStreak);
                    }

                } else {
                    frame = applyPLC(lastGoodFrame, currentMissingStreak);
                }
            }
        
            try{
                totalPlayedFrames++;
                player.playBlock(frame);
            } catch (Exception e) {
                e.printStackTrace();
            }

            playoutSeq++;

            long now = System.nanoTime();
            long sleepNs = nextPlayTime - now;
            if (sleepNs > 0) {
                sleepNanos(sleepNs);
            } else {
                nextPlayTime = now;
            }
            
        }
    }

    //Player loop helper function
    private void sleepNanos(long nanos) {
        try {
            long ms = nanos / 1_000_000L;
            int ns = (int)(nanos % 1_000_000L);
            Thread.sleep(ms, ns);
        } catch (InterruptedException ignored) {}
    }

    //PLC helper function
    private byte[] applyPLC(byte[] frame, int streak) {

    double soundVolume;

    if (streak == 1) soundVolume = 1.0;
    else if (streak == 2) soundVolume = 0.85;
    else if (streak == 3) soundVolume = 0.70;
    else if (streak == 4) soundVolume = 0.55;
    else if (streak == 5) soundVolume = 0.40;
    else soundVolume = 0.0;

    byte[] outputFrame = new byte[frame.length];

    for(int i = 0; i < frame.length; i+=2) {

        short sample =
            (short)((frame[i+1] << 8) | (frame[i] & 0xFF));

        sample = (short)(sample * soundVolume);

        outputFrame[i]   = (byte)(sample & 0xFF);
        outputFrame[i+1] = (byte)((sample >> 8) & 0xFF);
    }

    return outputFrame;
}



    public void run () {
     
        //***************************************************
        //Port to open socket on
        int PORT = VoipConfig.DEFAULT_PORT;
        //***************************************************
        
        //***************************************************
        //Open a socket to receive from on port PORT
        
        //DatagramSocket receiving_socket;
        try{
            switch (socketType) {
                case 1:
                    receiving_socket = new DatagramSocket(PORT);
                    break;
                case 2:
                    receiving_socket = new DatagramSocket2(PORT);
                    break;
                case 3:
                    receiving_socket = new DatagramSocket3(PORT);
                    break;
                default:
                    System.out.println("Invalid socket type specified. Defaulting to DatagramSocket.");
                    receiving_socket = new DatagramSocket(PORT);
                    break;
            }
            receiving_socket.setSoTimeout(VoipConfig.SOCKET_TIMEOUT);
        } catch (SocketException e){
                    System.out.println("ERROR: AudioReceiver: Could not open UDP socket to receive from.");
            e.printStackTrace();
                    System.exit(0);
	}
        //***************************************************
        
        //***************************************************
        //Initialize AudioPlayer
        AudioPlayer player;
        try {
            player = new AudioPlayer();
        } catch (Exception e) {
            System.out.println("ERROR: AudioReceiver: Could not initialize AudioPlayer.");
            e.printStackTrace();
            receiving_socket.close();
            return;
        }

        Thread playerThread = new Thread(() -> playerLoop(player));
        playerThread.setDaemon(true);
        playerThread.start();


        //***************************************************


        //***************************************************
        //Main loop.
        
        boolean running = true;
        
        
        while (running){
         
            try{

                //Receive a DatagramPacket
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, 0, buffer.length);

                receiving_socket.receive(packet);

                int len = packet.getLength();

                if (len == HandshakePacket.SIZE) {
                    handleHandshake(packet);
                } else if (len == VoipConfig.PACKET_SIZE_BYTES) {

                    if (!SessionState.established) {
                        System.out.println("Audio packet received before session established. Discarding.");
                        continue;
                    }

                    //Get Headers
                    byte[] voiceData = Arrays.copyOf(packet.getData(), packet.getLength());
                    ParsedPacket parsedPacket = VoipPacket.decodePacket(voiceData);
                    short packet_key = parsedPacket.authKey;
                    int packet_sequence = parsedPacket.seq;
                    int packet_timestamp = parsedPacket.timestamp;
                    int reserved = parsedPacket.reserved;

                    //Temp reserved use
                    reserved = reserved & 0xFFFF;

                    //*******************
                    //Statistics calculations
                    

                    //Packets calculations
                    receivedPackets++;

                    if (packet_sequence == expectedSeq) {
                        expectedSeq++;
                    } else if (packet_sequence > expectedSeq) {
                        lostPackets += (packet_sequence - expectedSeq);
                        expectedSeq = packet_sequence + 1;
                    }

                    //Jitter calculations  
                    long arrivalTime = System.currentTimeMillis();
                    long delay = arrivalTime - packet_timestamp;

                    if (previousDelay != -1) {
                        long diff = Math.abs(delay - previousDelay);
                        totalJitter += diff;
                    }
                    previousDelay = delay;

                    avgBurstLen = burstCount > 0 ? (double) sumBurstLen / burstCount : 0;
                    plcRate = totalPlayedFrames > 0 ? (double) totalMissingFrames / totalPlayedFrames : 0;

                    //Statistics printing
                    if (receivedPackets % 200 == 0) {
                        System.out.println("Current Socket: " + socketType);
                        System.out.println("Packets received: " + receivedPackets);
                        System.out.println("Lost: " + lostPackets);
                        System.out.println("Late discards: " + lateDiscards);
                        System.out.println("Duplicates: " + duplicatePackets);
                        System.out.println("Avg Jitter: " + (totalJitter / (receivedPackets - 1)) + " ms");
                        System.out.println("Buffer frames: " + bufferSize());
                        System.out.println("PlayoutSeq: " + playoutSeq);
                        System.out.println("Total missing frames: " + totalMissingFrames);
                        System.out.println("Current missing streak: " + currentMissingStreak);
                        System.out.println("Max missing streak: " + maxMissingStreak);
                        System.out.println("Burst count: " + burstCount);
                        System.out.println("Sum of burst lengths: " + sumBurstLen);
                        System.out.printf("Average burst length: %.2f\n", avgBurstLen);
                        System.out.printf("PLC rate: %.2f\n", plcRate);
                        System.out.println("-----------------------------------");

                    }

                    //*******************

                    // Get audio block from packet
                    byte[] packet_block = parsedPacket.payload;

                    //Authenticate packet
                    if(SessionState.authKey == packet_key){
                        if(!playoutSeqInitialized) {
                            playoutSeq = packet_sequence;
                            expectedSeq = packet_sequence + 1;
                            playoutSeqInitialized = true;
                        }
                        //Decrypt audio block
                        byte[] decrypted = decrypt(packet_block, SessionState.encryptionKey);
                        bufferPut(packet_sequence, decrypted);

                    } else {
                        System.out.println("Received packet with wrong authentication key. Discarding.");
                    }  
                } else {
                    System.out.println("Received packet of invalid length: " + len);
                }

                


                 

            } catch (SocketTimeoutException e) {
                System.out.println(".");
            } catch (IOException e){
                System.out.println("ERROR: AudioReceiver: Some random IO error occured!");
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        //Close the socket
        receiving_socket.close();
        //***************************************************
    }
}
