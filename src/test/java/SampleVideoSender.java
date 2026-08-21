/*
 */

import javax.swing.JFrame;

import vavi.apps.docopin.jmf.TransmitControl;
import vavi.util.Debug;


/**
 * @author suno
 * Created on 2003/07/19
 */
public class SampleVideoSender extends JFrame {
    /** */
    private final TransmitControl control;

    /** */
    private static final String MEDIATYPE = "VIDEO";

    /** */
    public SampleVideoSender(String address, int port) {
        String url = "rtp://" + address + ":" + port + "/" + MEDIATYPE + "/3";
        this.control = new TransmitControl(url);
    }

    /** */
    static void main(String[] args) {
        String destinationAddress;
        int destinationPort;
        try {
            destinationAddress = "133.27.170.62";
            destinationPort = 40000;
        } catch (Exception e) {
            Debug.printStackTrace(e);
            return;
        }
        new SampleVideoSender(destinationAddress, destinationPort).setVisible(true);
    }
}
