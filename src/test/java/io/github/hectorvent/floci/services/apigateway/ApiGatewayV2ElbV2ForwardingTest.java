package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.VpcLink;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ApiGatewayV2ElbV2ForwardingTest.ElbV2DataPlaneProfile.class)
class ApiGatewayV2ElbV2ForwardingTest {

    private static final String REGION = "us-east-1";

    @Inject
    ApiGatewayV2Service apiGatewayV2Service;

    @Inject
    ElbV2Service elbV2Service;

    private HttpServer backendServer;
    private String apiId;
    private String vpcLinkId;
    private String listenerArn;
    private String loadBalancerArn;
    private String targetGroupArn;

    @AfterEach
    void cleanup() {
        if (listenerArn != null) {
            elbV2Service.deleteListener(REGION, listenerArn);
        }
        if (loadBalancerArn != null) {
            elbV2Service.deleteLoadBalancer(REGION, loadBalancerArn);
        }
        if (targetGroupArn != null) {
            elbV2Service.deleteTargetGroup(REGION, targetGroupArn);
        }
        if (vpcLinkId != null) {
            apiGatewayV2Service.deleteVpcLink(REGION, vpcLinkId);
        }
        if (apiId != null) {
            apiGatewayV2Service.deleteApi(REGION, apiId);
        }
        if (backendServer != null) {
            backendServer.stop(0);
        }
    }

    @Test
    void httpApiVpcLinkForwardsThroughElbV2ListenerDataPlane() throws Exception {
        int backendPort = startBackend();
        int listenerPort = freePort();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Api api = apiGatewayV2Service.createApi(REGION, Map.of(
                "name", "apigw-elbv2-" + suffix,
                "protocolType", "HTTP"));
        apiId = api.getApiId();

        VpcLink vpcLink = apiGatewayV2Service.createVpcLink(REGION, Map.of(
                "name", "apigw-elbv2-" + suffix,
                "subnetIds", List.of("subnet-a"),
                "securityGroupIds", List.of("sg-a")));
        vpcLinkId = vpcLink.getVpcLinkId();

        LoadBalancer loadBalancer = elbV2Service.createLoadBalancer(
                REGION,
                "apigw-elbv2-" + suffix,
                "internal",
                "application",
                "ipv4",
                List.of("subnet-a"),
                List.of("sg-a"),
                Map.of());
        loadBalancerArn = loadBalancer.getLoadBalancerArn();

        TargetGroup targetGroup = elbV2Service.createTargetGroup(
                REGION,
                "apigw-elbv2-" + suffix,
                "HTTP",
                "HTTP1",
                backendPort,
                "vpc-00000001",
                "ip",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
        targetGroupArn = targetGroup.getTargetGroupArn();

        TargetDescription target = new TargetDescription();
        target.setId("localhost");
        target.setPort(backendPort);
        elbV2Service.registerTargets(REGION, targetGroupArn, List.of(target));

        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(targetGroupArn);
        Listener listener = elbV2Service.createListener(
                REGION,
                loadBalancerArn,
                "HTTP",
                listenerPort,
                null,
                List.of(),
                List.of(action),
                List.of(),
                Map.of());
        listenerArn = listener.getListenerArn();

        Integration integration = apiGatewayV2Service.createIntegration(REGION, apiId, Map.of(
                "integrationType", "HTTP_PROXY",
                "integrationMethod", "ANY",
                "integrationUri", listenerArn,
                "connectionType", "VPC_LINK",
                "connectionId", vpcLinkId,
                "payloadFormatVersion", "1.0"));
        apiGatewayV2Service.createRoute(REGION, apiId, Map.of(
                "routeKey", "ANY /{proxy+}",
                "target", "integrations/" + integration.getIntegrationId()));
        apiGatewayV2Service.createStage(REGION, apiId, Map.of(
                "stageName", "prod",
                "autoDeploy", true));

        Thread.sleep(250);

        given()
                .contentType("text/plain")
                .queryParam("trace", "abc")
                .body("payload")
        .when()
                .post("/execute-api/" + apiId + "/prod/orders/123")
        .then()
                .statusCode(200)
                .header("X-Backend", equalTo("api-gateway-elbv2"))
                .body(containsString("method=POST"))
                .body(containsString("path=/orders/123"))
                .body(containsString("query=trace=abc"))
                .body(containsString("body=payload"));

    }

    private int startBackend() throws IOException {
        backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendServer.createContext("/", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String response = "method=" + exchange.getRequestMethod()
                    + "\npath=" + exchange.getRequestURI().getPath()
                    + "\nquery=" + exchange.getRequestURI().getRawQuery()
                    + "\nbody=" + new String(requestBody, StandardCharsets.UTF_8);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.getResponseHeaders().add("X-Backend", "api-gateway-elbv2");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        backendServer.start();
        return backendServer.getAddress().getPort();
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static final class ElbV2DataPlaneProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.mock", "false");
        }
    }
}
