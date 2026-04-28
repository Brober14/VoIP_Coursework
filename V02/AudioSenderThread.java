package V02;

/*
 * TextSender.java
*/

/**
 *
 * @author  abj
 */
import java.net.*;
import java.security.SecureRandom;
import java.io.*;

//Import JAR Lib
import CMPC3M06.AudioRecorder;
import uk.ac.uea.cmp.voip.DatagramSocket2;
import uk.ac.uea.cmp.voip.DatagramSocket3; 

public class AudioSenderThread implements Runnable {
    
    static DatagramSocket sending_socket;
    private int sequenceNumber = 0;
    private int socketType;

    public void start(){
        this.socketType = VoipConfig.SOCKET_TYPE;
        Thread thread = new Thread(this);
	thread.start();
    }

    //Handshake helper
    private void sendHandshakePacket(byte[] data, InetAddress ip, int port) throws IOException {
        DatagramPacket packet1 = new DatagramPacket(data, data.length, ip, port);
        sending_socket.send(packet1);

        if (socketType == 3) {
            DatagramPacket packet2 = new DatagramPacket(data, data.length, ip, port);
            sending_socket.send(packet2);
        }
    }

    //Handshake function
    private boolean performHandshake(InetAddress clientIP, int port) throws IOException {
        int mySecret = 2 + new SecureRandom().nextInt(90); 
        int myPublic = KeyExchange.generatePublicKey(mySecret);

        byte[] hello = HandshakePacket.encode(HandshakePacket.HELLO, myPublic);

        int oldTimeout = sending_socket.getSoTimeout();
        sending_socket.setSoTimeout(VoipConfig.HANDSHAKE_TIMEOUT_MS);

        try {
            for (int attempt = 1; attempt <= VoipConfig.HANDSHAKE_MAX_RETRIES; attempt++) {
                System.out.println("Sending HELLO, attempt " + attempt);

                sendHandshakePacket(hello, clientIP, port);

                try {
                    byte[] responseBuf = new byte[1024];
                    DatagramPacket response = new DatagramPacket(responseBuf, responseBuf.length);
                    sending_socket.receive(response);

                    if (response.getLength() != HandshakePacket.SIZE) {
                        System.out.println("Received non-handshake packet during handshake. Ignoring.");
                        continue;
                    }

                    HandshakePacket.ParsedHandshake hs =
                        HandshakePacket.decode(response.getData(), response.getLength());

                    if (hs.type != HandshakePacket.HELLO_REPLY) {
                        System.out.println("Received unexpected handshake type. Ignoring.");
                        continue;
                    }

                    int shared = KeyExchange.calculateSharedKey(mySecret, hs.publicKey);

                    SessionState.sharedSecret = shared;
                    SessionState.encryptionKey = KeyExchange.deriveEncryptionKey(shared);
                    SessionState.authKey = KeyExchange.deriveAuthKey(shared);
                    SessionState.established = true;

                    System.out.println("Handshake complete on sender. Shared = " + shared);
                    return true;

                } catch (SocketTimeoutException e) {
                    System.out.println("Handshake timeout on attempt " + attempt);
                }
            }

            System.out.println("Handshake failed after " + VoipConfig.HANDSHAKE_MAX_RETRIES + " attempts.");
            return false;

        } finally {
            sending_socket.setSoTimeout(oldTimeout);
        }
    }



    /*
     * ENCRYPTION FUNCTION
     * This uses a simple bit-rotation and XOR.
     */
    public static byte[] encrypt(byte[] audioData, int key) {
        byte[] output = new byte[audioData.length];
        int byteKey = key & 0xFF;

        for (int i = 0; i < audioData.length; i++) {
            int b = audioData[i] & 0xFF;
            int scrambled = (b ^ byteKey) & 0xFF;
            int rotated = ((scrambled << 3) | (scrambled >>> 5)) & 0xFF;
            output[i] = (byte) rotated;
        }

        return output;
    }
    
    public void run () {
     
        //***************************************************
        //Port to send to
        int PORT = VoipConfig.DEFAULT_PORT;
        //IP ADDRESS to send to
        InetAddress clientIP = null;
        try {
            clientIP = InetAddress.getByName(VoipConfig.PEER_IP);
        } catch (UnknownHostException e) {
            System.out.println("ERROR: AudioSender: Could not find client IP");
            e.printStackTrace();
            System.exit(0);
        }
        //***************************************************
        
        //***************************************************
        //Open a socket to send from
        //We dont need to know its port number as we never send anything to it.
        //We need the try and catch block to make sure no errors occur.
        
        try{
            switch (socketType) {
                case 1:
                    sending_socket = new DatagramSocket();
                    break;
                case 2:
                    sending_socket = new DatagramSocket2();
                    break;
                case 3:
                    sending_socket = new DatagramSocket3();
                    break;
                default:
                    System.out.println("Invalid socket type specified. Defaulting to DatagramSocket.");
                    sending_socket = new DatagramSocket();
                    break;
            }
	    } catch (SocketException e){
            System.out.println("ERROR: AudioSender: Could not open UDP socket to send from.");
		    e.printStackTrace();
            System.exit(0);
	    }
        //***************************************************

        //Perform handshake to establish session parameters
        try {
            sending_socket.setSoTimeout(3000);
            boolean handshakeOk = performHandshake(clientIP, PORT);
            if (!handshakeOk) {
                System.out.println("Sender startup aborted: handshake could not be established.");
                sending_socket.close();
                return;
            }
        } catch (IOException e) {
            System.out.println("ERROR: AudioSender: Handshake failed.");
            e.printStackTrace();
            sending_socket.close();
            return;
        }
                
        //Initialize AudioRecorder
        AudioRecorder recorder;
        try {
            recorder = new AudioRecorder();
        } catch (Exception e) {
            System.out.println("ERROR: AudioSender: Could not initialise AudioRecorder.");
            e.printStackTrace();
            sending_socket.close();
            return;
        }

        //***************************************************
        //Main loop.

        boolean running = true;
        
        while (running){
            while(!SessionState.established) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    System.out.println("ERROR: AudioSender: Interrupted while waiting for session to establish.");
                    e.printStackTrace();
                }
            }
            try{
                
                //Header construction
                int currentSequence = sequenceNumber++;
                int timestamp = (int) System.currentTimeMillis();
                
                //Get a block of audio data from the AudioRecorder and encrypt it
                byte[] block = recorder.getBlock();
                byte[] encrypted = encrypt(block, SessionState.encryptionKey);

                //Create VoIP package
                byte[] packetData = VoipPacket.encodePacket(SessionState.authKey, currentSequence, timestamp, encrypted);

                //Make a DatagramPacket from it, with client address and port number
                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, clientIP, PORT);
            
                //Send it
                sending_socket.send(packet);

            } catch (IOException e){
                System.out.println("ERROR: AudioSender: Some random IO error occured!");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("ERROR: AudioSender: Error occured!");
                e.printStackTrace();
            }
        }
        //Close the socket
        sending_socket.close();
        //***************************************************
    }
} 
