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
package io.prestosql.server.security.jwt;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.jsonwebtoken.SigningKeyResolver;

import static io.airlift.configuration.ConditionalModule.installModuleIf;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;

public class JwtAuthenticatorSupportModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        configBinder(binder).bindConfig(JwtAuthenticatorConfig.class);
        install(installModuleIf(
                JwtAuthenticatorConfig.class,
                JwtAuthenticatorSupportModule::isHttp,
                new JwkModule(),
                jwkBinder -> jwkBinder.bind(SigningKeyResolver.class).to(FileSigningKeyResolver.class).in(Scopes.SINGLETON)));
    }

    private static boolean isHttp(JwtAuthenticatorConfig config)
    {
        return config.getKeyFile().startsWith("https://") || config.getKeyFile().startsWith("http://");
    }

    private static class JwkModule
            implements Module
    {
        @Override
        public void configure(Binder binder)
        {
            binder.bind(SigningKeyResolver.class).to(JwkSigningKeyResolver.class).in(Scopes.SINGLETON);
            binder.bind(JwkService.class).in(Scopes.SINGLETON);
            httpClientBinder(binder)
                    .bindHttpClient("jwk", ForJwk.class);
        }

        // this module can be added multiple times, and this prevents multiple processing by Guice
        @Override
        public int hashCode()
        {
            return JwkModule.class.hashCode();
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof JwkModule;
        }
    }
}
