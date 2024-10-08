package io.apicurio.registry.operator.resource.ui;

import io.apicurio.registry.operator.api.v1.ApicurioRegistry3;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static io.apicurio.registry.operator.Mapper.toYAML;
import static io.apicurio.registry.operator.resource.ResourceFactory.COMPONENT_UI;
import static io.apicurio.registry.operator.resource.ResourceKey.*;

@KubernetesDependent(labelSelector = "app.kubernetes.io/name=apicurio-registry,app.kubernetes.io/component="
        + COMPONENT_UI, resourceDiscriminator = UIDeploymentDiscriminator.class)
public class UIDeploymentResource extends CRUDKubernetesDependentResource<Deployment, ApicurioRegistry3> {

    private static final Logger log = LoggerFactory.getLogger(UIDeploymentResource.class);

    public UIDeploymentResource() {
        super(Deployment.class);
    }

    @Override
    protected Deployment desired(ApicurioRegistry3 primary, Context<ApicurioRegistry3> context) {

        var d = UI_DEPLOYMENT_KEY.getFactory().apply(primary);

        var uiEnv = new ArrayList<>(List.of(
                // spotless:off
                new EnvVarBuilder().withName("REGISTRY_API_URL").withValue("TODO").build()
                // spotless:on
        ));

        d.getSpec().getTemplate().getSpec().getContainers().get(0).setEnv(uiEnv);

        log.debug("Desired {} is {}", UI_DEPLOYMENT_KEY.getId(), toYAML(d));

        return d;
    }
}
