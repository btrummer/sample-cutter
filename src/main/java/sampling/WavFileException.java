package sampling;

/**
 * Copied from @url{http://www.labbookpages.co.uk/audio/javaWavFiles.html}.
 */
public class WavFileException extends Exception {
    public WavFileException() {
        super();
    }

    public WavFileException(String message) {
        super(message);
    }

    public WavFileException(String message, Throwable cause) {
        super(message, cause);
    }

    public WavFileException(Throwable cause) {
        super(cause);
    }
}
