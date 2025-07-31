package puregero.multipaper;

import io.methvin.watcher.DirectoryWatcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.mastermessagingprotocol.datastream.InboundDataStream;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.DownloadFileMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestFilesToSyncMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UploadFileMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.FileContentMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.FilesToSyncMessage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class MultiPaperFileSyncer extends Thread {
    private static final Logger LOGGER = LogManager.getLogger(MultiPaperFileSyncer.class.getSimpleName());

    public static void init() {
        MultiPaper.getConnection().sendAndAwaitReply(new RequestFilesToSyncMessage(), FilesToSyncMessage.class)
                .thenApply(message -> message.filesToSync)
                .thenComposeAsync(MultiPaperFileSyncer::uploadFiles)
                .join();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        instance = new MultiPaperFileSyncer();
    }

    public MultiPaperFileSyncer() {
        super("MultiPaperFileSyncer");

        start();
    }

    private static MultiPaperFileSyncer instance;
    public static final HashSet<String> pathsBeingModified = new HashSet<>();
    private static final Executor DELAYED_EXECUTOR = CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS);
    private final HashMap<File, CompletableFuture<Void>> queuedUploads = new HashMap<>();
    private final HashSet<CompletableFuture<Void>> ongoingUploads = new HashSet<>();
    private final HashSet<File> writeOnServerStop = new HashSet<>();

    public static CompletableFuture<Void> onStop() {
        if (instance != null) {
            return instance.closeUploads();
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> closeUploads() {
        synchronized (ongoingUploads) {
            for (File file : writeOnServerStop) {
                if (file.isFile()) {
                    ongoingUploads.add(sendUpload(file, false));
                }
            }
        }

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        synchronized (ongoingUploads) {
            for (CompletableFuture<Void> ongoingUpload : ongoingUploads) {
                future = CompletableFuture.allOf(future, ongoingUpload);
            }
        }

        return future;
    }

    public static CompletableFuture<Void> sendUpload(File file, boolean immediatelySyncToOtherServers) {
        try {
            byte[] data = Files.readAllBytes(file.toPath());

            return MultiPaper.getConnection().sendAndAwaitReply(new UploadFileMessage(immediatelySyncToOtherServers, file.getPath(), file.lastModified(), data), BooleanMessageReply.class).thenAccept(message -> {
                if (MultiPaperConfiguration.get().syncSettings.files.logFileSyncs) {
                    LOGGER.info("Uploaded " + file.getPath() + " (" + (data.length + 1023) / 1024 + "KB)");
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void run() {
        if (System.getProperty("disableFileWatching") != null) {
            return;
        }

        try {
            DirectoryWatcher.builder()
                    .path(Paths.get("."))
                    .fileHashing(false)
                    .listener(event -> {
                        try {
                            if (!event.isDirectory()) {
                                switch (event.eventType()) {
                                    case CREATE:
                                    case MODIFY:
                                        queueFileUpload(event.path());
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    })
                    .build()
                    .watch();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private synchronized void queueFileUpload(Path path) {
        File file = new File(getPathWithoutDotSlash(Paths.get(".").toAbsolutePath().relativize(path.toAbsolutePath()).toFile()));
        if (!syncFile(file)) {
            return;
        }

        boolean shouldWrite = false;
        boolean immediatelySyncToOtherServers = false;

        for (String toSync : MultiPaperConfiguration.get().syncSettings.files.filesToSyncInRealTime) {
            if (file.getPath().startsWith(toSync)) {
                shouldWrite = true;
                immediatelySyncToOtherServers = true;
            }
        }

        for (String toSync : MultiPaperConfiguration.get().syncSettings.files.filesToSyncOnStartup) {
            if (file.getPath().startsWith(toSync)) {
                shouldWrite = true;
            }
        }

        for (String toNotSync : MultiPaperConfiguration.get().syncSettings.files.filesToNotSync) {
            if (file.getPath().startsWith(toNotSync)) {
                shouldWrite = false;
            }
        }

        if (shouldWrite) {
            synchronized (MultiPaperFileSyncer.pathsBeingModified) {
                if (MultiPaperFileSyncer.pathsBeingModified.remove(file.getPath())) {
                    return;
                }
            }

            for (String toNotSync : MultiPaperConfiguration.get().syncSettings.files.filesToOnlyUploadOnServerStop) {
                if (file.getPath().startsWith(toNotSync)) {
                    writeOnServerStop.add(file);
                    return;
                }
            }

            CompletableFuture<Void> completableFuture = new CompletableFuture<>();
            queuedUploads.put(file, completableFuture);
            boolean finalImmediatelySyncToOtherServers = immediatelySyncToOtherServers;
            CompletableFuture.runAsync(() -> uploadFile(completableFuture, file, finalImmediatelySyncToOtherServers), DELAYED_EXECUTOR);
        }
    }

    private void uploadFile(CompletableFuture<Void> future, File file, boolean immediatelySyncToOtherServers) {
        if (queuedUploads.remove(file, future) && file.isFile()) {
            CompletableFuture<Void> uploadFuture = sendUpload(file, immediatelySyncToOtherServers);

            synchronized (ongoingUploads) {
                ongoingUploads.add(uploadFuture);
            }

            uploadFuture.thenRun(() -> {
                synchronized (ongoingUploads) {
                    ongoingUploads.remove(uploadFuture);
                }
            });
        }
    }

    private void registerRecursively(Path path, WatchService watchService) throws IOException {
        boolean listen = false;

        for (String toSync : MultiPaperConfiguration.get().syncSettings.files.filesToSyncInRealTime) {
            if (toSync.contains(getPathWithoutDotSlash(path.toFile()))) {
                listen = true;
            }
        }

        for (String toSync : MultiPaperConfiguration.get().syncSettings.files.filesToSyncOnStartup) {
            if (toSync.contains(getPathWithoutDotSlash(path.toFile()))) {
                listen = true;
            }
        }

        for (String toNotSync : MultiPaperConfiguration.get().syncSettings.files.filesToNotSync) {
            if ((getPathWithoutDotSlash(path.toFile()) + "/").contains(toNotSync)) {
                listen = false;
            }
        }

        if (listen) {
            path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

            for (File child : path.toFile().listFiles()) {
                if (child.isDirectory()) {
                    registerRecursively(child.toPath(), watchService);
                }
            }
        }
    }

    private static String getPathWithoutDotSlash(File file) {
        if (file.getPath().startsWith("./")) {
            return file.getPath().substring(2);
        }

        if (file.getPath().equals(".")) {
            return "";
        }

        return file.getPath();
    }

    private static CompletableFuture<Void> uploadFiles(FilesToSyncMessage.FileToSync[] fileInfos) {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        HashSet<String> paths = new HashSet<>();

        for (FilesToSyncMessage.FileToSync fileInfo : fileInfos) {
            File file = new File(fileInfo.getPath());
            paths.add(file.getPath());
            if (!file.isFile() || Math.abs(fileInfo.getLastModified() - file.lastModified()) > 2000) {
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                future = CompletableFuture.allOf(future, MultiPaper.getConnection()
                        .sendAndAwaitReply(new DownloadFileMessage(file.getPath()), FileContentMessage.class)
                        .thenCompose(message -> {
                            CompletableFuture<Void> fileDownloadedFuture = new CompletableFuture<>();
                            try {
                                InboundDataStream dataStream = MultiPaper.getConnection().getDataStreamManager().createInboundDataStream(MultiPaper.getConnection().getChannel(), message.streamId);
                                dataStream.copyToAsync(new FileOutputStream(file)).addListener(future2 -> {
                                    if (future2.cause() != null) {
                                        future2.cause().printStackTrace();
                                    }
                                    if (!file.setLastModified(message.lastModified)) {
                                        LOGGER.warn("Failed to setLastModified on file " + file);
                                    }
                                    if (MultiPaperConfiguration.get().syncSettings.files.logFileSyncs) {
                                        LOGGER.info("Downloaded " + file.getPath() + " (" + (file.length() + 1023) / 1024 + "KB)");
                                    }
                                    fileDownloadedFuture.complete(null);
                                });
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            return fileDownloadedFuture;
                        }));
            }
        }

        for (String path : MultiPaperConfiguration.get().syncSettings.files.filesToSyncInRealTime) {
            future = uploadFileIfNeededRecursively(new File(path), future, paths, true);
        }

        for (String path : MultiPaperConfiguration.get().syncSettings.files.filesToSyncOnStartup) {
            future = uploadFileIfNeededRecursively(new File(path), future, paths, false);
        }

        return future;
    }

    private static CompletableFuture<Void> uploadFileIfNeededRecursively(File file, CompletableFuture<Void> future, HashSet<String> paths, boolean immediatelySyncToOtherServers) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                future = uploadFileIfNeededRecursively(child, future, paths, immediatelySyncToOtherServers);
            }
        } else if (syncFile(file) && !paths.contains(getPathWithoutDotSlash(file)) ) {
            for (String toNotSync : MultiPaperConfiguration.get().syncSettings.files.filesToNotSync) {
                if (getPathWithoutDotSlash(file).startsWith(toNotSync)) {
                    return future;
                }
            }

            paths.add(getPathWithoutDotSlash(file));
            return CompletableFuture.allOf(future, sendUpload(new File(getPathWithoutDotSlash(file)), immediatelySyncToOtherServers));
        }
        return future;
    }

    private static boolean syncFile(File file) {
        return file.isFile() && !file.getName().toLowerCase(Locale.ROOT).endsWith(".tmp") && !file.getName().toLowerCase(Locale.ROOT).endsWith(".hprof") && !file.getName().endsWith("~") && !file.getName().toLowerCase(Locale.ROOT).startsWith(".");
    }
}
