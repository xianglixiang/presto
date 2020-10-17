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
package io.prestosql.execution.scheduler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import io.airlift.units.Duration;
import io.prestosql.client.NodeVersion;
import io.prestosql.connector.CatalogName;
import io.prestosql.cost.StatsAndCosts;
import io.prestosql.execution.DynamicFilterConfig;
import io.prestosql.execution.MockRemoteTaskFactory;
import io.prestosql.execution.MockRemoteTaskFactory.MockRemoteTask;
import io.prestosql.execution.NodeTaskMap;
import io.prestosql.execution.RemoteTask;
import io.prestosql.execution.SqlStageExecution;
import io.prestosql.execution.StageId;
import io.prestosql.execution.TableInfo;
import io.prestosql.execution.buffer.OutputBuffers.OutputBufferId;
import io.prestosql.failuredetector.NoOpFailureDetector;
import io.prestosql.metadata.InMemoryNodeManager;
import io.prestosql.metadata.InternalNode;
import io.prestosql.metadata.InternalNodeManager;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.server.DynamicFilterService;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.connector.ConnectorPartitionHandle;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.DynamicFilter;
import io.prestosql.spi.connector.FixedSplitSource;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.split.ConnectorAwareSplitSource;
import io.prestosql.split.SplitSource;
import io.prestosql.sql.DynamicFilters;
import io.prestosql.sql.planner.Partitioning;
import io.prestosql.sql.planner.PartitioningScheme;
import io.prestosql.sql.planner.PlanFragment;
import io.prestosql.sql.planner.StageExecutionPlan;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.plan.DynamicFilterId;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanFragmentId;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.sql.planner.plan.RemoteSourceNode;
import io.prestosql.sql.planner.plan.TableScanNode;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.testing.TestingMetadata.TestingColumnHandle;
import io.prestosql.testing.TestingSplit;
import io.prestosql.util.FinalizerService;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.execution.buffer.OutputBuffers.BufferType.PARTITIONED;
import static io.prestosql.execution.buffer.OutputBuffers.createInitialEmptyOutputBuffers;
import static io.prestosql.execution.scheduler.ScheduleResult.BlockedReason.SPLIT_QUEUES_FULL;
import static io.prestosql.execution.scheduler.SourcePartitionedScheduler.newSourcePartitionedSchedulerAsStageScheduler;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.StageExecutionDescriptor.ungroupedExecution;
import static io.prestosql.spi.StandardErrorCode.NO_NODES_AVAILABLE;
import static io.prestosql.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.DynamicFilters.createDynamicFilterExpression;
import static io.prestosql.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.SOURCE_DISTRIBUTION;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.REPLICATE;
import static io.prestosql.sql.planner.plan.JoinNode.Type.INNER;
import static io.prestosql.testing.TestingHandles.TEST_TABLE_HANDLE;
import static io.prestosql.testing.assertions.PrestoExceptionAssert.assertPrestoExceptionThrownBy;
import static java.lang.Integer.min;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestSourcePartitionedScheduler
{
    public static final OutputBufferId OUT = new OutputBufferId(0);
    private static final CatalogName CONNECTOR_ID = TEST_TABLE_HANDLE.getCatalogName();
    private static final QueryId QUERY_ID = new QueryId("query");
    private static final DynamicFilterId DYNAMIC_FILTER_ID = new DynamicFilterId("filter1");

    private final ExecutorService queryExecutor = newCachedThreadPool(daemonThreadsNamed("stageExecutor-%s"));
    private final ScheduledExecutorService scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("stageScheduledExecutor-%s"));
    private final InMemoryNodeManager nodeManager = new InMemoryNodeManager();
    private final FinalizerService finalizerService = new FinalizerService();

    public TestSourcePartitionedScheduler()
    {
        nodeManager.addNode(CONNECTOR_ID,
                new InternalNode("other1", URI.create("http://127.0.0.1:11"), NodeVersion.UNKNOWN, false),
                new InternalNode("other2", URI.create("http://127.0.0.1:12"), NodeVersion.UNKNOWN, false),
                new InternalNode("other3", URI.create("http://127.0.0.1:13"), NodeVersion.UNKNOWN, false));
    }

    @BeforeClass
    public void setUp()
    {
        finalizerService.start();
    }

    @AfterClass(alwaysRun = true)
    public void destroyExecutor()
    {
        queryExecutor.shutdownNow();
        scheduledExecutor.shutdownNow();
        finalizerService.destroy();
    }

    @Test
    public void testScheduleNoSplits()
    {
        StageExecutionPlan plan = createPlan(createFixedSplitSource(0, TestingSplit::createRemoteSplit));
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
        SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);

        StageScheduler scheduler = getSourcePartitionedScheduler(plan, stage, nodeManager, nodeTaskMap, 1);

        ScheduleResult scheduleResult = scheduler.schedule();

        assertEquals(scheduleResult.getNewTasks().size(), 1);
        assertEffectivelyFinished(scheduleResult, scheduler);

        stage.abort();
    }

    @Test
    public void testScheduleSplitsOneAtATime()
    {
        StageExecutionPlan plan = createPlan(createFixedSplitSource(60, TestingSplit::createRemoteSplit));
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
        SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);

        StageScheduler scheduler = getSourcePartitionedScheduler(plan, stage, nodeManager, nodeTaskMap, 1);

        for (int i = 0; i < 60; i++) {
            ScheduleResult scheduleResult = scheduler.schedule();

            // only finishes when last split is fetched
            if (i == 59) {
                assertEffectivelyFinished(scheduleResult, scheduler);
            }
            else {
                assertFalse(scheduleResult.isFinished());
            }

            // never blocks
            assertTrue(scheduleResult.getBlocked().isDone());

            // first three splits create new tasks
            assertEquals(scheduleResult.getNewTasks().size(), i < 3 ? 1 : 0);
            assertEquals(stage.getAllTasks().size(), i < 3 ? i + 1 : 3);

            assertPartitionedSplitCount(stage, min(i + 1, 60));
        }

        for (RemoteTask remoteTask : stage.getAllTasks()) {
            assertEquals(remoteTask.getPartitionedSplitCount(), 20);
        }

        stage.abort();
    }

    @Test
    public void testScheduleSplitsBatched()
    {
        StageExecutionPlan plan = createPlan(createFixedSplitSource(60, TestingSplit::createRemoteSplit));
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
        SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);

        StageScheduler scheduler = getSourcePartitionedScheduler(plan, stage, nodeManager, nodeTaskMap, 7);

        for (int i = 0; i <= (60 / 7); i++) {
            ScheduleResult scheduleResult = scheduler.schedule();

            // finishes when last split is fetched
            if (i == (60 / 7)) {
                assertEffectivelyFinished(scheduleResult, scheduler);
            }
            else {
                assertFalse(scheduleResult.isFinished());
            }

            // never blocks
            assertTrue(scheduleResult.getBlocked().isDone());

            // first three splits create new tasks
            assertEquals(scheduleResult.getNewTasks().size(), i == 0 ? 3 : 0);
            assertEquals(stage.getAllTasks().size(), 3);

            assertPartitionedSplitCount(stage, min((i + 1) * 7, 60));
        }

        for (RemoteTask remoteTask : stage.getAllTasks()) {
            assertEquals(remoteTask.getPartitionedSplitCount(), 20);
        }

        stage.abort();
    }

    @Test
    public void testScheduleSplitsBlock()
    {
        StageExecutionPlan plan = createPlan(createFixedSplitSource(80, TestingSplit::createRemoteSplit));
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
        SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);

        StageScheduler scheduler = getSourcePartitionedScheduler(plan, stage, nodeManager, nodeTaskMap, 1);

        // schedule first 60 splits, which will cause the scheduler to block
        for (int i = 0; i <= 60; i++) {
            ScheduleResult scheduleResult = scheduler.schedule();

            assertFalse(scheduleResult.isFinished());

            // blocks at 20 per node
            assertEquals(scheduleResult.getBlocked().isDone(), i != 60);

            // first three splits create new tasks
            assertEquals(scheduleResult.getNewTasks().size(), i < 3 ? 1 : 0);
            assertEquals(stage.getAllTasks().size(), i < 3 ? i + 1 : 3);

            assertPartitionedSplitCount(stage, min(i + 1, 60));
        }

        for (RemoteTask remoteTask : stage.getAllTasks()) {
            assertEquals(remoteTask.getPartitionedSplitCount(), 20);
        }

        // todo rewrite MockRemoteTask to fire a tate transition when splits are cleared, and then validate blocked future completes

        // drop the 20 splits from one node
        ((MockRemoteTask) stage.getAllTasks().get(0)).clearSplits();

        // schedule remaining 20 splits
        for (int i = 0; i < 20; i++) {
            ScheduleResult scheduleResult = scheduler.schedule();

            // finishes when last split is fetched
            if (i == 19) {
                assertEffectivelyFinished(scheduleResult, scheduler);
            }
            else {
                assertFalse(scheduleResult.isFinished());
            }

            // does not block again
            assertTrue(scheduleResult.getBlocked().isDone());

            // no additional tasks will be created
            assertEquals(scheduleResult.getNewTasks().size(), 0);
            assertEquals(stage.getAllTasks().size(), 3);

            // we dropped 20 splits so start at 40 and count to 60
            assertPartitionedSplitCount(stage, min(i + 41, 60));
        }

        for (RemoteTask remoteTask : stage.getAllTasks()) {
            assertEquals(remoteTask.getPartitionedSplitCount(), 20);
        }

        stage.abort();
    }

    @Test
    public void testScheduleSlowSplitSource()
    {
        QueuedSplitSource queuedSplitSource = new QueuedSplitSource(TestingSplit::createRemoteSplit);
        StageExecutionPlan plan = createPlan(queuedSplitSource);
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
        SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);

        StageScheduler scheduler = getSourcePartitionedScheduler(plan, stage, nodeManager, nodeTaskMap, 1);

        // schedule with no splits - will block
        ScheduleResult scheduleResult = scheduler.schedule();
        assertFalse(scheduleResult.isFinished());
        assertFalse(scheduleResult.getBlocked().isDone());
        assertEquals(scheduleResult.getNewTasks().size(), 0);
        assertEquals(stage.getAllTasks().size(), 0);

        queuedSplitSource.addSplits(1);
        assertTrue(scheduleResult.getBlocked().isDone());
    }

    @Test
    public void testNoNodes()
    {
        assertPrestoExceptionThrownBy(() -> {
            NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
            InMemoryNodeManager nodeManager = new InMemoryNodeManager();
            NodeScheduler nodeScheduler = new NodeScheduler(new UniformNodeSelectorFactory(nodeManager, new NodeSchedulerConfig().setIncludeCoordinator(false), nodeTaskMap));

            StageExecutionPlan plan = createPlan(createFixedSplitSource(20, TestingSplit::createRemoteSplit));
            SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);

            StageScheduler scheduler = newSourcePartitionedSchedulerAsStageScheduler(
                    stage,
                    Iterables.getOnlyElement(plan.getSplitSources().keySet()),
                    Iterables.getOnlyElement(plan.getSplitSources().values()),
                    new DynamicSplitPlacementPolicy(nodeScheduler.createNodeSelector(Optional.of(CONNECTOR_ID)), stage::getAllTasks),
                    2,
                    new DynamicFilterService(new DynamicFilterConfig()),
                    () -> false);
            scheduler.schedule();
        }).hasErrorCode(NO_NODES_AVAILABLE);
    }

    @Test
    public void testBalancedSplitAssignment()
    {
        // use private node manager so we can add a node later
        InMemoryNodeManager nodeManager = new InMemoryNodeManager();
        nodeManager.addNode(CONNECTOR_ID,
                new InternalNode("other1", URI.create("http://127.0.0.1:11"), NodeVersion.UNKNOWN, false),
                new InternalNode("other2", URI.create("http://127.0.0.1:12"), NodeVersion.UNKNOWN, false),
                new InternalNode("other3", URI.create("http://127.0.0.1:13"), NodeVersion.UNKNOWN, false));
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);

        // Schedule 15 splits - there are 3 nodes, each node should get 5 splits
        StageExecutionPlan firstPlan = createPlan(createFixedSplitSource(15, TestingSplit::createRemoteSplit));
        SqlStageExecution firstStage = createSqlStageExecution(firstPlan, nodeTaskMap);
        StageScheduler firstScheduler = getSourcePartitionedScheduler(firstPlan, firstStage, nodeManager, nodeTaskMap, 200);

        ScheduleResult scheduleResult = firstScheduler.schedule();
        assertEffectivelyFinished(scheduleResult, firstScheduler);
        assertTrue(scheduleResult.getBlocked().isDone());
        assertEquals(scheduleResult.getNewTasks().size(), 3);
        assertEquals(firstStage.getAllTasks().size(), 3);
        for (RemoteTask remoteTask : firstStage.getAllTasks()) {
            assertEquals(remoteTask.getPartitionedSplitCount(), 5);
        }

        // Add new node
        InternalNode additionalNode = new InternalNode("other4", URI.create("http://127.0.0.1:14"), NodeVersion.UNKNOWN, false);
        nodeManager.addNode(CONNECTOR_ID, additionalNode);

        // Schedule 5 splits in another query. Since the new node does not have any splits, all 5 splits are assigned to the new node
        StageExecutionPlan secondPlan = createPlan(createFixedSplitSource(5, TestingSplit::createRemoteSplit));
        SqlStageExecution secondStage = createSqlStageExecution(secondPlan, nodeTaskMap);
        StageScheduler secondScheduler = getSourcePartitionedScheduler(secondPlan, secondStage, nodeManager, nodeTaskMap, 200);

        scheduleResult = secondScheduler.schedule();
        assertEffectivelyFinished(scheduleResult, secondScheduler);
        assertTrue(scheduleResult.getBlocked().isDone());
        assertEquals(scheduleResult.getNewTasks().size(), 1);
        assertEquals(secondStage.getAllTasks().size(), 1);
        RemoteTask task = secondStage.getAllTasks().get(0);
        assertEquals(task.getPartitionedSplitCount(), 5);

        firstStage.abort();
        secondStage.abort();
    }

    @Test
    public void testNewTaskScheduledWhenChildStageBufferIsUnderutilized()
    {
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
        // use private node manager so we can add a node later
        InMemoryNodeManager nodeManager = new InMemoryNodeManager();
        nodeManager.addNode(CONNECTOR_ID,
                new InternalNode("other1", URI.create("http://127.0.0.1:11"), NodeVersion.UNKNOWN, false),
                new InternalNode("other2", URI.create("http://127.0.0.1:12"), NodeVersion.UNKNOWN, false),
                new InternalNode("other3", URI.create("http://127.0.0.1:13"), NodeVersion.UNKNOWN, false));
        NodeScheduler nodeScheduler = new NodeScheduler(new UniformNodeSelectorFactory(nodeManager, new NodeSchedulerConfig().setIncludeCoordinator(false), nodeTaskMap, new Duration(0, SECONDS)));

        StageExecutionPlan plan = createPlan(createFixedSplitSource(500, TestingSplit::createRemoteSplit));
        SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);

        // setting under utilized child output buffer
        StageScheduler scheduler = newSourcePartitionedSchedulerAsStageScheduler(
                stage,
                Iterables.getOnlyElement(plan.getSplitSources().keySet()),
                Iterables.getOnlyElement(plan.getSplitSources().values()),
                new DynamicSplitPlacementPolicy(nodeScheduler.createNodeSelector(Optional.of(CONNECTOR_ID)), stage::getAllTasks),
                500,
                new DynamicFilterService(new DynamicFilterConfig()),
                () -> false);

        // the queues of 3 running nodes should be full
        ScheduleResult scheduleResult = scheduler.schedule();
        assertEquals(scheduleResult.getBlockedReason().get(), SPLIT_QUEUES_FULL);
        assertEquals(scheduleResult.getNewTasks().size(), 3);
        assertEquals(scheduleResult.getSplitsScheduled(), 300);

        // new node added - the pending splits should go to it since the child tasks are not blocked
        nodeManager.addNode(CONNECTOR_ID, new InternalNode("other4", URI.create("http://127.0.0.4:14"), NodeVersion.UNKNOWN, false));
        scheduleResult = scheduler.schedule();
        assertEquals(scheduleResult.getBlockedReason().get(), SPLIT_QUEUES_FULL); // split queue is full but still the source task creation isn't blocked
        assertEquals(scheduleResult.getNewTasks().size(), 1);
        assertEquals(scheduleResult.getSplitsScheduled(), 100);
    }

    @Test
    public void testNoNewTaskScheduledWhenChildStageBufferIsOverutilized()
    {
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
        // use private node manager so we can add a node later
        InMemoryNodeManager nodeManager = new InMemoryNodeManager();
        nodeManager.addNode(CONNECTOR_ID,
                new InternalNode("other1", URI.create("http://127.0.0.1:11"), NodeVersion.UNKNOWN, false),
                new InternalNode("other2", URI.create("http://127.0.0.1:12"), NodeVersion.UNKNOWN, false),
                new InternalNode("other3", URI.create("http://127.0.0.1:13"), NodeVersion.UNKNOWN, false));
        NodeScheduler nodeScheduler = new NodeScheduler(new UniformNodeSelectorFactory(nodeManager, new NodeSchedulerConfig().setIncludeCoordinator(false), nodeTaskMap, new Duration(0, SECONDS)));

        StageExecutionPlan plan = createPlan(createFixedSplitSource(400, TestingSplit::createRemoteSplit));
        SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);

        // setting over utilized child output buffer
        StageScheduler scheduler = newSourcePartitionedSchedulerAsStageScheduler(
                stage,
                Iterables.getOnlyElement(plan.getSplitSources().keySet()),
                Iterables.getOnlyElement(plan.getSplitSources().values()),
                new DynamicSplitPlacementPolicy(nodeScheduler.createNodeSelector(Optional.of(CONNECTOR_ID)), stage::getAllTasks),
                400,
                new DynamicFilterService(new DynamicFilterConfig()),
                () -> true);

        // the queues of 3 running nodes should be full
        ScheduleResult scheduleResult = scheduler.schedule();
        assertEquals(scheduleResult.getBlockedReason().get(), SPLIT_QUEUES_FULL);
        assertEquals(scheduleResult.getNewTasks().size(), 3);
        assertEquals(scheduleResult.getSplitsScheduled(), 300);

        // new node added but 1 child's output buffer is overutilized - so lockdown the tasks
        nodeManager.addNode(CONNECTOR_ID, new InternalNode("other4", URI.create("http://127.0.0.4:14"), NodeVersion.UNKNOWN, false));
        scheduleResult = scheduler.schedule();
        assertEquals(scheduleResult.getBlockedReason().get(), SPLIT_QUEUES_FULL);
        assertEquals(scheduleResult.getNewTasks().size(), 0);
        assertEquals(scheduleResult.getSplitsScheduled(), 0);
    }

    @Test
    public void testDynamicFiltersUnblockedOnBlockedBuildSource()
    {
        StageExecutionPlan plan = createPlan(createBlockedSplitSource());
        NodeTaskMap nodeTaskMap = new NodeTaskMap(finalizerService);
        SqlStageExecution stage = createSqlStageExecution(plan, nodeTaskMap);
        NodeScheduler nodeScheduler = new NodeScheduler(new UniformNodeSelectorFactory(nodeManager, new NodeSchedulerConfig().setIncludeCoordinator(false), nodeTaskMap));
        DynamicFilterService dynamicFilterService = new DynamicFilterService(new DynamicFilterConfig());
        dynamicFilterService.registerQuery(
                QUERY_ID,
                ImmutableSet.of(DYNAMIC_FILTER_ID),
                ImmutableSet.of(DYNAMIC_FILTER_ID),
                ImmutableSet.of(DYNAMIC_FILTER_ID));
        StageScheduler scheduler = newSourcePartitionedSchedulerAsStageScheduler(
                stage,
                Iterables.getOnlyElement(plan.getSplitSources().keySet()),
                Iterables.getOnlyElement(plan.getSplitSources().values()),
                new DynamicSplitPlacementPolicy(nodeScheduler.createNodeSelector(Optional.of(CONNECTOR_ID)), stage::getAllTasks),
                2,
                dynamicFilterService,
                () -> true);

        SymbolReference symbolReference = new SymbolReference("DF_SYMBOL1");
        DynamicFilter dynamicFilter = dynamicFilterService.createDynamicFilter(
                QUERY_ID,
                ImmutableList.of(new DynamicFilters.Descriptor(DYNAMIC_FILTER_ID, symbolReference)),
                ImmutableMap.of(Symbol.from(symbolReference), new TestingColumnHandle("probeColumnA")));

        // make sure dynamic filter is initially blocked
        assertFalse(dynamicFilter.isBlocked().isDone());

        // make sure dynamic filter is unblocked due to build side source tasks being blocked
        ScheduleResult scheduleResult = scheduler.schedule();
        assertTrue(dynamicFilter.isBlocked().isDone());

        // make sure that an extra task for collecting dynamic filters was created
        assertEquals(scheduleResult.getNewTasks().size(), 1);
        assertEquals(scheduleResult.getSplitsScheduled(), 0);
    }

    private static void assertPartitionedSplitCount(SqlStageExecution stage, int expectedPartitionedSplitCount)
    {
        assertEquals(stage.getAllTasks().stream().mapToInt(RemoteTask::getPartitionedSplitCount).sum(), expectedPartitionedSplitCount);
    }

    private static void assertEffectivelyFinished(ScheduleResult scheduleResult, StageScheduler scheduler)
    {
        if (scheduleResult.isFinished()) {
            assertTrue(scheduleResult.getBlocked().isDone());
            return;
        }

        assertTrue(scheduleResult.getBlocked().isDone());
        ScheduleResult nextScheduleResult = scheduler.schedule();
        assertTrue(nextScheduleResult.isFinished());
        assertTrue(nextScheduleResult.getBlocked().isDone());
        assertEquals(nextScheduleResult.getNewTasks().size(), 0);
        assertEquals(nextScheduleResult.getSplitsScheduled(), 0);
    }

    private static StageScheduler getSourcePartitionedScheduler(
            StageExecutionPlan plan,
            SqlStageExecution stage,
            InternalNodeManager nodeManager,
            NodeTaskMap nodeTaskMap,
            int splitBatchSize)
    {
        NodeSchedulerConfig nodeSchedulerConfig = new NodeSchedulerConfig()
                .setIncludeCoordinator(false)
                .setMaxSplitsPerNode(20)
                .setMaxPendingSplitsPerTask(0);
        NodeScheduler nodeScheduler = new NodeScheduler(new UniformNodeSelectorFactory(nodeManager, nodeSchedulerConfig, nodeTaskMap));

        PlanNodeId sourceNode = Iterables.getOnlyElement(plan.getSplitSources().keySet());
        SplitSource splitSource = Iterables.getOnlyElement(plan.getSplitSources().values());
        SplitPlacementPolicy placementPolicy = new DynamicSplitPlacementPolicy(nodeScheduler.createNodeSelector(Optional.of(splitSource.getCatalogName())), stage::getAllTasks);
        return newSourcePartitionedSchedulerAsStageScheduler(
                stage,
                sourceNode,
                splitSource,
                placementPolicy,
                splitBatchSize,
                new DynamicFilterService(new DynamicFilterConfig()),
                () -> false);
    }

    private static StageExecutionPlan createPlan(ConnectorSplitSource splitSource)
    {
        Symbol symbol = new Symbol("column");
        Symbol buildSymbol = new Symbol("buildColumn");

        // table scan with splitCount splits
        PlanNodeId tableScanNodeId = new PlanNodeId("plan_id");
        TableScanNode tableScan = TableScanNode.newInstance(
                tableScanNodeId,
                TEST_TABLE_HANDLE,
                ImmutableList.of(symbol),
                ImmutableMap.of(symbol, new TestingColumnHandle("column")));
        FilterNode filterNode = new FilterNode(
                new PlanNodeId("filter_node_id"),
                tableScan,
                createDynamicFilterExpression(createTestMetadataManager(), DYNAMIC_FILTER_ID, VARCHAR, symbol.toSymbolReference()));

        RemoteSourceNode remote = new RemoteSourceNode(new PlanNodeId("remote_id"), new PlanFragmentId("plan_fragment_id"), ImmutableList.of(buildSymbol), Optional.empty(), REPLICATE);
        PlanFragment testFragment = new PlanFragment(
                new PlanFragmentId("plan_id"),
                new JoinNode(new PlanNodeId("join_id"),
                        INNER,
                        filterNode,
                        remote,
                        ImmutableList.of(),
                        tableScan.getOutputSymbols(),
                        remote.getOutputSymbols(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        ImmutableMap.of(DYNAMIC_FILTER_ID, buildSymbol),
                        Optional.empty()),
                ImmutableMap.of(symbol, VARCHAR),
                SOURCE_DISTRIBUTION,
                ImmutableList.of(tableScanNodeId),
                new PartitioningScheme(Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()), ImmutableList.of(symbol)),
                ungroupedExecution(),
                StatsAndCosts.empty(),
                Optional.empty());

        return new StageExecutionPlan(
                testFragment,
                ImmutableMap.of(tableScanNodeId, new ConnectorAwareSplitSource(CONNECTOR_ID, splitSource)),
                ImmutableList.of(),
                ImmutableMap.of(tableScanNodeId, new TableInfo(new QualifiedObjectName("test", "test", "test"), TupleDomain.all())));
    }

    private static ConnectorSplitSource createBlockedSplitSource()
    {
        return new ConnectorSplitSource()
        {
            @Override
            public CompletableFuture<ConnectorSplitBatch> getNextBatch(ConnectorPartitionHandle partitionHandle, int maxSize)
            {
                return new CompletableFuture<>();
            }

            @Override
            public void close()
            {
            }

            @Override
            public boolean isFinished()
            {
                return false;
            }
        };
    }

    private static ConnectorSplitSource createFixedSplitSource(int splitCount, Supplier<ConnectorSplit> splitFactory)
    {
        ImmutableList.Builder<ConnectorSplit> splits = ImmutableList.builder();

        for (int i = 0; i < splitCount; i++) {
            splits.add(splitFactory.get());
        }
        return new FixedSplitSource(splits.build());
    }

    private SqlStageExecution createSqlStageExecution(StageExecutionPlan tableScanPlan, NodeTaskMap nodeTaskMap)
    {
        StageId stageId = new StageId(QUERY_ID, 0);
        SqlStageExecution stage = SqlStageExecution.createSqlStageExecution(stageId,
                tableScanPlan.getFragment(),
                tableScanPlan.getTables(),
                new MockRemoteTaskFactory(queryExecutor, scheduledExecutor),
                TEST_SESSION,
                true,
                nodeTaskMap,
                queryExecutor,
                new NoOpFailureDetector(),
                new DynamicFilterService(new DynamicFilterConfig()),
                new SplitSchedulerStats());

        stage.setOutputBuffers(createInitialEmptyOutputBuffers(PARTITIONED)
                .withBuffer(OUT, 0)
                .withNoMoreBufferIds());

        return stage;
    }

    private static class QueuedSplitSource
            implements ConnectorSplitSource
    {
        private final Supplier<ConnectorSplit> splitFactory;
        private final LinkedBlockingQueue<ConnectorSplit> queue = new LinkedBlockingQueue<>();
        private CompletableFuture<?> notEmptyFuture = new CompletableFuture<>();
        private boolean closed;

        public QueuedSplitSource(Supplier<ConnectorSplit> splitFactory)
        {
            this.splitFactory = requireNonNull(splitFactory, "splitFactory is null");
        }

        synchronized void addSplits(int count)
        {
            if (closed) {
                return;
            }
            for (int i = 0; i < count; i++) {
                queue.add(splitFactory.get());
                notEmptyFuture.complete(null);
            }
        }

        @Override
        public CompletableFuture<ConnectorSplitBatch> getNextBatch(ConnectorPartitionHandle partitionHandle, int maxSize)
        {
            checkArgument(partitionHandle.equals(NOT_PARTITIONED), "partitionHandle must be NOT_PARTITIONED");
            return notEmptyFuture
                    .thenApply(x -> getBatch(maxSize))
                    .thenApply(splits -> new ConnectorSplitBatch(splits, isFinished()));
        }

        private synchronized List<ConnectorSplit> getBatch(int maxSize)
        {
            // take up to maxSize elements from the queue
            List<ConnectorSplit> elements = new ArrayList<>(maxSize);
            queue.drainTo(elements, maxSize);

            // if the queue is empty and the current future is finished, create a new one so
            // a new readers can be notified when the queue has elements to read
            if (queue.isEmpty() && !closed) {
                if (notEmptyFuture.isDone()) {
                    notEmptyFuture = new CompletableFuture<>();
                }
            }

            return ImmutableList.copyOf(elements);
        }

        @Override
        public synchronized boolean isFinished()
        {
            return closed && queue.isEmpty();
        }

        @Override
        public synchronized void close()
        {
            closed = true;
        }
    }
}
