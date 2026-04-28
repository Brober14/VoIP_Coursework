package V02;
/*
 * TextDuplex.java
 */

/**
 *
 * @author  abj
 */
public class AudioDuplex {
    
    public static void main (String[] args){
        
        AudioReceiverThread receiver = new AudioReceiverThread();
        AudioSenderThread sender = new AudioSenderThread();
        
        receiver.start();
        sender.start();
        
    }
    
}
