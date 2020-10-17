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
import com.google.inject.Injector;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.prestosql.plugin.base.security.CatalogAccessControlRule.AccessMode;
import io.prestosql.plugin.base.security.TableAccessControlRule.TablePrivilege;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.CatalogSchemaRoutineName;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.eventlistener.EventListener;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;
import io.prestosql.spi.security.SystemAccessControl;
import io.prestosql.spi.security.SystemAccessControlFactory;
import io.prestosql.spi.security.SystemSecurityContext;
import io.prestosql.spi.security.ViewExpression;
import io.prestosql.spi.type.Type;

import java.nio.file.Paths;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.google.common.base.Suppliers.memoizeWithExpiration;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.prestosql.plugin.base.security.CatalogAccessControlRule.AccessMode.ALL;
import static io.prestosql.plugin.base.security.CatalogAccessControlRule.AccessMode.READ_ONLY;
import static io.prestosql.plugin.base.security.FileBasedAccessControlConfig.SECURITY_REFRESH_PERIOD;
import static io.prestosql.plugin.base.security.TableAccessControlRule.TablePrivilege.DELETE;
import static io.prestosql.plugin.base.security.TableAccessControlRule.TablePrivilege.GRANT_SELECT;
import static io.prestosql.plugin.base.security.TableAccessControlRule.TablePrivilege.INSERT;
import static io.prestosql.plugin.base.security.TableAccessControlRule.TablePrivilege.OWNERSHIP;
import static io.prestosql.plugin.base.security.TableAccessControlRule.TablePrivilege.SELECT;
import static io.prestosql.plugin.base.util.JsonUtils.parseJson;
import static io.prestosql.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.prestosql.spi.security.AccessDeniedException.denyAddColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyCatalogAccess;
import static io.prestosql.spi.security.AccessDeniedException.denyCommentColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyCommentTable;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateTable;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateView;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateViewWithSelect;
import static io.prestosql.spi.security.AccessDeniedException.denyDeleteTable;
import static io.prestosql.spi.security.AccessDeniedException.denyDropColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyDropSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyDropTable;
import static io.prestosql.spi.security.AccessDeniedException.denyDropView;
import static io.prestosql.spi.security.AccessDeniedException.denyGrantTablePrivilege;
import static io.prestosql.spi.security.AccessDeniedException.denyImpersonateUser;
import static io.prestosql.spi.security.AccessDeniedException.denyInsertTable;
import static io.prestosql.spi.security.AccessDeniedException.denyReadSystemInformationAccess;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameTable;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameView;
import static io.prestosql.spi.security.AccessDeniedException.denyRevokeTablePrivilege;
import static io.prestosql.spi.security.AccessDeniedException.denySelectTable;
import static io.prestosql.spi.security.AccessDeniedException.denySetCatalogSessionProperty;
import static io.prestosql.spi.security.AccessDeniedException.denySetSchemaAuthorization;
import static io.prestosql.spi.security.AccessDeniedException.denySetSystemSessionProperty;
import static io.prestosql.spi.security.AccessDeniedException.denySetUser;
import static io.prestosql.spi.security.AccessDeniedException.denyShowColumns;
import static io.prestosql.spi.security.AccessDeniedException.denyShowCreateSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyShowCreateTable;
import static io.prestosql.spi.security.AccessDeniedException.denyShowSchemas;
import static io.prestosql.spi.security.AccessDeniedException.denyShowTables;
import static io.prestosql.spi.security.AccessDeniedException.denyViewQuery;
import static io.prestosql.spi.security.AccessDeniedException.denyWriteSystemInformationAccess;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class FileBasedSystemAccessControl
        implements SystemAccessControl
{
    private static final Logger log = Logger.get(FileBasedSystemAccessControl.class);

    public static final String NAME = "file";
    private static final String INFORMATION_SCHEMA_NAME = "information_schema";

    private final List<CatalogAccessControlRule> catalogRules;
    private final Optional<List<QueryAccessRule>> queryAccessRules;
    private final Optional<List<ImpersonationRule>> impersonationRules;
    private final Optional<List<PrincipalUserMatchRule>> principalUserMatchRules;
    private final Optional<List<SystemInformationRule>> systemInformationRules;
    private final List<CatalogSchemaAccessControlRule> schemaRules;
    private final List<CatalogTableAccessControlRule> tableRules;
    private final List<SessionPropertyAccessControlRule> sessionPropertyRules;
    private final List<CatalogSessionPropertyAccessControlRule> catalogSessionPropertyRules;
    private final Set<AnyCatalogPermissionsRule> anyCatalogPermissionsRules;
    private final Set<AnyCatalogSchemaPermissionsRule> anyCatalogSchemaPermissionsRules;

    private FileBasedSystemAccessControl(
            List<CatalogAccessControlRule> catalogRules,
            Optional<List<QueryAccessRule>> queryAccessRules,
            Optional<List<ImpersonationRule>> impersonationRules,
            Optional<List<PrincipalUserMatchRule>> principalUserMatchRules,
            Optional<List<SystemInformationRule>> systemInformationRules,
            List<CatalogSchemaAccessControlRule> schemaRules,
            List<CatalogTableAccessControlRule> tableRules,
            List<SessionPropertyAccessControlRule> sessionPropertyRules,
            List<CatalogSessionPropertyAccessControlRule> catalogSessionPropertyRules)
    {
        this.catalogRules = catalogRules;
        this.queryAccessRules = queryAccessRules;
        this.impersonationRules = impersonationRules;
        this.principalUserMatchRules = principalUserMatchRules;
        this.systemInformationRules = systemInformationRules;
        this.schemaRules = schemaRules;
        this.tableRules = tableRules;
        this.sessionPropertyRules = sessionPropertyRules;
        this.catalogSessionPropertyRules = catalogSessionPropertyRules;

        ImmutableSet.Builder<AnyCatalogPermissionsRule> anyCatalogPermissionsRules = ImmutableSet.builder();
        schemaRules.stream()
                .map(CatalogSchemaAccessControlRule::toAnyCatalogPermissionsRule)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(anyCatalogPermissionsRules::add);
        tableRules.stream()
                .map(CatalogTableAccessControlRule::toAnyCatalogPermissionsRule)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(anyCatalogPermissionsRules::add);
        catalogSessionPropertyRules.stream()
                .map(CatalogSessionPropertyAccessControlRule::toAnyCatalogPermissionsRule)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(anyCatalogPermissionsRules::add);
        this.anyCatalogPermissionsRules = anyCatalogPermissionsRules.build();

        ImmutableSet.Builder<AnyCatalogSchemaPermissionsRule> anyCatalogSchemaPermissionsRules = ImmutableSet.builder();
        schemaRules.stream()
                .map(CatalogSchemaAccessControlRule::toAnyCatalogSchemaPermissionsRule)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(anyCatalogSchemaPermissionsRules::add);
        tableRules.stream()
                .map(CatalogTableAccessControlRule::toAnyCatalogSchemaPermissionsRule)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(anyCatalogSchemaPermissionsRules::add);
        this.anyCatalogSchemaPermissionsRules = anyCatalogSchemaPermissionsRules.build();
    }

    public static class Factory
            implements SystemAccessControlFactory
    {
        @Override
        public String getName()
        {
            return NAME;
        }

        @Override
        public SystemAccessControl create(Map<String, String> config)
        {
            requireNonNull(config, "config is null");

            Bootstrap bootstrap = new Bootstrap(
                    binder -> configBinder(binder).bindConfig(FileBasedAccessControlConfig.class));
            Injector injector = bootstrap.strictConfig()
                    .doNotInitializeLogging()
                    .setRequiredConfigurationProperties(config)
                    .initialize();
            FileBasedAccessControlConfig fileBasedAccessControlConfig = injector.getInstance(FileBasedAccessControlConfig.class);
            String configFileName = fileBasedAccessControlConfig.getConfigFile();

            if (config.containsKey(SECURITY_REFRESH_PERIOD)) {
                Duration refreshPeriod;
                try {
                    refreshPeriod = fileBasedAccessControlConfig.getRefreshPeriod();
                }
                catch (IllegalArgumentException e) {
                    throw invalidRefreshPeriodException(config, configFileName);
                }
                if (refreshPeriod.toMillis() == 0) {
                    throw invalidRefreshPeriodException(config, configFileName);
                }
                return ForwardingSystemAccessControl.of(memoizeWithExpiration(
                        () -> {
                            log.info("Refreshing system access control from %s", configFileName);
                            return create(configFileName);
                        },
                        refreshPeriod.toMillis(),
                        MILLISECONDS));
            }
            return create(configFileName);
        }

        private static PrestoException invalidRefreshPeriodException(Map<String, String> config, String configFileName)
        {
            return new PrestoException(
                    CONFIGURATION_INVALID,
                    format("Invalid duration value '%s' for property '%s' in '%s'", config.get(SECURITY_REFRESH_PERIOD), SECURITY_REFRESH_PERIOD, configFileName));
        }

        private static SystemAccessControl create(String configFileName)
        {
            FileBasedSystemAccessControlRules rules = parseJson(Paths.get(configFileName), FileBasedSystemAccessControlRules.class);
            List<CatalogAccessControlRule> catalogAccessControlRules;
            if (rules.getCatalogRules().isPresent()) {
                ImmutableList.Builder<CatalogAccessControlRule> catalogRulesBuilder = ImmutableList.builder();
                catalogRulesBuilder.addAll(rules.getCatalogRules().get());

                // Hack to allow Presto Admin to access the "system" catalog for retrieving server status.
                // todo Change userRegex from ".*" to one particular user that Presto Admin will be restricted to run as
                catalogRulesBuilder.add(new CatalogAccessControlRule(
                        ALL,
                        Optional.of(Pattern.compile(".*")),
                        Optional.empty(),
                        Optional.of(Pattern.compile("system"))));
                catalogAccessControlRules = catalogRulesBuilder.build();
            }
            else {
                // if no rules are defined then all access is allowed
                catalogAccessControlRules = ImmutableList.of(CatalogAccessControlRule.ALLOW_ALL);
            }
            return FileBasedSystemAccessControl.builder()
                    .setCatalogRules(catalogAccessControlRules)
                    .setQueryAccessRules(rules.getQueryAccessRules())
                    .setImpersonationRules(rules.getImpersonationRules())
                    .setPrincipalUserMatchRules(rules.getPrincipalUserMatchRules())
                    .setSystemInformationRules(rules.getSystemInformationRules())
                    .setSchemaRules(rules.getSchemaRules().orElse(ImmutableList.of(CatalogSchemaAccessControlRule.ALLOW_ALL)))
                    .setTableRules(rules.getTableRules().orElse(ImmutableList.of(CatalogTableAccessControlRule.ALLOW_ALL)))
                    .setSessionPropertyRules(rules.getSessionPropertyRules().orElse(ImmutableList.of(SessionPropertyAccessControlRule.ALLOW_ALL)))
                    .setCatalogSessionPropertyRules(rules.getCatalogSessionPropertyRules().orElse(ImmutableList.of(CatalogSessionPropertyAccessControlRule.ALLOW_ALL)))
                    .build();
        }
    }

    @Override
    public void checkCanImpersonateUser(SystemSecurityContext context, String userName)
    {
        if (impersonationRules.isEmpty()) {
            // if there are principal user match rules, we assume that impersonation checks are
            // handled there; otherwise, impersonation must be manually configured
            if (principalUserMatchRules.isEmpty()) {
                denyImpersonateUser(context.getIdentity().getUser(), userName);
            }
            return;
        }

        for (ImpersonationRule rule : impersonationRules.get()) {
            Optional<Boolean> allowed = rule.match(context.getIdentity().getUser(), userName);
            if (allowed.isPresent()) {
                if (allowed.get()) {
                    return;
                }
                denyImpersonateUser(context.getIdentity().getUser(), userName);
            }
        }

        denyImpersonateUser(context.getIdentity().getUser(), userName);
    }

    @Override
    public void checkCanSetUser(Optional<Principal> principal, String userName)
    {
        requireNonNull(principal, "principal is null");
        requireNonNull(userName, "userName is null");

        if (principalUserMatchRules.isEmpty()) {
            return;
        }

        if (principal.isEmpty()) {
            denySetUser(principal, userName);
        }

        String principalName = principal.get().getName();

        for (PrincipalUserMatchRule rule : principalUserMatchRules.get()) {
            Optional<Boolean> allowed = rule.match(principalName, userName);
            if (allowed.isPresent()) {
                if (allowed.get()) {
                    return;
                }
                denySetUser(principal, userName);
            }
        }

        denySetUser(principal, userName);
    }

    @Override
    public void checkCanExecuteQuery(SystemSecurityContext context)
    {
        if (!canAccessQuery(context.getIdentity(), QueryAccessRule.AccessMode.EXECUTE)) {
            denyViewQuery();
        }
    }

    @Override
    public void checkCanViewQueryOwnedBy(SystemSecurityContext context, String queryOwner)
    {
        if (!canAccessQuery(context.getIdentity(), QueryAccessRule.AccessMode.VIEW)) {
            denyViewQuery();
        }
    }

    @Override
    public Set<String> filterViewQueryOwnedBy(SystemSecurityContext context, Set<String> queryOwners)
    {
        if (queryAccessRules.isEmpty()) {
            return queryOwners;
        }
        Identity identity = context.getIdentity();
        return queryOwners.stream()
                .filter(owner -> canAccessQuery(identity, QueryAccessRule.AccessMode.VIEW))
                .collect(toImmutableSet());
    }

    @Override
    public void checkCanKillQueryOwnedBy(SystemSecurityContext context, String queryOwner)
    {
        if (!canAccessQuery(context.getIdentity(), QueryAccessRule.AccessMode.KILL)) {
            denyViewQuery();
        }
    }

    private boolean canAccessQuery(Identity identity, QueryAccessRule.AccessMode requiredAccess)
    {
        if (queryAccessRules.isEmpty()) {
            return true;
        }
        for (QueryAccessRule rule : queryAccessRules.get()) {
            Optional<Set<QueryAccessRule.AccessMode>> accessMode = rule.match(identity.getUser());
            if (accessMode.isPresent()) {
                return accessMode.get().contains(requiredAccess);
            }
        }
        return false;
    }

    @Override
    public void checkCanReadSystemInformation(SystemSecurityContext context)
    {
        if (!checkCanSystemInformation(context.getIdentity(), SystemInformationRule.AccessMode.READ)) {
            denyReadSystemInformationAccess();
        }
    }

    @Override
    public void checkCanWriteSystemInformation(SystemSecurityContext context)
    {
        if (!checkCanSystemInformation(context.getIdentity(), SystemInformationRule.AccessMode.WRITE)) {
            denyWriteSystemInformationAccess();
        }
    }

    private boolean checkCanSystemInformation(Identity identity, SystemInformationRule.AccessMode requiredAccess)
    {
        for (SystemInformationRule rule : systemInformationRules.orElseGet(ImmutableList::of)) {
            Optional<Set<SystemInformationRule.AccessMode>> accessMode = rule.match(identity.getUser());
            if (accessMode.isPresent()) {
                return accessMode.get().contains(requiredAccess);
            }
        }
        return false;
    }

    @Override
    public void checkCanSetSystemSessionProperty(SystemSecurityContext context, String propertyName)
    {
        Identity identity = context.getIdentity();
        boolean allowed = sessionPropertyRules.stream()
                .map(rule -> rule.match(identity.getUser(), identity.getGroups(), propertyName))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(false);
        if (!allowed) {
            denySetSystemSessionProperty(propertyName);
        }
    }

    @Override
    public void checkCanAccessCatalog(SystemSecurityContext context, String catalogName)
    {
        if (!canAccessCatalog(context, catalogName, READ_ONLY)) {
            denyCatalogAccess(catalogName);
        }
    }

    @Override
    public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs)
    {
        ImmutableSet.Builder<String> filteredCatalogs = ImmutableSet.builder();
        for (String catalog : catalogs) {
            if (checkAnyCatalogAccess(context, catalog)) {
                filteredCatalogs.add(catalog);
            }
        }
        return filteredCatalogs.build();
    }

    @Override
    public void checkCanCreateSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
        if (!isSchemaOwner(context, schema)) {
            denyCreateSchema(schema.toString());
        }
    }

    @Override
    public void checkCanDropSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
        if (!isSchemaOwner(context, schema)) {
            denyDropSchema(schema.toString());
        }
    }

    @Override
    public void checkCanRenameSchema(SystemSecurityContext context, CatalogSchemaName schema, String newSchemaName)
    {
        if (!isSchemaOwner(context, schema) || !isSchemaOwner(context, new CatalogSchemaName(schema.getCatalogName(), newSchemaName))) {
            denyRenameSchema(schema.toString(), newSchemaName);
        }
    }

    @Override
    public void checkCanSetSchemaAuthorization(SystemSecurityContext context, CatalogSchemaName schema, PrestoPrincipal principal)
    {
        if (!isSchemaOwner(context, schema)) {
            denySetSchemaAuthorization(schema.toString(), principal);
        }
    }

    @Override
    public void checkCanShowSchemas(SystemSecurityContext context, String catalogName)
    {
        if (!checkAnyCatalogAccess(context, catalogName)) {
            denyShowSchemas();
        }
    }

    @Override
    public Set<String> filterSchemas(SystemSecurityContext context, String catalogName, Set<String> schemaNames)
    {
        return schemaNames.stream()
                .filter(schemaName -> checkAnySchemaAccess(context, catalogName, schemaName))
                .collect(toImmutableSet());
    }

    @Override
    public void checkCanShowCreateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyShowCreateTable(table.toString());
        }
    }

    @Override
    public void checkCanShowCreateSchema(SystemSecurityContext context, CatalogSchemaName schemaName)
    {
        if (!isSchemaOwner(context, schemaName)) {
            denyShowCreateSchema(schemaName.toString());
        }
    }

    @Override
    public void checkCanCreateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        // check if user will be an owner of the table after creation
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyCreateTable(table.toString());
        }
    }

    @Override
    public void checkCanDropTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyDropTable(table.toString());
        }
    }

    @Override
    public void checkCanRenameTable(SystemSecurityContext context, CatalogSchemaTableName table, CatalogSchemaTableName newTable)
    {
        // check if user is an owner current table and will be an owner of the renamed table
        if (!checkTablePermission(context, table, OWNERSHIP) || !checkTablePermission(context, newTable, OWNERSHIP)) {
            denyRenameTable(table.toString(), newTable.toString());
        }
    }

    @Override
    public void checkCanSetTableComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyCommentTable(table.toString());
        }
    }

    @Override
    public void checkCanSetColumnComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyCommentColumn(table.toString());
        }
    }

    @Override
    public void checkCanShowTables(SystemSecurityContext context, CatalogSchemaName schema)
    {
        if (!checkAnySchemaAccess(context, schema.getCatalogName(), schema.getSchemaName())) {
            denyShowTables(schema.toString());
        }
    }

    @Override
    public Set<SchemaTableName> filterTables(SystemSecurityContext context, String catalogName, Set<SchemaTableName> tableNames)
    {
        return tableNames.stream()
                .filter(tableName -> isSchemaOwner(context, new CatalogSchemaName(catalogName, tableName.getSchemaName())) ||
                        checkAnyTablePermission(context, new CatalogSchemaTableName(catalogName, tableName)))
                .collect(toImmutableSet());
    }

    @Override
    public void checkCanShowColumns(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkAnyTablePermission(context, table)) {
            denyShowColumns(table.toString());
        }
    }

    @Override
    public List<ColumnMetadata> filterColumns(SystemSecurityContext context, CatalogSchemaTableName tableName, List<ColumnMetadata> columns)
    {
        if (!checkAnyTablePermission(context, tableName)) {
            return ImmutableList.of();
        }

        return columns;
    }

    @Override
    public void checkCanAddColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyAddColumn(table.toString());
        }
    }

    @Override
    public void checkCanDropColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyDropColumn(table.toString());
        }
    }

    @Override
    public void checkCanRenameColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyRenameColumn(table.toString());
        }
    }

    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        if (!checkTablePermission(context, table, SELECT)) {
            denySelectTable(table.toString());
        }
    }

    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, INSERT)) {
            denyInsertTable(table.toString());
        }
    }

    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!checkTablePermission(context, table, DELETE)) {
            denyDeleteTable(table.toString());
        }
    }

    @Override
    public void checkCanCreateView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        // check if user will be an owner of the view after creation
        if (!checkTablePermission(context, view, OWNERSHIP)) {
            denyCreateView(view.toString());
        }
    }

    @Override
    public void checkCanRenameView(SystemSecurityContext context, CatalogSchemaTableName view, CatalogSchemaTableName newView)
    {
        // check if user owns the existing view, and if they will be an owner of the view after the rename
        if (!checkTablePermission(context, view, OWNERSHIP) || !checkTablePermission(context, newView, OWNERSHIP)) {
            denyRenameView(view.toString(), newView.toString());
        }
    }

    @Override
    public void checkCanDropView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        if (!checkTablePermission(context, view, OWNERSHIP)) {
            denyDropView(view.toString());
        }
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        // TODO: implement column level permissions
        if (!checkTablePermission(context, table, SELECT)) {
            denySelectTable(table.toString());
        }
        if (!checkTablePermission(context, table, GRANT_SELECT)) {
            denyCreateViewWithSelect(table.toString(), context.getIdentity());
        }
    }

    @Override
    public void checkCanGrantExecuteFunctionPrivilege(SystemSecurityContext context, String functionName, PrestoPrincipal grantee, boolean grantOption)
    {
    }

    @Override
    public void checkCanSetCatalogSessionProperty(SystemSecurityContext context, String catalogName, String propertyName)
    {
        Identity identity = context.getIdentity();
        boolean allowed = canAccessCatalog(context, catalogName, READ_ONLY) && catalogSessionPropertyRules.stream()
                .map(rule -> rule.match(identity.getUser(), identity.getGroups(), catalogName, propertyName))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(false);
        if (!allowed) {
            denySetCatalogSessionProperty(propertyName);
        }
    }

    @Override
    public void checkCanGrantTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, PrestoPrincipal grantee, boolean grantOption)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyGrantTablePrivilege(privilege.name(), table.toString());
        }
    }

    @Override
    public void checkCanRevokeTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, PrestoPrincipal revokee, boolean grantOption)
    {
        if (!checkTablePermission(context, table, OWNERSHIP)) {
            denyRevokeTablePrivilege(privilege.name(), table.toString());
        }
    }

    @Override
    public void checkCanShowRoles(SystemSecurityContext context, String catalogName)
    {
    }

    @Override
    public void checkCanExecuteProcedure(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName procedure)
    {
    }

    @Override
    public void checkCanExecuteFunction(SystemSecurityContext systemSecurityContext, String functionName)
    {
    }

    @Override
    public Iterable<EventListener> getEventListeners()
    {
        return ImmutableSet.of();
    }

    @Override
    public Optional<ViewExpression> getRowFilter(SystemSecurityContext context, CatalogSchemaTableName tableName)
    {
        return Optional.empty();
    }

    @Override
    public Optional<ViewExpression> getColumnMask(SystemSecurityContext context, CatalogSchemaTableName tableName, String columnName, Type type)
    {
        return Optional.empty();
    }

    private boolean checkAnyCatalogAccess(SystemSecurityContext context, String catalogName)
    {
        Identity identity = context.getIdentity();
        return canAccessCatalog(context, catalogName, READ_ONLY) &&
                anyCatalogPermissionsRules.stream().anyMatch(rule -> rule.match(identity.getUser(), identity.getGroups(), catalogName));
    }

    private boolean canAccessCatalog(SystemSecurityContext context, String catalogName, AccessMode requiredAccess)
    {
        Identity identity = context.getIdentity();
        for (CatalogAccessControlRule rule : catalogRules) {
            Optional<AccessMode> accessMode = rule.match(identity.getUser(), identity.getGroups(), catalogName);
            if (accessMode.isPresent()) {
                return accessMode.get().implies(requiredAccess);
            }
        }
        return false;
    }

    private boolean checkAnySchemaAccess(SystemSecurityContext context, String catalogName, String schemaName)
    {
        Identity identity = context.getIdentity();
        return canAccessCatalog(context, catalogName, READ_ONLY) &&
                anyCatalogSchemaPermissionsRules.stream().anyMatch(rule -> rule.match(identity.getUser(), identity.getGroups(), catalogName, schemaName));
    }

    private boolean isSchemaOwner(SystemSecurityContext context, CatalogSchemaName schema)
    {
        if (!canAccessCatalog(context, schema.getCatalogName(), ALL)) {
            return false;
        }

        Identity identity = context.getIdentity();
        for (CatalogSchemaAccessControlRule rule : schemaRules) {
            Optional<Boolean> owner = rule.match(identity.getUser(), identity.getGroups(), schema);
            if (owner.isPresent()) {
                return owner.get();
            }
        }
        return false;
    }

    private boolean checkAnyTablePermission(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        return checkTablePermission(context, table, READ_ONLY, privileges -> !privileges.isEmpty());
    }

    private boolean checkTablePermission(SystemSecurityContext context, CatalogSchemaTableName table, TableAccessControlRule.TablePrivilege requiredPrivilege)
    {
        AccessMode requiredCatalogAccess = requiredPrivilege == SELECT || requiredPrivilege == GRANT_SELECT ? READ_ONLY : ALL;
        return checkTablePermission(context, table, requiredCatalogAccess, privileges -> privileges.contains(requiredPrivilege));
    }

    private boolean checkTablePermission(
            SystemSecurityContext context,
            CatalogSchemaTableName table,
            AccessMode requiredCatalogAccess,
            Predicate<Set<TablePrivilege>> checkPrivileges)
    {
        if (!canAccessCatalog(context, table.getCatalogName(), requiredCatalogAccess)) {
            return false;
        }

        if (INFORMATION_SCHEMA_NAME.equals(table.getSchemaTableName().getSchemaName())) {
            return true;
        }

        Identity identity = context.getIdentity();
        for (CatalogTableAccessControlRule rule : tableRules) {
            Optional<Set<TableAccessControlRule.TablePrivilege>> tablePrivileges = rule.match(identity.getUser(), identity.getGroups(), table);
            if (tablePrivileges.isPresent()) {
                return checkPrivileges.test(tablePrivileges.get());
            }
        }
        return false;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static final class Builder
    {
        private List<CatalogAccessControlRule> catalogRules = ImmutableList.of(CatalogAccessControlRule.ALLOW_ALL);
        private Optional<List<QueryAccessRule>> queryAccessRules = Optional.empty();
        private Optional<List<ImpersonationRule>> impersonationRules = Optional.empty();
        private Optional<List<PrincipalUserMatchRule>> principalUserMatchRules = Optional.empty();
        private Optional<List<SystemInformationRule>> systemInformationRules = Optional.empty();
        private List<CatalogSchemaAccessControlRule> schemaRules = ImmutableList.of(CatalogSchemaAccessControlRule.ALLOW_ALL);
        private List<CatalogTableAccessControlRule> tableRules = ImmutableList.of(CatalogTableAccessControlRule.ALLOW_ALL);
        private List<SessionPropertyAccessControlRule> sessionPropertyRules = ImmutableList.of(SessionPropertyAccessControlRule.ALLOW_ALL);
        private List<CatalogSessionPropertyAccessControlRule> catalogSessionPropertyRules = ImmutableList.of(CatalogSessionPropertyAccessControlRule.ALLOW_ALL);

        @SuppressWarnings("unused")
        public Builder denyAllAccess()
        {
            catalogRules = ImmutableList.of();
            queryAccessRules = Optional.of(ImmutableList.of());
            impersonationRules = Optional.of(ImmutableList.of());
            principalUserMatchRules = Optional.of(ImmutableList.of());
            systemInformationRules = Optional.of(ImmutableList.of());
            schemaRules = ImmutableList.of();
            tableRules = ImmutableList.of();
            sessionPropertyRules = ImmutableList.of();
            catalogSessionPropertyRules = ImmutableList.of();
            return this;
        }

        public Builder setCatalogRules(List<CatalogAccessControlRule> catalogRules)
        {
            this.catalogRules = catalogRules;
            return this;
        }

        public Builder setQueryAccessRules(Optional<List<QueryAccessRule>> queryAccessRules)
        {
            this.queryAccessRules = queryAccessRules;
            return this;
        }

        public Builder setImpersonationRules(Optional<List<ImpersonationRule>> impersonationRules)
        {
            this.impersonationRules = impersonationRules;
            return this;
        }

        public Builder setPrincipalUserMatchRules(Optional<List<PrincipalUserMatchRule>> principalUserMatchRules)
        {
            this.principalUserMatchRules = principalUserMatchRules;
            return this;
        }

        public Builder setSystemInformationRules(Optional<List<SystemInformationRule>> systemInformationRules)
        {
            this.systemInformationRules = systemInformationRules;
            return this;
        }

        public Builder setSchemaRules(List<CatalogSchemaAccessControlRule> schemaRules)
        {
            this.schemaRules = schemaRules;
            return this;
        }

        public Builder setTableRules(List<CatalogTableAccessControlRule> tableRules)
        {
            this.tableRules = tableRules;
            return this;
        }

        public Builder setSessionPropertyRules(List<SessionPropertyAccessControlRule> sessionPropertyRules)
        {
            this.sessionPropertyRules = sessionPropertyRules;
            return this;
        }

        public Builder setCatalogSessionPropertyRules(List<CatalogSessionPropertyAccessControlRule> catalogSessionPropertyRules)
        {
            this.catalogSessionPropertyRules = catalogSessionPropertyRules;
            return this;
        }

        public FileBasedSystemAccessControl build()
        {
            return new FileBasedSystemAccessControl(
                    catalogRules,
                    queryAccessRules,
                    impersonationRules,
                    principalUserMatchRules,
                    systemInformationRules,
                    schemaRules,
                    tableRules,
                    sessionPropertyRules,
                    catalogSessionPropertyRules);
        }
    }
}
