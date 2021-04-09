package downloader.utility;

import com.google.common.util.concurrent.RateLimiter;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;

class AsyncFileDownloader implements Runnable {
    public static final int MAX_TRIES = 3;
    public static final int SLEEP_TIME_MS = 5_000;
    private static final int MAX_BUFFER_SIZE = 1_048_576;
    private static final int DEFAULT_BANDWIDTH = Integer.MAX_VALUE;

    private URI fileURI;
    private Path downloadDirectoryPath;
    private int threadNumber;
    private int bandwidth = DEFAULT_BANDWIDTH;

    private RateLimiter rateLimiter;
    private HttpsURLConnection connection;
    private int bufferSize;
    private DownloadManager masterManager;
    private int numberOfTry = 0;

    public class DownloaderBuilder {
        public DownloaderBuilder setFileURI(URI fileURI) {
            AsyncFileDownloader.this.fileURI = fileURI;
            return this;
        }

        public DownloaderBuilder setSpeedRestriction(int bps) {
            AsyncFileDownloader.this.bandwidth = bps;
            return this;
        }

        public DownloaderBuilder setThreadNumber(int number) {
            AsyncFileDownloader.this.threadNumber = number;
            return this;
        }

        public DownloaderBuilder setDownloadDirectory(Path directoryPath) {
            downloadDirectoryPath = directoryPath;
            return this;
        }

        public AsyncFileDownloader build(DownloadManager masterManager) {
            rateLimiter = RateLimiter.create(bandwidth);

            setNewBufferSize();

            AsyncFileDownloader.this.masterManager = masterManager;

            return AsyncFileDownloader.this;
        }
    }

    private AsyncFileDownloader() {
    }

    public static DownloaderBuilder getBuilder() {
        return new AsyncFileDownloader().new DownloaderBuilder();
    }

    @Override
    public void run() {
        while (!Objects.isNull(fileURI)) {
            try {
                InputStream inputStream = establishConnection();

                downloadFile(inputStream);

                connection.disconnect();
            } catch (IOException ioException) {
                System.out.println("Thread" + threadNumber + ": an error occurred while establishing a connection with " + fileURI.toString());
                if (numberOfTry < MAX_TRIES) {
                    System.out.println("Thread" + threadNumber + ": trying to reestablish a connection... " + ++numberOfTry);
                    sleep();
                    continue;
                } else {
                    System.out.println("Thread" + threadNumber + ": failed to reestablish a connection; file " + fileURI.toString() + " is skipped");
                }
            }

            fileURI = masterManager.getNextURI();
        }

        afterThreadRun();
    }

    public void setNewBandwidth(int newBandwidth) {
        if (newBandwidth != Integer.MAX_VALUE) {
            bandwidth = newBandwidth;
            setNewBufferSize();
            rateLimiter.setRate(bandwidth);
        }
    }

    private InputStream establishConnection() throws IOException {
        connection = (HttpsURLConnection) fileURI.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        return connection.getInputStream();
    }

    /////////////////////////////////////////////
    /// Impl
    /////////////////////////////////////////////

    private void downloadFile(InputStream inputStream) {
        int bytesReadNow = bandwidth; //initialize it for proper throttle in cases when size of the file is around bandwidth limit
        long totalDownloaded = 0;

        Path tempFilePath = downloadDirectoryPath.resolve("temp" + threadNumber);
        try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(
            new FileOutputStream(tempFilePath.toString()))) {

            LocalTime start = LocalTime.now();
            while (true) {
                byte[] arr = new byte[bufferSize];

                rateLimiter.acquire(bytesReadNow);

                bytesReadNow = inputStream.read(arr);

                if (bytesReadNow == -1) {
                    break;
                }

                bufferedOutputStream.write(arr, 0, bytesReadNow);
                totalDownloaded += bytesReadNow;
            }
            bufferedOutputStream.flush();

            LocalTime end = LocalTime.now();

            printDownloadStatistics(start, end, totalDownloaded, getFileIdentifier());
        } catch (IOException ioException) {
            System.out.println("An error occurred while creating a file.");
            ioException.printStackTrace();
        }

        Path filePath = tempFilePath.resolveSibling(getFileIdentifier());
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
            }

            Files.move(tempFilePath, tempFilePath.resolveSibling(getFileIdentifier()));
        } catch (IOException ioException) {
            System.out.println("An error occurred while deleting a temporary file.");
            ioException.printStackTrace();
        }
    }

    private void printDownloadStatistics(LocalTime start, LocalTime end, long size, String fileName) {
        Duration duration = Duration.between(start, end);

        System.out.println("------------------------------------------------------------");
        System.out.printf(Locale.US, "- File \"%s\" has been downloaded! (Thread %d)%n", fileName, threadNumber);
        System.out.printf(Locale.US, "- Download time: %dM %d.%02dS%n",
            duration.getSeconds() / 60,
            duration.getSeconds() % 60,
            duration.getNano() / 10_000_000);
        System.out.printf(Locale.US, "- File size: %.3f KB%n", size / (double) 1000);
        System.out.printf(Locale.US, "- Average download speed: %.3f KB/s%n", calculateAverageSpeed(size, duration) / 1000);
        System.out.println("------------------------------------------------------------");
    }

    private double calculateAverageSpeed(long size, Duration duration) {
        return size / (((double) duration.getNano() / 1_000_000_000) + duration.getSeconds());
    }

    private void setNewBufferSize() {
        bufferSize = Math.min(bandwidth, MAX_BUFFER_SIZE);
    }

    private void sleep() {
        try {
            Thread.sleep(SLEEP_TIME_MS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private void afterThreadRun() {
        masterManager.notifyOnThreadDestroy(threadNumber);
    }

    private String getFileIdentifier() {
        return Paths.get(fileURI.getPath()).getFileName().toString();
    }
}
