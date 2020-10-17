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
package io.prestosql.plugin.base.security;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorAccessControl;
import io.prestosql.spi.connector.ConnectorSecurityContext;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.ConnectorIdentity;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.PrincipalType;
import io.prestosql.spi.security.Privilege;
import io.prestosql.spi.type.VarcharType;
import org.testng.Assert.ThrowingRunnable;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.Set;

import static io.prestosql.spi.testing.InterfaceTestUtils.assertAllMethodsOverridden;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;

public class TestFileBasedAccessControl
{
    private static final ConnectorSecurityContext ADMIN = user("admin", ImmutableSet.of("admin", "staff"));
    private static final ConnectorSecurityContext ALICE = user("alice", ImmutableSet.of("staff"));
    private static final ConnectorSecurityContext BOB = user("bob", ImmutableSet.of("staff"));
    private static final ConnectorSecurityContext CHARLIE = user("charlie", ImmutableSet.of("guests"));
    private static final ConnectorSecurityContext JOE = user("joe", ImmutableSet.of());
    private static final ConnectorSecurityContext UNKNOWN = user("unknown", ImmutableSet.of());

    @Test
    public void testEmptyFile()
    {
        ConnectorAccessControl accessControl = createAccessControl("empty.json");

        accessControl.checkCanCreateSchema(UNKNOWN, "unknown");
        accessControl.checkCanDropSchema(UNKNOWN, "unknown");
        accessControl.checkCanRenameSchema(UNKNOWN, "unknown", "new_unknown");
        accessControl.checkCanSetSchemaAuthorization(UNKNOWN, "unknown", new PrestoPrincipal(PrincipalType.ROLE, "some_role"));
        accessControl.checkCanShowCreateSchema(UNKNOWN, "unknown");

        accessControl.checkCanSelectFromColumns(UNKNOWN, new SchemaTableName("unknown", "unknown"), ImmutableSet.of());
        accessControl.checkCanShowColumns(UNKNOWN, new SchemaTableName("unknown", "unknown"));
        accessControl.checkCanInsertIntoTable(UNKNOWN, new SchemaTableName("unknown", "unknown"));
        accessControl.checkCanDeleteFromTable(UNKNOWN, new SchemaTableName("unknown", "unknown"));

        accessControl.checkCanCreateTable(UNKNOWN, new SchemaTableName("unknown", "unknown"));
        accessControl.checkCanDropTable(UNKNOWN, new SchemaTableName("unknown", "unknown"));
        accessControl.checkCanRenameTable(UNKNOWN,
                new SchemaTableName("unknown", "unknown"),
                new SchemaTableName("unknown", "new_unknown"));

        accessControl.checkCanSetCatalogSessionProperty(UNKNOWN, "anything");

        Set<SchemaTableName> tables = ImmutableSet.<SchemaTableName>builder()
                .add(new SchemaTableName("secret", "any"))
                .add(new SchemaTableName("any", "any"))
                .build();
        assertEquals(accessControl.filterTables(UNKNOWN, tables), tables);

        // permissions management APIs are hard coded to deny
        PrestoPrincipal someUser = new PrestoPrincipal(PrincipalType.USER, "some_user");
        assertDenied(() -> accessControl.checkCanGrantTablePrivilege(ADMIN, Privilege.SELECT, new SchemaTableName("any", "any"), someUser, false));
        assertDenied(() -> accessControl.checkCanRevokeTablePrivilege(ADMIN, Privilege.SELECT, new SchemaTableName("any", "any"), someUser, false));
        assertDenied(() -> accessControl.checkCanCreateRole(ADMIN, "role", Optional.empty()));
        assertDenied(() -> accessControl.checkCanDropRole(ADMIN, "role"));
        assertDenied(() -> accessControl.checkCanGrantRoles(
                ADMIN,
                ImmutableSet.of("test"),
                ImmutableSet.of(someUser),
                false,
                Optional.empty(),
                "any"));
        assertDenied(() -> accessControl.checkCanRevokeRoles(
                ADMIN,
                ImmutableSet.of("test"),
                ImmutableSet.of(someUser),
                false,
                Optional.empty(),
                "any"));
        assertDenied(() -> accessControl.checkCanSetRole(ADMIN, "role", "any"));

        // showing roles and permissions is hard coded to allow
        accessControl.checkCanShowRoleAuthorizationDescriptors(UNKNOWN, "any");
        accessControl.checkCanShowRoles(UNKNOWN, "any");
        accessControl.checkCanShowCurrentRoles(UNKNOWN, "any");
        accessControl.checkCanShowRoleGrants(UNKNOWN, "any");
    }

    @Test
    public void testSchemaRules()
    {
        ConnectorAccessControl accessControl = createAccessControl("schema.json");

        accessControl.checkCanCreateSchema(ADMIN, "bob");
        accessControl.checkCanCreateSchema(ADMIN, "staff");
        accessControl.checkCanCreateSchema(ADMIN, "authenticated");
        accessControl.checkCanCreateSchema(ADMIN, "test");

        accessControl.checkCanCreateSchema(BOB, "bob");
        accessControl.checkCanCreateSchema(BOB, "staff");
        accessControl.checkCanCreateSchema(BOB, "authenticated");
        assertDenied(() -> accessControl.checkCanCreateSchema(BOB, "test"));

        assertDenied(() -> accessControl.checkCanCreateSchema(CHARLIE, "bob"));
        assertDenied(() -> accessControl.checkCanCreateSchema(CHARLIE, "staff"));
        accessControl.checkCanCreateSchema(CHARLIE, "authenticated");
        assertDenied(() -> accessControl.checkCanCreateSchema(CHARLIE, "test"));

        accessControl.checkCanDropSchema(ADMIN, "bob");
        accessControl.checkCanDropSchema(ADMIN, "staff");
        accessControl.checkCanDropSchema(ADMIN, "authenticated");
        accessControl.checkCanDropSchema(ADMIN, "test");

        accessControl.checkCanDropSchema(BOB, "bob");
        accessControl.checkCanDropSchema(BOB, "staff");
        accessControl.checkCanDropSchema(BOB, "authenticated");
        assertDenied(() -> accessControl.checkCanDropSchema(BOB, "test"));

        assertDenied(() -> accessControl.checkCanDropSchema(CHARLIE, "bob"));
        assertDenied(() -> accessControl.checkCanDropSchema(CHARLIE, "staff"));
        accessControl.checkCanDropSchema(CHARLIE, "authenticated");
        assertDenied(() -> accessControl.checkCanDropSchema(CHARLIE, "test"));

        accessControl.checkCanRenameSchema(ADMIN, "bob", "new_schema");
        accessControl.checkCanRenameSchema(ADMIN, "staff", "new_schema");
        accessControl.checkCanRenameSchema(ADMIN, "authenticated", "new_schema");
        accessControl.checkCanRenameSchema(ADMIN, "test", "new_schema");

        accessControl.checkCanRenameSchema(BOB, "bob", "staff");
        accessControl.checkCanRenameSchema(BOB, "staff", "authenticated");
        accessControl.checkCanRenameSchema(BOB, "authenticated", "bob");
        assertDenied(() -> accessControl.checkCanRenameSchema(BOB, "test", "bob"));
        assertDenied(() -> accessControl.checkCanRenameSchema(BOB, "bob", "test"));

        assertDenied(() -> accessControl.checkCanRenameSchema(CHARLIE, "bob", "new_schema"));
        assertDenied(() -> accessControl.checkCanRenameSchema(CHARLIE, "staff", "new_schema"));
        accessControl.checkCanRenameSchema(CHARLIE, "authenticated", "authenticated");
        assertDenied(() -> accessControl.checkCanRenameSchema(CHARLIE, "test", "new_schema"));

        accessControl.checkCanSetSchemaAuthorization(ADMIN, "test", new PrestoPrincipal(PrincipalType.ROLE, "some_role"));
        accessControl.checkCanSetSchemaAuthorization(ADMIN, "test", new PrestoPrincipal(PrincipalType.USER, "some_user"));
        accessControl.checkCanSetSchemaAuthorization(BOB, "bob", new PrestoPrincipal(PrincipalType.ROLE, "some_role"));
        accessControl.checkCanSetSchemaAuthorization(BOB, "bob", new PrestoPrincipal(PrincipalType.USER, "some_user"));
        assertDenied(() -> accessControl.checkCanSetSchemaAuthorization(BOB, "test", new PrestoPrincipal(PrincipalType.ROLE, "some_role")));
        assertDenied(() -> accessControl.checkCanSetSchemaAuthorization(BOB, "test", new PrestoPrincipal(PrincipalType.USER, "some_user")));

        accessControl.checkCanShowCreateSchema(ADMIN, "bob");
        accessControl.checkCanShowCreateSchema(ADMIN, "staff");
        accessControl.checkCanShowCreateSchema(ADMIN, "authenticated");
        accessControl.checkCanShowCreateSchema(ADMIN, "test");

        accessControl.checkCanShowCreateSchema(BOB, "bob");
        accessControl.checkCanShowCreateSchema(BOB, "staff");
        accessControl.checkCanShowCreateSchema(BOB, "authenticated");
        assertDenied(() -> accessControl.checkCanShowCreateSchema(BOB, "test"));

        assertDenied(() -> accessControl.checkCanShowCreateSchema(CHARLIE, "bob"));
        assertDenied(() -> accessControl.checkCanShowCreateSchema(CHARLIE, "staff"));
        accessControl.checkCanShowCreateSchema(CHARLIE, "authenticated");
        assertDenied(() -> accessControl.checkCanShowCreateSchema(CHARLIE, "test"));
    }

    @Test
    public void testTableRules()
    {
        ConnectorAccessControl accessControl = createAccessControl("table.json");
        accessControl.checkCanSelectFromColumns(ALICE, new SchemaTableName("test", "test"), ImmutableSet.of());
        accessControl.checkCanSelectFromColumns(ALICE, new SchemaTableName("bobschema", "bobtable"), ImmutableSet.of());
        accessControl.checkCanSelectFromColumns(ALICE, new SchemaTableName("bobschema", "bobtable"), ImmutableSet.of("bobcolumn"));

        accessControl.checkCanShowColumns(ALICE, new SchemaTableName("bobschema", "bobtable"));
        assertEquals(
                accessControl.filterColumns(ALICE, new SchemaTableName("bobschema", "bobtable"), ImmutableList.of(column("a"))),
                ImmutableList.of(column("a")));
        accessControl.checkCanSelectFromColumns(BOB, new SchemaTableName("bobschema", "bobtable"), ImmutableSet.of());
        accessControl.checkCanShowColumns(BOB, new SchemaTableName("bobschema", "bobtable"));
        assertEquals(
                accessControl.filterColumns(BOB, new SchemaTableName("bobschema", "bobtable"), ImmutableList.of(column("a"))),
                ImmutableList.of(column("a")));

        accessControl.checkCanInsertIntoTable(BOB, new SchemaTableName("bobschema", "bobtable"));
        accessControl.checkCanDeleteFromTable(BOB, new SchemaTableName("bobschema", "bobtable"));
        accessControl.checkCanSelectFromColumns(CHARLIE, new SchemaTableName("bobschema", "bobtable"), ImmutableSet.of());
        accessControl.checkCanSelectFromColumns(CHARLIE, new SchemaTableName("bobschema", "bobtable"), ImmutableSet.of("bobcolumn"));
        accessControl.checkCanInsertIntoTable(CHARLIE, new SchemaTableName("bobschema", "bobtable"));
        accessControl.checkCanSelectFromColumns(JOE, new SchemaTableName("bobschema", "bobtable"), ImmutableSet.of());

        accessControl.checkCanCreateTable(ADMIN, new SchemaTableName("bob", "test"));
        accessControl.checkCanCreateTable(ADMIN, new SchemaTableName("test", "test"));
        accessControl.checkCanCreateTable(ADMIN, new SchemaTableName("authenticated", "test"));
        assertDenied(() -> accessControl.checkCanCreateTable(ADMIN, new SchemaTableName("secret", "test")));

        accessControl.checkCanCreateTable(ALICE, new SchemaTableName("aliceschema", "test"));
        assertDenied(() -> accessControl.checkCanCreateTable(ALICE, new SchemaTableName("test", "test")));
        assertDenied(() -> accessControl.checkCanCreateTable(CHARLIE, new SchemaTableName("aliceschema", "test")));
        assertDenied(() -> accessControl.checkCanCreateTable(CHARLIE, new SchemaTableName("test", "test")));

        accessControl.checkCanCreateViewWithSelectFromColumns(BOB, new SchemaTableName("bobschema", "bobtable"), ImmutableSet.of());
        accessControl.checkCanDropTable(ADMIN, new SchemaTableName("bobschema", "bobtable"));

        accessControl.checkCanRenameTable(ADMIN, new SchemaTableName("bobschema", "bobtable"), new SchemaTableName("aliceschema", "newbobtable"));
        accessControl.checkCanRenameTable(ALICE, new SchemaTableName("aliceschema", "alicetable"), new SchemaTableName("aliceschema", "newalicetable"));
        accessControl.checkCanRenameView(ADMIN, new SchemaTableName("bobschema", "bobview"), new SchemaTableName("aliceschema", "newbobview"));
        accessControl.checkCanRenameView(ALICE, new SchemaTableName("aliceschema", "aliceview"), new SchemaTableName("aliceschema", "newaliceview"));

        assertDenied(() -> accessControl.checkCanInsertIntoTable(ALICE, new SchemaTableName("bobschema", "bobtable")));
        assertDenied(() -> accessControl.checkCanDropTable(BOB, new SchemaTableName("bobschema", "bobtable")));
        assertDenied(() -> accessControl.checkCanRenameTable(BOB, new SchemaTableName("bobschema", "bobtable"), new SchemaTableName("bobschema", "newbobtable")));
        assertDenied(() -> accessControl.checkCanRenameTable(ALICE, new SchemaTableName("aliceschema", "alicetable"), new SchemaTableName("bobschema", "newalicetable")));
        assertDenied(() -> accessControl.checkCanInsertIntoTable(BOB, new SchemaTableName("test", "test")));
        assertDenied(() -> accessControl.checkCanSelectFromColumns(ADMIN, new SchemaTableName("secret", "secret"), ImmutableSet.of()));
        assertDenied(() -> accessControl.checkCanSelectFromColumns(JOE, new SchemaTableName("secret", "secret"), ImmutableSet.of()));
        assertDenied(() -> accessControl.checkCanCreateViewWithSelectFromColumns(JOE, new SchemaTableName("bobschema", "bobtable"), ImmutableSet.of()));
        assertDenied(() -> accessControl.checkCanRenameView(BOB, new SchemaTableName("bobschema", "bobview"), new SchemaTableName("bobschema", "newbobview")));
        assertDenied(() -> accessControl.checkCanRenameView(ALICE, new SchemaTableName("aliceschema", "alicetable"), new SchemaTableName("bobschema", "newalicetable")));
    }

    @Test
    public void testTableFilter()
    {
        ConnectorAccessControl accessControl = createAccessControl("table-filter.json");
        Set<SchemaTableName> tables = ImmutableSet.<SchemaTableName>builder()
                .add(new SchemaTableName("restricted", "any"))
                .add(new SchemaTableName("secret", "any"))
                .add(new SchemaTableName("aliceschema", "any"))
                .add(new SchemaTableName("aliceschema", "bobtable"))
                .add(new SchemaTableName("bobschema", "bob_any"))
                .add(new SchemaTableName("bobschema", "any"))
                .add(new SchemaTableName("any", "any"))
                .build();
        assertEquals(accessControl.filterTables(ALICE, tables), ImmutableSet.<SchemaTableName>builder()
                .add(new SchemaTableName("aliceschema", "any"))
                .add(new SchemaTableName("aliceschema", "bobtable"))
                .build());
        assertEquals(accessControl.filterTables(BOB, tables), ImmutableSet.<SchemaTableName>builder()
                .add(new SchemaTableName("aliceschema", "bobtable"))
                .add(new SchemaTableName("bobschema", "bob_any"))
                .build());
        assertEquals(accessControl.filterTables(ADMIN, tables), ImmutableSet.<SchemaTableName>builder()
                .add(new SchemaTableName("secret", "any"))
                .add(new SchemaTableName("aliceschema", "any"))
                .add(new SchemaTableName("aliceschema", "bobtable"))
                .add(new SchemaTableName("bobschema", "bob_any"))
                .add(new SchemaTableName("bobschema", "any"))
                .add(new SchemaTableName("any", "any"))
                .build());
    }

    @Test
    public void testNoTableRules()
    {
        ConnectorAccessControl accessControl = createAccessControl("no-access.json");
        assertDenied(() -> accessControl.checkCanShowColumns(BOB, new SchemaTableName("bobschema", "bobtable")));
        assertDenied(() -> accessControl.checkCanShowTables(BOB, "bobschema"));
        assertEquals(
                accessControl.filterColumns(BOB, new SchemaTableName("bobschema", "bobtable"), ImmutableList.of(column("a"))),
                ImmutableList.of());

        Set<SchemaTableName> tables = ImmutableSet.<SchemaTableName>builder()
                .add(new SchemaTableName("restricted", "any"))
                .add(new SchemaTableName("secret", "any"))
                .add(new SchemaTableName("any", "any"))
                .build();
        assertEquals(accessControl.filterTables(ALICE, tables), ImmutableSet.of());
        assertEquals(accessControl.filterTables(BOB, tables), ImmutableSet.of());
    }

    @Test
    public void testSessionPropertyRules()
    {
        ConnectorAccessControl accessControl = createAccessControl("session_property.json");
        accessControl.checkCanSetCatalogSessionProperty(ADMIN, "dangerous");
        accessControl.checkCanSetCatalogSessionProperty(ALICE, "safe");
        accessControl.checkCanSetCatalogSessionProperty(ALICE, "unsafe");
        accessControl.checkCanSetCatalogSessionProperty(ALICE, "staff");
        accessControl.checkCanSetCatalogSessionProperty(BOB, "safe");
        accessControl.checkCanSetCatalogSessionProperty(BOB, "staff");
        assertDenied(() -> accessControl.checkCanSetCatalogSessionProperty(BOB, "unsafe"));
        assertDenied(() -> accessControl.checkCanSetCatalogSessionProperty(ALICE, "dangerous"));
        assertDenied(() -> accessControl.checkCanSetCatalogSessionProperty(CHARLIE, "safe"));
        assertDenied(() -> accessControl.checkCanSetCatalogSessionProperty(CHARLIE, "staff"));
        assertDenied(() -> accessControl.checkCanSetCatalogSessionProperty(JOE, "staff"));
    }

    @Test
    public void testInvalidRules()
    {
        assertThatThrownBy(() -> createAccessControl("invalid.json"))
                .hasMessageContaining("Invalid JSON");
    }

    @Test
    public void testFilterSchemas()
    {
        ConnectorAccessControl accessControl = createAccessControl("visibility.json");

        ImmutableSet<String> allSchemas = ImmutableSet.of("specific-schema", "alice-schema", "bob-schema", "unknown");
        assertEquals(accessControl.filterSchemas(ADMIN, allSchemas), allSchemas);
        assertEquals(accessControl.filterSchemas(ALICE, allSchemas), ImmutableSet.of("specific-schema", "alice-schema"));
        assertEquals(accessControl.filterSchemas(BOB, allSchemas), ImmutableSet.of("specific-schema", "bob-schema"));
        assertEquals(accessControl.filterSchemas(CHARLIE, allSchemas), ImmutableSet.of("specific-schema"));
    }

    @Test
    public void testSchemaRulesForCheckCanShowTables()
    {
        ConnectorAccessControl accessControl = createAccessControl("visibility.json");
        accessControl.checkCanShowTables(ADMIN, "specific-schema");
        accessControl.checkCanShowTables(ADMIN, "bob-schema");
        accessControl.checkCanShowTables(ADMIN, "alice-schema");
        accessControl.checkCanShowTables(ADMIN, "secret");
        accessControl.checkCanShowTables(ADMIN, "any");
        accessControl.checkCanShowTables(ALICE, "specific-schema");
        accessControl.checkCanShowTables(ALICE, "alice-schema");
        assertDenied(() -> accessControl.checkCanShowTables(ALICE, "bob-schema"));
        assertDenied(() -> accessControl.checkCanShowTables(ALICE, "secret"));
        assertDenied(() -> accessControl.checkCanShowTables(ALICE, "any"));
        accessControl.checkCanShowTables(BOB, "specific-schema");
        accessControl.checkCanShowTables(BOB, "bob-schema");
        assertDenied(() -> accessControl.checkCanShowTables(BOB, "alice-schema"));
        assertDenied(() -> accessControl.checkCanShowTables(BOB, "secret"));
        assertDenied(() -> accessControl.checkCanShowTables(BOB, "any"));
        accessControl.checkCanShowTables(CHARLIE, "specific-schema");
        assertDenied(() -> accessControl.checkCanShowTables(CHARLIE, "bob-schema"));
        assertDenied(() -> accessControl.checkCanShowTables(CHARLIE, "alice-schema"));
        assertDenied(() -> accessControl.checkCanShowTables(CHARLIE, "secret"));
        assertDenied(() -> accessControl.checkCanShowTables(CHARLIE, "any"));
    }

    @Test
    public void testEverythingImplemented()
    {
        assertAllMethodsOverridden(ConnectorAccessControl.class, FileBasedAccessControl.class);
    }

    private static ConnectorSecurityContext user(String name, Set<String> groups)
    {
        return new ConnectorSecurityContext(
                new ConnectorTransactionHandle() {},
                ConnectorIdentity.forUser(name).withGroups(groups).build(),
                new QueryId("query_id"));
    }

    private ConnectorAccessControl createAccessControl(String fileName)
    {
        String path = this.getClass().getClassLoader().getResource(fileName).getPath();
        FileBasedAccessControlConfig config = new FileBasedAccessControlConfig();
        config.setConfigFile(path);
        return new FileBasedAccessControl(config);
    }

    private static void assertDenied(ThrowingRunnable runnable)
    {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(AccessDeniedException.class)
                // TODO test expected message precisely, as in TestFileBasedSystemAccessControl
                .hasMessageStartingWith("Access Denied");
    }

    private static ColumnMetadata column(String columnName)
    {
        return new ColumnMetadata(columnName, VarcharType.VARCHAR);
    }
}
