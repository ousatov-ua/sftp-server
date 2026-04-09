package io.github.ousatov.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SftpServer}.
 *
 * @author Oleksii Usatov
 */
class SftpServerTest {

  private SftpServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new SftpServer(0);
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  @Test
  void serverStartsAndPortIsPositive() {
    assertTrue(server.getPortFromServer() > 0);
  }

  @Test
  void putFileBytes_getFileContentReturnsBytes() throws IOException {
    byte[] data = {1, 2, 3};
    server.putFile("/test.bin", data);
    assertArrayEquals(data, server.getFileContent("/test.bin"));
  }

  @Test
  void putFileString_getFileContentReturnsString() throws IOException {
    server.putFile("/hello.txt", "Hello", StandardCharsets.UTF_8);
    assertEquals("Hello", server.getFileContent("/hello.txt", StandardCharsets.UTF_8));
  }

  @Test
  void putFileInputStream_getFileContentReturnsBytes() throws IOException {
    byte[] data = {10, 20, 30};
    try (InputStream is = new ByteArrayInputStream(data)) {
      server.putFile("/stream.bin", is);
    }
    assertArrayEquals(data, server.getFileContent("/stream.bin"));
  }

  @Test
  void putFileWithNestedPath_autoCreatesParentDirs() throws IOException {
    server.putFile("/a/b/c/nested.txt", "content", StandardCharsets.UTF_8);
    assertTrue(server.existsFile("/a/b/c/nested.txt"));
  }

  @Test
  void createDirectory_existsFileReturnsFalseForDirectory() throws IOException {
    server.createDirectory("/mydir");
    assertFalse(server.existsFile("/mydir"));
  }

  @Test
  void createDirectories_allDirsCreated() throws IOException {
    server.createDirectories("/dirA", "/dirB", "/dirC");
    assertFalse(server.existsFile("/dirA"));
    assertFalse(server.existsFile("/dirB"));
    assertFalse(server.existsFile("/dirC"));
    server.putFile("/dirA/file.txt", new byte[0]);
    assertTrue(server.existsFile("/dirA/file.txt"));
  }

  @Test
  void existsFile_trueForFile_falseForAbsent_falseForDirectory() throws IOException {
    server.putFile("/exists.txt", new byte[] {42});
    assertTrue(server.existsFile("/exists.txt"));
    assertFalse(server.existsFile("/absent.txt"));
    server.createDirectory("/adir");
    assertFalse(server.existsFile("/adir"));
  }

  @Test
  void deleteAllFilesAndDirectories_clearsContent() throws IOException {
    server.putFile("/file1.txt", "data".getBytes(StandardCharsets.UTF_8));
    server.putFile("/sub/file2.txt", "data".getBytes(StandardCharsets.UTF_8));
    server.deleteAllFilesAndDirectories();
    assertFalse(server.existsFile("/file1.txt"));
    assertFalse(server.existsFile("/sub/file2.txt"));
  }

  @Test
  void guardThrows_whenServerNotStarted() throws IOException {
    SftpServer notStarted = new SftpServer(0);
    assertThrows(IllegalStateException.class, () -> notStarted.putFile("/f.txt", new byte[0]));
    notStarted.close();
  }

  @Test
  void sftpUpload_verifyWithGetFileContent() throws Exception {
    byte[] data = {5, 6, 7, 8};
    try (SshClient client = SshClient.setUpDefaultClient()) {
      client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
      client.start();
      try (ClientSession session =
          client
              .connect("user", "localhost", server.getPortFromServer())
              .verify(5, TimeUnit.SECONDS)
              .getSession()) {
        session.addPasswordIdentity("password");
        session.auth().verify(5, TimeUnit.SECONDS);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            OutputStream out =
                sftp.write(
                    "/uploaded.bin", SftpClient.OpenMode.Create, SftpClient.OpenMode.Write)) {
          out.write(data);
        }
      }
      client.stop();
      assertArrayEquals(data, server.getFileContent("/uploaded.bin"));
    }
  }

  @Test
  void sftpDownload_filePreSeededWithPutFile_returnsBytes() throws Exception {
    byte[] expected = {9, 8, 7};
    server.putFile("/download.bin", expected);
    try (SshClient client = SshClient.setUpDefaultClient()) {
      client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
      client.start();
      byte[] actual;
      try (ClientSession session =
          client
              .connect("user", "localhost", server.getPortFromServer())
              .verify(5, TimeUnit.SECONDS)
              .getSession()) {
        session.addPasswordIdentity("password");
        session.auth().verify(5, TimeUnit.SECONDS);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            InputStream in = sftp.read("/download.bin")) {
          actual = in.readAllBytes();
        }
      }
      client.stop();
      assertArrayEquals(expected, actual);
    }
  }
}
