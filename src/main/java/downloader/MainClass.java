package downloader;

import downloader.utility.DownloadManager;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.lang.Math.min;
import static java.util.Collections.synchronizedList;

public class MainClass {
    private static Path fileWithURLs;
    private static int bandwidth = 1024 * 1024;
    private static int numberOfThreads = 2;

    private static final List<String> ARG_LIST = List.of("f", "b", "t");

    private static final Path DOWNLOAD_DIRECTORY_PATH = Paths.get("/download");

    public static void main(String[] args) {
        parseCommandLine(args);

        List<URI> filesURLs = synchronizedList(obtainURIsList());

        DownloadManager manager = new DownloadManager(filesURLs, DOWNLOAD_DIRECTORY_PATH, numberOfThreads, bandwidth);
        manager.startDownload();
    }

    /////////////////////////////////////////////
    /// Impl
    /////////////////////////////////////////////

    private static void parseCommandLine(String[] args) {
        for (String str :
            args) {
            if (isArgument(str)) {
                Map.Entry<String, String> entry = getArgumentFromString(str);
                switch (entry.getKey()) {
                    case "f":
                        fileWithURLs = Paths.get(entry.getValue());
                        break;
                    case "t":
                        numberOfThreads = Integer.parseInt(entry.getValue());
                        break;
                    case "b":
                        bandwidth = Integer.parseInt(entry.getValue());
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static Map.Entry<String, String> getArgumentFromString(String str) {
        return new AbstractMap.SimpleImmutableEntry<>(
            str.substring(1, str.indexOf('=')),
            str.substring(str.indexOf('=') + 1));
    }

    private static boolean isArgument(String str) {
        return str.length() >= 4
            && str.charAt(0) == '-'
            && str.indexOf('=') != -1
            && ARG_LIST.contains(str.substring(1, str.indexOf('=')));
    }

    private static List<URI> obtainURIsList() {
        List<URI> FilesURLs = new ArrayList<>();
        try (FileReader fileReader = new FileReader(fileWithURLs.toFile());
             BufferedReader bufferedReader = new BufferedReader(fileReader)) {
            String str;
            while ((str = bufferedReader.readLine()) != null) {
                FilesURLs.add(URI.create(str));
            }
        } catch (FileNotFoundException e) {
            System.out.println("Не найден файл \"" + fileWithURLs + "\"");
            e.printStackTrace();
        } catch (IOException ioException) {
            System.out.println("Ошибка при чтении из файла \"" + fileWithURLs + "\"");
            ioException.printStackTrace();
        }

        return FilesURLs;
    }

}
