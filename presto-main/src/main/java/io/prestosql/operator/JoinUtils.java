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
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.sql.planner.optimizations.PlanNodeSearcher;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.RemoteSourceNode;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.util.MorePredicates;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.sql.planner.plan.ExchangeNode.Scope.LOCAL;
import static io.prestosql.sql.planner.plan.ExchangeNode.Scope.REMOTE;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.GATHER;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.REPARTITION;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.REPLICATE;
import static io.prestosql.util.MorePredicates.isInstanceOfAny;

/**
 * This class must be public as it is accessed via join compiler reflection.
 */
public final class JoinUtils
{
    private JoinUtils() {}

    public static List<Page> channelsToPages(List<List<Block>> channels)
    {
        if (channels.isEmpty()) {
            return ImmutableList.of();
        }

        int pagesCount = channels.get(0).size();
        ImmutableList.Builder<Page> pagesBuilder = ImmutableList.builderWithExpectedSize(pagesCount);
        for (int pageIndex = 0; pageIndex < pagesCount; ++pageIndex) {
            Block[] blocks = new Block[channels.size()];
            for (int channelIndex = 0; channelIndex < blocks.length; ++channelIndex) {
                blocks[channelIndex] = channels.get(channelIndex).get(pageIndex);
            }
            pagesBuilder.add(new Page(blocks));
        }
        return pagesBuilder.build();
    }

    public static boolean isBuildSideReplicated(PlanNode node)
    {
        checkArgument(isInstanceOfAny(JoinNode.class, SemiJoinNode.class).test(node));
        if (node instanceof JoinNode) {
            return PlanNodeSearcher.searchFrom(((JoinNode) node).getRight())
                    .recurseOnlyWhen(
                            MorePredicates.<PlanNode>isInstanceOfAny(ProjectNode.class)
                                    .or(JoinUtils::isLocalRepartitionExchange))
                    .where(joinNode -> isRemoteReplicatedExchange(joinNode) || isRemoteReplicatedSourceNode(joinNode))
                    .matches();
        }
        return PlanNodeSearcher.searchFrom(((SemiJoinNode) node).getFilteringSource())
                .recurseOnlyWhen(
                        MorePredicates.<PlanNode>isInstanceOfAny(ProjectNode.class)
                                .or(JoinUtils::isLocalGatherExchange))
                .where(joinNode -> isRemoteReplicatedExchange(joinNode) || isRemoteReplicatedSourceNode(joinNode))
                .matches();
    }

    private static boolean isRemoteReplicatedExchange(PlanNode node)
    {
        if (!(node instanceof ExchangeNode)) {
            return false;
        }

        ExchangeNode exchangeNode = (ExchangeNode) node;
        return exchangeNode.getScope() == REMOTE && exchangeNode.getType() == REPLICATE;
    }

    private static boolean isRemoteReplicatedSourceNode(PlanNode node)
    {
        if (!(node instanceof RemoteSourceNode)) {
            return false;
        }

        RemoteSourceNode remoteSourceNode = (RemoteSourceNode) node;
        return remoteSourceNode.getExchangeType() == REPLICATE;
    }

    private static boolean isLocalRepartitionExchange(PlanNode node)
    {
        if (!(node instanceof ExchangeNode)) {
            return false;
        }

        ExchangeNode exchangeNode = (ExchangeNode) node;
        return exchangeNode.getScope() == LOCAL && exchangeNode.getType() == REPARTITION;
    }

    private static boolean isLocalGatherExchange(PlanNode node)
    {
        if (!(node instanceof ExchangeNode)) {
            return false;
        }

        ExchangeNode exchangeNode = (ExchangeNode) node;
        return exchangeNode.getScope() == LOCAL && exchangeNode.getType() == GATHER;
    }
}
