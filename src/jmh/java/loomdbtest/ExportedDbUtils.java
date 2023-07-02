package loomdbtest;

// Used in SQL scripts
@SuppressWarnings("unused")
public final class ExportedDbUtils {
    public static Integer sleepSeconds(double seconds) {
        try {
            Thread.sleep(Math.round(seconds * 1000.0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private ExportedDbUtils() {
        throw new AssertionError();
    }
}
