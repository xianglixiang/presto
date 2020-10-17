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
package io.prestosql.sql.query;

import io.prestosql.Session;
import io.prestosql.cost.PlanNodeStatsEstimate;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.spi.type.SqlTime;
import io.prestosql.spi.type.SqlTimeWithTimeZone;
import io.prestosql.spi.type.SqlTimestamp;
import io.prestosql.spi.type.SqlTimestampWithTimeZone;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.Plan;
import io.prestosql.sql.planner.assertions.PlanAssert;
import io.prestosql.sql.planner.assertions.PlanMatchPattern;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.TableScanNode;
import io.prestosql.testing.LocalQueryRunner;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import io.prestosql.testing.QueryRunner;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.AssertProvider;
import org.assertj.core.api.ListAssert;
import org.assertj.core.presentation.Representation;
import org.assertj.core.presentation.StandardRepresentation;
import org.intellij.lang.annotations.Language;

import java.io.Closeable;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static io.airlift.testing.Assertions.assertEqualsIgnoreOrder;
import static io.prestosql.sql.planner.assertions.PlanAssert.assertPlan;
import static io.prestosql.sql.query.QueryAssertions.ExpressionAssert.newExpressionAssert;
import static io.prestosql.sql.query.QueryAssertions.QueryAssert.newQueryAssert;
import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static io.prestosql.transaction.TransactionBuilder.transaction;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class QueryAssertions
        implements Closeable
{
    private final QueryRunner runner;

    public QueryAssertions()
    {
        this(testSessionBuilder()
                .setCatalog("local")
                .setSchema("default")
                .build());
    }

    public QueryAssertions(Session session)
    {
        this(LocalQueryRunner.create(session));
    }

    public QueryAssertions(QueryRunner runner)
    {
        this.runner = requireNonNull(runner, "runner is null");
    }

    public Session.SessionBuilder sessionBuilder()
    {
        return Session.builder(runner.getDefaultSession());
    }

    public Session getDefaultSession()
    {
        return runner.getDefaultSession();
    }

    public AssertProvider<QueryAssert> query(@Language("SQL") String query)
    {
        return query(runner.getDefaultSession(), query);
    }

    @Deprecated
    public AssertProvider<QueryAssert> query(@Language("SQL") String query, Session session)
    {
        return query(session, query);
    }

    public AssertProvider<QueryAssert> query(Session session, @Language("SQL") String query)
    {
        return newQueryAssert(query, runner, session);
    }

    public AssertProvider<ExpressionAssert> expression(@Language("SQL") String expression)
    {
        return expression(expression, runner.getDefaultSession());
    }

    public AssertProvider<ExpressionAssert> expression(@Language("SQL") String expression, Session session)
    {
        return newExpressionAssert(expression, runner, session);
    }

    public void assertQueryAndPlan(
            @Language("SQL") String actual,
            @Language("SQL") String expected,
            PlanMatchPattern pattern)
    {
        assertQuery(runner.getDefaultSession(), actual, expected, false);

        Plan plan = runner.executeWithPlan(runner.getDefaultSession(), actual, WarningCollector.NOOP).getQueryPlan();
        PlanAssert.assertPlan(runner.getDefaultSession(), runner.getMetadata(), runner.getStatsCalculator(), plan, pattern);
    }

    private void assertQuery(Session session, @Language("SQL") String actual, @Language("SQL") String expected, boolean ensureOrdering)
    {
        MaterializedResult actualResults = null;
        try {
            actualResults = execute(session, actual);
        }
        catch (RuntimeException ex) {
            fail("Execution of 'actual' query failed: " + actual, ex);
        }

        MaterializedResult expectedResults = null;
        try {
            expectedResults = execute(expected);
        }
        catch (RuntimeException ex) {
            fail("Execution of 'expected' query failed: " + expected, ex);
        }

        assertEquals(actualResults.getTypes(), expectedResults.getTypes(), "Types mismatch for query: \n " + actual + "\n:");

        List<MaterializedRow> actualRows = actualResults.getMaterializedRows();
        List<MaterializedRow> expectedRows = expectedResults.getMaterializedRows();

        if (ensureOrdering) {
            if (!actualRows.equals(expectedRows)) {
                assertEquals(actualRows, expectedRows, "For query: \n " + actual + "\n:");
            }
        }
        else {
            assertEqualsIgnoreOrder(actualRows, expectedRows, "For query: \n " + actual);
        }
    }

    public void assertQueryReturnsEmptyResult(@Language("SQL") String actual)
    {
        MaterializedResult actualResults = null;
        try {
            actualResults = execute(actual);
        }
        catch (RuntimeException ex) {
            fail("Execution of 'actual' query failed: " + actual, ex);
        }
        List<MaterializedRow> actualRows = actualResults.getMaterializedRows();
        assertEquals(actualRows.size(), 0);
    }

    public MaterializedResult execute(@Language("SQL") String query)
    {
        return execute(runner.getDefaultSession(), query);
    }

    public MaterializedResult execute(Session session, @Language("SQL") String query)
    {
        MaterializedResult actualResults;
        actualResults = runner.execute(session, query).toTestTypes();
        return actualResults;
    }

    @Override
    public void close()
    {
        runner.close();
    }

    public QueryRunner getQueryRunner()
    {
        return runner;
    }

    protected void executeExclusively(Runnable executionBlock)
    {
        runner.getExclusiveLock().lock();
        try {
            executionBlock.run();
        }
        finally {
            runner.getExclusiveLock().unlock();
        }
    }

    public static class QueryAssert
            extends AbstractAssert<QueryAssert, MaterializedResult>
    {
        private static final Representation ROWS_REPRESENTATION = new StandardRepresentation()
        {
            @Override
            public String toStringOf(Object object)
            {
                if (object instanceof List) {
                    List<?> list = (List<?>) object;
                    return list.stream()
                            .map(this::toStringOf)
                            .collect(Collectors.joining(", "));
                }
                if (object instanceof MaterializedRow) {
                    MaterializedRow row = (MaterializedRow) object;

                    return row.getFields().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(", ", "(", ")"));
                }
                else {
                    return super.toStringOf(object);
                }
            }
        };

        private final QueryRunner runner;
        private final Session session;
        private final String query;
        private boolean ordered;

        static AssertProvider<QueryAssert> newQueryAssert(String query, QueryRunner runner, Session session)
        {
            MaterializedResult result = runner.execute(session, query);
            return () -> new QueryAssert(runner, session, query, result);
        }

        public QueryAssert(QueryRunner runner, Session session, String query, MaterializedResult actual)
        {
            super(actual, Object.class);
            this.runner = requireNonNull(runner, "runner is null");
            this.session = requireNonNull(session, "session is null");
            this.query = requireNonNull(query, "query is null");
        }

        public QueryAssert matches(BiFunction<Session, QueryRunner, MaterializedResult> evaluator)
        {
            MaterializedResult expected = evaluator.apply(session, runner);
            return isEqualTo(expected);
        }

        public QueryAssert ordered()
        {
            ordered = true;
            return this;
        }

        public QueryAssert matches(@Language("SQL") String query)
        {
            MaterializedResult expected = runner.execute(session, query);
            return matches(expected);
        }

        private QueryAssert matches(MaterializedResult expected)
        {
            return satisfies(actual -> {
                assertThat(actual.getTypes())
                        .as("Output types")
                        .isEqualTo(expected.getTypes());

                ListAssert<MaterializedRow> assertion = assertThat(actual.getMaterializedRows())
                        .as("Rows")
                        .withRepresentation(ROWS_REPRESENTATION);

                if (ordered) {
                    assertion.containsExactlyElementsOf(expected.getMaterializedRows());
                }
                else {
                    assertion.containsExactlyInAnyOrderElementsOf(expected.getMaterializedRows());
                }
            });
        }

        public QueryAssert returnsEmptyResult()
        {
            return satisfies(actual -> {
                assertThat(actual.getRowCount()).as("row count").isEqualTo(0);
            });
        }

        /**
         * Verifies query is fully pushed down and verifies the results are the same as when the pushdown is disabled.
         */
        public QueryAssert isFullyPushedDown()
        {
            checkState(!(runner instanceof LocalQueryRunner), "testIsFullyPushedDown() currently does not work with LocalQueryRunner");

            // Compare the results with pushdown disabled, so that explicit matches() call is not needed
            verifyResultsWithPushdownDisabled();

            transaction(runner.getTransactionManager(), runner.getAccessControl())
                    .execute(session, session -> {
                        Plan plan = runner.createPlan(session, query, WarningCollector.NOOP);
                        assertPlan(
                                session,
                                runner.getMetadata(),
                                (node, sourceStats, lookup, ignore, types) -> PlanNodeStatsEstimate.unknown(),
                                plan,
                                PlanMatchPattern.output(
                                        PlanMatchPattern.exchange(
                                                PlanMatchPattern.node(TableScanNode.class))));
                    });

            return this;
        }

        /**
         * @deprecated Use {@link #isFullyPushedDown()} instead.
         */
        @Deprecated
        public QueryAssert isCorrectlyPushedDown()
        {
            return isFullyPushedDown();
        }

        /**
         * Verifies query is not fully pushed down and verifies the results are the same as when the pushdown is fully disabled.
         * <p>
         * <b>Note:</b> the primary intent of this assertion is to ensure the test is updated to {@link #isFullyPushedDown()}
         * when pushdown capabilities are improved.
         */
        public QueryAssert isNotFullyPushedDown(Class<? extends PlanNode> retainedNode)
        {
            // Compare the results with pushdown disabled, so that explicit matches() call is not needed
            verifyResultsWithPushdownDisabled();

            transaction(runner.getTransactionManager(), runner.getAccessControl())
                    .execute(session, session -> {
                        Plan plan = runner.createPlan(session, query, WarningCollector.NOOP);
                        assertPlan(
                                session,
                                runner.getMetadata(),
                                (node, sourceStats, lookup, ignore, types) -> PlanNodeStatsEstimate.unknown(),
                                plan,
                                PlanMatchPattern.anyTree(
                                        PlanMatchPattern.node(retainedNode,
                                                PlanMatchPattern.node(TableScanNode.class))));
                    });

            return this;
        }

        private void verifyResultsWithPushdownDisabled()
        {
            Session withoutPushdown = Session.builder(session)
                    .setSystemProperty("allow_pushdown_into_connectors", "false")
                    .build();
            matches(runner.execute(withoutPushdown, query));
        }
    }

    public static class ExpressionAssert
            extends AbstractAssert<ExpressionAssert, Object>
    {
        private static final StandardRepresentation TYPE_RENDERER = new StandardRepresentation()
        {
            @Override
            public String toStringOf(Object object)
            {
                if (object instanceof SqlTimestamp) {
                    SqlTimestamp timestamp = (SqlTimestamp) object;
                    return String.format(
                            "%s [p = %s, epochMicros = %s, fraction = %s]",
                            timestamp,
                            timestamp.getPrecision(),
                            timestamp.getEpochMicros(),
                            timestamp.getPicosOfMicros());
                }
                else if (object instanceof SqlTimestampWithTimeZone) {
                    SqlTimestampWithTimeZone timestamp = (SqlTimestampWithTimeZone) object;
                    return String.format(
                            "%s [p = %s, epochMillis = %s, fraction = %s, tz = %s]",
                            timestamp,
                            timestamp.getPrecision(),
                            timestamp.getEpochMillis(),
                            timestamp.getPicosOfMilli(),
                            timestamp.getTimeZoneKey());
                }
                else if (object instanceof SqlTime) {
                    SqlTime time = (SqlTime) object;
                    return String.format("%s [picos = %s]", time, time.getPicos());
                }
                else if (object instanceof SqlTimeWithTimeZone) {
                    SqlTimeWithTimeZone time = (SqlTimeWithTimeZone) object;
                    return String.format(
                            "%s [picos = %s, offset = %s]",
                            time,
                            time.getPicos(),
                            time.getOffsetMinutes());
                }

                return Objects.toString(object);
            }
        };

        private final QueryRunner runner;
        private final Session session;
        private final Type actualType;

        static AssertProvider<ExpressionAssert> newExpressionAssert(String expression, QueryRunner runner, Session session)
        {
            MaterializedResult result = runner.execute(session, "VALUES " + expression);
            Type type = result.getTypes().get(0);
            Object value = result.getOnlyColumnAsSet().iterator().next();
            return () -> new ExpressionAssert(runner, session, value, type)
                    .withRepresentation(TYPE_RENDERER);
        }

        public ExpressionAssert(QueryRunner runner, Session session, Object actual, Type actualType)
        {
            super(actual, Object.class);
            this.runner = runner;
            this.session = session;
            this.actualType = actualType;
        }

        public ExpressionAssert isEqualTo(BiFunction<Session, QueryRunner, Object> evaluator)
        {
            return isEqualTo(evaluator.apply(session, runner));
        }

        public ExpressionAssert matches(@Language("SQL") String expression)
        {
            MaterializedResult result = runner.execute(session, "VALUES " + expression);
            Type expectedType = result.getTypes().get(0);
            Object expectedValue = result.getOnlyColumnAsSet().iterator().next();

            return satisfies(actual -> {
                assertThat(actualType).as("Type")
                        .isEqualTo(expectedType);

                assertThat(actual)
                        .withRepresentation(TYPE_RENDERER)
                        .isEqualTo(expectedValue);
            });
        }

        public ExpressionAssert hasType(Type type)
        {
            objects.assertEqual(info, actualType, type);
            return this;
        }
    }
}
