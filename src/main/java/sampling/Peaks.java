package sampling;

import java.util.Set;
import java.util.TreeSet;

public class Peaks {

    private final int maxSampleValue;
    private final Set<Integer> peaks = new TreeSet<Integer>((a, b) -> -a.compareTo(b));

    public Peaks(int maxSampleValue) {
        this.maxSampleValue = maxSampleValue;
    }

    public void clear() {
        peaks.clear();
    }

    public void add(int peak) {
        peaks.add(Math.abs(peak));
    }

    // Previously I used "sox stats" to extract the "Pk lev dB".
    // However, it could happen that a sample has a very short transient attack
    // leading to a bigger peak value than a sample with a smoother attack but more loudness.
    //
    // I try to fix this by ignoring the 3 biggest peak values and averaging the next 5.

    public int getEffectivePeakValue() {
        return (int)Math.round(peaks.stream()
                .mapToInt(Integer::intValue)
                .skip(3)
                .limit(5)
                .average()
                .orElse(0.0)
        );
    }

    public double getEffectivePeakValueInDb() {
        return 20.0 * Math.log10(1.0 * getEffectivePeakValue() / maxSampleValue);
    }
}
