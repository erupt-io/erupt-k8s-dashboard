# AGENTS.md

This file provides guidance to coding agents (Claude Code, Codex, ...) when working with code in this repository.

## Commands

```bash
# Build and run the tests (what CI runs, .github/workflows/maven.yml, JDK 17)
mvn -B verify

# Tests only / a single test method (there is one test class)
mvn test
mvn test -Dtest=KubeDashboardTest#workspacePicksTheNamespace

# Run the dashboard on its own: http://localhost:8080, erupt / erupt
mvn spring-boot:run
```

Java 17, system `mvn` (no wrapper). The parent pom is `spring-boot-starter-parent` (the Spring Boot version
erupt is built on), not `xyz.erupt:erupt`: the erupt parent published to Maven Central is flattened and
carries no dependency management. The erupt version is the `erupt.version` property; erupt 2.4.0 is on
Maven Central, so a fresh clone builds without the erupt checkout.

## What this is

A library, not an application: a ready-made Kubernetes admin for erupt. A host application adds the
dependency and gets a **Kubernetes** menu (Pods, Deployments, Stateful Sets, Daemon Sets, Services,
Config Maps, Events, Namespaces, Nodes) with logs, a web terminal, scaling, restarts and YAML editing.
It is built on `erupt-data-k8s` (in the erupt repo), which owns the data processor, the fabric8 client
and the generic row operations; custom resources are modelled there with `@EruptK8s`, not here.

`spring-boot-maven-plugin` is kept only for `spring-boot:run`; repackaging is switched off, and
`erupt-spring-boot-starter` is `provided` because the host brings the erupt stack.

## Architecture

- **Registration.** `EruptK8sDashboardAutoConfiguration` is listed in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, implements
  `EruptModule` and builds the menu tree in `initMenus()` (root `$k8s`). Its `@ComponentScan` excludes
  `@SpringBootApplication` classes, because the standalone launcher lives in the same package.
- **Standalone launcher.** `EruptK8sDashboardApplication` sets default properties only (an H2 file
  database under `./h2`, DDL generation, quoted identifiers), so a config file or command line argument
  still wins. It reads the clusters of `~/.kube/config`. `h2/` and `.erupt/` are git-ignored.
- **Models (`model/`).** Plain classes without JPA: `@Erupt` + `@EruptK8s(apiVersion, kind, ...)` +
  `@EruptDataProcessor(EruptK8sDataService.DATA_PROCESSOR)`, fields mapped with `@EruptK8sField("json.path")`.
  Every model carries the connection placeholders of `KubeCluster` (`erupt.k8s-dashboard.master-url`,
  `.kubeconfig`, `.token`); all empty selects the standard client chain (`KUBECONFIG`, `~/.kube/config`,
  in-cluster service account). Never put a token value in a file: refer to the property name.
- **Workspace (`scope/`).** `KubeScope` is an `@EruptScope` form (cluster, optional namespace) shown in the
  page header and sent with each request in the `erupt-scope` header. `KubeContextFetchHandler` lists the
  kubeconfig contexts, `KubeScopeNamespaceFetchHandler` the namespaces (cached per cluster for 30 seconds),
  and `KubeScopeContextResolver` (a `K8sContextResolver`) points the client at the picked cluster, only
  for models that carry the dashboard's `master-url` placeholder, so a host's own `@EruptK8s` models are
  left alone.
- **Web terminal (`terminal/`).** A FreeMarker row-operation template (`tpl/erupt-k8s-terminal.ftl`,
  `K8sTerminalTplHandler`) with xterm.js bundled under `public/erupt-k8s-dashboard/xterm/`. The socket
  `/erupt-k8s-terminal` (`K8sTerminalEndpoint`) only accepts one-time tickets from
  `K8sTerminalTicketService` (60 seconds, bound to session, pod and container). `K8sTerminalConfiguration`
  registers it only when WebSocket support and erupt-upms are on the classpath. The terminal is a
  separate button permission, `KubePod.TERMINAL`, registered as a button menu under Pods.
- **i18n.** Labels are English keys translated in `src/main/resources/i18n/erupt-k8s-dashboard.i18n.csv`.

## Tests

`KubeDashboardTest` is a `@SpringBootTest` on a random port against fabric8's `KubernetesMockServer`
(CRUD mode), reached through the `erupt.k8s-dashboard.master-url` property, on an in-memory H2
(`src/test/resources/application.yml`). It covers workspace scoping, menus, the terminal grant, tickets
and the terminal socket.

## Behaviour worth knowing (from README.md)

- Each list call reads up to 1000 items and pages them in memory; namespace and label selector are
  applied by the API server.
- Editing from YAML is a replace with the manifest's `resourceVersion`, so a stale edit fails instead
  of overwriting.
- Setting `master-url` without `kubeconfig` pins one cluster and ignores `~/.kube/config`.
