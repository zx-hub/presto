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
package io.prestosql.sql.planner.optimizations;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import io.prestosql.Session;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.sql.planner.DeterminismEvaluator;
import io.prestosql.sql.planner.OrderingScheme;
import io.prestosql.sql.planner.PartitioningScheme;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.SymbolAllocator;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.ApplyNode;
import io.prestosql.sql.planner.plan.AssignUniqueId;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.CorrelatedJoinNode;
import io.prestosql.sql.planner.plan.DeleteNode;
import io.prestosql.sql.planner.plan.DistinctLimitNode;
import io.prestosql.sql.planner.plan.DynamicFilterId;
import io.prestosql.sql.planner.plan.EnforceSingleRowNode;
import io.prestosql.sql.planner.plan.ExceptNode;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.ExplainAnalyzeNode;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.GroupIdNode;
import io.prestosql.sql.planner.plan.IndexJoinNode;
import io.prestosql.sql.planner.plan.IndexSourceNode;
import io.prestosql.sql.planner.plan.IntersectNode;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.LimitNode;
import io.prestosql.sql.planner.plan.MarkDistinctNode;
import io.prestosql.sql.planner.plan.OffsetNode;
import io.prestosql.sql.planner.plan.OutputNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.PlanVisitor;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.RemoteSourceNode;
import io.prestosql.sql.planner.plan.RowNumberNode;
import io.prestosql.sql.planner.plan.SampleNode;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.sql.planner.plan.SortNode;
import io.prestosql.sql.planner.plan.SpatialJoinNode;
import io.prestosql.sql.planner.plan.StatisticsWriterNode;
import io.prestosql.sql.planner.plan.TableDeleteNode;
import io.prestosql.sql.planner.plan.TableFinishNode;
import io.prestosql.sql.planner.plan.TableScanNode;
import io.prestosql.sql.planner.plan.TableWriterNode;
import io.prestosql.sql.planner.plan.TopNNode;
import io.prestosql.sql.planner.plan.TopNRowNumberNode;
import io.prestosql.sql.planner.plan.UnionNode;
import io.prestosql.sql.planner.plan.UnnestNode;
import io.prestosql.sql.planner.plan.ValuesNode;
import io.prestosql.sql.planner.plan.WindowNode;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.SymbolReference;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.sql.planner.plan.JoinNode.Type.INNER;
import static java.util.Objects.requireNonNull;

/**
 * Re-maps symbol references that are just aliases of each other (e.g., due to projections like {@code $0 := $1})
 * <p/>
 * E.g.,
 * <p/>
 * {@code Output[$0, $1] -> Project[$0 := $2, $1 := $3 * 100] -> Aggregate[$2, $3 := sum($4)] -> ...}
 * <p/>
 * gets rewritten as
 * <p/>
 * {@code Output[$2, $1] -> Project[$2, $1 := $3 * 100] -> Aggregate[$2, $3 := sum($4)] -> ...}
 */
public class UnaliasSymbolReferences
        implements PlanOptimizer
{
    private final Metadata metadata;

    public UnaliasSymbolReferences(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");
        requireNonNull(symbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");

        return plan.accept(new Visitor(metadata), UnaliasContext.empty()).getRoot();
    }

    private static class Visitor
            extends PlanVisitor<PlanAndMappings, UnaliasContext>
    {
        private final Metadata metadata;

        public Visitor(Metadata metadata)
        {
            this.metadata = requireNonNull(metadata, "metadata is null");
        }

        @Override
        protected PlanAndMappings visitPlan(PlanNode node, UnaliasContext context)
        {
            throw new UnsupportedOperationException("Unsupported plan node " + node.getClass().getSimpleName());
        }

        @Override
        public PlanAndMappings visitAggregation(AggregationNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            AggregationNode rewrittenAggregation = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenAggregation, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitGroupId(GroupIdNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            GroupIdNode rewrittenGroupId = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenGroupId, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitExplainAnalyze(ExplainAnalyzeNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            Symbol newOutputSymbol = mapper.map(node.getOutputSymbol());

            return new PlanAndMappings(
                    new ExplainAnalyzeNode(node.getId(), rewrittenSource.getRoot(), newOutputSymbol, node.isVerbose()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitMarkDistinct(MarkDistinctNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            Symbol newMarkerSymbol = mapper.map(node.getMarkerSymbol());
            List<Symbol> newDistinctSymbols = mapper.mapAndDistinct(node.getDistinctSymbols());
            Optional<Symbol> newHashSymbol = node.getHashSymbol().map(mapper::map);

            return new PlanAndMappings(
                    new MarkDistinctNode(
                            node.getId(),
                            rewrittenSource.getRoot(),
                            newMarkerSymbol,
                            newDistinctSymbols,
                            newHashSymbol),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitUnnest(UnnestNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            List<Symbol> newReplicateSymbols = mapper.mapAndDistinct(node.getReplicateSymbols());

            ImmutableList.Builder<UnnestNode.Mapping> newMappings = ImmutableList.builder();
            for (UnnestNode.Mapping unnestMapping : node.getMappings()) {
                newMappings.add(new UnnestNode.Mapping(mapper.map(unnestMapping.getInput()), mapper.map(unnestMapping.getOutputs())));
            }

            Optional<Symbol> newOrdinalitySymbol = node.getOrdinalitySymbol().map(mapper::map);
            Optional<Expression> newFilter = node.getFilter().map(mapper::map);

            return new PlanAndMappings(
                    new UnnestNode(
                            node.getId(),
                            rewrittenSource.getRoot(),
                            newReplicateSymbols,
                            newMappings.build(),
                            newOrdinalitySymbol,
                            node.getJoinType(),
                            newFilter),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitWindow(WindowNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            WindowNode rewrittenWindow = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenWindow, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitTableScan(TableScanNode node, UnaliasContext context)
        {
            SymbolMapper mapper = new SymbolMapper(context.getCorrelationMapping());

            List<Symbol> newOutputs = mapper.map(node.getOutputSymbols());

            Map<Symbol, ColumnHandle> newAssignments = new HashMap<>();
            node.getAssignments().forEach((symbol, handle) -> {
                newAssignments.put(mapper.map(symbol), handle);
            });

            return new PlanAndMappings(
                    new TableScanNode(node.getId(), node.getTable(), newOutputs, newAssignments, node.getEnforcedConstraint()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitExchange(ExchangeNode node, UnaliasContext context)
        {
            ImmutableList.Builder<PlanNode> rewrittenChildren = ImmutableList.builder();
            ImmutableList.Builder<List<Symbol>> rewrittenInputsBuilder = ImmutableList.builder();

            // rewrite child and map corresponding input list accordingly to the child's mapping
            for (int i = 0; i < node.getSources().size(); i++) {
                PlanAndMappings rewrittenChild = node.getSources().get(i).accept(this, context);
                rewrittenChildren.add(rewrittenChild.getRoot());
                SymbolMapper mapper = new SymbolMapper(rewrittenChild.getMappings());
                rewrittenInputsBuilder.add(mapper.map(node.getInputs().get(i)));
            }
            List<List<Symbol>> rewrittenInputs = rewrittenInputsBuilder.build();

            // canonicalize ExchangeNode outputs
            SymbolMapper mapper = new SymbolMapper(context.getCorrelationMapping());
            List<Symbol> rewrittenOutputs = mapper.map(node.getOutputSymbols());

            // sanity check: assert that duplicate outputs result from same inputs
            Map<Symbol, List<Symbol>> outputsToInputs = new HashMap<>();
            for (int i = 0; i < rewrittenOutputs.size(); i++) {
                ImmutableList.Builder<Symbol> inputsBuilder = ImmutableList.builder();
                for (List<Symbol> inputs : rewrittenInputs) {
                    inputsBuilder.add(inputs.get(i));
                }
                List<Symbol> inputs = inputsBuilder.build();
                List<Symbol> previous = outputsToInputs.put(rewrittenOutputs.get(i), inputs);
                checkState(previous == null || inputs.equals(previous), "different inputs mapped to the same output symbol");
            }

            // derive new mappings for ExchangeNode output symbols
            Map<Symbol, Symbol> newMapping = new HashMap<>();

            // 1. for a single ExchangeNode source, map outputs to inputs
            if (rewrittenInputs.size() == 1) {
                for (int i = 0; i < rewrittenOutputs.size(); i++) {
                    Symbol output = rewrittenOutputs.get(i);
                    Symbol input = rewrittenInputs.get(0).get(i);
                    if (!output.equals(input)) {
                        newMapping.put(output, input);
                    }
                }
            }

            // 2. for multiple ExchangeNode sources, if different output symbols result from the same lists of canonical input symbols, map all those outputs to the same symbol
            Map<List<Symbol>, Symbol> inputsToOutputs = new HashMap<>();
            for (int i = 0; i < rewrittenOutputs.size(); i++) {
                ImmutableList.Builder<Symbol> inputsBuilder = ImmutableList.builder();
                for (List<Symbol> inputs : rewrittenInputs) {
                    inputsBuilder.add(inputs.get(i));
                }
                List<Symbol> inputs = inputsBuilder.build();
                Symbol previous = inputsToOutputs.get(inputs);
                if (previous == null || rewrittenOutputs.get(i).equals(previous)) {
                    inputsToOutputs.put(inputs, rewrittenOutputs.get(i));
                }
                else {
                    newMapping.put(rewrittenOutputs.get(i), previous);
                }
            }

            Map<Symbol, Symbol> outputMapping = new HashMap<>();
            outputMapping.putAll(mapper.getMapping());
            outputMapping.putAll(newMapping);

            mapper = new SymbolMapper(outputMapping);

            // deduplicate outputs and prune input symbols lists accordingly
            List<List<Symbol>> newInputs = new ArrayList<>();
            for (int i = 0; i < node.getInputs().size(); i++) {
                newInputs.add(new ArrayList<>());
            }
            ImmutableList.Builder<Symbol> newOutputs = ImmutableList.builder();
            Set<Symbol> addedOutputs = new HashSet<>();
            for (int i = 0; i < rewrittenOutputs.size(); i++) {
                Symbol output = mapper.map(rewrittenOutputs.get(i));
                if (addedOutputs.add(output)) {
                    newOutputs.add(output);
                    for (int j = 0; j < rewrittenInputs.size(); j++) {
                        newInputs.get(j).add(rewrittenInputs.get(j).get(i));
                    }
                }
            }

            // rewrite PartitioningScheme
            PartitioningScheme newPartitioningScheme = mapper.map(node.getPartitioningScheme(), newOutputs.build());

            // rewrite OrderingScheme
            Optional<OrderingScheme> newOrderingScheme = node.getOrderingScheme().map(mapper::map);

            return new PlanAndMappings(
                    new ExchangeNode(
                            node.getId(),
                            node.getType(),
                            node.getScope(),
                            newPartitioningScheme,
                            rewrittenChildren.build(),
                            newInputs,
                            newOrderingScheme),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitRemoteSource(RemoteSourceNode node, UnaliasContext context)
        {
            SymbolMapper mapper = new SymbolMapper(context.getCorrelationMapping());

            List<Symbol> newOutputs = mapper.mapAndDistinct(node.getOutputSymbols());
            Optional<OrderingScheme> newOrderingScheme = node.getOrderingScheme().map(mapper::map);

            return new PlanAndMappings(
                    new RemoteSourceNode(
                            node.getId(),
                            node.getSourceFragmentIds(),
                            newOutputs,
                            newOrderingScheme,
                            node.getExchangeType()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitOffset(OffsetNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);

            return new PlanAndMappings(
                    node.replaceChildren(ImmutableList.of(rewrittenSource.getRoot())),
                    rewrittenSource.getMappings());
        }

        @Override
        public PlanAndMappings visitLimit(LimitNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            LimitNode rewrittenLimit = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenLimit, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitDistinctLimit(DistinctLimitNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            DistinctLimitNode rewrittenDistinctLimit = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenDistinctLimit, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitSample(SampleNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);

            return new PlanAndMappings(
                    node.replaceChildren(ImmutableList.of(rewrittenSource.getRoot())),
                    rewrittenSource.getMappings());
        }

        @Override
        public PlanAndMappings visitValues(ValuesNode node, UnaliasContext context)
        {
            SymbolMapper mapper = new SymbolMapper(context.getCorrelationMapping());

            List<List<Expression>> newRows = node.getRows().stream()
                    .map(row -> row.stream()
                            .map(mapper::map)
                            .collect(toImmutableList()))
                    .collect(toImmutableList());

            List<Symbol> newOutputSymbols = mapper.mapAndDistinct(node.getOutputSymbols());
            checkState(node.getOutputSymbols().size() == newOutputSymbols.size(), "Values output symbols were pruned");

            return new PlanAndMappings(
                    new ValuesNode(node.getId(), newOutputSymbols, newRows),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitTableDelete(TableDeleteNode node, UnaliasContext context)
        {
            SymbolMapper mapper = new SymbolMapper(context.getCorrelationMapping());

            Symbol newOutput = mapper.map(node.getOutput());

            return new PlanAndMappings(
                    new TableDeleteNode(node.getId(), node.getTarget(), newOutput),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitDelete(DeleteNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            Symbol newRowId = mapper.map(node.getRowId());
            List<Symbol> newOutputs = mapper.map(node.getOutputSymbols());

            return new PlanAndMappings(
                    new DeleteNode(
                            node.getId(),
                            rewrittenSource.getRoot(),
                            node.getTarget(),
                            newRowId,
                            newOutputs),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitStatisticsWriterNode(StatisticsWriterNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            StatisticsWriterNode rewrittenStatisticsWriter = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenStatisticsWriter, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitTableWriter(TableWriterNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            TableWriterNode rewrittenTableWriter = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenTableWriter, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitTableFinish(TableFinishNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            TableFinishNode rewrittenTableFinish = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenTableFinish, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitRowNumber(RowNumberNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            RowNumberNode rewrittenRowNumber = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenRowNumber, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitTopNRowNumber(TopNRowNumberNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            TopNRowNumberNode rewrittenTopNRowNumber = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenTopNRowNumber, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitTopN(TopNNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            TopNNode rewrittenTopN = mapper.map(node, rewrittenSource.getRoot());

            return new PlanAndMappings(rewrittenTopN, mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitSort(SortNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            OrderingScheme newOrderingScheme = mapper.map(node.getOrderingScheme());

            return new PlanAndMappings(
                    new SortNode(node.getId(), rewrittenSource.getRoot(), newOrderingScheme, node.isPartial()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitFilter(FilterNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            Expression newPredicate = mapper.map(node.getPredicate());

            return new PlanAndMappings(
                    new FilterNode(node.getId(), rewrittenSource.getRoot(), newPredicate),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitProject(ProjectNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            // canonicalize ProjectNode assignments
            ImmutableList.Builder<Map.Entry<Symbol, Expression>> rewrittenAssignments = ImmutableList.builder();
            for (Map.Entry<Symbol, Expression> assignment : node.getAssignments().entrySet()) {
                rewrittenAssignments.add(new SimpleEntry<>(mapper.map(assignment.getKey()), mapper.map(assignment.getValue())));
            }

            // deduplicate assignments
            Map<Symbol, Expression> deduplicateAssignments = rewrittenAssignments.build().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (previous, current) -> {
                        checkState(previous.equals(current), "different expressions projected to the same symbol");
                        return previous;
                    }));

            // derive new mappings for ProjectNode output symbols
            Map<Symbol, Symbol> newMapping = mappingFromAssignments(deduplicateAssignments);

            Map<Symbol, Symbol> outputMapping = new HashMap<>();
            outputMapping.putAll(mapper.getMapping());
            outputMapping.putAll(newMapping);

            mapper = new SymbolMapper(outputMapping);

            // build new Assignments with canonical outputs
            // duplicate entries will be removed by the Builder
            Assignments.Builder newAssignments = Assignments.builder();
            for (Map.Entry<Symbol, Expression> assignment : deduplicateAssignments.entrySet()) {
                newAssignments.put(mapper.map(assignment.getKey()), assignment.getValue());
            }

            return new PlanAndMappings(
                    new ProjectNode(node.getId(), rewrittenSource.getRoot(), newAssignments.build()),
                    mapper.getMapping());
        }

        private Map<Symbol, Symbol> mappingFromAssignments(Map<Symbol, Expression> assignments)
        {
            Map<Symbol, Symbol> newMapping = new HashMap<>();
            Map<Expression, Symbol> inputsToOutputs = new HashMap<>();
            for (Map.Entry<Symbol, Expression> assignment : assignments.entrySet()) {
                Expression expression = assignment.getValue();
                // 1. for trivial symbol projection, map output symbol to input symbol
                if (expression instanceof SymbolReference) {
                    Symbol value = Symbol.from(expression);
                    if (!assignment.getKey().equals(value)) {
                        newMapping.put(assignment.getKey(), value);
                    }
                }
                // 2. map same deterministic expressions within a projection into the same symbol
                // omit NullLiterals since those have ambiguous types
                else if (DeterminismEvaluator.isDeterministic(expression, metadata) && !(expression instanceof NullLiteral)) {
                    Symbol previous = inputsToOutputs.get(expression);
                    if (previous == null) {
                        inputsToOutputs.put(expression, assignment.getKey());
                    }
                    else {
                        newMapping.put(assignment.getKey(), previous);
                    }
                }
            }
            return newMapping;
        }

        @Override
        public PlanAndMappings visitOutput(OutputNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            List<Symbol> newOutputs = mapper.map(node.getOutputSymbols());

            return new PlanAndMappings(
                    new OutputNode(node.getId(), rewrittenSource.getRoot(), node.getColumnNames(), newOutputs),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitEnforceSingleRow(EnforceSingleRowNode node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);

            return new PlanAndMappings(
                    node.replaceChildren(ImmutableList.of(rewrittenSource.getRoot())),
                    rewrittenSource.getMappings());
        }

        @Override
        public PlanAndMappings visitAssignUniqueId(AssignUniqueId node, UnaliasContext context)
        {
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenSource.getMappings());

            Symbol newUnique = mapper.map(node.getIdColumn());

            return new PlanAndMappings(
                    new AssignUniqueId(node.getId(), rewrittenSource.getRoot(), newUnique),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitApply(ApplyNode node, UnaliasContext context)
        {
            // it is assumed that apart from correlation (and possibly outer correlation), symbols are distinct between Input and Subquery
            // rewrite Input
            PlanAndMappings rewrittenInput = node.getInput().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenInput.getMappings());

            // rewrite correlation with mapping from Input
            List<Symbol> rewrittenCorrelation = mapper.mapAndDistinct(node.getCorrelation());

            // extract new mappings for correlation symbols to apply in Subquery
            Set<Symbol> correlationSymbols = ImmutableSet.copyOf(node.getCorrelation());
            Map<Symbol, Symbol> correlationMapping = mapper.getMapping().entrySet().stream()
                    .filter(mapping -> correlationSymbols.contains(mapping.getKey()))
                    .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));

            Map<Symbol, Symbol> mappingForSubquery = new HashMap<>();
            mappingForSubquery.putAll(context.getCorrelationMapping());
            mappingForSubquery.putAll(correlationMapping);

            // rewrite Subquery
            PlanAndMappings rewrittenSubquery = node.getSubquery().accept(this, new UnaliasContext(mappingForSubquery));

            // unify mappings from Input and Subquery to rewrite Subquery assignments
            Map<Symbol, Symbol> resultMapping = new HashMap<>();
            resultMapping.putAll(rewrittenInput.getMappings());
            resultMapping.putAll(rewrittenSubquery.getMappings());
            mapper = new SymbolMapper(resultMapping);

            ImmutableList.Builder<Map.Entry<Symbol, Expression>> rewrittenAssignments = ImmutableList.builder();
            for (Map.Entry<Symbol, Expression> assignment : node.getSubqueryAssignments().entrySet()) {
                rewrittenAssignments.add(new SimpleEntry<>(mapper.map(assignment.getKey()), mapper.map(assignment.getValue())));
            }

            // deduplicate assignments
            Map<Symbol, Expression> deduplicateAssignments = rewrittenAssignments.build().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (previous, current) -> {
                        checkState(previous.equals(current), "different expressions assigned to the same symbol");
                        return previous;
                    }));

            // derive new mappings for Subquery assignments outputs
            Map<Symbol, Symbol> newMapping = mappingFromAssignments(deduplicateAssignments);

            Map<Symbol, Symbol> assignmentsOutputMapping = new HashMap<>();
            assignmentsOutputMapping.putAll(mapper.getMapping());
            assignmentsOutputMapping.putAll(newMapping);

            mapper = new SymbolMapper(assignmentsOutputMapping);

            // build new Assignments with canonical outputs
            // duplicate entries will be removed by the Builder
            Assignments.Builder newAssignments = Assignments.builder();
            for (Map.Entry<Symbol, Expression> assignment : deduplicateAssignments.entrySet()) {
                newAssignments.put(mapper.map(assignment.getKey()), assignment.getValue());
            }

            return new PlanAndMappings(
                    new ApplyNode(node.getId(), rewrittenInput.getRoot(), rewrittenSubquery.getRoot(), newAssignments.build(), rewrittenCorrelation, node.getOriginSubquery()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitCorrelatedJoin(CorrelatedJoinNode node, UnaliasContext context)
        {
            // it is assumed that apart from correlation (and possibly outer correlation), symbols are distinct between left and right CorrelatedJoin source
            // rewrite Input
            PlanAndMappings rewrittenInput = node.getInput().accept(this, context);
            SymbolMapper mapper = new SymbolMapper(rewrittenInput.getMappings());

            // rewrite correlation with mapping from Input
            List<Symbol> rewrittenCorrelation = mapper.mapAndDistinct(node.getCorrelation());

            // extract new mappings for correlation symbols to apply in Subquery
            Set<Symbol> correlationSymbols = ImmutableSet.copyOf(node.getCorrelation());
            Map<Symbol, Symbol> correlationMapping = mapper.getMapping().entrySet().stream()
                    .filter(mapping -> correlationSymbols.contains(mapping.getKey()))
                    .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));

            Map<Symbol, Symbol> mappingForSubquery = new HashMap<>();
            mappingForSubquery.putAll(context.getCorrelationMapping());
            mappingForSubquery.putAll(correlationMapping);

            // rewrite Subquery
            PlanAndMappings rewrittenSubquery = node.getSubquery().accept(this, new UnaliasContext(mappingForSubquery));

            // unify mappings from Input and Subquery
            Map<Symbol, Symbol> resultMapping = new HashMap<>();
            resultMapping.putAll(rewrittenInput.getMappings());
            resultMapping.putAll(rewrittenSubquery.getMappings());

            // rewrite filter with unified mapping
            mapper = new SymbolMapper(resultMapping);
            Expression newFilter = mapper.map(node.getFilter());

            return new PlanAndMappings(
                    new CorrelatedJoinNode(node.getId(), rewrittenInput.getRoot(), rewrittenSubquery.getRoot(), rewrittenCorrelation, node.getType(), newFilter, node.getOriginSubquery()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitJoin(JoinNode node, UnaliasContext context)
        {
            // it is assumed that symbols are distinct between left and right join source. Only symbols from outer correlation might be the exception
            PlanAndMappings rewrittenLeft = node.getLeft().accept(this, context);
            PlanAndMappings rewrittenRight = node.getRight().accept(this, context);

            // unify mappings from left and right join source
            Map<Symbol, Symbol> unifiedMapping = new HashMap<>();
            unifiedMapping.putAll(rewrittenLeft.getMappings());
            unifiedMapping.putAll(rewrittenRight.getMappings());

            SymbolMapper mapper = new SymbolMapper(unifiedMapping);

            ImmutableList.Builder<JoinNode.EquiJoinClause> builder = ImmutableList.builder();
            for (JoinNode.EquiJoinClause clause : node.getCriteria()) {
                builder.add(new JoinNode.EquiJoinClause(mapper.map(clause.getLeft()), mapper.map(clause.getRight())));
            }
            List<JoinNode.EquiJoinClause> newCriteria = builder.build();

            Optional<Expression> newFilter = node.getFilter().map(mapper::map);
            Optional<Symbol> newLeftHashSymbol = node.getLeftHashSymbol().map(mapper::map);
            Optional<Symbol> newRightHashSymbol = node.getRightHashSymbol().map(mapper::map);

            // rewrite dynamic filters
            Set<Symbol> added = new HashSet<>();
            ImmutableMap.Builder<DynamicFilterId, Symbol> filtersBuilder = ImmutableMap.builder();
            for (Map.Entry<DynamicFilterId, Symbol> entry : node.getDynamicFilters().entrySet()) {
                Symbol canonical = mapper.map(entry.getValue());
                if (added.add(canonical)) {
                    filtersBuilder.put(entry.getKey(), canonical);
                }
            }
            Map<DynamicFilterId, Symbol> newDynamicFilters = filtersBuilder.build();

            // derive new mappings from inner join equi criteria
            Map<Symbol, Symbol> newMapping = new HashMap<>();
            if (node.getType() == INNER) {
                newCriteria.stream()
                        // Map right equi-condition symbol to left symbol. This helps to
                        // reuse join node partitioning better as partitioning properties are
                        // only derived from probe side symbols
                        .forEach(clause -> newMapping.put(clause.getRight(), clause.getLeft()));
            }

            Map<Symbol, Symbol> outputMapping = new HashMap<>();
            outputMapping.putAll(mapper.getMapping());
            outputMapping.putAll(newMapping);

            mapper = new SymbolMapper(outputMapping);
            List<Symbol> canonicalOutputs = mapper.mapAndDistinct(node.getOutputSymbols());
            List<Symbol> newLeftOutputSymbols = canonicalOutputs.stream()
                    .filter(rewrittenLeft.getRoot().getOutputSymbols()::contains)
                    .collect(toImmutableList());
            List<Symbol> newRightOutputSymbols = canonicalOutputs.stream()
                    .filter(rewrittenRight.getRoot().getOutputSymbols()::contains)
                    .collect(toImmutableList());

            return new PlanAndMappings(
                    new JoinNode(
                            node.getId(),
                            node.getType(),
                            rewrittenLeft.getRoot(),
                            rewrittenRight.getRoot(),
                            newCriteria,
                            newLeftOutputSymbols,
                            newRightOutputSymbols,
                            newFilter,
                            newLeftHashSymbol,
                            newRightHashSymbol,
                            node.getDistributionType(),
                            node.isSpillable(),
                            newDynamicFilters,
                            node.getReorderJoinStatsAndCost()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitSemiJoin(SemiJoinNode node, UnaliasContext context)
        {
            // it is assumed that symbols are distinct between SemiJoin source and filtering source. Only symbols from outer correlation might be the exception
            PlanAndMappings rewrittenSource = node.getSource().accept(this, context);
            PlanAndMappings rewrittenFilteringSource = node.getFilteringSource().accept(this, context);

            Map<Symbol, Symbol> outputMapping = new HashMap<>();
            outputMapping.putAll(rewrittenSource.getMappings());
            outputMapping.putAll(rewrittenFilteringSource.getMappings());

            SymbolMapper mapper = new SymbolMapper(outputMapping);

            Symbol newSourceJoinSymbol = mapper.map(node.getSourceJoinSymbol());
            Symbol newFilteringSourceJoinSymbol = mapper.map(node.getFilteringSourceJoinSymbol());
            Symbol newSemiJoinOutput = mapper.map(node.getSemiJoinOutput());
            Optional<Symbol> newSourceHashSymbol = node.getSourceHashSymbol().map(mapper::map);
            Optional<Symbol> newFilteringSourceHashSymbol = node.getFilteringSourceHashSymbol().map(mapper::map);

            return new PlanAndMappings(
                    new SemiJoinNode(
                            node.getId(),
                            rewrittenSource.getRoot(),
                            rewrittenFilteringSource.getRoot(),
                            newSourceJoinSymbol,
                            newFilteringSourceJoinSymbol,
                            newSemiJoinOutput,
                            newSourceHashSymbol,
                            newFilteringSourceHashSymbol,
                            node.getDistributionType()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitSpatialJoin(SpatialJoinNode node, UnaliasContext context)
        {
            // it is assumed that symbols are distinct between left and right SpatialJoin source. Only symbols from outer correlation might be the exception
            PlanAndMappings rewrittenLeft = node.getLeft().accept(this, context);
            PlanAndMappings rewrittenRight = node.getRight().accept(this, context);

            Map<Symbol, Symbol> outputMapping = new HashMap<>();
            outputMapping.putAll(rewrittenLeft.getMappings());
            outputMapping.putAll(rewrittenRight.getMappings());

            SymbolMapper mapper = new SymbolMapper(outputMapping);

            List<Symbol> newOutputSymbols = mapper.mapAndDistinct(node.getOutputSymbols());
            Expression newFilter = mapper.map(node.getFilter());
            Optional<Symbol> newLeftPartitionSymbol = node.getLeftPartitionSymbol().map(mapper::map);
            Optional<Symbol> newRightPartitionSymbol = node.getRightPartitionSymbol().map(mapper::map);

            return new PlanAndMappings(
                    new SpatialJoinNode(node.getId(), node.getType(), rewrittenLeft.getRoot(), rewrittenRight.getRoot(), newOutputSymbols, newFilter, newLeftPartitionSymbol, newRightPartitionSymbol, node.getKdbTree()),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitIndexJoin(IndexJoinNode node, UnaliasContext context)
        {
            // it is assumed that symbols are distinct between probeSource and indexSource. Only symbols from outer correlation might be the exception
            PlanAndMappings rewrittenProbe = node.getProbeSource().accept(this, context);
            PlanAndMappings rewrittenIndex = node.getIndexSource().accept(this, context);

            Map<Symbol, Symbol> outputMapping = new HashMap<>();
            outputMapping.putAll(rewrittenProbe.getMappings());
            outputMapping.putAll(rewrittenIndex.getMappings());

            SymbolMapper mapper = new SymbolMapper(outputMapping);

            // canonicalize index join criteria
            ImmutableList.Builder<IndexJoinNode.EquiJoinClause> builder = ImmutableList.builder();
            for (IndexJoinNode.EquiJoinClause clause : node.getCriteria()) {
                builder.add(new IndexJoinNode.EquiJoinClause(mapper.map(clause.getProbe()), mapper.map(clause.getIndex())));
            }
            List<IndexJoinNode.EquiJoinClause> newEquiCriteria = builder.build();

            Optional<Symbol> newProbeHashSymbol = node.getProbeHashSymbol().map(mapper::map);
            Optional<Symbol> newIndexHashSymbol = node.getIndexHashSymbol().map(mapper::map);

            return new PlanAndMappings(
                    new IndexJoinNode(node.getId(), node.getType(), rewrittenProbe.getRoot(), rewrittenIndex.getRoot(), newEquiCriteria, newProbeHashSymbol, newIndexHashSymbol),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitIndexSource(IndexSourceNode node, UnaliasContext context)
        {
            SymbolMapper mapper = new SymbolMapper(context.getCorrelationMapping());

            Set<Symbol> newLookupSymbols = node.getLookupSymbols().stream()
                    .map(mapper::map)
                    .collect(toImmutableSet());
            List<Symbol> newOutputSymbols = mapper.mapAndDistinct(node.getOutputSymbols());

            Map<Symbol, ColumnHandle> newAssignments = new HashMap<>();
            node.getAssignments().entrySet().stream()
                    .forEach(assignment -> newAssignments.put(mapper.map(assignment.getKey()), assignment.getValue()));

            return new PlanAndMappings(
                    new IndexSourceNode(node.getId(), node.getIndexHandle(), node.getTableHandle(), newLookupSymbols, newOutputSymbols, newAssignments),
                    mapper.getMapping());
        }

        @Override
        public PlanAndMappings visitUnion(UnionNode node, UnaliasContext context)
        {
            List<PlanAndMappings> rewrittenSources = node.getSources().stream()
                    .map(source -> source.accept(this, context))
                    .collect(toImmutableList());

            List<SymbolMapper> inputMappers = rewrittenSources.stream()
                    .map(source -> new SymbolMapper(source.getMappings()))
                    .collect(toImmutableList());

            SymbolMapper outputMapper = new SymbolMapper(context.getCorrelationMapping());

            ListMultimap<Symbol, Symbol> newOutputToInputs = rewriteOutputToInputsMap(node.getSymbolMapping(), outputMapper, inputMappers);
            List<Symbol> newOutputs = outputMapper.mapAndDistinct(node.getOutputSymbols());

            return new PlanAndMappings(
                    new UnionNode(
                            node.getId(),
                            rewrittenSources.stream()
                                    .map(PlanAndMappings::getRoot)
                                    .collect(toImmutableList()),
                            newOutputToInputs,
                            newOutputs),
                    outputMapper.getMapping());
        }

        @Override
        public PlanAndMappings visitIntersect(IntersectNode node, UnaliasContext context)
        {
            List<PlanAndMappings> rewrittenSources = node.getSources().stream()
                    .map(source -> source.accept(this, context))
                    .collect(toImmutableList());

            List<SymbolMapper> inputMappers = rewrittenSources.stream()
                    .map(source -> new SymbolMapper(source.getMappings()))
                    .collect(toImmutableList());

            SymbolMapper outputMapper = new SymbolMapper(context.getCorrelationMapping());

            ListMultimap<Symbol, Symbol> newOutputToInputs = rewriteOutputToInputsMap(node.getSymbolMapping(), outputMapper, inputMappers);
            List<Symbol> newOutputs = outputMapper.mapAndDistinct(node.getOutputSymbols());

            return new PlanAndMappings(
                    new IntersectNode(
                            node.getId(),
                            rewrittenSources.stream()
                                    .map(PlanAndMappings::getRoot)
                                    .collect(toImmutableList()),
                            newOutputToInputs,
                            newOutputs),
                    outputMapper.getMapping());
        }

        @Override
        public PlanAndMappings visitExcept(ExceptNode node, UnaliasContext context)
        {
            List<PlanAndMappings> rewrittenSources = node.getSources().stream()
                    .map(source -> source.accept(this, context))
                    .collect(toImmutableList());

            List<SymbolMapper> inputMappers = rewrittenSources.stream()
                    .map(source -> new SymbolMapper(source.getMappings()))
                    .collect(toImmutableList());

            SymbolMapper outputMapper = new SymbolMapper(context.getCorrelationMapping());

            ListMultimap<Symbol, Symbol> newOutputToInputs = rewriteOutputToInputsMap(node.getSymbolMapping(), outputMapper, inputMappers);
            List<Symbol> newOutputs = outputMapper.mapAndDistinct(node.getOutputSymbols());

            return new PlanAndMappings(
                    new ExceptNode(
                            node.getId(),
                            rewrittenSources.stream()
                                    .map(PlanAndMappings::getRoot)
                                    .collect(toImmutableList()),
                            newOutputToInputs,
                            newOutputs),
                    outputMapper.getMapping());
        }

        private ListMultimap<Symbol, Symbol> rewriteOutputToInputsMap(ListMultimap<Symbol, Symbol> oldMapping, SymbolMapper outputMapper, List<SymbolMapper> inputMappers)
        {
            ImmutableListMultimap.Builder<Symbol, Symbol> newMappingBuilder = ImmutableListMultimap.builder();
            Set<Symbol> addedSymbols = new HashSet<>();
            for (Map.Entry<Symbol, Collection<Symbol>> entry : oldMapping.asMap().entrySet()) {
                Symbol rewrittenOutput = outputMapper.map(entry.getKey());
                if (addedSymbols.add(rewrittenOutput)) {
                    List<Symbol> inputs = ImmutableList.copyOf(entry.getValue());
                    ImmutableList.Builder<Symbol> rewrittenInputs = ImmutableList.builder();
                    for (int i = 0; i < inputs.size(); i++) {
                        rewrittenInputs.add(inputMappers.get(i).map(inputs.get(i)));
                    }
                    newMappingBuilder.putAll(rewrittenOutput, rewrittenInputs.build());
                }
            }
            return newMappingBuilder.build();
        }
    }

    private static class UnaliasContext
    {
        // Correlation mapping is a record of how correlation symbols have been mapped in the subplan which provides them.
        // All occurrences of correlation symbols within the correlated subquery must be remapped accordingly.
        // In case of nested correlation, correlationMappings has required mappings for correlation symbols from all levels of nesting.
        private final Map<Symbol, Symbol> correlationMapping;

        public UnaliasContext(Map<Symbol, Symbol> correlationMapping)
        {
            this.correlationMapping = requireNonNull(correlationMapping, "correlationMapping is null");
        }

        public static UnaliasContext empty()
        {
            return new UnaliasContext(ImmutableMap.of());
        }

        public Map<Symbol, Symbol> getCorrelationMapping()
        {
            return correlationMapping;
        }
    }

    private static class PlanAndMappings
    {
        private final PlanNode root;
        private final Map<Symbol, Symbol> mappings;

        public PlanAndMappings(PlanNode root, Map<Symbol, Symbol> mappings)
        {
            this.root = requireNonNull(root, "root is null");
            this.mappings = ImmutableMap.copyOf(requireNonNull(mappings, "mappings is null"));
        }

        public PlanNode getRoot()
        {
            return root;
        }

        public Map<Symbol, Symbol> getMappings()
        {
            return mappings;
        }
    }
}
