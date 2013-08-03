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
import java.io.IOException;

import org.apache.blur.thirdparty.thrift_0_9_0.TException;
import org.apache.blur.thrift.BlurClient;
import org.apache.blur.thrift.generated.Blur.Iface;
import org.apache.blur.thrift.generated.BlurException;
import org.apache.blur.thrift.generated.TableDescriptor;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

public class CsvBlurDriver {

  public static void main(String[] args) throws IOException, InterruptedException, ClassNotFoundException,
      BlurException, TException {
    Configuration configuration = new Configuration();
    String[] otherArgs = new GenericOptionsParser(configuration, args).getRemainingArgs();
    if (otherArgs.length != 5) {
      System.err
          .println("Usage: csvindexer <thrift controller connection str> <tablename> <column family definitions> <auto generate record id> <in>");
      System.exit(2);
    }
    int c = 0;
    final String controllerConnectionStr = otherArgs[c++];
    final String tableName = otherArgs[c++];
    final String columnDefs = otherArgs[c++];
    final Boolean autoGenerateRecordIds = Boolean.parseBoolean(otherArgs[c++]);
    final String input = otherArgs[c++];

    final Iface client = BlurClient.getClient(controllerConnectionStr);
    TableDescriptor tableDescriptor = client.describe(tableName);
    
    Job job = new Job(configuration, "Blur indexer [" + tableName + "] [" + input + "]");
    CsvBlurMapper.setAutoGenerateRecordIdAsHashOfData(job, autoGenerateRecordIds);
    job.setJarByClass(CsvBlurDriver.class);
    job.setMapperClass(CsvBlurMapper.class);
    job.setInputFormatClass(TextInputFormat.class);
    
    FileInputFormat.addInputPath(job, new Path(input));
    CsvBlurMapper.setColumns(job, columnDefs);
    BlurOutputFormat.setupJob(job, tableDescriptor);
    
//    CsvBlurCombineFileInputFormat

    boolean waitForCompletion = job.waitForCompletion(true);
    System.exit(waitForCompletion ? 0 : 1);
  }
  
}
