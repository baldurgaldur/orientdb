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

package com.orientechnologies.orient.core.storage.index.engine;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.encryption.OEncryption;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.index.*;
import com.orientechnologies.orient.core.index.engine.OIndexEngine;
import com.orientechnologies.orient.core.iterator.OEmptyIterator;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.index.sbtree.local.OSBTree;
import com.orientechnologies.orient.core.storage.index.sbtree.local.v1.OSBTreeV1;
import com.orientechnologies.orient.core.storage.index.sbtree.local.v2.OSBTreeV2;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 8/30/13
 */
public class OSBTreeIndexEngine implements OIndexEngine {
  public static final int VERSION = 2;

  public static final String DATA_FILE_EXTENSION        = ".sbt";
  public static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";

  private final OSBTree<Object, Object> sbTree;
  private final int                     version;
  private final String                  name;
  private final int                     id;

  public OSBTreeIndexEngine(final int id, String name, OAbstractPaginatedStorage storage, int version) {
    this.id = id;
    this.name = name;
    this.version = version;

    if (version == 1) {
      sbTree = new OSBTreeV1<>(name, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
    } else if (version == 2) {
      sbTree = new OSBTreeV2<>(name, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
    } else {
      throw new IllegalStateException("Invalid version of index, version = " + version);
    }
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void init(String indexName, String indexType, OIndexDefinition indexDefinition, boolean isAutomatic, ODocument metadata) {
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void flush() {
  }

  @Override
  public void create(OBinarySerializer valueSerializer, boolean isAutomatic, OType[] keyTypes, boolean nullPointerSupport,
      OBinarySerializer keySerializer, int keySize, Map<String, String> engineProperties, OEncryption encryption) {
    try {
      //noinspection unchecked
      sbTree.create(keySerializer, valueSerializer, keyTypes, keySize, nullPointerSupport, encryption);
    } catch (IOException e) {
      throw OException.wrapException(new OIndexException("Error during creation of index " + name), e);
    }
  }

  @Override
  public void delete() {
    try {
      doClearTree();

      sbTree.delete();
    } catch (IOException e) {
      throw OException.wrapException(new OIndexException("Error during deletion of index " + name), e);
    }
  }

  private void doClearTree() throws IOException {
    final OSBTree.OSBTreeKeyCursor<Object> keyCursor = sbTree.keyCursor();
    Object key = keyCursor.next(-1);

    while (key != null) {
      sbTree.remove(key);
      key = keyCursor.next(-1);
    }

    if (sbTree.isNullPointerSupport()) {
      sbTree.remove(null);
    }
  }

  @Override
  public void load(String indexName, OBinarySerializer valueSerializer, boolean isAutomatic, OBinarySerializer keySerializer,
      OType[] keyTypes, boolean nullPointerSupport, int keySize, Map<String, String> engineProperties, OEncryption encryption) {
    //noinspection unchecked
    sbTree.load(indexName, keySerializer, valueSerializer, keyTypes, keySize, nullPointerSupport, encryption);
  }

  @Override
  public boolean contains(Object key) {
    return sbTree.get(key) != null;
  }

  @Override
  public boolean remove(Object key) {
    try {
      return sbTree.remove(key) != null;
    } catch (IOException e) {
      throw OException.wrapException(new OIndexException("Error during removal of key " + key + " from index " + name), e);
    }
  }

  @Override
  public int getVersion() {
    return version;
  }

  @Override
  public void clear() {
    try {
      doClearTree();
    } catch (IOException e) {
      throw OException.wrapException(new OIndexException("Error during clear index " + name), e);
    }
  }

  @Override
  public void close() {
    sbTree.close();
  }

  @Override
  public Object get(Object key) {
    return sbTree.get(key);
  }

  @Override
  public OIndexCursor cursor(ValuesTransformer valuesTransformer) {
    final Object firstKey = sbTree.firstKey();
    if (firstKey == null) {
      return new NullCursor();
    }

    return new OSBTreeIndexCursor(sbTree.iterateEntriesMajor(firstKey, true, true), valuesTransformer);
  }

  @Override
  public OIndexCursor descCursor(ValuesTransformer valuesTransformer) {
    final Object lastKey = sbTree.lastKey();
    if (lastKey == null) {
      return new NullCursor();
    }

    return new OSBTreeIndexCursor(sbTree.iterateEntriesMinor(lastKey, true, false), valuesTransformer);
  }

  @Override
  public OIndexKeyCursor keyCursor() {
    return new OIndexKeyCursor() {
      private final OSBTree.OSBTreeKeyCursor<Object> sbTreeKeyCursor = sbTree.keyCursor();

      @Override
      public Object next(int prefetchSize) {
        return sbTreeKeyCursor.next(prefetchSize);
      }
    };
  }

  @Override
  public void put(Object key, Object value) {
    try {
      sbTree.put(key, value);
    } catch (IOException e) {
      throw OException.wrapException(new OIndexException("Error during insertion of key " + key + " in index " + name), e);
    }
  }

  @Override
  public void update(Object key, OIndexKeyUpdater<Object> updater) {
    try {
      sbTree.update(key, updater, null);
    } catch (IOException e) {
      throw OException.wrapException(new OIndexException("Error during update of key " + key + " in index " + name), e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean validatedPut(Object key, ORID value, Validator<Object, ORID> validator) {
    try {
      return sbTree.validatedPut(key, value, (Validator) validator);
    } catch (IOException e) {
      throw OException.wrapException(new OIndexException("Error during insertion of key " + key + " in index " + name), e);
    }
  }

  @Override
  public Object getFirstKey() {
    return sbTree.firstKey();
  }

  @Override
  public Object getLastKey() {
    return sbTree.lastKey();
  }

  @Override
  public OIndexCursor iterateEntriesBetween(Object rangeFrom, boolean fromInclusive, Object rangeTo, boolean toInclusive,
      boolean ascSortOrder, ValuesTransformer transformer) {
    return new OSBTreeIndexCursor(sbTree.iterateEntriesBetween(rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder),
        transformer);
  }

  @Override
  public OIndexCursor iterateEntriesMajor(Object fromKey, boolean isInclusive, boolean ascSortOrder,
      ValuesTransformer transformer) {
    return new OSBTreeIndexCursor(sbTree.iterateEntriesMajor(fromKey, isInclusive, ascSortOrder), transformer);
  }

  @Override
  public OIndexCursor iterateEntriesMinor(Object toKey, boolean isInclusive, boolean ascSortOrder, ValuesTransformer transformer) {
    return new OSBTreeIndexCursor(sbTree.iterateEntriesMinor(toKey, isInclusive, ascSortOrder), transformer);
  }

  @Override
  public long size(final ValuesTransformer transformer) {
    if (transformer == null) {
      return sbTree.size();
    } else {
      int counter = 0;

      if (sbTree.isNullPointerSupport()) {
        final Object nullValue = sbTree.get(null);
        if (nullValue != null) {
          counter += transformer.transformFromValue(nullValue).size();
        }
      }

      final Object firstKey = sbTree.firstKey();
      final Object lastKey = sbTree.lastKey();

      if (firstKey != null && lastKey != null) {
        final OSBTree.OSBTreeCursor<Object, Object> cursor = sbTree.iterateEntriesBetween(firstKey, true, lastKey, true, true);
        Map.Entry<Object, Object> entry = cursor.next(-1);
        while (entry != null) {
          counter += transformer.transformFromValue(entry.getValue()).size();
          entry = cursor.next(-1);
        }

        return counter;
      }

      return counter;
    }
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return true;
  }

  @Override
  public boolean acquireAtomicExclusiveLock(Object key) {
    sbTree.acquireAtomicExclusiveLock();
    return true;
  }

  @Override
  public String getIndexNameByKey(Object key) {
    return name;
  }

  private static final class OSBTreeIndexCursor extends OIndexAbstractCursor {
    private final OSBTree.OSBTreeCursor<Object, Object> treeCursor;
    private final ValuesTransformer                     valuesTransformer;

    private Iterator<ORID> currentIterator = OEmptyIterator.IDENTIFIABLE_INSTANCE;
    private Object         currentKey      = null;

    private OSBTreeIndexCursor(OSBTree.OSBTreeCursor<Object, Object> treeCursor, ValuesTransformer valuesTransformer) {
      this.treeCursor = treeCursor;
      this.valuesTransformer = valuesTransformer;
    }

    @Override
    public Map.Entry<Object, OIdentifiable> nextEntry() {
      if (valuesTransformer == null) {
        final Object entry = treeCursor.next(getPrefetchSize());
        //noinspection unchecked
        return (Map.Entry<Object, OIdentifiable>) entry;
      }

      if (currentIterator == null) {
        return null;
      }

      while (!currentIterator.hasNext()) {
        final Object p = treeCursor.next(getPrefetchSize());
        @SuppressWarnings("unchecked")
        final Map.Entry<Object, OIdentifiable> entry = (Map.Entry<Object, OIdentifiable>) p;

        if (entry == null) {
          currentIterator = null;
          return null;
        }

        currentKey = entry.getKey();
        currentIterator = valuesTransformer.transformFromValue(entry.getValue()).iterator();
      }

      final OIdentifiable value = currentIterator.next();

      return new Map.Entry<Object, OIdentifiable>() {
        @Override
        public Object getKey() {
          return currentKey;
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
  }

  private static class NullCursor extends OIndexAbstractCursor {
    @Override
    public Map.Entry<Object, OIdentifiable> nextEntry() {
      return null;
    }
  }
}
