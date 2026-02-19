# Istio Integration Testing

This module provides integration tests for Istio service mesh functionality.

## Test Overview

### IstioStartupTest
- Verifies that Istio control plane (istiod) is properly installed and running
- Basic health check for the Istio installation

### IstioSidecarCommunicationTest  
- **Purpose**: Tests end-to-end communication between two services through Istio service mesh
- **Setup**: 
  - Creates a test namespace with Istio sidecar injection enabled
  - Deploys an echo server (httpbin) service 
  - Deploys a client service with curl
- **Verification**:
  - Verifies both pods have Istio proxy sidecars injected
  - Tests HTTP communication between client and server through the mesh
  - Validates that traffic flows through Istio's service discovery and load balancing

### IstioReinstallExampleTest (Disabled by default)
- Example showing how to use `runForEachTest(true)` for tests requiring fresh Istio installations
- Useful for testing Istio configuration changes or ensuring complete isolation

## Infrastructure

### IstioClusterExtension
JUnit extension that manages Kubernetes cluster lifecycle with Istio:
- **Default**: Reuses existing cluster/Istio installation across all tests  
- **Builder options**:
  - `runForEachTest(true)`: Reinstalls Istio before each test (slower but provides isolation)
  - `istioProfile(profile)`: Customize Istio installation profile

### IstioMain 
- Standalone utility to start a Kind cluster with Istio
- Idempotent - checks for existing containers and returns early if found
- Usage: `./gradlew :it:istio:startIstio`

## Environment Configuration

- `ISTIO_PROFILE`: Istio installation profile (default from environment)
- Tests run sequentially to avoid conflicts (configured in build.gradle)

## Key Benefits

1. **Service Mesh Validation**: Verifies that Istio's core functionality works end-to-end
2. **Sidecar Injection**: Confirms automatic sidecar injection in labeled namespaces  
3. **Traffic Management**: Tests service discovery and communication through Istio proxy
4. **Integration Testing**: Provides confidence that Istio setup works with real workloads
