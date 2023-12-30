// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.mv;

import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.KeysType;
import org.apache.doris.catalog.MaterializedIndex;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.rules.rewrite.RewriteRuleFactory;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.plans.PreAggStatus;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Select materialized index, i.e., both for rollup and materialized view when aggregate is not present.
 * <p>
 * Scan OLAP table with aggregate is handled in {@link SelectMaterializedIndexWithAggregate}.
 * <p>
 * Note that we should first apply {@link SelectMaterializedIndexWithAggregate} and then
 * {@link SelectMaterializedIndexWithoutAggregate}.
 * Besides, these two rules should run in isolated batches, thus when enter this rule, it's guaranteed that there is
 * no aggregation on top of the scan.
 */
public class SelectMaterializedIndexWithoutAggregate extends AbstractSelectMaterializedIndexRule
        implements RewriteRuleFactory {

    @Override
    public List<Rule> buildRules() {
        return ImmutableList.of(
                // project with pushdown filter.
                // Project(Filter(Scan))
                logicalProject(logicalFilter(logicalOlapScan().when(this::shouldSelectIndex)))
                        .then(project -> {
                            LogicalFilter<LogicalOlapScan> filter = project.child();
                            LogicalOlapScan scan = filter.child();
                            return project.withChildren(filter.withChildren(
                                    select(scan, project::getInputSlots, filter::getConjuncts)));
                        }).toRule(RuleType.MATERIALIZED_INDEX_PROJECT_FILTER_SCAN),

                // project with filter that cannot be pushdown.
                // Filter(Project(Scan))
                logicalFilter(logicalProject(logicalOlapScan().when(this::shouldSelectIndex)))
                        .then(filter -> {
                            LogicalProject<LogicalOlapScan> project = filter.child();
                            LogicalOlapScan scan = project.child();
                            return filter.withChildren(project.withChildren(
                                    select(scan, project::getInputSlots, ImmutableSet::of)
                            ));
                        }).toRule(RuleType.MATERIALIZED_INDEX_FILTER_PROJECT_SCAN),

                // scan with filters could be pushdown.
                // Filter(Scan)
                logicalFilter(logicalOlapScan().when(this::shouldSelectIndex))
                        .then(filter -> {
                            LogicalOlapScan scan = filter.child();
                            return filter.withChildren(select(scan, ImmutableSet::of, filter::getConjuncts));
                        })
                        .toRule(RuleType.MATERIALIZED_INDEX_FILTER_SCAN),

                // project and scan.
                // Project(Scan)
                logicalProject(logicalOlapScan().when(this::shouldSelectIndex))
                        .then(project -> {
                            LogicalOlapScan scan = project.child();
                            return project.withChildren(
                                    select(scan, project::getInputSlots, ImmutableSet::of));
                        })
                        .toRule(RuleType.MATERIALIZED_INDEX_PROJECT_SCAN),

                // only scan.
                logicalOlapScan()
                        .when(this::shouldSelectIndex)
                        .then(scan -> select(scan, scan::getOutputSet, ImmutableSet::of))
                        .toRule(RuleType.MATERIALIZED_INDEX_SCAN)
        );
    }

    /**
     * Select materialized index when aggregate node is not present.
     *
     * @param scan Scan node.
     * @param requiredScanOutputSupplier Supplier to get the required scan output.
     * @param predicatesSupplier Supplier to get pushdown predicates.
     * @return Result scan node.
     */
    private LogicalPlan select(
            LogicalOlapScan scan,
            Supplier<Set<Slot>> requiredScanOutputSupplier,
            Supplier<Set<Expression>> predicatesSupplier) {
        OlapTable table = scan.getTable();
        long baseIndexId = table.getBaseIndexId();
        KeysType keysType = scan.getTable().getKeysType();
        switch (keysType) {
            case AGG_KEYS:
            case UNIQUE_KEYS:
                break;
            case DUP_KEYS:
                if (table.getIndexIdToMeta().size() == 1) {
                    return scan.withMaterializedIndexSelected(PreAggStatus.on(), baseIndexId);
                }
                break;
            default:
                throw new RuntimeException("Not supported keys type: " + keysType);
        }
        if (scan.getTable().isDupKeysOrMergeOnWrite()) {
            // Set pre-aggregation to `on` to keep consistency with legacy logic.
            List<MaterializedIndex> candidates = scan.getTable().getVisibleIndex().stream()
                    .filter(index -> !indexHasAggregate(index, scan))
                    .filter(index -> containAllRequiredColumns(index, scan,
                            requiredScanOutputSupplier.get()))
                    .collect(Collectors.toList());
            long bestIndex = selectBestIndex(candidates, scan, predicatesSupplier.get());
            if (bestIndex == baseIndexId) {
                return scan.withMaterializedIndexSelected(PreAggStatus.on(), bestIndex);
            } else {
                return createProjectForMv(scan.withMaterializedIndexSelected(PreAggStatus.on(), bestIndex));
            }
        } else {
            final PreAggStatus preAggStatus;
            if (preAggEnabledByHint(scan)) {
                // PreAggStatus could be enabled by pre-aggregation hint for agg-keys and unique-keys.
                preAggStatus = PreAggStatus.on();
            } else {
                preAggStatus = PreAggStatus.off("No aggregate on scan.");
            }
            if (table.getIndexIdToMeta().size() == 1) {
                return scan.withMaterializedIndexSelected(preAggStatus, baseIndexId);
            }
            int baseIndexKeySize = table.getKeyColumnsByIndexId(table.getBaseIndexId()).size();
            // No aggregate on scan.
            // So only base index and indexes that have all the keys could be used.
            List<MaterializedIndex> candidates = table.getVisibleIndex().stream()
                    .filter(index -> index.getId() == baseIndexId
                            || table.getKeyColumnsByIndexId(index.getId()).size() == baseIndexKeySize)
                    .filter(index -> containAllRequiredColumns(index, scan, requiredScanOutputSupplier.get()))
                    .collect(Collectors.toList());

            if (candidates.size() == 1) {
                // `candidates` only have base index.
                return scan.withMaterializedIndexSelected(preAggStatus, baseIndexId);
            } else {
                return createProjectForMv(scan.withMaterializedIndexSelected(preAggStatus,
                        selectBestIndex(candidates, scan, predicatesSupplier.get())));
            }
        }
    }

    private boolean indexHasAggregate(MaterializedIndex index, LogicalOlapScan scan) {
        return scan.getTable().getSchemaByIndexId(index.getId())
                .stream()
                .anyMatch(Column::isAggregated);
    }

    private LogicalProject createProjectForMv(LogicalOlapScan scan) {
        Preconditions.checkArgument(scan.getSelectedIndexId() != scan.getTable().getBaseIndexId());
        List<Slot> mvSlots = scan.getOutputByMvIndex(scan.getSelectedIndexId());
        List<Slot> baseSlots = scan.getOutputByMvIndex(scan.getTable().getBaseIndexId());
        List<Alias> aliases = Lists.newArrayList();
        List<String> baseColumnNames = mvSlots.stream()
                .map(slot -> org.apache.doris.analysis.CreateMaterializedViewStmt.mvColumnBreaker(slot.getName()))
                .collect(Collectors.toList());
        boolean isMvName = org.apache.doris.analysis.CreateMaterializedViewStmt.isMVColumn(mvSlots.get(0).getName());
        for (int i = 0; i < baseColumnNames.size(); ++i) {
            for (Slot slot : baseSlots) {
                if (((SlotReference) slot).getColumn().get().getName()
                        .equals(baseColumnNames.get(i))) {
                    aliases.add(
                            new Alias(slot.getExprId(), isMvName ? mvSlots.get(i) : slot, baseColumnNames.get(i)));
                    break;
                }
            }
        }
        return new LogicalProject(aliases,
                isMvName ? scan.withOutput(scan.getOutputByMvIndex(scan.getSelectedIndexId()))
                        : scan);
    }
}