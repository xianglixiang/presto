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
package io.prestosql.operator.aggregation;

import com.google.common.collect.ImmutableList;
import io.airlift.bytecode.DynamicClassLoader;
import io.prestosql.metadata.FunctionArgumentDefinition;
import io.prestosql.metadata.FunctionBinding;
import io.prestosql.metadata.FunctionMetadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.SqlAggregationFunction;
import io.prestosql.operator.aggregation.AggregationMetadata.AccumulatorStateDescriptor;
import io.prestosql.operator.aggregation.state.KeyValuePairStateSerializer;
import io.prestosql.operator.aggregation.state.KeyValuePairsState;
import io.prestosql.operator.aggregation.state.KeyValuePairsStateFactory;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.MapType;
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
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.STATE;
import static io.prestosql.operator.aggregation.AggregationUtils.generateAggregationName;
import static io.prestosql.spi.type.TypeSignature.mapType;
import static io.prestosql.util.Reflection.methodHandle;
import static java.util.Objects.requireNonNull;

public class MapUnionAggregation
        extends SqlAggregationFunction
{
    public static final String NAME = "map_union";
    private static final MethodHandle OUTPUT_FUNCTION = methodHandle(MapUnionAggregation.class, "output", KeyValuePairsState.class, BlockBuilder.class);
    private static final MethodHandle INPUT_FUNCTION = methodHandle(MapUnionAggregation.class,
            "input",
            Type.class,
            BlockPositionEqual.class,
            BlockPositionHashCode.class,
            Type.class,
            KeyValuePairsState.class,
            Block.class);
    private static final MethodHandle COMBINE_FUNCTION = methodHandle(MapUnionAggregation.class, "combine", KeyValuePairsState.class, KeyValuePairsState.class);

    private final BlockTypeOperators blockTypeOperators;

    public MapUnionAggregation(BlockTypeOperators blockTypeOperators)
    {
        super(
                new FunctionMetadata(
                        new Signature(
                                NAME,
                                ImmutableList.of(comparableTypeParameter("K"), typeVariable("V")),
                                ImmutableList.of(),
                                mapType(new TypeSignature("K"), new TypeSignature("V")),
                                ImmutableList.of(mapType(new TypeSignature("K"), new TypeSignature("V"))),
                                false),
                        true,
                        ImmutableList.of(new FunctionArgumentDefinition(false)),
                        false,
                        true,
                        "Aggregate all the maps into a single map",
                        AGGREGATE),
                true,
                false);
        this.blockTypeOperators = requireNonNull(blockTypeOperators, "blockTypeOperators is null");
    }

    @Override
    public List<TypeSignature> getIntermediateTypes(FunctionBinding functionBinding)
    {
        MapType outputType = (MapType) functionBinding.getBoundSignature().getReturnType();
        return ImmutableList.of(outputType.getTypeSignature());
    }

    @Override
    public InternalAggregationFunction specialize(FunctionBinding functionBinding)
    {
        Type keyType = functionBinding.getTypeVariable("K");
        BlockPositionEqual keyEqual = blockTypeOperators.getEqualOperator(keyType);
        BlockPositionHashCode keyHashCode = blockTypeOperators.getHashCodeOperator(keyType);

        Type valueType = functionBinding.getTypeVariable("V");
        MapType outputType = (MapType) functionBinding.getBoundSignature().getReturnType();
        return generateAggregation(keyType, keyEqual, keyHashCode, valueType, outputType);
    }

    private static InternalAggregationFunction generateAggregation(Type keyType, BlockPositionEqual keyEqual, BlockPositionHashCode keyHashCode, Type valueType, MapType outputType)
    {
        DynamicClassLoader classLoader = new DynamicClassLoader(MapUnionAggregation.class.getClassLoader());
        List<Type> inputTypes = ImmutableList.of(outputType);
        KeyValuePairStateSerializer stateSerializer = new KeyValuePairStateSerializer(outputType, keyEqual, keyHashCode);
        Type intermediateType = stateSerializer.getSerializedType();

        AggregationMetadata metadata = new AggregationMetadata(
                generateAggregationName(NAME, outputType.getTypeSignature(), inputTypes.stream().map(Type::getTypeSignature).collect(toImmutableList())),
                createInputParameterMetadata(outputType),
                MethodHandles.insertArguments(INPUT_FUNCTION, 0, keyType, keyEqual, keyHashCode, valueType),
                Optional.empty(),
                COMBINE_FUNCTION,
                OUTPUT_FUNCTION,
                ImmutableList.of(new AccumulatorStateDescriptor(
                        KeyValuePairsState.class,
                        stateSerializer,
                        new KeyValuePairsStateFactory(keyType, valueType))),
                outputType);

        GenericAccumulatorFactoryBinder factory = AccumulatorCompiler.generateAccumulatorFactoryBinder(metadata, classLoader);
        return new InternalAggregationFunction(NAME, inputTypes, ImmutableList.of(intermediateType), outputType, factory);
    }

    private static List<ParameterMetadata> createInputParameterMetadata(Type inputType)
    {
        return ImmutableList.of(
                new ParameterMetadata(STATE),
                new ParameterMetadata(INPUT_CHANNEL, inputType));
    }

    public static void input(
            Type keyType,
            BlockPositionEqual keyEqual,
            BlockPositionHashCode keyHashCode,
            Type valueType,
            KeyValuePairsState state,
            Block value)
    {
        KeyValuePairs pairs = state.get();
        if (pairs == null) {
            pairs = new KeyValuePairs(keyType, keyEqual, keyHashCode, valueType);
            state.set(pairs);
        }

        long startSize = pairs.estimatedInMemorySize();
        for (int i = 0; i < value.getPositionCount(); i += 2) {
            pairs.add(value, value, i, i + 1);
        }
        state.addMemoryUsage(pairs.estimatedInMemorySize() - startSize);
    }

    public static void combine(KeyValuePairsState state, KeyValuePairsState otherState)
    {
        MapAggregationFunction.combine(state, otherState);
    }

    public static void output(KeyValuePairsState state, BlockBuilder out)
    {
        MapAggregationFunction.output(state, out);
    }
}
