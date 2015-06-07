package org.apache.blur.lucene.search;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.blur.trace.Trace;
import org.apache.blur.trace.Tracer;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.Directory;

public abstract class IndexSearcherCloseableBase extends IndexSearcher implements IndexSearcherCloseable {

  private final ExecutorService _executor;

  public IndexSearcherCloseableBase(IndexReader r, ExecutorService executor) {
    super(r, executor);
    _executor = executor;
  }

  public abstract Directory getDirectory();

  @Override
  public abstract void close() throws IOException;

  protected void search(List<AtomicReaderContext> leaves, Weight weight, Collector collector) throws IOException {
    if (collector instanceof CloneableCollector) {
      CloneableCollector cloneableCollector = (CloneableCollector) collector;
      Collector[] collectors = new Collector[leaves.size()];
      int i = 0;

      if (_executor == null) {
        for (AtomicReaderContext ctx : leaves) { // search each subreader
          Collector newCollector = cloneableCollector.newCollector();
          collectors[i++] = newCollector;
          runSearch(weight, newCollector, ctx);
        }
      } else {
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (AtomicReaderContext ctx : leaves) { // search each subreader
          Collector newCollector = cloneableCollector.newCollector();
          collectors[i++] = newCollector;
          Callable<Void> callable = newSearchCallable(weight, newCollector, ctx);
          futures.add(_executor.submit(callable));
        }
        for (Future<Void> future : futures) {
          try {
            future.get();
          } catch (InterruptedException e) {
            throw new IOException(e);
          } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
              throw (IOException) cause;
            } else {
              throw new RuntimeException(cause);
            }
          }
        }
      }
      cloneableCollector.merge(collectors);
    } else {
      for (AtomicReaderContext ctx : leaves) { // search each subreader
        runSearch(weight, collector, ctx);
      }
    }
  }

  private Callable<Void> newSearchCallable(final Weight weight, final Collector collector, final AtomicReaderContext ctx) {
    return new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        runSearch(weight, collector, ctx);
        return null;
      }
    };
  }

  private void runSearch(Weight weight, Collector collector, AtomicReaderContext ctx) throws IOException {
    Tracer trace = Trace.trace("search - internal", Trace.param("AtomicReader", ctx.reader()));
    try {
      try {
        collector.setNextReader(ctx);
      } catch (CollectionTerminatedException e) {
        return;
      }
      Scorer scorer = weight.scorer(ctx, !collector.acceptsDocsOutOfOrder(), true, ctx.reader().getLiveDocs());
      if (scorer != null) {
        try {
          scorer.score(collector);
        } catch (CollectionTerminatedException e) {
          // collection was terminated prematurely
          // continue with the following leaf
        }
      }
    } finally {
      trace.done();
    }
  }

}
