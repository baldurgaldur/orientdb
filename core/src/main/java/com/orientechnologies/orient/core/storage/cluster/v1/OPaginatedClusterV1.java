/*
 * Copyright 2010-2013 OrientDB LTD (info--at--orientdb.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.storage.cluster.v1;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.compression.OCompression;
import com.orientechnologies.orient.core.compression.OCompressionFactory;
import com.orientechnologies.orient.core.compression.impl.ONothingCompression;
import com.orientechnologies.orient.core.config.OContextConfiguration;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.config.OStorageClusterConfiguration;
import com.orientechnologies.orient.core.config.OStoragePaginatedClusterConfiguration;
import com.orientechnologies.orient.core.conflict.ORecordConflictStrategy;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.encryption.OEncryption;
import com.orientechnologies.orient.core.encryption.OEncryptionFactory;
import com.orientechnologies.orient.core.encryption.impl.ONothingEncryption;
import com.orientechnologies.orient.core.exception.ONonEmptyComponentCanNotBeRemovedException;
import com.orientechnologies.orient.core.exception.OPaginatedClusterException;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.OMetadataInternal;
import com.orientechnologies.orient.core.storage.OPhysicalPosition;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.cluster.*;
import com.orientechnologies.orient.core.storage.config.OClusterBasedStorageConfiguration;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.OClusterBrowseEntry;
import com.orientechnologies.orient.core.storage.impl.local.OClusterBrowsePage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.ORecordOperationMetadata;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationsManager;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.co.paginatedcluster.OPaginatedClusterCreateCO;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.co.paginatedcluster.OPaginatedClusterDeleteCO;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.orientechnologies.orient.core.config.OGlobalConfiguration.DISK_CACHE_PAGE_SIZE;
import static com.orientechnologies.orient.core.config.OGlobalConfiguration.PAGINATED_STORAGE_LOWEST_FREELIST_BOUNDARY;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 10/7/13
 */
public final class OPaginatedClusterV1 extends OPaginatedCluster {
  private static final int     STATE_ENTRY_INDEX = 0;
  private static final int     BINARY_VERSION    = 1;
  private final        boolean addRidMetadata    = OGlobalConfiguration.STORAGE_TRACK_CHANGED_RECORDS_IN_WAL.getValueAsBoolean();

  private static final int DISK_PAGE_SIZE           = DISK_CACHE_PAGE_SIZE.getValueAsInteger();
  private static final int LOWEST_FREELIST_BOUNDARY = PAGINATED_STORAGE_LOWEST_FREELIST_BOUNDARY.getValueAsInteger();
  private static final int FREE_LIST_SIZE           = DISK_PAGE_SIZE - LOWEST_FREELIST_BOUNDARY;
  private static final int PAGE_INDEX_OFFSET        = 16;
  private static final int RECORD_POSITION_MASK     = 0xFFFF;
  private static final int ONE_KB                   = 1024;

  private volatile OCompression compression = ONothingCompression.INSTANCE;
  private volatile OEncryption  encryption  = ONothingEncryption.INSTANCE;

  private final    boolean                               systemCluster;
  private final    OClusterPositionMapV1                 clusterPositionMap;
  private final    OAbstractPaginatedStorage             storageLocal;
  private volatile int                                   id;
  private          long                                  fileId;
  private          OStoragePaginatedClusterConfiguration config;
  private          ORecordConflictStrategy               recordConflictStrategy;

  private static final class AddEntryResult {
    private final long pageIndex;
    private final int  pagePosition;

    private final int recordVersion;
    private final int recordsSizeDiff;

    AddEntryResult(final long pageIndex, final int pagePosition, final int recordVersion, final int recordsSizeDiff) {
      this.pageIndex = pageIndex;
      this.pagePosition = pagePosition;
      this.recordVersion = recordVersion;
      this.recordsSizeDiff = recordsSizeDiff;
    }
  }

  private static final class FindFreePageResult {
    private final long    pageIndex;
    private final int     freePageIndex;
    private final boolean allocateNewPage;

    private FindFreePageResult(final long pageIndex, final int freePageIndex, final boolean allocateNewPage) {
      this.pageIndex = pageIndex;
      this.freePageIndex = freePageIndex;
      this.allocateNewPage = allocateNewPage;
    }
  }

  public OPaginatedClusterV1(final String name, final OAbstractPaginatedStorage storage) {
    this(name, OPaginatedCluster.DEF_EXTENSION, OClusterPositionMap.DEF_EXTENSION, storage);
  }

  public OPaginatedClusterV1(final String name, final String dataExtension, final String cpmExtension,
      final OAbstractPaginatedStorage storage) {
    super(storage, name, dataExtension, name + dataExtension);

    systemCluster = OMetadataInternal.SYSTEM_CLUSTER.contains(name);
    storageLocal = storage;

    clusterPositionMap = new OClusterPositionMapV1(storage, getName(), getFullName(), cpmExtension);
  }

  @Override
  public void configure(final int id, final String clusterName) throws IOException {
    acquireExclusiveLock();
    try {
      final OContextConfiguration ctxCfg = storage.getConfiguration().getContextConfiguration();
      final String cfgCompression = ctxCfg.getValueAsString(OGlobalConfiguration.STORAGE_COMPRESSION_METHOD);
      final String cfgEncryption = ctxCfg.getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_METHOD);
      final String cfgEncryptionKey = ctxCfg.getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY);

      config = new OStoragePaginatedClusterConfiguration(storage.getConfiguration(), id, clusterName, null, true,
          OStoragePaginatedClusterConfiguration.DEFAULT_GROW_FACTOR, OStoragePaginatedClusterConfiguration.DEFAULT_GROW_FACTOR,
          cfgCompression, cfgEncryption, cfgEncryptionKey, null, OStorageClusterConfiguration.STATUS.ONLINE, BINARY_VERSION);
      config.name = clusterName;

      init(config);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public boolean exists() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        return isFileExists(atomicOperation, getFullName());
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public int getBinaryVersion() {
    return BINARY_VERSION;
  }

  @Override
  public void configure(final OStorage storage, final OStorageClusterConfiguration config) throws IOException {
    acquireExclusiveLock();
    try {
      init(config);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void create() throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);
    try {
      acquireExclusiveLock();
      try {
        fileId = addFile(atomicOperation, getFullName());

        initCusterState(atomicOperation);

        clusterPositionMap.create(atomicOperation);

        atomicOperation.addComponentOperation(new OPaginatedClusterCreateCO(getName(), id));
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  public void registerInStorageConfig(final OClusterBasedStorageConfiguration root) {
    root.updateCluster(config);
  }

  @Override
  public void open() throws IOException {
    acquireExclusiveLock();
    try {
      final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
      fileId = openFile(atomicOperation, getFullName());
      clusterPositionMap.open(atomicOperation);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void replaceFile(final File file) throws IOException {
    acquireExclusiveLock();
    try {
      final String tempFileName = file.getName() + "$temp";
      try {
        final long tempFileId = writeCache.addFile(tempFileName);
        writeCache.replaceFileContentWith(tempFileId, file.toPath());

        readCache.deleteFile(fileId, writeCache);
        writeCache.renameFile(tempFileId, getFullName());
        fileId = tempFileId;
      } finally {
        // If, for some reason, the temp file is still exists, wipe it out.

        final long tempFileId = writeCache.fileIdByName(tempFileName);
        if (tempFileId >= 0) {
          writeCache.deleteFile(tempFileId);
        }
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  public void replaceClusterMapFile(final File file) throws IOException {
    acquireExclusiveLock();
    try {
      final String tempFileName = file.getName() + "$temp";
      try {
        final long tempFileId = writeCache.addFile(tempFileName);
        writeCache.replaceFileContentWith(tempFileId, file.toPath());

        readCache.deleteFile(clusterPositionMap.getFileId(), writeCache);
        writeCache.renameFile(tempFileId, clusterPositionMap.getFullName());
        clusterPositionMap.replaceFileId(tempFileId);
      } finally {
        final long tempFileId = writeCache.fileIdByName(tempFileName);
        if (tempFileId >= 0) {
          writeCache.deleteFile(tempFileId);
        }
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void close() {
    close(true);
  }

  @Override
  public void close(final boolean flush) {
    acquireExclusiveLock();
    try {
      if (flush) {
        synch();
      }

      readCache.closeFile(fileId, flush, writeCache);
      clusterPositionMap.close(flush);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void delete() throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);

    try {
      acquireExclusiveLock();
      try {
        final long entries = getEntries();
        if (entries > 0) {
          throw new ONonEmptyComponentCanNotBeRemovedException(
              getName() + " : Not empty cluster can not be deleted. Cluster has " + entries + " records");
        }

        deleteFile(atomicOperation, fileId);

        clusterPositionMap.delete(atomicOperation);

        atomicOperation.addComponentOperation(new OPaginatedClusterDeleteCO(getName(), id));
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  @Override
  public Object set(final ATTRIBUTES attribute, final Object value) throws IOException {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final String stringValue = value != null ? value.toString() : null;

    acquireExclusiveLock();
    try {

      switch (attribute) {
      case NAME:
        setNameInternal(stringValue);
        break;
      case RECORD_GROW_FACTOR:
        setRecordGrowFactorInternal(stringValue);
        break;
      case RECORD_OVERFLOW_GROW_FACTOR:
        setRecordOverflowGrowFactorInternal(stringValue);
        break;
      case CONFLICTSTRATEGY:
        setRecordConflictStrategy(stringValue);
        break;
      case STATUS: {
        if (stringValue == null) {
          throw new IllegalStateException("Value of attribute is null");
        }

        return storageLocal.setClusterStatus(id, OStorageClusterConfiguration.STATUS
            .valueOf(stringValue.toUpperCase(storageLocal.getConfiguration().getLocaleInstance())));
      }
      case ENCRYPTION:
        if (getEntries() > 0) {
          throw new IllegalArgumentException(
              "Cannot change encryption setting on cluster '" + getName() + "' because it is not empty");
        }
        setEncryptionInternal(stringValue,
            ODatabaseRecordThreadLocal.instance().get().getStorage().getConfiguration().getContextConfiguration()
                .getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY));
        break;
      default:
        throw new IllegalArgumentException("Runtime change of attribute '" + attribute + " is not supported");
      }

    } finally {
      releaseExclusiveLock();
    }

    return null;
  }

  @Override
  public boolean isSystemCluster() {
    return systemCluster;
  }

  @Override
  public float recordGrowFactor() {
    acquireSharedLock();
    try {
      return config.recordGrowFactor;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public float recordOverflowGrowFactor() {
    acquireSharedLock();
    try {
      return config.recordOverflowGrowFactor;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public String compression() {
    acquireSharedLock();
    try {
      return config.compression;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public String encryption() {
    acquireSharedLock();
    try {
      return config.encryption;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public OPhysicalPosition allocatePosition(final byte recordType) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      acquireExclusiveLock();
      try {
        final OPhysicalPosition pos = createPhysicalPosition(recordType, clusterPositionMap.allocate(atomicOperation), -1);
        addAtomicOperationMetadata(new ORecordId(id, pos.clusterPosition), atomicOperation);
        return pos;
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  @Override
  public OPhysicalPosition createRecord(byte[] content, final int recordVersion, final byte recordType,
      final OPhysicalPosition allocatedPosition) throws IOException {
    content = compression.compress(content);
    content = encryption.encrypt(content);

    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      acquireExclusiveLock();
      try {
        final int entryContentLength = getEntryContentLength(content.length);

        if (entryContentLength < OClusterPage.MAX_RECORD_SIZE) {
          final byte[] entryContent = new byte[entryContentLength];

          int entryPosition = 0;
          entryContent[entryPosition] = recordType;
          entryPosition++;

          OIntegerSerializer.INSTANCE.serializeNative(content.length, entryContent, entryPosition);
          entryPosition += OIntegerSerializer.INT_SIZE;

          System.arraycopy(content, 0, entryContent, entryPosition, content.length);
          entryPosition += content.length;

          entryContent[entryPosition] = 1;
          entryPosition++;

          OLongSerializer.INSTANCE.serializeNative(-1L, entryContent, entryPosition);

          final AddEntryResult addEntryResult = addEntry(recordVersion, entryContent, atomicOperation);

          updateClusterState(1, addEntryResult.recordsSizeDiff, atomicOperation);

          final long clusterPosition;
          if (allocatedPosition != null) {
            clusterPositionMap.update(allocatedPosition.clusterPosition,
                new OClusterPositionMapBucket.PositionEntry(addEntryResult.pageIndex, addEntryResult.pagePosition),
                atomicOperation);
            clusterPosition = allocatedPosition.clusterPosition;
          } else {
            clusterPosition = clusterPositionMap.add(addEntryResult.pageIndex, addEntryResult.pagePosition, atomicOperation);
          }

          addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);

          return createPhysicalPosition(recordType, clusterPosition, addEntryResult.recordVersion);
        } else {
          final int entrySize = content.length + OIntegerSerializer.INT_SIZE + OByteSerializer.BYTE_SIZE;

          int fullEntryPosition = 0;
          final byte[] fullEntry = new byte[entrySize];

          fullEntry[fullEntryPosition] = recordType;
          fullEntryPosition++;

          OIntegerSerializer.INSTANCE.serializeNative(content.length, fullEntry, fullEntryPosition);
          fullEntryPosition += OIntegerSerializer.INT_SIZE;

          System.arraycopy(content, 0, fullEntry, fullEntryPosition, content.length);

          long prevPageRecordPointer = -1;
          long firstPageIndex = -1;
          int firstPagePosition = -1;

          int version = 0;

          int from = 0;
          int to = from + (OClusterPage.MAX_RECORD_SIZE - OByteSerializer.BYTE_SIZE - OLongSerializer.LONG_SIZE);

          int recordsSizeDiff = 0;

          do {
            final byte[] entryContent = new byte[to - from + OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE];
            System.arraycopy(fullEntry, from, entryContent, 0, to - from);

            if (from > 0) {
              entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 0;
            } else {
              entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 1;
            }

            OLongSerializer.INSTANCE.serializeNative(-1L, entryContent, entryContent.length - OLongSerializer.LONG_SIZE);

            final AddEntryResult addEntryResult = addEntry(recordVersion, entryContent, atomicOperation);
            recordsSizeDiff += addEntryResult.recordsSizeDiff;

            if (firstPageIndex == -1) {
              firstPageIndex = addEntryResult.pageIndex;
              firstPagePosition = addEntryResult.pagePosition;
              version = addEntryResult.recordVersion;
            }

            final long addedPagePointer = createPagePointer(addEntryResult.pageIndex, addEntryResult.pagePosition);
            if (prevPageRecordPointer >= 0) {
              final long prevPageIndex = getPageIndex(prevPageRecordPointer);
              final int prevPageRecordPosition = getRecordPosition(prevPageRecordPointer);

              final OCacheEntry prevPageCacheEntry = loadPageForWrite(atomicOperation, fileId, prevPageIndex, false, true);
              try {
                final OClusterPage prevPage = new OClusterPage(prevPageCacheEntry, false);
                prevPage.setRecordLongValue(prevPageRecordPosition, -OLongSerializer.LONG_SIZE, addedPagePointer);
              } finally {
                releasePageFromWrite(atomicOperation, prevPageCacheEntry);
              }
            }

            prevPageRecordPointer = addedPagePointer;
            from = to;
            to = to + (OClusterPage.MAX_RECORD_SIZE - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE);
            if (to > fullEntry.length) {
              to = fullEntry.length;
            }

          } while (from < to);

          updateClusterState(1, recordsSizeDiff, atomicOperation);
          final long clusterPosition;
          if (allocatedPosition != null) {
            clusterPositionMap.update(allocatedPosition.clusterPosition,
                new OClusterPositionMapBucket.PositionEntry(firstPageIndex, firstPagePosition), atomicOperation);
            clusterPosition = allocatedPosition.clusterPosition;
          } else {
            clusterPosition = clusterPositionMap.add(firstPageIndex, firstPagePosition, atomicOperation);
          }

          addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);
          return createPhysicalPosition(recordType, clusterPosition, version);

        }
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  private void addAtomicOperationMetadata(final ORID rid, final OAtomicOperation atomicOperation) {
    if (!addRidMetadata) {
      return;
    }

    if (atomicOperation == null) {
      return;
    }

    ORecordOperationMetadata recordOperationMetadata = (ORecordOperationMetadata) atomicOperation
        .getMetadata(ORecordOperationMetadata.RID_METADATA_KEY);

    if (recordOperationMetadata == null) {
      recordOperationMetadata = new ORecordOperationMetadata();
      atomicOperation.addMetadata(recordOperationMetadata);
    }

    recordOperationMetadata.addRid(rid);
  }

  private static int getEntryContentLength(final int grownContentSize) {

    return grownContentSize + 2 * OByteSerializer.BYTE_SIZE + OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE;
  }

  @Override
  public ORawBuffer readRecord(final long clusterPosition, final boolean prefetchRecords) throws IOException {
    int pagesToPrefetch = 1;

    if (prefetchRecords) {
      pagesToPrefetch = OGlobalConfiguration.QUERY_SCAN_PREFETCH_PAGES.getValueAsInteger();
    }

    return readRecord(clusterPosition, pagesToPrefetch);

  }

  private ORawBuffer readRecord(final long clusterPosition, final int pageCount) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap
            .get(clusterPosition, pageCount, atomicOperation);
        if (positionEntry == null) {
          return null;
        }

        return internalReadRecord(clusterPosition, positionEntry.getPageIndex(), positionEntry.getRecordPosition(), pageCount,
            atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  private ORawBuffer internalReadRecord(final long clusterPosition, final long pageIndex, final int recordPosition, int pageCount,
      final OAtomicOperation atomicOperation) throws IOException {
    if (pageCount > 1) {
      final OCacheEntry stateCacheEntry = loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX, false);
      try {
        final OPaginatedClusterStateV1 state = new OPaginatedClusterStateV1(stateCacheEntry);
        pageCount = (int) Math.min(state.getFileSize() + 1 - pageIndex, pageCount);
      } finally {
        releasePageFromRead(atomicOperation, stateCacheEntry);
      }
    }

    int recordVersion;
    final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false, pageCount);
    try {
      final OClusterPage localPage = new OClusterPage(cacheEntry, false);
      recordVersion = localPage.getRecordVersion(recordPosition);
    } finally {
      releasePageFromRead(atomicOperation, cacheEntry);
    }

    final byte[] fullContent = readFullEntry(clusterPosition, pageIndex, recordPosition, atomicOperation, pageCount);
    if (fullContent == null) {
      return null;
    }

    int fullContentPosition = 0;

    final byte recordType = fullContent[fullContentPosition];
    fullContentPosition++;

    final int readContentSize = OIntegerSerializer.INSTANCE.deserializeNative(fullContent, fullContentPosition);
    fullContentPosition += OIntegerSerializer.INT_SIZE;

    byte[] recordContent = Arrays.copyOfRange(fullContent, fullContentPosition, fullContentPosition + readContentSize);

    recordContent = encryption.decrypt(recordContent);
    recordContent = compression.uncompress(recordContent);

    return new ORawBuffer(recordContent, recordVersion, recordType);
  }

  @Override
  public ORawBuffer readRecordIfVersionIsNotLatest(final long clusterPosition, final int recordVersion)
      throws IOException, ORecordNotFoundException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1, atomicOperation);

        if (positionEntry == null) {
          throw new ORecordNotFoundException(new ORecordId(id, clusterPosition),
              "Record for cluster with id " + id + " and position " + clusterPosition + " is absent.");
        }

        final int recordPosition = positionEntry.getRecordPosition();
        final long pageIndex = positionEntry.getPageIndex();

        int loadedRecordVersion;
        final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
        try {
          final OClusterPage localPage = new OClusterPage(cacheEntry, false);
          if (localPage.isDeleted(recordPosition)) {
            throw new ORecordNotFoundException(new ORecordId(id, clusterPosition),
                "Record for cluster with id " + id + " and position " + clusterPosition + " is absent.");
          }

          loadedRecordVersion = localPage.getRecordVersion(recordPosition);
        } finally {
          releasePageFromRead(atomicOperation, cacheEntry);
        }

        if (loadedRecordVersion > recordVersion) {
          return readRecord(clusterPosition, false);
        }

        return null;
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public boolean deleteRecord(final long clusterPosition) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      acquireExclusiveLock();
      try {
        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1, atomicOperation);
        if (positionEntry == null) {
          return false;
        }

        long pageIndex = positionEntry.getPageIndex();
        int recordPosition = positionEntry.getRecordPosition();

        long nextPagePointer;
        int removedContentSize = 0;

        do {
          boolean cacheEntryReleased = false;
          OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false, true);
          int initialFreePageIndex;
          try {
            OClusterPage localPage = new OClusterPage(cacheEntry, false);
            initialFreePageIndex = calculateFreePageIndex(localPage);

            if (localPage.isDeleted(recordPosition)) {
              if (removedContentSize == 0) {
                cacheEntryReleased = true;
                releasePageFromWrite(atomicOperation, cacheEntry);
                return false;
              } else {
                throw new OPaginatedClusterException("Content of record " + new ORecordId(id, clusterPosition) + " was broken",
                    this);
              }
            } else if (removedContentSize == 0) {
              releasePageFromWrite(atomicOperation, cacheEntry);

              cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false, true);

              localPage = new OClusterPage(cacheEntry, false);
            }

            final byte[] content = localPage.getRecordBinaryValue(recordPosition, 0, localPage.getRecordSize(recordPosition));

            final int initialFreeSpace = localPage.getFreeSpace();
            localPage.deleteRecord(recordPosition);

            removedContentSize += localPage.getFreeSpace() - initialFreeSpace;
            nextPagePointer = OLongSerializer.INSTANCE.deserializeNative(content, content.length - OLongSerializer.LONG_SIZE);
          } finally {
            if (!cacheEntryReleased) {
              releasePageFromWrite(atomicOperation, cacheEntry);
            }
          }

          updateFreePagesIndex(initialFreePageIndex, pageIndex, atomicOperation);

          pageIndex = getPageIndex(nextPagePointer);
          recordPosition = getRecordPosition(nextPagePointer);
        } while (nextPagePointer >= 0);

        updateClusterState(-1, -removedContentSize, atomicOperation);

        clusterPositionMap.remove(clusterPosition, atomicOperation);
        addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);

        return true;
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  @Override
  public boolean hideRecord(final long position) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      acquireExclusiveLock();
      try {
        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(position, 1, atomicOperation);

        if (positionEntry == null) {
          return false;
        }

        updateClusterState(-1, 0, atomicOperation);
        clusterPositionMap.remove(position, atomicOperation);

        addAtomicOperationMetadata(new ORecordId(id, position), atomicOperation);

        return true;
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  @Override
  public void updateRecord(final long clusterPosition, byte[] content, final int recordVersion, final byte recordType)
      throws IOException {
    content = compression.compress(content);
    content = encryption.encrypt(content);

    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      acquireExclusiveLock();
      try {
        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1, atomicOperation);

        if (positionEntry == null) {
          return;
        }

        int nextRecordPosition = positionEntry.getRecordPosition();
        long nextPageIndex = positionEntry.getPageIndex();

        int newRecordPosition = -1;
        long newPageIndex = -1;

        long prevPageIndex = -1;
        int prevRecordPosition = -1;

        long nextEntryPointer = -1;
        int from = 0;
        int to;

        long sizeDiff = 0;
        byte[] updateEntry = null;

        do {
          final int entrySize;
          final int updatedEntryPosition;

          if (updateEntry == null) {
            if (from == 0) {
              entrySize = Math.min(getEntryContentLength(content.length), OClusterPage.MAX_RECORD_SIZE);
              to = entrySize - (2 * OByteSerializer.BYTE_SIZE + OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);
            } else {
              entrySize = Math
                  .min(content.length - from + OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE, OClusterPage.MAX_RECORD_SIZE);
              to = from + entrySize - (OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE);
            }

            updateEntry = new byte[entrySize];
            int entryPosition = 0;

            if (from == 0) {
              updateEntry[entryPosition] = recordType;
              entryPosition++;

              OIntegerSerializer.INSTANCE.serializeNative(content.length, updateEntry, entryPosition);
              entryPosition += OIntegerSerializer.INT_SIZE;
            }

            System.arraycopy(content, from, updateEntry, entryPosition, to - from);
            entryPosition += to - from;

            if (nextPageIndex == positionEntry.getPageIndex()) {
              updateEntry[entryPosition] = 1;
            }

            entryPosition++;

            OLongSerializer.INSTANCE.serializeNative(-1, updateEntry, entryPosition);

            assert to >= content.length || entrySize == OClusterPage.MAX_RECORD_SIZE;
          } else {
            entrySize = updateEntry.length;

            if (from == 0) {
              to = entrySize - (2 * OByteSerializer.BYTE_SIZE + OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);
            } else {
              to = from + entrySize - (OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE);
            }
          }

          int freePageIndex = -1;

          final boolean isNew;
          if (nextPageIndex < 0) {
            final FindFreePageResult findFreePageResult = findFreePage(entrySize, atomicOperation);
            nextPageIndex = findFreePageResult.pageIndex;
            freePageIndex = findFreePageResult.freePageIndex;
            isNew = findFreePageResult.allocateNewPage;
          } else {
            isNew = false;
          }

          final OCacheEntry cacheEntry;
          if (isNew) {
            final OCacheEntry stateCacheEntry = loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, false, true);
            try {
              final OPaginatedClusterStateV1 clusterState = new OPaginatedClusterStateV1(stateCacheEntry);

              final int fileSize = clusterState.getFileSize();
              final long filledUpTo = getFilledUpTo(atomicOperation, fileId);

              if (fileSize == filledUpTo - 1) {
                cacheEntry = addPage(atomicOperation, fileId);
              } else {
                assert fileSize < filledUpTo - 1;

                cacheEntry = loadPageForWrite(atomicOperation, fileId, fileSize + 1, false, false);
              }

              clusterState.setFileSize(fileSize + 1);
            } finally {
              releasePageFromWrite(atomicOperation, stateCacheEntry);
            }
          } else {
            cacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageIndex, false, true);
          }

          try {
            final OClusterPage localPage = new OClusterPage(cacheEntry, isNew);
            final int pageFreeSpace = localPage.getFreeSpace();

            if (freePageIndex < 0) {
              freePageIndex = calculateFreePageIndex(localPage);
            } else {
              assert isNew || freePageIndex == calculateFreePageIndex(localPage);
            }

            if (nextRecordPosition >= 0) {
              if (localPage.isDeleted(nextRecordPosition)) {
                throw new OPaginatedClusterException("Record with rid " + new ORecordId(id, clusterPosition) + " was deleted",
                    this);
              }

              final int currentEntrySize = localPage.getRecordSize(nextRecordPosition);
              nextEntryPointer = localPage.getRecordLongValue(nextRecordPosition, currentEntrySize - OLongSerializer.LONG_SIZE);

              if (currentEntrySize == entrySize) {
                localPage.replaceRecord(nextRecordPosition, updateEntry, recordVersion);
                updatedEntryPosition = nextRecordPosition;
              } else {
                localPage.deleteRecord(nextRecordPosition);

                if (localPage.getMaxRecordSize() >= entrySize) {
                  updatedEntryPosition = localPage.appendRecord(recordVersion, updateEntry);

                  if (updatedEntryPosition < 0) {
                    localPage.dumpToLog();
                    throw new IllegalStateException("Page " + cacheEntry.getPageIndex()
                        + " does not have enough free space to add record content, freePageIndex=" + freePageIndex
                        + ", updateEntry.length=" + updateEntry.length + ", content.length=" + content.length);
                  }
                } else {
                  updatedEntryPosition = -1;
                }
              }

              if (nextEntryPointer >= 0) {
                nextRecordPosition = getRecordPosition(nextEntryPointer);
                nextPageIndex = getPageIndex(nextEntryPointer);
              } else {
                nextPageIndex = -1;
                nextRecordPosition = -1;
              }

            } else {
              assert localPage.getFreeSpace() >= entrySize;
              updatedEntryPosition = localPage.appendRecord(recordVersion, updateEntry);

              if (updatedEntryPosition < 0) {
                localPage.dumpToLog();
                throw new IllegalStateException(
                    "Page " + cacheEntry.getPageIndex() + " does not have enough free space to add record content, freePageIndex="
                        + freePageIndex + ", updateEntry.length=" + updateEntry.length + ", content.length=" + content.length);
              }

              nextPageIndex = -1;
              nextRecordPosition = -1;
            }

            sizeDiff += pageFreeSpace - localPage.getFreeSpace();

          } finally {
            releasePageFromWrite(atomicOperation, cacheEntry);
          }

          updateFreePagesIndex(freePageIndex, cacheEntry.getPageIndex(), atomicOperation);

          if (updatedEntryPosition >= 0) {
            if (from == 0) {
              newPageIndex = cacheEntry.getPageIndex();
              newRecordPosition = updatedEntryPosition;
            }

            from = to;

            if (prevPageIndex >= 0) {
              final OCacheEntry prevCacheEntry = loadPageForWrite(atomicOperation, fileId, prevPageIndex, false, true);
              try {
                final OClusterPage prevPage = new OClusterPage(prevCacheEntry, false);
                prevPage.setRecordLongValue(prevRecordPosition, -OLongSerializer.LONG_SIZE,
                    createPagePointer(cacheEntry.getPageIndex(), updatedEntryPosition));
              } finally {
                releasePageFromWrite(atomicOperation, prevCacheEntry);
              }
            }

            prevPageIndex = cacheEntry.getPageIndex();
            prevRecordPosition = updatedEntryPosition;

            updateEntry = null;
          }
        } while (to < content.length || updateEntry != null);

        // clear unneeded pages
        while (nextEntryPointer >= 0) {
          nextPageIndex = getPageIndex(nextEntryPointer);
          nextRecordPosition = getRecordPosition(nextEntryPointer);

          final int freePagesIndex;
          final int freeSpace;

          final OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageIndex, false, true);
          try {
            final OClusterPage localPage = new OClusterPage(cacheEntry, false);
            freeSpace = localPage.getFreeSpace();
            freePagesIndex = calculateFreePageIndex(localPage);

            nextEntryPointer = localPage.getRecordLongValue(nextRecordPosition, -OLongSerializer.LONG_SIZE);
            localPage.deleteRecord(nextRecordPosition);

            sizeDiff += freeSpace - localPage.getFreeSpace();
          } finally {
            releasePageFromWrite(atomicOperation, cacheEntry);
          }

          updateFreePagesIndex(freePagesIndex, nextPageIndex, atomicOperation);
        }

        assert newPageIndex >= 0;
        assert newRecordPosition >= 0;

        if (newPageIndex != positionEntry.getPageIndex() || newRecordPosition != positionEntry.getRecordPosition()) {
          clusterPositionMap.update(clusterPosition, new OClusterPositionMapBucket.PositionEntry(newPageIndex, newRecordPosition),
              atomicOperation);
        }

        updateClusterState(0, sizeDiff, atomicOperation);

        addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }

  }

  @Override
  public long getTombstonesCount() {
    return 0;
  }

  @Override
  public OPhysicalPosition getPhysicalPosition(final OPhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final long clusterPosition = position.clusterPosition;
        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1, atomicOperation);

        if (positionEntry == null) {
          return null;
        }

        final long pageIndex = positionEntry.getPageIndex();
        final int recordPosition = positionEntry.getRecordPosition();

        final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
        try {
          final OClusterPage localPage = new OClusterPage(cacheEntry, false);
          if (localPage.isDeleted(recordPosition)) {
            return null;
          }

          if (localPage.getRecordByteValue(recordPosition, -OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE) == 0) {
            return null;
          }

          final OPhysicalPosition physicalPosition = new OPhysicalPosition();
          physicalPosition.recordSize = -1;

          physicalPosition.recordType = localPage.getRecordByteValue(recordPosition, 0);
          physicalPosition.recordVersion = localPage.getRecordVersion(recordPosition);
          physicalPosition.clusterPosition = position.clusterPosition;

          return physicalPosition;
        } finally {
          releasePageFromRead(atomicOperation, cacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public boolean isDeleted(final OPhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final long clusterPosition = position.clusterPosition;
        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1, atomicOperation);

        if (positionEntry == null) {
          return false;
        }

        final long pageIndex = positionEntry.getPageIndex();
        final int recordPosition = positionEntry.getRecordPosition();

        final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
        try {
          final OClusterPage localPage = new OClusterPage(cacheEntry, false);
          return localPage.isDeleted(recordPosition);
        } finally {
          releasePageFromRead(atomicOperation, cacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getEntries() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX, true);
        try {
          return new OPaginatedClusterStateV1(pinnedStateEntry).getSize();
        } finally {
          releasePageFromRead(atomicOperation, pinnedStateEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException ioe) {
      throw OException
          .wrapException(new OPaginatedClusterException("Error during retrieval of size of '" + getName() + "' cluster", this),
              ioe);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getFirstPosition() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        return clusterPositionMap.getFirstPosition(atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getLastPosition() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        return clusterPositionMap.getLastPosition(atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getNextPosition() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        return clusterPositionMap.getNextPosition(atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public String getFileName() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        return writeCache.fileNameById(fileId);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public int getId() {
    return id;
  }

  /**
   * Returns the fileId used in disk cache.
   */
  public long getFileId() {
    return fileId;
  }

  @Override
  public void synch() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        writeCache.flush(fileId);
        clusterPositionMap.flush();
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getRecordsSize() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX, true);
        try {
          return new OPaginatedClusterStateV1(pinnedStateEntry).getRecordsSize();
        } finally {
          releasePageFromRead(atomicOperation, pinnedStateEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public OPhysicalPosition[] higherPositions(final OPhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final long[] clusterPositions = clusterPositionMap.higherPositions(position.clusterPosition, atomicOperation);
        return convertToPhysicalPositions(clusterPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public OPhysicalPosition[] ceilingPositions(final OPhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final long[] clusterPositions = clusterPositionMap.ceilingPositions(position.clusterPosition, atomicOperation);
        return convertToPhysicalPositions(clusterPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public OPhysicalPosition[] lowerPositions(final OPhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final long[] clusterPositions = clusterPositionMap.lowerPositions(position.clusterPosition, atomicOperation);
        return convertToPhysicalPositions(clusterPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public OPhysicalPosition[] floorPositions(final OPhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final long[] clusterPositions = clusterPositionMap.floorPositions(position.clusterPosition, atomicOperation);
        return convertToPhysicalPositions(clusterPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public ORecordConflictStrategy getRecordConflictStrategy() {
    return recordConflictStrategy;
  }

  private void setRecordConflictStrategy(final String stringValue) {
    recordConflictStrategy = Orient.instance().getRecordConflictStrategy().getStrategy(stringValue);
    config.conflictStrategy = stringValue;
    ((OClusterBasedStorageConfiguration) storageLocal.getConfiguration()).updateCluster(config);
  }

  private void updateClusterState(final long sizeDiff, final long recordsSizeDiff, final OAtomicOperation atomicOperation)
      throws IOException {
    final OCacheEntry pinnedStateEntry = loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, false, true);
    try {
      final OPaginatedClusterStateV1 paginatedClusterState = new OPaginatedClusterStateV1(pinnedStateEntry);
      paginatedClusterState.setSize((int) (paginatedClusterState.getSize() + sizeDiff));
      paginatedClusterState.setRecordsSize((int) (paginatedClusterState.getRecordsSize() + recordsSizeDiff));
    } finally {
      releasePageFromWrite(atomicOperation, pinnedStateEntry);
    }
  }

  private void init(final OStorageClusterConfiguration config) throws IOException {
    OFileUtils.checkValidName(config.getName());

    this.config = (OStoragePaginatedClusterConfiguration) config;
    this.compression = OCompressionFactory.INSTANCE.getCompression(this.config.compression, null);
    this.encryption = OEncryptionFactory.INSTANCE.getEncryption(this.config.encryption, this.config.encryptionKey);

    if (((OStoragePaginatedClusterConfiguration) config).conflictStrategy != null) {
      this.recordConflictStrategy = Orient.instance().getRecordConflictStrategy()
          .getStrategy(((OStoragePaginatedClusterConfiguration) config).conflictStrategy);
    }

    this.id = config.getId();
  }

  private void setEncryptionInternal(final String iMethod, final String iKey) {
    try {
      encryption = OEncryptionFactory.INSTANCE.getEncryption(iMethod, iKey);
      config.encryption = iMethod;
      ((OClusterBasedStorageConfiguration) storageLocal.getConfiguration()).updateCluster(config);
    } catch (final IllegalArgumentException e) {
      throw OException
          .wrapException(new OPaginatedClusterException("Invalid value for " + ATTRIBUTES.ENCRYPTION + " attribute", this), e);
    }
  }

  private void setRecordOverflowGrowFactorInternal(final String stringValue) {
    try {
      final float growFactor = Float.parseFloat(stringValue);
      if (growFactor < 1) {
        throw new OPaginatedClusterException(ATTRIBUTES.RECORD_OVERFLOW_GROW_FACTOR + " cannot be less than 1", this);
      }

      config.recordOverflowGrowFactor = growFactor;
      ((OClusterBasedStorageConfiguration) storageLocal.getConfiguration()).updateCluster(config);
    } catch (final NumberFormatException nfe) {
      throw OException.wrapException(new OPaginatedClusterException(
          "Invalid value for cluster attribute " + ATTRIBUTES.RECORD_OVERFLOW_GROW_FACTOR + " was passed [" + stringValue + "]",
          this), nfe);
    }
  }

  private void setRecordGrowFactorInternal(final String stringValue) {
    try {
      final float growFactor = Float.parseFloat(stringValue);
      if (growFactor < 1) {
        throw new OPaginatedClusterException(ATTRIBUTES.RECORD_GROW_FACTOR + " cannot be less than 1", this);
      }

      config.recordGrowFactor = growFactor;
      ((OClusterBasedStorageConfiguration) storageLocal.getConfiguration()).updateCluster(config);
    } catch (final NumberFormatException nfe) {
      throw OException.wrapException(new OPaginatedClusterException(
          "Invalid value for cluster attribute " + ATTRIBUTES.RECORD_GROW_FACTOR + " was passed [" + stringValue + "]", this), nfe);
    }
  }

  private void setNameInternal(final String newName) throws IOException {

    writeCache.renameFile(fileId, newName + getExtension());
    clusterPositionMap.rename(newName);

    config.name = newName;
    storageLocal.renameCluster(getName(), newName);
    setName(newName);

    ((OClusterBasedStorageConfiguration) storageLocal.getConfiguration()).updateCluster(config);
  }

  private static OPhysicalPosition createPhysicalPosition(final byte recordType, final long clusterPosition, final int version) {
    final OPhysicalPosition physicalPosition = new OPhysicalPosition();
    physicalPosition.recordType = recordType;
    physicalPosition.recordSize = -1;
    physicalPosition.clusterPosition = clusterPosition;
    physicalPosition.recordVersion = version;
    return physicalPosition;
  }

  private byte[] readFullEntry(final long clusterPosition, long pageIndex, int recordPosition,
      final OAtomicOperation atomicOperation, int pageCount) throws IOException {
    final List<byte[]> recordChunks = new ArrayList<>(2);
    int contentSize = 0;

    if (pageCount > 1) {
      final OCacheEntry stateCacheEntry = loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX, false);
      try {
        final OPaginatedClusterStateV1 state = new OPaginatedClusterStateV1(stateCacheEntry);
        pageCount = (int) Math.min(state.getFileSize() + 1 - pageIndex, pageCount);
      } finally {
        releasePageFromRead(atomicOperation, stateCacheEntry);
      }
    }

    long nextPagePointer;
    boolean firstEntry = true;
    do {
      final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false, pageCount);
      try {
        final OClusterPage localPage = new OClusterPage(cacheEntry, false);

        if (localPage.isDeleted(recordPosition)) {
          if (recordChunks.isEmpty()) {
            return null;
          } else {
            throw new OPaginatedClusterException("Content of record " + new ORecordId(id, clusterPosition) + " was broken", this);
          }
        }

        final byte[] content = localPage.getRecordBinaryValue(recordPosition, 0, localPage.getRecordSize(recordPosition));

        if (firstEntry && content[content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] == 0) {
          return null;
        }

        recordChunks.add(content);
        nextPagePointer = OLongSerializer.INSTANCE.deserializeNative(content, content.length - OLongSerializer.LONG_SIZE);
        contentSize += content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE;

        firstEntry = false;
      } finally {
        releasePageFromRead(atomicOperation, cacheEntry);
      }

      pageIndex = getPageIndex(nextPagePointer);
      recordPosition = getRecordPosition(nextPagePointer);
    } while (nextPagePointer >= 0);

    final byte[] fullContent;
    if (recordChunks.size() == 1) {
      fullContent = recordChunks.get(0);
    } else {
      fullContent = new byte[contentSize + OLongSerializer.LONG_SIZE + OByteSerializer.BYTE_SIZE];
      int fullContentPosition = 0;
      for (final byte[] recordChuck : recordChunks) {
        System.arraycopy(recordChuck, 0, fullContent, fullContentPosition,
            recordChuck.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE);
        fullContentPosition += recordChuck.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE;
      }
    }

    return fullContent;
  }

  private static long createPagePointer(final long pageIndex, final int pagePosition) {
    return pageIndex << PAGE_INDEX_OFFSET | pagePosition;
  }

  private static int getRecordPosition(final long nextPagePointer) {
    return (int) (nextPagePointer & RECORD_POSITION_MASK);
  }

  private static long getPageIndex(final long nextPagePointer) {
    return nextPagePointer >>> PAGE_INDEX_OFFSET;
  }

  private AddEntryResult addEntry(final int recordVersion, final byte[] entryContent, final OAtomicOperation atomicOperation)
      throws IOException {
    final FindFreePageResult findFreePageResult = findFreePage(entryContent.length, atomicOperation);

    final int freePageIndex = findFreePageResult.freePageIndex;
    final long pageIndex = findFreePageResult.pageIndex;

    final boolean newPage = findFreePageResult.allocateNewPage;

    final OCacheEntry cacheEntry;
    if (newPage) {
      final OCacheEntry stateCacheEntry = loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, false, true);
      try {
        final OPaginatedClusterStateV1 clusterState = new OPaginatedClusterStateV1(stateCacheEntry);
        final int fileSize = clusterState.getFileSize();
        final long filledUpTo = getFilledUpTo(atomicOperation, fileId);

        if (fileSize == filledUpTo - 1) {
          cacheEntry = addPage(atomicOperation, fileId);
        } else {
          assert fileSize < filledUpTo - 1;

          cacheEntry = loadPageForWrite(atomicOperation, fileId, fileSize + 1, false, false);
        }

        clusterState.setFileSize(fileSize + 1);
      } finally {
        releasePageFromWrite(atomicOperation, stateCacheEntry);
      }
    } else {
      cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false, true);
    }

    int recordSizesDiff;
    int position;
    final int finalVersion;

    try {
      final OClusterPage localPage = new OClusterPage(cacheEntry, newPage);
      assert newPage || freePageIndex == calculateFreePageIndex(localPage);

      final int initialFreeSpace = localPage.getFreeSpace();

      position = localPage.appendRecord(recordVersion, entryContent);

      if (position < 0) {
        localPage.dumpToLog();
        throw new IllegalStateException(
            "Page " + cacheEntry.getPageIndex() + " does not have enough free space to add record content, freePageIndex="
                + freePageIndex + ", entryContent.length=" + entryContent.length);
      }

      finalVersion = localPage.getRecordVersion(position);

      final int freeSpace = localPage.getFreeSpace();
      recordSizesDiff = initialFreeSpace - freeSpace;
    } finally {
      releasePageFromWrite(atomicOperation, cacheEntry);
    }

    updateFreePagesIndex(freePageIndex, pageIndex, atomicOperation);

    return new AddEntryResult(pageIndex, position, finalVersion, recordSizesDiff);
  }

  private FindFreePageResult findFreePage(final int contentSize, final OAtomicOperation atomicOperation) throws IOException {
    int freePageIndex = contentSize / ONE_KB;
    freePageIndex -= PAGINATED_STORAGE_LOWEST_FREELIST_BOUNDARY.getValueAsInteger();
    if (freePageIndex < 0) {
      freePageIndex = 0;
    }

    long pageIndex;

    final OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX, true);
    try {
      final OPaginatedClusterStateV1 freePageLists = new OPaginatedClusterStateV1(pinnedStateEntry);
      do {
        pageIndex = freePageLists.getFreeListPage(freePageIndex);
        freePageIndex++;
      } while (pageIndex < 0 && freePageIndex < FREE_LIST_SIZE);

    } finally {
      releasePageFromRead(atomicOperation, pinnedStateEntry);
    }

    final boolean allocateNewPage;

    if (pageIndex < 0) {
      final int fileSize;

      final OCacheEntry stateCacheEntry = loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX, false);
      try {
        final OPaginatedClusterStateV1 clusterState = new OPaginatedClusterStateV1(stateCacheEntry);
        fileSize = clusterState.getFileSize();
      } finally {
        releasePageFromRead(atomicOperation, stateCacheEntry);
      }

      allocateNewPage = true;
      pageIndex = fileSize + 1;
    } else {
      allocateNewPage = false;
      freePageIndex--;
    }

    return new FindFreePageResult(pageIndex, freePageIndex, allocateNewPage);
  }

  private void updateFreePagesIndex(final int prevFreePageIndex, final long pageIndex, final OAtomicOperation atomicOperation)
      throws IOException {
    final OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false, true);

    try {
      final OClusterPage localPage = new OClusterPage(cacheEntry, false);
      final int newFreePageIndex = calculateFreePageIndex(localPage);

      if (prevFreePageIndex == newFreePageIndex) {
        return;
      }

      final long nextPageIndex = localPage.getNextPage();
      final long prevPageIndex = localPage.getPrevPage();

      if (prevPageIndex >= 0) {
        final OCacheEntry prevPageCacheEntry = loadPageForWrite(atomicOperation, fileId, prevPageIndex, false, true);
        try {
          final OClusterPage prevPage = new OClusterPage(prevPageCacheEntry, false);
          assert calculateFreePageIndex(prevPage) == prevFreePageIndex;
          prevPage.setNextPage(nextPageIndex);
        } finally {
          releasePageFromWrite(atomicOperation, prevPageCacheEntry);
        }
      }

      if (nextPageIndex >= 0) {
        final OCacheEntry nextPageCacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageIndex, false, true);
        try {
          final OClusterPage nextPage = new OClusterPage(nextPageCacheEntry, false);
          if (calculateFreePageIndex(nextPage) != prevFreePageIndex) {
            calculateFreePageIndex(nextPage);
          }

          assert calculateFreePageIndex(nextPage) == prevFreePageIndex;
          nextPage.setPrevPage(prevPageIndex);

        } finally {
          releasePageFromWrite(atomicOperation, nextPageCacheEntry);
        }
      }

      localPage.setNextPage(-1);
      localPage.setPrevPage(-1);

      if (prevFreePageIndex < 0 && newFreePageIndex < 0) {
        return;
      }

      if (prevFreePageIndex >= 0 && prevFreePageIndex < FREE_LIST_SIZE) {
        if (prevPageIndex < 0) {
          updateFreePagesList(prevFreePageIndex, nextPageIndex, atomicOperation);
        }
      }

      if (newFreePageIndex >= 0) {
        long oldFreePage;
        final OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX, true);
        try {
          final OPaginatedClusterStateV1 clusterFreeList = new OPaginatedClusterStateV1(pinnedStateEntry);
          oldFreePage = clusterFreeList.getFreeListPage(newFreePageIndex);
        } finally {
          releasePageFromRead(atomicOperation, pinnedStateEntry);
        }

        if (oldFreePage >= 0) {
          final OCacheEntry oldFreePageCacheEntry = loadPageForWrite(atomicOperation, fileId, oldFreePage, false, true);
          try {
            final OClusterPage oldFreeLocalPage = new OClusterPage(oldFreePageCacheEntry, false);
            assert calculateFreePageIndex(oldFreeLocalPage) == newFreePageIndex;

            oldFreeLocalPage.setPrevPage(pageIndex);
          } finally {
            releasePageFromWrite(atomicOperation, oldFreePageCacheEntry);
          }

          localPage.setNextPage(oldFreePage);
          localPage.setPrevPage(-1);
        }

        updateFreePagesList(newFreePageIndex, pageIndex, atomicOperation);
      }
    } finally {
      releasePageFromWrite(atomicOperation, cacheEntry);
    }
  }

  private void updateFreePagesList(final int freeListIndex, final long pageIndex, final OAtomicOperation atomicOperation)
      throws IOException {
    final OCacheEntry pinnedStateEntry = loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, true, true);
    try {
      final OPaginatedClusterStateV1 paginatedClusterState = new OPaginatedClusterStateV1(pinnedStateEntry);
      paginatedClusterState.setFreeListPage(freeListIndex, (int) pageIndex);
    } finally {
      releasePageFromWrite(atomicOperation, pinnedStateEntry);
    }
  }

  private static int calculateFreePageIndex(final OClusterPage localPage) {
    int newFreePageIndex;
    if (localPage.isEmpty()) {
      newFreePageIndex = FREE_LIST_SIZE - 1;
    } else {
      newFreePageIndex = (localPage.getMaxRecordSize() - (ONE_KB - 1)) / ONE_KB;

      newFreePageIndex -= LOWEST_FREELIST_BOUNDARY;
    }
    return newFreePageIndex;
  }

  private void initCusterState(final OAtomicOperation atomicOperation) throws IOException {
    final OCacheEntry stateEntry;
    if (getFilledUpTo(atomicOperation, fileId) == 0) {
      stateEntry = addPage(atomicOperation, fileId);
    } else {
      stateEntry = loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, false, false);
    }

    assert stateEntry.getPageIndex() == 0;
    try {
      final OPaginatedClusterStateV1 paginatedClusterState = new OPaginatedClusterStateV1(stateEntry);
      paginatedClusterState.setSize(0);
      paginatedClusterState.setRecordsSize(0);
      paginatedClusterState.setFileSize(0);

      for (int i = 0; i < FREE_LIST_SIZE; i++) {
        paginatedClusterState.setFreeListPage(i, -1);
      }
    } finally {
      releasePageFromWrite(atomicOperation, stateEntry);
    }

  }

  private static OPhysicalPosition[] convertToPhysicalPositions(final long[] clusterPositions) {
    final OPhysicalPosition[] positions = new OPhysicalPosition[clusterPositions.length];
    for (int i = 0; i < positions.length; i++) {
      final OPhysicalPosition physicalPosition = new OPhysicalPosition();
      physicalPosition.clusterPosition = clusterPositions[i];
      positions[i] = physicalPosition;
    }
    return positions;
  }

  public OPaginatedClusterDebug readDebug(final long clusterPosition) throws IOException {
    final OPaginatedClusterDebug debug = new OPaginatedClusterDebug();
    debug.clusterPosition = clusterPosition;
    debug.fileId = fileId;
    final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

    final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1, atomicOperation);
    if (positionEntry == null) {
      debug.empty = true;
      return debug;
    }

    long pageIndex = positionEntry.getPageIndex();
    int recordPosition = positionEntry.getRecordPosition();

    debug.pages = new ArrayList<>(2);
    int contentSize = 0;

    long nextPagePointer;
    boolean firstEntry = true;
    do {
      final OClusterPageDebug debugPage = new OClusterPageDebug();
      debugPage.pageIndex = pageIndex;
      final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
      try {
        final OClusterPage localPage = new OClusterPage(cacheEntry, false);

        if (localPage.isDeleted(recordPosition)) {
          if (debug.pages.isEmpty()) {
            debug.empty = true;
            return debug;
          } else {
            throw new OPaginatedClusterException("Content of record " + new ORecordId(id, clusterPosition) + " was broken", this);
          }
        }
        debugPage.inPagePosition = recordPosition;
        debugPage.inPageSize = localPage.getRecordSize(recordPosition);
        final byte[] content = localPage.getRecordBinaryValue(recordPosition, 0, debugPage.inPageSize);
        debugPage.content = content;
        if (firstEntry && content[content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] == 0) {
          debug.empty = true;
          return debug;
        }

        debug.pages.add(debugPage);
        nextPagePointer = OLongSerializer.INSTANCE.deserializeNative(content, content.length - OLongSerializer.LONG_SIZE);
        contentSize += content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE;

        firstEntry = false;
      } finally {
        releasePageFromRead(atomicOperation, cacheEntry);
      }

      pageIndex = getPageIndex(nextPagePointer);
      recordPosition = getRecordPosition(nextPagePointer);
    } while (nextPagePointer >= 0);
    debug.contentSize = contentSize;
    return debug;
  }

  public RECORD_STATUS getRecordStatus(final long clusterPosition) throws IOException {
    final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
    acquireSharedLock();
    try {
      final byte status = clusterPositionMap.getStatus(clusterPosition, atomicOperation);

      switch (status) {
      case OClusterPositionMapBucket.NOT_EXISTENT:
        return RECORD_STATUS.NOT_EXISTENT;
      case OClusterPositionMapBucket.ALLOCATED:
        return RECORD_STATUS.ALLOCATED;
      case OClusterPositionMapBucket.FILLED:
        return RECORD_STATUS.PRESENT;
      case OClusterPositionMapBucket.REMOVED:
        return RECORD_STATUS.REMOVED;
      }

      // UNREACHABLE
      return null;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public void acquireAtomicExclusiveLock() {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(this);
  }

  @Override
  public String toString() {
    return "plocal cluster: " + getName();
  }

  @Override
  public OClusterBrowsePage nextPage(final long lastPosition) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final OClusterPositionMapV1.OClusterPositionEntry[] nextPositions = clusterPositionMap
            .higherPositionsEntries(lastPosition, atomicOperation);
        if (nextPositions.length > 0) {
          final long newLastPosition = nextPositions[nextPositions.length - 1].getPosition();
          final List<OClusterBrowseEntry> nexv = new ArrayList<>(nextPositions.length);
          for (final OClusterPositionMapV1.OClusterPositionEntry pos : nextPositions) {
            final ORawBuffer buff = internalReadRecord(pos.getPosition(), pos.getPage(), pos.getOffset(), 1, atomicOperation);
            nexv.add(new OClusterBrowseEntry(pos.getPosition(), buff));
          }
          return new OClusterBrowsePage(nexv, newLastPosition);
        } else {
          return null;
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }
}
