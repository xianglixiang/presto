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
package io.prestosql.sql.planner.assertions;

import com.google.common.collect.ImmutableSet;
import io.prestosql.Session;
import io.prestosql.cost.StatsProvider;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.DynamicFilters;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.plan.DynamicFilterId;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.JoinNode.DistributionType;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.tree.Expression;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.sql.DynamicFilters.extractDynamicFilters;
import static io.prestosql.sql.planner.ExpressionExtractor.extractExpressions;
import static io.prestosql.sql.planner.assertions.MatchResult.NO_MATCH;
import static io.prestosql.sql.planner.optimizations.PlanNodeSearcher.searchFrom;
import static java.util.Objects.requireNonNull;

final class JoinMatcher
        implements Matcher
{
    private final JoinNode.Type joinType;
    private final List<ExpectedValueProvider<JoinNode.EquiJoinClause>> equiCriteria;
    private final Optional<Expression> filter;
    private final Optional<DistributionType> distributionType;
    private final Optional<Boolean> spillable;
    // LEFT_SYMBOL -> RIGHT_SYMBOL
    private final Optional<Map<SymbolAlias, SymbolAlias>> expectedDynamicFilterAliases;

    JoinMatcher(
            JoinNode.Type joinType,
            List<ExpectedValueProvider<JoinNode.EquiJoinClause>> equiCriteria,
            Optional<Expression> filter,
            Optional<DistributionType> distributionType,
            Optional<Boolean> spillable,
            Optional<Map<SymbolAlias, SymbolAlias>> expectedDynamicFilterAliases)
    {
        this.joinType = requireNonNull(joinType, "joinType is null");
        this.equiCriteria = requireNonNull(equiCriteria, "equiCriteria is null");
        this.filter = requireNonNull(filter, "filter cannot be null");
        this.distributionType = requireNonNull(distributionType, "distributionType is null");
        this.spillable = requireNonNull(spillable, "spillable is null");
        this.expectedDynamicFilterAliases = requireNonNull(expectedDynamicFilterAliases, "expectedDynamicFilterAliases is null");
    }

    @Override
    public boolean shapeMatches(PlanNode node)
    {
        if (!(node instanceof JoinNode)) {
            return false;
        }

        JoinNode joinNode = (JoinNode) node;
        return joinNode.getType() == joinType;
    }

    @Override
    public MatchResult detailMatches(PlanNode node, StatsProvider stats, Session session, Metadata metadata, SymbolAliases symbolAliases)
    {
        checkState(shapeMatches(node), "Plan testing framework error: shapeMatches returned false in detailMatches in %s", this.getClass().getName());

        JoinNode joinNode = (JoinNode) node;

        if (joinNode.getCriteria().size() != equiCriteria.size()) {
            return NO_MATCH;
        }

        if (filter.isPresent()) {
            if (joinNode.getFilter().isEmpty()) {
                return NO_MATCH;
            }
            if (!new ExpressionVerifier(symbolAliases).process(joinNode.getFilter().get(), filter.get())) {
                return NO_MATCH;
            }
        }
        else {
            if (joinNode.getFilter().isPresent()) {
                return NO_MATCH;
            }
        }

        if (distributionType.isPresent() && !distributionType.equals(joinNode.getDistributionType())) {
            return NO_MATCH;
        }

        if (spillable.isPresent() && !spillable.equals(joinNode.isSpillable())) {
            return NO_MATCH;
        }

        /*
         * Have to use order-independent comparison; there are no guarantees what order
         * the equi criteria will have after planning and optimizing.
         */
        Set<JoinNode.EquiJoinClause> actual = ImmutableSet.copyOf(joinNode.getCriteria());
        Set<JoinNode.EquiJoinClause> expected =
                equiCriteria.stream()
                        .map(maker -> maker.getExpectedValue(symbolAliases))
                        .collect(toImmutableSet());

        if (!expected.equals(actual)) {
            return NO_MATCH;
        }

        return new MatchResult(matchDynamicFilters(joinNode, symbolAliases));
    }

    private boolean matchDynamicFilters(JoinNode joinNode, SymbolAliases symbolAliases)
    {
        if (expectedDynamicFilterAliases.isEmpty()) {
            return true;
        }
        Set<DynamicFilterId> dynamicFilterIds = joinNode.getDynamicFilters().keySet();
        Map<Symbol, DynamicFilterId> probeSymbolToFilterId = searchFrom(joinNode.getLeft())
                .where(FilterNode.class::isInstance)
                .findAll()
                .stream()
                .flatMap(filterNode -> extractExpressions(filterNode).stream())
                .flatMap(expression -> extractDynamicFilters(expression).getDynamicConjuncts().stream())
                .filter(descriptor -> dynamicFilterIds.contains(descriptor.getId()))
                .collect(toImmutableMap(filter -> Symbol.from(filter.getInput()), DynamicFilters.Descriptor::getId));

        Map<DynamicFilterId, Symbol> idToBuildSymbolMap = joinNode.getDynamicFilters();
        Map<Symbol, Symbol> actual = new HashMap<>();
        for (Map.Entry<Symbol, DynamicFilterId> entry : probeSymbolToFilterId.entrySet()) {
            Symbol probe = entry.getKey();
            DynamicFilterId id = entry.getValue();

            Symbol build = idToBuildSymbolMap.get(id);
            if (build == null) {
                return false;
            }
            actual.put(probe, build);
        }

        Map<Symbol, Symbol> expected = expectedDynamicFilterAliases.get().entrySet().stream()
                .collect(toImmutableMap(entry -> entry.getKey().toSymbol(symbolAliases), entry -> entry.getValue().toSymbol(symbolAliases)));

        return expected.equals(actual);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .omitNullValues()
                .add("type", joinType)
                .add("equiCriteria", equiCriteria)
                .add("filter", filter.orElse(null))
                .add("distributionType", distributionType)
                .add("dynamicFilter", expectedDynamicFilterAliases.map(aliases -> aliases.values().stream()
                        .collect(toImmutableMap(rightSymbol -> rightSymbol.toString() + "_alias", SymbolAlias::toString))))
                .toString();
    }
}
