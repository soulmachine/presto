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
package com.facebook.presto.hive.orc;

import com.facebook.presto.hive.HiveColumnHandle;
import com.facebook.presto.hive.HiveType;
import com.facebook.presto.spi.Domain;
import com.facebook.presto.spi.Range;
import com.facebook.presto.spi.SortedRangeSet;
import com.facebook.presto.spi.TupleDomain;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Primitives;
import io.airlift.slice.Slice;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.BucketStatistics;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.DateStatistics;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.DoubleStatistics;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.IntegerStatistics;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.RowIndex;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.RowIndexEntry;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.StringStatistics;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static io.airlift.slice.Slices.utf8Slice;
import static org.apache.hadoop.hive.ql.io.orc.OrcProto.ColumnStatistics;

public final class OrcDomainExtractor
{
    private static final long MILLIS_IN_DAY = TimeUnit.DAYS.toMillis(1);

    private OrcDomainExtractor()
    {
    }

    public static List<TupleDomain<HiveColumnHandle>> extractDomain(
            TypeManager typeManager,
            Map<HiveColumnHandle, Integer> columnHandles,
            int rowsInStripe,
            int rowsInRowGroup,
            RowIndex[] indexes)
    {
        ImmutableList.Builder<TupleDomain<HiveColumnHandle>> rowGroupTupleDomains = ImmutableList.builder();

        int rowGroup = 0;
        for (int remainingRows = rowsInStripe; remainingRows > 0; remainingRows -= rowsInRowGroup) {
            int rows = Math.min(remainingRows, rowsInRowGroup);
            TupleDomain<HiveColumnHandle> rowGroupTupleDomain = extractDomain(typeManager, columnHandles, Arrays.asList(indexes), rowGroup, rows);
            rowGroupTupleDomains.add(rowGroupTupleDomain);
            rowGroup++;
        }

        return rowGroupTupleDomains.build();
    }

    public static TupleDomain<HiveColumnHandle> extractDomain(
            TypeManager typeManager,
            Map<HiveColumnHandle, Integer> columnHandles,
            List<RowIndex> rowIndexes,
            int rowGroup,
            long rowCount)
    {
        ImmutableMap.Builder<HiveColumnHandle, Domain> domains = ImmutableMap.builder();
        for (Entry<HiveColumnHandle, Integer> entry : columnHandles.entrySet()) {
            HiveColumnHandle columnHandle = entry.getKey();
            Integer streamIndex = entry.getValue();

            RowIndexEntry rowIndexEntry = rowIndexes.get(streamIndex).getEntry(rowGroup);
            Domain domain = getDomain(typeManager.getType(columnHandle.getTypeSignature()), rowCount, rowIndexEntry.hasStatistics() ? rowIndexEntry.getStatistics() : null);
            domains.put(columnHandle, domain);
        }
        return TupleDomain.withColumnDomains(domains.build());
    }

    public static TupleDomain<HiveColumnHandle> extractDomain(
            TypeManager typeManager,
            Map<HiveColumnHandle, Integer> columnHandles,
            long rowCount,
            List<ColumnStatistics> stripeColumnStatistics)
    {
        ImmutableMap.Builder<HiveColumnHandle, Domain> domains = ImmutableMap.builder();
        for (Entry<HiveColumnHandle, Integer> entry : columnHandles.entrySet()) {
            HiveColumnHandle columnHandle = entry.getKey();
            Integer columnIndex = entry.getValue();

            Domain domain = getDomain(toPrestoType(typeManager, columnHandle.getHiveType()), rowCount, stripeColumnStatistics.get(columnIndex));
            domains.put(columnHandle, domain);
        }
        return TupleDomain.withColumnDomains(domains.build());
    }

    private static Type toPrestoType(TypeManager typeManager, HiveType hiveType)
    {
        TypeInfo typeInfo = TypeInfoUtils.getTypeInfoFromTypeString(hiveType.getHiveTypeName());
        switch (typeInfo.getCategory()) {
            case MAP:
            case LIST:
            case STRUCT:
                return VARCHAR;
            case PRIMITIVE:
                PrimitiveTypeInfo primitiveTypeInfo = (PrimitiveTypeInfo) typeInfo;
                switch (primitiveTypeInfo.getPrimitiveCategory()) {
                    case BOOLEAN:
                        return BOOLEAN;
                    case BYTE:
                    case SHORT:
                    case INT:
                    case LONG:
                        return BIGINT;
                    case FLOAT:
                    case DOUBLE:
                        return DOUBLE;
                    case STRING:
                        return VARCHAR;
                    case DATE:
                        return DATE;
                    case TIMESTAMP:
                        return TIMESTAMP;
                    case BINARY:
                        return VARBINARY;
                }
        }
        throw new IllegalArgumentException("Unsupported hive type " + hiveType);
    }

    public static Domain getDomain(Type type, long rowCount, ColumnStatistics columnStatistics)
    {
        Class<?> boxedJavaType = Primitives.wrap(type.getJavaType());
        if (rowCount == 0) {
            return Domain.none(boxedJavaType);
        }

        if (columnStatistics == null) {
            return Domain.all(boxedJavaType);
        }

        if (columnStatistics.hasNumberOfValues() && columnStatistics.getNumberOfValues() == 0) {
            return Domain.onlyNull(boxedJavaType);
        }

        boolean hasNullValue = columnStatistics.getNumberOfValues() != rowCount;

        if (boxedJavaType == Boolean.class && columnStatistics.hasBucketStatistics()) {
            BucketStatistics bucketStatistics = columnStatistics.getBucketStatistics();

            boolean hasTrueValues = (bucketStatistics.getCount(0) != 0);
            boolean hasFalseValues = (columnStatistics.getNumberOfValues() != bucketStatistics.getCount(0));
            if (hasTrueValues && hasFalseValues) {
                return Domain.all(Boolean.class);
            }
            else if (hasTrueValues) {
                return Domain.create(SortedRangeSet.singleValue(true), hasNullValue);
            }
            else if (hasFalseValues) {
                return Domain.create(SortedRangeSet.singleValue(false), hasNullValue);
            }
        }
        else if (boxedJavaType == Long.class && columnStatistics.hasIntStatistics()) {
            IntegerStatistics integerStatistics = columnStatistics.getIntStatistics();
            if (integerStatistics.hasMinimum() && integerStatistics.hasMaximum()) {
                return Domain.create(SortedRangeSet.of(Range.range(integerStatistics.getMinimum(), true, integerStatistics.getMaximum(), true)), hasNullValue);
            }
            else if (integerStatistics.hasMaximum()) {
                return Domain.create(SortedRangeSet.of(Range.lessThanOrEqual(integerStatistics.getMaximum())), hasNullValue);
            }
            else if (integerStatistics.hasMinimum()) {
                return Domain.create(SortedRangeSet.of(Range.greaterThanOrEqual(integerStatistics.getMinimum())), hasNullValue);
            }
        }
        else if (boxedJavaType == Double.class && columnStatistics.hasDoubleStatistics()) {
            DoubleStatistics doubleStatistics = columnStatistics.getDoubleStatistics();
            if (doubleStatistics.hasMinimum() && doubleStatistics.hasMaximum()) {
                return Domain.create(SortedRangeSet.of(Range.range(doubleStatistics.getMinimum(), true, doubleStatistics.getMaximum(), true)), hasNullValue);
            }
            else if (doubleStatistics.hasMaximum()) {
                return Domain.create(SortedRangeSet.of(Range.lessThanOrEqual(doubleStatistics.getMaximum())), hasNullValue);
            }
            else if (doubleStatistics.hasMinimum()) {
                return Domain.create(SortedRangeSet.of(Range.greaterThanOrEqual(doubleStatistics.getMinimum())), hasNullValue);
            }
        }
        else if (boxedJavaType == Slice.class && columnStatistics.hasStringStatistics()) {
            StringStatistics stringStatistics = columnStatistics.getStringStatistics();
            if (stringStatistics.hasMinimum() && stringStatistics.hasMaximum()) {
                return Domain.create(SortedRangeSet.of(Range.range(utf8Slice(stringStatistics.getMinimum()), true, utf8Slice(stringStatistics.getMaximum()), true)), hasNullValue);
            }
            else if (stringStatistics.hasMaximum()) {
                return Domain.create(SortedRangeSet.of(Range.lessThanOrEqual(utf8Slice(stringStatistics.getMaximum()))), hasNullValue);
            }
            else if (stringStatistics.hasMinimum()) {
                return Domain.create(SortedRangeSet.of(Range.greaterThanOrEqual(utf8Slice(stringStatistics.getMinimum()))), hasNullValue);
            }
        }
        else if (boxedJavaType == Long.class && columnStatistics.hasDateStatistics()) {
            DateStatistics dateStatistics = columnStatistics.getDateStatistics();
            if (dateStatistics.hasMinimum() && dateStatistics.hasMaximum()) {
                return Domain.create(
                        SortedRangeSet.of(Range.range(dateStatistics.getMinimum() * MILLIS_IN_DAY, true, dateStatistics.getMaximum() * MILLIS_IN_DAY, true)),
                        hasNullValue);
            }
            else if (dateStatistics.hasMaximum()) {
                return Domain.create(SortedRangeSet.of(Range.lessThanOrEqual(dateStatistics.getMaximum() * MILLIS_IN_DAY)), hasNullValue);
            }
            else if (dateStatistics.hasMinimum()) {
                return Domain.create(SortedRangeSet.of(Range.greaterThanOrEqual(dateStatistics.getMinimum() * MILLIS_IN_DAY)), hasNullValue);
            }
        }
        return Domain.create(SortedRangeSet.all(boxedJavaType), hasNullValue);
    }
}