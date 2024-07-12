package com.azure.java.sdk.tests;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Metrics;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.math.BigDecimal;
import java.util.function.Function;

public class AzureSDKMetricsTests {

    @Test
    public void test() {
        // replace LoggingMeterRegistry with your own PushMeterRegistry implementation
        MeterRegistry meterRegistry = new LoggingMeterRegistry();

        CompositeMeterRegistry globalRegistry = (CompositeMeterRegistry) Metrics.REGISTRY;
        globalRegistry.add(meterRegistry);

        // see https://github.com/Azure/azure-sdk-for-java/tree/main/sdk/resourcemanager#authentication for environment variables

        int maxConnections = 100;
        com.azure.core.http.HttpClient httpClient = new NettyAsyncHttpClientBuilder(
                // enable reactor-netty metrics
                // availabe metrics can be found https://projectreactor.io/docs/netty/release/reference/index.html#_metrics_3
                HttpClient.create(ConnectionProvider
                                .builder("myConnectionProvider")
                                .maxConnections(maxConnections)
//                                .metrics(true) // HttpClient level metrics enable should be enough
                                .build())
                        .resolver(DefaultAddressResolverGroup.INSTANCE)
                        .metrics(true, Function.identity())
        ).build();

        TokenCredential credential = new DefaultAzureCredentialBuilder()
                .httpClient(httpClient)
                .build();
        AzureProfile profile = new AzureProfile(AzureEnvironment.AZURE);

        AzureResourceManager azureResourceManager = AzureResourceManager
                .configure()
                .withHttpClient(httpClient)
                .withLogLevel(HttpLogDetailLevel.BASIC)
                .authenticate(credential, profile)
                .withDefaultSubscription();

        // export Scheduler(reactor threads) related metrics
        // see https://projectreactor.io/docs/core/release/reference/#metrics
        Scheduler schedulerWithMetrics = Micrometer.timedScheduler(
                Schedulers.boundedElastic(),
                Metrics.REGISTRY,
                "testingMetrics",
                Tags.of(Tag.of("additionalTag", "yes"))
        );

        ResourceGroup resourceGroup = azureResourceManager.resourceGroups().getByNameAsync("rg-xiaofei")
                // if you use extra Scheduler
                .subscribeOn(schedulerWithMetrics)
                .block();

        Assertions.assertEquals(maxConnections, BigDecimal.valueOf(globalRegistry.get(Metrics.CONNECTION_PROVIDER_PREFIX + Metrics.MAX_CONNECTIONS).gauge().value()).intValue());
        meterRegistry.close();
    }
}
