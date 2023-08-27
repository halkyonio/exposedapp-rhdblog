package io.halkyon;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.javaoperatorsdk.operator.OperatorException;
import io.javaoperatorsdk.operator.api.config.informer.InformerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.processing.event.Event;
import io.javaoperatorsdk.operator.processing.event.EventHandler;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

@ControllerConfiguration(name = "exposedapp", namespaces = Constants.WATCH_CURRENT_NAMESPACE)
public class ExposedAppReconciler implements Reconciler<ExposedApp>, EventSourceInitializer<ExposedApp> {

    static final Logger log = LoggerFactory.getLogger(ExposedAppReconciler.class);
    static final String APP_LABEL = "app.kubernetes.io/name";
    private final KubernetesClient client;

    private static final ExposedAppStatus DEFAULT_STATUS = new ExposedAppStatus("processing", null);

    public ExposedAppReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public UpdateControl<ExposedApp> reconcile(ExposedApp exposedApp, Context context) {
        final var labels = Map.of(APP_LABEL, exposedApp.getMetadata().getName());
        final var name = exposedApp.getMetadata().getName();
        final var spec = exposedApp.getSpec();
        final var imageRef = spec.getImageRef();
        final var metadata = createMetadata(exposedApp, labels);

        // @formatter:off
    log.info("Create deployment {}", metadata.getName());
    final var deployment = new DeploymentBuilder()
        .withMetadata(createMetadata(exposedApp, labels))
        .withNewSpec()
          .withNewSelector().withMatchLabels(labels).endSelector()
          .withNewTemplate()
            .withNewMetadata().withLabels(labels).endMetadata()
            .withNewSpec()
              .addNewContainer()
                .withName(name).withImage(imageRef)
                .addNewPort().withName("http").withProtocol("TCP").withContainerPort(8080).endPort()
              .endContainer()
            .endSpec()
          .endTemplate()
        .endSpec()
        .build();
    client.apps().deployments().createOrReplace(deployment);

    log.info("Create service {}", metadata.getName());
    client.services().createOrReplace(new ServiceBuilder()
        .withMetadata(createMetadata(exposedApp,labels))
        .withNewSpec()
          .addNewPort()
            .withName("http")
            .withPort(8080)
            .withNewTargetPort(8080)
          .endPort()
          .withSelector(labels)
          .withType("ClusterIP")
        .endSpec()
        .build());

    log.info("Create ingress {}", metadata.getName());
    metadata.setAnnotations(Map.of(
        "nginx.ingress.kubernetes.io/rewrite-target", "/",
        "kubernetes.io/ingress.class", "nginx"
    ));
    final var ingress = client.network().v1().ingresses().createOrReplace(new IngressBuilder()
        .withMetadata(metadata)
        .withNewSpec()
          .addNewRule()
            .withNewHttp()
              .addNewPath()
                .withPath("/")
                .withPathType("Prefix")
                .withNewBackend()
                  .withNewService()
                    .withName(metadata.getName())
                    .withNewPort().withNumber(8080).endPort()
                  .endService()
                .endBackend()
              .endPath()
            .endHttp()
          .endRule()
        .endSpec()
        .build());
    // @formatter:on

        final var maybeStatus = ingress.getStatus();
        final var status = Optional.ofNullable(maybeStatus).map(s -> {
            var result = DEFAULT_STATUS;
            final var ingresses = s.getLoadBalancer().getIngress();
            if (ingresses != null && !ingresses.isEmpty()) {
                // only set the status if the ingress is ready to provide the info we need
                var ing = ingresses.get(0);
                String hostname = ing.getHostname();
                final var url = "https://" + (hostname != null ? hostname : ing.getIp());
                log.info("App {} is exposed and ready to used at {}", name, url);
                result = new ExposedAppStatus("exposed", url);
            }
            return result;
        }).orElse(DEFAULT_STATUS);

        exposedApp.setStatus(status);
        return UpdateControl.updateStatus(exposedApp);
    }

    private ObjectMeta createMetadata(ExposedApp resource, Map<String, String> labels) {
        final var metadata = resource.getMetadata();
        return new ObjectMetaBuilder()
                .withName(metadata.getName())
                .addNewOwnerReference()
                .withUid(metadata.getUid())
                .withApiVersion(resource.getApiVersion())
                .withName(metadata.getName())
                .withKind(resource.getKind())
                .endOwnerReference()
                .withLabels(labels)
                .build();
    }

    @Override
    public Map<String, EventSource> prepareEventSources(EventSourceContext<ExposedApp> eventSourceContext) {
        return EventSourceInitializer.nameEventSources(new InformerEventSource<>(InformerConfiguration.from(Ingress.class).build(), eventSourceContext));  
    }
}

