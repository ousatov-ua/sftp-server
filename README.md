# sftp-server

[![Build](https://github.com/ousatov-ua/sftp-server/actions/workflows/maven.yml/badge.svg)](https://github.com/ousatov-ua/sftp-server/actions/workflows/maven.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.ousatov-ua/sftp-server)](https://central.sonatype.com/artifact/io.github.ousatov-ua/sftp-server)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Visitors](https://visitor-badge.laobi.icu/badge?page_id=ousatov-ua.sftp-server)](https://github.com/ousatov-ua/sftp-server)
[![GitHub commits](https://img.shields.io/github/commit-activity/t/ousatov-ua/sftp-server)](https://github.com/ousatov-ua/sftp-server/commits/main)
[![GitHub last commit](https://img.shields.io/github/last-commit/ousatov-ua/sftp-server)](https://github.com/ousatov-ua/sftp-server/commits/main)

A lightweight, zero-config **in-memory SFTP server** for use in JUnit tests. No temp files, no
open ports left behind — just a real SFTP server that lives entirely in RAM and vanishes when your
test finishes.

---

## Why use it?

Testing code that talks SFTP usually means spinning up a real server, managing credentials, or
wrestling with Docker. This library gives you a **fully functional SFTP server** that:

- 🚀 Starts in milliseconds
- 🧹 Leaves no files on disk — everything is in memory
- 🔑 Accepts any username and password out of the box
- 🔌 Works on a random OS-assigned port — no conflicts between parallel tests
- ✅ Compatible with any standard SFTP client library

---

## Installation

### Maven

```xml
<dependency>
  <groupId>io.github.ousatov-ua</groupId>
  <artifactId>sftp-server</artifactId>
  <version><!-- see latest on Maven Central --></version>
  <scope>test</scope>
</dependency>
```

### Gradle

```groovy
testImplementation 'io.github.ousatov-ua:sftp-server:<version>'
```

---

## Quick start

### 1. Using try-with-resources (recommended)

```java
@Test
void myTest() throws Exception {
    try (SftpServer server = new SftpServer(0)) { // port 0 = OS picks a free port
        server.start();

        // pre-seed the filesystem
        server.putFile("/data/report.csv", "id,name\n1,Alice", StandardCharsets.UTF_8);

        // your production code connects and downloads the file
        myService.fetchReport("localhost", server.getPortFromServer());

        // assert the outcome
        assertEquals("id,name\n1,Alice",
            server.getFileContent("/data/report.csv", StandardCharsets.UTF_8));
    }
}
```

### 2. Using JUnit 5 lifecycle (for multiple tests)

```java
class MyIntegrationTest {

    private SftpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new SftpServer(0);
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close(); // stops server + releases memory filesystem
    }

    @Test
    void uploadFile_thenExistsOnServer() throws IOException {
        server.putFile("/upload/hello.txt", "Hello, SFTP!", StandardCharsets.UTF_8);
        assertTrue(server.existsFile("/upload/hello.txt"));
    }

    @Test
    void downloadFile_returnsExpectedContent() throws IOException {
        byte[] expected = {1, 2, 3};
        server.putFile("/files/data.bin", expected);
        assertArrayEquals(expected, server.getFileContent("/files/data.bin"));
    }
}
```

---

## API reference

| Method | Description |
|--------|-------------|
| `new SftpServer(int port)` | Creates a server on the given port (`0` = OS-assigned) |
| `start()` | Starts the SSH/SFTP daemon |
| `stop()` | Stops the daemon, keeps the in-memory filesystem |
| `close()` | Stops the daemon **and** releases the filesystem (use in `@AfterEach`) |
| `getPortFromServer()` | Returns the actual bound port (call after `start()`) |
| `putFile(path, byte[])` | Seeds a file from a byte array |
| `putFile(path, String, Charset)` | Seeds a file from a string |
| `putFile(path, InputStream)` | Seeds a file from a stream |
| `getFileContent(path)` | Reads a file as bytes |
| `getFileContent(path, Charset)` | Reads a file as a string |
| `existsFile(path)` | Returns `true` if a regular file exists at the path |
| `createDirectory(path)` | Creates a directory (including missing parents) |
| `createDirectories(paths...)` | Creates multiple directories |
| `deleteAllFilesAndDirectories()` | Wipes all content, leaves the root intact |

> **Tip:** Parent directories are created automatically when you call `putFile` with a nested path
> like `/a/b/c/file.txt`.

---

## Connecting with an SFTP client

The server accepts **any username and password**. Here is an example using the Apache SSHD client
(already on the classpath as a transitive dependency):

```java
SshClient client = SshClient.setUpDefaultClient();
client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
client.start();

try (ClientSession session = client.connect("user", "localhost", server.getPortFromServer())
        .verify(5, TimeUnit.SECONDS).getSession()) {
    session.addPasswordIdentity("any-password");
    session.auth().verify(5, TimeUnit.SECONDS);

    try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
        // upload
        try (OutputStream out = sftp.write("/remote/file.txt",
                SftpClient.OpenMode.Create, SftpClient.OpenMode.Write)) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        // download
        try (InputStream in = sftp.read("/remote/file.txt")) {
            byte[] content = in.readAllBytes();
        }
    }
}
client.stop();
```

---

## Requirements

| Component | Version  |
|-----------|----------|
| Java | 25+      |
| Apache SSHD | 3.0.0-M2 |
| SLF4J API | 1.7.36   |

---

## License

[MIT](https://opensource.org/licenses/MIT) — free to use in any project, commercial or otherwise.
