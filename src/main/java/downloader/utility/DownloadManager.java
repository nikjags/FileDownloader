package downloader.utility;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.Files.exists;

public class DownloadManager {
    private Path downloadDirectoryPath = Paths.get("./downloads");

    private int maxThreadNumber = -1;
    private final List<URI> urlList;

    private Map<Integer, AsyncFileDownloader> downloaderMap = new HashMap<>();

    private int numberOfThreads = 1;

    private int overallBandwidth = Integer.MAX_VALUE;

    private int bandwidthPerThread;

    public DownloadManager(List<URI> urlList) {
        this.urlList = urlList;
    }

    public DownloadManager(List<URI> urlList, Path downloadDirectoryPath, int numberOfThreads, int overallBandwidth) {
        this.urlList = urlList;
        this.downloadDirectoryPath = downloadDirectoryPath;
        this.numberOfThreads = numberOfThreads;

        this.overallBandwidth = overallBandwidth;
        recalculateBandwidthPerThread();
    }

    public void startDownload() {
        int listSize = urlList.size();

        if (!exists(downloadDirectoryPath)) {
            try {
                Files.createDirectory(downloadDirectoryPath);
            } catch (IOException ioException) {
                System.out.println("Error while creating a download directory.");
                ioException.printStackTrace();
            }
        }

        for (int i = 0; i < Math.min(numberOfThreads, listSize); i++) {
            newDownloadThread(urlList.remove(0));
        }

        writeAboutDownloadStart();
    }

    private void writeAboutDownloadStart() {
        System.out.println("-----------------------------");
        System.out.println("- Download has been started!");
        System.out.println("- Number of threads: " + numberOfThreads);
        System.out.println("-----------------------------");
    }

    synchronized boolean isNextURIExists() {
        return !urlList.isEmpty();
    }

    synchronized URI getNextURI() {
        return isNextURIExists() ? urlList.remove(0) : null;
    }


    synchronized void notifyOnThreadDestroy(int threadNumber) {
        if (numberOfThreads > 1) {
            downloaderMap.remove(threadNumber);
            numberOfThreads--;
            recalculateBandwidthPerThread();
            notifyThreads();
        } else {
            downloaderMap = null;
            writeCongratulation();
        }
    }

    synchronized int getThreadBandwidth() {
        return bandwidthPerThread;
    }


    /////////////////////////////////////////
    /// Impl
    /////////////////////////////////////////

    private void notifyThreads() {
        for (AsyncFileDownloader downloader :
            downloaderMap.values()) {
            downloader.setNewBandwidth(bandwidthPerThread);
        }
    }

    private void writeCongratulation() {
        System.out.println("------------------------------");
        System.out.println("ALL FILES HAS BEEN DOWNLOADED.");
        System.out.println("CONGRATULATION!");
        System.out.println("------------------------------");
    }

    private void newDownloadThread(URI uri) {
        AsyncFileDownloader downloader = AsyncFileDownloader.getBuilder()
            .setFileURI(uri)
            .setThreadNumber(++maxThreadNumber)
            .setSpeedRestriction(bandwidthPerThread)
            .setDownloadDirectory(downloadDirectoryPath)
            .build(this);

        downloaderMap.put(maxThreadNumber, downloader);

        Thread thread = new Thread(downloader);
        thread.start();
    }

    private void recalculateBandwidthPerThread() {
        if (numberOfThreads == 1) {
            bandwidthPerThread = overallBandwidth;
        } else {
            bandwidthPerThread = overallBandwidth / numberOfThreads;
        }
    }
}
