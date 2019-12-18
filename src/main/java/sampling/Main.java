package sampling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
public class Main implements ApplicationRunner {

    private static Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(final String[] args) {
        SpringApplication.run(Main.class, args);
    }

    private static final int BUF_SIZE = 65536;

    // full amplitude range is from -1.0 to +1.0 (like in Audacity)
    private double thresholdInLevel = 0.03;
    private double thresholdOutLevel = 0.0003;
    private int absThresholdIn = 0;
    private int absThresholdOut = 0;

    private File currentFile = null;
    private int numChannels = 0;
    private long sampleRate = 0;
    private int validBits = 0;

    private ArrayList<Frame> samples = new ArrayList<>(1024 * 1024);
    private Peaks peaks;
    private ArrayList<Frame> samplesShadowForLeadIn = new ArrayList<>(1024 * 1024);
    private int channelForZeroCrossingDetection = 0;
    private int peak;
    private boolean thresholdInReached;
    private int thresholdOutReachedCount;
    private int maxThresholdOutReachedCount = 100;

    private int leadInFrames = 88; // about 2ms @ 44.1kHz

    private File outputDir = null;
    private int fileCount = 0;

    @Override
    public void run(ApplicationArguments args) throws IOException, WavFileException {
        if (args.containsOption("help")) {
            printHelp();
            return;
        }

        parseApplicationArguments(args);

        for (String file : args.getNonOptionArgs()) {
            LOG.info("Processing file {}", file);
            currentFile = new File(file);

            try (WavFile wav = WavFile.openWavFile(currentFile)) {
                numChannels = wav.getNumChannels();
                sampleRate = wav.getSampleRate();
                validBits = wav.getValidBits();

                LOG.info("  channels: {}", numChannels);
                LOG.info("  sample rate: {}", sampleRate);
                LOG.info("  sample size: {}", validBits);

                int maxSampleValue = 1 << (validBits - 1);
                absThresholdIn = (int) (maxSampleValue * thresholdInLevel);
                absThresholdOut = (int) (maxSampleValue * thresholdOutLevel);

                samples.clear();
                peaks = new Peaks(maxSampleValue);
                samplesShadowForLeadIn.clear();
                peak = 0;
                thresholdInReached = false;
                thresholdOutReachedCount = 0;
                fileCount = 1;

                int[][] sampleBuffer = new int[numChannels][BUF_SIZE];
                while (true) {
                    int samplesRead = wav.readFrames(sampleBuffer, BUF_SIZE);
                    if (samplesRead == 0) {
                        handleEof();
                        break;
                    }

                    for (int i = 0; i < samplesRead; i++) {
                        handleFrame(Frame.readFromSampleBuffer(sampleBuffer, i));
                    }
                }
            }
        }
    }

    private void printHelp() {
        System.out.println("Usage: java -jar sample-cutter.jar [options...] [files...]");
        System.out.println("  options:");
        System.out.println("    --channel-for-zero-crossing-detection=<value>");
        System.out.println("            the channel number for zero-crossing detection, default = " + channelForZeroCrossingDetection);
        System.out.println("    --threshold-in=<value>");
        System.out.println("            any float value > 0 and <= 1, default = " + thresholdInLevel);
        System.out.println("    --threshold-out=<value>");
        System.out.println("            any float value > 0 and < threshold-in, default = " + thresholdOutLevel);
        System.out.println("    --threshold-out-reached-count=<value>");
        System.out.println("            the number of subsequent half-waves with peak < threshold-out to detect the end of sample, default = " + maxThresholdOutReachedCount);
        System.out.println("    --lead-in");
        System.out.println("            number of frames to prepend before the initial zero-crossing, default = 44 (~1ms)");
        System.out.println("    --output-dir=<dir>");
        System.out.println("            output directory, default = current directory");
    }

    private void parseApplicationArguments(ApplicationArguments args) {
        String channelForZeroCrossingDetectionValue = parseArgument(args, "channel-for-zero-crossing-detection");
        if (channelForZeroCrossingDetectionValue != null) {
            channelForZeroCrossingDetection = Integer.parseInt(channelForZeroCrossingDetectionValue);
        }

        String thresholdInValue = parseArgument(args, "threshold-in");
        if (thresholdInValue != null) {
            thresholdInLevel = Double.parseDouble(thresholdInValue);
        }

        String thresholdOutValue = parseArgument(args, "threshold-out");
        if (thresholdOutValue != null) {
            thresholdOutLevel = Double.parseDouble(thresholdOutValue);
        }

        String thresholdOutReachedCountValue = parseArgument(args, "threshold-out-reached-count");
        if (thresholdOutReachedCountValue != null) {
            maxThresholdOutReachedCount = Integer.parseInt(thresholdOutReachedCountValue);
        }

        String outputDirValue = parseArgument(args, "output-dir");
        if (outputDirValue != null) {
            outputDir = new File(outputDirValue);
        }

        String leadInValue = parseArgument(args, "lead-in");
        if (leadInValue != null) {
            leadInFrames = Integer.parseInt(leadInValue);
        }
    }

    private String parseArgument(ApplicationArguments args, String arg) {
        String value = null;
        List<String> values = args.getOptionValues(arg);
        if (values != null && !values.isEmpty()) {
            value = values.get(0);
            LOG.info("{} set to {}", arg, value);
        }
        return value;
    }

    private void handleFrame(Frame frame) throws IOException, WavFileException {
        // Only consider one channel for zero-crossing detection.
        // Otherwise it sometimes can happen that multiple zero-crossings are detected
        // if there's (still) a signal, but the channel values are phase-inverted.
        // This would lead to a false threshold-out detection and the writing of a sample file
        // which still is far away from actually reaching the real threshold-out.

        // On the other hand, consider all channels for peak (and subsequent threshold-out) detection.
        // Otherwise, if the other channel is still "loud", the output sample file would be cut too early.

        // peak value detection/update over all channels, keeping the sign once it is set.
        int framePeak = frame.getPeak();
        if ((peak > 0 && framePeak > peak) || (peak < 0 && framePeak < peak)) {
            peak = framePeak;
        }

        // zero crossing detection
        int value = frame.getValue(channelForZeroCrossingDetection);
        if (peak == 0) {
            if (value == 0) {
                // Handle digital silence as half-wave (with size of one frame).
                // Otherwise it will accumulate to the lead-in of the written sample,
                // causing an unwanted delay before the attack.

                handleHalfWave();
            }
            peak = value;
        } else if ((peak > 0 && value <= 0) || (peak < 0 && value >= 0)) {
            handleHalfWave();
            peak = value;
        }

        samples.add(frame);
        samplesShadowForLeadIn.add(frame);
    }

    private void handleHalfWave() throws IOException, WavFileException {
        if (!thresholdInReached) {
            if (Math.abs(peak) < absThresholdIn) {
                // Didn't detect anything yet, except "noise".
                // However, keep the samplesShadowForLeadIn buffer, in case we need it for the next half-wave
                samples.clear();
                peaks.clear();
                return;
            }

            thresholdInReached = true;
        }

        peaks.add(peak);

        if (Math.abs(peak) < absThresholdOut) {
            thresholdOutReachedCount++;
        } else {
            thresholdOutReachedCount = 0;
        }

        if (thresholdOutReachedCount >= maxThresholdOutReachedCount) {
            writeSample();

            thresholdOutReachedCount = 0;
            thresholdInReached = false;

            samples.clear();
            peaks.clear();
            samplesShadowForLeadIn.clear();
        }
    }

    private void handleEof() throws IOException, WavFileException {
        if (thresholdInReached) {
            // we don't want to lose the last sample if threshold out is not reached yet
            writeSample();
        }
    }

    private void writeSample() throws IOException, WavFileException {
        // Prepend each sample with a lead-in of 'leadInFrames' frames.
        // This will match a 2ms fade-in I apply to the samples in a post-processing step
        // (not included in this tool), so it won't "distort" the initial attack slope:
        //     sox ... fade h 0.002

        int[][] leadIn = new int[numChannels][leadInFrames];
        if (samplesShadowForLeadIn.size() - samples.size() < leadInFrames) {
            LOG.warn("too less data for a lead-in");
        } else {
            for (int i = 0; i < leadInFrames; i++) {
                samplesShadowForLeadIn
                        .get(samplesShadowForLeadIn.size() - samples.size() - leadInFrames + i)
                        .writeToSampleBuffer(leadIn, i);
            }
        }

        //                     leadInLastDigitalZeroFrame    leadInFramesToWrite
        //
        // x x x x x x x x 0   leadInFrames-1                0
        // x x x x 0 2 2 5 3   n                             m
        // 1 2 3 4 5 6 7 8 9   -1                            leadInFrames
        //                                                   leadInFrames - leadInLastDigitalZeroFrame - 1
        //
        //     example values
        //                     87                            88 - 87 - 1 = 0
        //                     83                            88 - 83 - 1 = 4
        //                     -1                            88 - -1 - 1 = 88

        // TODO use Frame.isZero() here
        int leadInLastDigitalZeroFrame = -1;
        for (int i = 0; i < leadInFrames; i++) {
            boolean isZero = true;
            for (int c = 0; c < numChannels; c++) {
                if (leadIn[c][i] != 0) {
                    isZero = false;
                }
            }
            if (isZero) {
                leadInLastDigitalZeroFrame = i;
            }
        }
        int leadInFramesToWrite = leadInFrames - leadInLastDigitalZeroFrame - 1;
        if (leadInFramesToWrite < leadInFrames) {
            LOG.info("Just writing the last {} frames of the lead-in", leadInFramesToWrite);
        }

        int[][] frames = new int[numChannels][samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            samples.get(i).writeToSampleBuffer(frames, i);
        }

        int thresholdInDelayFrames = getThresholdInDelayFrames();

        String fileName = String.format(
                "%s_%03d_%06.2f_%05d.wav",
                currentFile.getName().replace(".wav", ""),
                fileCount++,
                peaks.getEffectivePeakValueInDb(),
                thresholdInDelayFrames
        );
        File targetFile = outputDir != null ? new File(outputDir, fileName) : new File(fileName);

        LOG.info("Writing {}", fileName);
        try (WavFile wav = WavFile.newWavFile(targetFile, numChannels, leadInFramesToWrite + samples.size(), validBits, sampleRate)) {
            wav.writeFrames(leadIn, leadInFrames - leadInFramesToWrite, leadInFramesToWrite);
            wav.writeFrames(frames, samples.size());
        }
    }

    private int getThresholdInDelayFrames() {
        for (int i = 0; i < samples.size(); i++) {
            int framePeak = samples.get(i).getPeak();
            if (Math.abs(framePeak) >= absThresholdIn) {
                if (i < 220) {
                    LOG.info("threshold-in value reached after {} frames", i);
                } else {
                    // warn if bigger than ~5ms (@ 44.1kHz).
                    // Worst case is (usually during the attack) that after a zero crossing
                    // the waveform stays a little above (or below) zero for quite a "long" time
                    // and the threshold-in value is reached very late - e.g. right before the next zero crossing is reached.
                    // When writing such samples, they effectively will have unwanted delay before the attack.

                    LOG.warn("threshold-in value reached after {} frames", i);
                }

                return i;
            }
        }

        LOG.warn("threshold-in value never reached. WTF?");
        return -1;
    }
}
