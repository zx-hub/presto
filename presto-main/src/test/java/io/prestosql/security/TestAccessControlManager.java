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
package io.prestosql.security;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.connector.CatalogName;
import io.prestosql.connector.informationschema.InformationSchemaConnector;
import io.prestosql.connector.system.SystemConnector;
import io.prestosql.eventlistener.EventListenerManager;
import io.prestosql.metadata.Catalog;
import io.prestosql.metadata.CatalogManager;
import io.prestosql.metadata.InMemoryNodeManager;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.plugin.base.security.AllowAllAccessControl;
import io.prestosql.plugin.base.security.AllowAllSystemAccessControl;
import io.prestosql.plugin.base.security.ReadOnlySystemAccessControl;
import io.prestosql.plugin.tpch.TpchConnectorFactory;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorAccessControl;
import io.prestosql.spi.connector.ConnectorSecurityContext;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.eventlistener.EventListener;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.BasicPrincipal;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.SystemAccessControl;
import io.prestosql.spi.security.SystemAccessControlFactory;
import io.prestosql.spi.security.SystemSecurityContext;
import io.prestosql.spi.security.ViewExpression;
import io.prestosql.spi.type.Type;
import io.prestosql.testing.TestingConnectorContext;
import io.prestosql.testing.TestingEventListenerManager;
import io.prestosql.transaction.TransactionId;
import io.prestosql.transaction.TransactionManager;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.prestosql.connector.CatalogName.createInformationSchemaCatalogName;
import static io.prestosql.connector.CatalogName.createSystemTablesCatalogName;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.security.AccessDeniedException.denySelectTable;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.testing.TestingEventListenerManager.emptyEventListenerManager;
import static io.prestosql.transaction.InMemoryTransactionManager.createTestTransactionManager;
import static io.prestosql.transaction.TransactionBuilder.transaction;
import static java.nio.file.Files.createTempFile;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class TestAccessControlManager
{
    private static final Principal PRINCIPAL = new BasicPrincipal("principal");
    private static final String USER_NAME = "user_name";
    private static final QueryId queryId = new QueryId("query_id");

    @Test(expectedExceptions = PrestoException.class, expectedExceptionsMessageRegExp = "Presto server is still initializing")
    public void testInitializing()
    {
        AccessControlManager accessControlManager = createAccessControlManager(createTestTransactionManager());
        accessControlManager.checkCanSetUser(Optional.empty(), "foo");
    }

    @Test
    public void testNoneSystemAccessControl()
    {
        AccessControlManager accessControlManager = createAccessControlManager(createTestTransactionManager());
        accessControlManager.setSystemAccessControl(AllowAllSystemAccessControl.NAME, ImmutableMap.of());
        accessControlManager.checkCanSetUser(Optional.empty(), USER_NAME);
    }

    @Test
    public void testReadOnlySystemAccessControl()
    {
        Identity identity = Identity.forUser(USER_NAME).withPrincipal(PRINCIPAL).build();
        QualifiedObjectName tableName = new QualifiedObjectName("catalog", "schema", "table");
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);

        accessControlManager.setSystemAccessControl(ReadOnlySystemAccessControl.NAME, ImmutableMap.of());
        accessControlManager.checkCanSetUser(Optional.of(PRINCIPAL), USER_NAME);
        accessControlManager.checkCanSetSystemSessionProperty(identity, "property");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    SecurityContext context = new SecurityContext(transactionId, identity, queryId);
                    accessControlManager.checkCanSetCatalogSessionProperty(context, "catalog", "property");
                    accessControlManager.checkCanShowSchemas(context, "catalog");
                    accessControlManager.checkCanShowTables(context, new CatalogSchemaName("catalog", "schema"));
                    accessControlManager.checkCanSelectFromColumns(context, tableName, ImmutableSet.of("column"));
                    accessControlManager.checkCanCreateViewWithSelectFromColumns(context, tableName, ImmutableSet.of("column"));
                    accessControlManager.checkCanGrantExecuteFunctionPrivilege(context, "function", Identity.ofUser("bob"), false);
                    accessControlManager.checkCanGrantExecuteFunctionPrivilege(context, "function", Identity.ofUser("bob"), true);
                    Set<String> catalogs = ImmutableSet.of("catalog");
                    assertEquals(accessControlManager.filterCatalogs(identity, catalogs), catalogs);
                    Set<String> schemas = ImmutableSet.of("schema");
                    assertEquals(accessControlManager.filterSchemas(context, "catalog", schemas), schemas);
                    Set<SchemaTableName> tableNames = ImmutableSet.of(new SchemaTableName("schema", "table"));
                    assertEquals(accessControlManager.filterTables(context, "catalog", tableNames), tableNames);
                });

        try {
            transaction(transactionManager, accessControlManager)
                    .execute(transactionId -> {
                        accessControlManager.checkCanInsertIntoTable(new SecurityContext(transactionId, identity, queryId), tableName);
                    });
            fail();
        }
        catch (AccessDeniedException expected) {
        }
    }

    @Test
    public void testSetAccessControl()
    {
        AccessControlManager accessControlManager = createAccessControlManager(createTestTransactionManager());

        TestSystemAccessControlFactory accessControlFactory = new TestSystemAccessControlFactory("test");
        accessControlManager.addSystemAccessControlFactory(accessControlFactory);
        accessControlManager.setSystemAccessControl("test", ImmutableMap.of());

        accessControlManager.checkCanSetUser(Optional.of(PRINCIPAL), USER_NAME);
        assertEquals(accessControlFactory.getCheckedUserName(), USER_NAME);
        assertEquals(accessControlFactory.getCheckedPrincipal(), Optional.of(PRINCIPAL));
    }

    @Test
    public void testNoCatalogAccessControl()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);

        TestSystemAccessControlFactory accessControlFactory = new TestSystemAccessControlFactory("test");
        accessControlManager.addSystemAccessControlFactory(accessControlFactory);
        accessControlManager.setSystemAccessControl("test", ImmutableMap.of());

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanSelectFromColumns(context(transactionId), new QualifiedObjectName("catalog", "schema", "table"), ImmutableSet.of("column"));
                });
    }

    @Test(expectedExceptions = PrestoException.class, expectedExceptionsMessageRegExp = "Access Denied: Cannot select from columns \\[column\\] in table or view schema.table")
    public void testDenyCatalogAccessControl()
    {
        CatalogManager catalogManager = new CatalogManager();
        TransactionManager transactionManager = createTestTransactionManager(catalogManager);
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);

        TestSystemAccessControlFactory accessControlFactory = new TestSystemAccessControlFactory("test");
        accessControlManager.addSystemAccessControlFactory(accessControlFactory);
        accessControlManager.setSystemAccessControl("test", ImmutableMap.of());

        CatalogName catalogName = registerBogusConnector(catalogManager, transactionManager, accessControlManager, "catalog");
        accessControlManager.addCatalogAccessControl(catalogName, new DenyConnectorAccessControl());

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanSelectFromColumns(context(transactionId), new QualifiedObjectName("catalog", "schema", "table"), ImmutableSet.of("column"));
                });
    }

    @Test
    public void testColumnMaskOrdering()
    {
        CatalogManager catalogManager = new CatalogManager();
        TransactionManager transactionManager = createTestTransactionManager(catalogManager);
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);

        accessControlManager.addSystemAccessControlFactory(new SystemAccessControlFactory()
        {
            @Override
            public String getName()
            {
                return "test";
            }

            @Override
            public SystemAccessControl create(Map<String, String> config)
            {
                return new SystemAccessControl()
                {
                    @Override
                    public Optional<ViewExpression> getColumnMask(SystemSecurityContext context, CatalogSchemaTableName tableName, String column, Type type)
                    {
                        return Optional.of(new ViewExpression("user", Optional.empty(), Optional.empty(), "system mask"));
                    }

                    @Override
                    public void checkCanSetSystemSessionProperty(SystemSecurityContext context, String propertyName)
                    {
                    }
                };
            }
        });
        accessControlManager.setSystemAccessControl("test", ImmutableMap.of());

        CatalogName catalogName = registerBogusConnector(catalogManager, transactionManager, accessControlManager, "catalog");
        accessControlManager.addCatalogAccessControl(catalogName, new ConnectorAccessControl()
        {
            @Override
            public Optional<ViewExpression> getColumnMask(ConnectorSecurityContext context, SchemaTableName tableName, String column, Type type)
            {
                return Optional.of(new ViewExpression("user", Optional.empty(), Optional.empty(), "connector mask"));
            }

            @Override
            public void checkCanShowCreateTable(ConnectorSecurityContext context, SchemaTableName tableName)
            {
            }
        });

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    List<ViewExpression> masks = accessControlManager.getColumnMasks(context(transactionId), new QualifiedObjectName("catalog", "schema", "table"), "column", BIGINT);
                    assertEquals(masks.get(0).getExpression(), "connector mask");
                    assertEquals(masks.get(1).getExpression(), "system mask");
                });
    }

    private static SecurityContext context(TransactionId transactionId)
    {
        Identity identity = Identity.forUser(USER_NAME).withPrincipal(PRINCIPAL).build();
        return new SecurityContext(transactionId, identity, queryId);
    }

    @Test(expectedExceptions = PrestoException.class, expectedExceptionsMessageRegExp = "Access Denied: Cannot select from table secured_catalog.schema.table")
    public void testDenySystemAccessControl()
    {
        CatalogManager catalogManager = new CatalogManager();
        TransactionManager transactionManager = createTestTransactionManager(catalogManager);
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);

        TestSystemAccessControlFactory accessControlFactory = new TestSystemAccessControlFactory("test");
        accessControlManager.addSystemAccessControlFactory(accessControlFactory);
        accessControlManager.setSystemAccessControl("test", ImmutableMap.of());

        registerBogusConnector(catalogManager, transactionManager, accessControlManager, "connector");
        accessControlManager.addCatalogAccessControl(new CatalogName("connector"), new DenyConnectorAccessControl());

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanSelectFromColumns(context(transactionId), new QualifiedObjectName("secured_catalog", "schema", "table"), ImmutableSet.of("column"));
                });
    }

    @Test
    public void testDenyExecuteProcedureBySystem()
    {
        CatalogManager catalogManager = new CatalogManager();
        TransactionManager transactionManager = createTestTransactionManager(catalogManager);
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);

        TestSystemAccessControlFactory accessControlFactory = new TestSystemAccessControlFactory("deny-all");
        accessControlManager.addSystemAccessControlFactory(accessControlFactory);
        accessControlManager.setSystemAccessControl("deny-all", ImmutableMap.of());

        assertDenyExecuteProcedure(transactionManager, accessControlManager, "Access Denied: Cannot execute procedure connector.schema.procedure");
    }

    @Test
    public void testDenyExecuteProcedureByConnector()
    {
        CatalogManager catalogManager = new CatalogManager();
        TransactionManager transactionManager = createTestTransactionManager(catalogManager);
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);
        accessControlManager.setSystemAccessControl("allow-all", ImmutableMap.of());

        registerBogusConnector(catalogManager, transactionManager, accessControlManager, "connector");
        accessControlManager.addCatalogAccessControl(new CatalogName("connector"), new DenyConnectorAccessControl());

        assertDenyExecuteProcedure(transactionManager, accessControlManager, "Access Denied: Cannot execute procedure schema.procedure");
    }

    @Test
    public void testAllowExecuteProcedure()
    {
        CatalogManager catalogManager = new CatalogManager();
        TransactionManager transactionManager = createTestTransactionManager(catalogManager);
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);
        accessControlManager.setSystemAccessControl("allow-all", ImmutableMap.of());

        registerBogusConnector(catalogManager, transactionManager, accessControlManager, "connector");
        accessControlManager.addCatalogAccessControl(new CatalogName("connector"), new AllowAllAccessControl());

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanExecuteProcedure(context(transactionId), new QualifiedObjectName("connector", "schema", "procedure"));
                });
    }

    @Test
    public void testRegisterSingleEventListener()
            throws IOException
    {
        EventListener expectedListener = new EventListener() {};

        String systemAccessControlName = "event-listening-sac";
        TestingEventListenerManager eventListenerManager = emptyEventListenerManager();
        AccessControlManager accessControlManager = createAccessControlManager(eventListenerManager, ImmutableList.of("access-control.name=" + systemAccessControlName));
        accessControlManager.addSystemAccessControlFactory(
                eventListeningSystemAccessControlFactory(systemAccessControlName, expectedListener));

        accessControlManager.loadSystemAccessControl();

        assertThat(eventListenerManager.getConfiguredEventListeners())
                .contains(expectedListener);
    }

    @Test
    public void testRegisterMultipleEventListeners()
            throws IOException
    {
        EventListener firstListener = new EventListener() {};
        EventListener secondListener = new EventListener() {};

        String systemAccessControlName = "event-listening-sac";
        TestingEventListenerManager eventListenerManager = emptyEventListenerManager();
        AccessControlManager accessControlManager = createAccessControlManager(eventListenerManager, ImmutableList.of("access-control.name=" + systemAccessControlName));
        accessControlManager.addSystemAccessControlFactory(
                eventListeningSystemAccessControlFactory(systemAccessControlName, firstListener, secondListener));

        accessControlManager.loadSystemAccessControl();

        assertThat(eventListenerManager.getConfiguredEventListeners())
                .contains(firstListener, secondListener);
    }

    private void assertDenyExecuteProcedure(TransactionManager transactionManager, AccessControlManager accessControlManager, String s)
    {
        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    assertThatThrownBy(
                            () -> accessControlManager.checkCanExecuteProcedure(context(transactionId), new QualifiedObjectName("connector", "schema", "procedure")))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessage(s);
                });
    }

    private static CatalogName registerBogusConnector(CatalogManager catalogManager, TransactionManager transactionManager, AccessControl accessControl, String catalogName)
    {
        CatalogName catalog = new CatalogName(catalogName);
        Connector connector = new TpchConnectorFactory().create(catalogName, ImmutableMap.of(), new TestingConnectorContext());

        InMemoryNodeManager nodeManager = new InMemoryNodeManager();
        Metadata metadata = createTestMetadataManager(catalogManager);
        CatalogName systemId = createSystemTablesCatalogName(catalog);
        catalogManager.registerCatalog(new Catalog(
                catalogName,
                catalog,
                connector,
                createInformationSchemaCatalogName(catalog),
                new InformationSchemaConnector(catalogName, nodeManager, metadata, accessControl),
                systemId,
                new SystemConnector(
                        nodeManager,
                        connector.getSystemTables(),
                        transactionId -> transactionManager.getConnectorTransaction(transactionId, catalog))));

        return catalog;
    }

    @Test
    public void testDenyExecuteFunctionBySystemAccessControl()
    {
        CatalogManager catalogManager = new CatalogManager();
        TransactionManager transactionManager = createTestTransactionManager(catalogManager);
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);

        TestSystemAccessControlFactory accessControlFactory = new TestSystemAccessControlFactory("deny-all");
        accessControlManager.addSystemAccessControlFactory(accessControlFactory);
        accessControlManager.setSystemAccessControl("deny-all", ImmutableMap.of());

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    assertThatThrownBy(() -> accessControlManager.checkCanExecuteFunction(context(transactionId), "executed_function"))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessage("Access Denied: Cannot execute function executed_function");
                    assertThatThrownBy(() -> accessControlManager.checkCanGrantExecuteFunctionPrivilege(context(transactionId), "executed_function", Identity.ofUser("bob"), true))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessage("Access Denied: 'user_name' cannot grant 'executed_function' execution to user 'bob'");
                });
    }

    @Test
    public void testAllowExecuteFunction()
    {
        CatalogManager catalogManager = new CatalogManager();
        TransactionManager transactionManager = createTestTransactionManager(catalogManager);
        AccessControlManager accessControlManager = createAccessControlManager(transactionManager);
        accessControlManager.setSystemAccessControl("allow-all", ImmutableMap.of());

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanExecuteFunction(context(transactionId), "executed_function");
                    accessControlManager.checkCanGrantExecuteFunctionPrivilege(context(transactionId), "executed_function", Identity.ofUser("bob"), true);
                });
    }

    private AccessControlManager createAccessControlManager(TestingEventListenerManager eventListenerManager, List<String> systemAccessControlProperties)
            throws IOException
    {
        Path systemAccessControlConfig = createTempFile("access-control-config-file", ".properties");
        Files.write(systemAccessControlConfig, systemAccessControlProperties, TRUNCATE_EXISTING, CREATE, WRITE);
        String accessControlConfigPath = systemAccessControlConfig
                .toFile()
                .getAbsolutePath();

        return createAccessControlManager(
                eventListenerManager,
                new AccessControlConfig().setAccessControlFiles(accessControlConfigPath));
    }

    private AccessControlManager createAccessControlManager(TransactionManager testTransactionManager)
    {
        return new AccessControlManager(testTransactionManager, emptyEventListenerManager(), new AccessControlConfig());
    }

    private AccessControlManager createAccessControlManager(EventListenerManager eventListenerManager, AccessControlConfig config)
    {
        return new AccessControlManager(createTestTransactionManager(), eventListenerManager, config);
    }

    private SystemAccessControlFactory eventListeningSystemAccessControlFactory(String name, EventListener... eventListeners)
    {
        return new SystemAccessControlFactory()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public SystemAccessControl create(Map<String, String> config)
            {
                return new SystemAccessControl()
                {
                    @Override
                    public void checkCanSetSystemSessionProperty(SystemSecurityContext context, String propertyName)
                    {
                    }

                    @Override
                    public Iterable<EventListener> getEventListeners()
                    {
                        return ImmutableSet.copyOf(eventListeners);
                    }
                };
            }
        };
    }

    private static class TestSystemAccessControlFactory
            implements SystemAccessControlFactory
    {
        private final String name;
        private Map<String, String> config;

        private Optional<Principal> checkedPrincipal;
        private String checkedUserName;

        public TestSystemAccessControlFactory(String name)
        {
            this.name = requireNonNull(name, "name is null");
        }

        public Map<String, String> getConfig()
        {
            return config;
        }

        public Optional<Principal> getCheckedPrincipal()
        {
            return checkedPrincipal;
        }

        public String getCheckedUserName()
        {
            return checkedUserName;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public SystemAccessControl create(Map<String, String> config)
        {
            this.config = config;
            return new SystemAccessControl()
            {
                @Override
                public void checkCanSetUser(Optional<Principal> principal, String userName)
                {
                    checkedPrincipal = principal;
                    checkedUserName = userName;
                }

                @Override
                public void checkCanAccessCatalog(SystemSecurityContext context, String catalogName)
                {
                }

                @Override
                public void checkCanSetSystemSessionProperty(SystemSecurityContext context, String propertyName)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
                {
                    if (table.getCatalogName().equals("secured_catalog")) {
                        denySelectTable(table.toString());
                    }
                }

                @Override
                public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs)
                {
                    return catalogs;
                }
            };
        }
    }

    private static class DenyConnectorAccessControl
            implements ConnectorAccessControl
    {
    }
}
