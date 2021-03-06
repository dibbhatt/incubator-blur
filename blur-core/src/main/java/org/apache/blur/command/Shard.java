package org.apache.blur.command;

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

public class Shard extends Location<Shard> implements Comparable<Shard> {

  private final String _shard;
  private final String _table;

  public Shard(String table, String shard) {
    _table = table;
    _shard = shard;
  }

  @Override
  public int compareTo(Shard o) {
    if (o == null) {
      return -1;
    }
    int compareTo = _table.compareTo(o._table);
    if (compareTo == 0) {
      return _shard.compareTo(o._shard);
    }
    return compareTo;
  }

  @Override
  public String toString() {
    return "Shard [_table=" + _table + ", _shard=" + _shard + "]";
  }

  public String getShard() {
    return _shard;
  }

  public String getTable() {
    return _table;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((_shard == null) ? 0 : _shard.hashCode());
    result = prime * result + ((_table == null) ? 0 : _table.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    Shard other = (Shard) obj;
    if (_shard == null) {
      if (other._shard != null)
        return false;
    } else if (!_shard.equals(other._shard))
      return false;
    if (_table == null) {
      if (other._table != null)
        return false;
    } else if (!_table.equals(other._table))
      return false;
    return true;
  }

}
