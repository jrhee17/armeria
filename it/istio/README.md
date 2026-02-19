# XDS Istio integration tests

This module contains integration tests that exercise Armeria's xDS support in an Istio + Docker environment.

## Running tests

### Local Kind (default)

```
./gradlew :it:istio:test --tests '*K8sStartupTest'
```

### Local Kind with Istio

```
./gradlew :it:istio:test --tests '*IstioStartupTest'
```

Gradle sets the `KUBECONFIG_PATH` environment variable for this module to the default path:

```
it/istio/build/kubeconfig/kubeconfig.yaml
```

If the kubeconfig file exists, tests reuse it and skip creating a new cluster.
Gradle downloads `istioctl` via `:it:istio:prepareIstioctl` (also wired into `assemble`)
and sets `ISTIOCTL_PATH` and `ISTIO_PROFILE` (`minimal`) for all Java tasks. Istio's version
is pinned in `dependencies.toml` and exported as `ISTIO_VERSION`. If you run `IstioMain`
outside Gradle, set `ISTIOCTL_PATH`, `ISTIO_VERSION`, and `ISTIO_PROFILE` yourself.
