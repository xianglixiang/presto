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
package io.prestosql.operator.scalar;

import com.google.common.collect.ImmutableList;
import io.prestosql.annotation.UsedByGeneratedCode;
import io.prestosql.metadata.FunctionArgumentDefinition;
import io.prestosql.metadata.FunctionBinding;
import io.prestosql.metadata.FunctionMetadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.operator.aggregation.TypedSet;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.sql.gen.VarArgsToArrayAdapterGenerator.MethodHandleAndConstructor;
import io.prestosql.type.BlockTypeOperators;
import io.prestosql.type.BlockTypeOperators.BlockPositionEqual;
import io.prestosql.type.BlockTypeOperators.BlockPositionHashCode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Optional;

import static io.prestosql.metadata.FunctionKind.SCALAR;
import static io.prestosql.metadata.Signature.typeVariable;
import static io.prestosql.operator.aggregation.TypedSet.createEqualityTypedSet;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.prestosql.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.prestosql.spi.type.TypeSignature.mapType;
import static io.prestosql.sql.gen.VarArgsToArrayAdapterGenerator.generateVarArgsToArrayAdapter;
import static io.prestosql.util.Reflection.methodHandle;
import static java.lang.Math.min;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;

public final class MapConcatFunction
        extends SqlScalarFunction
{
    private static final String FUNCTION_NAME = "map_concat";
    private static final String DESCRIPTION = "Concatenates given maps";

    private static final MethodHandle USER_STATE_FACTORY = methodHandle(MapConcatFunction.class, "createMapState", MapType.class);
    private static final MethodHandle METHOD_HANDLE = methodHandle(
            MapConcatFunction.class,
            "mapConcat",
            MapType.class,
            BlockPositionEqual.class,
            BlockPositionHashCode.class,
            Object.class,
            Block[].class);

    private final BlockTypeOperators blockTypeOperators;

    public MapConcatFunction(BlockTypeOperators blockTypeOperators)
    {
        super(new FunctionMetadata(
                new Signature(
                        FUNCTION_NAME,
                        ImmutableList.of(typeVariable("K"), typeVariable("V")),
                        ImmutableList.of(),
                        mapType(new TypeSignature("K"), new TypeSignature("V")),
                        ImmutableList.of(mapType(new TypeSignature("K"), new TypeSignature("V"))),
                        true),
                false,
                ImmutableList.of(new FunctionArgumentDefinition(false)),
                false,
                true,
                DESCRIPTION,
                SCALAR));
        this.blockTypeOperators = requireNonNull(blockTypeOperators, "blockTypeOperators is null");
    }

    @Override
    protected ScalarFunctionImplementation specialize(FunctionBinding functionBinding)
    {
        if (functionBinding.getArity() < 2) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "There must be two or more concatenation arguments to " + FUNCTION_NAME);
        }

        MapType mapType = (MapType) functionBinding.getBoundSignature().getReturnType();
        Type keyType = mapType.getKeyType();
        BlockPositionEqual keyEqual = blockTypeOperators.getEqualOperator(keyType);
        BlockPositionHashCode keyHashCode = blockTypeOperators.getHashCodeOperator(keyType);

        MethodHandleAndConstructor methodHandleAndConstructor = generateVarArgsToArrayAdapter(
                Block.class,
                Block.class,
                functionBinding.getArity(),
                MethodHandles.insertArguments(METHOD_HANDLE, 0, mapType, keyEqual, keyHashCode),
                USER_STATE_FACTORY.bindTo(mapType));

        return new ChoicesScalarFunctionImplementation(
                functionBinding,
                FAIL_ON_NULL,
                nCopies(functionBinding.getArity(), NEVER_NULL),
                methodHandleAndConstructor.getMethodHandle(),
                Optional.of(methodHandleAndConstructor.getConstructor()));
    }

    @UsedByGeneratedCode
    public static Object createMapState(MapType mapType)
    {
        return new PageBuilder(ImmutableList.of(mapType));
    }

    @UsedByGeneratedCode
    public static Block mapConcat(MapType mapType, BlockPositionEqual keyEqual, BlockPositionHashCode keyHashCode, Object state, Block[] maps)
    {
        int entries = 0;
        int lastMapIndex = maps.length - 1;
        int firstMapIndex = lastMapIndex;
        for (int i = 0; i < maps.length; i++) {
            entries += maps[i].getPositionCount();
            if (maps[i].getPositionCount() > 0) {
                lastMapIndex = i;
                firstMapIndex = min(firstMapIndex, i);
            }
        }
        if (lastMapIndex == firstMapIndex) {
            return maps[lastMapIndex];
        }

        PageBuilder pageBuilder = (PageBuilder) state;
        if (pageBuilder.isFull()) {
            pageBuilder.reset();
        }

        // TODO: we should move TypedSet into user state as well
        Type keyType = mapType.getKeyType();
        Type valueType = mapType.getValueType();
        TypedSet typedSet = createEqualityTypedSet(keyType, keyEqual, keyHashCode, entries / 2, FUNCTION_NAME);
        BlockBuilder mapBlockBuilder = pageBuilder.getBlockBuilder(0);
        BlockBuilder blockBuilder = mapBlockBuilder.beginBlockEntry();

        // the last map
        Block map = maps[lastMapIndex];
        for (int i = 0; i < map.getPositionCount(); i += 2) {
            typedSet.add(map, i);
            keyType.appendTo(map, i, blockBuilder);
            valueType.appendTo(map, i + 1, blockBuilder);
        }
        // the map between the last and the first
        for (int idx = lastMapIndex - 1; idx > firstMapIndex; idx--) {
            map = maps[idx];
            for (int i = 0; i < map.getPositionCount(); i += 2) {
                if (!typedSet.contains(map, i)) {
                    typedSet.add(map, i);
                    keyType.appendTo(map, i, blockBuilder);
                    valueType.appendTo(map, i + 1, blockBuilder);
                }
            }
        }
        // the first map
        map = maps[firstMapIndex];
        for (int i = 0; i < map.getPositionCount(); i += 2) {
            if (!typedSet.contains(map, i)) {
                keyType.appendTo(map, i, blockBuilder);
                valueType.appendTo(map, i + 1, blockBuilder);
            }
        }

        mapBlockBuilder.closeEntry();
        pageBuilder.declarePosition();
        return mapType.getObject(mapBlockBuilder, mapBlockBuilder.getPositionCount() - 1);
    }
}
