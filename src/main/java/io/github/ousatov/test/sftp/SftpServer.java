package io.github.ousatov.test.sftp;

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Set;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple in-memory SFTP server for testing. Accepts all credentials.
 *
 * @author Oleksii Usatov
 */
public class SftpServer implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SftpServer.class);

  private static final SimpleFileVisitor<Path> DELETE_FILES_AND_DIRECTORIES =
      new SimpleFileVisitor<>() {

        @Override
        public @NonNull FileVisitResult visitFile(
            @NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public @NonNull FileVisitResult postVisitDirectory(Path dir, IOException exc)
            throws IOException {
          if (dir.getParent() != null) {
            Files.delete(dir);
          }
          return super.postVisitDirectory(dir, exc);
        }
      };

  private final SshServer server;
  private final FileSystem fileSystem;
  private boolean started;

  /**
   * Package-private constructor allowing a custom {@link FileSystem} (supports DIP).
   *
   * @param port the port to listen on (0 for OS-assigned)
   * @param fileSystem the backing filesystem
   * @throws IOException if the SSH server cannot be configured
   */
  SftpServer(int port, FileSystem fileSystem) throws IOException {
    this.fileSystem = fileSystem;
    server = SshServer.setUpDefaultServer();
    server.setPort(port);
    server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
    server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
    server.setPasswordAuthenticator((username, password, session) -> true);
    server.setFileSystemFactory(
        new FileSystemFactory() {
          @Override
          public Path getUserHomeDir(SessionContext session) {
            return fileSystem.getPath("/");
          }

          @Override
          public FileSystem createFileSystem(SessionContext session) {
            return new DoNotClose(fileSystem);
          }
        });
  }

  /**
   * Creates an SFTP server with a Linux in-memory filesystem.
   *
   * @param port the port to listen on (0 for OS-assigned)
   * @throws IOException if the filesystem or SSH server cannot be initialized
   */
  public SftpServer(int port) throws IOException {
    this(port, MemoryFileSystemBuilder.newLinux().build("FakeSftpServer@" + System.nanoTime()));
  }

  /**
   * @return the port the server is bound to; must be called after {@link #start()}
   */
  public int getPortFromServer() {
    this.verifyThatTestIsRunning("call getPort()");
    return this.server.getPort();
  }

  /**
   * Starts the SSH/SFTP server.
   *
   * @throws IOException if the server cannot bind to the port
   */
  public void start() throws IOException {
    server.start();
    started = true;
    LOGGER.info("SFTP server started on port {}", server.getPort());
  }

  /**
   * Stops the SSH server without closing the underlying filesystem.
   *
   * @throws IOException if the server cannot be stopped
   */
  @SuppressWarnings("unused")
  public void stop() throws IOException {
    server.stop();
    started = false;
    LOGGER.info("SFTP server stopped");
  }

  /**
   * Stops the server and releases the underlying filesystem.
   *
   * @throws IOException if stop or filesystem close fails
   */
  @Override
  public void close() throws IOException {
    server.stop();
    fileSystem.close();
    LOGGER.info("SFTP server closed");
  }

  /**
   * Puts a file at the given path with text content.
   *
   * @param path target path
   * @param content text content
   * @param encoding character encoding
   * @throws IOException if the writing fails
   */
  public void putFile(String path, String content, Charset encoding) throws IOException {
    byte[] contentAsBytes = content.getBytes(encoding);
    this.putFile(path, contentAsBytes);
  }

  /**
   * Puts a file at the given path with raw byte content.
   *
   * @param path target path
   * @param content raw bytes
   * @throws IOException if the writing fails
   */
  public void putFile(String path, byte[] content) throws IOException {
    Path pathAsObject = this.preparePath(path);
    Files.write(pathAsObject, content);
  }

  /**
   * Puts a file at the given path by copying from an input stream.
   *
   * @param path target path
   * @param is source stream
   * @throws IOException if the copy fails
   */
  public void putFile(String path, InputStream is) throws IOException {
    Path pathAsObject = this.preparePath(path);
    Files.copy(is, pathAsObject);
  }

  /**
   * Creates a single directory, including any missing parents.
   *
   * @param path directory path
   * @throws IOException if creation fails
   */
  public void createDirectory(String path) throws IOException {
    this.verifyThatTestIsRunning("create directory");
    LOGGER.debug("Creating directory: {}", path);
    Path pathAsObject = this.fileSystem.getPath(path);
    Files.createDirectories(pathAsObject);
  }

  /**
   * Creates multiple directories.
   *
   * @param paths directory paths
   * @throws IOException if any creation fails
   */
  public void createDirectories(String... paths) throws IOException {
    for (String path : paths) {
      this.createDirectory(path);
    }
  }

  /**
   * Returns the text content of a file.
   *
   * @param path file path
   * @param encoding character encoding
   * @return decoded file content
   * @throws IOException if the read fails
   */
  public String getFileContent(String path, Charset encoding) throws IOException {
    byte[] content = this.getFileContent(path);
    return new String(content, encoding);
  }

  /**
   * Returns the raw byte content of a file.
   *
   * @param path file path
   * @return file bytes
   * @throws IOException if the read fails
   */
  public byte[] getFileContent(String path) throws IOException {
    this.verifyThatTestIsRunning("download file");
    Path pathAsObject = this.fileSystem.getPath(path);
    return Files.readAllBytes(pathAsObject);
  }

  /**
   * Returns {@code true} if a regular file (not a directory) exists at the given path.
   *
   * @param path path to check
   * @return {@code true} if a file exists, {@code false} otherwise
   */
  public boolean existsFile(String path) {
    this.verifyThatTestIsRunning("check existence of file");
    Path pathAsObject = this.fileSystem.getPath(path);
    return Files.exists(pathAsObject) && !Files.isDirectory(pathAsObject);
  }

  /**
   * Deletes all files and directories, leaving the filesystem root intact.
   *
   * @throws IOException if deletion fails
   */
  public void deleteAllFilesAndDirectories() throws IOException {
    this.verifyThatTestIsRunning("delete all files and directories");
    LOGGER.debug("Deleting all files and directories");
    for (Path directory : this.fileSystem.getRootDirectories()) {
      Files.walkFileTree(directory, DELETE_FILES_AND_DIRECTORIES);
    }
  }

  private void verifyThatTestIsRunning(String mode) {
    if (!started) {
      throw new IllegalStateException(
          "Failed to " + mode + " because test has not been started or is already finished.");
    }
  }

  private void ensureDirectoryOfPathExists(Path path) throws IOException {
    Path directory = path.getParent();
    if (directory != null && !directory.equals(path.getRoot())) {
      Files.createDirectories(directory);
    }
  }

  /**
   * Resolves a string path and ensures its parent directory exists.
   *
   * @param path the target file path
   * @return the resolved {@link Path}
   * @throws IOException if parent directory creation fails
   */
  private Path preparePath(String path) throws IOException {
    this.verifyThatTestIsRunning("upload file");
    LOGGER.debug("Putting file: {}", path);
    Path pathAsObject = this.fileSystem.getPath(path);
    this.ensureDirectoryOfPathExists(pathAsObject);
    return pathAsObject;
  }

  private static class DoNotClose extends FileSystem {

    final FileSystem fileSystem;

    DoNotClose(FileSystem fileSystem) {
      this.fileSystem = fileSystem;
    }

    public FileSystemProvider provider() {
      return this.fileSystem.provider();
    }

    public void close() {
      // Do nothing — prevents SSHD from closing the shared filesystem
    }

    public boolean isOpen() {
      return this.fileSystem.isOpen();
    }

    public boolean isReadOnly() {
      return this.fileSystem.isReadOnly();
    }

    public String getSeparator() {
      return this.fileSystem.getSeparator();
    }

    public Iterable<Path> getRootDirectories() {
      return this.fileSystem.getRootDirectories();
    }

    public Iterable<FileStore> getFileStores() {
      return this.fileSystem.getFileStores();
    }

    public Set<String> supportedFileAttributeViews() {
      return this.fileSystem.supportedFileAttributeViews();
    }

    public @NonNull Path getPath(@NonNull String first, String @NonNull ... more) {
      return this.fileSystem.getPath(first, more);
    }

    public PathMatcher getPathMatcher(String syntaxAndPattern) {
      return this.fileSystem.getPathMatcher(syntaxAndPattern);
    }

    public UserPrincipalLookupService getUserPrincipalLookupService() {
      return this.fileSystem.getUserPrincipalLookupService();
    }

    public WatchService newWatchService() throws IOException {
      return this.fileSystem.newWatchService();
    }
  }
}
