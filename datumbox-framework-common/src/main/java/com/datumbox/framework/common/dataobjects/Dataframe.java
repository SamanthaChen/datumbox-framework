/**
 * Copyright (C) 2013-2016 Vasilis Vryniotis <bbriniotis@datumbox.com>
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
package com.datumbox.framework.common.dataobjects;

import com.datumbox.framework.common.Configuration;
import com.datumbox.framework.common.concurrency.ForkJoinStream;
import com.datumbox.framework.common.interfaces.Copyable;
import com.datumbox.framework.common.persistentstorage.interfaces.DatabaseConnector;
import com.datumbox.framework.common.persistentstorage.interfaces.DatabaseConnector.MapType;
import com.datumbox.framework.common.persistentstorage.interfaces.DatabaseConnector.StorageHint;
import com.datumbox.framework.common.concurrency.StreamMethods;
import com.datumbox.framework.common.concurrency.ThreadMethods;
import com.datumbox.framework.common.interfaces.Extractable;
import com.datumbox.framework.development.switchers.DataframeMapType;
import com.datumbox.framework.development.switchers.DataframeMapTypeMark;
import com.datumbox.framework.development.switchers.SynchronizedBlocks;
import com.datumbox.framework.development.switchers.SynchronizedBlocksMark;
import com.datumbox.framework.common.utilities.StringCleaner;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Dataframe class stores a list of Records Objects and several meta-data. All
 * Machine Learning algorithms get as argument Dataframe objects. The class has an
 * internal static Builder class which can be used to generate Dataframe objects 
 * from Text or CSV files.
 * 
 * @author Vasilis Vryniotis <bbriniotis@datumbox.com>
 */
public class Dataframe implements Collection<Record>, Copyable<Dataframe> {
    
    /**
     * Internal name of the response variable.
     */
    public static final String COLUMN_NAME_Y = "~Y";
    
    /**
     * Internal name of the constant.
     */
    public static final String COLUMN_NAME_CONSTANT = "~CONSTANT";
    
    /**
     * The Builder is a utility class which can help you build Dataframe from
 Text files and CSV files.
     */
    public static class Builder {
        
        /**
         * It builds a Dataframe object from a provided list of text files. The data
 map should have as index the names of each class and as values the URIs
 of the training files. The files should contain one training example
 per row. If we want to parse a Text File of unknown category then
 pass a single URI with null as key.
 
 The method requires as arguments a file with the category names and locations
 of the training files, an instance of a TextExtractor which is used
 to extract the keywords from the documents and the Database Configuration
 Object.
         * 
         * @param textFilesMap
         * @param textExtractor
         * @param conf
         * @return 
         */
        public static Dataframe parseTextFiles(Map<Object, URI> textFilesMap, Extractable textExtractor, Configuration conf) {
            Dataframe dataset = new Dataframe(conf);
            Logger logger = LoggerFactory.getLogger(Dataframe.Builder.class);
            
            for (Map.Entry<Object, URI> entry : textFilesMap.entrySet()) {
                Object theClass = entry.getKey();
                URI datasetURI = entry.getValue();
                
                logger.info("Dataset Parsing {} class", theClass);
                
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(new File(datasetURI)), "UTF8"))) {
                    final int baseCounter = dataset.size(); //because we read multiple files we need to keep track of all records added earlier
                    ThreadMethods.throttledExecution(StreamMethods.enumerate(br.lines()), e -> { 
                        Integer rId = baseCounter + e.getKey();
                        String line = e.getValue();
                        
                        AssociativeArray xData = new AssociativeArray(
                                textExtractor.extract(StringCleaner.clear(line))
                        );
                        Record r = new Record(xData, theClass);

                        //we call below the recalculateMeta()
                        if(SynchronizedBlocks.WITHOUT_SYNCHRONIZED.isActivated()) {
                            dataset.set(rId, r); 
                        }
                        else {
                            synchronized(dataset) {
                                dataset.set(rId, r);
                            }
                        }
                    }, conf.getConcurrencyConfig());
                } 
                catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
            
            return dataset;
        }
        
        /**
         * It builds a Dataframe object from a CSV file; the first line of the provided 
         * CSV file must have a header with the column names.
         * 
         * The method accepts the following arguments: A Reader object from where
         * we will read the contents of the csv file. The name column of the 
         * response variable y. A map with the column names and their respective
         * DataTypes. The char delimiter for the columns, the char for quotes and
         * the string of the record/row separator. The Database Configuration
         * object.
         * 
         * @param reader
         * @param yVariable
         * @param headerDataTypes
         * @param delimiter
         * @param quote
         * @param recordSeparator
         * @param skip
         * @param limit
         * @param conf
         * @return 
         */
        public static Dataframe parseCSVFile(Reader reader, String yVariable, LinkedHashMap<String, TypeInference.DataType> headerDataTypes, 
                                           char delimiter, char quote, String recordSeparator, Long skip, Long limit, Configuration conf) {
            Logger logger = LoggerFactory.getLogger(Dataframe.Builder.class);
            
            if(skip == null) {
                skip = 0L;
            }
            
            if(limit == null) {
                limit = Long.MAX_VALUE;
            }
            
            logger.info("Parsing CSV file");
            
            if (!headerDataTypes.containsKey(yVariable)) {
                logger.warn("WARNING: The file is missing the response variable column {}.", yVariable);
            }
            
            TypeInference.DataType yDataType = headerDataTypes.get(yVariable);
            Map<String, TypeInference.DataType> xDataTypes = new HashMap<>(headerDataTypes); //copy header types
            xDataTypes.remove(yVariable); //remove the response variable from xDataTypes
            Dataframe dataset = new Dataframe(conf, yDataType, xDataTypes); //use the private constructor to pass DataTypes directly and avoid updating them on the fly
            
            
            CSVFormat format = CSVFormat
                                .RFC4180
                                .withHeader()
                                .withDelimiter(delimiter)
                                .withQuote(quote)
                                .withRecordSeparator(recordSeparator);
            
            try (final CSVParser parser = new CSVParser(reader, format)) { 
                ThreadMethods.throttledExecution(StreamMethods.enumerate(StreamMethods.stream(parser.spliterator(), false)).skip(skip).limit(limit), e -> { 
                    Integer rId = e.getKey();
                    CSVRecord row = e.getValue();
                
                    if (!row.isConsistent()) {
                        logger.warn("WARNING: Skipping row {} because its size does not match the header size.", row.getRecordNumber());
                    }
                    else {
                        Object y = null;
                        AssociativeArray xData = new AssociativeArray();
                        for (Map.Entry<String, TypeInference.DataType> entry : headerDataTypes.entrySet()) {
                            String column = entry.getKey();
                            TypeInference.DataType dataType = entry.getValue();

                            Object value = TypeInference.DataType.parse(row.get(column), dataType); //parse the string value according to the DataType
                            if (yVariable != null && yVariable.equals(column)) {
                                y = value;
                            } 
                            else {
                                xData.put(column, value);
                            }
                        }
                        
                        Record r = new Record(xData, y);
                        
                        //use the internal unsafe methods to avoid the update of the Metas. 
                        //The Metas are already set in the construction of the Dataframe.
                        if(SynchronizedBlocks.WITHOUT_SYNCHRONIZED.isActivated()) {
                            dataset._unsafe_set(rId, r); 
                        }
                        else {
                            synchronized(dataset) {
                                dataset._unsafe_set(rId, r);
                            }
                        }
                    }
                }, conf.getConcurrencyConfig());
            } 
            catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            return dataset;
        }

    }    
    
    private TypeInference.DataType yDataType; 
    private Map<Object, TypeInference.DataType> xDataTypes;
    private Map<Integer, Record> records;
    
    private final DatabaseConnector dbc; 
    private final Configuration conf; 
    
    /**
     * This executor is used for the parallel processing of streams with custom 
     * Thread pool.
     */
    protected final ForkJoinStream streamExecutor;
    
    /**
     * Keeps a counter of the id that will be used for the next record.
     */
    @SynchronizedBlocksMark(options={SynchronizedBlocks.WITH_SYNCHRONIZED})
    private int nextAvailableRecordId = 0;
    
    @SynchronizedBlocksMark(options={SynchronizedBlocks.WITHOUT_SYNCHRONIZED})
    private final AtomicInteger atomicNextAvailableRecordId = new AtomicInteger();
    
    @DataframeMapTypeMark(options={DataframeMapType.HASHMAP})
    private Set<Integer> index = new ConcurrentSkipListSet<>();
    
    /**
     * Public constructor of Dataframe.
     * 
     * @param conf 
     */
    public Dataframe(Configuration conf) {
        this.conf = conf;
        
        //we dont need to have a unique name, because it is not used by the connector on the current implementations
        //String dbName = "dts_"+new BigInteger(130, RandomGenerator.getThreadLocalRandom()).toString(32);
        String dbName = "dts";
        dbc = this.conf.getDbConfig().getConnector(dbName);
        
        records = dbc.getBigMap("tmp_records", (DataframeMapType.HASHMAP.isActivated())?MapType.HASHMAP:MapType.TREEMAP, StorageHint.IN_DISK, SynchronizedBlocks.WITHOUT_SYNCHRONIZED.isActivated(), true);
        
        yDataType = null;
        xDataTypes = dbc.getBigMap("tmp_xDataTypes", MapType.HASHMAP, StorageHint.IN_MEMORY, SynchronizedBlocks.WITHOUT_SYNCHRONIZED.isActivated(), true);
        
        streamExecutor = new ForkJoinStream(this.conf.getConcurrencyConfig());
    }
    
    /**
     * Private constructor used by the Builder inner static class.
     * 
     * @param conf
     * @param yDataType
     * @param xDataTypes 
     */
    private Dataframe(Configuration conf, TypeInference.DataType yDataType, Map<String, TypeInference.DataType> xDataTypes) {
        this(conf);
        this.yDataType = yDataType;
        this.xDataTypes.putAll(xDataTypes);
    }
    
    
    //Mandatory Collection Methods
    
    /**
     * Returns the total number of Records of the Dataframe.
     * 
     * @return 
     */
    @Override
    public int size() {
        return records.size();
    }
    
    /**
     * Checks if the Dataframe is empty.
     * 
     * @return 
     */
    @Override
    public boolean isEmpty() {
        return records.isEmpty();
    }
    
    /**
     * Clears all the internal Records of the Dataframe. The Dataframe can be used
     * after you clear it.
     */
    @Override
    public void clear() {
        yDataType = null;
        
        xDataTypes.clear();
        records.clear();
        if(DataframeMapType.HASHMAP.isActivated()) {
            index.clear();
        }
    }

    /**
     * Adds a record in the Dataframe and updates the Meta data. 
     * 
     * @param r
     * @return 
     */
    @Override
    public boolean add(Record r) {
        addRecord(r);
        return true;
    }
    
    /**
     * Checks if the Record exists in the Dataframe. Note that the Record is checked only
     * for its x and y components.
     * 
     * @param o
     * @return 
     */
    @Override
    public boolean contains(Object o) {
        return records.containsValue((Record)o);
    }
    
    /** {@inheritDoc} */
    @Override
    public boolean addAll(Collection<? extends Record> c) {
        c.stream().forEach(r -> {
            add(r);
        });
        return true;
    }
    
    /** {@inheritDoc} */
    @Override
    public boolean containsAll(Collection<?> c) {
        return records.values().containsAll(c);
    }
    
    /** {@inheritDoc} */
    @Override
    public Object[] toArray() {
        Object[] array = new Object[size()];
        int i = 0;
        for(Record r : values()) {
            array[i++] = r;
        }
        return array;
    }
    
    /** {@inheritDoc} */      
    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        int size = size();
        if (a.length < size) {
            a = (T[])java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
        }
        int i = 0;
        for (Record r : values()) {
            a[i++] = (T) r;
        }
        return a;
    }
    
    /**
     * Returns a read-only iterator on the values of the Dataframe.
     * 
     * @return 
     */
    @Override
    public Iterator<Record> iterator() {
        return values().iterator();
    }
    
    /** {@inheritDoc} */
    @Override
    public Stream<Record> stream() {
        return StreamMethods.stream(values(), false);
    }
    
    //Optional Collection Methods
    
    /**
     * Removes the first occurrence of the specified element from this Dataframe, 
     * if it is present and it does not update the metadata.
     * 
     * @param o
     * @return 
     */
    @Override
    public boolean remove(Object o) {
        Integer id = indexOf((Record) o);
        if(id == null) {
            return false;
        }
        remove(id);
        return true;
    }
    
    /**
     * Removes all of this collection's elements that are also contained in the
     * specified collection and updates the metadata.
     * 
     * @param c
     * @return 
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for(Object o : c) {
            modified |= remove((Record)o);
        }
        if(modified) {
            recalculateMeta();
        }
        return modified;
    }

    /**
     * Retains only the elements in this collection that are contained in the
     * specified collection and updates the meta data.
     * 
     * @param c
     * @return 
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        boolean modified = false;
        for(Map.Entry<Integer, Record> e : entries()) {
            Integer rId = e.getKey();
            Record r = e.getValue();
            if(!c.contains(r)) {
                remove(rId);
                modified = true;
            }
        }
        if(modified) {
            recalculateMeta();
        }
        return modified;
    }
    
    
    //Other methods

    /**
     * Removes a record with a particular id from the Dataframe but does not update
     * the metadata.
     * 
     * @param id
     * @return 
     */
    public Record remove(Integer id) {
        Record r = records.remove(id);
        
        if(DataframeMapType.HASHMAP.isActivated()) {
            if(r != null) {
                index.remove(id);
            }            
        }

        return r;
    }
    
    /**
     * Returns the index of the first occurrence of the specified element in this 
     * Dataframe, or null if this Dataframe does not contain the element.
     * WARNING: The Recordsare checked only for their X and Y values, not for 
     * the yPredicted and yPredictedProbabilities values.
     * 
     * @param o
     * @return 
     */
    public Integer indexOf(Record o) {
        if(o!=null) {
            for(Map.Entry<Integer, Record> e : entries()) {
                Integer rId = e.getKey();
                Record r = e.getValue();
                if(o.equals(r)) {
                    return rId;
                }
            }
        }
        return null;
    }
    
    /**
     * Returns a particular Record using its id.
     * 
     * @param id
     * @return 
     */
    public Record get(Integer id) {
        return records.get(id);
    }
    
    /**
     * Adds a Record in the Dataframe and returns its id.
     * 
     * @param r
     * @return 
     */
    public Integer addRecord(Record r) {
        Integer rId = _unsafe_add(r);
        updateMeta(r);
        return rId;
    }
    
    /**
     * Sets the record of a particular id in the dataset. If the record does not
     * exist it will be added with the specific id and the next added record will
     * have as id the next integer.
     * 
     * Note that the meta-data are partially updated. This means that if the replaced 
     * Record contained a column which is now no longer available in the dataset,
     * then the meta-data will not refect this update (the column will continue to exist
     * in the meta data). If this is a problem, you should call the recalculateMeta()
     * method to force them being recalculated.
     * 
     * @param rId
     * @param r
     * @return 
     */
    public Integer set(Integer rId, Record r) {
        _unsafe_set(rId, r);
        updateMeta(r);
        return rId;
    }
    
    /**
     * Returns the total number of X columns in the Dataframe.
     * 
     * @return 
     */
    public int xColumnSize() {
        return xDataTypes.size();
    }
    
    /**
     * Returns the type of the response variable y.
     * 
     * @return 
     */
    public TypeInference.DataType getYDataType() {
        return yDataType;
    }
    
    /**
     * Returns an Map with column names as index and DataTypes as values.
     * 
     * @return 
     */
    public Map<Object, TypeInference.DataType> getXDataTypes() {
        return Collections.unmodifiableMap(xDataTypes);
    }
    
    /**
     * It extracts the values of a particular column from all records and
     * stores them into an FlatDataList.
     * 
     * @param column
     * @return 
     */
    public FlatDataList getXColumn(Object column) {
        FlatDataList flatDataList = new FlatDataList();
        
        for(Record r : values()) {
            flatDataList.add(r.getX().get(column));
        }
        
        return flatDataList;
    }
    
    /**
     * It extracts the values of the response variables from all observations and
     * stores them into an FlatDataList.
     * 
     * @return 
     */
    public FlatDataList getYColumn() {
        FlatDataList flatDataList = new FlatDataList();
        
        for(Record r : values()) {
            flatDataList.add(r.getY());
        }
        
        return flatDataList;
    }
    
    /**
     * Removes completely a list of columns from the dataset. The meta-data of 
     * the Dataframe are updated. The method internally uses threads.
     * 
     * @param columnSet
     */
    public void dropXColumns(Set<Object> columnSet) {  
        columnSet.retainAll(xDataTypes.keySet()); //keep only those columns that are already known to the Meta data of the Dataframe
        
        if(columnSet.isEmpty()) {
            return;
        }
        
        //remove all the columns from the Meta data
        xDataTypes.keySet().removeAll(columnSet);
        
        streamExecutor.forEach(StreamMethods.stream(entries(), true), e -> { 
            Integer rId = e.getKey();
            Record r = e.getValue();
            
            AssociativeArray xData = r.getX().copy();
            boolean modified = xData.keySet().removeAll(columnSet);
            
            if(modified) {
                Record newR = new Record(xData, r.getY(), r.getYPredicted(), r.getYPredictedProbabilities());
                
                //safe to call in this context. we already updated the meta when we modified the xDataTypes
                if(SynchronizedBlocks.WITHOUT_SYNCHRONIZED.isActivated()) {
                    _unsafe_set(rId, newR); 
                }
                else {
                    synchronized(this) {
                        _unsafe_set(rId, newR); 
                    }                    
                }
            }
        });
        
    }
    
    /**
     * It generates and returns a new Dataframe which contains a subset of this Dataframe. 
     * All the Records of the returned Dataframe are copies of the original Records. 
     * The method is used for k-fold cross validation and sampling. Note that the 
     * Records in the new Dataframe have DIFFERENT ids from the original ones.
     * 
     * @param idsCollection
     * @return 
     */
    public Dataframe getSubset(FlatDataList idsCollection) {
        Dataframe d = new Dataframe(conf);
        
        for(Object id : idsCollection) {
            d.add(get((Integer)id)); 
        }        
        return d;
    }
    
    /**
     * It forces the recalculation of Meta data using the Records of the dataset.
     */
    public void recalculateMeta() {
        yDataType = null;
        xDataTypes.clear();
        for(Record r : values()) {
            updateMeta(r);
        }
    }
    
    /** {@inheritDoc} */
    @Override
    public Dataframe copy() {
        Dataframe d = new Dataframe(conf);
        
        for(Map.Entry<Integer, Record> e : entries()) {
            Integer rId = e.getKey();
            Record r = e.getValue();
            d.set(rId, r); 
        }        
        return d;
    }
    
    /**
     * Deletes the Dataframe and removes all internal variables. Once you delete a
     * dataset, the instance can no longer be used.
     */
    public void delete() {
        dbc.dropBigMap("tmp_records", records);
        dbc.dropBigMap("tmp_xDataTypes", xDataTypes);
        dbc.clear();
        try {
            dbc.close();
        } 
        catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        
        //Ensures that the Dataframe can't be used after delete() is called.
        yDataType = null;
        xDataTypes = null;
        records = null;
        if(DataframeMapType.HASHMAP.isActivated()) {
            index = null;
        }
    }
    
    /**
     * Returns a read-only Iterable on the keys and Records of the Dataframe.
     * 
     * @return 
     */
    public Iterable<Map.Entry<Integer, Record>> entries() {
        if(DataframeMapType.HASHMAP.isActivated()) {
            return () -> new Iterator<Map.Entry<Integer, Record>>() {
                private final Iterator<Integer> it = index.iterator();

                /** {@inheritDoc} */
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                /** {@inheritDoc} */
                @Override
                public Map.Entry<Integer, Record> next() {
                    Integer rId = it.next();
                    return new AbstractMap.SimpleImmutableEntry<>(rId, records.get(rId));
                }

                /** {@inheritDoc} */
                @Override
                public void remove() {
                    throw new UnsupportedOperationException("This is a read-only iterator, remove operation is not supported.");
                }
            };
        }
        else {
            return () -> new Iterator<Map.Entry<Integer, Record>>() {
                private final Iterator<Map.Entry<Integer, Record>> it = records.entrySet().iterator();

                /** {@inheritDoc} */
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                /** {@inheritDoc} */
                @Override
                public Map.Entry<Integer, Record> next() {
                    return it.next();
                }

                /** {@inheritDoc} */
                @Override
                public void remove() {
                    throw new UnsupportedOperationException("This is a read-only iterator, remove operation is not supported.");
                }
            };
        }
    }
    
    /**
     * Returns a read-only Iterable on the keys of the Dataframe.
     * 
     * @return 
     */
    public Iterable<Integer> index() {
        return () -> new Iterator<Integer>() {
            private final Iterator<Integer> it = (DataframeMapType.HASHMAP.isActivated())?index.iterator():records.keySet().iterator();
            
            /** {@inheritDoc} */
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }
            
            /** {@inheritDoc} */
            @Override
            public Integer next() {
                return it.next();
            }
            
            /** {@inheritDoc} */
            @Override
            public void remove() {
                throw new UnsupportedOperationException("This is a read-only iterator, remove operation is not supported.");
            }
        };
    }
    
    /**
     * Returns a read-only Iterable on the values of the Dataframe.
     * 
     * @return 
     */
    public Iterable<Record> values() {
        if(DataframeMapType.HASHMAP.isActivated()) {
            return () -> new Iterator<Record>(){

                private final Iterator<Integer> it = index.iterator();

                /** {@inheritDoc} */
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                /** {@inheritDoc} */
                @Override
                public Record next() {
                    Integer id = it.next();
                    return records.get(id);
                }

                /** {@inheritDoc} */
                @Override
                public void remove() {
                    throw new UnsupportedOperationException("This is a read-only iterator, remove operation is not supported.");
                }
            };
        }
        else {
            return () -> new Iterator<Record>(){

                private final Iterator<Record> it = records.values().iterator();

                /** {@inheritDoc} */
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                /** {@inheritDoc} */
                @Override
                public Record next() {
                    return it.next();
                }

                /** {@inheritDoc} */
                @Override
                public void remove() {
                    throw new UnsupportedOperationException("This is a read-only iterator, remove operation is not supported.");
                }
            };
        }
    }
    
    /**
     * Sets the record in a particular position in the dataset, WITHOUT updating
     * the internal meta-info and returns the previous value (null if not existed). 
     * This method is similar to set() and it allows quick updates 
     * on the dataset. Nevertheless it is not advised to use this method because 
     * unless you explicitly call the recalculateMeta() method, the meta data
     * will be corrupted. If you do use this method, MAKE sure you perform the 
     * recalculation after you are done with the updates.
     * 
     * @param rId
     * @param r 
     * @return  
     */
    public Record _unsafe_set(Integer rId, Record r) {
        //move ahead the next id
        if(SynchronizedBlocks.WITHOUT_SYNCHRONIZED.isActivated()) {
            atomicNextAvailableRecordId.updateAndGet(x -> (x<rId)?Math.max(x+1,rId+1):x);
        }
        else {
            if(nextAvailableRecordId<rId) {
                nextAvailableRecordId = rId+1; 
            }
        }
        
        Record old = records.put(rId, r);
        if(old == null) {
            if(DataframeMapType.HASHMAP.isActivated()) {
                index.add(rId);
            }
        }
        
        return old;
    }
    
    /**
     * Adds the record in the dataset without updating the Meta. The add method 
     * returns the id of the new record.
     * 
     * @param r
     * @return 
     */
    private Integer _unsafe_add(Record r) {
        Integer newId;
        if(SynchronizedBlocks.WITHOUT_SYNCHRONIZED.isActivated()) {
            newId = atomicNextAvailableRecordId.getAndIncrement();
        }
        else {
            newId = nextAvailableRecordId++;
        }
        records.put(newId, r);
        
        if(DataframeMapType.HASHMAP.isActivated()) {
            index.add(newId);
        }

        return newId;
    }
    
    /**
     * Protected getter for the DatabaseConnector of the Dataframe. It is used
     * by the DataframeMatrix.
     * 
     * @return 
     */
    public DatabaseConnector getDbc() {
        return dbc;
    }
    
    /**
     * Updates the meta data of the Dataframe using the provided Record. 
     * The Meta-data include the supported columns and their DataTypes.
     * 
     * @param r 
     */
    private void updateMeta(Record r) {
        for(Map.Entry<Object, Object> entry : r.getX().entrySet()) {
            Object column = entry.getKey();
            Object value = entry.getValue();
            
            if(value!=null) {
                xDataTypes.putIfAbsent(column, TypeInference.getDataType(value));
            }
        }
        
        if(yDataType == null) {
            Object value = r.getY();
            if(value!=null) {
                yDataType = TypeInference.getDataType(r.getY());
            }
        }
    }
    
}