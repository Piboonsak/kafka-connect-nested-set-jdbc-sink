/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.findinpath.connect.nestedset.jdbc.sink;

import com.findinpath.connect.nestedset.jdbc.dialect.DatabaseDialect;
import com.findinpath.connect.nestedset.jdbc.dialect.DatabaseDialects;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

public class NestedSetJdbcSinkTask extends SinkTask {
  private static final Logger log = LoggerFactory
			.getLogger(NestedSetJdbcSinkTask.class);

  DatabaseDialect dialect;
  JdbcSinkConfig config;
  JdbcDbWriter writer;
  AsyncSquashingExecutor asyncSquashingExecutor;
  NestedSetSynchronizer nestedSetSynchronizer;

  int remainingRetries;

  @Override
  public void start(final Map<String, String> props) {
    log.info("Starting JDBC Sink task");
    config = new JdbcSinkConfig(props);
    initWriters();
    remainingRetries = config.maxRetries;
    asyncSquashingExecutor = new AsyncSquashingExecutor();

    //TODO in case that the log table exists and is not empty
    // try on start to sync the contents to the nested table
  }

  void initWriters() {
    if (config.dialectName != null && !config.dialectName.trim().isEmpty()) {
      dialect = DatabaseDialects.create(config.dialectName, config);
    } else {
      dialect = DatabaseDialects.findBestFor(config.connectionUrl, config);
    }

    final DbStructure dbStructure = new DbStructure(dialect);
    log.info("Initializing writer using SQL dialect: {}", dialect.getClass().getSimpleName());
    //TODO the writer and the sync should share the same transaction to avoid writing duplicate data to the log table
    writer = new JdbcDbWriter(config, dialect, dbStructure);

    nestedSetSynchronizer = new NestedSetSynchronizer(config, dialect, dbStructure);
  }

  @Override
  public void put(Collection<SinkRecord> records) {
    if (records.isEmpty()) {
      return;
    }
    final SinkRecord first = records.iterator().next();
    final int recordsCount = records.size();
    log.debug(
        "Received {} records. First record kafka coordinates:({}-{}-{}). Writing them to the "
        + "database...",
        recordsCount, first.topic(), first.kafkaPartition(), first.kafkaOffset()
    );
    try {
      writer.write(records);
      nestedSetSynchronizer.synchronize();
    } catch (SQLException sqle) {
      log.warn(
          "Write of {} records failed, remainingRetries={}",
          records.size(),
          remainingRetries,
          sqle
      );
      String sqleAllMessages = "";
      for (Throwable e : sqle) {
        sqleAllMessages += e + System.lineSeparator();
      }
      if (remainingRetries == 0) {
        throw new ConnectException(new SQLException(sqleAllMessages));
      } else {
        writer.closeQuietly();
        initWriters();
        remainingRetries--;
        context.timeout(config.retryBackoffMs);
        throw new RetriableException(new SQLException(sqleAllMessages));
      }
    }


    remainingRetries = config.maxRetries;
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> map) {
    // Not necessary
  }

  public void stop() {
    log.info("Stopping task");
    try {
      writer.closeQuietly();
    } finally {
      try {
        if (dialect != null) {
          dialect.close();
        }
      } catch (Throwable t) {
        log.warn("Error while closing the {} dialect: ", dialect.name(), t);
      } finally {
        dialect = null;
      }
    }

    asyncSquashingExecutor.stop();
  }

  @Override
  public String version() {
    return getClass().getPackage().getImplementationVersion();
  }

}
