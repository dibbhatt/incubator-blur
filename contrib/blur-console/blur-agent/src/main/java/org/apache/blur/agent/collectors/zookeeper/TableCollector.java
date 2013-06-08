package org.apache.blur.agent.collectors.zookeeper;

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

import org.apache.blur.agent.connections.zookeeper.interfaces.TableDatabaseInterface;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;


public class TableCollector implements Runnable {
	private static final Log log = LogFactory.getLog(TableCollector.class);

	private final int clusterId;
	private final String clusterName;
	private final ZooKeeper zookeeper;
	private final TableDatabaseInterface database;

	public TableCollector(int clusterId, String clusterName, ZooKeeper zookeeper, TableDatabaseInterface database) {
		this.clusterId = clusterId;
		this.clusterName = clusterName;
		this.zookeeper = zookeeper;
		this.database = database;
	}

	@Override
	public void run() {
		try {
			List<String> tables = this.zookeeper.getChildren("/blur/clusters/" + clusterName + "/tables", false);
			this.database.markDeletedTables(tables, this.clusterId);
			updateOnlineTables(tables);
		} catch (KeeperException e) {
			log.error("Error talking to zookeeper in TableCollector.", e);
		} catch (InterruptedException e) {
			log.error("Zookeeper session expired in TableCollector.", e);
		}
	}

	private void updateOnlineTables(List<String> tables) throws KeeperException, InterruptedException {
		for (String table : tables) {
			String tablePath = "/blur/clusters/" + clusterName + "/tables/" + table;

			String uri = new String(this.zookeeper.getData(tablePath + "/uri", false, null));
			boolean enabled = this.zookeeper.getChildren(tablePath, false).contains("enabled");

			this.database.updateOnlineTable(table, this.clusterId, uri, enabled);
		}
	}
}
