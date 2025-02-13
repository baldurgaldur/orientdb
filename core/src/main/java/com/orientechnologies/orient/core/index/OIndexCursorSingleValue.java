/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.core.index;

import com.orientechnologies.common.util.OSizeable;
import com.orientechnologies.orient.core.db.record.OIdentifiable;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Implementation of index cursor in case of only single entree should be returned.
 *
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 4/4/14
 */
public class OIndexCursorSingleValue extends OIndexAbstractCursor implements OSizeable {
  private final Object        key;
  private       OIdentifiable identifiable;
  private       boolean       empty = false;

  public OIndexCursorSingleValue(OIdentifiable identifiable, Object key) {
    this.identifiable = identifiable;
    this.key = key;
    if (this.identifiable == null) {
      empty = true;
    }
  }

  @Override
  public boolean hasNext() {
    return identifiable != null;
  }

  @Override
  public OIdentifiable next() {
    if (identifiable == null)
      throw new NoSuchElementException();

    final OIdentifiable value = identifiable;
    identifiable = null;
    return value;
  }

  @Override
  public Map.Entry<Object, OIdentifiable> nextEntry() {
    if (identifiable == null)
      return null;

    final OIdentifiable value = identifiable;
    identifiable = null;

    return new Map.Entry<Object, OIdentifiable>() {

      @Override
      public Object getKey() {
        return key;
      }

      @Override
      public OIdentifiable getValue() {
        return value;
      }

      @Override
      public OIdentifiable setValue(OIdentifiable value) {
        throw new UnsupportedOperationException("setValue");
      }
    };
  }

  @Override
  public int size() {
    return empty ? 0 : 1;
  }
}
