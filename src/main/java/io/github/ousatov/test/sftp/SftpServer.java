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

/**
 * Simple SFTP server for testing.
 *
 * @author Oleksii Usatov
 * @since 20.02.2026
 */
public class SftpServer {

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

  public SftpServer(int port) throws IOException {
    fileSystem = MemoryFileSystemBuilder.newLinux().build("FakeSftpServerRule@" + this.hashCode());
    server = SshServer.setUpDefaultServer();
    server.setPort(port);
    server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
    server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
    server.setPasswordAuthenticator((username, password, session) -> true);
    server.setFileSystemFactory(
        new FileSystemFactory() {
          @Override
          public Path getUserHomeDir(SessionContext session) throws IOException {
            return fileSystem.getPath("/"); // or whichever root/home path you want
          }

          @Override
          public FileSystem createFileSystem(SessionContext session) throws IOException {
            return new DoNotClose(fileSystem);
          }
        });
  }

  public int getPortFromServer() {
    this.verifyThatTestIsRunning("call getPort()");
    return this.server.getPort();
  }

  public void start() throws IOException {
    server.start();
  }

  public void stop() throws IOException {
    server.stop();
  }

  public void putFile(String path, String content, Charset encoding) throws IOException {
    byte[] contentAsBytes = content.getBytes(encoding);
    this.putFile(path, contentAsBytes);
  }

  private void verifyThatTestIsRunning(String mode) {
    if (this.fileSystem == null) {
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

  public void putFile(String path, byte[] content) throws IOException {
    this.verifyThatTestIsRunning("upload file");
    Path pathAsObject = this.fileSystem.getPath(path);
    this.ensureDirectoryOfPathExists(pathAsObject);
    Files.write(pathAsObject, content);
  }

  public void putFile(String path, InputStream is) throws IOException {
    this.verifyThatTestIsRunning("upload file");
    Path pathAsObject = this.fileSystem.getPath(path);
    this.ensureDirectoryOfPathExists(pathAsObject);
    Files.copy(is, pathAsObject);
  }

  public void createDirectory(String path) throws IOException {
    this.verifyThatTestIsRunning("create directory");
    Path pathAsObject = this.fileSystem.getPath(path);
    Files.createDirectories(pathAsObject);
  }

  public void createDirectories(String... paths) throws IOException {
    for (String path : paths) {
      this.createDirectory(path);
    }
  }

  public String getFileContent(String path, Charset encoding) throws IOException {
    byte[] content = this.getFileContent(path);
    return new String(content, encoding);
  }

  public byte[] getFileContent(String path) throws IOException {
    this.verifyThatTestIsRunning("download file");
    Path pathAsObject = this.fileSystem.getPath(path);
    return Files.readAllBytes(pathAsObject);
  }

  public boolean existsFile(String path) {
    this.verifyThatTestIsRunning("check existence of file");
    Path pathAsObject = this.fileSystem.getPath(path);
    return Files.exists(pathAsObject) && !Files.isDirectory(pathAsObject);
  }

  public void deleteAllFilesAndDirectories() throws IOException {
    for (Path directory : this.fileSystem.getRootDirectories()) {
      Files.walkFileTree(directory, DELETE_FILES_AND_DIRECTORIES);
    }
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

      // Do nothing
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
