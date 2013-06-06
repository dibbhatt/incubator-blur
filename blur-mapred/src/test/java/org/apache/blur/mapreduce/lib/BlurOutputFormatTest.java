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
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.TreeSet;

import org.apache.blur.store.buffer.BufferStore;
import org.apache.blur.store.hdfs.HdfsDirectory;
import org.apache.blur.thrift.generated.AnalyzerDefinition;
import org.apache.blur.thrift.generated.TableDescriptor;
import org.apache.blur.utils.BlurUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.TestMapReduceLocal.TrackingTextInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.lucene.index.DirectoryReader;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class BlurOutputFormatTest {

  private static Configuration conf = new Configuration();
  private static FileSystem localFs;
  private static MiniMRCluster mr;
  private static Path TEST_ROOT_DIR;
  private static JobConf jobConf;
  private Path outDir = new Path(TEST_ROOT_DIR + "/out");
  private Path inDir = new Path(TEST_ROOT_DIR + "/in");

  @BeforeClass
  public static void setup() throws Exception {
    System.setProperty("test.build.data", "./target/BlurOutputFormatTest/data");
    TEST_ROOT_DIR = new Path(System.getProperty("test.build.data", "/tmp"));
    System.setProperty("hadoop.log.dir", "./target/BlurOutputFormatTest/hadoop_log");
    try {
      localFs = FileSystem.getLocal(conf);
    } catch (IOException io) {
      throw new RuntimeException("problem getting local fs", io);
    }
    mr = new MiniMRCluster(2, "file:///", 3);
    jobConf = mr.createJobConf();
    BufferStore.init(128, 128);
  }

  @AfterClass
  public static void teardown() {
    if (mr != null) {
      mr.shutdown();
    }
  }

  @Test
  public void testBlurOutputFormat() throws IOException, InterruptedException, ClassNotFoundException {
    localFs.delete(inDir, true);
    localFs.delete(outDir, true);
    writeRecordsFile("in/part1", 1, 1, 1, 1, "cf1");
    writeRecordsFile("in/part2", 1, 1, 2, 1, "cf1");

    Job job = new Job(jobConf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TrackingTextInputFormat.class);

    FileInputFormat.addInputPath(job, new Path(TEST_ROOT_DIR + "/in"));
    String tableUri = new Path(TEST_ROOT_DIR + "/out").toString();
    CsvBlurMapper.addColumns(job, "cf1", "col");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(1);
    tableDescriptor.setAnalyzerDefinition(new AnalyzerDefinition());
    tableDescriptor.setTableUri(tableUri);
    
    createShardDirectories(outDir,1);
    
    BlurOutputFormat.setupJob(job, tableDescriptor);

    assertTrue(job.waitForCompletion(true));
    Counters ctrs = job.getCounters();
    System.out.println("Counters: " + ctrs);

    Path path = new Path(tableUri, BlurUtil.getShardName(0));
    Collection<Path> commitedTasks = getCommitedTasks(path);
    assertEquals(1, commitedTasks.size());
    DirectoryReader reader = DirectoryReader.open(new HdfsDirectory(conf, commitedTasks.iterator().next()));
    assertEquals(2, reader.numDocs());
    reader.close();
  }

  private Collection<Path> getCommitedTasks(Path path) throws IOException {
    Collection<Path> result = new TreeSet<Path>();
    FileSystem fileSystem = path.getFileSystem(jobConf);
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
    localFs.delete(new Path(TEST_ROOT_DIR + "/in"), true);
    localFs.delete(new Path(TEST_ROOT_DIR + "/out"), true);

    writeRecordsFile("in/part1", 1, 50, 1, 1500, "cf1"); // 1500 * 50 = 75,000
    writeRecordsFile("in/part2", 1, 50, 2000, 100, "cf1"); // 100 * 50 = 5,000

    Job job = new Job(jobConf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TrackingTextInputFormat.class);

    FileInputFormat.addInputPath(job, new Path(TEST_ROOT_DIR + "/in"));
    String tableUri = new Path(TEST_ROOT_DIR + "/out").toString();
    CsvBlurMapper.addColumns(job, "cf1", "col");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(1);
    tableDescriptor.setAnalyzerDefinition(new AnalyzerDefinition());
    tableDescriptor.setTableUri(tableUri);

    createShardDirectories(outDir,1);
    
    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setIndexLocally(job, true);
    BlurOutputFormat.setOptimizeInFlight(job, false);

    assertTrue(job.waitForCompletion(true));
    Counters ctrs = job.getCounters();
    System.out.println("Counters: " + ctrs);

    Path path = new Path(tableUri, BlurUtil.getShardName(0));
    Collection<Path> commitedTasks = getCommitedTasks(path);
    assertEquals(1, commitedTasks.size());

    DirectoryReader reader = DirectoryReader.open(new HdfsDirectory(conf, commitedTasks.iterator().next()));
    assertEquals(80000, reader.numDocs());
    reader.close();
  }

  @Test
  public void testBlurOutputFormatOverFlowMultipleReducersTest() throws IOException, InterruptedException,
      ClassNotFoundException {
    localFs.delete(new Path(TEST_ROOT_DIR + "/in"), true);
    localFs.delete(new Path(TEST_ROOT_DIR + "/out"), true);

    writeRecordsFile("in/part1", 1, 50, 1, 1500, "cf1"); // 1500 * 50 = 75,000
    writeRecordsFile("in/part2", 1, 50, 2000, 100, "cf1"); // 100 * 50 = 5,000

    Job job = new Job(jobConf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TrackingTextInputFormat.class);

    FileInputFormat.addInputPath(job, new Path(TEST_ROOT_DIR + "/in"));
    String tableUri = new Path(TEST_ROOT_DIR + "/out").toString();
    CsvBlurMapper.addColumns(job, "cf1", "col");
      
    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(2);
    tableDescriptor.setAnalyzerDefinition(new AnalyzerDefinition());
    tableDescriptor.setTableUri(tableUri);
    
    createShardDirectories(outDir,2);
    
    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setIndexLocally(job, false);

    assertTrue(job.waitForCompletion(true));
    Counters ctrs = job.getCounters();
    System.out.println("Counters: " + ctrs);

    long total = 0;
    for (int i = 0; i < tableDescriptor.getShardCount(); i++) {
      Path path = new Path(tableUri, BlurUtil.getShardName(i));
      Collection<Path> commitedTasks = getCommitedTasks(path);
      assertEquals(1, commitedTasks.size());

      DirectoryReader reader = DirectoryReader.open(new HdfsDirectory(conf, commitedTasks.iterator().next()));
      total += reader.numDocs();
      reader.close();
    }
    assertEquals(80000, total);

  }

  @Test
  public void testBlurOutputFormatOverFlowMultipleReducersWithReduceMultiplierTest() throws IOException,
      InterruptedException, ClassNotFoundException {
    localFs.delete(new Path(TEST_ROOT_DIR + "/in"), true);
    localFs.delete(new Path(TEST_ROOT_DIR + "/out"), true);

    writeRecordsFile("in/part1", 1, 50, 1, 1500, "cf1"); // 1500 * 50 = 75,000
    writeRecordsFile("in/part2", 1, 50, 2000, 100, "cf1"); // 100 * 50 = 5,000

    Job job = new Job(jobConf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TrackingTextInputFormat.class);

    FileInputFormat.addInputPath(job, new Path(TEST_ROOT_DIR + "/in"));
    String tableUri = new Path(TEST_ROOT_DIR + "/out").toString();
    CsvBlurMapper.addColumns(job, "cf1", "col");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(7);
    tableDescriptor.setAnalyzerDefinition(new AnalyzerDefinition());
    tableDescriptor.setTableUri(tableUri);

    createShardDirectories(outDir,7);
    
    BlurOutputFormat.setupJob(job, tableDescriptor);
    int multiple = 2;
    BlurOutputFormat.setReducerMultiplier(job, multiple);

    assertTrue(job.waitForCompletion(true));
    Counters ctrs = job.getCounters();
    System.out.println("Counters: " + ctrs);

    long total = 0;
    for (int i = 0; i < tableDescriptor.getShardCount(); i++) {
      Path path = new Path(tableUri, BlurUtil.getShardName(i));
      Collection<Path> commitedTasks = getCommitedTasks(path);
      assertTrue(multiple >= commitedTasks.size());
      for (Path p : commitedTasks) {
        DirectoryReader reader = DirectoryReader.open(new HdfsDirectory(conf, p));
        total += reader.numDocs();
        reader.close();
      }
    }
    assertEquals(80000, total);

  }
  
  @Test (expected = IllegalArgumentException.class)
  public void testBlurOutputFormatValidateReducerCount() throws IOException, InterruptedException, ClassNotFoundException {
    localFs.delete(new Path(TEST_ROOT_DIR + "/in"), true);
    localFs.delete(new Path(TEST_ROOT_DIR + "/out"), true);
    writeRecordsFile("in/part1", 1, 1, 1, 1, "cf1");
    writeRecordsFile("in/part2", 1, 1, 2, 1, "cf1");

    Job job = new Job(jobConf, "blur index");
    job.setJarByClass(BlurOutputFormatTest.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TrackingTextInputFormat.class);

    FileInputFormat.addInputPath(job, new Path(TEST_ROOT_DIR + "/in"));
    String tableUri = new Path(TEST_ROOT_DIR + "/out").toString();
    CsvBlurMapper.addColumns(job, "cf1", "col");

    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(1);
    tableDescriptor.setAnalyzerDefinition(new AnalyzerDefinition());
    tableDescriptor.setTableUri(tableUri);
    
    createShardDirectories(outDir,1);
    
    BlurOutputFormat.setupJob(job, tableDescriptor);
    BlurOutputFormat.setReducerMultiplier(job, 2);
    job.setNumReduceTasks(4);
    job.submit();
    
  }

  public static String readFile(String name) throws IOException {
    DataInputStream f = localFs.open(new Path(TEST_ROOT_DIR + "/" + name));
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

  private Path writeRecordsFile(String name, int starintgRowId, int numberOfRows, int startRecordId,
      int numberOfRecords, String family) throws IOException {
    // "1,1,cf1,val1"
    Path file = new Path(TEST_ROOT_DIR + "/" + name);
    localFs.delete(file, false);
    DataOutputStream f = localFs.create(file);
    PrintWriter writer = new PrintWriter(f);
    for (int row = 0; row < numberOfRows; row++) {
      for (int record = 0; record < numberOfRecords; record++) {
        writer.println(getRecord(row + starintgRowId, record + startRecordId, family));
      }
    }
    writer.close();
    return file;
  }

  private void createShardDirectories(Path outDir, int shardCount) throws IOException{
    localFs.mkdirs(outDir);
    for(int i=0; i<shardCount; i++){
      localFs.mkdirs(new Path(outDir, BlurUtil.getShardName(i)));
    }
  }
  private String getRecord(int rowId, int recordId, String family) {
    return rowId + "," + recordId + "," + family + ",valuetoindex";
  }
}