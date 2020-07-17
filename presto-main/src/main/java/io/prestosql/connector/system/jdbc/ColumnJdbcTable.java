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
package io.prestosql.connector.system.jdbc;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slices;
import io.prestosql.FullConnectorSession;
import io.prestosql.Session;
import io.prestosql.connector.system.SystemColumnHandle;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.QualifiedTablePrefix;
import io.prestosql.security.AccessControl;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.Constraint;
import io.prestosql.spi.connector.InMemoryRecordSet;
import io.prestosql.spi.connector.InMemoryRecordSet.Builder;
import io.prestosql.spi.connector.RecordCursor;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.NullableValue;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.CharType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarcharType;

import javax.inject.Inject;

import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.connector.system.jdbc.FilterUtil.tablePrefix;
import static io.prestosql.connector.system.jdbc.FilterUtil.tryGetSingleVarcharValue;
import static io.prestosql.metadata.MetadataListing.listCatalogs;
import static io.prestosql.metadata.MetadataListing.listSchemas;
import static io.prestosql.metadata.MetadataListing.listTableColumns;
import static io.prestosql.metadata.MetadataListing.listTables;
import static io.prestosql.metadata.MetadataUtil.TableMetadataBuilder.tableMetadataBuilder;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.Chars.isCharType;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimeType.TIME;
import static io.prestosql.spi.type.TimeWithTimeZoneType.TIME_WITH_TIME_ZONE;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static io.prestosql.spi.type.Varchars.isVarcharType;
import static io.prestosql.type.TypeUtils.getDisplayLabel;
import static java.util.Objects.requireNonNull;

public class ColumnJdbcTable
        extends JdbcTable
{
    public static final SchemaTableName NAME = new SchemaTableName("jdbc", "columns");

    private static final int MAX_DOMAIN_SIZE = 100;

    private static final ColumnHandle TABLE_CATALOG_COLUMN = new SystemColumnHandle("table_cat");
    private static final ColumnHandle TABLE_SCHEMA_COLUMN = new SystemColumnHandle("table_schem");
    private static final ColumnHandle TABLE_NAME_COLUMN = new SystemColumnHandle("table_name");

    public static final ConnectorTableMetadata METADATA = tableMetadataBuilder(NAME)
            .column("table_cat", createUnboundedVarcharType())
            .column("table_schem", createUnboundedVarcharType())
            .column("table_name", createUnboundedVarcharType())
            .column("column_name", createUnboundedVarcharType())
            .column("data_type", BIGINT)
            .column("type_name", createUnboundedVarcharType())
            .column("column_size", BIGINT)
            .column("buffer_length", BIGINT)
            .column("decimal_digits", BIGINT)
            .column("num_prec_radix", BIGINT)
            .column("nullable", BIGINT)
            .column("remarks", createUnboundedVarcharType())
            .column("column_def", createUnboundedVarcharType())
            .column("sql_data_type", BIGINT)
            .column("sql_datetime_sub", BIGINT)
            .column("char_octet_length", BIGINT)
            .column("ordinal_position", BIGINT)
            .column("is_nullable", createUnboundedVarcharType())
            .column("scope_catalog", createUnboundedVarcharType())
            .column("scope_schema", createUnboundedVarcharType())
            .column("scope_table", createUnboundedVarcharType())
            .column("source_data_type", BIGINT)
            .column("is_autoincrement", createUnboundedVarcharType())
            .column("is_generatedcolumn", createUnboundedVarcharType())
            .build();

    private final Metadata metadata;
    private final AccessControl accessControl;

    @Inject
    public ColumnJdbcTable(Metadata metadata, AccessControl accessControl)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
    }

    @Override
    public ConnectorTableMetadata getTableMetadata()
    {
        return METADATA;
    }

    @Override
    public TupleDomain<ColumnHandle> applyFilter(ConnectorSession connectorSession, Constraint constraint)
    {
        TupleDomain<ColumnHandle> tupleDomain = constraint.getSummary();
        if (tupleDomain.isNone() || constraint.predicate().isEmpty()) {
            return tupleDomain;
        }
        Predicate<Map<ColumnHandle, NullableValue>> predicate = constraint.predicate().get();
        Set<ColumnHandle> predicateColumns = constraint.getColumns().orElseThrow(() -> new VerifyException("columns not present for a predicate"));

        boolean hasSchemaPredicate = predicateColumns.contains(TABLE_SCHEMA_COLUMN);
        boolean hasTablePredicate = predicateColumns.contains(TABLE_NAME_COLUMN);
        if (!hasSchemaPredicate && !hasTablePredicate) {
            // No filter on schema name and table name at all.
            return tupleDomain;
        }

        Session session = ((FullConnectorSession) connectorSession).getSession();

        Optional<String> catalogFilter = tryGetSingleVarcharValue(tupleDomain, TABLE_CATALOG_COLUMN);
        Optional<String> schemaFilter = tryGetSingleVarcharValue(tupleDomain, TABLE_SCHEMA_COLUMN);
        Optional<String> tableFilter = tryGetSingleVarcharValue(tupleDomain, TABLE_NAME_COLUMN);

        if (schemaFilter.isPresent() && tableFilter.isPresent()) {
            // No need to narrow down the domain.
            return tupleDomain;
        }

        List<String> catalogs = listCatalogs(session, metadata, accessControl, catalogFilter).keySet().stream()
                .filter(catalogName -> predicate.test(ImmutableMap.of(TABLE_CATALOG_COLUMN, toNullableValue(catalogName))))
                .collect(toImmutableList());

        List<CatalogSchemaName> schemas = catalogs.stream()
                .flatMap(catalogName ->
                        listSchemas(session, metadata, accessControl, catalogName, schemaFilter).stream()
                                .filter(schemaName -> !hasSchemaPredicate || predicate.test(ImmutableMap.of(
                                        TABLE_CATALOG_COLUMN, toNullableValue(catalogName),
                                        TABLE_SCHEMA_COLUMN, toNullableValue(schemaName))))
                                .map(schemaName -> new CatalogSchemaName(catalogName, schemaName)))
                .collect(toImmutableList());

        if (!hasTablePredicate) {
            return TupleDomain.withColumnDomains(ImmutableMap.<ColumnHandle, Domain>builder()
                    .put(TABLE_CATALOG_COLUMN, schemas.stream()
                            .map(CatalogSchemaName::getCatalogName)
                            .collect(toVarcharDomain())
                            .simplify(MAX_DOMAIN_SIZE))
                    .put(TABLE_SCHEMA_COLUMN, schemas.stream()
                            .map(CatalogSchemaName::getSchemaName)
                            .collect(toVarcharDomain())
                            .simplify(MAX_DOMAIN_SIZE))
                    .build());
        }

        List<CatalogSchemaTableName> tables = schemas.stream()
                .flatMap(schema -> {
                    QualifiedTablePrefix tablePrefix = tableFilter.isPresent()
                            ? new QualifiedTablePrefix(schema.getCatalogName(), schema.getSchemaName(), tableFilter.get())
                            : new QualifiedTablePrefix(schema.getCatalogName(), schema.getSchemaName());
                    return listTables(session, metadata, accessControl, tablePrefix).stream()
                            .filter(schemaTableName -> predicate.test(ImmutableMap.of(
                                    TABLE_CATALOG_COLUMN, toNullableValue(schema.getCatalogName()),
                                    TABLE_SCHEMA_COLUMN, toNullableValue(schemaTableName.getSchemaName()),
                                    TABLE_NAME_COLUMN, toNullableValue(schemaTableName.getTableName()))))
                            .map(schemaTableName -> new CatalogSchemaTableName(schema.getCatalogName(), schemaTableName.getSchemaName(), schemaTableName.getTableName()));
                })
                .collect(toImmutableList());

        return TupleDomain.withColumnDomains(ImmutableMap.<ColumnHandle, Domain>builder()
                .put(TABLE_CATALOG_COLUMN, tables.stream()
                        .map(CatalogSchemaTableName::getCatalogName)
                        .collect(toVarcharDomain())
                        .simplify(MAX_DOMAIN_SIZE))
                .put(TABLE_SCHEMA_COLUMN, tables.stream()
                        .map(catalogSchemaTableName -> catalogSchemaTableName.getSchemaTableName().getSchemaName())
                        .collect(toVarcharDomain())
                        .simplify(MAX_DOMAIN_SIZE))
                .put(TABLE_NAME_COLUMN, tables.stream()
                        .map(catalogSchemaTableName -> catalogSchemaTableName.getSchemaTableName().getTableName())
                        .collect(toVarcharDomain())
                        .simplify(MAX_DOMAIN_SIZE))
                .build());
    }

    @Override
    public RecordCursor cursor(ConnectorTransactionHandle transactionHandle, ConnectorSession connectorSession, TupleDomain<Integer> constraint)
    {
        Builder table = InMemoryRecordSet.builder(METADATA);
        if (constraint.isNone()) {
            return table.build().cursor();
        }

        Session session = ((FullConnectorSession) connectorSession).getSession();
        Optional<String> catalogFilter = tryGetSingleVarcharValue(constraint, 0);
        Optional<String> schemaFilter = tryGetSingleVarcharValue(constraint, 1);
        Optional<String> tableFilter = tryGetSingleVarcharValue(constraint, 2);

        Domain catalogDomain = constraint.getDomains().get().getOrDefault(0, Domain.all(createUnboundedVarcharType()));
        Domain schemaDomain = constraint.getDomains().get().getOrDefault(1, Domain.all(createUnboundedVarcharType()));
        Domain tableDomain = constraint.getDomains().get().getOrDefault(2, Domain.all(createUnboundedVarcharType()));

        for (String catalog : listCatalogs(session, metadata, accessControl, catalogFilter).keySet()) {
            if (!catalogDomain.includesNullableValue(utf8Slice(catalog))) {
                continue;
            }

            if ((schemaDomain.isAll() && tableDomain.isAll()) || (schemaFilter.isPresent() && tableFilter.isPresent())) {
                QualifiedTablePrefix tablePrefix = tablePrefix(catalog, schemaFilter, tableFilter);
                Map<SchemaTableName, List<ColumnMetadata>> tableColumns = listTableColumns(session, metadata, accessControl, tablePrefix);
                addColumnsRow(table, catalog, tableColumns, connectorSession.isOmitDatetimeTypePrecision());
            }
            else {
                Collection<String> schemas = listSchemas(session, metadata, accessControl, catalog, schemaFilter);
                for (String schema : schemas) {
                    if (!schemaDomain.includesNullableValue(utf8Slice(schema))) {
                        continue;
                    }

                    QualifiedTablePrefix tablePrefix = tableFilter.isPresent()
                            ? new QualifiedTablePrefix(catalog, schema, tableFilter.get())
                            : new QualifiedTablePrefix(catalog, schema);
                    Set<SchemaTableName> tables = listTables(session, metadata, accessControl, tablePrefix);
                    for (SchemaTableName schemaTableName : tables) {
                        String tableName = schemaTableName.getTableName();
                        if (!tableDomain.includesNullableValue(utf8Slice(tableName))) {
                            continue;
                        }

                        Map<SchemaTableName, List<ColumnMetadata>> tableColumns = listTableColumns(session, metadata, accessControl, new QualifiedTablePrefix(catalog, schema, tableName));
                        addColumnsRow(table, catalog, tableColumns, connectorSession.isOmitDatetimeTypePrecision());
                    }
                }
            }
        }
        return table.build().cursor();
    }

    private static void addColumnsRow(Builder builder, String catalog, Map<SchemaTableName, List<ColumnMetadata>> columns, boolean isOmitTimestampPrecision)
    {
        for (Entry<SchemaTableName, List<ColumnMetadata>> entry : columns.entrySet()) {
            addColumnRows(builder, catalog, entry.getKey(), entry.getValue(), isOmitTimestampPrecision);
        }
    }

    private static void addColumnRows(Builder builder, String catalog, SchemaTableName tableName, List<ColumnMetadata> columns, boolean isOmitTimestampPrecision)
    {
        int ordinalPosition = 1;
        for (ColumnMetadata column : columns) {
            if (column.isHidden()) {
                continue;
            }
            builder.addRow(
                    catalog,
                    tableName.getSchemaName(),
                    tableName.getTableName(),
                    column.getName(),
                    jdbcDataType(column.getType()),
                    getDisplayLabel(column.getType(), isOmitTimestampPrecision),
                    columnSize(column.getType()),
                    0,
                    decimalDigits(column.getType()),
                    numPrecRadix(column.getType()),
                    DatabaseMetaData.columnNullableUnknown,
                    column.getComment(),
                    null,
                    null,
                    null,
                    charOctetLength(column.getType()),
                    ordinalPosition,
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            ordinalPosition++;
        }
    }

    static int jdbcDataType(Type type)
    {
        if (type.equals(BOOLEAN)) {
            return Types.BOOLEAN;
        }
        if (type.equals(BIGINT)) {
            return Types.BIGINT;
        }
        if (type.equals(INTEGER)) {
            return Types.INTEGER;
        }
        if (type.equals(SMALLINT)) {
            return Types.SMALLINT;
        }
        if (type.equals(TINYINT)) {
            return Types.TINYINT;
        }
        if (type.equals(REAL)) {
            return Types.REAL;
        }
        if (type.equals(DOUBLE)) {
            return Types.DOUBLE;
        }
        if (type instanceof DecimalType) {
            return Types.DECIMAL;
        }
        if (isVarcharType(type)) {
            return Types.VARCHAR;
        }
        if (isCharType(type)) {
            return Types.CHAR;
        }
        if (type.equals(VARBINARY)) {
            return Types.VARBINARY;
        }
        if (type.equals(TIME)) {
            return Types.TIME;
        }
        if (type.equals(TIME_WITH_TIME_ZONE)) {
            return Types.TIME_WITH_TIMEZONE;
        }
        if (type.equals(TIMESTAMP)) {
            return Types.TIMESTAMP;
        }
        if (type.equals(TIMESTAMP_WITH_TIME_ZONE)) {
            return Types.TIMESTAMP_WITH_TIMEZONE;
        }
        if (type.equals(DATE)) {
            return Types.DATE;
        }
        if (type instanceof ArrayType) {
            return Types.ARRAY;
        }
        return Types.JAVA_OBJECT;
    }

    static Integer columnSize(Type type)
    {
        if (type.equals(BIGINT)) {
            return 19;  // 2**63-1
        }
        if (type.equals(INTEGER)) {
            return 10;  // 2**31-1
        }
        if (type.equals(SMALLINT)) {
            return 5;   // 2**15-1
        }
        if (type.equals(TINYINT)) {
            return 3;   // 2**7-1
        }
        if (type instanceof DecimalType) {
            return ((DecimalType) type).getPrecision();
        }
        if (type.equals(REAL)) {
            return 24; // IEEE 754
        }
        if (type.equals(DOUBLE)) {
            return 53; // IEEE 754
        }
        if (isVarcharType(type)) {
            return ((VarcharType) type).getLength().orElse(VarcharType.UNBOUNDED_LENGTH);
        }
        if (isCharType(type)) {
            return ((CharType) type).getLength();
        }
        if (type.equals(VARBINARY)) {
            return Integer.MAX_VALUE;
        }
        if (type.equals(TIME)) {
            return 8; // 00:00:00
        }
        if (type.equals(TIME_WITH_TIME_ZONE)) {
            return 8 + 6; // 00:00:00+00:00
        }
        if (type.equals(DATE)) {
            return 14; // +5881580-07-11 (2**31-1 days)
        }
        if (type.equals(TIMESTAMP)) {
            return 15 + 8;
        }
        if (type.equals(TIMESTAMP_WITH_TIME_ZONE)) {
            return 15 + 8 + 6;
        }
        return null;
    }

    // DECIMAL_DIGITS is the number of fractional digits
    private static Integer decimalDigits(Type type)
    {
        if (type instanceof DecimalType) {
            return ((DecimalType) type).getScale();
        }
        return null;
    }

    private static Integer charOctetLength(Type type)
    {
        if (isVarcharType(type)) {
            return ((VarcharType) type).getLength().orElse(VarcharType.UNBOUNDED_LENGTH);
        }
        if (isCharType(type)) {
            return ((CharType) type).getLength();
        }
        if (type.equals(VARBINARY)) {
            return Integer.MAX_VALUE;
        }
        return null;
    }

    static Integer numPrecRadix(Type type)
    {
        if (type.equals(BIGINT) ||
                type.equals(INTEGER) ||
                type.equals(SMALLINT) ||
                type.equals(TINYINT) ||
                (type instanceof DecimalType)) {
            return 10;
        }
        if (type.equals(REAL) || type.equals(DOUBLE)) {
            return 2;
        }
        return null;
    }

    private static NullableValue toNullableValue(String varcharValue)
    {
        return NullableValue.of(createUnboundedVarcharType(), utf8Slice(varcharValue));
    }

    private static Collector<String, ?, Domain> toVarcharDomain()
    {
        return Collectors.collectingAndThen(toImmutableSet(), set -> {
            if (set.isEmpty()) {
                return Domain.none(createUnboundedVarcharType());
            }
            return Domain.multipleValues(createUnboundedVarcharType(), set.stream()
                    .map(Slices::utf8Slice)
                    .collect(toImmutableList()));
        });
    }
}
