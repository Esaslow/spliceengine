/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.si.data.hbase.coprocessor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import com.splicemachine.access.api.SnowflakeFactory;
import com.splicemachine.access.hbase.HSnowflakeFactory;
import com.splicemachine.access.util.ByteComparisons;
import com.splicemachine.si.api.txn.TransactionStore;
import com.splicemachine.si.api.txn.TxnFactory;
import com.splicemachine.si.api.txn.TxnLocationFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.zookeeper.RecoverableZooKeeper;
import com.splicemachine.access.HConfiguration;
import com.splicemachine.access.api.DistributedFileSystem;
import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.concurrent.Clock;
import com.splicemachine.si.api.data.ExceptionFactory;
import com.splicemachine.si.api.data.OperationStatusFactory;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.api.txn.TxnSupplier;
import com.splicemachine.si.data.HExceptionFactory;
import com.splicemachine.si.data.hbase.HOperationStatusFactory;
import com.splicemachine.si.impl.HBaseTxnStore;
import com.splicemachine.si.impl.SimpleTxnOperationFactory;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.si.impl.driver.SIEnvironment;
import com.splicemachine.storage.HNIOFileSystem;
import com.splicemachine.storage.PartitionInfoCache;
import com.splicemachine.timestamp.api.TimestampSource;
import com.splicemachine.timestamp.hbase.ZkTimestampSource;

/**
 * @author Scott Fines
 *         Date: 12/18/15
 */
public class HBaseSIEnvironment implements SIEnvironment{
    private static volatile HBaseSIEnvironment INSTANCE;
    private final TimestampSource timestampSource;
    private final PartitionFactory<TableName> partitionFactory;
    private final TransactionStore txnStore;
    private final TxnOperationFactory txnOpFactory;
    private final PartitionInfoCache partitionCache;
    private final SConfiguration config;
    private final Clock clock;
    private final DistributedFileSystem fileSystem;
    private final SnowflakeFactory snowflakeFactory;
    private SIDriver siDriver;


    public static HBaseSIEnvironment loadEnvironment(Clock clock,RecoverableZooKeeper rzk) throws IOException{
        HBaseSIEnvironment env = INSTANCE;
        if(env==null){
            synchronized(HBaseSIEnvironment.class){
                env = INSTANCE;
                if(env==null){
                    env = INSTANCE = new HBaseSIEnvironment(rzk,clock);
                }
            }
        }
        return env;
    }

    public static void setEnvironment(HBaseSIEnvironment siEnv){
        INSTANCE = siEnv;
    }

    public HBaseSIEnvironment(TimestampSource timeSource,Clock clock) throws IOException{
        ByteComparisons.setComparator(HBaseComparator.INSTANCE);
        this.config=HConfiguration.getConfiguration();
        this.timestampSource =timeSource;
        this.partitionCache = PartitionCacheService.loadPartitionCache(config);
        this.partitionFactory =TableFactoryService.loadTableFactory(clock,this.config,partitionCache);
        this.txnStore = new HBaseTxnStore(timestampSource,null);
        int completedTxnCacheSize = config.getCompletedTxnCacheSize();
        int completedTxnConcurrency = config.getCompletedTxnConcurrency();
        this.txnStore.setCache(txnSupplier);
        this.txnOpFactory = new SimpleTxnOperationFactory();
        this.clock = clock;
        this.snowflakeFactory = new HSnowflakeFactory();
        this.fileSystem =new HNIOFileSystem(FileSystem.get((Configuration) config.getConfigSource().unwrapDelegate()), exceptionFactory());
        siDriver = SIDriver.loadDriver(this);
    }

    @SuppressWarnings("unchecked")
    public HBaseSIEnvironment(RecoverableZooKeeper rzk,Clock clock) throws IOException{
        ByteComparisons.setComparator(HBaseComparator.INSTANCE);
        this.config=HConfiguration.getConfiguration();

        this.timestampSource =new ZkTimestampSource(config,rzk);
        this.partitionCache = PartitionCacheService.loadPartitionCache(config);
        this.partitionFactory =TableFactoryService.loadTableFactory(clock, this.config,partitionCache);
        this.txnStore = new HBaseTxnStore(timestampSource,null);
        int completedTxnCacheSize = config.getCompletedTxnCacheSize();
        int completedTxnConcurrency = config.getCompletedTxnConcurrency();
        this.txnStore.setCache(txnSupplier);
        this.txnOpFactory = new SimpleTxnOperationFactory();
        this.clock = clock;
        this.fileSystem =new HNIOFileSystem(FileSystem.get((Configuration) config.getConfigSource().unwrapDelegate()), exceptionFactory());
        this.snowflakeFactory = new HSnowflakeFactory();
        siDriver = SIDriver.loadDriver(this);
    }


    @Override public PartitionFactory tableFactory(){ return partitionFactory; }

    @Override
    public ExceptionFactory exceptionFactory(){
        return HExceptionFactory.INSTANCE;
    }

    @Override
    public SConfiguration configuration(){
        return config;
    }

    @Override
    public TransactionStore txnStore(){
        return txnStore;
    }

    @Override
    public OperationStatusFactory statusFactory(){
        return HOperationStatusFactory.INSTANCE;
    }

    @Override
    public TxnOperationFactory operationFactory(){
        return txnOpFactory;
    }

    @Override
    public SIDriver getSIDriver(){
        return siDriver;
    }

    @Override
    public PartitionInfoCache partitionInfoCache(){
        return partitionCache;
    }

    @Override
    public Clock systemClock(){
        return clock;
    }

    @Override
    public DistributedFileSystem fileSystem(){
        return fileSystem;
    }

    @Override
    public DistributedFileSystem fileSystem(String path) throws IOException, URISyntaxException  {
        return new HNIOFileSystem(FileSystem.get(new URI(path), (Configuration) config.getConfigSource().unwrapDelegate()), exceptionFactory());
    }

    public void setSIDriver(SIDriver siDriver) {
        this.siDriver = siDriver;
    }

    @Override
    public SnowflakeFactory snowflakeFactory() {
        return snowflakeFactory;
    }

    @Override
    public TxnFactory txnFactory() {
        return null;
    }

    @Override
    public TimestampSource logicalTimestampSource() {
        return null;
    }

    @Override
    public TxnLocationFactory txnLocationFactory() {
        return null;
    }

    @Override
    public TimestampSource physicalTimestampSource() {
        return null;
    }

    @Override
    public TxnSupplier globalTxnCache() {
        return null;
    }
}
