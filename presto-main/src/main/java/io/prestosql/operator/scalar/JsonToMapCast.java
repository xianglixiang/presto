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
import io.airlift.slice.Slice;
import io.prestosql.annotation.UsedByGeneratedCode;
import io.prestosql.metadata.FunctionBinding;
import io.prestosql.metadata.SqlOperator;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.util.JsonCastException;
import io.prestosql.util.JsonUtil.BlockBuilderAppender;

import java.lang.invoke.MethodHandle;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.metadata.Signature.castableFromTypeParameter;
import static io.prestosql.spi.StandardErrorCode.INVALID_CAST_ARGUMENT;
import static io.prestosql.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.prestosql.spi.function.InvocationConvention.InvocationReturnConvention.NULLABLE_RETURN;
import static io.prestosql.spi.type.TypeSignature.mapType;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.type.JsonType.JSON;
import static io.prestosql.util.Failures.checkCondition;
import static io.prestosql.util.JsonUtil.BlockBuilderAppender.createBlockBuilderAppender;
import static io.prestosql.util.JsonUtil.JSON_FACTORY;
import static io.prestosql.util.JsonUtil.canCastFromJson;
import static io.prestosql.util.JsonUtil.createJsonParser;
import static io.prestosql.util.JsonUtil.truncateIfNecessaryForErrorMessage;
import static io.prestosql.util.Reflection.methodHandle;
import static java.lang.String.format;

public class JsonToMapCast
        extends SqlOperator
{
    public static final JsonToMapCast JSON_TO_MAP = new JsonToMapCast();
    private static final MethodHandle METHOD_HANDLE = methodHandle(JsonToMapCast.class, "toMap", MapType.class, BlockBuilderAppender.class, ConnectorSession.class, Slice.class);

    private JsonToMapCast()
    {
        super(OperatorType.CAST,
                ImmutableList.of(
                        castableFromTypeParameter("K", VARCHAR.getTypeSignature()),
                        castableFromTypeParameter("V", JSON.getTypeSignature())),
                ImmutableList.of(),
                mapType(new TypeSignature("K"), new TypeSignature("V")),
                ImmutableList.of(JSON.getTypeSignature()),
                true);
    }

    @Override
    protected ScalarFunctionImplementation specialize(FunctionBinding functionBinding)
    {
        checkArgument(functionBinding.getArity() == 1, "Expected arity to be 1");
        MapType mapType = (MapType) functionBinding.getBoundSignature().getReturnType();
        checkCondition(canCastFromJson(mapType), INVALID_CAST_ARGUMENT, "Cannot cast JSON to %s", mapType);

        BlockBuilderAppender mapAppender = createBlockBuilderAppender(mapType);
        MethodHandle methodHandle = METHOD_HANDLE.bindTo(mapType).bindTo(mapAppender);
        return new ChoicesScalarFunctionImplementation(
                functionBinding,
                NULLABLE_RETURN,
                ImmutableList.of(NEVER_NULL),
                methodHandle);
    }

    @UsedByGeneratedCode
    public static Block toMap(MapType mapType, BlockBuilderAppender mapAppender, ConnectorSession connectorSession, Slice json)
    {
        try (JsonParser jsonParser = createJsonParser(JSON_FACTORY, json)) {
            jsonParser.nextToken();
            if (jsonParser.getCurrentToken() == JsonToken.VALUE_NULL) {
                return null;
            }

            BlockBuilder blockBuilder = mapType.createBlockBuilder(null, 1);
            mapAppender.append(jsonParser, blockBuilder);
            if (jsonParser.nextToken() != null) {
                throw new JsonCastException(format("Unexpected trailing token: %s", jsonParser.getText()));
            }
            return mapType.getObject(blockBuilder, blockBuilder.getPositionCount() - 1);
        }
        catch (PrestoException | JsonCastException e) {
            throw new PrestoException(INVALID_CAST_ARGUMENT, format("Cannot cast to %s. %s\n%s", mapType, e.getMessage(), truncateIfNecessaryForErrorMessage(json)), e);
        }
        catch (Exception e) {
            throw new PrestoException(INVALID_CAST_ARGUMENT, format("Cannot cast to %s.\n%s", mapType, truncateIfNecessaryForErrorMessage(json)), e);
        }
    }
}
