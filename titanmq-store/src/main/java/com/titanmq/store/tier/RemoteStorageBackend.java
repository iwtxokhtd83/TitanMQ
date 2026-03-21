package com.titanmq.store.tier;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Abstraction for remote/object storage backends (S3, GCS, Azure Blob, local filesystem).
 * Implementations handle the actual data transfer to/from cold storage.
 */
public interface RemoteStorageBackend {

    /**
     * Upload a local segment file to remote storage.
     *
     * @param localPath  the local file to upload
     * @param remotePath the destination path in remote storage
     */
    void upload(Path localPath, String remotePath) throws IOException;

    /**
     * Download a segment file from remote storage to a local path.
     *
     * @param remotePath the source path in remote storage
     * @param localPath  the local destination
     */
    void download(String remotePath, Path localPath) throws IOException;

    /**
     * Delete a segment from remote storage.
     */
    void delete(String remotePath) throws IOException;

    /**
     * Check if a remote path exists.
     */
    boolean exists(String remotePath) throws IOException;
}
