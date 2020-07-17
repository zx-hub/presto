/*
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
package io.prestosql.plugin.hive;

import com.google.common.collect.ImmutableList;
import io.prestosql.plugin.hive.HiveSplit.BucketConversion;
import io.prestosql.spi.HostAddress;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.airlift.slice.SizeOf.estimatedSizeOf;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

@NotThreadSafe
public class InternalHiveSplit
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(InternalHiveSplit.class).instanceSize() +
            ClassLayout.parseClass(String.class).instanceSize() +
            ClassLayout.parseClass(Properties.class).instanceSize() +
            ClassLayout.parseClass(String.class).instanceSize() +
            ClassLayout.parseClass(OptionalInt.class).instanceSize();

    private final String path;
    private final long end;
    private final long fileSize;
    private final long fileModifiedTime;
    private final Properties schema;
    private final List<HivePartitionKey> partitionKeys;
    private final List<InternalHiveBlock> blocks;
    private final String partitionName;
    private final OptionalInt bucketNumber;
    private final boolean splittable;
    private final boolean forceLocalScheduling;
    private final TableToPartitionMapping tableToPartitionMapping;
    private final Optional<BucketConversion> bucketConversion;
    private final boolean s3SelectPushdownEnabled;
    private final Optional<AcidInfo> acidInfo;

    private long start;
    private int currentBlockIndex;

    public InternalHiveSplit(
            String partitionName,
            String path,
            long start,
            long end,
            long fileSize,
            long fileModifiedTime,
            Properties schema,
            List<HivePartitionKey> partitionKeys,
            List<InternalHiveBlock> blocks,
            OptionalInt bucketNumber,
            boolean splittable,
            boolean forceLocalScheduling,
            TableToPartitionMapping tableToPartitionMapping,
            Optional<BucketConversion> bucketConversion,
            boolean s3SelectPushdownEnabled,
            Optional<AcidInfo> acidInfo)
    {
        checkArgument(start >= 0, "start must be positive");
        checkArgument(end >= 0, "length must be positive");
        checkArgument(fileSize >= 0, "fileSize must be positive");
        requireNonNull(partitionName, "partitionName is null");
        requireNonNull(path, "path is null");
        requireNonNull(schema, "schema is null");
        requireNonNull(partitionKeys, "partitionKeys is null");
        requireNonNull(blocks, "blocks is null");
        requireNonNull(bucketNumber, "bucketNumber is null");
        requireNonNull(tableToPartitionMapping, "tableToPartitionMapping is null");
        requireNonNull(bucketConversion, "bucketConversion is null");
        requireNonNull(acidInfo, "acidInfo is null");

        this.partitionName = partitionName;
        this.path = path;
        this.start = start;
        this.end = end;
        this.fileSize = fileSize;
        this.fileModifiedTime = fileModifiedTime;
        this.schema = schema;
        this.partitionKeys = ImmutableList.copyOf(partitionKeys);
        this.blocks = ImmutableList.copyOf(blocks);
        this.bucketNumber = bucketNumber;
        this.splittable = splittable;
        this.forceLocalScheduling = forceLocalScheduling;
        this.tableToPartitionMapping = tableToPartitionMapping;
        this.bucketConversion = bucketConversion;
        this.s3SelectPushdownEnabled = s3SelectPushdownEnabled;
        this.acidInfo = acidInfo;
    }

    public String getPath()
    {
        return path;
    }

    public long getStart()
    {
        return start;
    }

    public long getEnd()
    {
        return end;
    }

    public long getFileSize()
    {
        return fileSize;
    }

    public long getFileModifiedTime()
    {
        return fileModifiedTime;
    }

    public boolean isS3SelectPushdownEnabled()
    {
        return s3SelectPushdownEnabled;
    }

    public Properties getSchema()
    {
        return schema;
    }

    public List<HivePartitionKey> getPartitionKeys()
    {
        return partitionKeys;
    }

    public String getPartitionName()
    {
        return partitionName;
    }

    public OptionalInt getBucketNumber()
    {
        return bucketNumber;
    }

    public boolean isSplittable()
    {
        return splittable;
    }

    public boolean isForceLocalScheduling()
    {
        return forceLocalScheduling;
    }

    public TableToPartitionMapping getTableToPartitionMapping()
    {
        return tableToPartitionMapping;
    }

    public Optional<BucketConversion> getBucketConversion()
    {
        return bucketConversion;
    }

    public InternalHiveBlock currentBlock()
    {
        checkState(!isDone(), "All blocks have been consumed");
        return blocks.get(currentBlockIndex);
    }

    public boolean isDone()
    {
        return currentBlockIndex == blocks.size();
    }

    public void increaseStart(long value)
    {
        start += value;
        if (start == currentBlock().getEnd()) {
            currentBlockIndex++;
            if (isDone()) {
                return;
            }
            verify(start == currentBlock().getStart());
        }
    }

    public int getEstimatedSizeInBytes()
    {
        long result = INSTANCE_SIZE +
                estimatedSizeOf(path) +
                estimatedSizeOf(partitionKeys, HivePartitionKey::getEstimatedSizeInBytes) +
                estimatedSizeOf(blocks, InternalHiveBlock::getEstimatedSizeInBytes) +
                estimatedSizeOf(partitionName) +
                tableToPartitionMapping.getEstimatedSizeInBytes();
        return toIntExact(result);
    }

    public Optional<AcidInfo> getAcidInfo()
    {
        return acidInfo;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("path", path)
                .add("start", start)
                .add("end", end)
                .add("fileSize", fileSize)
                .toString();
    }

    public static class InternalHiveBlock
    {
        private static final int INSTANCE_SIZE = ClassLayout.parseClass(InternalHiveBlock.class).instanceSize();
        private static final int HOST_ADDRESS_INSTANCE_SIZE = ClassLayout.parseClass(HostAddress.class).instanceSize() +
                ClassLayout.parseClass(String.class).instanceSize();

        private final long start;
        private final long end;
        private final List<HostAddress> addresses;

        public InternalHiveBlock(long start, long end, List<HostAddress> addresses)
        {
            checkArgument(start <= end, "block end cannot be before block start");
            this.start = start;
            this.end = end;
            this.addresses = ImmutableList.copyOf(addresses);
        }

        public long getStart()
        {
            return start;
        }

        public long getEnd()
        {
            return end;
        }

        public List<HostAddress> getAddresses()
        {
            return addresses;
        }

        public long getEstimatedSizeInBytes()
        {
            return INSTANCE_SIZE + estimatedSizeOf(addresses, address -> HOST_ADDRESS_INSTANCE_SIZE + estimatedSizeOf(address.getHostText()));
        }
    }
}
