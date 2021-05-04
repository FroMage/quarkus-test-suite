package io.quarkus.ts.openshift.scaling;

import io.quarkus.test.bootstrap.RestService;
import io.quarkus.test.bootstrap.inject.OpenShiftClient;
import io.quarkus.test.scenarios.OpenShiftScenario;
import io.quarkus.test.scenarios.annotations.EnabledIfOpenShiftScenarioPropertyIsTrue;
import io.quarkus.test.services.QuarkusApplication;
import io.restassured.response.ValidatableResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static javax.ws.rs.core.Response.Status.OK;
import static javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.MethodOrderer.OrderAnnotation;

@OpenShiftScenario
@EnabledIfOpenShiftScenarioPropertyIsTrue
@TestMethodOrder(OrderAnnotation.class)
public class OpenShiftScalingIT {

    @Inject
    static OpenShiftClient openShiftUtil;

    @QuarkusApplication
    static RestService app = new RestService();

    private static final int TIMEOUT_SEC = 60;
    private static final int DELAY_BETWEEN_REQUEST_MS = 100;
    private static final int POLL_INTERVAL_MS = 1000;
    private static final int FIRST = 1;
    private static final int SECOND = 2;
    private static final int THIRD = 3;

    @AfterEach
    public void scaleBack() {
        openShiftUtil.scaleTo(app, 1);
    }

    /**
     * Workflow:
     * * Make sure the single replica is running.
     * * Scale up to two replicas.
     * * Wait for their readiness and verify that both of the replicas are responding.
     */
    @Test
    @Order(FIRST)
    public void scaleUpTest() {
        int replicas = 2;
        givenResourcePath("/scaling");
        whenScaleTo(2);
        thenCheckReplicasAmount(2);
        whenMakeRequestTo("/scaling", replicas);
    }


    /**
     * Workflow:
     * * Scale to two replicas and verify that both of the replicas are responding.
     * * Scale down to a single replica.
     * * Execute an arbitrary minimal sample of requests and verify that all are served by the same replica.
     */
    @Test
    @Order(SECOND)
    public void scaleDownTest() throws InterruptedException {
        int replicas = 2;
        givenResourcePath("/scaling");
        whenScaleTo(2);
        thenCheckReplicasAmount(2);
        whenMakeRequestTo("/scaling", replicas);

        replicas = 1;
        whenScaleTo(1);
        thenCheckReplicasAmount(1);
        whenMakeRequestTo("/scaling", replicas);
    }


    /**
     * Workflow:
     * * Scale down to zero replicas.
     * * Execute an arbitrary minimal sample of requests and verify that all get HTTP 503 response.
     */
    @Test
    @Order(THIRD)
    public void scaleToZero() throws InterruptedException {
        givenResourcePath("/scaling");
        whenScaleTo(0);
        thenCheckReplicasAmount(0);
        makeHttpScalingRequest("/scaling", SERVICE_UNAVAILABLE.getStatusCode());
    }

    private void givenResourcePath(String path) {
        with().pollInterval(Duration.ofMillis(POLL_INTERVAL_MS))
                .await().atMost(TIMEOUT_SEC, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    app.given()
                            .when().get("/scaling")
                            .then()
                            .log().body()
                            .log().status()
                            .statusCode(OK.getStatusCode());
                });
    }

    private void whenScaleTo(int amount) {
        openShiftUtil.scaleTo(app, amount);
    }

    private void thenCheckReplicasAmount(int expectedAmount) {
        await().pollInterval(DELAY_BETWEEN_REQUEST_MS, TimeUnit.MILLISECONDS)
                .atMost(TIMEOUT_SEC, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(openShiftUtil.readyReplicas(app)).isEqualTo(expectedAmount));
    }

    private void whenMakeRequestTo(String path, int expectedReplicas) {
        Set<String> replicas = new HashSet<>();
        await().pollInterval(DELAY_BETWEEN_REQUEST_MS, TimeUnit.MILLISECONDS)
                .atMost(TIMEOUT_SEC, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    replicas.add(makeHttpScalingRequest(path, OK.getStatusCode()).extract().asString());
                    assertEquals(expectedReplicas, replicas.size());
                });
    }

    private ValidatableResponse makeHttpScalingRequest(String path, int expectedHttpStatus) {
        return app.given()
                .when().get(path)
                .then()
                .statusCode(expectedHttpStatus);
    }
}