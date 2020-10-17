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
package io.prestosql.operator.aggregation.multimapagg;

import com.google.common.collect.ImmutableList;
import io.airlift.bytecode.DynamicClassLoader;
import io.prestosql.array.ObjectBigArray;
import io.prestosql.metadata.FunctionArgumentDefinition;
import io.prestosql.metadata.FunctionBinding;
import io.prestosql.metadata.FunctionMetadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.SqlAggregationFunction;
import io.prestosql.operator.aggregation.AccumulatorCompiler;
import io.prestosql.operator.aggregation.AggregationMetadata;
import io.prestosql.operator.aggregation.AggregationMetadata.AccumulatorStateDescriptor;
import io.prestosql.operator.aggregation.GenericAccumulatorFactoryBinder;
import io.prestosql.operator.aggregation.InternalAggregationFunction;
import io.prestosql.operator.aggregation.TypedSet;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.type.BlockTypeOperators;
import io.prestosql.type.BlockTypeOperators.BlockPositionEqual;
import io.prestosql.type.BlockTypeOperators.BlockPositionHashCode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.metadata.FunctionKind.AGGREGATE;
import static io.prestosql.metadata.Signature.comparableTypeParameter;
import static io.prestosql.metadata.Signature.typeVariable;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INPUT_CHANNEL;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.NULLABLE_BLOCK_INPUT_CHANNEL;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.STATE;
import static io.prestosql.operator.aggregation.AggregationUtils.generateAggregationName;
import static io.prestosql.operator.aggregation.TypedSet.createEqualityTypedSet;
import static io.prestosql.spi.type.TypeSignature.arrayType;
import static io.prestosql.spi.type.TypeSignature.mapType;
import static io.prestosql.type.TypeUtils.expectedValueSize;
import static io.prestosql.util.Reflection.methodHandle;
import static java.util.Objects.requireNonNull;

public class MultimapAggregationFunction
        extends SqlAggregationFunction
{
    public static final String NAME = "multimap_agg";
    private static final MethodHandle OUTPUT_FUNCTION = methodHandle(
            MultimapAggregationFunction.class,
            "output",
            Type.class,
            BlockPositionEqual.class,
            BlockPositionHashCode.class,
            Type.class,
            MultimapAggregationState.class,
            BlockBuilder.class);
    private static final MethodHandle COMBINE_FUNCTION = methodHandle(
            MultimapAggregationFunction.class,
            "combine",
            MultimapAggregationState.class,
            MultimapAggregationState.class);
    private static final MethodHandle INPUT_FUNCTION = methodHandle(
            MultimapAggregationFunction.class,
            "input",
            MultimapAggregationState.class,
            Block.class,
            Block.class,
            int.class);
    private static final int EXPECTED_ENTRY_SIZE = 100;
    private final MultimapAggGroupImplementation groupMode;
    private final BlockTypeOperators blockTypeOperators;

    public MultimapAggregationFunction(MultimapAggGroupImplementation groupMode, BlockTypeOperators blockTypeOperators)
    {
        super(
                new FunctionMetadata(
                        new Signature(
                                NAME,
                                ImmutableList.of(comparableTypeParameter("K"), typeVariable("V")),
                                ImmutableList.of(),
                                mapType(new TypeSignature("K"), arrayType(new TypeSignature("V"))),
                                ImmutableList.of(new TypeSignature("K"), new TypeSignature("V")),
                                false),
                        true,
                        ImmutableList.of(
                                new FunctionArgumentDefinition(false),
                                new FunctionArgumentDefinition(true)),
                        false,
                        true,
                        "Aggregates all the rows (key/value pairs) into a single multimap",
                        AGGREGATE),
                true,
                true);
        this.groupMode = requireNonNull(groupMode, "groupMode is null");
        this.blockTypeOperators = requireNonNull(blockTypeOperators, "blockTypeOperators is null");
    }

    @Override
    public List<TypeSignature> getIntermediateTypes(FunctionBinding functionBinding)
    {
        Type keyType = functionBinding.getTypeVariable("K");
        Type valueType = functionBinding.getTypeVariable("V");
        return ImmutableList.of(new MultimapAggregationStateSerializer(keyType, valueType).getSerializedType().getTypeSignature());
    }

    @Override
    public InternalAggregationFunction specialize(FunctionBinding functionBinding)
    {
        Type keyType = functionBinding.getTypeVariable("K");
        BlockPositionEqual keyEqual = blockTypeOperators.getEqualOperator(keyType);
        BlockPositionHashCode keyHashCode = blockTypeOperators.getHashCodeOperator(keyType);

        Type valueType = functionBinding.getTypeVariable("V");
        Type outputType = functionBinding.getBoundSignature().getReturnType();
        return generateAggregation(keyType, keyEqual, keyHashCode, valueType, outputType);
    }

    private InternalAggregationFunction generateAggregation(Type keyType, BlockPositionEqual keyEqual, BlockPositionHashCode keyHashCode, Type valueType, Type outputType)
    {
        DynamicClassLoader classLoader = new DynamicClassLoader(MultimapAggregationFunction.class.getClassLoader());
        List<Type> inputTypes = ImmutableList.of(keyType, valueType);
        MultimapAggregationStateSerializer stateSerializer = new MultimapAggregationStateSerializer(keyType, valueType);
        Type intermediateType = stateSerializer.getSerializedType();

        AggregationMetadata metadata = new AggregationMetadata(
                generateAggregationName(NAME, outputType.getTypeSignature(), inputTypes.stream().map(Type::getTypeSignature).collect(toImmutableList())),
                createInputParameterMetadata(keyType, valueType),
                INPUT_FUNCTION,
                Optional.empty(),
                COMBINE_FUNCTION,
                MethodHandles.insertArguments(OUTPUT_FUNCTION, 0, keyType, keyEqual, keyHashCode, valueType),
                ImmutableList.of(new AccumulatorStateDescriptor(
                        MultimapAggregationState.class,
                        stateSerializer,
                        new MultimapAggregationStateFactory(keyType, valueType, groupMode))),
                outputType);

        GenericAccumulatorFactoryBinder factory = AccumulatorCompiler.generateAccumulatorFactoryBinder(metadata, classLoader);
        return new InternalAggregationFunction(NAME, inputTypes, ImmutableList.of(intermediateType), outputType, factory);
    }

    private static List<ParameterMetadata> createInputParameterMetadata(Type keyType, Type valueType)
    {
        return ImmutableList.of(new ParameterMetadata(STATE),
                new ParameterMetadata(BLOCK_INPUT_CHANNEL, keyType),
                new ParameterMetadata(NULLABLE_BLOCK_INPUT_CHANNEL, valueType),
                new ParameterMetadata(BLOCK_INDEX));
    }

    public static void input(MultimapAggregationState state, Block key, Block value, int position)
    {
        state.add(key, value, position);
    }

    public static void combine(MultimapAggregationState state, MultimapAggregationState otherState)
    {
        state.merge(otherState);
    }

    public static void output(Type keyType, BlockPositionEqual keyEqual, BlockPositionHashCode keyHashCode, Type valueType, MultimapAggregationState state, BlockBuilder out)
    {
        if (state.isEmpty()) {
            out.appendNull();
        }
        else {
            // TODO: Avoid copy value block associated with the same key by using strategy similar to multimap_from_entries
            ObjectBigArray<BlockBuilder> valueArrayBlockBuilders = new ObjectBigArray<>();
            valueArrayBlockBuilders.ensureCapacity(state.getEntryCount());
            BlockBuilder distinctKeyBlockBuilder = keyType.createBlockBuilder(null, state.getEntryCount(), expectedValueSize(keyType, 100));
            TypedSet keySet = createEqualityTypedSet(keyType, keyEqual, keyHashCode, state.getEntryCount(), NAME);

            state.forEach((key, value, keyValueIndex) -> {
                // Merge values of the same key into an array
                if (!keySet.contains(key, keyValueIndex)) {
                    keySet.add(key, keyValueIndex);
                    keyType.appendTo(key, keyValueIndex, distinctKeyBlockBuilder);
                    BlockBuilder valueArrayBuilder = valueType.createBlockBuilder(null, 10, expectedValueSize(valueType, EXPECTED_ENTRY_SIZE));
                    valueArrayBlockBuilders.set(keySet.positionOf(key, keyValueIndex), valueArrayBuilder);
                }
                valueType.appendTo(value, keyValueIndex, valueArrayBlockBuilders.get(keySet.positionOf(key, keyValueIndex)));
            });

            // Write keys and value arrays into one Block
            Type valueArrayType = new ArrayType(valueType);
            BlockBuilder multimapBlockBuilder = out.beginBlockEntry();
            for (int i = 0; i < distinctKeyBlockBuilder.getPositionCount(); i++) {
                keyType.appendTo(distinctKeyBlockBuilder, i, multimapBlockBuilder);
                valueArrayType.writeObject(multimapBlockBuilder, valueArrayBlockBuilders.get(i).build());
            }
            out.closeEntry();
        }
    }
}
