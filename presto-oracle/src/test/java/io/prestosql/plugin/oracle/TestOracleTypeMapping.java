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
package io.prestosql.plugin.oracle;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.testing.QueryRunner;
import io.prestosql.testing.sql.SqlExecutor;
import org.testng.annotations.AfterClass;

public class TestOracleTypeMapping
        extends AbstractTestOracleTypeMapping
{
    private TestingOracleServer oracleServer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        this.oracleServer = new TestingOracleServer();
        return OracleQueryRunner.createOracleQueryRunner(oracleServer, ImmutableMap.of(), ImmutableList.of(), false);
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        if (oracleServer != null) {
            oracleServer.close();
        }
    }

    @Override
    protected SqlExecutor getOracleSqlExecutor()
    {
        return sql -> oracleServer.execute(sql);
    }
}
