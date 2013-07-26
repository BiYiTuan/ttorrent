package com.turn.ttorrent.tracker;

import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.ClientState;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Torrent;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.*;
import org.apache.log4j.spi.RootLogger;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.*;
import java.net.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

@Test
public class TrackerTest extends TestCase {
  private static final String TEST_RESOURCES = "src/test/resources";
  private Tracker tracker;
  private TempFiles tempFiles;
  private String myLogfile;
  private List<Client> clientList = new ArrayList<Client>();

  @Override
  @BeforeMethod
  protected void setUp() throws Exception {
//    org.apache.log4j.BasicConfigurator.configure();
    final Logger rootLogger = RootLogger.getRootLogger();
    rootLogger.removeAllAppenders();
    rootLogger.setLevel(Level.ALL);
    myLogfile = String.format("test-%d.txt", System.currentTimeMillis());
    final Layout layout = new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN);
    final FileAppender newAppender = new FileAppender(layout, myLogfile);
    rootLogger.addAppender(newAppender);
    super.setUp();
    tempFiles = new TempFiles();
    Torrent.setHashingThreadsCount(1);
    startTracker();
  }

  public void test_share_and_download() throws IOException, NoSuchAlgorithmException, InterruptedException {
    final TrackedTorrent tt = this.tracker.announce(loadTorrent("file1.jar.torrent"));
    assertEquals(0, tt.getPeers().size());

    Client seeder = createClient();
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));

    assertEquals(tt.getHexInfoHash(), seeder.getTorrents().iterator().next().getHexInfoHash());

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(incompleteTorrent("file1.jar.torrent", downloadDir));

    try {
      seeder.share();

      leech.download();

      waitForFileInDir(downloadDir, "file1.jar");
      assertFilesEqual(new File(TEST_RESOURCES + "/parentFiles/file1.jar"), new File(downloadDir, "file1.jar"));
    } finally {
      leech.stop(true);
      seeder.stop(true);
    }
  }

  public void tracker_accepts_torrent_from_seeder() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));

    try {
      seeder.share();

      waitForSeeder(seeder.getTorrents().iterator().next().getInfoHash());

      Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
      assertEquals(1, trackedTorrents.size());

      TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
      Map<String, TrackedPeer> peers = trackedTorrent.getPeers();
      assertEquals(1, peers.size());
      assertTrue(peers.values().iterator().next().isCompleted()); // seed
      assertEquals(1, trackedTorrent.seeders());
      assertEquals(0, trackedTorrent.leechers());
    } finally {
      seeder.stop(true);
    }
  }

  public void tracker_accepts_torrent_from_leech() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(incompleteTorrent("file1.jar.torrent", downloadDir));

    try {
      leech.download();

      waitForPeers(1);

      Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
      assertEquals(1, trackedTorrents.size());

      TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
      Map<String, TrackedPeer> peers = trackedTorrent.getPeers();
      assertEquals(1, peers.size());
      assertFalse(peers.values().iterator().next().isCompleted()); // leech
      assertEquals(0, trackedTorrent.seeders());
      assertEquals(1, trackedTorrent.leechers());
    } finally {
      leech.stop(true);
    }
  }

//  @Test(invocationCount = 50)
  public void tracker_accepts_torrent_from_seeder_plus_leech() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    Client seeder = createClient();
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(incompleteTorrent("file1.jar.torrent", downloadDir));

    try {
      seeder.share();
      leech.download();

      waitForFileInDir(downloadDir, "file1.jar");
    } finally {
      seeder.stop(true);
      leech.stop(true);
    }
  }

//  @Test(invocationCount = 50)
  public void download_multiple_files() throws IOException, NoSuchAlgorithmException, InterruptedException, URISyntaxException {
    int numFiles = 50;
    this.tracker.setAcceptForeignTorrents(true);

    final File srcDir = tempFiles.createTempDir();
    final File downloadDir = tempFiles.createTempDir();

    Client seeder = createClient();
    seeder.share();
    Client leech = null;


    try {
      URL announce = new URL("http://127.0.0.1:6969/announce");
      URI announceURI = announce.toURI();
      final Set<String> names = new HashSet<String>();
      List<File> filesToShare = new ArrayList<File>();
      for (int i = 0; i < numFiles; i++) {
        File tempFile = tempFiles.createTempFile(513 * 1024);
        File srcFile = new File(srcDir, tempFile.getName());
        assertTrue(tempFile.renameTo(srcFile));

        Torrent torrent = Torrent.create(srcFile, announceURI, "Test");
        File torrentFile = new File(srcFile.getParentFile(), srcFile.getName() + ".torrent");
        torrent.save(torrentFile);
        filesToShare.add(srcFile);
        names.add(srcFile.getName());
      }

      for (File f : filesToShare) {
        File torrentFile = new File(f.getParentFile(), f.getName() + ".torrent");
        SharedTorrent st1 = SharedTorrent.fromFile(torrentFile, f.getParentFile(), true);
        seeder.addTorrent(st1);
      }
      leech = createClient();
      leech.share();
      for (File f : filesToShare) {
        File torrentFile = new File(f.getParentFile(), f.getName() + ".torrent");
        SharedTorrent st2 = SharedTorrent.fromFile(torrentFile, downloadDir, true);
        leech.addTorrent(st2);
      }

      new WaitFor(90 * 1000, 100) {
        @Override
        protected boolean condition() {
          return listFileNames(downloadDir).containsAll(names);
        }
      };

      assertTrue(myLogfile, listFileNames(downloadDir).equals(names));
    } finally {
      leech.stop(true);
      seeder.stop(true);
    }
  }

  private Set<String> listFileNames(File downloadDir) {
    if (downloadDir == null) return Collections.emptySet();
    Set<String> names = new HashSet<String>();
    File[] files = downloadDir.listFiles();
    if (files == null) return Collections.emptySet();
    for (File f : files) {
      names.add(f.getName());
    }
    return names;
  }

//  @Test(invocationCount = 50)
  public void large_file_download() throws IOException, URISyntaxException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    File tempFile = tempFiles.createTempFile(201 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    Torrent torrent = Torrent.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    torrent.save(torrentFile);

    Client seeder = createClient();
    seeder.addTorrent(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile(), true));

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(SharedTorrent.fromFile(torrentFile, downloadDir, true));

    try {
      seeder.share();
      leech.download();

      waitForFileInDir(downloadDir, tempFile.getName());
      assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
    } finally {
      seeder.stop(true);
      leech.stop(true);
    }
  }

  public void test_announce() throws IOException, NoSuchAlgorithmException {
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    this.tracker.announce(loadTorrent("file1.jar.torrent"));

    assertEquals(1, this.tracker.getTrackedTorrents().size());
  }

  public void more_than_one_seeder_for_same_torrent() throws IOException, NoSuchAlgorithmException, InterruptedException, URISyntaxException {
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    int numSeeders = 5;
    List<Client> seeders = new ArrayList<Client>();
    for (int i = 0; i < numSeeders; i++) {
      seeders.add(createClient());
    }

    try {
      File tempFile = tempFiles.createTempFile(100 * 1024);

      Torrent torrent = Torrent.create(tempFile, this.tracker.getAnnounceUrl().toURI(), "Test");
      File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
      torrent.save(torrentFile);

      for (int i = 0; i < numSeeders; i++) {
        Client client = seeders.get(i);
        client.addTorrent(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile(), false));
        client.share();
      }

      waitForPeers(numSeeders);

      Collection<TrackedTorrent> torrents = this.tracker.getTrackedTorrents();
      assertEquals(1, torrents.size());
      assertEquals(numSeeders, torrents.iterator().next().seeders());
    } finally {
      for (Client client : seeders) {
        client.stop();
      }
    }

  }

  public void no_full_seeder_test() throws IOException, URISyntaxException, InterruptedException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48*1024; // lower piece size to reduce disk usage
    final int numSeeders = 10;
    final int piecesCount = numSeeders * 3 + 15;

    final List<Client> clientsList;
    clientsList = new ArrayList<Client>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      File tempFile = tempFiles.createTempFile(piecesCount * pieceSize);

      createMultipleSeedersWithDifferentPieces(tempFile, piecesCount, pieceSize, numSeeders, clientsList);
      String baseMD5 = getFileMD5(tempFile, md5);

      validateMultipleClientsResults(clientsList, md5, tempFile, baseMD5);

    } finally {
      for (Client client : clientsList) {
        client.stop();
      }
    }
  }

  public void testDelete(){

  }
//  @Test(invocationCount = 50)
/*
  public void bad_seeder() throws NoSuchAlgorithmException, IOException, URISyntaxException, InterruptedException {
//    throw new SkipTestException();
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48*1024; // lower piece size to reduce disk usage
    final int numSeeders = 5;
    final int piecesCount = numSeeders +7;

    final List<Client> clientsList;
    clientsList = new ArrayList<Client>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      File baseFile = tempFiles.createTempFile(piecesCount * pieceSize);

      createMultipleSeedersWithDifferentPieces(baseFile, piecesCount, pieceSize, numSeeders, clientsList);
      String baseMD5 = getFileMD5(baseFile, md5);
      Client firstClient = clientsList.get(0);
      final SharedTorrent torrent = firstClient.getTorrents().iterator().next();
      {
        File file = new File(torrent.getParentFile(), torrent.getFilenames().get(0));
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        raf.seek(0);
        final int read = raf.read();
        raf.seek(0);
        // replacing the byte
        if (read != 35) {
          raf.write(35);
        } else {
          raf.write(45);
        }
        raf.close();
      }

      {
        byte[] piece = new byte[pieceSize];
        FileInputStream fin = new FileInputStream(baseFile);
        fin.read(piece);
        fin.close();

        final File baseDir = tempFiles.createTempDir();
        final File seederPiecesFile = new File(baseDir, baseFile.getName());
        RandomAccessFile raf = new RandomAccessFile(seederPiecesFile, "rw");
        raf.setLength(baseFile.length());
        raf.seek(0);
        raf.write(piece);
        Client client = createClient();
        clientsList.add(client);
        client.addTorrent(new SharedTorrent(torrent, baseDir, false));
        client.share();
      }

      validateMultipleClientsResults(clientsList, md5, baseFile, baseMD5);

    } finally {
      for (Client client : clientsList) {
        client.stop();
      }
    }
  }
*/

//  @Test(invocationCount = 50)
  public void corrupted_seeder_repair()  throws NoSuchAlgorithmException, IOException, URISyntaxException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48*1024; // lower piece size to reduce disk usage
    final int numSeeders = 6;
    final int piecesCount = numSeeders +7;

    final List<Client> clientsList;
    clientsList = new ArrayList<Client>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      File baseFile = tempFiles.createTempFile(piecesCount * pieceSize);

      createMultipleSeedersWithDifferentPieces(baseFile, piecesCount, pieceSize, numSeeders, clientsList);
      String baseMD5 = getFileMD5(baseFile, md5);
      Client firstClient = clientsList.get(0);
      final SharedTorrent torrent = firstClient.getTorrents().iterator().next();
      final File file = new File(torrent.getParentFile(), torrent.getFilenames().get(0));
      final int oldByte;
      {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        raf.seek(0);
        oldByte = raf.read();
        raf.seek(0);
        // replacing the byte
        if (oldByte != 35) {
          raf.write(35);
        } else {
          raf.write(45);
        }
        raf.close();
      }
      final WaitFor waitFor = new WaitFor(30 * 1000, 1000) {
        @Override
        protected boolean condition() {
          for (Client client : clientsList) {
            final SharedTorrent next = client.getTorrents().iterator().next();
            if (next.getCompletedPieces().cardinality() < next.getPieceCount()-1){
              return false;
            }
          }
          return true;
        }
      };

      if (!waitFor.isMyResult()){
        fail("All seeders didn't get their files:" + myLogfile);
      }
      Thread.sleep(10*1000);
      {
        byte[] piece = new byte[pieceSize];
        FileInputStream fin = new FileInputStream(baseFile);
        fin.read(piece);
        fin.close();
        RandomAccessFile raf;
        try {
          raf = new RandomAccessFile(file, "rw");
          raf.seek(0);
          raf.write(oldByte);
          raf.close();
        } catch (FileNotFoundException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }

      validateMultipleClientsResults(clientsList, md5, baseFile, baseMD5);

    } finally {
      for (Client client : clientsList) {
        client.stop();
      }
    }
  }

  public void unlock_file_when_no_leechers() throws InterruptedException, NoSuchAlgorithmException, IOException {
    Client seeder = createClient();
    tracker.setAcceptForeignTorrents(true);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 7);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.share();

    downloadAndStop(torrent, 15*1000, createClient());
    Thread.sleep(2*1000);
    assertTrue(dwnlFile.exists() && dwnlFile.isFile());
    dwnlFile.delete();
    assertFalse(dwnlFile.exists());
  }

  public void download_many_times() throws InterruptedException, NoSuchAlgorithmException, IOException {
    Client seeder = createClient();
    tracker.setAcceptForeignTorrents(true);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 7);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.share();

    for(int i=0; i<5; i++) {
      downloadAndStop(torrent, 1000*1000, createClient());
      Thread.sleep(5*1000);
    }
  }

  public void download_io_error() throws InterruptedException, NoSuchAlgorithmException, IOException{
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 14);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.share();

    for(int i=0; i<5; i++) {
      final AtomicInteger interrupts = new AtomicInteger(0);
      final Client leech = new Client(InetAddress.getLocalHost()){
        @Override
        public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
          super.handlePieceCompleted(peer, piece);
          if (piece.getIndex()%4==0 && interrupts.incrementAndGet() <= 2){
            peer.getSocketChannel().close();
          }
        }
      };
      //manually add leech here for graceful shutdown.
      clientList.add(leech);
      downloadAndStop(torrent, 45*1000, leech);
      Thread.sleep(2*1000);
    }

  }

  private void downloadAndStop(Torrent torrent, long timeout, final Client leech) throws IOException, NoSuchAlgorithmException, InterruptedException {
    final File tempDir = tempFiles.createTempDir();
    leech.addTorrent(new SharedTorrent(torrent, tempDir, false));
    leech.download();

    final WaitFor waitFor = new WaitFor(timeout, 2000) {
      @Override
      protected boolean condition() {
        final SharedTorrent leechTorrent = leech.getTorrents().iterator().next();
        if (leech.isRunning()) {
          return leechTorrent.getClientState() == ClientState.SEEDING;
        } else {
          return true;
        }
      }
    };

    assertTrue("File wasn't downloaded in time", waitFor.isMyResult());
  }

  private void validateMultipleClientsResults(final List<Client> clientsList, MessageDigest md5, File baseFile, String baseMD5) throws IOException {
    final WaitFor waitFor = new WaitFor(5000 * 1000, 1000) {
      @Override
      protected boolean condition() {
        boolean retval = true;
        for (Client client : clientsList) {
          if (!retval) return false;
          final boolean torrentState = client.getTorrents().iterator().next().getClientState() == ClientState.SEEDING;
          retval = retval && torrentState;
        }
        return retval;
      }
    };

    if (!waitFor.isMyResult()){
      fail("All seeders didn't get their files:" + myLogfile);
    } else {
      // check file contents here:
      for (Client client : clientsList) {
        final SharedTorrent st = client.getTorrents().iterator().next();
        final File file = new File(st.getParentFile(), st.getFilenames().get(0));
        assertEquals(String.format("MD5 hash is invalid. C:%s, O:%s ",
          file.getAbsolutePath(), baseFile.getAbsolutePath()), baseMD5, getFileMD5(file, md5));
      }
    }
  }

  private void createMultipleSeedersWithDifferentPieces(File baseFile, int piecesCount, int pieceSize, int numSeeders,
       List<Client> clientList) throws IOException, InterruptedException, NoSuchAlgorithmException, URISyntaxException {

    List<byte[]> piecesList = new ArrayList<byte[]>(piecesCount);
    FileInputStream fin = new FileInputStream(baseFile);
    for (int i=0; i<piecesCount; i++){
      byte[] piece = new byte[pieceSize];
      fin.read(piece);
      piecesList.add(piece);
    }
    fin.close();

    final long torrentFileLength = baseFile.length();
    Torrent torrent = Torrent.create(baseFile, null, this.tracker.getAnnounceUrl().toURI(), null,  "Test", pieceSize);
    File torrentFile = new File(baseFile.getParentFile(), baseFile.getName() + ".torrent");
    torrent.save(torrentFile);


    for (int i=0; i<numSeeders; i++){
      final File baseDir = tempFiles.createTempDir();
      final File seederPiecesFile = new File(baseDir, baseFile.getName());
      RandomAccessFile raf = new RandomAccessFile(seederPiecesFile, "rw");
      raf.setLength(torrentFileLength);
      for (int pieceIdx=i; pieceIdx<piecesCount; pieceIdx += numSeeders){
        raf.seek(pieceIdx*pieceSize);
        raf.write(piecesList.get(pieceIdx));
      }
      Client client = createClient();
      clientList.add(client);
      client.addTorrent(new SharedTorrent(torrent, baseDir, false));
      client.share();
    }
  }

  private String getFileMD5(File file, MessageDigest digest) throws IOException {
    DigestInputStream dIn = new DigestInputStream(new FileInputStream(file), digest);
    while (dIn.read() >= 0);
    return dIn.getMessageDigest().toString();
  }

  private void waitForSeeder(final byte[] torrentHash) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        for (TrackedTorrent tt : TrackerTest.this.tracker.getTrackedTorrents()) {
          if (tt.seeders() == 1 && tt.getHexInfoHash().equals(Torrent.byteArrayToHexString(torrentHash))) return true;
        }

        return false;
      }
    };
  }

  private void waitForPeers(final int numPeers) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        for (TrackedTorrent tt : TrackerTest.this.tracker.getTrackedTorrents()) {
          if (tt.getPeers().size() == numPeers) return true;
        }

        return false;
      }
    };
  }

  private void waitForFileInDir(final File downloadDir, final String fileName) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        return new File(downloadDir, fileName).isFile();
      }
    };

    assertTrue(this.myLogfile, new File(downloadDir, fileName).isFile());
  }

  private TrackedTorrent loadTorrent(String name) throws IOException, NoSuchAlgorithmException {
    return new TrackedTorrent(Torrent.load(new File(TEST_RESOURCES + "/torrents", name)));
  }


  @Override
  @AfterMethod
  protected void tearDown() throws Exception {
    super.tearDown();
    stopTracker();
    for (Client client : clientList) {
      client.stop();
    }
    tempFiles.cleanup();
  }

  private void startTracker() throws IOException {
    this.tracker = new Tracker(new InetSocketAddress(6969));
    this.tracker.start();
  }

  private Client createClient() throws IOException, NoSuchAlgorithmException, InterruptedException {
    final Client client = new Client(InetAddress.getLocalHost());
    clientList.add(client);
    return client;
  }

  private SharedTorrent completeTorrent(String name) throws IOException, NoSuchAlgorithmException {
    File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    return SharedTorrent.fromFile(torrentFile, parentFiles, false);
  }

  private SharedTorrent incompleteTorrent(String name, File destDir) throws IOException, NoSuchAlgorithmException {
    File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
    return SharedTorrent.fromFile(torrentFile, destDir, false);
  }

  private void stopTracker() {
    this.tracker.stop();
  }

  private void assertFilesEqual(File f1, File f2) throws IOException {
    assertEquals("Files size differs", f1.length(), f2.length());
    Checksum c1 = FileUtils.checksum(f1, new CRC32());
    Checksum c2 = FileUtils.checksum(f2, new CRC32());
    assertEquals(c1.getValue(), c2.getValue());
  }
}
