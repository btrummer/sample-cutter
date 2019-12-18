package sampling;

public class Frame {
    private final int[] frame;

    private Frame(int[] frame) {
        this.frame = frame;
    }

    /**
     * @return the offset'th frame in the WavFile sampleBuffer.
     */
    public static Frame readFromSampleBuffer(int[][] sampleBuffer, int offset) {
        int[] frame = new int[sampleBuffer.length];
        for (int c = 0; c < frame.length; c++) {
            frame[c] = sampleBuffer[c][offset];
        }
        return new Frame(frame);
    }

    public void writeToSampleBuffer(int[][] sampleBuffer, int offset) {
        for (int c = 0; c < sampleBuffer.length; c++) {
            sampleBuffer[c][offset] = frame[c];
        }
    }

    /**
     * @return The (signed) peak value from all channels of this Frame.
     */
    public int getPeak() {
        int peak = 0;
        for (int value : frame) {
            if (Math.abs(value) > Math.abs(peak)) {
                peak = value;
            }
        }
        return peak;
    }

    public int getValue(int channel) {
        return frame[channel];
    }

    public boolean isZero() {
        for (int value : frame) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
