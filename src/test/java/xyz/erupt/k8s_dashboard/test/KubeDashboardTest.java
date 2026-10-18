package xyz.erupt.k8s_dashboard.test;

import com.google.gson.JsonObject;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import xyz.erupt.core.constant.MenuTypeEnum;
import xyz.erupt.core.context.MetaContext;
import xyz.erupt.core.context.MetaErupt;
import xyz.erupt.core.context.MetaUser;
import xyz.erupt.core.exception.EruptWebApiRuntimeException;
import xyz.erupt.core.invoke.DataProcessorManager;
import xyz.erupt.core.module.MetaMenu;
import xyz.erupt.core.module.MetaUserinfo;
import xyz.erupt.core.scope.EruptScopeService;
import xyz.erupt.core.scope.EruptScopes;
import xyz.erupt.core.service.EruptCoreService;
import xyz.erupt.core.service.EruptService;
import xyz.erupt.core.util.Erupts;
import xyz.erupt.core.view.EruptModel;
import xyz.erupt.core.view.TableQuery;
import xyz.erupt.k8s_dashboard.EruptK8sDashboardAutoConfiguration;
import xyz.erupt.k8s_dashboard.model.KubeDeployment;
import xyz.erupt.k8s_dashboard.model.KubeNamespace;
import xyz.erupt.k8s_dashboard.model.KubePod;
import xyz.erupt.k8s_dashboard.scope.KubeContextFetchHandler;
import xyz.erupt.k8s_dashboard.scope.KubeScope;
import xyz.erupt.k8s_dashboard.terminal.K8sTerminalEndpoint;
import xyz.erupt.k8s_dashboard.terminal.K8sTerminalTicketService;
import xyz.erupt.k8s_dashboard.terminal.K8sTerminalTplHandler;
import xyz.erupt.tpl.controller.EruptTplController;
import xyz.erupt.tpl.engine.EngineConst;
import xyz.erupt.upms.service.EruptTokenService;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dashboard against fabric8's CRUD mock API server, pointed there through the
 * erupt.k8s-dashboard.master-url placeholder every model carries: workspace scoping, menus, the
 * terminal grant and the terminal socket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KubeDashboardTest {

    private static final String MASTER_URL_PROPERTY = "erupt.k8s-dashboard.master-url";

    private static KubernetesMockServer server;

    private static KubernetesClient cluster;

    @Resource
    private EruptTokenService eruptTokenService;

    @Resource
    private EruptScopeService scopeService;

    @Resource
    private EruptService eruptService;

    @Resource
    private EruptTplController tplController;

    @Resource
    private K8sTerminalTplHandler terminalTplHandler;

    @Resource
    private K8sTerminalTicketService ticketService;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private String operatorToken;

    @BeforeAll
    static void startCluster() {
        server = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(), new KubernetesCrudDispatcher(), false);
        server.init();
        cluster = server.createClient();
        System.setProperty(MASTER_URL_PROPERTY, cluster.getConfiguration().getMasterUrl());
        cluster.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName("demo").endMetadata().build()).create();
        cluster.apps().deployments().inNamespace("demo").resource(new DeploymentBuilder()
                .withNewMetadata().withName("web").withNamespace("demo").endMetadata()
                .withNewSpec().withReplicas(2).withNewTemplate().withNewSpec().addNewContainer().withName("web")
                .withImage("nginx:1.27").endContainer().endSpec().endTemplate().endSpec()
                .withNewStatus().withReadyReplicas(2).endStatus().build()).create();
        cluster.pods().inNamespace("demo").resource(new PodBuilder()
                .withNewMetadata().withName("web-1").withNamespace("demo").endMetadata()
                .withNewSpec().withNodeName("node-a").addNewContainer().withName("web").withImage("nginx:1.27").endContainer().endSpec()
                .withNewStatus().withPhase("Running").endStatus().build()).create();
    }

    @AfterAll
    static void stopCluster() {
        System.clearProperty(MASTER_URL_PROPERTY);
        cluster.close();
        server.destroy();
    }

    @BeforeEach
    void admin() {
        // A session holding every dashboard menu: the table path checks menus like the page does
        if (null == operatorToken) {
            List<MetaMenu> menus = new ArrayList<>();
            for (Class<?> model : List.of(KubePod.class, KubeDeployment.class, KubeNamespace.class)) {
                menus.add(MetaMenu.createEruptClassMenu(model, null, 1));
            }
            operatorToken = this.login(-30L, menus);
        }
        this.workspace(KubeContextFetchHandler.CONFIGURED, null);
    }

    // A fresh request in the workspace a page would send; a null cluster sends no pick at all
    private void workspace(String cluster, String namespace) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (null != cluster) {
            JsonObject form = new JsonObject();
            form.addProperty("cluster", cluster);
            if (null != namespace) form.addProperty("namespace", namespace);
            request.addHeader(EruptScopeService.HEADER, URLEncoder.encode("{\"KubeScope\":" + form + "}", StandardCharsets.UTF_8));
        }
        request.addHeader("token", operatorToken);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MetaContext.remove();
        MetaContext.register(new MetaUser(-30L, "k8s-user-30", "k8s-user-30"));
        MetaContext.registerToken(operatorToken);
        // the header is read for the requested model only: a dashboard page
        MetaContext.register(new MetaErupt(KubePod.class.getSimpleName()));
    }

    @AfterEach
    void clear() {
        MetaContext.remove();
        RequestContextHolder.resetRequestAttributes();
    }

    private static EruptModel model(Class<?> clazz) {
        return EruptCoreService.getErupt(clazz.getSimpleName());
    }

    // Through EruptService, the path the table page takes: scope conditions apply there
    private Collection<Map<String, Object>> list(Class<?> clazz) {
        MetaContext.register(new MetaErupt(clazz.getSimpleName()));
        TableQuery query = new TableQuery();
        query.setPageIndex(1);
        query.setPageSize(100);
        return eruptService.getEruptData(model(clazz), query, null).getList();
    }

    private String login(long id, List<MetaMenu> menus) {
        MetaUserinfo user = new MetaUserinfo();
        user.setId(id);
        user.setAccount("k8s-user" + id);
        user.setUsername("k8s-user" + id);
        String token = Erupts.generateCode(22);
        eruptTokenService.loginToken(user, menus, token, 5);
        return token;
    }

    @Test
    void modelsReadTheConfiguredCluster() {
        Map<String, Object> pod = list(KubePod.class).iterator().next();
        assertEquals("demo/web-1", pod.get("qualifiedName"));
        assertEquals("Running", pod.get("phase"));
        assertEquals("node-a", pod.get("node"));
        assertEquals("nginx:1.27", pod.get("image"));

        Map<String, Object> web = list(KubeDeployment.class).iterator().next();
        assertEquals(2, web.get("replicas"));
        assertEquals(2, web.get("readyReplicas"));
        assertEquals("nginx:1.27", web.get("image"));

        assertTrue(list(KubeNamespace.class).stream().anyMatch(it -> "demo".equals(it.get("name"))));
    }

    @Test
    void workspacePicksTheNamespace() {
        cluster.pods().inNamespace("other").resource(new PodBuilder()
                .withNewMetadata().withName("other-1").withNamespace("other").endMetadata()
                .withNewSpec().addNewContainer().withName("c").withImage("busybox").endContainer().endSpec().build()).create();
        try {
            this.workspace(null, null);
            assertThrows(EruptWebApiRuntimeException.class, () -> list(KubePod.class), "no cluster picked: nothing is guessed");

            this.workspace(KubeContextFetchHandler.CONFIGURED, null);
            assertNull(EruptScopes.get(KubeScope.class).getNamespace(), "no namespace picked: every namespace");
            assertEquals(2, list(KubePod.class).size());

            this.workspace(KubeContextFetchHandler.CONFIGURED, "demo");
            assertEquals(List.of("demo/web-1"), list(KubePod.class).stream().map(it -> it.get("qualifiedName")).toList());

            JsonObject form = new JsonObject();
            form.addProperty("cluster", KubeContextFetchHandler.CONFIGURED);
            form.addProperty("namespace", "nowhere");
            assertThrows(EruptWebApiRuntimeException.class, () -> scopeService.accept(KubeScope.class, form), "only the cluster's namespaces");
        } finally {
            cluster.pods().inNamespace("other").withName("other-1").delete();
        }
    }

    @Test
    void terminalHasItsOwnButton() {
        List<MetaMenu> menus = new EruptK8sDashboardAutoConfiguration().initMenus();
        MetaMenu terminal = menus.stream().filter(it -> KubePod.TERMINAL.equals(it.getValue())).findFirst().orElseThrow();
        assertEquals(MenuTypeEnum.BUTTON.getCode(), terminal.getType());
        assertEquals(KubePod.class.getSimpleName(), terminal.getParentMenu().getValue());
    }

    @Test
    void terminalNeedsTheButton() throws Exception {
        MetaMenu pods = MetaMenu.createEruptClassMenu(KubePod.class, null, 1);
        MetaMenu button = MetaMenu.createSimpleMenu(KubePod.TERMINAL, "Terminal", KubePod.TERMINAL, pods, 1, MenuTypeEnum.BUTTON.getCode());
        String url = "/erupt-api/tpl/operation-tpl/KubePod/exec?ids=demo/web-1&_erupt=KubePod&_token=";

        String viewer = this.login(-31L, List.of(pods));
        ResponseEntity<String> refused = rest.getForEntity(url + viewer, String.class);
        assertFalse(String.valueOf(refused.getBody()).contains("ticket:"), "pods alone do not open a shell");

        // The page reads its pod, so it needs a workspace: a tpl is opened by URL and carries it as
        // _scope. This app has no security interceptor to register the requested erupt from the
        // URL, so the request runs in this thread with the context that interceptor would leave
        String operator = this.login(-32L, List.of(pods, button));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(EruptScopeService.PARAM, URLEncoder.encode("{\"KubeScope\":{\"cluster\":\""
                + KubeContextFetchHandler.CONFIGURED + "\"}}", StandardCharsets.UTF_8));
        request.addHeader("token", operator);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        MetaContext.remove();
        MetaContext.register(new MetaUser(-32L, "k8s-user-32", "k8s-user-32"));
        MetaContext.registerToken(operator);
        MetaContext.register(new MetaErupt(KubePod.class.getSimpleName()));
        tplController.operationTpl(KubePod.class.getSimpleName(), "exec", new String[]{"demo/web-1"}, response);
        String page = response.getContentAsString();
        assertTrue(page.contains("ticket:"), "the page carries a ticket per container");
        assertTrue(page.contains("/erupt-k8s-dashboard/xterm/xterm.js"), "xterm is served from the jar, not a CDN");
        assertEquals(200, rest.getForEntity("/erupt-k8s-dashboard/xterm/xterm.js", String.class).getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void terminalTicketsAreBoundAndSingleUse() {
        Object pod = DataProcessorManager.getEruptDataProcessor(KubePod.class).findDataById(model(KubePod.class), "demo/web-1");
        Map<String, Object> binding = new HashMap<>();
        binding.put(EngineConst.INJECT_ROWS, List.of(pod));
        terminalTplHandler.bindTplData(binding, new String[]{"logs"});
        assertEquals("logs", binding.get("mode"));
        List<Map<String, String>> containers = (List<Map<String, String>>) binding.get("containers");
        assertEquals("web", containers.get(0).get("name"));

        K8sTerminalTicketService.Ticket ticket = ticketService.consume(containers.get(0).get("ticket"));
        assertNotNull(ticket);
        assertEquals("demo", ticket.namespace(), "the pod comes from the server-checked row, not the browser");
        assertEquals("web-1", ticket.pod());
        assertEquals(operatorToken, ticket.token());
        assertEquals(K8sTerminalTicketService.Mode.LOGS, ticket.mode());
        assertNull(ticketService.consume(containers.get(0).get("ticket")), "a ticket opens one socket");
    }

    // Opens the terminal socket and returns the close code plus any text the server sent first
    private String[] openTerminal(String ticket) throws Exception {
        CompletableFuture<String[]> result = new CompletableFuture<>();
        StringBuilder received = new StringBuilder();
        HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + K8sTerminalEndpoint.PATH + "?ticket=" + ticket), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        received.append(StandardCharsets.UTF_8.decode(data));
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        result.complete(new String[]{String.valueOf(statusCode), received.toString()});
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        result.complete(new String[]{"error", error.getMessage()});
                    }
                }).get(5, TimeUnit.SECONDS);
        return result.get(10, TimeUnit.SECONDS);
    }

    @Test
    void terminalSocketRefusesBadTickets() throws Exception {
        assertEquals("1008", this.openTerminal("not-a-ticket")[0]);

        // A real ticket whose session has ended is refused too
        String stale = ticketService.issue("logged-out-token", KubePod.class, null, "demo", "web-1", "web", K8sTerminalTicketService.Mode.LOGS);
        assertEquals("1008", this.openTerminal(stale)[0]);
    }

    @Test
    void terminalSocketReachesThePod() throws Exception {
        String ticket = ticketService.issue(operatorToken, KubePod.class, null, "demo", "web-1", "web", K8sTerminalTicketService.Mode.EXEC);
        // The CRUD mock server has no exec support, so the bridge opens, reports the failure and closes
        String[] outcome = this.openTerminal(ticket);
        assertEquals("1011", outcome[0], Arrays.toString(outcome));
        assertFalse(outcome[1].isBlank(), "the failure is shown in the terminal");
    }

}
