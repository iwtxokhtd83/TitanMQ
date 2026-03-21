package com.titanmq.store.tier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local filesystem implementation of RemoteStorageBackend.
 * Useful for development, testing, and single-node deployments where
 * cold storage is simply a different disk/mount point.
 */
public class LocalFileStorageBackend implements RemoteStorageBackend {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageBackend.class);

    private final Path baseDir;

    public LocalFileStorageBackend(Path baseDir) {
        this.baseDir = baseDir;
        baseDir.toFile().mkdirs();
    }

    @Override
    public void upload(Path localPath, String remotePath) throws IOException {
        Path target = baseDir.resolve(remotePath);
        target.getParent().toFile().mkdirs();
        Files.copy(localPath, target, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Uploaded {} -> {}", localPath, target);
    }

    @Override
    public void download(String remotePath, Path localPath) throws IOException {
        Path source = baseDir.resolve(remotePath);
        localPath.getParent().toFile().mkdirs();
        Files.copy(source, localPath, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Downloaded {} -> {}", source, localPath);
    }

    @Override
    public void delete(String remotePath) throws IOException {
        Path target = baseDir.resolve(remotePath);
        Files.deleteIfExists(target);
        log.debug("Deleted {}", target);
    }

    @Override
    public boolean exists(String remotePath) throws IOException {
        return Files.exists(baseDir.resolve(remotePath));
    }
}
