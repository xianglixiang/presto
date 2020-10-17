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
package io.prestosql;

import io.prestosql.connector.CatalogName;
import org.testng.annotations.Test;

import java.util.Map;

import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

public class TestSession
{
    @Test
    public void testSetCatalogProperty()
    {
        Session session = Session.builder(testSessionBuilder().build())
                .setCatalogSessionProperty("some_catalog", "first_property", "some_value")
                .build();

        assertThat(session.getUnprocessedCatalogProperties())
                .isEqualTo(Map.of("some_catalog", Map.of("first_property", "some_value")));

        // empty, will be populated at transaction start
        assertThat(session.getConnectorProperties())
                .isEqualTo(Map.of());

        // empty, will be populated at transaction start
        assertThat(session.getConnectorProperties(new CatalogName("some_catalog")))
                .isEqualTo(Map.of());
    }

    @Test
    public void testBuildWithCatalogProperty()
    {
        Session session = Session.builder(testSessionBuilder().build())
                .setCatalogSessionProperty("some_catalog", "first_property", "some_value")
                .build();
        session = Session.builder(session)
                .build();

        assertThat(session.getUnprocessedCatalogProperties())
                .isEqualTo(Map.of("some_catalog", Map.of("first_property", "some_value")));

        // empty, will be populated at transaction start
        assertThat(session.getConnectorProperties())
                .isEqualTo(Map.of());

        // empty, will be populated at transaction start
        assertThat(session.getConnectorProperties(new CatalogName("some_catalog")))
                .isEqualTo(Map.of());
    }

    @Test
    public void testAddSecondCatalogProperty()
    {
        Session session = Session.builder(testSessionBuilder().build())
                .setCatalogSessionProperty("some_catalog", "first_property", "some_value")
                .build();
        session = Session.builder(session)
                .setCatalogSessionProperty("some_catalog", "second_property", "another_value")
                .build();

        assertThat(session.getUnprocessedCatalogProperties())
                .isEqualTo(Map.of("some_catalog", Map.of(
                        "first_property", "some_value",
                        "second_property", "another_value")));

        // empty, will be populated at transaction start
        assertThat(session.getConnectorProperties())
                .isEqualTo(Map.of());

        // empty, will be populated at transaction start
        assertThat(session.getConnectorProperties(new CatalogName("some_catalog")))
                .isEqualTo(Map.of());
    }
}
