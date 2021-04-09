package downloader.utility;

import com.google.common.util.concurrent.RateLimiter;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;

import static java.lang.Integer.numberOfLeadingZeros;
import static java.lang.Math.*;

class AsyncFileDownloader implements Runnable {
    private static final int MAX_BUFFER_SIZE = 1_048_576;
    private static final int DEFAULT_BANDWIDTH = Integer.MAX_VALUE;

    private URI fileURI;

    private Path downloadDirectoryPath;

    private int threadNumber;

    private int bandwidth = DEFAULT_BANDWIDTH;

    private RateLimiter rateLimiter;

    private DownloadManager masterManager;

    private int bufferSize;

    private AsyncFileDownloader() {}

    public static DownloaderBuilder getBuilder() {
        return new AsyncFileDownloader().new DownloaderBuilder();
    }

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

    @Override
    public void run() {
        while (!Objects.isNull(fileURI)) {
            try {
                HttpsURLConnection connection = (HttpsURLConnection) fileURI.toURL().openConnection();
                connection.setConnectTimeout(10_000);
                InputStream inputStream = connection.getInputStream();

                downloadFile(inputStream);

                connection.disconnect();
            } catch (IOException ioException) {
                System.out.println("An error occurred while establishing a connection.");
                ioException.printStackTrace();
            }

            fileURI = masterManager.getNextURI();
        }

        afterThreadRun();
    }

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

                if (bytesReadNow == -1 ) {
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

    public void setNewBandwidth(int newBandwidth) {
        bandwidth = newBandwidth;
        setNewBufferSize();
        rateLimiter.setRate(bandwidth);
    }


    ////////////////////////////////////////////
    /// Impl
    ////////////////////////////////////////////
    private void printDownloadStatistics(LocalTime start, LocalTime end, long size, String fileName) {
        Duration duration = Duration.between(start, end);

        System.out.println("------------------------------------------------------------");
        System.out.printf(Locale.US,"- File \"%s\" has been downloaded! (Thread %d)%n", fileName, threadNumber);
        System.out.printf(Locale.US,"- Download time: %dM %d.%02dS%n",
            duration.getSeconds() / 60,
            duration.getSeconds() % 60,
            duration.getNano() / 10_000_000);
        System.out.printf(Locale.US,"- File size: %.3f KB%n", size / (double) 1000);
        System.out.printf(Locale.US,"- Average download speed: %.3f KB/s%n", calculateAverageSpeed(size, duration) / 1000);
        System.out.println("------------------------------------------------------------");
    }

    private double calculateAverageSpeed(long size, Duration duration) {
        return size / (((double)duration.getNano() / 1_000_000_000) + duration.getSeconds()) ;
    }

    private void setNewBufferSize() {
        bufferSize = Math.min(bandwidth, MAX_BUFFER_SIZE);
    }

    private void afterThreadRun() {
        masterManager.notifyOnThreadDestroy(threadNumber);
    }

    private int getCeilPowerOfTwo(int x) {
        return x == 0 ? 1 : round((float) pow(2, 32.0 - numberOfLeadingZeros(x - 1)));
    }

    private String getFileIdentifier() {
        return Paths.get(fileURI.getPath()).getFileName().toString();
    }
}
