/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.sdk.file;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.carbondata.common.annotations.InterfaceAudience;
import org.apache.carbondata.common.annotations.InterfaceStability;
import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datastore.filesystem.CarbonFile;
import org.apache.carbondata.core.datastore.impl.FileFactory;
import org.apache.carbondata.core.index.IndexFilter;
import org.apache.carbondata.core.index.IndexStoreManager;
import org.apache.carbondata.core.metadata.schema.table.CarbonTable;
import org.apache.carbondata.core.scan.expression.Expression;
import org.apache.carbondata.core.scan.model.ProjectionDimension;
import org.apache.carbondata.core.scan.model.QueryModel;
import org.apache.carbondata.core.util.CarbonProperties;
import org.apache.carbondata.core.util.CarbonSessionInfo;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.core.util.ThreadLocalSessionInfo;
import org.apache.carbondata.hadoop.CarbonInputSplit;
import org.apache.carbondata.hadoop.api.CarbonFileInputFormat;
import org.apache.carbondata.hadoop.api.CarbonTableInputFormat;
import org.apache.carbondata.hadoop.readsupport.CarbonReadSupport;
import org.apache.carbondata.hadoop.util.CarbonVectorizedRecordReader;
import org.apache.carbondata.processing.loading.iterator.CarbonOutputIteratorWrapper;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.JobContextImpl;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.log4j.Logger;

@InterfaceAudience.User
@InterfaceStability.Evolving
public class CarbonReaderBuilder {

  private String tablePath;
  private String[] projectionColumns;
  private Expression filterExpression;
  private String tableName;
  private Configuration hadoopConf;
  private boolean useVectorReader = true;
  private InputSplit inputSplit;
  private boolean useArrowReader;
  private boolean usePaginationReader;
  private List fileLists;
  private Class<? extends CarbonReadSupport> readSupportClass;

  private static final Logger LOGGER =
      LogServiceFactory.getLogService(CarbonOutputIteratorWrapper.class.getName());

  /**
   * Construct a CarbonReaderBuilder with table path and table name
   *
   * @param tablePath table path
   * @param tableName table name
   */
  CarbonReaderBuilder(String tablePath, String tableName) {
    this.tablePath = tablePath;
    this.tableName = tableName;
    ThreadLocalSessionInfo.setCarbonSessionInfo(new CarbonSessionInfo());
  }

  CarbonReaderBuilder(InputSplit inputSplit) {
    this.inputSplit = inputSplit;
    ThreadLocalSessionInfo.setCarbonSessionInfo(new CarbonSessionInfo());
  }

  /**
   * Construct a CarbonReaderBuilder with table name
   *
   * @param tableName table name
   */
  CarbonReaderBuilder(String tableName) {
    this.tableName = tableName;
    ThreadLocalSessionInfo.setCarbonSessionInfo(new CarbonSessionInfo());
  }

  /**
   * set carbonData file folder
   *
   * @param tablePath table path
   * @return CarbonReaderBuilder object
   */
  public CarbonReaderBuilder withFolder(String tablePath) {
    this.tablePath = tablePath;
    return this;
  }

  /**
   * set carbondata file lists
   *
   * @param fileLists carbondata file lists
   * @return CarbonReaderBuilder object
   */
  public CarbonReaderBuilder withFileLists(List fileLists) {
    if (null == this.fileLists) {
      this.fileLists = fileLists;
    } else {
      this.fileLists.addAll(fileLists);
    }
    return this;
  }

  /**
   * set one carbondata file
   *
   * @param file carbondata file
   * @return CarbonReaderBuilder object
   */
  public CarbonReaderBuilder withFile(String file) {
    List fileLists = new ArrayList();
    fileLists.add(file);
    return withFileLists(fileLists);
  }

  /**
   * set read support class
   * @param readSupportClass read support class
   * @return CarbonReaderBuilder object
   */
  public CarbonReaderBuilder withReadSupport(Class<? extends CarbonReadSupport> readSupportClass) {
    this.readSupportClass = readSupportClass;
    return this;
  }

  /**
   * Configure the projection column names of carbon reader
   *
   * @param projectionColumnNames projection column names
   * @return CarbonReaderBuilder object
   */
  public CarbonReaderBuilder projection(String[] projectionColumnNames) {
    Objects.requireNonNull(projectionColumnNames);
    this.projectionColumns = projectionColumnNames;
    return this;
  }

  /**
   * Accepts projection list
   *
   * @param projectionColumnNames
   * @return
   */
  public CarbonReaderBuilder projection(List<String> projectionColumnNames) {
    Objects.requireNonNull(projectionColumnNames);
    String[] strings = new String[projectionColumnNames.size()];
    for (int i = 0; i < projectionColumnNames.size(); i++) {
      strings[i] = projectionColumnNames.get(i);
    }
    return projection(strings);
  }

  /**
   * Configure the filter expression for carbon reader
   *
   * @param filterExpression filter expression
   * @return CarbonReaderBuilder object
   */
  public CarbonReaderBuilder filter(Expression filterExpression) {
    Objects.requireNonNull(filterExpression);
    this.filterExpression = filterExpression;
    return this;
  }

  /**
   * To support hadoop configuration
   *
   * @param conf hadoop configuration support, can set s3a AK,SK,end point and other conf with this
   * @return updated CarbonReaderBuilder
   */
  public CarbonReaderBuilder withHadoopConf(Configuration conf) {
    if (conf != null) {
      this.hadoopConf = conf;
    }
    return this;
  }

  /**
   * Sets the batch size of records to read
   *
   * @param batch batch size
   * @return updated CarbonReaderBuilder
   */
  public CarbonReaderBuilder withBatch(int batch) {
    CarbonProperties.getInstance()
        .addProperty(CarbonCommonConstants.DETAIL_QUERY_BATCH_SIZE,
            String.valueOf(batch));
    return this;
  }

  /**
   * Updates the hadoop configuration with the given key value
   *
   * @param key   key word
   * @param value value
   * @return this object
   */
  public CarbonReaderBuilder withHadoopConf(String key, String value) {
    if (this.hadoopConf == null) {
      this.hadoopConf = new Configuration();
    }
    this.hadoopConf.set(key, value);
    return this;
  }

  /**
   * Configure Row Record Reader for reading.
   *
   */
  public CarbonReaderBuilder withRowRecordReader() {
    this.useVectorReader = false;
    return this;
  }

  /**
   * To set the input split before build() to build the reader with the specified input split.
   *
   * @param inputSplit CarbonInputSplit
   */
  void setInputSplit(InputSplit inputSplit) {
    this.inputSplit = inputSplit;
  }

  /**
   * build Arrow carbon reader
   *
   * @param <T>
   * @return ArrowCarbonReader
   * @throws IOException
   * @throws InterruptedException
   */
  public <T> ArrowCarbonReader<T> buildArrowReader() throws IOException, InterruptedException {
    useArrowReader = true;
    return (ArrowCarbonReader<T>) this.build();
  }

  /**
   * If pagination reader is required then set builder for pagination support.
   *
   * @return CarbonReaderBuilder, current object with updated configuration.
   */
  public CarbonReaderBuilder withPaginationSupport() {
    usePaginationReader = true;
    return this;
  }

  /**
   * This interface is for python to call java.
   * Because python cannot use build() which returns superclass.
   * Casting is not possible from python for java objects.
   *
   * If pagination reader is required then set builder for pagination support.
   *
   * @return CarbonReaderBuilder, current object with updated configuration.
   */
  public <T> PaginationCarbonReader<T> buildPaginationReader()
      throws IOException, InterruptedException {
    usePaginationReader = true;
    return (PaginationCarbonReader<T>) this.build();
  }

  private CarbonFileInputFormat prepareFileInputFormat(Job job, boolean enableBlockletDistribution,
      boolean disableLoadBlockIndex) throws IOException {
    if (inputSplit != null && inputSplit instanceof CarbonInputSplit) {
      tablePath =
          ((CarbonInputSplit) inputSplit).getSegment().getReadCommittedScope().getFilePath();
      tableName = "UnknownTable" + UUID.randomUUID();
    }
    if (null == this.fileLists && null == tablePath) {
      throw new IllegalArgumentException("Please set table path first.");
    }
    // infer schema
    CarbonTable table;
    if (null != this.fileLists) {
      if (fileLists.size() < 1) {
        throw new IllegalArgumentException("fileLists must have one file in list as least!");
      }
      String commonString = String.valueOf(fileLists.get(0));
      for (int i = 1; i < fileLists.size(); i++) {
        commonString = commonString.substring(0, StringUtils.indexOfDifference(commonString,
            String.valueOf(fileLists.get(i))));
      }
      int index = commonString.lastIndexOf("/");
      commonString = commonString.substring(0, index);

      table = CarbonTable.buildTable(commonString, tableName, hadoopConf);
    } else {
      table = CarbonTable.buildTable(tablePath, tableName, hadoopConf);
    }
    if (enableBlockletDistribution) {
      // set cache level to blocklet level
      Map<String, String> tableProperties =
          table.getTableInfo().getFactTable().getTableProperties();
      tableProperties.put(CarbonCommonConstants.CACHE_LEVEL, "BLOCKLET");
      table.getTableInfo().getFactTable().setTableProperties(tableProperties);
    }
    final CarbonFileInputFormat format = new CarbonFileInputFormat();
    CarbonFileInputFormat.setTableInfo(job.getConfiguration(), table.getTableInfo());
    CarbonFileInputFormat.setTablePath(job.getConfiguration(), table.getTablePath());
    CarbonFileInputFormat.setTableName(job.getConfiguration(), table.getTableName());
    CarbonFileInputFormat.setDatabaseName(job.getConfiguration(), table.getDatabaseName());
    if (filterExpression != null) {
      CarbonFileInputFormat.setFilterPredicates(job.getConfiguration(),
          new IndexFilter(table, filterExpression, true));
    }
    if (null != this.fileLists) {
      format.setFileLists(this.fileLists);
    }
    if (projectionColumns != null) {
      // set the user projection
      int len = projectionColumns.length;
      for (int i = 0; i < len; i++) {
        if (projectionColumns[i].contains(".")) {
          throw new UnsupportedOperationException(
              "Complex child columns projection NOT supported through CarbonReader");
        }
      }
      CarbonFileInputFormat.setColumnProjection(job.getConfiguration(), projectionColumns);
    }
    if ((disableLoadBlockIndex) && (filterExpression == null)) {
      job.getConfiguration().set("filter_blocks", "false");
    }
    return format;
  }

  private <T> RecordReader getRecordReader(Job job, CarbonFileInputFormat format,
      List<RecordReader<Void, T>> readers, InputSplit split)
      throws IOException, InterruptedException {
    TaskAttemptContextImpl attempt =
        new TaskAttemptContextImpl(job.getConfiguration(), new TaskAttemptID());
    RecordReader reader;
    QueryModel queryModel = format.createQueryModel(split, attempt);
    boolean hasComplex = false;
    for (ProjectionDimension projectionDimension : queryModel.getProjectionDimensions()) {
      if (projectionDimension.getDimension().isComplex()) {
        hasComplex = true;
        break;
      }
    }
    if (useVectorReader && !hasComplex) {
      queryModel.setDirectVectorFill(filterExpression == null);
      reader = new CarbonVectorizedRecordReader(queryModel);
    } else {
      reader = format.createRecordReader(split, attempt);
    }
    try {
      reader.initialize(split, attempt);
    } catch (Exception e) {
      CarbonUtil.closeStreams(readers.toArray(new RecordReader[0]));
      throw e;
    }
    return reader;
  }

  /**
   * Build CarbonReader
   *
   * @param <T>
   * @return CarbonReader
   * @throws IOException
   * @throws InterruptedException
   */
  public <T> CarbonReader<T> build()
      throws IOException, InterruptedException {
    if (inputSplit != null) {
      return buildWithSplits(inputSplit);
    }
    if (hadoopConf == null) {
      hadoopConf = FileFactory.getConfiguration();
    }
    CarbonTableInputFormat.setCarbonReadSupport(hadoopConf, readSupportClass);
    final Job job = new Job(new JobConf(hadoopConf));
    CarbonFileInputFormat format = null;
    try {
      if (!usePaginationReader) {
        // block level dummy splits without IO and loading the cache (if filter not present)
        format = prepareFileInputFormat(job, false, true);
        List<InputSplit> splits =
            format.getSplits(new JobContextImpl(job.getConfiguration(), new JobID()));
        List<RecordReader<Void, T>> readers = new ArrayList<>(splits.size());
        for (InputSplit split : splits) {
          RecordReader reader = getRecordReader(job, format, readers, split);
          readers.add(reader);
        }
        if (useArrowReader) {
          return new ArrowCarbonReader<>(readers);
        }
        return new CarbonReader<>(readers);
      } else {
        // blocklet level splits formed by reading footer and loading the cache
        format = prepareFileInputFormat(job, true, false);
        List<InputSplit> splits =
            format.getSplits(new JobContextImpl(job.getConfiguration(), new JobID()));
        List<Long> rowCountInSplit = new ArrayList<>(splits.size());
        totalRowCountInSplits(job, splits, rowCountInSplit);
        return new PaginationCarbonReader(splits, this, rowCountInSplit);
      }
    } catch (Exception ex) {
      if (format != null) {
        // Clear the index cache as it can get added in getSplits() method
        IndexStoreManager.getInstance().clearIndexCache(
            format.getOrCreateCarbonTable((job.getConfiguration())).getAbsoluteTableIdentifier(),
            false);
      }
      throw ex;
    }
  }

  private <T> void totalRowCountInSplits(Job job, List<InputSplit> splits,
      List<Long> rowCountInSplit)
      throws IOException, InterruptedException {
    CarbonFileInputFormat format = this.prepareFileInputFormat(job, false, true);
    long sum = 0;
    boolean isIUDTable = false;
    if (!StringUtils.isEmpty(this.tablePath)) {
      // Check if update or delete happened on the table.
      CarbonFile emptyMetadataFile = FileFactory.getCarbonFile(this.tablePath +
          CarbonCommonConstants.FILE_SEPARATOR +
          CarbonCommonConstants.CARBON_SDK_EMPTY_METADATA_PATH, this.hadoopConf);
      if (emptyMetadataFile.exists() && emptyMetadataFile.isDirectory()) {
        isIUDTable = true;
      }
    }
    // if filter exists or IUD happened then read the total number of rows after
    // building carbon reader else get the row count from the details info of each splits.
    if (this.filterExpression != null || isIUDTable) {
      RecordReader reader = null;
      CarbonReader carbonReader = null;
      for (InputSplit split : splits) {
        List<RecordReader<Void, T>> readers = new ArrayList<>();
        try {
          reader = this.getRecordReader(job, format, readers, split);
          readers.add(reader);
          carbonReader = new CarbonReader<>(readers);
          while (carbonReader.hasNext()) {
            try {
              sum += carbonReader.readNextBatchRow().length;
            } catch (Exception ex) {
              LOGGER.error("Exception occured while reading the batch row " + ex.getMessage());
            }
          }
          rowCountInSplit.add(sum);
        } finally {
          if (reader != null) {
            reader.close();
          }
          if (carbonReader != null) {
            carbonReader.close();
          }
        }
      }
    } else {
      for (InputSplit split : splits) {
        // prepare a summation array of row counts in each blocklet,
        // this is used for pruning with pagination vales.
        // At current index, it contains sum of rows of all the blocklet from previous + current.
        sum += ((CarbonInputSplit) split).getDetailInfo().getRowCount();
        rowCountInSplit.add(sum);
      }
    }
  }

  private  <T> CarbonReader<T> buildWithSplits(InputSplit inputSplit)
      throws IOException, InterruptedException {
    if (hadoopConf == null) {
      hadoopConf = FileFactory.getConfiguration();
    }
    CarbonTableInputFormat.setCarbonReadSupport(hadoopConf, readSupportClass);
    final Job job = new Job(new JobConf(hadoopConf));
    CarbonFileInputFormat format = prepareFileInputFormat(job, false, true);
    format.setAllColumnProjectionIfNotConfigured(job,
        format.getOrCreateCarbonTable(job.getConfiguration()));
    try {
      List<RecordReader<Void, T>> readers = new ArrayList<>(1);
      RecordReader reader = getRecordReader(job, format, readers, inputSplit);
      readers.add(reader);
      if (useArrowReader) {
        return new ArrowCarbonReader<>(readers);
      } else {
        return new CarbonReader<>(readers);
      }
    } catch (Exception ex) {
      throw ex;
    }
  }

  /**
   * Gets an array of CarbonInputSplits.
   * In carbondata, splits can be block level or blocklet level.
   * by default splits are block level.
   *
   * @param enableBlockletDistribution, returns blocklet level splits if set to true,
   *                                    else block level splits.
   * @return
   * @throws IOException
   */
  public InputSplit[] getSplits(boolean enableBlockletDistribution) throws IOException {
    if (hadoopConf == null) {
      hadoopConf = FileFactory.getConfiguration();
    }
    Job job = null;
    List<InputSplit> splits;
    CarbonFileInputFormat format = null;
    try {
      job = new Job(new JobConf(hadoopConf));
      format = prepareFileInputFormat(job, enableBlockletDistribution, false);
      splits = format.getSplits(new JobContextImpl(job.getConfiguration(), new JobID()));
      for (InputSplit split : splits) {
        // Load the detailInfo
        ((CarbonInputSplit) split).getDetailInfo();
      }
    } finally {
      if (format != null) {
        // Clear the index cache as it is added in getSplits() method
        IndexStoreManager.getInstance().clearIndexCache(
            format.getOrCreateCarbonTable((job.getConfiguration())).getAbsoluteTableIdentifier(),
            false);
      }
    }
    return splits.toArray(new InputSplit[splits.size()]);
  }
}
