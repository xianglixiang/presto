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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.prestosql.annotation.UsedByGeneratedCode;
import io.prestosql.metadata.FunctionBinding;
import io.prestosql.metadata.SqlOperator;
import io.prestosql.metadata.TypeVariableConstraint;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.util.JsonCastException;
import io.prestosql.util.JsonUtil.BlockBuilderAppender;

import java.lang.invoke.MethodHandle;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.StandardErrorCode.INVALID_CAST_ARGUMENT;
import static io.prestosql.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.prestosql.spi.function.InvocationConvention.InvocationReturnConvention.NULLABLE_RETURN;
import static io.prestosql.type.JsonType.JSON;
import static io.prestosql.util.Failures.checkCondition;
import static io.prestosql.util.JsonUtil.BlockBuilderAppender.createBlockBuilderAppender;
import static io.prestosql.util.JsonUtil.JSON_FACTORY;
import static io.prestosql.util.JsonUtil.canCastFromJson;
import static io.prestosql.util.JsonUtil.createJsonParser;
import static io.prestosql.util.JsonUtil.truncateIfNecessaryForErrorMessage;
import static io.prestosql.util.Reflection.methodHandle;
import static java.lang.String.format;

public class JsonToRowCast
        extends SqlOperator
{
    public static final JsonToRowCast JSON_TO_ROW = new JsonToRowCast();
    private static final MethodHandle METHOD_HANDLE = methodHandle(JsonToRowCast.class, "toRow", RowType.class, BlockBuilderAppender.class, ConnectorSession.class, Slice.class);

    private JsonToRowCast()
    {
        super(OperatorType.CAST,
                ImmutableList.of(
                        // this is technically a recursive constraint for cast, but TypeRegistry.canCast has explicit handling for json to row cast
                        new TypeVariableConstraint("T", false, false, "row", ImmutableSet.of(), ImmutableSet.of(JSON.getTypeSignature()))),
                ImmutableList.of(),
                new TypeSignature("T"),
                ImmutableList.of(JSON.getTypeSignature()),
                true);
    }

    @Override
    protected ScalarFunctionImplementation specialize(FunctionBinding functionBinding)
    {
        checkArgument(functionBinding.getArity() == 1, "Expected arity to be 1");
        RowType rowType = (RowType) functionBinding.getTypeVariable("T");
        checkCondition(canCastFromJson(rowType), INVALID_CAST_ARGUMENT, "Cannot cast JSON to %s", rowType);

        BlockBuilderAppender fieldAppender = createBlockBuilderAppender(rowType);
        MethodHandle methodHandle = METHOD_HANDLE.bindTo(rowType).bindTo(fieldAppender);
        return new ChoicesScalarFunctionImplementation(
                functionBinding,
                NULLABLE_RETURN,
                ImmutableList.of(NEVER_NULL),
                methodHandle);
    }

    @UsedByGeneratedCode
    public static Block toRow(
            RowType rowType,
            BlockBuilderAppender rowAppender,
            ConnectorSession connectorSession,
            Slice json)
    {
        try (JsonParser jsonParser = createJsonParser(JSON_FACTORY, json)) {
            jsonParser.nextToken();
            if (jsonParser.getCurrentToken() == JsonToken.VALUE_NULL) {
                return null;
            }

            BlockBuilder rowBlockBuilder = rowType.createBlockBuilder(null, 1);
            rowAppender.append(jsonParser, rowBlockBuilder);
            if (jsonParser.nextToken() != null) {
                throw new JsonCastException(format("Unexpected trailing token: %s", jsonParser.getText()));
            }
            return rowType.getObject(rowBlockBuilder, 0);
        }
        catch (PrestoException | JsonCastException e) {
            throw new PrestoException(INVALID_CAST_ARGUMENT, format("Cannot cast to %s. %s\n%s", rowType, e.getMessage(), truncateIfNecessaryForErrorMessage(json)), e);
        }
        catch (Exception e) {
            throw new PrestoException(INVALID_CAST_ARGUMENT, format("Cannot cast to %s.\n%s", rowType, truncateIfNecessaryForErrorMessage(json)), e);
        }
    }
}
