package org.apache.blur.mapreduce.lib;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.TreeSet;

import org.apache.blur.MiniCluster;
import org.apache.blur.server.TableContext;
import org.apache.blur.store.buffer.BufferStore;
import org.apache.blur.store.hdfs.HdfsDirectory;
import org.apache.blur.thrift.generated.TableDescriptor;
import org.apache.blur.utils.JavaHome;
import org.apache.blur.utils.ShardUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.lucene.index.DirectoryReader;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class BlurOutputFormatTest {

  private static Configuration _conf = new Configuration();
  private static FileSystem _fileSystem;
  private static MiniCluster _miniCluster;

  private static Path _root;

  @BeforeClass
  public static void setupTest() throws Exception {
    JavaHome.checkJavaHome();
    File file = new File("./target/tmp/BlurOutputFormatTest_tmp");
    String pathStr = file.getAbsoluteFile().toURI().toString();
    String hdfsPath = pathStr + "/hdfs";
    System.setProperty("test.build.data", hdfsPath);
    System.setProperty("hadoop.log.dir", pathStr + "/hadoop_log");

    _miniCluster = new MiniCluster();
    _miniCluster.startDfs(hdfsPath);
    _fileSystem = _miniCluster.getFileSystem();
    _root = new Path(_fileSystem.getUri() + "/testroot");
    _miniCluster.startMrMiniCluster();
    _conf = _miniCluster.getMRConfiguration();

    BufferStore.initNewBuffer(128, 128 * 128);
  }

  @AfterClass
  public static void teardown() throws IOException {
    if (_miniCluster != null) {
      _miniCluster.stopMrMiniCluster();
      _miniCluster.shutdownDfs();
    }
    rm(new File("build"));
  }

  private static void rm(File file) {
    if (!file.exists()) {
      return;
    }
    if (file.isDirectory()) {
      for (File f : file.listFiles()) {
        rm(f);
      }
    }
    file.delete();
  }

  @Before
  public void setup() {
    TableContext.clear();
  }

  @Test
  public void testBlurOutputFormat() throws IOException, InterruptedException, ClassNotFoundException {
    Path input = getInDir();
    Path output = getOutDir();
    _fileSystem.delete(input, true);
    _fileSystem.delete(output, true);
    writeRecordsFile(new Path(input, "part1"), 1, 1, 1, 1, "cf1");
    writeRecordsFile(new Path(input, "part2"), 1, 1, 2, 1, "cf1");

    Job job = Job.getInstance(_conf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TextInputFormat.class);

    FileInputFormat.addInputPath(job, input);
    CsvBlurMapper.addColumns(job, "cf1", "col");

    Path tablePath = new Path(new Path(_root, "table"), "test");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(1);
    tableDescriptor.setTableUri(tablePath.toString());
    tableDescriptor.setName("test");

    createShardDirectories(tablePath, 1);

    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setOutputPath(job, output);

    assertTrue(job.waitForCompletion(true));
    Counters ctrs = job.getCounters();
    System.out.println("Counters: " + ctrs);

    Path path = new Path(output, ShardUtil.getShardName(0));
    dump(path, _conf);
    Collection<Path> commitedTasks = getCommitedTasks(path);
    assertEquals(1, commitedTasks.size());
    DirectoryReader reader = DirectoryReader.open(new HdfsDirectory(_conf, commitedTasks.iterator().next()));
    assertEquals(2, reader.numDocs());
    reader.close();
  }

  private Path getOutDir() {
    return new Path(_root, "out");
  }

  private Path getInDir() {
    return new Path(_root, "in");
  }

  private void dump(Path path, Configuration conf) throws IOException {
    FileSystem fileSystem = path.getFileSystem(conf);
    System.out.println(path);
    if (!fileSystem.isFile(path)) {
      FileStatus[] listStatus = fileSystem.listStatus(path);
      for (FileStatus fileStatus : listStatus) {
        dump(fileStatus.getPath(), conf);
      }
    }
  }

  private Collection<Path> getCommitedTasks(Path path) throws IOException {
    Collection<Path> result = new TreeSet<Path>();
    FileSystem fileSystem = path.getFileSystem(_conf);
    FileStatus[] listStatus = fileSystem.listStatus(path);
    for (FileStatus fileStatus : listStatus) {
      Path p = fileStatus.getPath();
      if (fileStatus.isDir() && p.getName().endsWith(".commit")) {
        result.add(p);
      }
    }
    return result;
  }

  @Test
  public void testBlurOutputFormatOverFlowTest() throws IOException, InterruptedException, ClassNotFoundException {
    Path input = getInDir();
    Path output = getOutDir();
    _fileSystem.delete(input, true);
    _fileSystem.delete(output, true);
    // 1500 * 50 = 75,000
    writeRecordsFile(new Path(input, "part1"), 1, 50, 1, 1500, "cf1");
    // 100 * 50 = 5,000
    writeRecordsFile(new Path(input, "part2"), 1, 50, 2000, 100, "cf1");

    Job job = Job.getInstance(_conf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TextInputFormat.class);

    FileInputFormat.addInputPath(job, input);
    CsvBlurMapper.addColumns(job, "cf1", "col");

    Path tablePath = new Path(new Path(_root, "table"), "test");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(1);
    tableDescriptor.setTableUri(tablePath.toString());
    tableDescriptor.setName("test");

    createShardDirectories(tablePath, 1);

    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setOutputPath(job, output);
    BlurOutputFormat.setIndexLocally(job, true);
    BlurOutputFormat.setOptimizeInFlight(job, false);

    assertTrue(job.waitForCompletion(true));
    Counters ctrs = job.getCounters();
    System.out.println("Counters: " + ctrs);

    Path path = new Path(output, ShardUtil.getShardName(0));
    Collection<Path> commitedTasks = getCommitedTasks(path);
    assertEquals(1, commitedTasks.size());

    DirectoryReader reader = DirectoryReader.open(new HdfsDirectory(_conf, commitedTasks.iterator().next()));
    assertEquals(80000, reader.numDocs());
    reader.close();
  }

  @Test
  public void testBlurOutputFormatOverFlowMultipleReducersTest() throws IOException, InterruptedException,
      ClassNotFoundException {
    Path input = getInDir();
    Path output = getOutDir();
    _fileSystem.delete(input, true);
    _fileSystem.delete(output, true);
    // 1500 * 50 = 75,000
    writeRecordsFile(new Path(input, "part1"), 1, 50, 1, 1500, "cf1");
    // 100 * 50 = 5,000
    writeRecordsFile(new Path(input, "part2"), 1, 50, 2000, 100, "cf1");

    Job job = Job.getInstance(_conf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TextInputFormat.class);

    FileInputFormat.addInputPath(job, input);
    CsvBlurMapper.addColumns(job, "cf1", "col");

    Path tablePath = new Path(new Path(_root, "table"), "test");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(2);
    tableDescriptor.setTableUri(tablePath.toString());
    tableDescriptor.setName("test");

    createShardDirectories(output, 2);

    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setOutputPath(job, output);
    BlurOutputFormat.setIndexLocally(job, false);
    BlurOutputFormat.setDocumentBufferStrategy(job, DocumentBufferStrategyHeapSize.class);
    BlurOutputFormat.setMaxDocumentBufferHeapSize(job, 128 * 1024);

    assertTrue(job.waitForCompletion(true));
    Counters ctrs = job.getCounters();
    System.out.println("Counters: " + ctrs);

    long total = 0;
    for (int i = 0; i < tableDescriptor.getShardCount(); i++) {
      Path path = new Path(output, ShardUtil.getShardName(i));
      Collection<Path> commitedTasks = getCommitedTasks(path);
      assertEquals(1, commitedTasks.size());

      DirectoryReader reader = DirectoryReader.open(new HdfsDirectory(_conf, commitedTasks.iterator().next()));
      total += reader.numDocs();
      reader.close();
    }
    assertEquals(80000, total);

  }

  @Test
  public void testBlurOutputFormatOverFlowMultipleReducersWithReduceMultiplierTest() throws IOException,
      InterruptedException, ClassNotFoundException {
    Path input = getInDir();
    Path output = getOutDir();
    _fileSystem.delete(input, true);
    _fileSystem.delete(output, true);

    // 1500 * 50 = 75,000
    writeRecordsFile(new Path(input, "part1"), 1, 50, 1, 1500, "cf1");
    // 100 * 50 = 5,000
    writeRecordsFile(new Path(input, "part2"), 1, 50, 2000, 100, "cf1");

    Job job = Job.getInstance(_conf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TextInputFormat.class);

    FileInputFormat.addInputPath(job, input);
    CsvBlurMapper.addColumns(job, "cf1", "col");

    Path tablePath = new Path(new Path(_root, "table"), "test");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(7);
    tableDescriptor.setTableUri(tablePath.toString());
    tableDescriptor.setName("test");

    createShardDirectories(output, 7);

    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setOutputPath(job, output);
    int multiple = 2;
    BlurOutputFormat.setReducerMultiplier(job, multiple);

    assertTrue(job.waitForCompletion(true));
    Counters ctrs = job.getCounters();
    System.out.println("Counters: " + ctrs);

    long total = 0;
    for (int i = 0; i < tableDescriptor.getShardCount(); i++) {
      Path path = new Path(output, ShardUtil.getShardName(i));
      Collection<Path> commitedTasks = getCommitedTasks(path);
      assertTrue(multiple >= commitedTasks.size());
      for (Path p : commitedTasks) {
        DirectoryReader reader = DirectoryReader.open(new HdfsDirectory(_conf, p));
        total += reader.numDocs();
        reader.close();
      }
    }
    assertEquals(80000, total);

  }

  @Test(expected = IllegalArgumentException.class)
  public void testBlurOutputFormatValidateReducerCount() throws IOException, InterruptedException,
      ClassNotFoundException {

    Path input = getInDir();
    Path output = getOutDir();
    _fileSystem.delete(input, true);
    _fileSystem.delete(output, true);
    writeRecordsFile(new Path(input, "part1"), 1, 1, 1, 1, "cf1");
    writeRecordsFile(new Path(input, "part2"), 1, 1, 2, 1, "cf1");

    Job job = Job.getInstance(_conf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TextInputFormat.class);

    FileInputFormat.addInputPath(job, input);
    CsvBlurMapper.addColumns(job, "cf1", "col");

    Path tablePath = new Path(new Path(_root, "table"), "test");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(1);
    tableDescriptor.setTableUri(tablePath.toString());
    tableDescriptor.setName("test");

    createShardDirectories(getOutDir(), 1);

    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setOutputPath(job, output);
    BlurOutputFormat.setReducerMultiplier(job, 2);
    job.setNumReduceTasks(4);
    job.submit();

  }

  // @TODO this test to fail sometimes due to issues in the MR MiniCluster
  // @Test
  public void testBlurOutputFormatCleanupDuringJobKillTest() throws IOException, InterruptedException,
      ClassNotFoundException {
    Path input = getInDir();
    Path output = getOutDir();
    _fileSystem.delete(input, true);
    _fileSystem.delete(output, true);
    // 1500 * 50 = 75,000
    writeRecordsFile(new Path(input, "part1"), 1, 50, 1, 1500, "cf1");
    // 100 * 5000 = 500,000
    writeRecordsFile(new Path(input, "part2"), 1, 5000, 2000, 100, "cf1");

    Job job = Job.getInstance(_conf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TextInputFormat.class);

    FileInputFormat.addInputPath(job, input);
    CsvBlurMapper.addColumns(job, "cf1", "col");

    Path tablePath = new Path(new Path(_root, "table"), "test");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(2);
    tableDescriptor.setTableUri(tablePath.toString());
    tableDescriptor.setName("test");

    createShardDirectories(getOutDir(), 2);

    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setOutputPath(job, output);
    BlurOutputFormat.setIndexLocally(job, false);

    job.submit();
    boolean killCalled = false;
    while (!job.isComplete()) {
      Thread.sleep(1000);
      System.out.printf("Killed [" + killCalled + "] Map [%f] Reduce [%f]%n", job.mapProgress() * 100,
          job.reduceProgress() * 100);
      if (job.reduceProgress() > 0.7 && !killCalled) {
        job.killJob();
        killCalled = true;
      }
    }

    assertFalse(job.isSuccessful());

    for (int i = 0; i < tableDescriptor.getShardCount(); i++) {
      Path path = new Path(output, ShardUtil.getShardName(i));
      FileSystem fileSystem = path.getFileSystem(job.getConfiguration());
      FileStatus[] listStatus = fileSystem.listStatus(path);
      assertEquals(toString(listStatus), 0, listStatus.length);
    }
  }

  private String toString(FileStatus[] listStatus) {
    if (listStatus == null || listStatus.length == 0) {
      return "";
    }
    String s = "";
    for (FileStatus fileStatus : listStatus) {
      if (s.length() > 0) {
        s += ",";
      }
      s += fileStatus.getPath();
    }
    return s;
  }

  public static String readFile(Path file) throws IOException {
    DataInputStream f = _fileSystem.open(file);
    BufferedReader b = new BufferedReader(new InputStreamReader(f));
    StringBuilder result = new StringBuilder();
    String line = b.readLine();
    while (line != null) {
      result.append(line);
      result.append('\n');
      line = b.readLine();
    }
    b.close();
    return result.toString();
  }

  private Path writeRecordsFile(Path file, int starintgRowId, int numberOfRows, int startRecordId, int numberOfRecords,
      String family) throws IOException {
    _fileSystem.delete(file, false);
    DataOutputStream f = _fileSystem.create(file);
    PrintWriter writer = new PrintWriter(f);
    for (int row = 0; row < numberOfRows; row++) {
      for (int record = 0; record < numberOfRecords; record++) {
        writer.println(getRecord(row + starintgRowId, record + startRecordId, family));
      }
    }
    writer.close();
    return file;
  }

  private void createShardDirectories(Path outDir, int shardCount) throws IOException {
    _fileSystem.mkdirs(outDir);
    for (int i = 0; i < shardCount; i++) {
      _fileSystem.mkdirs(new Path(outDir, ShardUtil.getShardName(i)));
    }
  }

  private String getRecord(int rowId, int recordId, String family) {
    return rowId + "," + recordId + "," + family + ",valuetoindex";
  }
}
