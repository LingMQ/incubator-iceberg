/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.google.common.collect.ImmutableList;
import java.util.Collection;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.ResidualEvaluator;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;

/**
 * A {@link Table} implementation that exposes a table's data files as rows.
 */
class DataFilesTable extends BaseMetadataTable {
  private final TableOperations ops;
  private final Table table;

  DataFilesTable(TableOperations ops, Table table) {
    this.ops = ops;
    this.table = table;
  }

  @Override
  Table table() {
    return table;
  }

  @Override
  String metadataTableName() {
    return "files";
  }

  @Override
  public TableScan newScan() {
    return new FilesTableScan(ops, table);
  }

  @Override
  public Schema schema() {
    return new Schema(DataFile.getType(table.spec().partitionType()).fields());
  }

  @Override
  public String location() {
    return table.currentSnapshot().manifestListLocation();
  }

  public static class FilesTableScan extends BaseTableScan {
    private static final long TARGET_SPLIT_SIZE = 32 * 1024 * 1024; // 32 MB

    FilesTableScan(TableOperations ops, Table table) {
      super(ops, table, ManifestEntry.getSchema(table.spec().partitionType()));
    }

    private FilesTableScan(
        TableOperations ops, Table table, Long snapshotId, Schema schema, Expression rowFilter,
        boolean caseSensitive, boolean colStats, Collection<String> selectedColumns) {
      super(ops, table, snapshotId, schema, rowFilter, caseSensitive, colStats, selectedColumns);
    }

    @Override
    protected TableScan newRefinedScan(
        TableOperations ops, Table table, Long snapshotId, Schema schema, Expression rowFilter,
        boolean caseSensitive, boolean colStats, Collection<String> selectedColumns) {
      return new FilesTableScan(
          ops, table, snapshotId, schema, rowFilter, caseSensitive, colStats, selectedColumns);
    }

    @Override
    protected long targetSplitSize(TableOperations ops) {
      return TARGET_SPLIT_SIZE;
    }

    @Override
    protected CloseableIterable<FileScanTask> planFiles(
        TableOperations ops, Snapshot snapshot, Expression rowFilter, boolean caseSensitive, boolean colStats) {
      CloseableIterable<ManifestFile> manifests = Avro
          .read(ops.io().newInputFile(snapshot.manifestListLocation()))
          .rename("manifest_file", GenericManifestFile.class.getName())
          .rename("partitions", GenericPartitionFieldSummary.class.getName())
          // 508 is the id used for the partition field, and r508 is the record name created for it in Avro schemas
          .rename("r508", GenericPartitionFieldSummary.class.getName())
          .project(ManifestFile.schema())
          .reuseContainers(false)
          .build();

      String schemaString = SchemaParser.toJson(schema());
      String specString = PartitionSpecParser.toJson(PartitionSpec.unpartitioned());

      return CloseableIterable.transform(manifests, manifest ->
          new ManifestReadTask(ops.io(), new BaseFileScanTask(
              DataFiles.fromManifest(manifest), schemaString, specString, ResidualEvaluator.unpartitioned(rowFilter))));
    }
  }

  private static class ManifestReadTask implements DataTask {
    private final FileIO io;
    private final FileScanTask manifestTask;

    private ManifestReadTask(FileIO io, FileScanTask manifestTask) {
      this.io = io;
      this.manifestTask = manifestTask;
    }

    @Override
    public CloseableIterable<StructLike> rows() {
      return CloseableIterable.transform(
          ManifestReader.read(io.newInputFile(manifestTask.file().path().toString())),
          file -> (GenericDataFile) file);
    }

    @Override
    public DataFile file() {
      return manifestTask.file();
    }

    @Override
    public PartitionSpec spec() {
      return manifestTask.spec();
    }

    @Override
    public long start() {
      return 0;
    }

    @Override
    public long length() {
      return manifestTask.length();
    }

    @Override
    public Expression residual() {
      return manifestTask.residual();
    }

    @Override
    public Iterable<FileScanTask> split(long splitSize) {
      return ImmutableList.of(this); // don't split
    }
  }
}
