package org.apache.blur.agent.collectors.blur;

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
import java.util.List;
import java.util.Map;

import org.apache.blur.agent.Agent;
import org.apache.blur.agent.collectors.blur.query.QueryCollector;
import org.apache.blur.agent.collectors.blur.table.TableCollector;
import org.apache.blur.agent.connections.blur.interfaces.BlurDatabaseInterface;
import org.apache.blur.agent.exceptions.ZookeeperNameCollisionException;
import org.apache.blur.agent.exceptions.ZookeeperNameMissingException;
import org.apache.blur.thrift.BlurClient;
import org.apache.blur.thrift.generated.Blur.Iface;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.JdbcTemplate;


public class BlurCollector implements Runnable {
	private static final Log log = LogFactory.getLog(BlurCollector.class);

	private final String zookeeperName;
	private final BlurDatabaseInterface database;
	private final boolean collectTables;
	private final boolean collectQueries;

	private String connection;

	public BlurCollector(final String zookeeperName, final String connection, final List<String> activeCollectors,
			final BlurDatabaseInterface database, final JdbcTemplate jdbc) {
		this.zookeeperName = zookeeperName;
		this.connection = connection;
		this.database = database;
		this.collectTables = activeCollectors.contains("tables");
		this.collectQueries = activeCollectors.contains("queries");
	}

	@Override
	public void run() {
		while (true) {
			// Retrieve the zookeeper id
			int zookeeperId = getZookeeperId();

			// If the connection string is blank then we need to build it from the
			// online controllers from the database
			String resolvedConnection = getResolvedConnection(zookeeperId);

			if (StringUtils.isBlank(resolvedConnection)) {
				try {
					Thread.sleep(Agent.COLLECTOR_SLEEP_TIME);
				} catch (InterruptedException e) {
					break;
				}
				continue;
			}

			Iface blurConnection = BlurClient.getClient(resolvedConnection);

			/* Retrieve the clusters and their info */
			for (Map<String, Object> cluster : this.database.getClusters(zookeeperId)) {
				String clusterName = (String) cluster.get("NAME");
				Integer clusterId = (Integer) cluster.get("ID");

				List<String> tables;
				try {
					tables = blurConnection.tableListByCluster(clusterName);
				} catch (Exception e) {
					log.error("An error occured while trying to retrieve the table list for cluster[" + clusterName + "], skipping cluster", e);
					continue;
				}

				for (final String tableName : tables) {
					int tableId = this.database.getTableId(clusterId, tableName);
					if (tableId == -1) {
						continue;
					}

					if (this.collectTables) {
						new Thread(new TableCollector(blurConnection, tableName, tableId, this.database), "Table Collector - " + tableName).start();
					}

					if (this.collectQueries) {
						new Thread(new QueryCollector(blurConnection, tableName, tableId, this.database), "Query Collector - " + tableName).start();
					}
				}
			}

			try {
				Thread.sleep(Agent.COLLECTOR_SLEEP_TIME);
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	private String getResolvedConnection(int zookeeperId) {
		if (StringUtils.isBlank(this.connection)) {
			return this.database.resolveConnectionString(zookeeperId);
		} else {
			return this.connection;
		}
	}

	private int getZookeeperId() {
		try {
			return Integer.parseInt(this.database.getZookeeperId(this.zookeeperName));
		} catch (NumberFormatException e) {
			log.error("The returned zookeeperId is not a valid number", e);
			return -1;
		} catch (ZookeeperNameMissingException e) {
			log.error(e.getMessage(), e);
			return -1;
		} catch (ZookeeperNameCollisionException e) {
			log.error(e.getMessage(), e);
			return -1;
		}
	}
}
