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
package io.prestosql.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.operator.scalar.ChoicesScalarFunctionImplementation;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.LongArrayBlock;
import io.prestosql.spi.function.InvocationConvention;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;

import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.metadata.Signature.comparableWithVariadicBound;
import static io.prestosql.metadata.TestPolymorphicScalarFunction.TestMethods.VARCHAR_TO_BIGINT_RETURN_VALUE;
import static io.prestosql.metadata.TestPolymorphicScalarFunction.TestMethods.VARCHAR_TO_VARCHAR_RETURN_VALUE;
import static io.prestosql.spi.function.InvocationConvention.InvocationArgumentConvention.BLOCK_POSITION;
import static io.prestosql.spi.function.InvocationConvention.InvocationArgumentConvention.NULL_FLAG;
import static io.prestosql.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.prestosql.spi.function.OperatorType.ADD;
import static io.prestosql.spi.function.OperatorType.IS_DISTINCT_FROM;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.Decimals.MAX_SHORT_PRECISION;
import static io.prestosql.spi.type.TypeSignatureParameter.typeVariable;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestPolymorphicScalarFunction
{
    private static final Metadata METADATA = createTestMetadataManager();
    private static final Signature SIGNATURE = Signature.builder()
            .name("foo")
            .returnType(BIGINT.getTypeSignature())
            .argumentTypes(new TypeSignature("varchar", typeVariable("x")))
            .build();
    private static final int INPUT_VARCHAR_LENGTH = 10;
    private static final Slice INPUT_SLICE = Slices.allocate(INPUT_VARCHAR_LENGTH);
    private static final BoundSignature BOUND_SIGNATURE = new BoundSignature(SIGNATURE.getName(), BIGINT, ImmutableList.of(createVarcharType(INPUT_VARCHAR_LENGTH)));
    private static final Map<String, Type> VARCHAR_TYPE_VARIABLES = ImmutableMap.of("V", createVarcharType(INPUT_VARCHAR_LENGTH));
    private static final Map<String, Long> VARCHAR_LONG_VARIABLES = ImmutableMap.of("x", (long) INPUT_VARCHAR_LENGTH);

    private static final TypeSignature DECIMAL_SIGNATURE = new TypeSignature("decimal", typeVariable("a_precision"), typeVariable("a_scale"));

    private static final DecimalType LONG_DECIMAL_BOUND_TYPE = DecimalType.createDecimalType(MAX_SHORT_PRECISION + 1, 2);
    private static final Map<String, Long> LONG_DECIMAL_LONG_VARIABLES = ImmutableMap.<String, Long>builder()
            .put("a_precision", (long) LONG_DECIMAL_BOUND_TYPE.getPrecision())
            .put("a_scale", (long) LONG_DECIMAL_BOUND_TYPE.getScale())
            .build();

    private static final DecimalType SHORT_DECIMAL_BOUND_TYPE = DecimalType.createDecimalType(MAX_SHORT_PRECISION, 2);
    private static final Map<String, Long> SHORT_DECIMAL_LONG_VARIABLES = ImmutableMap.<String, Long>builder()
            .put("a_precision", (long) SHORT_DECIMAL_BOUND_TYPE.getPrecision())
            .put("a_scale", (long) SHORT_DECIMAL_BOUND_TYPE.getScale())
            .build();

    @Test
    public void testSelectsMultipleChoiceWithBlockPosition()
            throws Throwable
    {
        Signature signature = Signature.builder()
                .operatorType(IS_DISTINCT_FROM)
                .argumentTypes(DECIMAL_SIGNATURE, DECIMAL_SIGNATURE)
                .returnType(BOOLEAN.getTypeSignature())
                .build();

        SqlScalarFunction function = new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(signature)
                .argumentDefinitions(
                        new FunctionArgumentDefinition(true),
                        new FunctionArgumentDefinition(true))
                .deterministic(true)
                .choice(choice -> choice
                        .argumentProperties(NULL_FLAG, NULL_FLAG)
                        .implementation(methodsGroup -> methodsGroup
                                .methods("shortShort", "longLong")))
                .choice(choice -> choice
                        .argumentProperties(BLOCK_POSITION, BLOCK_POSITION)
                        .implementation(methodsGroup -> methodsGroup
                                .methodWithExplicitJavaTypes("blockPositionLongLong",
                                        asList(Optional.of(Slice.class), Optional.of(Slice.class)))
                                .methodWithExplicitJavaTypes("blockPositionShortShort",
                                        asList(Optional.of(long.class), Optional.of(long.class)))))
                .build();

        FunctionBinding shortDecimalFunctionBinding = new FunctionBinding(
                function.getFunctionMetadata().getFunctionId(),
                new BoundSignature(signature.getName(), BOOLEAN, ImmutableList.of(SHORT_DECIMAL_BOUND_TYPE, SHORT_DECIMAL_BOUND_TYPE)),
                ImmutableMap.of(),
                SHORT_DECIMAL_LONG_VARIABLES);
        ChoicesScalarFunctionImplementation functionImplementation = (ChoicesScalarFunctionImplementation) function.specialize(
                shortDecimalFunctionBinding,
                new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of()));

        assertEquals(functionImplementation.getChoices().size(), 2);
        assertEquals(
                functionImplementation.getChoices().get(0).getInvocationConvention(),
                new InvocationConvention(ImmutableList.of(NULL_FLAG, NULL_FLAG), FAIL_ON_NULL, false, false));
        assertEquals(
                functionImplementation.getChoices().get(1).getInvocationConvention(),
                new InvocationConvention(ImmutableList.of(BLOCK_POSITION, BLOCK_POSITION), FAIL_ON_NULL, false, false));
        Block block1 = new LongArrayBlock(0, Optional.empty(), new long[0]);
        Block block2 = new LongArrayBlock(0, Optional.empty(), new long[0]);
        assertFalse((boolean) functionImplementation.getChoices().get(1).getMethodHandle().invoke(block1, 0, block2, 0));

        FunctionBinding longDecimalFunctionBinding = new FunctionBinding(
                function.getFunctionMetadata().getFunctionId(),
                new BoundSignature(signature.getName(), BOOLEAN, ImmutableList.of(LONG_DECIMAL_BOUND_TYPE, LONG_DECIMAL_BOUND_TYPE)),
                ImmutableMap.of(),
                LONG_DECIMAL_LONG_VARIABLES);
        functionImplementation = (ChoicesScalarFunctionImplementation) function.specialize(
                longDecimalFunctionBinding,
                new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of()));
        assertTrue((boolean) functionImplementation.getChoices().get(1).getMethodHandle().invoke(block1, 0, block2, 0));
    }

    @Test
    public void testSelectsMethodBasedOnArgumentTypes()
            throws Throwable
    {
        SqlScalarFunction function = new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("bigintToBigintReturnExtraParameter"))
                        .implementation(methodsGroup -> methodsGroup
                                .methods("varcharToBigintReturnExtraParameter")
                                .withExtraParameters(context -> ImmutableList.of(context.getLiteral("x")))))
                .build();

        FunctionBinding functionBinding = new FunctionBinding(
                function.getFunctionMetadata().getFunctionId(),
                BOUND_SIGNATURE,
                VARCHAR_TYPE_VARIABLES,
                VARCHAR_LONG_VARIABLES);
        ChoicesScalarFunctionImplementation functionImplementation = (ChoicesScalarFunctionImplementation) function.specialize(
                functionBinding,
                new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of()));
        assertEquals(functionImplementation.getChoices().get(0).getMethodHandle().invoke(INPUT_SLICE), (long) INPUT_VARCHAR_LENGTH);
    }

    @Test
    public void testSelectsMethodBasedOnReturnType()
            throws Throwable
    {
        SqlScalarFunction function = new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToVarcharCreateSliceWithExtraParameterLength"))
                        .implementation(methodsGroup -> methodsGroup
                                .methods("varcharToBigintReturnExtraParameter")
                                .withExtraParameters(context -> ImmutableList.of(42))))
                .build();

        FunctionBinding functionBinding = new FunctionBinding(
                function.getFunctionMetadata().getFunctionId(),
                BOUND_SIGNATURE,
                VARCHAR_TYPE_VARIABLES,
                VARCHAR_LONG_VARIABLES);
        ChoicesScalarFunctionImplementation functionImplementation = (ChoicesScalarFunctionImplementation) function.specialize(
                functionBinding,
                new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of()));

        assertEquals(functionImplementation.getChoices().get(0).getMethodHandle().invoke(INPUT_SLICE), VARCHAR_TO_BIGINT_RETURN_VALUE);
    }

    @Test
    public void testSameLiteralInArgumentsAndReturnValue()
            throws Throwable
    {
        Signature signature = Signature.builder()
                .name("foo")
                .returnType(new TypeSignature("varchar", typeVariable("x")))
                .argumentTypes(new TypeSignature("varchar", typeVariable("x")))
                .build();

        SqlScalarFunction function = new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(signature)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToVarchar")))
                .build();

        FunctionBinding functionBinding = new FunctionBinding(
                function.getFunctionMetadata().getFunctionId(),
                new BoundSignature(signature.getName(), createVarcharType(INPUT_VARCHAR_LENGTH), ImmutableList.of(createVarcharType(INPUT_VARCHAR_LENGTH))),
                VARCHAR_TYPE_VARIABLES,
                VARCHAR_LONG_VARIABLES);

        ChoicesScalarFunctionImplementation functionImplementation = (ChoicesScalarFunctionImplementation) function.specialize(
                functionBinding,
                new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of()));
        Slice slice = (Slice) functionImplementation.getChoices().get(0).getMethodHandle().invoke(INPUT_SLICE);
        assertEquals(slice, VARCHAR_TO_VARCHAR_RETURN_VALUE);
    }

    @Test
    public void testTypeParameters()
            throws Throwable
    {
        Signature signature = Signature.builder()
                .name("foo")
                .typeVariableConstraints(comparableWithVariadicBound("V", "ROW"))
                .returnType(new TypeSignature("V"))
                .argumentTypes(new TypeSignature("V"))
                .build();

        SqlScalarFunction function = new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(signature)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToVarchar")))
                .build();

        FunctionBinding functionBinding = new FunctionBinding(
                function.getFunctionMetadata().getFunctionId(),
                new BoundSignature(signature.getName(), VARCHAR, ImmutableList.of(VARCHAR)),
                VARCHAR_TYPE_VARIABLES,
                VARCHAR_LONG_VARIABLES);

        ChoicesScalarFunctionImplementation functionImplementation = (ChoicesScalarFunctionImplementation) function.specialize(
                functionBinding,
                new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of()));
        Slice slice = (Slice) functionImplementation.getChoices().get(0).getMethodHandle().invoke(INPUT_SLICE);
        assertEquals(slice, VARCHAR_TO_VARCHAR_RETURN_VALUE);
    }

    @Test
    public void testSetsHiddenToTrueForOperators()
    {
        Signature signature = Signature.builder()
                .operatorType(ADD)
                .returnType(new TypeSignature("varchar", typeVariable("x")))
                .argumentTypes(new TypeSignature("varchar", typeVariable("x")))
                .build();

        SqlScalarFunction function = new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(signature)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToVarchar")))
                .build();

        FunctionBinding functionBinding = new FunctionBinding(
                function.getFunctionMetadata().getFunctionId(),
                new BoundSignature(signature.getName(), createVarcharType(INPUT_VARCHAR_LENGTH), ImmutableList.of(createVarcharType(INPUT_VARCHAR_LENGTH))),
                VARCHAR_TYPE_VARIABLES,
                VARCHAR_LONG_VARIABLES);

        function.specialize(functionBinding, new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of()));
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "method foo was not found in class io.prestosql.metadata.TestPolymorphicScalarFunction\\$TestMethods")
    public void testFailIfNotAllMethodsPresent()
    {
        new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("bigintToBigintReturnExtraParameter"))
                        .implementation(methodsGroup -> methodsGroup.methods("foo")))
                .build();
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "methods must be selected first")
    public void testFailNoMethodsAreSelectedWhenExtraParametersFunctionIsSet()
    {
        new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup
                                .withExtraParameters(context -> ImmutableList.of(42))))
                .build();
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "two matching methods \\(varcharToBigintReturnFirstExtraParameter and varcharToBigintReturnExtraParameter\\) for parameter types \\[varchar\\(10\\)\\]")
    public void testFailIfTwoMethodsWithSameArguments()
    {
        SqlScalarFunction function = new PolymorphicScalarFunctionBuilder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToBigintReturnFirstExtraParameter"))
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToBigintReturnExtraParameter")))
                .build();

        FunctionBinding functionBinding = new FunctionBinding(
                function.getFunctionMetadata().getFunctionId(),
                BOUND_SIGNATURE,
                VARCHAR_TYPE_VARIABLES,
                VARCHAR_LONG_VARIABLES);
        function.specialize(functionBinding, new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of()));
    }

    public static final class TestMethods
    {
        static final Slice VARCHAR_TO_VARCHAR_RETURN_VALUE = Slices.utf8Slice("hello world");
        static final long VARCHAR_TO_BIGINT_RETURN_VALUE = 42L;

        public static Slice varcharToVarchar(Slice varchar)
        {
            return VARCHAR_TO_VARCHAR_RETURN_VALUE;
        }

        public static long varcharToBigint(Slice varchar)
        {
            return VARCHAR_TO_BIGINT_RETURN_VALUE;
        }

        public static long varcharToBigintReturnExtraParameter(Slice varchar, long extraParameter)
        {
            return extraParameter;
        }

        public static long bigintToBigintReturnExtraParameter(long bigint, int extraParameter)
        {
            return bigint;
        }

        public static long varcharToBigintReturnFirstExtraParameter(Slice varchar, long extraParameter1, int extraParameter2)
        {
            return extraParameter1;
        }

        public static Slice varcharToVarcharCreateSliceWithExtraParameterLength(Slice string, int extraParameter)
        {
            return Slices.allocate(extraParameter);
        }

        public static boolean blockPositionLongLong(Block left, int leftPosition, Block right, int rightPosition)
        {
            return true;
        }

        public static boolean blockPositionShortShort(Block left, int leftPosition, Block right, int rightPosition)
        {
            return false;
        }

        public static boolean shortShort(long left, boolean leftNull, long right, boolean rightNull)
        {
            return false;
        }

        public static boolean longLong(Slice left, boolean leftNull, Slice right, boolean rightNull)
        {
            return false;
        }
    }
}
