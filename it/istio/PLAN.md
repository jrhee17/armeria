# Plan: Refactor Container Management Logic

## Overview
Move container existence checking from Java to Gradle while keeping all container lifecycle operations in Java for consistency between tests and main execution.

## Current State
- `IstioMain.java` handles both checking if containers exist AND starting/stopping them
- This leads to duplicate Docker process execution in Java
- Both tests and main need to use the same container management logic

## Proposed Architecture

### Gradle Responsibilities (Orchestration)
- Check if metadata file exists at `build/kubeconfig/istio-kind.metadata`
- Read container ID from metadata file
- Execute `docker inspect` to verify container is still running
- Decide whether to invoke Java code based on container state
- For `stopIstio`: Read metadata and pass container ID to Java

### Java Responsibilities (Execution)
- Start Kind container via `K8sClusterHelper`
- Install Istio via `IstioInstaller`
- Write metadata file with container ID after successful start
- Handle all Kubernetes client operations
- Execute docker commands for stop operations

## Implementation Steps

### 1. Update Gradle build.gradle
```gradle
// Check if container exists and is running
tasks.register('checkIstioContainer') {
    doLast {
        if (metadataPath.exists()) {
            def containerId = metadataPath.text.trim()
            def result = exec {
                commandLine 'docker', 'inspect', '-f', '{{.State.Running}}', containerId
                ignoreExitValue = true
                standardOutput = new ByteArrayOutputStream()
            }
            ext.containerRunning = result.exitValue == 0 && standardOutput.toString().trim() == 'true'
        } else {
            ext.containerRunning = false
        }
    }
}

// Start task that skips if container already running
tasks.register('startIstio', JavaExec) {
    dependsOn 'checkIstioContainer'
    onlyIf { !tasks.checkIstioContainer.containerRunning }
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.linecorp.armeria.xds.it.IstioMain'
    args 'start'
}

// Stop task that always delegates to Java
tasks.register('stopIstio', JavaExec) {
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.linecorp.armeria.xds.it.IstioMain'
    args 'stop'
}
```

### 2. Keep IstioMain.java Logic
- Keep the current `start()` method that checks metadata and starts container
- Keep the current `stop()` method that reads metadata and stops container
- Remove duplicate checking since Gradle will handle the initial check

### 3. Benefits
- **Consistency**: Tests and main use identical Java code path
- **Separation of Concerns**: Gradle orchestrates, Java executes
- **Container Reuse**: Works seamlessly for both manual runs and tests
- **Single Source of Truth**: Java owns container lifecycle and metadata

## Key Design Decision
The metadata file (`istio-kind.metadata`) serves as the contract:
- **Java writes it** when creating a container
- **Gradle reads it** to check container state
- **Both read it** for stop operations

This ensures that container management logic remains consistent regardless of how it's invoked.